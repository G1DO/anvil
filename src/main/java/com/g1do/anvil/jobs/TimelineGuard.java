package com.g1do.anvil.jobs;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fail-closed database timeline gate (issue #34, record 0016).
 *
 * <p>A point-in-time restore rewinds fencing tokens and capability grants
 * together with the {@code db_timeline} row, while Postgres moves to a new
 * timeline on promotion. Comparing the stored {@code (system_identifier,
 * timeline_id)} with the server's own pair therefore detects the rewind from
 * inside the restored database, across service restarts. Claim, heartbeat and
 * commit call {@link #requireConfirmed} in their transaction: a mismatch, a
 * missing row, or a timeline that can't be read (privileges revoked, server in
 * recovery) refuses with {@link TimelineUnconfirmedException} before any job
 * row is touched.
 *
 * <p>No lock is taken: the only transition is mismatch to match, made by
 * {@link JobService#confirmTimeline()} after its fix-ups commit, so a
 * transaction that reads a match can't race a pending confirm.
 */
public final class TimelineGuard {

    /** Server's insert timeline; changes at promotion, before any checkpoint. */
    private static final String CURRENT_TIMELINE_EXPR =
            "('x' || substr(pg_walfile_name(pg_current_wal_lsn()), 1, 8))::bit(32)::bigint";

    private static final String CURRENT_SQL =
            "SELECT s.system_identifier, " + CURRENT_TIMELINE_EXPR + " AS timeline_id "
                    + "FROM pg_control_system() s";

    private static final String CONFIRMED_SQL =
            "SELECT EXISTS (SELECT 1 FROM db_timeline t, pg_control_system() s "
                    + "WHERE t.id = 1 AND t.system_identifier = s.system_identifier "
                    + "AND t.timeline_id = " + CURRENT_TIMELINE_EXPR + ")";

    /** One database history: the cluster and the timeline within it. */
    record Timeline(long systemIdentifier, long timelineId) {
    }

    private TimelineGuard() {
    }

    /**
     * Refuses unless the stored timeline matches the server's.
     *
     * @throws TimelineUnconfirmedException ({@code 503 TIMELINE_UNCONFIRMED})
     *         when the timeline is rewound, unconfirmed, or unknown.
     */
    public static void requireConfirmed(JdbcTemplate jdbc) {
        Boolean confirmed;
        try {
            confirmed = jdbc.queryForObject(CONFIRMED_SQL, Boolean.class);
        } catch (DataAccessException e) {
            throw new TimelineUnconfirmedException("timeline unknown", e);
        }
        if (!Boolean.TRUE.equals(confirmed)) {
            throw new TimelineUnconfirmedException("timeline rewound or unconfirmed");
        }
    }

    /**
     * Reads the server's current timeline. Empty when it can't be read; a
     * failed read aborts the surrounding transaction, so the caller must roll
     * back.
     */
    static Optional<Timeline> current(JdbcTemplate jdbc) {
        try {
            List<Timeline> rows = jdbc.query(CURRENT_SQL,
                    (rs, i) -> new Timeline(rs.getLong("system_identifier"), rs.getLong("timeline_id")));
            return rows.stream().findFirst();
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }
}
