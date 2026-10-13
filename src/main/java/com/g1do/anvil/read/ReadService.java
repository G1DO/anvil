package com.g1do.anvil.read;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.tenant.TenantGuard;

/**
 * Tenant-isolated direct-ID reads and minimal title-prefix search (issue #22).
 *
 * <p>Contract:
 * <ul>
 * <li>Every method sets transaction-local {@code app.tenant} via
 * {@link TenantGuard#setTenant} with a read-back probe; RLS {@code FORCE}
 * enforces {@code tenant_id = current_setting('app.tenant', true)} underneath,
 * and every statement keeps {@code WHERE tenant_id = ?} in addition to RLS.</li>
 * <li>Direct-ID fetches deny without content and without an existence oracle:
 * a cross-tenant ID returns the same empty result as a missing ID (no bytes,
 * no row, no distinguishing error).</li>
 * <li>Minimal search is {@code WHERE tenant_id = :t AND title ILIKE :prefix%
 * ORDER BY created_at, id} with RLS underneath, a SQL pre-filter, and a
 * per-row Java re-check ({@link #recheck}) that drops any row whose
 * {@code tenant_id} differs from the caller (race-inserted cross rows). No
 * GIN/FTS, no jobs/artifacts full-text, no public shares.</li>
 * <li>Cursor pagination is keyset on {@code (created_at, id)}: the cursor is a
 * document UUID visible to the caller. A cursor belonging to another tenant
 * resolves to zero rows under the caller's tenant context, so replaying B's
 * cursor as A returns 0 rows (fail closed).</li>
 * </ul>
 */
@Service
public class ReadService {

    /** Default page size for search/listing. */
    public static final int DEFAULT_LIMIT = 20;

    /** Maximum page size (DoS bound for the minimal search). */
    public static final int MAX_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReadService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public record DocumentRow(
            UUID id, String tenantId, String title, String mimeType, OffsetDateTime createdAt) {
    }

    public record VersionRow(UUID id, String tenantId, UUID documentId, String sha256) {
    }

    public record JobRow(UUID id, String tenantId, String state, String idempotencyKey) {
    }

    public record AttemptRow(UUID id, String tenantId, UUID jobId, int attemptNumber) {
    }

    public record ArtifactRow(String tenantId, String sha256, long sizeBytes, int refcount) {
    }

    public record OutboxRow(UUID id, String tenantId, UUID jobId, String type) {
    }

    // Direct-ID fetches (D01-D06): WHERE tenant_id + RLS, empty on cross/missing.

