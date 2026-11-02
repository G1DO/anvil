package com.g1do.anvil.extract;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.eval.RulesBaseline;
import com.g1do.anvil.extract.Extractor.Outcome;
import com.g1do.anvil.extract.Extractor.Prepared;
import com.g1do.anvil.extract.Extractor.Quarantined;
import com.g1do.anvil.extract.Extractor.Reason;
import com.g1do.anvil.submit.CanonicalCbor;
import com.g1do.anvil.tenant.TenantGuard;

/**
 * Tenant-scoped extraction over stored documents (#37, record 0022), with a
 * cache, a budget, a kill-switch and a rules fallback (#38, record 0023).
 *
 * <ul>
 * <li>Every read and write sets the transaction-local tenant through
 * {@link TenantGuard#setTenant}, keeps {@code WHERE tenant_id = ?}, and runs
 * under RLS {@code FORCE}. A document of another tenant, a missing one and a
 * forged ID all give {@link Optional#empty()}, before any model call.</li>
 * <li>A version's result is keyed by tenant, version, prompt hash and model,
 * and a repeat request returns it. A new prompt or model extracts afresh.</li>
 * <li>A version without a result is decided in this order, each step only if
 * the one before didn't decide it:
 * <ol>
 * <li>the screens, with no model call (poison is quarantined);</li>
 * <li>the kill-switch: when it is off, the rules answer;</li>
 * <li>the cache: an earlier accepted, unrevoked model result of the same
 * tenant for the same bounded text, prompt and model, whose every fact passes
 * the checks again, is copied with no model call;</li>
 * <li>the budget: a call takes one from the tenant's budget, committed before
 * the call; with none left, the rules answer;</li>
 * <li>the model.</li>
 * </ol>
 * A rules result is marked as fallback, with its reason, and is stored under
 * the rules' own key, so once calls are allowed again the model can still
 * extract the version.</li>
 * <li>A revoked result is never served or reused. A version whose model result
 * is revoked is answered by the rules, with no model call.</li>
 * <li>Concurrent requests for the same version in this process share one
 * decision: the first decides, the others wait for its result. Across
 * processes each may spend a call; the budget bounds that, and the unique key
 * keeps one result.</li>
 * <li>The model is asked outside any transaction. Its outcome is written in
 * one transaction: the extraction row, the accepted facts, and the dead
 * letters. Dead letters are written here; routes report only how many there
 * are.</li>
 * </ul>
 */
@Service
public class ExtractionService {

    /** Why the rules answered instead of the model. */
    public enum FallbackReason {
        KILL_SWITCH, BUDGET_EXHAUSTED, REVOKED
    }

    /** Where a result came from. */
    public enum Source {
        MODEL, CACHE, RULES
    }

