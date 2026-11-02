package com.g1do.anvil.extract;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.eval.Fact;
import com.g1do.anvil.extract.Extractor.Outcome;
import com.g1do.anvil.extract.Extractor.Quarantined;
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
 * <li>The model is asked outside any transaction. Its outcome is written in
 * one transaction: the extraction row, the accepted facts, and the dead
 * letters. If another request for the same version committed first, its
 * result is returned and this one is dropped.</li>
 * <li>A version is extracted once per tenant. The key holds the tenant, so
 * identical content in two tenants is extracted twice: no cross-tenant dedup,
 * cache or result.</li>
 * <li>Dead letters are written here and read by no route.</li>
 * </ul>
 */
@Service
public class ExtractionService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Extractor extractor;

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

    private record Source(UUID documentId, UUID versionId, String mimeType, byte[] cbor) {
    }

    /**
     * Extracts the latest version of the caller's document, or returns the
     * stored result when that version was already extracted. Empty when the
     * caller can't see the document. Throws {@link ModelUnavailableException}
     * when the model can't be asked; nothing is stored then.
     */
    public Optional<Result> extract(String tenant, UUID documentId) {
        Optional<Source> source = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return latestVersion(tenant, documentId);
        });
        if (source.isEmpty()) {
            return Optional.empty();
        }
        Source s = source.get();
        Optional<Result> done = stored(tenant, s.versionId());
        if (done.isPresent()) {
            return done;
        }

        byte[] body = CanonicalCbor.decode(s.cbor()).body;
        Outcome outcome = extractor.extract(body, s.mimeType());

        return Optional.of(tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<UUID> inserted = jdbc.queryForList(
                    "INSERT INTO extractions (tenant_id, document_id, version_id, model, prompt_sha256, status, truncated) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, version_id) DO NOTHING RETURNING id",
                    UUID.class, tenant, s.documentId(), s.versionId(), extractor.modelId(), Prompt.SHA256,
                    outcome.status().name(), outcome.truncated());
            if (inserted.isEmpty()) {
                return storedInTx(tenant, s.versionId()).orElseThrow();
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
                        f == null ? null : f.field(), f == null ? null : f.value(), f == null ? null : f.citation(),
                        q.detail());
            }
            return new Result(id, s.documentId(), s.versionId(), outcome.status().name(), outcome.accepted().size(),
                    outcome.quarantined().size(), outcome.truncated());
        }));
    }

    /**
     * The indexed facts of the caller's document's latest version. Empty when
     * the caller can't see the document. A document not yet extracted has
     * status {@code NOT_EXTRACTED}; a quarantined one has no facts.
     */
    public Optional<Facts> facts(String tenant, UUID documentId) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            Optional<Source> source = latestVersion(tenant, documentId);
            if (source.isEmpty()) {
                return Optional.<Facts>empty();
            }
            Source s = source.get();
            List<String> st = jdbc.queryForList(
                    "SELECT status FROM extractions WHERE tenant_id = ? AND version_id = ?", String.class, tenant,
                    s.versionId());
            List<Fact> facts = jdbc.query(
                    "SELECT f.field, f.value, f.citation FROM extracted_facts f "
                            + "JOIN extractions e ON e.tenant_id = f.tenant_id AND e.id = f.extraction_id "
                            + "WHERE f.tenant_id = ? AND e.tenant_id = ? AND e.version_id = ? AND e.status = 'ACCEPTED' "
                            + "ORDER BY f.ordinal",
                    (rs, i) -> new Fact(rs.getString(1), rs.getString(2), rs.getString(3)),
                    tenant, tenant, s.versionId());
            return Optional.of(new Facts(s.documentId(), s.versionId(), st.isEmpty() ? "NOT_EXTRACTED" : st.get(0),
                    facts));
        });
    }

    private Optional<Source> latestVersion(String tenant, UUID documentId) {
        List<Source> rows = jdbc.query(
                "SELECT d.id, v.id, d.mime_type, v.canonical_cbor FROM documents d "
                        + "JOIN document_versions v ON v.tenant_id = d.tenant_id AND v.document_id = d.id "
                        + "WHERE d.id = ? AND d.tenant_id = ? AND v.tenant_id = ? "
                        + "ORDER BY v.version_number DESC LIMIT 1",
                (rs, i) -> new Source(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getBytes(4)),
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
                        + "FROM extractions e WHERE e.tenant_id = ? AND e.version_id = ?",
                (rs, i) -> new Result(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(6), rs.getInt(7), rs.getBoolean(5)),
                tenant, versionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
}
