-- V1 durable execution slice: source of truth for single-tenant submit/claim/relay.
--
-- Tables (tenant_id carried on every row for later isolation work; no RLS proof here):
--   tenants, documents, document_versions, jobs (= "active jobs" store, all states),
--   attempts, artifacts, outbox, processed_events.
--
-- Assumptions (narrowest reasonable; see issue #2):
--   * "active jobs" maps to table jobs; non-terminal rows are exposed via view active_jobs.
--   * Runnable/claimable means state='QUEUED', attempt < max_attempts, and lease unheld/expired.
--     Terminal states are SUCCEEDED, FAILED, DEAD and are fully immutable once entered:
--     ANY UPDATE on a terminal row fails at the database (zombies cannot mutate terminals).
--   * Legal transitions (enforced by trigger job_state_guard, database clock only for leases):
--       QUEUED  -> RUNNING   (claim)
--       RUNNING -> QUEUED    (release / lease-expiry reclaim via reconciler)
--       RUNNING -> SUCCEEDED (commit success, terminal)
--       RUNNING -> FAILED    (commit failure, terminal)
--       RUNNING -> DEAD      (exhausted, terminal)
--       QUEUED  -> FAILED    (fail without running, terminal)
--       QUEUED  -> DEAD      (reconciler exhausts queued, terminal)
--     Same-state updates on non-terminal rows (e.g. heartbeat extending lease_expires_at)
--     are allowed. INSERTs accept any valid state so tests can seed terminal rows;
--     only UPDATEs are guarded.
--   * Idempotency scope is UNIQUE(tenant_id, idempotency_key) on jobs.
--   * Content addressing is per-tenant (tenant_id, sha256); no cross-tenant dedup.
--     Filesystem path convention tenants/{tenant}/{sha} is enforced by the PK shape.
--   * Tenant-scoped references are enforced by composite FOREIGN KEY(tenant_id, xxx_id)
--     edges backed by UNIQUE(tenant_id, id) targets, so tenant A cannot reference
--     tenant B's document/version/job. No RLS/policies here by design.
--
-- Canonical claim query (must use jobs_claim_idx, never returns terminals):
--   SELECT id FROM jobs
--    WHERE tenant_id = ? AND state = 'QUEUED'
--      AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp())
--      AND attempt < max_attempts
--    ORDER BY created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED;
-- Canonical relay poll (must use outbox_poll_idx):
--   SELECT id FROM outbox
--    WHERE sent_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED;
-- Tenant-scoped relay variant filters tenant_id and still uses outbox_poll_idx for sent_at/id.
-- gen_random_uuid() is core since PG13; no extension required.

-- Tenants: single-tenant slice uses id 't_single' (X-Tenant-Id lookup target).
CREATE TABLE tenants (
    id TEXT PRIMARY KEY CHECK (id <> ''),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Logical documents (submit validation lives in the app; columns nullable for flexible seeding).
CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    title TEXT,
    mime_type TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
CREATE INDEX documents_tenant_idx ON documents (tenant_id, created_at);

-- Immutable versions of a document; sha256 is the canonical-CBOR hash (lowercase hex).
CREATE TABLE document_versions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    document_id UUID NOT NULL,
    version_number INT NOT NULL DEFAULT 1 CHECK (version_number >= 1),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    canonical_cbor BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (document_id, version_number),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX document_versions_lookup_idx ON document_versions (tenant_id, document_id);

-- Jobs: the single "active jobs" store (all states). Terminals stay in-row but are
-- excluded from every claim path (see claimable_jobs / active_jobs views + trigger).
CREATE TABLE jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    document_id UUID NOT NULL,
    version_id UUID NOT NULL,
    idempotency_key TEXT NOT NULL CHECK (idempotency_key <> ''),
    state TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD')),
    attempt INT NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts INT NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    owner TEXT,
    fencing_token BIGINT NOT NULL DEFAULT 0 CHECK (fencing_token >= 0),
    lease_expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, idempotency_key),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES document_versions (tenant_id, id)
);

