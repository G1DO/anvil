package com.g1do.anvil.jobs;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.audit.AuditChain;
import com.g1do.anvil.observe.SaturationSignals;
import com.g1do.anvil.tenant.TenantGuard;

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
 * <p>Fair claim (issue #35, record 0020): a claim takes a job only while its
 * tenant holds fewer than {@code tenants.max_running} jobs in {@code RUNNING}.
 * Claims for one tenant serialize on a transaction-scoped advisory lock taken
 * before the count, so concurrent claims can't overshoot the quota. Jobs are
 * claimed in the order {@code (penalty, next_run_at, created_at, id)}: the
 * penalty is the tenant's {@code RUNNING} count, only jobs with
 * {@code next_run_at <= clock_timestamp()} are eligible, and arrival order
 * breaks ties. {@link #claim(String, String)} applies it within one tenant,
 * where the penalty is the same for every job; {@link #claimNext(String)}
 * applies it across tenants.
 *
 * <p>Terminal jobs ({@code SUCCEEDED, FAILED, DEAD}) are never offered: claim
 * filters {@code state='QUEUED'} and the {@code job_state_guard} trigger
 * rejects any mutation of a terminal row.
 *
 * <p>Tenant isolation (issue #20): every transaction sets transaction-local
 * {@code app.tenant} via {@link TenantGuard#setTenant} with a read-back probe
 * ({@code SET LOCAL} resets on commit/rollback, so pooled reuse cannot leak),
 * RLS {@code FORCE} policies enforce {@code tenant_id =
 * current_setting('app.tenant', true)} underneath, and every statement keeps
 * an explicit {@code WHERE tenant_id = ?} in addition to RLS.
 *
 * <p>Capability gating (issue #21): every commit requires a valid
 * {@code cap_grants} row for {@code (tenant_id, job_id)}. The grant is checked
 * in the same transaction as the effect, after tenant context is established
 * and before any {@code jobs} write, with the grant row locked
 * {@code FOR UPDATE} and evaluated with the database clock only
 * ({@code clock_timestamp()} vs {@code expires_at}). Missing, revoked,
 * expired, superseded (stale {@code epoch}), or cross-tenant grants deny
 * atomically: zero job rows, {@link CapDeniedException} ({@code 403
 * NEEDS_APPROVAL}), {@code cap_rejected} incremented. Fencing failures (wrong
 * owner/token) still return {@code false} with no exception and no counter
 * increment. Grants are tenant-bound via composite
 * {@code FOREIGN KEY(tenant_id, job_id)} plus {@code WHERE tenant_id} and RLS;
 * no cross-tenant dedup is introduced. Post-grant TOCTOU (revoke committed
 * after the commit txn commits) is residual by definition; revoke committed
 * before the commit's {@code FOR UPDATE} lock is acquired is always observed
 * because the same transaction holds the row lock across check and effect.
 *
 * <p>Timeline gate (issue #34, record 0016): a point-in-time restore rewinds
 * fencing tokens and grants, so a worker from the lost timeline could hold a
 * token or grant the restored database hands out again. Claim, heartbeat and
 * commit first call {@link TimelineGuard#requireConfirmed}, which refuses
 * ({@code 503 TIMELINE_UNCONFIRMED}, zero rows) while the database timeline is
 * unknown or differs from {@code db_timeline}. {@link #confirmTimeline()}
 * reopens the gate only once no lease is live on the database clock, after
 * raising every open job's token past any the lost timeline could have issued
 * and deleting every grant, so each commit waits for a re-issued capability.
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

    /** Quota for a tenant row that doesn't set one; the V7 column default. */
    public static final int DEFAULT_MAX_RUNNING = 1000;

    /**
     * Serializes claims for one tenant until the claiming transaction ends.
     * The key is a 64-bit hash of the tenant id in an {@code anvil.claim:}
     * namespace; a collision only serializes two tenants' claims.
     */
    private static final String CLAIM_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended('anvil.claim:' || ?, 0))";

    /**
     * The tenant's quota and its current {@code RUNNING} count (the penalty).
     * Read after {@link #CLAIM_LOCK_SQL}, so in read committed it sees every
     * claim that held the lock before.
     */
    private static final String QUOTA_SQL = """
            SELECT t.max_running,
                   (SELECT count(*) FROM jobs r WHERE r.tenant_id = ? AND r.state = 'RUNNING') AS running
            FROM tenants t
            WHERE t.id = ?
            """;

    /**
     * Single atomic claim: lock up to 20 claimable candidates in claim order,
     * take one, move it {@code QUEUED -> RUNNING} with a new fencing token and
     * incremented attempt, stamped by the database clock only.
     */
    private static final String CLAIM_SQL = """
            WITH candidates AS (
              SELECT id
              FROM jobs
              WHERE tenant_id = ?
                AND state = 'QUEUED'
                AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
                AND attempt < max_attempts
                AND next_run_at <= clock_timestamp()
              ORDER BY next_run_at, created_at, id
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

    /**
     * One tenant's head job in claim order, with its {@code RUNNING} count.
     * {@link #claimNext(String)} ranks tenants by these rows; no row means
     * nothing is eligible.
     */
    private static final String HEAD_SQL = """
            SELECT j.next_run_at, j.created_at, j.id,
                   (SELECT count(*) FROM jobs r WHERE r.tenant_id = ? AND r.state = 'RUNNING') AS running
            FROM jobs j
            WHERE j.tenant_id = ?
              AND j.state = 'QUEUED'
              AND (j.lease_expires_at IS NULL OR j.lease_expires_at <= clock_timestamp())
              AND j.attempt < j.max_attempts
              AND j.next_run_at <= clock_timestamp()
            ORDER BY j.next_run_at, j.created_at, j.id
            LIMIT 1
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

    /**
     * Capability issue: first grant starts at epoch 1 with a 1h DB-clock TTL;
     * re-issue supersedes by bumping epoch and clearing revoked with a fresh
     * TTL. Single row per (tenant, job) keeps one authoritative grant.
     */
    private static final String ISSUE_CAP_SQL = """
            INSERT INTO cap_grants (tenant_id, job_id, epoch, revoked, expires_at)
            VALUES (?, ?, 1, FALSE, clock_timestamp() + INTERVAL '1 hour')
            ON CONFLICT (tenant_id, job_id) DO UPDATE SET
              epoch = cap_grants.epoch + 1,
              revoked = FALSE,
              expires_at = clock_timestamp() + INTERVAL '1 hour',
              updated_at = clock_timestamp()
            RETURNING id, tenant_id, job_id, epoch, expires_at
            """;

    /**
     * Current-grant lock for the 4-arg commit path: one authoritative row per
     * (tenant, job), locked FOR UPDATE in the same txn as the effect.
     */
    private static final String SELECT_CURRENT_GRANT_SQL = """
            SELECT epoch, revoked, expires_at
            FROM cap_grants
            WHERE tenant_id = ?
              AND job_id = ?
            FOR UPDATE
            """;

    /**
     * Presented-capability lock for the explicit-cap commit path: the grant
     * id plus tenant plus job must all match, locked FOR UPDATE. A cap issued
     * for tenant A yields zero rows when presented with tenant B even when
     * epoch/expiry are otherwise valid.
     */
    private static final String SELECT_PRESENTED_GRANT_SQL = """
            SELECT epoch, revoked, expires_at
            FROM cap_grants
            WHERE id = ?
              AND tenant_id = ?
              AND job_id = ?
            FOR UPDATE
            """;

    private static final String REVOKE_CAP_SQL = """
            UPDATE cap_grants
            SET revoked = TRUE,
                updated_at = clock_timestamp()
            WHERE tenant_id = ?
              AND job_id = ?
            """;

    private static final String STORED_TIMELINE_SQL = """
            SELECT system_identifier, timeline_id
            FROM db_timeline
            WHERE id = 1
            FOR UPDATE
            """;

    private static final String LIVE_LEASE_SQL = """
            SELECT EXISTS (
              SELECT 1 FROM jobs
              WHERE tenant_id = ?
                AND state = 'RUNNING'
                AND lease_expires_at > clock_timestamp()
            )
            """;

    /**
     * A claim needs {@code attempt < max_attempts} and raises token and
     * attempt together, so the lost timeline issued at most
     * {@code max_attempts - attempt} tokens past the restored one. Adding
     * {@code max_attempts} puts every open job at or above them, and strictly
     * above for {@code RUNNING} rows ({@code attempt >= 1}); a
     * {@code QUEUED} row needs a fresh claim (+1) before any commit.
     */
    private static final String RAISE_TOKENS_SQL = """
            UPDATE jobs
            SET fencing_token = fencing_token + max_attempts,
                updated_at = clock_timestamp()
            WHERE tenant_id = ?
              AND state IN ('QUEUED', 'RUNNING')
            """;

    /**
     * Deleting (not revoking) grants means a re-issue creates a new grant id,
     * so a grant id and epoch held from the lost timeline never match again.
     */
    private static final String DELETE_CAPS_SQL = """
            DELETE FROM cap_grants
            WHERE tenant_id = ?
            RETURNING job_id, epoch
            """;

    private static final String UPSERT_TIMELINE_SQL = """
            INSERT INTO db_timeline (id, system_identifier, timeline_id, confirmed_at)
            VALUES (1, ?, ?, clock_timestamp())
            ON CONFLICT (id) DO UPDATE SET
              system_identifier = EXCLUDED.system_identifier,
              timeline_id = EXCLUDED.timeline_id,
              confirmed_at = EXCLUDED.confirmed_at
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SaturationSignals signals;

    /** Rejection counter for capability-denied commits ({@code cap_rejected}). */
    private final AtomicLong capRejected = new AtomicLong();

    public JobService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this(jdbc, txManager, null);
    }

    @Autowired
    public JobService(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            SaturationSignals signals) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.signals = signals;
    }

    /**
     * Atomically claims one of {@code tenantId}'s claimable jobs for
     * {@code owner}, in the order {@code (next_run_at, created_at, id)}.
     *
     * @return the claimed job with its new fencing token and attempt, or empty
     *         when the tenant already holds {@code max_running} jobs in
     *         {@code RUNNING}, or when no {@code QUEUED} job with
     *         {@code attempt < max_attempts}, an unheld/expired lease and
     *         {@code next_run_at <= clock_timestamp()} is available (includes
     *         the case where every job is terminal, running, or exhausted).
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         when the timeline is unknown, or rewound and
     *         {@link #confirmTimeline()} can't confirm it yet.
     */
    public Optional<ClaimedJob> claim(String tenantId, String owner) {
        String tenant = requireTenant(tenantId);
        String worker = requireOwner(owner);
        long start = System.nanoTime();
        try {
            return confirmingTimeline(() -> claimOnce(tenant, worker));
        } finally {
            if (signals != null) {
                signals.recordClaimNanos(System.nanoTime() - start);
            }
        }
    }

    /**
     * Claims the next job across every tenant for {@code owner}: the fair
     * claim (issue #35, record 0020).
     *
     * <p>One transaction reads each tenant's quota, {@code RUNNING} count and
     * head job, each under that tenant's own context, and drops tenants at
     * their quota or with nothing eligible. The rest are ranked by their head
     * job's {@code (penalty, next_run_at, created_at, id)}, where the penalty
     * is the tenant's {@code RUNNING} count, so a tenant running fewer jobs
     * goes first. Then each tenant in rank order is tried with the same claim
     * as {@link #claim(String, String)}, which re-checks the quota under the
     * tenant's lock; the first job claimed is returned. A tenant whose head
     * another worker took in between is skipped, so the order is exact for a
     * single worker and best-effort under concurrency, while the quota is
     * always exact.
     *
     * @return the claimed job and its tenant, or empty when no tenant under
     *         its quota has an eligible job.
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         when the timeline is unknown, or rewound and
     *         {@link #confirmTimeline()} can't confirm it yet.
     */
    public Optional<TenantClaim> claimNext(String owner) {
        String worker = requireOwner(owner);
        long start = System.nanoTime();
        try {
            return confirmingTimeline(() -> {
                for (String tenant : rankTenants()) {
                    Optional<ClaimedJob> claimed = claimOnce(tenant, worker);
                    if (claimed.isPresent()) {
                        return Optional.of(new TenantClaim(tenant, claimed.get()));
                    }
                }
                return Optional.<TenantClaim>empty();
            });
        } finally {
            if (signals != null) {
                signals.recordClaimNanos(System.nanoTime() - start);
            }
        }
    }

    /**
     * Runs a claim; when the timeline gate refuses, tries to confirm the
     * timeline once and runs it again. Workers poll claim, so the first poll
     * after leases expire reopens the gate without an operator step.
     */
    private <T> T confirmingTimeline(Supplier<T> attempt) {
        try {
            return attempt.get();
        } catch (TimelineUnconfirmedException e) {
            if (confirmTimeline() != TimelineStatus.CONFIRMED) {
                throw e;
            }
            return attempt.get();
        }
    }

    /** Claim order of two tenants' head jobs; see {@link #claimNext(String)}. */
    private static final Comparator<Head> CLAIM_ORDER = Comparator
            .comparingLong(Head::running)
            .thenComparing(h -> h.nextRunAt().toInstant())
            .thenComparing(h -> h.createdAt().toInstant())
            // Postgres orders uuid bytewise, which is the order of the lowercase text form.
            .thenComparing(h -> h.id().toString());

    /** A tenant's head job in claim order, with its {@code RUNNING} count. */
    private record Head(String tenant, OffsetDateTime nextRunAt, OffsetDateTime createdAt, UUID id, long running) {
    }

    /** Tenants under their quota with an eligible job, best first. */
    private List<String> rankTenants() {
        record Quota(String tenant, int maxRunning) {
        }
        List<Head> ranked = tx.execute(status -> {
            TimelineGuard.requireConfirmed(jdbc);
            List<Quota> quotas = jdbc.query("SELECT id, max_running FROM tenants ORDER BY id",
                    (rs, i) -> new Quota(rs.getString("id"), rs.getInt("max_running")));
            List<Head> heads = new ArrayList<>();
            for (Quota quota : quotas) {
                // One tenant context per statement, as in confirmTimeline.
                TenantGuard.setTenant(jdbc, quota.tenant());
                List<Head> head = jdbc.query(HEAD_SQL,
                        (rs, i) -> new Head(
                                quota.tenant(),
                                rs.getObject("next_run_at", OffsetDateTime.class),
                                rs.getObject("created_at", OffsetDateTime.class),
                                (UUID) rs.getObject("id"),
                                rs.getLong("running")),
                        quota.tenant(), quota.tenant());
                if (!head.isEmpty() && head.get(0).running() < quota.maxRunning()) {
                    heads.add(head.get(0));
                }
            }
            heads.sort(CLAIM_ORDER);
            return heads;
        });
        return ranked == null ? List.of() : ranked.stream().map(Head::tenant).toList();
    }

    private Optional<ClaimedJob> claimOnce(String tenant, String worker) {
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            TimelineGuard.requireConfirmed(jdbc);
            jdbc.query(CLAIM_LOCK_SQL, rs -> {
            }, tenant);
            if (!underQuota(tenant)) {
                return Optional.<ClaimedJob>empty();
            }
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
            // Same-transaction audit: attempt/owner/token/lease excluded from
            // payload by contract; heartbeat is never logged.
            AuditChain.append(jdbc, tenant, claimed.jobId(), AuditChain.TYPE_CLAIM,
                    java.util.Map.of("state", "RUNNING"));
            return Optional.of(claimed);
        });
    }

    /** Whether the tenant holds fewer {@code RUNNING} jobs than its quota. */
    private boolean underQuota(String tenant) {
        List<Boolean> rows = jdbc.query(QUOTA_SQL,
                (rs, i) -> rs.getLong("running") < rs.getInt("max_running"),
                tenant, tenant);
        return !rows.isEmpty() && rows.get(0);
    }

    /**
     * Extends the lease only when the caller still holds a live lease:
     * same owner, same fencing token, {@code RUNNING}, and
     * {@code lease_expires_at > clock_timestamp()}.
     *
     * @return {@code true} when exactly one row was extended.
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         while the timeline is unknown or rewound, so a lost-timeline
     *         worker can't keep a restored lease alive.
     */
    public boolean heartbeat(String tenantId, UUID jobId, String owner, long fencingToken) {
        String tenant = requireTenant(tenantId);
        String worker = requireOwner(owner);
        requireJobId(jobId);
        Boolean ok = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            TimelineGuard.requireConfirmed(jdbc);
            int updated = jdbc.update(HEARTBEAT_SQL, jobId, tenant, worker, fencingToken);
            return updated == 1;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Commits a running job to {@code SUCCEEDED} with full fencing plus
     * capability gating (issue #21).
     *
     * <p>The current {@code cap_grants} row for {@code (tenant_id, job_id)} is
     * locked {@code FOR UPDATE} in the same transaction after tenant context
     * and before any {@code jobs} write; missing, revoked, or expired grants
     * deny atomically (zero rows, {@code 403 NEEDS_APPROVAL},
     * {@code cap_rejected} incremented). A zombie that lost ownership affects
     * zero rows and returns {@code false} (no exception, no counter).
     *
     * @return {@code true} when exactly one row transitioned.
     * @throws CapDeniedException ({@code 403 NEEDS_APPROVAL}) when no valid
     *         capability authorizes this commit.
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         while the timeline is unknown or rewound.
     */
    public boolean commitSucceeded(String tenantId, UUID jobId, String owner, long fencingToken) {
        return commit(tenantId, jobId, owner, fencingToken, "SUCCEEDED");
    }

    /**
     * Commits a running job to {@code FAILED} with full fencing plus
     * capability gating (issue #21).
     *
     * @return {@code true} when exactly one row transitioned.
     * @throws CapDeniedException ({@code 403 NEEDS_APPROVAL}) when no valid
     *         capability authorizes this commit.
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         while the timeline is unknown or rewound.
     */
    public boolean commitFailed(String tenantId, UUID jobId, String owner, long fencingToken) {
        return commit(tenantId, jobId, owner, fencingToken, "FAILED");
    }

    /**
     * Commits to {@code SUCCEEDED} presenting an explicit capability.
     *
     * <p>Locks {@code WHERE id = ? AND tenant_id = ? AND job_id = ? FOR UPDATE}
     * so a cap issued for tenant A yields zero rows for tenant B even with a
     * valid epoch/expiry; a stale {@code capEpoch} denies as superseded.
     *
     * @return {@code true} when exactly one row transitioned.
     * @throws CapDeniedException ({@code 403 NEEDS_APPROVAL}) on missing,
     *         revoked, expired, superseded, or cross-tenant capability.
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         while the timeline is unknown or rewound.
     */
    public boolean commitSucceeded(
            String tenantId, UUID jobId, String owner, long fencingToken, UUID capId, long capEpoch) {
        return commitWithCap(tenantId, jobId, owner, fencingToken, "SUCCEEDED", capId, capEpoch);
    }

    /**
     * Commits to {@code FAILED} presenting an explicit capability.
     *
     * @return {@code true} when exactly one row transitioned.
     * @throws CapDeniedException ({@code 403 NEEDS_APPROVAL}) on missing,
     *         revoked, expired, superseded, or cross-tenant capability.
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         while the timeline is unknown or rewound.
     */
    public boolean commitFailed(
            String tenantId, UUID jobId, String owner, long fencingToken, UUID capId, long capEpoch) {
        return commitWithCap(tenantId, jobId, owner, fencingToken, "FAILED", capId, capEpoch);
    }

    private boolean commit(String tenantId, UUID jobId, String owner, long fencingToken, String targetState) {
        String tenant = requireTenant(tenantId);
        String worker = requireOwner(owner);
        requireJobId(jobId);
        Boolean ok = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            TimelineGuard.requireConfirmed(jdbc);
            requireCurrentGrantValid(tenant, jobId);
            int updated = jdbc.update(COMMIT_SQL_PREFIX, targetState, jobId, tenant, worker, fencingToken);
            if (updated == 1) {
                // Same-transaction audit; fencing misses (0 rows) never append.
                AuditChain.append(jdbc, tenant, jobId, AuditChain.TYPE_COMMIT,
                        java.util.Map.of("outcome", targetState));
            }
            return updated == 1;
        });
        return Boolean.TRUE.equals(ok);
    }

    private boolean commitWithCap(
            String tenantId, UUID jobId, String owner, long fencingToken, String targetState,
            UUID capId, long capEpoch) {
        String tenant = requireTenant(tenantId);
        String worker = requireOwner(owner);
        requireJobId(jobId);
        if (capId == null) {
            throw new IllegalArgumentException("capId must not be null");
        }
        Boolean ok = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            TimelineGuard.requireConfirmed(jdbc);
            requirePresentedGrantValid(tenant, jobId, capId, capEpoch);
            int updated = jdbc.update(COMMIT_SQL_PREFIX, targetState, jobId, tenant, worker, fencingToken);
            if (updated == 1) {
                AuditChain.append(jdbc, tenant, jobId, AuditChain.TYPE_COMMIT,
                        java.util.Map.of("outcome", targetState));
            }
            return updated == 1;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Issues (or supersedes) the capability grant for one job.
     *
     * <p>First issue creates epoch 1 with
     * {@code expires_at = clock_timestamp() + 1 hour} (DB clock only);
     * re-issue bumps {@code epoch + 1}, clears {@code revoked}, and refreshes
     * expiry, superseding any previously presented epoch. Tenant-scoped
     * ({@code WHERE tenant_id} plus RLS) with a composite
     * {@code FOREIGN KEY(tenant_id, job_id)} so a grant cannot reference
     * another tenant's job.
     *
     * @return the current grant identity, epoch, and expiry.
     */
    public CapGrant issueCap(String tenantId, UUID jobId) {
        String tenant = requireTenant(tenantId);
        requireJobId(jobId);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<CapGrant> rows = jdbc.query(
                    ISSUE_CAP_SQL,
                    (rs, i) -> new CapGrant(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            (UUID) rs.getObject("job_id"),
                            rs.getLong("epoch"),
                            rs.getObject("expires_at", OffsetDateTime.class)),
                    tenant, jobId);
            if (rows.isEmpty()) {
                throw new IllegalStateException("cap issue returned no row");
            }
            CapGrant grant = rows.get(0);
            // Same-transaction audit: epoch link for replay.
            AuditChain.append(jdbc, tenant, jobId, AuditChain.TYPE_CAP_ISSUE,
                    java.util.Map.of("epoch", grant.epoch(), "cap_id", grant.capId().toString()));
            return grant;
        });
    }

    /**
     * Revokes the grant for one job mid-run: the next commit in another
     * transaction observes {@code revoked=TRUE} under {@code FOR UPDATE} and
     * denies atomically. Idempotent; zero rows when no grant exists (the
     * subsequent commit still denies as missing).
     */
    public void revokeCap(String tenantId, UUID jobId) {
        String tenant = requireTenant(tenantId);
        requireJobId(jobId);
        tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            List<Long> epochs = jdbc.query(
                    "SELECT epoch FROM cap_grants WHERE tenant_id = ? AND job_id = ? FOR UPDATE",
                    (rs, i) -> rs.getLong("epoch"), tenant, jobId);
            int updated = jdbc.update(REVOKE_CAP_SQL, tenant, jobId);
            if (updated == 1 && !epochs.isEmpty()) {
                // Same-transaction audit with the revoked epoch.
                AuditChain.append(jdbc, tenant, jobId, AuditChain.TYPE_CAP_REVOKE,
                        java.util.Map.of("epoch", epochs.get(0)));
            }
            return null;
        });
    }

    /** Rejection counter for capability-denied commits ({@code cap_rejected}). */
    public long capRejectedCount() {
        return capRejected.get();
    }

    /**
     * Fetches the current valid grant for presigned-CAS issue (issue #22 P01/P06).
     *
     * <p>Own transaction with {@link TenantGuard#setTenant}: locks the current
     * grant {@code FOR UPDATE} and validates {@code revoked + expires_at} with
     * the database clock only. Missing/revoked/expired denies with
     * {@link CapDeniedException} ({@code 403 NEEDS_APPROVAL}) and increments
     * {@code cap_rejected}, sharing the commit-gate counter.
     */
    public CapGrant fetchValidCurrentGrant(String tenantId, UUID jobId) {
        String tenant = requireTenant(tenantId);
        requireJobId(jobId);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            record FullGrant(UUID id, long epoch, boolean revoked, OffsetDateTime expiresAt) {
            }
            List<FullGrant> rows = jdbc.query(
                    "SELECT id, epoch, revoked, expires_at FROM cap_grants "
                            + "WHERE tenant_id = ? AND job_id = ? FOR UPDATE",
                    (rs, i) -> new FullGrant(
                            (UUID) rs.getObject("id"),
                            rs.getLong("epoch"),
                            rs.getBoolean("revoked"),
                            rs.getObject("expires_at", OffsetDateTime.class)),
                    tenant, jobId);
            if (rows.isEmpty()) {
                capRejected.incrementAndGet();
                throw new CapDeniedException("no grant for job");
            }
            FullGrant row = rows.get(0);
            OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
            if (row.revoked()) {
                capRejected.incrementAndGet();
                throw new CapDeniedException("revoked");
            }
            if (row.expiresAt() == null || now == null || !row.expiresAt().isAfter(now)) {
                capRejected.incrementAndGet();
                throw new CapDeniedException("expired");
            }
            return new CapGrant(row.id(), tenant, jobId, row.epoch(), row.expiresAt());
        });
    }

    /**
     * Validates a presented grant for presigned-CAS verify (issue #22 P03).
     *
     * <p>Own transaction with {@link TenantGuard#setTenant}: locks
     * {@code WHERE id = ? AND tenant_id = ? AND job_id = ? FOR UPDATE} so a cap
     * issued for tenant A yields zero rows for tenant B. Missing, revoked,
     * expired, or superseded (epoch mismatch) denies with
     * {@link CapDeniedException} ({@code 403 NEEDS_APPROVAL}) and increments
     * {@code cap_rejected}, sharing the commit-gate counter.
     */
    public void checkPresentedGrant(String tenantId, UUID jobId, UUID capId, long epoch) {
        String tenant = requireTenant(tenantId);
        requireJobId(jobId);
        if (capId == null) {
            throw new IllegalArgumentException("capId must not be null");
        }
        tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            requirePresentedGrantValid(tenant, jobId, capId, epoch);
            return null;
        });
    }

    private void requireCurrentGrantValid(String tenant, UUID jobId) {
        record GrantRow(long epoch, boolean revoked, OffsetDateTime expiresAt) {
        }
        List<GrantRow> rows = jdbc.query(
                SELECT_CURRENT_GRANT_SQL,
                (rs, i) -> new GrantRow(
                        rs.getLong("epoch"),
                        rs.getBoolean("revoked"),
                        rs.getObject("expires_at", OffsetDateTime.class)),
                tenant, jobId);
        if (rows.isEmpty()) {
            capRejected.incrementAndGet();
            throw new CapDeniedException("no grant for job");
        }
        GrantRow row = rows.get(0);
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (row.revoked()) {
            capRejected.incrementAndGet();
            throw new CapDeniedException("revoked");
        }
        if (row.expiresAt() == null || now == null || !row.expiresAt().isAfter(now)) {
            capRejected.incrementAndGet();
            throw new CapDeniedException("expired");
        }
    }

    private void requirePresentedGrantValid(String tenant, UUID jobId, UUID capId, long expectedEpoch) {
        record GrantRow(long epoch, boolean revoked, OffsetDateTime expiresAt) {
        }
        List<GrantRow> rows = jdbc.query(
                SELECT_PRESENTED_GRANT_SQL,
                (rs, i) -> new GrantRow(
                        rs.getLong("epoch"),
                        rs.getBoolean("revoked"),
                        rs.getObject("expires_at", OffsetDateTime.class)),
                capId, tenant, jobId);
        if (rows.isEmpty()) {
            capRejected.incrementAndGet();
            throw new CapDeniedException("no grant for cap+tenant+job");
        }
        GrantRow row = rows.get(0);
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (row.epoch() != expectedEpoch) {
            capRejected.incrementAndGet();
            throw new CapDeniedException("superseded: presented epoch " + expectedEpoch
                    + " != current " + row.epoch());
        }
        if (row.revoked()) {
            capRejected.incrementAndGet();
            throw new CapDeniedException("revoked");
        }
        if (row.expiresAt() == null || now == null || !row.expiresAt().isAfter(now)) {
            capRejected.incrementAndGet();
            throw new CapDeniedException("expired");
        }
    }

    /**
     * Confirms the database timeline after a restore (issue #34, record 0016).
     *
     * <p>One transaction, so the gate reopens only together with its fix-ups:
     * lock the {@code db_timeline} row (serializing concurrent confirms);
     * when it already matches the server, return {@code CONFIRMED}. Otherwise,
     * per tenant under {@link TenantGuard#setTenant} (each statement keeps a
     * single tenant context and {@code WHERE tenant_id}), roll back with
     * {@code WAITING_FOR_LEASES} if any lease is live on the database clock;
     * else raise open jobs' fencing tokens by {@code max_attempts} and delete
     * every grant, auditing each as {@code cap.revoke}. Finally store the
     * server's pair. An unreadable timeline returns {@code UNKNOWN} and
     * changes nothing.
     *
     * <p>Database-wide: the timeline belongs to the whole database, so one
     * call, from any tenant's claim, waits on and fixes up every tenant.
     */
    public TimelineStatus confirmTimeline() {
        return tx.execute(status -> {
            Optional<TimelineGuard.Timeline> current = TimelineGuard.current(jdbc);
            if (current.isEmpty()) {
                status.setRollbackOnly();
                return TimelineStatus.UNKNOWN;
            }
            TimelineGuard.Timeline server = current.get();
            List<TimelineGuard.Timeline> stored = jdbc.query(STORED_TIMELINE_SQL,
                    (rs, i) -> new TimelineGuard.Timeline(
                            rs.getLong("system_identifier"), rs.getLong("timeline_id")));
            if (stored.equals(List.of(server))) {
                return TimelineStatus.CONFIRMED;
            }
            List<String> tenants = jdbc.query("SELECT id FROM tenants", (rs, i) -> rs.getString("id"));
            for (String tenant : tenants) {
                TenantGuard.setTenant(jdbc, tenant);
                if (Boolean.TRUE.equals(jdbc.queryForObject(LIVE_LEASE_SQL, Boolean.class, tenant))) {
                    status.setRollbackOnly();
                    return TimelineStatus.WAITING_FOR_LEASES;
                }
                jdbc.update(RAISE_TOKENS_SQL, tenant);
                record Deleted(UUID jobId, long epoch) {
                }
                List<Deleted> deleted = jdbc.query(DELETE_CAPS_SQL,
                        (rs, i) -> new Deleted((UUID) rs.getObject("job_id"), rs.getLong("epoch")),
                        tenant);
                for (Deleted grant : deleted) {
                    AuditChain.append(jdbc, tenant, grant.jobId(), AuditChain.TYPE_CAP_REVOKE,
                            java.util.Map.of("epoch", grant.epoch()));
                }
            }
            jdbc.update(UPSERT_TIMELINE_SQL, server.systemIdentifier(), server.timelineId());
            return TimelineStatus.CONFIRMED;
        });
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
            TenantGuard.setTenant(jdbc, tenant);
            // DEAD transitions are audited as commit/DEAD in the same txn;
            // reclaim to QUEUED and empty polls are never logged (issue #28).
            List<UUID> deadQueuedIds = jdbc.query(
                    "UPDATE jobs SET state = 'DEAD', updated_at = clock_timestamp() "
                            + "WHERE tenant_id = ? AND state = 'QUEUED' AND attempt >= max_attempts "
                            + "RETURNING id",
                    (rs, i) -> (UUID) rs.getObject("id"), tenant);
            List<UUID> deadRunningIds = jdbc.query(
                    "UPDATE jobs SET state = 'DEAD', updated_at = clock_timestamp() "
                            + "WHERE tenant_id = ? AND state = 'RUNNING' "
                            + "AND lease_expires_at <= clock_timestamp() AND attempt >= max_attempts "
                            + "RETURNING id",
                    (rs, i) -> (UUID) rs.getObject("id"), tenant);
            int released = jdbc.update(
                    "UPDATE jobs SET state = 'QUEUED', owner = NULL, lease_expires_at = NULL, "
                            + "updated_at = clock_timestamp() "
                            + "WHERE tenant_id = ? AND state = 'RUNNING' "
                            + "AND lease_expires_at <= clock_timestamp() AND attempt < max_attempts",
                    tenant);
            for (UUID deadId : deadQueuedIds) {
                AuditChain.append(jdbc, tenant, deadId, AuditChain.TYPE_COMMIT,
                        java.util.Map.of("outcome", "DEAD"));
            }
            for (UUID deadId : deadRunningIds) {
                AuditChain.append(jdbc, tenant, deadId, AuditChain.TYPE_COMMIT,
                        java.util.Map.of("outcome", "DEAD"));
            }
            return new ReconcileResult(released, deadQueuedIds.size() + deadRunningIds.size());
        });
    }

    /**
     * Single reconciler pass across all tenants. Intended cadence is
     * {@link #RECONCILER_POLL_SECONDS}s in polling deployments; tests invoke
     * this (or {@link #reconcile(String)}) directly for determinism.
     *
     * <p>Each tenant reconciles in its own transaction with its own
     * {@code SET LOCAL app.tenant}: one transaction cannot hold two tenants,
     * so a shared global {@code UPDATE} without {@code WHERE tenant_id = ?}
     * is never issued (issue #20).
     */
    public ReconcileResult reconcileAll() {
        List<String> tenants = jdbc.query("SELECT id FROM tenants", (rs, i) -> rs.getString("id"));
        int released = 0;
        int dead = 0;
        for (String tenant : tenants) {
            ReconcileResult one = reconcile(tenant);
            if (one != null) {
                released += one.releasedToQueued();
                dead += one.markedDead();
            }
        }
        return new ReconcileResult(released, dead);
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