    /** D01: tenant-scoped document fetch. */
    public Optional<DocumentRow> getDocument(String tenantId, UUID docId) {
        String tenant = requireTenant(tenantId);
        requireId(docId, "docId");
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<DocumentRow> rows = jdbc.query(
                    "SELECT id, tenant_id, title, mime_type, created_at FROM documents "
                            + "WHERE id = ? AND tenant_id = ?",
                    (rs, i) -> new DocumentRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            rs.getString("title"),
                            rs.getString("mime_type"),
                            rs.getObject("created_at", OffsetDateTime.class)),
                    docId, tenant);
            rows.removeIf(r -> !tenant.equals(r.tenantId()));
            return rows.isEmpty() ? Optional.<DocumentRow>empty() : Optional.of(rows.get(0));
        });
    }

    /** D02: tenant-scoped version fetch. */
    public Optional<VersionRow> getVersion(String tenantId, UUID versionId) {
        String tenant = requireTenant(tenantId);
        requireId(versionId, "versionId");
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<VersionRow> rows = jdbc.query(
                    "SELECT id, tenant_id, document_id, sha256 FROM document_versions "
                            + "WHERE id = ? AND tenant_id = ?",
                    (rs, i) -> new VersionRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            (UUID) rs.getObject("document_id"),
                            rs.getString("sha256")),
                    versionId, tenant);
            rows.removeIf(r -> !tenant.equals(r.tenantId()));
            return rows.isEmpty() ? Optional.<VersionRow>empty() : Optional.of(rows.get(0));
        });
    }

    /** D03: tenant-scoped job fetch. */
    public Optional<JobRow> getJob(String tenantId, UUID jobId) {
        String tenant = requireTenant(tenantId);
        requireId(jobId, "jobId");
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<JobRow> rows = jdbc.query(
                    "SELECT id, tenant_id, state, idempotency_key FROM jobs "
                            + "WHERE id = ? AND tenant_id = ?",
                    (rs, i) -> new JobRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            rs.getString("state"),
                            rs.getString("idempotency_key")),
                    jobId, tenant);
            rows.removeIf(r -> !tenant.equals(r.tenantId()));
            return rows.isEmpty() ? Optional.<JobRow>empty() : Optional.of(rows.get(0));
        });
    }

    /** D04: tenant-scoped attempt fetch by attempt PK. */
    public Optional<AttemptRow> getAttempt(String tenantId, UUID attemptId) {
        String tenant = requireTenant(tenantId);
        requireId(attemptId, "attemptId");
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<AttemptRow> rows = jdbc.query(
                    "SELECT id, tenant_id, job_id, attempt_number FROM attempts "
                            + "WHERE id = ? AND tenant_id = ?",
                    (rs, i) -> new AttemptRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            (UUID) rs.getObject("job_id"),
                            rs.getInt("attempt_number")),
                    attemptId, tenant);
            rows.removeIf(r -> !tenant.equals(r.tenantId()));
            return rows.isEmpty() ? Optional.<AttemptRow>empty() : Optional.of(rows.get(0));
        });
    }

    /** D05: tenant-scoped artifact-row fetch. */
    public Optional<ArtifactRow> getArtifact(String tenantId, String shaHex) {
        String tenant = requireTenant(tenantId);
        String sha = requireSha(shaHex);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<ArtifactRow> rows = jdbc.query(
                    "SELECT tenant_id, sha256, size_bytes, refcount FROM artifacts "
                            + "WHERE tenant_id = ? AND sha256 = ?",
                    (rs, i) -> new ArtifactRow(
                            rs.getString("tenant_id"),
                            rs.getString("sha256"),
                            rs.getLong("size_bytes"),
                            rs.getInt("refcount")),
                    tenant, sha);
            rows.removeIf(r -> !tenant.equals(r.tenantId()));
            return rows.isEmpty() ? Optional.<ArtifactRow>empty() : Optional.of(rows.get(0));
        });
    }

    /** D06: tenant-scoped outbox-event fetch. */
    public Optional<OutboxRow> getOutboxEvent(String tenantId, UUID eventId) {
        String tenant = requireTenant(tenantId);
        requireId(eventId, "eventId");
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<OutboxRow> rows = jdbc.query(
                    "SELECT id, tenant_id, job_id, type FROM outbox WHERE id = ? AND tenant_id = ?",
                    (rs, i) -> new OutboxRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            (UUID) rs.getObject("job_id"),
                            rs.getString("type")),
                    eventId, tenant);
            rows.removeIf(r -> !tenant.equals(r.tenantId()));
            return rows.isEmpty() ? Optional.<OutboxRow>empty() : Optional.of(rows.get(0));
        });
    }

    // Search/listing (S01-S04, S07-S08).

    /**
     * Minimal title-prefix search: {@code WHERE tenant_id = :t AND title ILIKE
     * :prefix% ORDER BY created_at, id}.
     *
     * @param tenantId caller tenant (sole authority; any query/body tenant is ignored upstream).
     * @param prefix title prefix; null/blank matches all titles.
     * @param limit page size; null/defaults to {@link #DEFAULT_LIMIT}, capped at {@link #MAX_LIMIT}.
     * @param cursorId last-seen document ID for keyset pagination; null/blank starts from the beginning.
     * @return caller-owned rows only, after the SQL pre-filter and the Java re-check.
     * @throws IllegalArgumentException when the cursor is malformed (caller error, 400 at HTTP).
     */
    public List<DocumentRow> searchDocuments(
            String tenantId, String prefix, Integer limit, UUID cursorId) {
        String tenant = requireTenant(tenantId);
        String like = toPrefixLike(prefix);
        int pageSize = normalizeLimit(limit);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            OffsetDateTime cursorCreated = null;
            UUID cursorKey = null;
            if (cursorId != null) {
                List<CursorPos> pos = jdbc.query(
                        "SELECT created_at, id FROM documents WHERE id = ? AND tenant_id = ?",
                        (rs, i) -> new CursorPos(
                                rs.getObject("created_at", OffsetDateTime.class),
                                (UUID) rs.getObject("id")),
                        cursorId, tenant);
                if (pos.isEmpty()) {
                    // S07: cursor belonging to another tenant (or missing) is not
                    // visible under the caller's context: fail closed with 0 rows.
                    return List.<DocumentRow>of();
                }
                cursorCreated = pos.get(0).createdAt();
                cursorKey = pos.get(0).id();
            }
            List<DocumentRow> rows;
            if (cursorKey == null) {
                rows = jdbc.query(
                        "SELECT id, tenant_id, title, mime_type, created_at FROM documents "
                                + "WHERE tenant_id = ? AND title ILIKE ? ESCAPE '\\' "
                                + "ORDER BY created_at, id LIMIT ?",
                        (rs, i) -> new DocumentRow(
                                (UUID) rs.getObject("id"),
                                rs.getString("tenant_id"),
                                rs.getString("title"),
                                rs.getString("mime_type"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        tenant, like, pageSize);
            } else {
                rows = jdbc.query(
                        "SELECT id, tenant_id, title, mime_type, created_at FROM documents "
                                + "WHERE tenant_id = ? AND title ILIKE ? ESCAPE '\\' "
                                + "AND (created_at, id) > (?, ?) "
                                + "ORDER BY created_at, id LIMIT ?",
                        (rs, i) -> new DocumentRow(
                                (UUID) rs.getObject("id"),
                                rs.getString("tenant_id"),
                                rs.getString("title"),
                                rs.getString("mime_type"),
                                rs.getObject("created_at", OffsetDateTime.class)),
                        tenant, like, cursorCreated, cursorKey, pageSize);
            }
            // S08: execution re-check drops any race-inserted cross-tenant row.
            return recheck(tenant, rows);
        });
    }

    /**
     * Parses a cursor string for {@link #searchDocuments}: blank returns null
     * (start), otherwise the UUID; malformed throws {@link IllegalArgumentException}.
     */
    public static UUID parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cursor must be a UUID");
        }
    }

    /** S02: tenant-scoped job listing (RLS list-filter check, no full-text index). */
    public List<JobRow> listJobs(String tenantId, Integer limit) {
        String tenant = requireTenant(tenantId);
        int pageSize = normalizeLimit(limit);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<JobRow> rows = jdbc.query(
                    "SELECT id, tenant_id, state, idempotency_key FROM jobs "
                            + "WHERE tenant_id = ? ORDER BY created_at, id LIMIT ?",
                    (rs, i) -> new JobRow(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            rs.getString("state"),
                            rs.getString("idempotency_key")),
                    tenant, pageSize);
            List<JobRow> kept = new ArrayList<>();
            for (JobRow row : rows) {
                if (tenant.equals(row.tenantId())) {
                    kept.add(row);
                }
            }
            return kept;
        });
    }

    /** S03: tenant-scoped artifact listing (RLS list-filter check, no full-text index). */
    public List<ArtifactRow> listArtifacts(String tenantId, Integer limit) {
        String tenant = requireTenant(tenantId);
        int pageSize = normalizeLimit(limit);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<ArtifactRow> rows = jdbc.query(
                    "SELECT tenant_id, sha256, size_bytes, refcount FROM artifacts "
                            + "WHERE tenant_id = ? ORDER BY created_at, sha256 LIMIT ?",
                    (rs, i) -> new ArtifactRow(
                            rs.getString("tenant_id"),
                            rs.getString("sha256"),
                            rs.getLong("size_bytes"),
                            rs.getInt("refcount")),
                    tenant, pageSize);
            List<ArtifactRow> kept = new ArrayList<>();
            for (ArtifactRow row : rows) {
                if (tenant.equals(row.tenantId())) {
                    kept.add(row);
                }
            }
            return kept;
        });
    }

    /**
     * Execution re-check (S08): drops any row whose {@code tenant_id} differs
     * from the caller. Pure function so the race-inserted cross-row case is
     * unit-testable without timing.
     */
    public static List<DocumentRow> recheck(String callerTenant, List<DocumentRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DocumentRow> kept = new ArrayList<>(rows.size());
        for (DocumentRow row : rows) {
            if (row != null && callerTenant.equals(row.tenantId())) {
                kept.add(row);
            }
        }
        return List.copyOf(kept);
    }

    private record CursorPos(OffsetDateTime createdAt, UUID id) {
    }

    static String toPrefixLike(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "%";
        }
        String trimmed = prefix;
        // Escape LIKE metacharacters so the prefix is literal.
        String escaped = trimmed
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return escaped + "%";
    }

    static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return tenantId.trim();
    }

    private static void requireId(UUID id, String name) {
        if (id == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    private static String requireSha(String shaHex) {
        if (shaHex == null) {
            throw new IllegalArgumentException("sha256 must not be null");
        }
        String sha = shaHex.trim().toLowerCase(java.util.Locale.ROOT);
        if (!sha.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("sha256 must be lowercase hex [0-9a-f]{64}");
        }
        return sha;
    }
}
