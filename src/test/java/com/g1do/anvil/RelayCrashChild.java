package com.g1do.anvil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Real-crash relay worker for issue #14.
 *
 * <p>Mimics the production relay boundary without ever advancing it:
 * poll ({@code WHERE sent_at IS NULL ORDER BY id LIMIT 1} in its own
 * auto-commit read) -&gt; publish ({@code INSERT INTO processed_events
 * ON CONFLICT DO NOTHING} in a second auto-commit write) -&gt; sleep
 * forever instead of {@code UPDATE outbox SET sent_at}.
 *
 * <p>The parent test SIGKILLs this process between publish and mark
 * ({@code kill -9} when available, else {@code Process.destroyForcibly},
 * which is SIGKILL on Unix and TerminateProcess on Windows). Because the
 * mark never runs, the durable post-crash state ({@code sent_at IS NULL}
 * plus one {@code processed_events} row) is bit-identical to a real relay
 * crash at the same boundary: no in-memory mark ever existed, and both
 * open transactions already committed before the sleep.
 */
public class RelayCrashChild {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RelayCrashChild <jdbcUrl> <username> <password>");
            System.exit(2);
            return;
        }
        String jdbcUrl = args[0];
        String username = args[1];
        String password = args[2];
        if ("__NULL__".equals(password)) {
            password = null;
        }

        try (Connection conn = openConnection(jdbcUrl, username, password)) {
            conn.setAutoCommit(true);

            UUID eventId;
            String tenantId;
            UUID jobId;
            try (PreparedStatement poll = conn.prepareStatement(
                    "SELECT id, tenant_id, job_id FROM outbox "
                            + "WHERE sent_at IS NULL ORDER BY id LIMIT 1");
                    ResultSet rs = poll.executeQuery()) {
                if (!rs.next()) {
                    System.out.println("NO_WORK");
                    System.out.flush();
                    System.exit(3);
                    return;
                }
                eventId = (UUID) rs.getObject("id");
                tenantId = rs.getString("tenant_id");
                jobId = (UUID) rs.getObject("job_id");
            }

            try (PreparedStatement claim = conn.prepareStatement(
                    "INSERT INTO processed_events (event_id, tenant_id, job_id) "
                            + "VALUES (?, ?, ?) ON CONFLICT (event_id) DO NOTHING")) {
                claim.setObject(1, eventId);
                claim.setString(2, tenantId);
                claim.setObject(3, jobId);
                claim.executeUpdate();
            }

            // Publish is durable; the mark below never runs because the parent
            // SIGKILLs this process while it sleeps.
            System.out.println("PUBLISHED " + eventId);
            System.out.flush();

            Thread.sleep(60_000L);
            System.out.println("TIMEOUT_WITHOUT_KILL");
            System.out.flush();
            System.exit(4);
        }
    }

    private static Connection openConnection(String jdbcUrl, String username, String password)
            throws Exception {
        if (password == null) {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("user", username);
            return DriverManager.getConnection(jdbcUrl, props);
        }
        return DriverManager.getConnection(jdbcUrl, username, password);
    }
}
