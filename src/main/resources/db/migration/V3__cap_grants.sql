-- V3 capability grants: revocable, tenant-bound authority for job commits (issue #21).
--
-- Table cap_grants is the durable grant lifecycle:
--   * one current grant per (tenant_id, job_id): UNIQUE(tenant_id, job_id) keeps
--     exactly one authoritative row per job; re-issue bumps epoch (supersedes),
--     revoke sets revoked=TRUE, expiry is expires_at (DB clock only).
--   * tenant binding is composite FOREIGN KEY(tenant_id, job_id) REFERENCES
--     jobs(tenant_id, id) ON DELETE CASCADE backed by UNIQUE(tenant_id, id), so
--     tenant A cannot reference tenant B's job; per-tenant PK shape prevents
--     cross-tenant dedup (no shared grant across tenants).
--   * commit path (see JobService): same transaction as the effect, after
--     TenantGuard.setTenant and before any jobs UPDATE, SELECT ... FOR UPDATE
--     on the grant row (tenant-scoped WHERE tenant_id = ? in addition to RLS),
--     evaluated with DB clock only (clock_timestamp() vs expires_at). Revoked,
--     expired, superseded (epoch mismatch) or cross-tenant (no row for
--     id+tenant+job) denies atomically: 0 jobs rows, 403 NEEDS_APPROVAL,
--     cap_rejected++.
--   * default TTL on issue is clock_timestamp() + 1 hour (DB clock); tests expire
--     via UPDATE ... clock_timestamp() - interval (DB clock, never worker clock).
-- Residual TOCTOU: revoke committed after the commit txn's FOR UPDATE lock is
-- acquired cannot interleave between the grant check and the jobs UPDATE (same
-- txn holds the row lock); revoke committed after the commit txn commits is too
-- late by definition. The window between worker-side grant caching and commit's
-- re-check is inherent and documented as residual: workers must present the
-- epoch they hold and the commit re-checks it under lock.
-- No cross-tenant dedup: grants are per (tenant, job); CAS/content-store
-- per-tenant PK is unchanged.

CREATE TABLE cap_grants (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    job_id UUID NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 1 CHECK (epoch >= 1),
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, job_id),
    FOREIGN KEY (tenant_id, job_id) REFERENCES jobs (tenant_id, id) ON DELETE CASCADE
);

-- Ownership + least privilege mirror V2: migrator owns, app gets DML, no CREATE.
ALTER TABLE cap_grants OWNER TO anvil_migrator;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE cap_grants TO anvil_app;

-- RLS FORCE with the same tenant isolation policy as all other tenant tables.
ALTER TABLE cap_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE cap_grants FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS tenant_isolation ON cap_grants;
CREATE POLICY tenant_isolation ON cap_grants FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));
