-- V9 extraction cache, budget, kill-switch and rules fallback (issue #38, record 0023).
-- Additive only: V8's tables, keys and grants are unchanged.
--
--   * extractions gains where a result came from (source) and the SHA-256 of
--     the bounded text it was made from (content_sha256).
--       MODEL  the extractor decided it: a model call, or a quarantine made
--              before any call.
--       CACHE  copied, with no model call, from an earlier MODEL result of the
--              same tenant with the same content, prompt and model. cache_of
--              names that result. It is provenance only, not a foreign key: a
--              copy keeps its facts when the original's document is deleted.
--       RULES  the fallback, stored under the rules' own identifier and hash in
--              place of model and prompt_sha256, so it never takes the model's
--              key and a later model call can still replace it.
--              fallback_reason says why: KILL_SWITCH, BUDGET_EXHAUSTED, or
--              REVOKED (the version's model result was revoked).
--     The cache is these rows: a lookup reads only ACCEPTED MODEL rows of the
--     caller's tenant (the index below), so there is no cross-tenant hit.
--   * extraction_revocations: an operator revokes a result by inserting a row
--     here; a revoked result is never served or reused. The runtime role can
--     only read it. A copy counts as revoked when it or its original is; the
--     operator's statement in record 0023 also inserts a row for each copy, so
--     a copy stays revoked after its original's document is deleted.
--   * extraction_budgets: model calls a tenant may still make. Each call takes
--     one, in its own committed statement, before the call. A tenant with no row
--     has no budget. The runtime role may only spend: it can't raise
--     calls_remaining or lower calls_used (trigger below).
--   * extraction_switch: one row, read before every model call. With
--     model_calls_enabled false no call is made and the rules answer. No row
--     reads as off. It holds no tenant data, so it has no RLS, like tenants.

ALTER TABLE extractions ADD COLUMN content_sha256 TEXT CHECK (content_sha256 ~ '^[0-9a-f]{64}$');
ALTER TABLE extractions ADD COLUMN source TEXT NOT NULL DEFAULT 'MODEL'
    CHECK (source IN ('MODEL', 'CACHE', 'RULES'));
ALTER TABLE extractions ADD COLUMN cache_of UUID;
ALTER TABLE extractions ADD COLUMN fallback_reason TEXT
    CHECK (fallback_reason IN ('KILL_SWITCH', 'BUDGET_EXHAUSTED', 'REVOKED'));
ALTER TABLE extractions ADD CONSTRAINT extractions_fallback_marked
    CHECK ((source = 'RULES') = (fallback_reason IS NOT NULL));
ALTER TABLE extractions ADD CONSTRAINT extractions_cache_of_marked
    CHECK ((source = 'CACHE') = (cache_of IS NOT NULL));
CREATE INDEX extractions_cache_lookup_idx ON extractions (tenant_id, content_sha256, prompt_sha256, model)
    WHERE source = 'MODEL' AND status = 'ACCEPTED';

CREATE TABLE extraction_revocations (
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    extraction_id UUID NOT NULL,
    reason TEXT NOT NULL CHECK (reason <> ''),
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, extraction_id),
    FOREIGN KEY (tenant_id, extraction_id) REFERENCES extractions (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE extraction_budgets (
    tenant_id TEXT PRIMARY KEY REFERENCES tenants (id),
    calls_remaining BIGINT NOT NULL CHECK (calls_remaining >= 0),
    calls_used BIGINT NOT NULL DEFAULT 0 CHECK (calls_used >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE extraction_switch (
    id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
    model_calls_enabled BOOLEAN NOT NULL,
    reason TEXT NOT NULL CHECK (reason <> ''),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO extraction_switch (model_calls_enabled, reason)
    VALUES (TRUE, 'V9 default: calls allowed, but no tenant has a budget until an operator grants one');

CREATE OR REPLACE FUNCTION extraction_budget_spend_only() RETURNS trigger AS $$
BEGIN
    IF current_user = 'anvil_app'
            AND (NEW.calls_remaining > OLD.calls_remaining OR NEW.calls_used < OLD.calls_used
                 OR NEW.tenant_id <> OLD.tenant_id) THEN
        RAISE EXCEPTION 'extraction_budgets: the runtime role may only spend';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER extraction_budgets_spend_only
    BEFORE UPDATE ON extraction_budgets
    FOR EACH ROW EXECUTE FUNCTION extraction_budget_spend_only();
ALTER FUNCTION extraction_budget_spend_only() OWNER TO anvil_migrator;

ALTER TABLE extraction_revocations OWNER TO anvil_migrator;
ALTER TABLE extraction_budgets OWNER TO anvil_migrator;
ALTER TABLE extraction_switch OWNER TO anvil_migrator;
REVOKE ALL ON TABLE extraction_revocations, extraction_budgets, extraction_switch FROM anvil_app;
GRANT SELECT ON TABLE extraction_revocations, extraction_budgets, extraction_switch TO anvil_app;
GRANT UPDATE (calls_remaining, calls_used, updated_at) ON TABLE extraction_budgets TO anvil_app;

ALTER TABLE extraction_revocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_revocations FORCE ROW LEVEL SECURITY;
ALTER TABLE extraction_budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_budgets FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON extraction_revocations FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));
CREATE POLICY tenant_isolation ON extraction_budgets FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));
