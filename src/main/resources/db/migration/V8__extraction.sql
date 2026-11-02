-- V8 single bounded extraction (issue #37, record 0022).
--
--   * extractions: one per (tenant, document version). UNIQUE(tenant_id,
--     version_id) means a version is extracted once per tenant; a repeat
--     request returns the stored result without a model call. The key holds the
--     tenant, so two tenants with identical content never share a result: there
--     is no cross-tenant dedup or cache hit. status is ACCEPTED (facts may be
--     indexed) or QUARANTINED (the whole document was poison; nothing indexed).
--   * extracted_facts: the index. Only facts whose citation passed the exact
--     substring check against the text the model saw are inserted. This is the
--     only table GET /api/documents/{id}/facts reads.
--   * extraction_dead_letters: what was quarantined, with a reason. A whole-
--     document reason has no fact; a per-fact reason keeps the rejected fact.
--     No route reads this table: dead letters are kept and never served.
--   * Every table carries tenant_id, references its parent through a composite
--     (tenant_id, id) foreign key, and has RLS FORCE with the same
--     tenant_isolation policy as the other tenant tables (V2). Deleting a
--     document cascades to its extractions, facts and dead letters.

CREATE TABLE extractions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    document_id UUID NOT NULL,
    version_id UUID NOT NULL,
    model TEXT NOT NULL CHECK (model <> ''),
    prompt_sha256 TEXT NOT NULL CHECK (prompt_sha256 ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL CHECK (status IN ('ACCEPTED', 'QUARANTINED')),
    truncated BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, version_id),
    FOREIGN KEY (tenant_id, document_id) REFERENCES documents (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, version_id) REFERENCES document_versions (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE extracted_facts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    extraction_id UUID NOT NULL,
    ordinal INT NOT NULL CHECK (ordinal >= 0),
    field TEXT NOT NULL CHECK (field IN ('party', 'person', 'date', 'amount')),
    value TEXT NOT NULL CHECK (value <> ''),
    citation TEXT NOT NULL CHECK (citation <> ''),
    UNIQUE (tenant_id, extraction_id, ordinal),
    FOREIGN KEY (tenant_id, extraction_id) REFERENCES extractions (tenant_id, id) ON DELETE CASCADE
);

CREATE TABLE extraction_dead_letters (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id TEXT NOT NULL REFERENCES tenants (id),
    extraction_id UUID NOT NULL,
    reason TEXT NOT NULL CHECK (reason IN (
        'UNSUPPORTED_MEDIA_TYPE', 'NOT_TEXT', 'EMPTY_TEXT', 'MODEL_REFUSED', 'MALFORMED_OUTPUT',
        'UNKNOWN_FIELD', 'UNCITED', 'CITATION_NOT_IN_SOURCE', 'VALUE_NOT_IN_CITATION')),
    field TEXT,
    value TEXT,
    citation TEXT,
    detail TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, extraction_id) REFERENCES extractions (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX extraction_dead_letters_lookup_idx ON extraction_dead_letters (tenant_id, extraction_id);

ALTER TABLE extractions OWNER TO anvil_migrator;
ALTER TABLE extracted_facts OWNER TO anvil_migrator;
ALTER TABLE extraction_dead_letters OWNER TO anvil_migrator;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE extractions, extracted_facts, extraction_dead_letters TO anvil_app;

ALTER TABLE extractions ENABLE ROW LEVEL SECURITY;
ALTER TABLE extractions FORCE ROW LEVEL SECURITY;
ALTER TABLE extracted_facts ENABLE ROW LEVEL SECURITY;
ALTER TABLE extracted_facts FORCE ROW LEVEL SECURITY;
ALTER TABLE extraction_dead_letters ENABLE ROW LEVEL SECURITY;
ALTER TABLE extraction_dead_letters FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON extractions FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));
CREATE POLICY tenant_isolation ON extracted_facts FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));
CREATE POLICY tenant_isolation ON extraction_dead_letters FOR ALL TO PUBLIC
    USING (tenant_id = current_setting('app.tenant', true))
    WITH CHECK (tenant_id = current_setting('app.tenant', true));
