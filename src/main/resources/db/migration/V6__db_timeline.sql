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
--   * Seeded with the server's current pair, so a fresh database is confirmed.
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

INSERT INTO db_timeline (id, system_identifier, timeline_id)
SELECT 1, s.system_identifier,
       ('x' || substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8))::bit(32)::bigint
FROM pg_control_system() s;
