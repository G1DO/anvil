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
import com.g1do.anvil.extract.Extractor.Outcome;
import com.g1do.anvil.extract.Extractor.Quarantined;
import com.g1do.anvil.extract.Extractor.Reason;
import com.g1do.anvil.submit.CanonicalCbor;
import com.g1do.anvil.tenant.TenantGuard;

/**
 * Tenant-scoped extraction over stored documents (#37, record 0022).
 *
 * <ul>
 * <li>Every read and write sets the transaction-local tenant through
 * {@link TenantGuard#setTenant}, keeps {@code WHERE tenant_id = ?}, and runs
 * under RLS {@code FORCE}. A document of another tenant, a missing one and a
 * forged ID all give {@link Optional#empty()}, before any model call.</li>
 * <li>A result is keyed by tenant, version, prompt hash and model. The key
 * holds the tenant, so identical content in two tenants is extracted twice: no
 * cross-tenant dedup, cache or result. A new prompt or model extracts a version
 * afresh; results from another prompt or model are kept but not served.</li>
 * <li>Concurrent requests for the same key in this process share one model
 * call: the first asks, the others wait for its result. Across processes the
 * unique key decides: if another request committed first, its result is
 * returned and this one is dropped.</li>
 * <li>The model is asked outside any transaction. Its outcome is written in
 * one transaction: the extraction row, the accepted facts, and the dead
 * letters.</li>
 * <li>A version whose stored body is missing or can't be decoded is
 * quarantined without a model call.</li>
 * <li>Dead letters are written here; routes report only how many there are.</li>
 * </ul>
 */
@Service
public class ExtractionService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Extractor extractor;
    private final ConcurrentHashMap<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    public ExtractionService(JdbcTemplate jdbc, PlatformTransactionManager txManager, Extractor extractor) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.extractor = extractor;
    }

    /** The stored result of one extraction, as the API reports it. */
    public record Result(UUID extractionId, UUID documentId, UUID versionId, String status, int facts,
            int quarantined, boolean truncated) {
    }

    /** The facts served for a document: only those that passed every check. */
    public record Facts(UUID documentId, UUID versionId, String status, List<Fact> facts) {
    }

    private record Version(UUID documentId, UUID versionId, String mimeType) {
    }

    private record Lookup(Version version, Optional<Result> stored) {
    }

    /**
     * Extracts the latest version of the caller's document, or returns the
     * stored result when that version was already extracted with this prompt
     * and model. Empty when the caller can't see the document. Throws
     * {@link ModelUnavailableException} when the model can't be asked; nothing
     * is stored then.
     */
    public Optional<Result> extract(String tenant, UUID documentId) {
        Optional<Lookup> found = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return latestVersion(tenant, documentId)
                    .map(v -> new Lookup(v, storedInTx(tenant, v.versionId())));
        });
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (found.get().stored().isPresent()) {
            return found.get().stored();
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
            Result r = stored(tenant, v.versionId()).orElseGet(() -> extractAndStore(tenant, v));
            mine.complete(r);
            return Optional.of(r);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private Result extractAndStore(String tenant, Version v) {
        Outcome outcome = outcome(tenant, v);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<UUID> inserted = jdbc.queryForList(
                    "INSERT INTO extractions (tenant_id, document_id, version_id, model, prompt_sha256, status, truncated) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (tenant_id, version_id, prompt_sha256, model) DO NOTHING RETURNING id",
                    UUID.class, tenant, v.documentId(), v.versionId(), extractor.modelId(), Prompt.SHA256,
                    outcome.status().name(), outcome.truncated());
            if (inserted.isEmpty()) {
                return storedInTx(tenant, v.versionId()).orElseThrow();
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
                    outcome.quarantined().size(), outcome.truncated());
        });
    }

    /** Reads the version's stored body, only now that a model call is needed, and extracts from it. */
    private Outcome outcome(String tenant, Version v) {
        List<byte[]> rows = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return jdbc.query("SELECT canonical_cbor FROM document_versions WHERE id = ? AND tenant_id = ?",
                    (rs, i) -> rs.getBytes(1), v.versionId(), tenant);
        });
        byte[] cbor = rows.isEmpty() ? null : rows.get(0);
        if (cbor == null) {
            return Extractor.quarantine(Reason.EMPTY_TEXT, "version has no stored body");
        }
        CanonicalCbor.CanonicalForm form;
        try {
            form = CanonicalCbor.decode(cbor);
        } catch (IllegalStateException e) {
            return Extractor.quarantine(Reason.NOT_TEXT, "stored body can't be decoded");
        }
        // The version's own media type, which can't change, over the document row's.
        String mime = form.mime_type != null ? form.mime_type : v.mimeType();
        return extractor.extract(form.body, mime);
    }

    /**
     * Model output kept in a dead letter can hold U+0000, which Postgres text
     * can't store; it is replaced so the quarantine is still written.
     */
    static String storable(String s) {
        return s == null ? null : s.replace('\u0000', '�');
    }

    /**
     * The indexed facts of the caller's document's latest version, from this
     * prompt and model. Empty when the caller can't see the document. A
     * document not yet extracted has status {@code NOT_EXTRACTED}; a
     * quarantined one has no facts.
     */
    public Optional<Facts> facts(String tenant, UUID documentId) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            Optional<Version> found = latestVersion(tenant, documentId);
            if (found.isEmpty()) {
                return Optional.<Facts>empty();
            }
            Version v = found.get();
            List<String> st = jdbc.queryForList(
                    "SELECT status FROM extractions WHERE tenant_id = ? AND version_id = ? AND prompt_sha256 = ? "
                            + "AND model = ?",
                    String.class, tenant, v.versionId(), Prompt.SHA256, extractor.modelId());
            List<Fact> facts = jdbc.query(
                    "SELECT f.field, f.value, f.citation FROM extracted_facts f "
                            + "JOIN extractions e ON e.tenant_id = f.tenant_id AND e.id = f.extraction_id "
                            + "WHERE f.tenant_id = ? AND e.tenant_id = ? AND e.version_id = ? AND e.prompt_sha256 = ? "
                            + "AND e.model = ? AND e.status = 'ACCEPTED' ORDER BY f.ordinal",
                    (rs, i) -> new Fact(rs.getString(1), rs.getString(2), rs.getString(3)),
                    tenant, tenant, v.versionId(), Prompt.SHA256, extractor.modelId());
            return Optional.of(new Facts(v.documentId(), v.versionId(), st.isEmpty() ? "NOT_EXTRACTED" : st.get(0),
                    facts));
        });
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

    private Optional<Result> stored(String tenant, UUID versionId) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return storedInTx(tenant, versionId);
        });
    }

    private Optional<Result> storedInTx(String tenant, UUID versionId) {
        List<Result> rows = jdbc.query(
                "SELECT e.id, e.document_id, e.version_id, e.status, e.truncated, "
                        + "(SELECT count(*) FROM extracted_facts f WHERE f.tenant_id = e.tenant_id AND f.extraction_id = e.id), "
                        + "(SELECT count(*) FROM extraction_dead_letters q WHERE q.tenant_id = e.tenant_id AND q.extraction_id = e.id) "
                        + "FROM extractions e WHERE e.tenant_id = ? AND e.version_id = ? AND e.prompt_sha256 = ? "
                        + "AND e.model = ?",
                (rs, i) -> new Result(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(6), rs.getInt(7), rs.getBoolean(5)),
                tenant, versionId, Prompt.SHA256, extractor.modelId());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
