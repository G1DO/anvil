package com.g1do.anvil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;

/**
 * Real worker process for the fault matrix (issue #78).
 *
 * <p>Runs the production {@link JobService} (claim, heartbeat, commit) in its own JVM
 * and takes one command per stdin line, answering on stdout:
 * <ul>
 *   <li>{@code claim} -&gt; {@code CLAIMED <jobId> <token>} or {@code NO_WORK};</li>
 *   <li>{@code heartbeat} -&gt; {@code HEARTBEAT true|false} for the held job;</li>
 *   <li>{@code commit} -&gt; {@code COMMIT true|false} for the held job.</li>
 * </ul>
 * The parent {@code kill -9}s it before or after a commit, or {@code kill -STOP}s it
 * past its lease and {@code kill -CONT}s it, so a crash or suspend is a real one,
 * not a thrown exception.
 */
public class WorkerFaultChild {

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            System.err.println("usage: WorkerFaultChild <jdbcUrl> <username> <password> <tenantId> <owner>");
            System.exit(2);
            return;
        }
        String password = "__NULL__".equals(args[2]) ? null : args[2];
        DriverManagerDataSource dataSource = new DriverManagerDataSource(args[0], args[1], password);
        JobService jobs = new JobService(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
        String tenant = args[3];
        String owner = args[4];

        ClaimedJob held = null;
        System.out.println("READY");
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String command;
        while ((command = in.readLine()) != null) {
            switch (command.trim()) {
                case "claim" -> {
                    held = jobs.claim(tenant, owner).orElse(null);
                    System.out.println(held == null ? "NO_WORK" : "CLAIMED " + held.jobId() + " " + held.fencingToken());
                }
                case "heartbeat" -> System.out.println("HEARTBEAT " + jobs.heartbeat(tenant, jobId(held), owner, held.fencingToken()));
                case "commit" -> System.out.println("COMMIT " + jobs.commitSucceeded(tenant, jobId(held), owner, held.fencingToken()));
                default -> System.out.println("UNKNOWN " + command);
            }
            System.out.flush();
        }
    }

    private static UUID jobId(ClaimedJob held) {
        if (held == null) {
            throw new IllegalStateException("no job held; send claim first");
        }
        return held.jobId();
    }
}
