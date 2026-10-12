-- V2 tenant RLS enforcement: DB-level isolation so pooled-connection reuse
-- cannot read or effect another tenant's data (issue #20).
--
-- V1 carried tenant_id on every row with composite FK edges but explicitly no
-- policies. This migration adds defense-in-depth at the database:
--   * separate roles: anvil_migrator owns tables/views/function, anvil_app is
--     the runtime role (NOSUPERUSER NOBYPASSRLS, LOGIN for pooled Hikari use).
--   * RLS FORCE on all tenant-scoped tables so even the table owner is subject
--     to policies; superuser/BYPASSRLS still bypass (used by pre-O2 test
--     wiring which connects as the bootstrap superuser and stays green).
--   * one policy per table: tenant_id = current_setting('app.tenant', true).
--     missing_ok=true means missing context yields NULL = NULL-is-not-true,
--     i.e. SELECT sees zero rows (fail closed); INSERT violating WITH CHECK
--     raises new-row-violates-policy (fail closed, never leaks).
--   * views set security_invoker=true so RLS is evaluated as the invoking app
--     role, not the migrator owner.
--   * no SECURITY DEFINER objects: check_job_state_transition stays INVOKER.
-- Runtime sets tenant per transaction with SET LOCAL app.tenant
-- (SELECT set_config('app.tenant', ?, true)) plus a read-back probe of
-- current_setting('app.tenant', true); SET LOCAL resets on COMMIT/ROLLBACK so
-- a pooled checkout cannot retain the previous tenant. All hot-path SQL keeps
-- an explicit WHERE tenant_id = ? in addition to RLS.
-- Tenants table has no RLS (lookup target for X-Tenant-Id auth).

-- 1. Roles (idempotent so re-run/dev-services stay safe).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anvil_migrator') THEN
        CREATE ROLE anvil_migrator WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'anvil_migrator';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'anvil_app') THEN
        CREATE ROLE anvil_app WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD 'anvil_app';
    END IF;
END
$$;

-- Enforce least privilege even if roles pre-existed.
ALTER ROLE anvil_migrator WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS LOGIN;
ALTER ROLE anvil_app WITH NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS LOGIN;

-- 2. Ownership to the migrator role (indexes follow their table).
ALTER TABLE tenants OWNER TO anvil_migrator;
ALTER TABLE documents OWNER TO anvil_migrator;
ALTER TABLE document_versions OWNER TO anvil_migrator;
ALTER TABLE jobs OWNER TO anvil_migrator;
ALTER TABLE attempts OWNER TO anvil_migrator;
ALTER TABLE artifacts OWNER TO anvil_migrator;
ALTER TABLE outbox OWNER TO anvil_migrator;
ALTER TABLE processed_events OWNER TO anvil_migrator;
ALTER VIEW active_jobs OWNER TO anvil_migrator;
ALTER VIEW claimable_jobs OWNER TO anvil_migrator;
ALTER VIEW unclaimed_outbox OWNER TO anvil_migrator;
ALTER FUNCTION check_job_state_transition() OWNER TO anvil_migrator;

-- Views evaluate RLS as the invoker (app role), not the migrator owner.
ALTER VIEW active_jobs SET (security_invoker = true);
ALTER VIEW claimable_jobs SET (security_invoker = true);
ALTER VIEW unclaimed_outbox SET (security_invoker = true);

-- 3. Least-privilege grants for the runtime role.
GRANT USAGE ON SCHEMA public TO anvil_app;
GRANT SELECT ON TABLE tenants TO anvil_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    documents, document_versions, jobs, attempts, artifacts, outbox, processed_events
    TO anvil_app;
GRANT SELECT ON TABLE active_jobs, claimable_jobs, unclaimed_outbox TO anvil_app;
REVOKE CREATE ON SCHEMA public FROM anvil_app;
ALTER DEFAULT PRIVILEGES FOR ROLE anvil_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO anvil_app;

-- 4. RLS FORCE on every tenant-scoped table (tenants intentionally excluded).
ALTER TABLE documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents FORCE ROW LEVEL SECURITY;
ALTER TABLE document_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE artifacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE artifacts FORCE ROW LEVEL SECURITY;
ALTER TABLE outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox FORCE ROW LEVEL SECURITY;
ALTER TABLE processed_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE processed_events FORCE ROW LEVEL SECURITY;

-- One isolation policy per table. TO PUBLIC covers the app role and any future
-- restricted role; superuser/BYPASSRLS still bypass by design (bootstrap test
-- wiring). Missing context -> current_setting(..., true) IS NULL ->
-- tenant_id = NULL is not true -> zero rows (fail closed).
DROP POLICY IF EXISTS tenant_isolation ON documents;
CREATE POLICY tenant_isolation ON documents FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

DROP POLICY IF EXISTS tenant_isolation ON document_versions;
CREATE POLICY tenant_isolation ON document_versions FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

DROP POLICY IF EXISTS tenant_isolation ON jobs;
CREATE POLICY tenant_isolation ON jobs FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

DROP POLICY IF EXISTS tenant_isolation ON attempts;
CREATE POLICY tenant_isolation ON attempts FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

DROP POLICY IF EXISTS tenant_isolation ON artifacts;
CREATE POLICY tenant_isolation ON artifacts FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

DROP POLICY IF EXISTS tenant_isolation ON outbox;
CREATE POLICY tenant_isolation ON outbox FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

DROP POLICY IF EXISTS tenant_isolation ON processed_events;
CREATE POLICY tenant_isolation ON processed_events FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));

COMMENT ON FUNCTION check_job_state_transition() IS
    'SECURITY INVOKER only; no DEFINER path for the app role (issue #20).';
