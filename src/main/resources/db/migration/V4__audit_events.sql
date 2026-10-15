-- V4 immutable per-job hash-chained audit log (issue #28).
--
-- Table audit_events is the tamper-evident source for replay:
--   * one append-only chain per job: (tenant_id, job_id, seq) with seq 0-based,
--     prev_hash = 'GENESIS' for seq 0 else the previous event_hash.
--   * UNIQUE(job_id, prev_hash) rejects forks: two events cannot share the same
--     predecessor for one job. UNIQUE(job_id, seq) rejects duplicate positions.
--     job_id is globally unique (random UUID), so tenant scoping is carried by
--     the composite FK, not by dedup: no cross-tenant dedup is introduced.
--   * composite FOREIGN KEY(tenant_id, job_id) REFERENCES jobs(tenant_id, id)
--     ON DELETE CASCADE backed by UNIQUE(tenant_id, id), so tenant A cannot log
--     for tenant B's job; per-tenant PK shape mirrors cap_grants/artifacts.
--   * event_hash = sha256_hex(type + 0x00 + job_id + 0x00 + seq + 0x00 + prev_hash
--     + 0x00 + canonical_cbor(payload)) where canonical_cbor uses the existing
--     mapper (SORT_PROPERTIES_ALPHABETICALLY + ORDER_MAP_ENTRIES_BY_KEYS, see
--     CanonicalCbor.java:31-35). Hashed: type, job_id, seq, prev_hash,
--     deterministic payload. Excluded always from payload: owner, fencing_token,
--     lease_expires_at, attempt/attempt_number, created_at/sent_at/updated_at,
--     tenant context (tenant_id/app.tenant). AuditChain.append enforces this.
--   * Logged: submit, claim, commit (outcome SUCCEEDED/FAILED/DEAD), cap.issue
--     /cap.revoke (epoch), cas.put (artifact_sha link). Not logged: heartbeat,
--     reconciler poll (RUNNING->QUEUED reclaim), outbox.sent_at.
--   * Append runs in the same DB transaction as the effect it records (submit,
--     claim+attempt, commit, cap issue/revoke, CAS upsert, reconciler DEAD via
--     UPDATE ... RETURNING); duplicate resubmit returns the winner without a
--     second submit event (no fork). Crash between effect and append rolls back
--     both: no orphan in either direction.
--   * Append-only: BEFORE UPDATE OR DELETE trigger rejects all mutations, and
--     the runtime role holds only SELECT+INSERT (REVOKE ALL then GRANT minimal,
--     defeating the V2 default-privilege grant of UPDATE/DELETE to future
--     tables). TRUNCATE as migrator/superuser still works for test cleanup;
--     row DELETE (including FK-cascade from jobs) is rejected, so cleanup must
--     TRUNCATE audit_events before deleting jobs.
--   * RLS FORCE + tenant_isolation policy mirrors all other tenant tables:
--     missing app.tenant yields NULL = not-true, i.e. 0 rows (fail closed).
--   * created_at defaults to now() (DB clock); it is never part of the hash.
-- Residual: replay/reads/checkpoints/CLI/offline verifier deferred (out of scope).

CREATE TABLE audit_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    job_id UUID NOT NULL,
    seq BIGINT NOT NULL CHECK (seq >= 0),
    prev_hash TEXT NOT NULL CHECK (prev_hash = 'GENESIS' OR prev_hash ~ '^[0-9a-f]{64}$'),
    event_hash TEXT NOT NULL CHECK (event_hash ~ '^[0-9a-f]{64}$'),
    type TEXT NOT NULL CHECK (type IN ('submit', 'claim', 'commit', 'cap.issue', 'cap.revoke', 'cas.put')),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (job_id, seq),
    UNIQUE (job_id, prev_hash),
    FOREIGN KEY (tenant_id, job_id) REFERENCES jobs (tenant_id, id) ON DELETE CASCADE
);

CREATE INDEX audit_events_job_seq_idx ON audit_events (job_id, seq);
CREATE INDEX audit_events_tenant_job_seq_idx ON audit_events (tenant_id, job_id, seq);

-- Ownership + least privilege mirror V2/V3, then strip UPDATE/DELETE.
ALTER TABLE audit_events OWNER TO anvil_migrator;
REVOKE ALL ON TABLE audit_events FROM anvil_app;
GRANT SELECT, INSERT ON TABLE audit_events TO anvil_app;

-- RLS FORCE with the same tenant isolation policy as all other tenant tables.
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON audit_events;
CREATE POLICY tenant_isolation ON audit_events FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

-- Append-only guard: any UPDATE or DELETE fails at the database, for every role
-- (including the migrator/superuser; cleanup uses TRUNCATE which does not fire
-- row triggers). The runtime grant layer above is defense-in-depth.
CREATE OR REPLACE FUNCTION reject_audit_events_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % not allowed', TG_OP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS audit_events_immutable ON audit_events;
CREATE TRIGGER audit_events_immutable
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_events_mutation();

ALTER FUNCTION reject_audit_events_mutation() OWNER TO anvil_migrator;

COMMENT ON FUNCTION reject_audit_events_mutation() IS
    'SECURITY INVOKER only; no DEFINER path for the app role (issue #28).';
