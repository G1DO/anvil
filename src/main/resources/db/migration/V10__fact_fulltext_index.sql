-- V10 full-text index on fact values (#79, record 0025). Additive only.
--
-- Measured before it was added: over one soak day (10,000 extractions) on
-- containerized Postgres 18.6 against the frozen full-v1 run's facts, it met
-- record 0025's budget with fastupdate on (load/runs/gin-budget/). With
-- fastupdate off it wrote 2.5 times the WAL of no index, over the budget's 2.
-- With it on, inserts add to a pending list that autovacuum, or an insert
-- that fills it, merges into the index.
--
-- No application code reads it yet. The kill-switch is for an operator, as
-- anvil_migrator, to drop it:
--   DROP INDEX CONCURRENTLY extracted_facts_value_fts_idx;
-- which nothing depends on (GinIndexTest). Lookups through it still run under
-- RLS as every read does.

CREATE INDEX extracted_facts_value_fts_idx ON extracted_facts USING gin (to_tsvector('simple', value))
    WITH (fastupdate = on);
