-- V7 fair claim across tenants under a per-tenant quota (issue #35, record 0020).
--
--   * tenants.max_running is the quota: the most jobs one tenant may hold in
--     RUNNING at once. A claim counts the tenant's RUNNING rows (an expired lease
--     still counts until the reconciler releases it) and claims nothing at the
--     quota. Claims for one tenant serialize on a transaction-scoped advisory
--     lock, so concurrent claims can't overshoot. The default of 1000 caps no
--     tenant in the current suite; operators set a lower value per tenant.
--   * jobs.next_run_at is when the job may first be claimed, on the database
--     clock: a claim takes only rows with next_run_at <= clock_timestamp().
--     It defaults to now(), like created_at. Rows that exist when V7 runs all
--     get the migration's time, so they tie and keep their arrival order through
--     the created_at, id tiebreak.
--   * Claim order is (penalty, next_run_at, created_at, id). The penalty is the
--     tenant's current RUNNING count, so across tenants the one running fewest
--     jobs goes first. Within one tenant the penalty is the same for every row.
--   * jobs_claim_order_idx serves that order. jobs_claim_idx (V1) stays for its
--     canonical query; dropping it would be a contract step of its own.
--   * claimable_jobs gains the next_run_at predicate, so the view still lists
--     exactly what a claim may take. It stays security_invoker.

ALTER TABLE tenants
    ADD COLUMN max_running INT NOT NULL DEFAULT 1000 CHECK (max_running > 0);

ALTER TABLE jobs
    ADD COLUMN next_run_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX jobs_claim_order_idx ON jobs (tenant_id, next_run_at, created_at, id)
    WHERE state = 'QUEUED';

CREATE OR REPLACE VIEW claimable_jobs WITH (security_invoker = true) AS
    SELECT * FROM jobs
    WHERE state = 'QUEUED'
      AND attempt < max_attempts
      AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
      AND next_run_at <= clock_timestamp();
