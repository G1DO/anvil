package com.g1do.anvil;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.TimelineStatus;

/**
 * Backup, clean and point-in-time restore on one containerized Postgres
 * (issue #34).
 *
 * <p>The container runs {@code sleep infinity} instead of the server, so the
 * drill can stop, wipe and restore the data directory without losing the
 * container. Everything runs as {@code postgres} via {@code gosu}:
 * {@code initdb} with WAL archiving to {@code /drill/archive},
 * {@code pg_basebackup} to {@code /drill/base}, a named restore point as the
 * declared target, {@code pg_ctl stop -m immediate} as the failure,
 * {@code rm -rf} as the clean, then the base backup plus
 * {@code recovery.signal}, {@code restore_command} and
 * {@code recovery_target_name} as the restore. Recovery stops at the target
 * and promotes onto a new timeline.
 *
 * <p>{@code initdb} and {@code pg_basebackup} run with {@code -N} (no fsync):
 * both finish before the failure, in a disposable container, so syncing them
 * only slows the suite. The restored server keeps the default start-up sync
 * of its data directory, so the measured recovery time includes it.
 */
final class PitrDrill implements AutoCloseable {

    static final String USER = "test";
    static final String PASSWORD = "test";
    static final String DATABASE = "test";

    /** Declared recovery target: a named restore point. */
    static final String TARGET = "drill-target";

    private static final Duration SERVER_TIMEOUT = Duration.ofSeconds(60);

    private final GenericContainer<?> pg = new GenericContainer<>(DockerImageName.parse("postgres:18.6"))
            .withCommand("sleep", "infinity")
            .withExposedPorts(5432)
            .waitingFor(Wait.forSuccessfulCommand("true"));

    private DriverManagerDataSource dataSource;

    /**
     * The database every Spring context in the suite uses: migrated, written
     * to, restored to {@link #TARGET}, and confirmed. Started once per JVM.
     */
    static PitrDrill restoredForSuite() {
        PitrDrill drill = new PitrDrill();
        drill.start();
        drill.migrate();
        drill.baseBackup();
        JdbcTemplate jdbc = drill.jdbc();
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_drill_kept')");
        drill.createRestorePoint();
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_drill_lost')");
        drill.archiveWal();
        drill.crash();
        drill.clean();
        drill.restoreToTarget();
        String drillRows = jdbc.queryForObject(
                "SELECT string_agg(id, ',') FROM tenants WHERE id LIKE 't_drill_%'", String.class);
        if (!"t_drill_kept".equals(drillRows)) {
            throw new IllegalStateException("restore did not stop at " + TARGET + ": " + drillRows);
        }
        jdbc.update("DELETE FROM tenants WHERE id = 't_drill_kept'");
        TimelineStatus status = drill.jobs().confirmTimeline();
        if (status != TimelineStatus.CONFIRMED) {
            throw new IllegalStateException("restored suite database not confirmed: " + status);
        }
        return drill;
    }

    void start() {
        pg.start();
        sh("mkdir -p /drill/archive && chown -R postgres:postgres /drill"
                + " && echo '" + PASSWORD + "' > /drill/pw && chown postgres /drill/pw");
        sh("gosu postgres initdb -N -D /drill/data -U " + USER
                + " --pwfile=/drill/pw --auth-local=trust --auth-host=scram-sha-256 >/dev/null");
        sh("""
                cat >> /drill/data/postgresql.conf <<'EOF'
                listen_addresses = '*'
                archive_mode = on
                archive_command = 'test ! -f /drill/archive/%f && cp %p /drill/archive/%f'
                EOF
                echo 'host all all all scram-sha-256' >> /drill/data/pg_hba.conf
                """);
        startServer();
        sh("gosu postgres createdb -U " + USER + " " + DATABASE);
        dataSource = new DriverManagerDataSource(
                "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/" + DATABASE,
                USER, PASSWORD);
    }

    String jdbcUrl() {
        return dataSource.getUrl();
    }

    DataSource dataSource() {
        return dataSource;
    }

    JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    JobService jobs() {
        return new JobService(jdbc(), new DataSourceTransactionManager(dataSource));
    }

    void migrate() {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    /** Migrates up to and including {@code version}. */
    void migrate(String version) {
        Flyway.configure().dataSource(dataSource).target(version).load().migrate();
    }

    void baseBackup() {
        sh("gosu postgres pg_basebackup -N -D /drill/base -U " + USER + " -c fast");
    }

    /** Creates the declared target and returns its time on the database clock. */
    OffsetDateTime createRestorePoint() {
        return jdbc().queryForObject(
                "SELECT clock_timestamp() FROM pg_create_restore_point(?)", OffsetDateTime.class, TARGET);
    }

    /** Switches WAL and waits until the archive holds everything written so far. */
    void archiveWal() {
        JdbcTemplate jdbc = jdbc();
        String segment = jdbc.queryForObject("SELECT pg_walfile_name(pg_switch_wal())", String.class);
        long deadline = System.nanoTime() + SERVER_TIMEOUT.toNanos();
        while (true) {
            String archived = jdbc.queryForObject("SELECT last_archived_wal FROM pg_stat_archiver", String.class);
            if (archived != null && archived.compareTo(segment) >= 0) {
                return;
            }
            pause(deadline, "WAL segment " + segment + " not archived");
        }
    }

    /** The failure: stop without a shutdown checkpoint. */
    void crash() {
        sh("gosu postgres pg_ctl -D /drill/data -m immediate stop >/dev/null");
    }

    void clean() {
        sh("rm -rf /drill/data");
    }

    /** Restores the base backup and replays archived WAL up to {@link #TARGET}. */
    void restoreToTarget() {
        sh("cp -a /drill/base /drill/data && chmod 700 /drill/data");
        sh("printf '%s\\n' \"restore_command = 'cp /drill/archive/%f %p'\""
                + " \"recovery_target_name = '" + TARGET + "'\""
                + " \"recovery_target_action = 'promote'\" >> /drill/data/postgresql.conf"
                + " && gosu postgres touch /drill/data/recovery.signal");
        startServer();
        JdbcTemplate jdbc = jdbc();
        long deadline = System.nanoTime() + SERVER_TIMEOUT.toNanos();
        while (Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_is_in_recovery()", Boolean.class))) {
            pause(deadline, "restore did not promote");
        }
    }

    @Override
    public void close() {
        pg.stop();
    }

    private void startServer() {
        sh("gosu postgres pg_ctl -D /drill/data -l /drill/server.log -w -t "
                + SERVER_TIMEOUT.toSeconds() + " start >/dev/null");
    }

    private String sh(String script) {
        try {
            ExecResult result = pg.execInContainer("bash", "-c", script);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("drill step failed (" + result.getExitCode() + "): "
                        + script + "\n" + result.getStdout() + result.getStderr());
            }
            return result.getStdout().trim();
        } catch (IOException e) {
            throw new IllegalStateException("drill step failed: " + script, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("drill step interrupted: " + script, e);
        }
    }

    private static void pause(long deadline, String timeoutMessage) {
        if (System.nanoTime() > deadline) {
            throw new IllegalStateException(timeoutMessage + " within " + SERVER_TIMEOUT);
        }
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
