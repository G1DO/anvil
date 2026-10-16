package com.g1do.anvil.audit;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.tenant.TenantGuard;

/**
 * Tenant-isolated audit event reads (issue #29).
 *
 * <p>Contract:
 * <ul>
 * <li>Every call sets transaction-local {@code app.tenant} via
 * {@link TenantGuard#setTenant} with a read-back probe; RLS {@code FORCE}
 * enforces {@code tenant_id = current_setting('app.tenant', true)} underneath,
 * and every statement keeps {@code WHERE tenant_id = ?} in addition to RLS.</li>
 * <li>Chain is per {@code (tenant_id, job_id)} ordered by {@code seq}: a
 * cross-tenant {@code job_id} returns the same empty list as a missing
 * {@code job_id} (no bytes, no row, no distinguishing error).</li>
 * <li>Cursor pagination is keyset on {@code seq} via a tenant- and job-scoped
 * event UUID: the cursor is an {@code audit_events.id} visible to the caller
 * for the requested job. A cursor belonging to another tenant (or another job,
 * or missing) resolves to zero rows under the caller's context, so replaying
 * B's cursor as A returns 0 rows (fail closed).</li>
 * <li>Limits default {@link #DEFAULT_LIMIT}, capped at {@link #MAX_LIMIT}.
 * No GIN/FTS. No filesystem access: JDBC only.</li>
 * </ul>
 */
@Service
public class AuditReadService {

    /** Default page size for audit reads. */
    public static final int DEFAULT_LIMIT = 20;

    /** Maximum page size (DoS bound, mirrors reads). */
    public static final int MAX_LIMIT = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AuditReadService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public record AuditEventRow(
            UUID id,
            String tenantId,
            UUID jobId,
            long seq,
            String prevHash,
            String eventHash,
            String type,
            JsonNode payload,
            OffsetDateTime createdAt) {
    }

    /**
     * Lists audit events for one job, ordered by {@code seq}.
     *
     * @param tenantId caller tenant (sole authority; any query/body tenant is ignored upstream).
     * @param jobId job to read; cross-tenant or missing yields empty (no oracle).
     * @param limit page size; null/defaults to {@link #DEFAULT_LIMIT}, capped at {@link #MAX_LIMIT}.
     * @param cursorId last-seen event ID for keyset pagination; null starts from the beginning.
     * @return caller-owned rows only, after the SQL pre-filter and the Java re-check.
     * @throws IllegalArgumentException when jobId is null (caller error, 400 at HTTP).
     */
    public List<AuditEventRow> listEvents(
            String tenantId, UUID jobId, Integer limit, UUID cursorId) {
        String tenant = requireTenant(tenantId);
        requireJobId(jobId);
        int pageSize = normalizeLimit(limit);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            Long cursorSeq = null;
            if (cursorId != null) {
                List<Long> pos = jdbc.query(
                        "SELECT seq FROM audit_events WHERE id = ? AND tenant_id = ? AND job_id = ?",
                        (rs, i) -> rs.getLong("seq"),
                        cursorId, tenant, jobId);
                if (pos.isEmpty()) {
                    // Foreign cursor (other tenant/job) or missing: fail closed with 0 rows.
                    return List.<AuditEventRow>of();
                }
                cursorSeq = pos.get(0);
            }
            List<AuditEventRow> rows;
            if (cursorSeq == null) {
                rows = jdbc.query(
                        "SELECT id, tenant_id, job_id, seq, prev_hash, event_hash, type, "
                                + "payload, created_at FROM audit_events "
                                + "WHERE tenant_id = ? AND job_id = ? ORDER BY seq LIMIT ?",
                        (rs, i) -> mapRow(rs),
                        tenant, jobId, pageSize);
            } else {
                rows = jdbc.query(
                        "SELECT id, tenant_id, job_id, seq, prev_hash, event_hash, type, "
                                + "payload, created_at FROM audit_events "
                                + "WHERE tenant_id = ? AND job_id = ? AND seq > ? ORDER BY seq LIMIT ?",
                        (rs, i) -> mapRow(rs),
                        tenant, jobId, cursorSeq, pageSize);
            }
            return recheck(tenant, jobId, rows);
        });
    }

    /**
     * Parses a cursor string: blank returns null (start), otherwise the UUID;
     * malformed throws {@link IllegalArgumentException}.
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

    /**
     * Execution re-check: drops any row whose {@code tenant_id} or
     * {@code job_id} differs from the caller. Pure function so the
     * race-inserted cross-row case is unit-testable without timing.
     */
    public static List<AuditEventRow> recheck(String callerTenant, UUID jobId, List<AuditEventRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<AuditEventRow> kept = new ArrayList<>(rows.size());
        for (AuditEventRow row : rows) {
            if (row != null && callerTenant.equals(row.tenantId()) && jobId.equals(row.jobId())) {
                kept.add(row);
            }
        }
        return List.copyOf(kept);
    }

    public static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static AuditEventRow mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = (UUID) rs.getObject("id");
        String tenantId = rs.getString("tenant_id");
        UUID jobId = (UUID) rs.getObject("job_id");
        long seq = rs.getLong("seq");
        String prevHash = rs.getString("prev_hash");
        String eventHash = rs.getString("event_hash");
        String type = rs.getString("type");
        // payload is jsonb: read as text to avoid a compile-time dependency on
        // the runtime-scoped Postgres driver (no direct PGobject reference).
        String payloadJson = rs.getString("payload");
        if (payloadJson == null) {
            payloadJson = "{}";
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalStateException("audit payload decode failed", e);
        }
        OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
        return new AuditEventRow(id, tenantId, jobId, seq, prevHash, eventHash, type, payload, createdAt);
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return tenantId.trim();
    }

    private static void requireJobId(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId must not be null");
        }
    }
}
