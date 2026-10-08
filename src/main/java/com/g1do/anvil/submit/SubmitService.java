package com.g1do.anvil.submit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Single-transaction idempotent submit.
 *
 * <p>Order: Auth -&gt; Validate -&gt; Canonicalize CBOR -&gt; persist.
 * One valid submit durably writes document, document version, active job and
 * outbox event in exactly one database transaction.
 */
@Service
public class SubmitService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json = new ObjectMapper();

    public SubmitService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public record SubmitResult(UUID docId, UUID versionId, UUID jobId) {
    }

    /**
     * Non-transactional entry point: validates, canonicalizes, then delegates to
     * a single transaction. Converts concurrent unique violations into an
     * idempotent replay of the winner.
     */
    public SubmitResult submit(String tenantId, String idempotencyKey, String title, String mimeType,
            String body) {
        String tenant = validateTenant(tenantId);
        String key = validateIdempotencyKey(idempotencyKey);
        String cleanTitle = validateTitle(title);
        String cleanMime = validateMimeType(mimeType);
        String cleanBody = validateBody(body);

        byte[] bodyBytes = cleanBody.getBytes(StandardCharsets.UTF_8);
        CanonicalCbor.Encoded encoded = CanonicalCbor.encode(cleanTitle, cleanMime, bodyBytes);

        // Fast path: sequential duplicate returns without writing.
        SubmitResult existing = findExisting(tenant, key);
        if (existing != null) {
            return existing;
        }
        try {
            return insertInTransaction(tenant, key, cleanTitle, cleanMime, encoded);
        } catch (DuplicateKeyException e) {
            // Concurrent duplicate lost the UNIQUE(tenant_id, idempotency_key) race:
            // the winner is committed (or committing); re-read it.
            SubmitResult winner = findExisting(tenant, key);
            if (winner != null) {
                return winner;
            }
            throw e;
        }
    }

    private SubmitResult findExisting(String tenantId, String idempotencyKey) {
        List<SubmitResult> rows = jdbc.query(
                "SELECT document_id, version_id, id FROM jobs WHERE tenant_id = ? AND idempotency_key = ?",
                (rs, i) -> new SubmitResult(
                        (UUID) rs.getObject("document_id"),
                        (UUID) rs.getObject("version_id"),
                        (UUID) rs.getObject("id")),
                tenantId, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Single database transaction for document plus version plus job plus outbox.
     * No dual-write: the commit succeeds with the event or not at all.
     */
    private SubmitResult insertInTransaction(String tenantId, String idempotencyKey, String title,
            String mimeType, CanonicalCbor.Encoded encoded) {
        return tx.execute(status -> {
            // Re-check inside the transaction so a sequential duplicate that raced the
            // outer fast-path still returns the same shape without writing.
            SubmitResult existing = findExisting(tenantId, idempotencyKey);
            if (existing != null) {
                return existing;
            }

            UUID docId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            UUID jobId = UUID.randomUUID();
            UUID outboxId = UUID.randomUUID();

            jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                    docId, tenantId, title, mimeType);

            jdbc.update(
                    "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256, canonical_cbor) "
                            + "VALUES (?, ?, ?, 1, ?, ?)",
                    versionId, tenantId, docId, encoded.sha256Hex(), encoded.cbor());

            jdbc.update(
                    "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) "
                            + "VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                    jobId, tenantId, docId, versionId, idempotencyKey);

            String payload;
            try {
                payload = json.writeValueAsString(Map.of(
                        "tenant_id", tenantId,
                        "job_id", jobId.toString(),
                        "doc_id", docId.toString(),
                        "version_id", versionId.toString(),
                        "sha256", encoded.sha256Hex(),
                        "type", "job.committed"));
            } catch (Exception e) {
                throw new IllegalStateException("outbox payload encoding failed", e);
            }

            jdbc.update(
                    "INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, sha256, payload) "
                            + "VALUES (?, ?, ?, ?, ?, 'job.committed', ?, ?::jsonb)",
                    outboxId, tenantId, jobId, docId, versionId, encoded.sha256Hex(), payload);

            return new SubmitResult(docId, versionId, jobId);
        });
    }

    private String validateTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-Tenant-Id");
        }
        String tenant = tenantId.trim();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Integer.class,
                tenant);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown tenant");
        }
        return tenant;
    }

    private String validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing Idempotency-Key");
        }
        String clean = key.trim();
        if (clean.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key too long");
        }
        return clean;
    }

    private String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        if (title.length() > 4096) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title too long");
        }
        return title;
    }

    private String validateMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mime_type must not be blank");
        }
        if (mimeType.trim().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mime_type too long");
        }
        return mimeType.trim();
    }

    private String validateBody(String body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body must be present");
        }
        return body;
    }
}
