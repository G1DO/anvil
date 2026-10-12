package com.g1do.anvil.tenant;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Database tenant-context guard for pooled connections (issue #20).
 *
 * <p>Contract: every transaction that touches tenant-scoped rows must call
 * {@link #setTenant} first. It runs {@code SELECT set_config('app.tenant', ?,
 * true)} (equivalent to {@code SET LOCAL}, transaction-local) and then reads
 * back {@code SELECT current_setting('app.tenant', true)}, throwing when the
 * read-back mismatches. {@code SET LOCAL} resets automatically on
 * {@code COMMIT}/{@code ROLLBACK}, so a Hikari checkout cannot retain the
 * previous tenant. Missing context yields {@code NULL} from
 * {@code current_setting(..., true)}, and every RLS policy compares
 * {@code tenant_id = NULL} (not true), so selects see zero rows and writes
 * fail the {@code WITH CHECK} — fail closed, never leaks.
 *
 * <p>Must be called inside an active transaction: {@code SET LOCAL} (and the
 * {@code is_local=true} form of {@code set_config}) requires one. All
 * transactional hot paths ({@code TransactionTemplate.execute} bodies) satisfy
 * this; single-statement callers are wrapped in a transaction for the same
 * reason.
 */
public final class TenantGuard {

    private TenantGuard() {
    }

    /**
     * Sets the transaction-local tenant and verifies it with a read-back
     * probe.
     *
     * @return the trimmed tenant id that is now active for this transaction.
     * @throws IllegalArgumentException when {@code tenantId} is blank.
     * @throws IllegalStateException when the read-back probe mismatches.
     */
    public static String setTenant(JdbcTemplate jdbc, String tenantId) {
        String tenant = requireTenant(tenantId);
        jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, tenant);
        String current = jdbc.queryForObject("SELECT current_setting('app.tenant', true)", String.class);
        if (!tenant.equals(current)) {
            throw new IllegalStateException(
                    "tenant context mismatch: expected '" + tenant + "' but read back '"
                            + current + "'");
        }
        return tenant;
    }

    /**
     * Reads back the current transaction-local tenant without setting it.
     * Returns {@code null} when no context is set (RLS then fails closed).
     */
    public static String currentTenant(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT current_setting('app.tenant', true)", String.class);
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return tenantId.trim();
    }
}
