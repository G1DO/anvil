-- V6 confirmed database timeline for the fail-closed restore gate (issue #34, record 0016).
--
-- Table db_timeline holds one row: the (system_identifier, timeline_id) pair the
-- service last confirmed.
--   * A point-in-time restore rewinds this row together with jobs and cap_grants,
--     while Postgres starts a new timeline when recovery promotes. After a restore
--     the stored pair no longer matches the server's, so claim, heartbeat and
--     commit refuse (TimelineGuard) until JobService.confirmTimeline has seen
--     every lease expire, raised fencing tokens past any the lost timeline could
--     have issued, deleted every capability grant, and stored the new pair.
--   * A restore into a fresh cluster (pg_dump, initdb) changes system_identifier.
--   * timeline_id is the first 8 hex digits of the current WAL file name: the
--     insert timeline, which changes at promotion, before any checkpoint.
--   * Not tenant-scoped: no tenant_id and no RLS, like tenants.
--   * Not seeded. A missing row refuses like a mismatch, so a database migrated
--     to V6 from a backup taken before V6 (restored onto a new timeline) stays
--     closed until confirmTimeline runs its fix-ups. On a fresh database the
--     first claim confirms it, with nothing to fix.
CREATE TABLE db_timeline (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    system_identifier BIGINT NOT NULL,
    timeline_id BIGINT NOT NULL,
    confirmed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

-- Ownership mirrors V2-V5; the runtime role reads it on every claim, heartbeat
-- and commit and upserts it on confirm.
ALTER TABLE db_timeline OWNER TO anvil_migrator;
GRANT SELECT, INSERT, UPDATE ON TABLE db_timeline TO anvil_app;
