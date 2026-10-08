package com.g1do.anvil.jobs;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Fenced claim, heartbeat, commit and reconciler over the V1 jobs slice.
 *
 * <p>Contract (issue #4, narrowest reasonable):
 * <ul>
 * <li>Timings: {@code lease_ttl=10s, heartbeat_every=3s, claim LIMIT 20,
 * max_attempts=5 (per-job default), reconciler_poll=1s}. The 10s TTL is well
 * under the 40s zombie suspension so expiry is guaranteed; the 3s heartbeat
 * is under TTL/3 to prevent false expiry.</li>
 * <li>Database clock ({@code clock_timestamp()}) is the only time source for
 * leases; worker clocks never decide ownership.</li>
 * <li>Fencing is {@code WHERE id plus fencing token plus owner plus expected
 * state} on every mutating step; a stale owner receives zero updated rows and
 * causes no state change.</li>
 * <li>Polling first; no broker, Redis or external queue.</li>
 * <li>At-least-once execution with idempotent fenced effects; exactly-once is
 * explicitly not asserted.</li>
 * </ul>
 *
 * <p>Claiming is a single atomic statement: candidate selection with
 * {@code LIMIT 20} plus {@code FOR UPDATE SKIP LOCKED}, incrementing fencing
 * token and attempt together and stamping time with the database clock only.
 * The ledger row ({@code attempts}, {@code UNIQUE(tenant_id, job_id,
 * attempt_number)}) is written in the same transaction so every successful
 * claim has exactly one ledger entry: zero lost, zero doubled (the UNIQUE
 * target rejects zombie double-record).
 *
 * <p>Terminal jobs ({@code SUCCEEDED, FAILED, DEAD}) are never offered: claim
 * filters {@code state='QUEUED'} and the {@code job_state_guard} trigger
 * rejects any mutation of a terminal row.
 */
@Service
public class JobService {

    /** Lease time-to-live; applied as {@code clock_timestamp() + 10 seconds}. */
    public static final int LEASE_TTL_SECONDS = 10;

    /** Worker heartbeat cadence; under TTL/3 to prevent false expiry. */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 3;

    /** Candidate window per claim; appears literally as {@code LIMIT 20}. */
    public static final int CLAIM_LIMIT = 20;

    /** Default per-job budget; the row-level {@code max_attempts} is authoritative. */
    public static final int MAX_ATTEMPTS = 5;

    /** Intended reconciler cadence for polling deployments. */
    public static final int RECONCILER_POLL_SECONDS = 1;

    /**
     * Single atomic claim: lock up to 20 claimable candidates, take one, move
     * it {@code QUEUED -> RUNNING} with a new fencing token and incremented
     * attempt, stamped by the database clock only.
     */
    private static final String CLAIM_SQL = """
            WITH candidates AS (
              SELECT id
              FROM jobs
              WHERE tenant_id = ?
                AND state = 'QUEUED'
                AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
                AND attempt < max_attempts
              ORDER BY created_at, id
              LIMIT 20
              FOR UPDATE SKIP LOCKED
            ),
            picked AS (
              SELECT id FROM candidates LIMIT 1
            )
            UPDATE jobs j
            SET state = 'RUNNING',
                owner = ?,
                fencing_token = j.fencing_token + 1,
                attempt = j.attempt + 1,
                lease_expires_at = clock_timestamp() + INTERVAL '10 seconds',
                updated_at = clock_timestamp()
            FROM picked
            WHERE j.id = picked.id
              AND j.tenant_id = ?
              AND j.state = 'QUEUED'
            RETURNING j.id, j.fencing_token, j.attempt, j.lease_expires_at
            """;

    private static final String INSERT_ATTEMPT_SQL = """
            INSERT INTO attempts (tenant_id, job_id, attempt_number, owner, fencing_token, outcome)
            VALUES (?, ?, ?, ?, ?, 'CLAIMED')
            """;

    /**
     * Heartbeat extends only a live lease held by the same owner and token.
     * Expired, fenced-out, non-running or terminal rows match zero rows.
     */
    private static final String HEARTBEAT_SQL = """
            UPDATE jobs
            SET lease_expires_at = clock_timestamp() + INTERVAL '10 seconds',
                updated_at = clock_timestamp()
            WHERE id = ?
              AND tenant_id = ?
              AND owner = ?
              AND fencing_token = ?
              AND state = 'RUNNING'
              AND lease_expires_at > clock_timestamp()
            """;

    private static final String COMMIT_SQL_PREFIX = """
            UPDATE jobs
            SET state = ?,
                updated_at = clock_timestamp()
            WHERE id = ?
              AND tenant_id = ?
              AND owner = ?
              AND fencing_token = ?
              AND state = 'RUNNING'
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JobService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Atomically claims one claimable job for {@code owner}.
     *
     * @return the claimed job with its new fencing token and attempt, or empty
     *         when no {@code QUEUED} job with {@code attempt < max_attempts}
     *         and an unheld/expired lease is available (includes the case where
     *         every job is terminal, running, or exhausted).
     */
    public Optional<ClaimedJob> claim(String tenantId, String owner) {
        String tenant = requireTenant(tenantId);
        String worker = requireOwner(owner);
        return tx.execute(status -> {
            List<ClaimedJob> rows = jdbc.query(
                    CLAIM_SQL,
                    (rs, i) -> new ClaimedJob(
                            (UUID) rs.getObject("id"),
                            worker,
                            rs.getLong("fencing_token"),
                            rs.getInt("attempt"),
                            rs.getObject("lease_expires_at", OffsetDateTime.class)),
                    tenant, worker, tenant);
            if (rows.isEmpty()) {
                return Optional.<ClaimedJob>empty();
            }
            ClaimedJob claimed = rows.get(0);
            jdbc.update(INSERT_ATTEMPT_SQL,
                    tenant, claimed.jobId(), claimed.attempt(), worker, claimed.fencingToken());
            return Optional.of(claimed);
        });
    }

    /**
     * Extends the lease only when the caller still holds a live lease:
     * same owner, same fencing token, {@code RUNNING}, and
     * {@code lease_expires_at > clock_timestamp()}.
     *
     * @return {@code true} when exactly one row was extended.
     */
    public boolean heartbeat(String tenantId, UUID jobId, String owner, long fencingToken) {
        String tenant = requireTenant(tenantId);
        String worker = requireOwner(owner);
        requireJobId(jobId);
        int updated = jdbc.update(HEARTBEAT_SQL, jobId, tenant, worker, fencingToken);
        return updated == 1;
    }

    /**
     * Commits a running job to {@code SUCCEEDED} with full fencing.
     * A zombie that lost ownership (token changed or state left
     * {@code RUNNING}) affects zero rows.
     *
     * @return {@code true} when exactly one row transitioned.
     */
    public boolean commitSucceeded(String tenantId, UUID jobId, String owner, long fencingToken) {
        return commit(tenantId, jobId, owner, fencingToken, "SUCCEEDED");
    }

    /**
     * Commits a running job to {@code FAILED} with full fencing.
     *
     * @return {@code true} when exactly one row transitioned.
     */
    public boolean commitFailed(String tenantId, UUID jobId, String owner, long fencingToken) {
        return commit(tenantId, jobId, owner, fencingToken, "FAILED");
    }

    private boolean commit(String tenantId, UUID jobId, String owner, long fencingToken, String targetState) {
        String tenant = requireTenant(tenantId);
        String worker = requireOwner(owner);
        requireJobId(jobId);
        int updated = jdbc.update(COMMIT_SQL_PREFIX, targetState, jobId, tenant, worker, fencingToken);
        return updated == 1;
    }

    /**
     * Single reconciler pass for one tenant: exhausted jobs move to
     * {@code DEAD} rather than being re-offered; abandoned leases with
     * attempts remaining return to {@code QUEUED} (claimable with a new
     * fencing token on next claim).
     */
    public ReconcileResult reconcile(String tenantId) {
        String tenant = requireTenant(tenantId);
        return tx.execute(status -> {
            int deadQueued = jdbc.update(
                    "UPDATE jobs SET state = 'DEAD', updated_at = clock_timestamp() "
                            + "WHERE tenant_id = ? AND state = 'QUEUED' AND attempt >= max_attempts",
                    tenant);
            int deadRunning = jdbc.update(
                    "UPDATE jobs SET state = 'DEAD', updated_at = clock_timestamp() "
                            + "WHERE tenant_id = ? AND state = 'RUNNING' "
                            + "AND lease_expires_at <= clock_timestamp() AND attempt >= max_attempts",
                    tenant);
            int released = jdbc.update(
                    "UPDATE jobs SET state = 'QUEUED', owner = NULL, lease_expires_at = NULL, "
                            + "updated_at = clock_timestamp() "
                            + "WHERE tenant_id = ? AND state = 'RUNNING' "
                            + "AND lease_expires_at <= clock_timestamp() AND attempt < max_attempts",
                    tenant);
            return new ReconcileResult(released, deadQueued + deadRunning);
        });
    }

    /**
     * Single reconciler pass across all tenants. Intended cadence is
     * {@link #RECONCILER_POLL_SECONDS}s in polling deployments; tests invoke
     * this (or {@link #reconcile(String)}) directly for determinism.
     */
    public ReconcileResult reconcileAll() {
        return tx.execute(status -> {
            int deadQueued = jdbc.update(
                    "UPDATE jobs SET state = 'DEAD', updated_at = clock_timestamp() "
                            + "WHERE state = 'QUEUED' AND attempt >= max_attempts");
            int deadRunning = jdbc.update(
                    "UPDATE jobs SET state = 'DEAD', updated_at = clock_timestamp() "
                            + "WHERE state = 'RUNNING' "
                            + "AND lease_expires_at <= clock_timestamp() AND attempt >= max_attempts");
            int released = jdbc.update(
                    "UPDATE jobs SET state = 'QUEUED', owner = NULL, lease_expires_at = NULL, "
                            + "updated_at = clock_timestamp() "
                            + "WHERE state = 'RUNNING' "
                            + "AND lease_expires_at <= clock_timestamp() AND attempt < max_attempts");
            return new ReconcileResult(released, deadQueued + deadRunning);
        });
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return tenantId.trim();
    }

    private static String requireOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("owner must not be blank");
        }
        return owner.trim();
    }

    private static void requireJobId(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId must not be null");
        }
    }
}