-- Every claim/heartbeat/commit outcome is recorded; (tenant, job, attempt_no) is unique
-- so zombie-resume cannot double-record the same attempt.
CREATE TABLE attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    job_id UUID NOT NULL,
    attempt_number INT NOT NULL CHECK (attempt_number >= 0),
    owner TEXT,
    fencing_token BIGINT CHECK (fencing_token IS NULL OR fencing_token >= 0),
    outcome TEXT NOT NULL DEFAULT 'CLAIMED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, job_id, attempt_number),
    FOREIGN KEY (tenant_id, job_id) REFERENCES jobs (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX attempts_job_lookup_idx ON attempts (tenant_id, job_id, attempt_number);

-- Content-addressed artifact references (bytes live under tenants/{tenant}/{sha} on disk).
-- Per-tenant PK prevents cross-tenant dedup; refcount + created_at drive grace-period GC.
CREATE TABLE artifacts (
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    sha256 TEXT NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    size_bytes BIGINT NOT NULL DEFAULT 0 CHECK (size_bytes >= 0),
    refcount INT NOT NULL DEFAULT 0 CHECK (refcount >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, sha256)
);
CREATE INDEX artifacts_gc_idx ON artifacts (tenant_id, created_at) WHERE refcount = 0;

-- Transactional outbox: written in the same DB transaction as submit; relay publishes
-- then sets sent_at in a separate transaction (at-least-once, never exactly-once).
-- outbox.id IS the stable event identity (event_id in relay/consumer contracts).
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    job_id UUID NOT NULL,
    doc_id UUID,
    version_id UUID,
    type TEXT NOT NULL DEFAULT 'job.committed',
    sha256 TEXT CHECK (sha256 IS NULL OR sha256 ~ '^[0-9a-f]{64}$'),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    FOREIGN KEY (tenant_id, job_id) REFERENCES jobs (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, doc_id) REFERENCES documents (tenant_id, id),
    FOREIGN KEY (tenant_id, version_id) REFERENCES document_versions (tenant_id, id)
);
-- Relay poll: WHERE sent_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED.
-- Single-column partial index keeps ORDER BY id satisfied directly from the index;
-- the predicate is implied so no Filter is needed and the PK is not chosen instead.
CREATE INDEX outbox_poll_idx ON outbox (id) WHERE sent_at IS NULL;

-- Idempotent consumer progress: INSERT ... ON CONFLICT DO NOTHING; apply effect only
-- when the insert succeeds. No FK to outbox so progress survives outbox retention.
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    job_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, job_id) REFERENCES jobs (tenant_id, id)
);
CREATE INDEX processed_events_tenant_idx ON processed_events (tenant_id, created_at);

-- Claim + running-lookup indexes (fairness = ORDER BY created_at, id).
-- Partial predicates keep each index dedicated: claim serves QUEUED fairness polling,
-- running serves live-lease heartbeat/commit lookups, lease-expiry serves the reconciler.
CREATE INDEX jobs_claim_idx ON jobs (tenant_id, created_at, id) WHERE state = 'QUEUED';
CREATE INDEX jobs_running_lookup_idx ON jobs (tenant_id, owner, fencing_token) WHERE state = 'RUNNING';
CREATE INDEX jobs_lease_expiry_idx ON jobs (lease_expires_at) WHERE state IN ('QUEUED', 'RUNNING');

-- Legal-transition guard: illegal transitions fail at the database.
-- Terminal rows are fully immutable: ANY UPDATE on SUCCEEDED/FAILED/DEAD fails,
-- so zombies cannot mutate owner/lease/attempt on completed work.
CREATE OR REPLACE FUNCTION check_job_state_transition() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        RETURN NEW;
    END IF;
    IF OLD.state IN ('SUCCEEDED', 'FAILED', 'DEAD') THEN
        RAISE EXCEPTION 'illegal job transition % -> %: terminal states are immutable', OLD.state, NEW.state;
    END IF;
    IF NEW.state = OLD.state THEN
        RETURN NEW;
    END IF;
    IF NOT (
        (OLD.state = 'QUEUED' AND NEW.state = 'RUNNING')
        OR (OLD.state = 'RUNNING' AND NEW.state IN ('QUEUED', 'SUCCEEDED', 'FAILED', 'DEAD'))
        OR (OLD.state = 'QUEUED' AND NEW.state IN ('FAILED', 'DEAD'))
    ) THEN
        RAISE EXCEPTION 'illegal job transition % -> %', OLD.state, NEW.state;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER job_state_guard
    BEFORE INSERT OR UPDATE ON jobs
    FOR EACH ROW EXECUTE FUNCTION check_job_state_transition();

-- No terminal row is ever runnable: claim paths must read through these views
-- (or an equivalent WHERE state='QUEUED' predicate, which uses jobs_claim_idx).
CREATE VIEW active_jobs AS
    SELECT * FROM jobs WHERE state IN ('QUEUED', 'RUNNING');

CREATE VIEW claimable_jobs AS
    SELECT * FROM jobs
    WHERE state = 'QUEUED'
      AND attempt < max_attempts
      AND (lease_expires_at IS NULL OR lease_expires_at <= clock_timestamp());

CREATE VIEW unclaimed_outbox AS
    SELECT * FROM outbox WHERE sent_at IS NULL;

-- Seed single-tenant slice value used by X-Tenant-Id: t_single lookups.
INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING;
