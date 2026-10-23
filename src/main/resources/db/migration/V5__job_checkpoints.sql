-- V5 per-job replay checkpoints for checkpoint-accelerated deterministic replay (issue #30).
--
-- Table job_checkpoints is the durable acceleration index over the immutable
-- hash chain in audit_events (V4):
--   * one row per 100 events per job: (tenant_id, job_id, seq, sha) with seq
--     0-based, sha = chain event_hash at the boundary (seq % 100 == 0, seq > 0),
--     NOT output_sha. Interval 100 is owned here; change requires ADR + test.
--   * UNIQUE(job_id, seq) rejects duplicate positions; composite
--     FOREIGN KEY(tenant_id, job_id) REFERENCES jobs(tenant_id, id)
--     ON DELETE CASCADE backed by UNIQUE(tenant_id, id), so tenant A cannot
--     checkpoint tenant B's job; per-tenant shape mirrors audit_events/cap_grants.
--   * written synchronously in the same DB transaction as the audited effect
--     (see AuditChain.append): tail audit INSERT plus checkpoint INSERT commit
--     or roll back together (no orphan in either direction).
--   * append-only like audit_events: BEFORE UPDATE OR DELETE trigger rejects all
--     mutations for every role (including migrator/superuser; cleanup uses
--     TRUNCATE which does not fire row triggers). Runtime role holds only
--     SELECT+INSERT (REVOKE ALL then GRANT minimal, defeating the V2
--     default-privilege grant of UPDATE/DELETE to future tables). TRUNCATE as
--     migrator/superuser still works for test cleanup; row DELETE (including
--     FK-cascade from jobs) is rejected, so cleanup must TRUNCATE
--     job_checkpoints (and audit_events) before deleting jobs.
--   * RLS FORCE + tenant_isolation policy mirrors all other tenant tables:
--     missing app.tenant yields NULL = not-true, i.e. 0 rows (fail closed).
--   * created_at defaults to now() (DB clock); it is never part of any hash.
-- Residual: checkpoint deletion/GC deferred (no retention in v1); checkpoints
-- are rebuilt by re-replay. Replay reads (ReplayService) are read-only and
-- never write checkpoints. Offline verifier/CLI deferred (out of scope).

CREATE TABLE job_checkpoints (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    job_id UUID NOT NULL,
    seq BIGINT NOT NULL CHECK (seq >= 0),
    sha TEXT NOT NULL CHECK (sha ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, seq),
    FOREIGN KEY (tenant_id, job_id) REFERENCES jobs (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX job_checkpoints_job_seq_idx ON job_checkpoints (job_id, seq);
CREATE INDEX job_checkpoints_tenant_job_seq_idx ON job_checkpoints (tenant_id, job_id, seq);

-- Ownership + least privilege mirror V4, then strip UPDATE/DELETE.
ALTER TABLE job_checkpoints OWNER TO anvil_migrator;
REVOKE ALL ON TABLE job_checkpoints FROM anvil_app;
GRANT SELECT, INSERT ON TABLE job_checkpoints TO anvil_app;

-- RLS FORCE with the same tenant isolation policy as all other tenant tables.
ALTER TABLE job_checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_checkpoints FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON job_checkpoints;
CREATE POLICY tenant_isolation ON job_checkpoints FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

-- Append-only guard: any UPDATE or DELETE fails at the database, for every role
-- (including the migrator/superuser; cleanup uses TRUNCATE which does not fire
-- row triggers). The runtime grant layer above is defense-in-depth.
CREATE OR REPLACE FUNCTION reject_job_checkpoints_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'job_checkpoints is append-only: % not allowed', TG_OP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS job_checkpoints_immutable ON job_checkpoints;
CREATE TRIGGER job_checkpoints_immutable
    BEFORE UPDATE OR DELETE ON job_checkpoints
    FOR EACH ROW EXECUTE FUNCTION reject_job_checkpoints_mutation();

ALTER FUNCTION reject_job_checkpoints_mutation() OWNER TO anvil_migrator;

COMMENT ON FUNCTION reject_job_checkpoints_mutation() IS
    'SECURITY INVOKER only; no DEFINER path for the app role (issue #30).';
