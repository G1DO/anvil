package com.g1do.anvil;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.g1do.anvil.audit.AuditChain;
import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.cli.AnvilCli;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.submit.CanonicalCbor;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Acceptance checks for issue #75 (record 0026): an operator replays a job
 * from the command line and verifies an exported chain with no database.
 *
 * <ul>
 * <li>{@code anvil replay} prints {@code input_sha}, {@code output_sha} and
 * {@code match} byte for byte as {@code GET /audit/replay} returns them, for an
 * intact, a running, a tampered and a checkpointed job.</li>
 * <li>{@code anvil verify} checks an export with no database connection: an
 * intact export gives the API's result, and a tampered one reports the first
 * bad sequence number.</li>
 * <li>Both scope by tenant as the API does: another tenant's job is "not
 * found" exactly like a missing one, an unknown tenant is refused, and the CLI
 * reads as the runtime role under RLS, read-only.</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ReplayCliTest {

    private static final String A = "t_cli_a";
    private static final String B = "t_cli_b";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmitService submits;

    @Autowired
    JobService jobs;

    @Autowired
    MockMvc mvc;

    @Autowired
    PlatformTransactionManager txManager;

    @Autowired
    JdbcConnectionDetails db;

    @TempDir
    Path tmp;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE job_checkpoints, audit_events");
        for (String t : new String[] { "cap_grants", "processed_events", "outbox", "attempts", "jobs",
                "document_versions", "documents", "artifacts" }) {
            jdbc.execute("DELETE FROM " + t + " WHERE tenant_id IN ('" + A + "', '" + B + "')");
        }
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", A);
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", B);
    }

    // ---- replay: the API's result from the command line ----

    @Test
    void replayPrintsTheApisResultByteForByteForEveryKindOfJob() throws Exception {
        UUID done = succeeded(A, "done");
        UUID running = running(A, "running");
        UUID tampered = tamperedInTheDatabase(A);
        UUID longChain = longChain(A, 250);

        for (UUID job : new UUID[] { done, running, tampered, longChain }) {
            MvcResult api = mvc.perform(get("/audit/replay").header("X-Tenant-Id", A).param("job_id", job.toString()))
                    .andReturn();
            assertThat(api.getResponse().getStatus()).isEqualTo(200);
            String body = api.getResponse().getContentAsString();

            Run cli = cli("replay", "--tenant", A, "--job", job.toString());
            assertThat(cli.out().strip()).as("job %s", job).isEqualTo(body);
            assertThat(json.readTree(cli.out()).fieldNames()).toIterable()
                    .containsExactlyInAnyOrder("input_sha", "output_sha", "match");
            boolean match = json.readTree(body).get("match").asBoolean();
            assertThat(cli.status()).isEqualTo(match ? AnvilCli.MATCH : AnvilCli.NO_MATCH);
        }
        assertThat(json.readTree(cli("replay", "--tenant", A, "--job", done.toString()).out())
                .get("match").asBoolean()).as("the intact, terminal job matches").isTrue();
    }

    @Test
    void replayGivesAnotherTenantsJobTheSameNotFoundAsAMissingOne() throws Exception {
        UUID theirs = succeeded(B, "theirs");
        UUID missing = UUID.randomUUID();

        Run foreign = cli("replay", "--tenant", A, "--job", theirs.toString());
        Run absent = cli("replay", "--tenant", A, "--job", missing.toString());
        assertThat(foreign.status()).isEqualTo(AnvilCli.NOT_FOUND);
        assertThat(foreign).isEqualTo(absent);
        assertThat(foreign.out()).isEmpty();
        assertThat(foreign.err().strip()).isEqualTo("not found");
        // The API says the same for both.
        for (UUID job : new UUID[] { theirs, missing }) {
            assertThat(mvc.perform(get("/audit/replay").header("X-Tenant-Id", A).param("job_id", job.toString()))
                    .andReturn().getResponse().getStatus()).isEqualTo(404);
        }
        // The owner still replays it.
        assertThat(cli("replay", "--tenant", B, "--job", theirs.toString()).status()).isEqualTo(AnvilCli.MATCH);
        // Export follows the same rule.
        assertThat(cli("export", "--tenant", A, "--job", theirs.toString())).isEqualTo(absent);
    }

    @Test
    void anUnknownTenantIsRefusedAsTheApiRefusesIt() throws Exception {
        UUID job = succeeded(A, "unknown");
        Run r = cli("replay", "--tenant", "t_cli_nobody", "--job", job.toString());
        assertThat(r.status()).isEqualTo(AnvilCli.UNKNOWN_TENANT);
        assertThat(r.out()).isEmpty();
        assertThat(mvc.perform(get("/audit/replay").header("X-Tenant-Id", "t_cli_nobody")
                .param("job_id", job.toString())).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(cli("replay", "--job", job.toString()).status()).as("no tenant").isEqualTo(AnvilCli.USAGE);
        assertThat(cli("replay", "--tenant", A, "--job", "not-a-uuid").status()).isEqualTo(AnvilCli.USAGE);
        assertThat(cli("replay", "--tenant", A, "--job", job.toString(), "--out", "f").status())
                .as("replay writes no file").isEqualTo(AnvilCli.USAGE);
        assertThat(cli("unknown", "--tenant", A, "--job", job.toString()).status()).isEqualTo(AnvilCli.USAGE);
    }

    @Test
    void theCliReadsAsTheRuntimeRoleUnderRlsAndWritesNothing() throws Exception {
        UUID job = succeeded(A, "role");
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
                .as("the test login bypasses RLS").isTrue();
        try (Connection c = connect()) {
            AnvilCli.restrict(c);
            try (Statement s = c.createStatement()) {
                assertThat(one(s, "SELECT current_user")).isEqualTo(AnvilCli.RUNTIME_ROLE);
                assertThat(one(s, "SELECT count(*) FROM audit_events"))
                        .as("no tenant context: RLS hides every row").isEqualTo("0");
                assertThat(one(s, "SHOW default_transaction_read_only")).isEqualTo("on");
            }
        }
        int events = jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class);
        int checkpoints = jdbc.queryForObject("SELECT count(*) FROM job_checkpoints", Integer.class);
        assertThat(cli("replay", "--tenant", A, "--job", job.toString()).status()).isEqualTo(AnvilCli.MATCH);
        assertThat(cli("export", "--tenant", A, "--job", job.toString()).status()).isEqualTo(AnvilCli.MATCH);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events", Integer.class)).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_checkpoints", Integer.class)).isEqualTo(checkpoints);
    }

    // ---- verify: an export, with no database ----

    @Test
    void anIntactExportVerifiesOfflineToTheApisResult() throws Exception {
        for (UUID job : new UUID[] { succeeded(A, "offline"), running(A, "offline-run"), longChain(A, 250) }) {
            Path file = export(A, job);
            JsonNode api = apiReplay(A, job);

            Run v = verify(A, job, file);
            JsonNode out = json.readTree(v.out());
            assertThat(out.get("input_sha")).isEqualTo(api.get("input_sha"));
            assertThat(out.get("output_sha")).isEqualTo(api.get("output_sha"));
            assertThat(out.get("match")).isEqualTo(api.get("match"));
            assertThat(out.get("first_bad_seq").isNull()).isTrue();
            assertThat(out.get("head").asText()).isEqualTo(jdbc.queryForObject(
                    "SELECT event_hash FROM audit_events WHERE job_id = ? ORDER BY seq DESC LIMIT 1", String.class, job));
            assertThat(v.status()).isEqualTo(api.get("match").asBoolean() ? AnvilCli.MATCH : AnvilCli.NO_MATCH);
            if (!api.get("match").asBoolean()) {
                assertThat(out.get("problem").asText()).startsWith("the job is not terminal: ");
            }
        }
    }

    @Test
    void aTamperedExportReportsTheFirstBadSeq() throws Exception {
        UUID job = succeeded(A, "tamper");
        Path file = export(A, job);
        // submit 0, claim 1, cap.issue 2, cas.put 3, commit 4.
        assertThat(types(file)).containsExactly("submit", "claim", "cap.issue", "cas.put", "commit");

        assertBadAt(file, job, 2, e -> payload(e, 2).put("epoch", 7), "the chain breaks at seq 2");
        assertBadAt(file, job, 1, e -> {
            payload(e, 3).put("size_bytes", 1);
            payload(e, 1).put("state", "RUNNINH");
        }, "the chain breaks at seq 1");
        assertBadAt(file, job, 3, e -> events(e).remove(2), "the chain breaks at seq 3");
        assertBadAt(file, job, 3, e -> event(e, 3).put("prev_hash", "a".repeat(64)), "the chain breaks at seq 3");
        assertBadAt(file, job, 1, e -> event(e, 1).put("event_hash", "b".repeat(64)), "the chain breaks at seq 1");
        assertBadAt(file, job, 0, e -> event(e, 0).put("type", "claim"), "the chain breaks at seq 0");
        // Swapping the artifact the job produced breaks the cas.put event, not just output_sha.
        assertBadAt(file, job, 3, e -> payload(e, 3).put("artifact_sha", "c".repeat(64)), "the chain breaks at seq 3");
        // An event relabelled to another tenant is dropped, as replay drops it, which leaves a gap.
        assertBadAt(file, job, 3, e -> event(e, 2).put("tenant_id", B), "the chain breaks at seq 3");
        // The same with the last event: the commit is gone, so the terminal state is unbacked.
        Run cut = tampered(file, job, e -> events(e).remove(4));
        assertThat(cut.status()).isEqualTo(AnvilCli.NO_MATCH);
        assertThat(json.readTree(cut.out()).get("problem").asText()).isEqualTo("state SUCCEEDED has no commit event");
    }

    @Test
    void anEditedHeaderCantTurnTheVerdict() throws Exception {
        UUID job = succeeded(A, "header");
        Path file = export(A, job);

        Run failed = tampered(file, job, e -> e.put("state", "FAILED"));
        assertThat(failed.status()).isEqualTo(AnvilCli.NO_MATCH);
        JsonNode f = json.readTree(failed.out());
        assertThat(f.get("problem").asText()).isEqualTo("state FAILED does not match the commit event at seq 4");
        assertThat(f.get("first_bad_seq").isNull()).isTrue();

        Run input = tampered(file, job, e -> e.put("input_sha", "d".repeat(64)));
        assertThat(input.status()).isEqualTo(AnvilCli.NO_MATCH);
        assertThat(json.readTree(input.out()).get("problem").asText())
                .isEqualTo("input_sha does not match the submit event at seq 0");

        // Events out of order in the file are read in seq order, as the database returns them.
        Run shuffled = tampered(file, job, e -> events(e).insert(0, events(e).remove(4)));
        assertThat(shuffled.status()).isEqualTo(AnvilCli.MATCH);
    }

    @Test
    void aCheckpointThatDisagreesWithTheChainIsReported() throws Exception {
        UUID job = longChain(A, 250);
        Path file = export(A, job);
        Run r = tampered(file, job,
                e -> ((ObjectNode) e.withArray("checkpoints").get(1)).put("sha", "e".repeat(64)));
        assertThat(r.status()).isEqualTo(AnvilCli.NO_MATCH);
        JsonNode out = json.readTree(r.out());
        assertThat(out.get("first_bad_seq").asLong()).isEqualTo(200);
        assertThat(out.get("problem").asText()).isEqualTo("the checkpoint at seq 200 does not match the chain");
        // A break before the checkpoint is still the first bad seq.
        Run both = tampered(file, job, e -> {
            ((ObjectNode) e.withArray("checkpoints").get(1)).put("sha", "e".repeat(64));
            payload(e, 150).put("k", "x");
        });
        assertThat(json.readTree(both.out()).get("first_bad_seq").asLong()).isEqualTo(150);
    }

    @Test
    void verifyScopesByTenantAndJobAsTheApiDoes() throws Exception {
        UUID job = succeeded(A, "scope");
        Path file = export(A, job);
        Run absent = verify(B, UUID.randomUUID(), file);
        assertThat(absent.status()).isEqualTo(AnvilCli.NOT_FOUND);
        assertThat(absent.err().strip()).isEqualTo("not found");
        assertThat(absent.out()).isEmpty();
        assertThat(verify(B, job, file)).as("another tenant").isEqualTo(absent);
        assertThat(verify(A, UUID.randomUUID(), file)).as("another job").isEqualTo(absent);
        // Relabelling the whole export to the caller's tenant leaves only foreign events: nothing to verify.
        assertThat(tampered(file, job, e -> e.put("tenant_id", B), B)).isEqualTo(absent);
    }

    @Test
    void aMalformedExportIsRefusedWithoutAVerdict() throws Exception {
        UUID job = succeeded(A, "malformed");
        Path file = export(A, job);
        for (Consumer<ObjectNode> bad : java.util.List.<Consumer<ObjectNode>>of(
                e -> e.put("format", "anvil-chain/9"),
                e -> e.put("extra", 1),
                e -> e.remove("events"),
                e -> event(e, 1).put("seq", "one"),
                e -> event(e, 1).put("seq", -1),
                e -> event(e, 1).put("payload", "{}"),
                e -> e.put("job_id", "not-a-uuid"))) {
            Run r = tampered(file, job, bad);
            assertThat(r.status()).isEqualTo(AnvilCli.USAGE);
            assertThat(r.out()).isEmpty();
            assertThat(r.err()).startsWith("malformed export: ");
        }
        Path notJson = tmp.resolve("not.json");
        Files.writeString(notJson, "{\"format\": \"anvil-chain/1\", \"format\": \"x\"}");
        assertThat(verify(A, job, notJson).err()).startsWith("malformed export: export is not valid JSON");
        Files.writeString(notJson, "[]");
        assertThat(verify(A, job, notJson).status()).isEqualTo(AnvilCli.USAGE);
    }

    @Test
    void exportNeverOverwritesAKeptFile() throws Exception {
        UUID job = succeeded(A, "overwrite");
        Path file = export(A, job);
        byte[] kept = Files.readAllBytes(file);
        Run again = cli("export", "--tenant", A, "--job", job.toString(), "--out", file.toString());
        assertThat(again.status()).isEqualTo(AnvilCli.USAGE);
        assertThat(Files.readAllBytes(file)).isEqualTo(kept);
    }

    // ---- helpers ----

    private record Run(int status, String out, String err) {
    }

    private Run cli(String... args) {
        return run(this::connect, args);
    }

    /** verify must not need a database: its connection throws. */
    private Run verify(String tenant, UUID job, Path file) {
        return run(() -> {
            throw new AssertionError("verify opened a database connection");
        }, "verify", "--tenant", tenant, "--job", job.toString(), file.toString());
    }

    private static Run run(AnvilCli.Database database, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int status = new AnvilCli(database, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private Path export(String tenant, UUID job) {
        Path file = tmp.resolve(job + ".json");
        Run r = cli("export", "--tenant", tenant, "--job", job.toString(), "--out", file.toString());
        assertThat(r.status()).as(r.err()).isEqualTo(AnvilCli.MATCH);
        return file;
    }

    private JsonNode apiReplay(String tenant, UUID job) throws Exception {
        return json.readTree(mvc.perform(get("/audit/replay").header("X-Tenant-Id", tenant)
                .param("job_id", job.toString())).andReturn().getResponse().getContentAsString());
    }

    private Run tampered(Path file, UUID job, Consumer<ObjectNode> edit) throws Exception {
        return tampered(file, job, edit, A);
    }

    private Run tampered(Path file, UUID job, Consumer<ObjectNode> edit, String asTenant) throws Exception {
        ObjectNode export = (ObjectNode) json.readTree(file.toFile());
        edit.accept(export);
        Path copy = Files.createTempFile(tmp, "tampered", ".json");
        json.writeValue(copy.toFile(), export);
        return verify(asTenant, job, copy);
    }

    private void assertBadAt(Path file, UUID job, long seq, Consumer<ObjectNode> edit, String problem)
            throws Exception {
        Run r = tampered(file, job, edit);
        assertThat(r.status()).as(r.err()).isEqualTo(AnvilCli.NO_MATCH);
        JsonNode out = json.readTree(r.out());
        assertThat(out.get("match").asBoolean()).isFalse();
        assertThat(out.get("first_bad_seq").asLong()).as(problem).isEqualTo(seq);
        assertThat(out.get("problem").asText()).isEqualTo(problem);
    }

    private java.util.List<String> types(Path file) throws Exception {
        java.util.List<String> types = new java.util.ArrayList<>();
        json.readTree(file.toFile()).get("events").forEach(e -> types.add(e.get("type").asText()));
        return types;
    }

    private static ArrayNode events(ObjectNode export) {
        return export.withArray("events");
    }

    /** The event with this seq. */
    private static ObjectNode event(ObjectNode export, long seq) {
        for (JsonNode e : events(export)) {
            if (e.get("seq").asLong() == seq) {
                return (ObjectNode) e;
            }
        }
        throw new AssertionError("no event at seq " + seq);
    }

    private static ObjectNode payload(ObjectNode export, long seq) {
        return (ObjectNode) event(export, seq).get("payload");
    }

    private static String one(Statement s, String sql) throws SQLException {
        try (var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    // ---- jobs ----

    /** submit, claim, cap.issue, cas.put, commit: a SUCCEEDED job with an artifact. */
    private UUID succeeded(String tenant, String key) throws Exception {
        UUID job = submits.submit(tenant, "cli-" + key + "-" + UUID.randomUUID(), "CLI", "text/plain",
                "cli-body-" + key).jobId();
        ClaimedJob claimed = jobs.claim(tenant, "cli-w").orElseThrow();
        assertThat(claimed.jobId()).isEqualTo(job);
        CapGrant cap = jobs.issueCap(tenant, job);
        new ContentStore(jdbc, txManager, tmp.resolve("cas").toString())
                .putForJob(tenant, job, ("cli-bytes-" + key).getBytes(StandardCharsets.UTF_8));
        assertThat(jobs.commitSucceeded(tenant, job, "cli-w", claimed.fencingToken(), cap.capId(), cap.epoch()))
                .isTrue();
        return job;
    }

    private UUID running(String tenant, String key) {
        UUID job = submits.submit(tenant, "cli-" + key + "-" + UUID.randomUUID(), "CLI run", "text/plain",
                "cli-run-" + key).jobId();
        assertThat(jobs.claim(tenant, "cli-run-w").orElseThrow().jobId()).isEqualTo(job);
        return job;
    }

    /** A submitted job whose seq 1 carries a hash for a payload other than the one stored. */
    private UUID tamperedInTheDatabase(String tenant) throws Exception {
        UUID job = submits.submit(tenant, "cli-tamper-" + UUID.randomUUID(), "T", "text/plain", "t-body").jobId();
        String x0 = jdbc.queryForObject("SELECT event_hash FROM audit_events WHERE job_id = ? AND seq = 0",
                String.class, job);
        String hash = AuditChain.computeHash("claim", job, 1L, x0, CanonicalCbor.encodeMap(Map.of("state", "RUNNING")));
        jdbc.update("INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                + "VALUES (?, ?, 1, ?, ?, 'claim', ?::jsonb)", tenant, job, x0, hash,
                json.writeValueAsString(Map.of("state", "RUNNINH")));
        return job;
    }

    /** A queued job with {@code appends} more events, so checkpoints at every 100. */
    private UUID longChain(String tenant, int appends) {
        UUID job = submits.submit(tenant, "cli-long-" + UUID.randomUUID(), "Long", "text/plain", "long").jobId();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        for (int i = 1; i <= appends; i++) {
            int n = i;
            tx.executeWithoutResult(s -> {
                jdbc.queryForObject("SELECT set_config('app.tenant', ?, true)", String.class, tenant);
                AuditChain.append(jdbc, tenant, job, AuditChain.TYPE_CLAIM, Map.of("state", "RUNNING", "k", "v" + n));
            });
        }
        return job;
    }
}