    /** At most this many cache entries are checked for one lookup. */
    static final int CACHE_CANDIDATES = 3;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Extractor extractor;
    private final ConcurrentHashMap<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    public ExtractionService(JdbcTemplate jdbc, PlatformTransactionManager txManager, Extractor extractor) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.extractor = extractor;
    }

    /**
     * The stored result of one extraction, as the API reports it.
     * {@code fallbackReason} is set exactly when {@code source} is RULES.
     */
    public record Result(UUID extractionId, UUID documentId, UUID versionId, String status, int facts,
            int quarantined, boolean truncated, Source source, FallbackReason fallbackReason) {

        public boolean fallback() {
            return source == Source.RULES;
        }
    }

    /** The facts served for a document: only those that passed every check. */
    public record Facts(UUID documentId, UUID versionId, String status, Source source, List<Fact> facts) {

        public boolean fallback() {
            return source == Source.RULES;
        }
    }

    private record Version(UUID documentId, UUID versionId, String mimeType) {
    }

    private record Stored(Result result, boolean revoked) {
    }

    /** A version's stored model result and rules result, either possibly absent. */
    private record Lookup(Version version, Optional<Stored> model, Optional<Stored> rules) {

        Optional<Result> served() {
            return model.filter(s -> !s.revoked()).map(Stored::result);
        }
    }

    private record Hit(Outcome outcome, UUID original) {
    }

    /**
     * Extracts the latest version of the caller's document, or returns its
     * stored result. Empty when the caller can't see the document. Throws
     * {@link ModelUnavailableException} when the model can't be asked; nothing
     * is stored then, and the call still counts against the budget.
     */
    public Optional<Result> extract(String tenant, UUID documentId) {
        Optional<Lookup> found = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return latestVersion(tenant, documentId).map(v -> lookupInTx(tenant, v));
        });
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (found.get().served().isPresent()) {
            return found.get().served();
        }
        Version v = found.get().version();

        String key = tenant + "\u0000" + v.versionId();
        CompletableFuture<Result> mine = new CompletableFuture<>();
        CompletableFuture<Result> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) {
            try {
                return Optional.of(leader.join());
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw e;
            }
        }
        try {
            // A request that finished between the lookup and the claim has stored its result.
            Lookup now = lookup(tenant, v);
            Result r = now.served().orElseGet(() -> decide(tenant, now));
            mine.complete(r);
            return Optional.of(r);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Result decide(String tenant, Lookup l) {
        Version v = l.version();
        Prepared p = prepare(tenant, v);
        if (l.model().isPresent()) {
            // The model result is revoked: never serve it, and don't spend a call on this version.
            return fallback(tenant, l, p, FallbackReason.REVOKED);
        }
        if (!p.ready()) {
            return store(tenant, v, p.screened(), Source.MODEL, extractor.modelId(), Prompt.SHA256, null, null,
                    null);
        }
        if (!modelCallsEnabled()) {
            return fallback(tenant, l, p, FallbackReason.KILL_SWITCH);
        }
        Optional<Hit> hit = cached(tenant, p);
        if (hit.isPresent()) {
            return store(tenant, v, hit.get().outcome(), Source.CACHE, extractor.modelId(), Prompt.SHA256,
                    p.contentSha256(), hit.get().original(), null);
        }
        if (!spendOneCall(tenant)) {
            return fallback(tenant, l, p, FallbackReason.BUDGET_EXHAUSTED);
        }
        Outcome o = extractor.ask(p);
        return store(tenant, v, o, Source.MODEL, extractor.modelId(), Prompt.SHA256, p.contentSha256(), null, null);
    }

    /** The rules' answer for the version, stored once under the rules' key; never a model call. */
    private Result fallback(String tenant, Lookup l, Prepared p, FallbackReason reason) {
        if (l.rules().isPresent()) {
            return l.rules().get().result();
        }
        Outcome o = p.ready() ? extractor.rules(p) : p.screened();
        return store(tenant, l.version(), o, Source.RULES, RulesBaseline.ID, RulesBaseline.SHA256,
                p.contentSha256(), null, reason);
    }

    /** Whether the kill-switch allows model calls; a missing switch row reads as off. */
    private boolean modelCallsEnabled() {
        List<Boolean> on = jdbc.queryForList("SELECT model_calls_enabled FROM extraction_switch WHERE id",
                Boolean.class);
        return !on.isEmpty() && Boolean.TRUE.equals(on.get(0));
    }

    /**
     * Takes one call from the tenant's budget, committed before the call, so a
     * crash or an outage after it still counts. False when the tenant has no
     * budget row or none left.
     */
    private boolean spendOneCall(String tenant) {
        Integer n = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return jdbc.update("UPDATE extraction_budgets SET calls_remaining = calls_remaining - 1, "
                    + "calls_used = calls_used + 1, updated_at = now() "
                    + "WHERE tenant_id = ? AND calls_remaining > 0", tenant);
        });
        return n != null && n == 1;
    }

    /**
     * An accepted, unrevoked model result of this tenant for the same bounded
     * text, prompt and model whose facts all pass the checks again. A
     * quarantined entry, a revoked one, and one with a fact that fails are
     * never reused.
     */
    private Optional<Hit> cached(String tenant, Prepared p) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<UUID> candidates = jdbc.queryForList(
                    "SELECT e.id FROM extractions e WHERE e.tenant_id = ? AND e.content_sha256 = ? "
                            + "AND e.prompt_sha256 = ? AND e.model = ? AND e.source = 'MODEL' "
                            + "AND e.status = 'ACCEPTED' AND NOT EXISTS (SELECT 1 FROM extraction_revocations r "
                            + "WHERE r.tenant_id = e.tenant_id AND r.extraction_id = e.id) "
                            + "ORDER BY e.created_at DESC, e.id LIMIT " + CACHE_CANDIDATES,
                    UUID.class, tenant, p.contentSha256(), Prompt.SHA256, extractor.modelId());
            for (UUID id : candidates) {
                Optional<Outcome> o = extractor.reuse(p, factsOf(tenant, id));
                if (o.isPresent()) {
                    return Optional.of(new Hit(o.get(), id));
                }
            }
            return Optional.<Hit>empty();
        });
    }

    private Result store(String tenant, Version v, Outcome outcome, Source source, String model, String promptSha,
            String contentSha, UUID cacheOf, FallbackReason reason) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<UUID> inserted = jdbc.queryForList(
                    "INSERT INTO extractions (tenant_id, document_id, version_id, model, prompt_sha256, status, "
                            + "truncated, source, content_sha256, cache_of, fallback_reason) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, version_id, prompt_sha256, model) DO NOTHING RETURNING id",
                    UUID.class, tenant, v.documentId(), v.versionId(), model, promptSha, outcome.status().name(),
                    outcome.truncated(), source.name(), contentSha, cacheOf, reason == null ? null : reason.name());
            if (inserted.isEmpty()) {
                // Another process stored this key first; its result stands.
                return storedInTx(tenant, v.versionId(), promptSha, model).orElseThrow().result();
            }
            UUID id = inserted.get(0);
            int ordinal = 0;
            for (Fact f : outcome.accepted()) {
                jdbc.update("INSERT INTO extracted_facts (tenant_id, extraction_id, ordinal, field, value, citation) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", tenant, id, ordinal++, f.field(), f.value(), f.citation());
            }
            for (Quarantined q : outcome.quarantined()) {
                Fact f = q.fact();
                jdbc.update("INSERT INTO extraction_dead_letters (tenant_id, extraction_id, reason, field, value, citation, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", tenant, id, q.reason().name(),
                        storable(f == null ? null : f.field()), storable(f == null ? null : f.value()),
                        storable(f == null ? null : f.citation()), storable(q.detail()));
            }
            return new Result(id, v.documentId(), v.versionId(), outcome.status().name(), outcome.accepted().size(),
                    outcome.quarantined().size(), outcome.truncated(), source, reason);
        });
    }

    /** Reads the version's stored body, only now that a decision is needed, and prepares it. */
    private Prepared prepare(String tenant, Version v) {
        List<byte[]> rows = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return jdbc.query("SELECT canonical_cbor FROM document_versions WHERE id = ? AND tenant_id = ?",
                    (rs, i) -> rs.getBytes(1), v.versionId(), tenant);
        });
        byte[] cbor = rows.isEmpty() ? null : rows.get(0);
        if (cbor == null) {
            return new Prepared(null, null, Extractor.quarantine(Reason.EMPTY_TEXT, "version has no stored body"));
        }
        CanonicalCbor.CanonicalForm form;
        try {
            form = CanonicalCbor.decode(cbor);
        } catch (IllegalStateException e) {
            return new Prepared(null, null, Extractor.quarantine(Reason.NOT_TEXT, "stored body can't be decoded"));
        }
        // The version's own media type, which can't change, over the document row's.
        String mime = form.mime_type != null ? form.mime_type : v.mimeType();
        return extractor.prepare(form.body, mime);
    }

    /**
     * Model output kept in a dead letter can hold U+0000, which Postgres text
     * can't store; it is replaced so the quarantine is still written.
     */
    static String storable(String s) {
        return s == null ? null : s.replace('\u0000', '�');
    }

    /**
     * The indexed facts of the caller's document's latest version. Empty when
     * the caller can't see the document. The unrevoked model result is served
     * when there is one, otherwise the rules fallback, marked as such;
     * otherwise the status is {@code NOT_EXTRACTED}. A quarantined result has
     * no facts.
     */
    public Optional<Facts> facts(String tenant, UUID documentId) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            Optional<Version> found = latestVersion(tenant, documentId);
            if (found.isEmpty()) {
                return Optional.<Facts>empty();
            }
            Version v = found.get();
            Lookup l = lookupInTx(tenant, v);
            Optional<Result> served = l.served().or(() -> l.rules().map(Stored::result));
            if (served.isEmpty()) {
                return Optional.of(new Facts(v.documentId(), v.versionId(), "NOT_EXTRACTED", null, List.of()));
            }
            Result r = served.get();
            List<Fact> facts = "ACCEPTED".equals(r.status()) ? factsOf(tenant, r.extractionId()) : List.of();
            return Optional.of(new Facts(v.documentId(), v.versionId(), r.status(), r.source(), facts));
        });
    }

    private List<Fact> factsOf(String tenant, UUID extractionId) {
        return jdbc.query("SELECT field, value, citation FROM extracted_facts "
                + "WHERE tenant_id = ? AND extraction_id = ? ORDER BY ordinal",
                (rs, i) -> new Fact(rs.getString(1), rs.getString(2), rs.getString(3)), tenant, extractionId);
    }

    /** The latest version's IDs and media type; the body isn't read here. */
    private Optional<Version> latestVersion(String tenant, UUID documentId) {
        List<Version> rows = jdbc.query(
                "SELECT d.id, v.id, d.mime_type FROM documents d "
                        + "JOIN document_versions v ON v.tenant_id = d.tenant_id AND v.document_id = d.id "
                        + "WHERE d.id = ? AND d.tenant_id = ? AND v.tenant_id = ? "
                        + "ORDER BY v.version_number DESC LIMIT 1",
                (rs, i) -> new Version(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3)),
                documentId, tenant, tenant);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private Lookup lookup(String tenant, Version v) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return lookupInTx(tenant, v);
        });
    }

    private Lookup lookupInTx(String tenant, Version v) {
        return new Lookup(v, storedInTx(tenant, v.versionId(), Prompt.SHA256, extractor.modelId()),
                storedInTx(tenant, v.versionId(), RulesBaseline.SHA256, RulesBaseline.ID));
    }

    /**
     * The result stored for one key, and whether it is revoked: a result is
     * revoked when it, or the original it was copied from, has a revocation.
     */
    private Optional<Stored> storedInTx(String tenant, UUID versionId, String promptSha, String model) {
        List<Stored> rows = jdbc.query(
                "SELECT e.id, e.document_id, e.version_id, e.status, e.truncated, "
                        + "(SELECT count(*) FROM extracted_facts f WHERE f.tenant_id = e.tenant_id AND f.extraction_id = e.id), "
                        + "(SELECT count(*) FROM extraction_dead_letters q WHERE q.tenant_id = e.tenant_id AND q.extraction_id = e.id), "
                        + "e.source, e.fallback_reason, "
                        + "EXISTS (SELECT 1 FROM extraction_revocations r WHERE r.tenant_id = e.tenant_id "
                        + "AND r.extraction_id IN (e.id, e.cache_of)) "
                        + "FROM extractions e WHERE e.tenant_id = ? AND e.version_id = ? AND e.prompt_sha256 = ? "
                        + "AND e.model = ?",
                (rs, i) -> new Stored(new Result(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(6), rs.getInt(7), rs.getBoolean(5),
                        Source.valueOf(rs.getString(8)),
                        rs.getString(9) == null ? null : FallbackReason.valueOf(rs.getString(9))), rs.getBoolean(10)),
                tenant, versionId, promptSha, model);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
