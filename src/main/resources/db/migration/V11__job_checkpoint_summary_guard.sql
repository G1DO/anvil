-- V11 checkpoint summary guard (issue #76 review).
--
-- V10 let the writer compute the prefix summary (last artifact, input root,
-- last commit) and replay trusted it. The runtime role can INSERT a checkpoint
-- row, so a forged row at an uncheckpointed seq with a copied event hash could
-- carry a fake summary. Two type-filtered prefix scans per checkpoint also ran
-- without an index on (tenant_id, job_id, type, seq).
--
-- This migration:
--   * adds audit_events_tenant_job_type_seq_idx for the summary lookups;
--   * adds a BEFORE INSERT trigger on job_checkpoints that requires a positive
--     100-event boundary, requires the audit event at exactly that seq and a
--     matching sha, rejects a boundary event that does not link to seq - 1,
--     then derives the summary from the chain itself (first submit root, last
--     valid cas.put artifact, last commit). A writer cannot choose
--     artifact_sha, input_sha, commit_seq or commit_outcome: the trigger
--     overwrites them. A non-boundary seq, a missing or unlinked event, or a
--     wrong sha is rejected.
--
-- The function stays SECURITY INVOKER and is owned by anvil_migrator. Inserts
-- by anvil_app execute it as anvil_app, so the tenant-filtered SELECTs run
-- under the same RLS context (app.tenant) as the insert. The search_path is
-- pinned. Pre-V10 rows (nullable summary) are unchanged; replay still falls
-- back to the full chain for them.

CREATE INDEX audit_events_tenant_job_type_seq_idx
    ON audit_events (tenant_id, job_id, type, seq);

CREATE OR REPLACE FUNCTION guard_job_checkpoint_summary() RETURNS trigger
LANGUAGE plpgsql
SET search_path = public
AS $$
DECLARE
    boundary_hash TEXT;
    boundary_prev TEXT;
    previous_hash TEXT;
    artifact TEXT;
    input_root TEXT;
    commit_at BIGINT;
    commit_result TEXT;
BEGIN
    IF NEW.seq <= 0 OR NEW.seq % 100 <> 0 THEN
        RAISE EXCEPTION 'job_checkpoints seq % is not a positive 100-event boundary', NEW.seq;
    END IF;

    SELECT e.event_hash, e.prev_hash INTO boundary_hash, boundary_prev
      FROM public.audit_events e
     WHERE e.tenant_id = NEW.tenant_id
       AND e.job_id = NEW.job_id
       AND e.seq = NEW.seq;
    IF boundary_hash IS NULL THEN
        RAISE EXCEPTION 'job_checkpoints seq % has no audit event', NEW.seq;
    END IF;
    IF NEW.sha IS DISTINCT FROM boundary_hash THEN
        RAISE EXCEPTION 'job_checkpoints sha does not match the audit event at seq %', NEW.seq;
    END IF;

    -- A checkpoint must not jump over a gap: the boundary event has to link to
    -- the event immediately before it, so the fast path cannot start past
    -- unseen events.
    IF NEW.seq > 0 THEN
        SELECT e.event_hash INTO previous_hash
          FROM public.audit_events e
         WHERE e.tenant_id = NEW.tenant_id
           AND e.job_id = NEW.job_id
           AND e.seq = NEW.seq - 1;
        IF previous_hash IS NULL OR previous_hash IS DISTINCT FROM boundary_prev THEN
            RAISE EXCEPTION 'job_checkpoints seq % is not linked to the event at seq %',
                NEW.seq, NEW.seq - 1;
        END IF;
    END IF;

    -- Same rule as ReplayService.lastArtifactSha: the last valid cas.put
    -- artifact at or before the boundary, else the empty string.
    SELECT e.payload->>'artifact_sha' INTO artifact
      FROM public.audit_events e
     WHERE e.tenant_id = NEW.tenant_id
       AND e.job_id = NEW.job_id
       AND e.type = 'cas.put'
       AND e.seq <= NEW.seq
       AND e.payload->>'artifact_sha' ~ '^[0-9a-f]{64}$'
     ORDER BY e.seq DESC
     LIMIT 1;

    -- The input root comes from the first hashed submit event at or before the
    -- boundary (ReplayService.bindHeader uses the first), never from the mutable
    -- jobs/document_versions link.
    SELECT e.payload->>'sha256' INTO input_root
      FROM public.audit_events e
     WHERE e.tenant_id = NEW.tenant_id
       AND e.job_id = NEW.job_id
       AND e.type = 'submit'
       AND e.seq <= NEW.seq
     ORDER BY e.seq ASC
     LIMIT 1;
    IF input_root IS NULL THEN
        RAISE EXCEPTION 'job_checkpoints seq % has no submit event with an input root', NEW.seq;
    END IF;

    SELECT e.seq, e.payload->>'outcome' INTO commit_at, commit_result
      FROM public.audit_events e
     WHERE e.tenant_id = NEW.tenant_id
       AND e.job_id = NEW.job_id
       AND e.type = 'commit'
       AND e.seq <= NEW.seq
     ORDER BY e.seq DESC
     LIMIT 1;
    IF commit_result IS NULL THEN
        commit_at := NULL;
    END IF;

    NEW.artifact_sha := COALESCE(artifact, '');
    NEW.input_sha := input_root;
    NEW.commit_seq := commit_at;
    NEW.commit_outcome := commit_result;
    RETURN NEW;
END;
$$;

ALTER FUNCTION guard_job_checkpoint_summary() OWNER TO anvil_migrator;

CREATE TRIGGER job_checkpoints_summary_guard
    BEFORE INSERT ON job_checkpoints
    FOR EACH ROW EXECUTE FUNCTION guard_job_checkpoint_summary();

COMMENT ON FUNCTION guard_job_checkpoint_summary() IS
    'Validates the checkpoint boundary and derives its replay summary from audit_events (issue #76).';

-- One-time backfill of V10 rows, which the old writer filled from the job link
-- and not from the hashed submit event. Only rows with some non-null summary
-- are touched; pre-V10 rows stay all-null so replay keeps falling back to the
-- full chain. The append-only guard is disabled for this statement only.
ALTER TABLE job_checkpoints DISABLE TRIGGER job_checkpoints_immutable;
DO $$
DECLARE
    tenant TEXT;
BEGIN
    -- tenants has no RLS; setting app.tenant per tenant also makes the update
    -- work when the migration role is the RLS-forced table owner.
    FOR tenant IN SELECT id FROM public.tenants LOOP
        PERFORM set_config('app.tenant', tenant, true);
        UPDATE public.job_checkpoints c
           SET artifact_sha = COALESCE((
                   SELECT e.payload->>'artifact_sha'
                     FROM public.audit_events e
                    WHERE e.tenant_id = c.tenant_id
                      AND e.job_id = c.job_id
                      AND e.type = 'cas.put'
                      AND e.seq <= c.seq
                      AND e.payload->>'artifact_sha' ~ '^[0-9a-f]{64}$'
                    ORDER BY e.seq DESC
                    LIMIT 1), ''),
               input_sha = (
                   SELECT e.payload->>'sha256'
                     FROM public.audit_events e
                    WHERE e.tenant_id = c.tenant_id
                      AND e.job_id = c.job_id
                      AND e.type = 'submit'
                      AND e.seq <= c.seq
                    ORDER BY e.seq ASC
                    LIMIT 1),
               (commit_seq, commit_outcome) = (
                   SELECT CASE WHEN e.payload->>'outcome' IS NULL THEN NULL ELSE e.seq END,
                          e.payload->>'outcome'
                     FROM public.audit_events e
                    WHERE e.tenant_id = c.tenant_id
                      AND e.job_id = c.job_id
                      AND e.type = 'commit'
                      AND e.seq <= c.seq
                    ORDER BY e.seq DESC
                    LIMIT 1)
         WHERE c.tenant_id = tenant
           AND (c.artifact_sha IS NOT NULL OR c.input_sha IS NOT NULL
                OR c.commit_seq IS NOT NULL OR c.commit_outcome IS NOT NULL);
    END LOOP;
    PERFORM set_config('app.tenant', '', true);
END;
$$;
ALTER TABLE job_checkpoints ENABLE TRIGGER job_checkpoints_immutable;

