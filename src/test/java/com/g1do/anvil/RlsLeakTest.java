package com.g1do.anvil;

import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.g1do.anvil.audit.AuditChain;
import com.g1do.anvil.audit.AuditReadService;
import com.g1do.anvil.cas.CasPresignedService;
import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.jobs.CapDeniedException;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.jobs.TenantClaim;
import com.g1do.anvil.outbox.IdempotentConsumer;
import com.g1do.anvil.outbox.OutboxEvent;
import com.g1do.anvil.outbox.OutboxRelay;
import com.g1do.anvil.read.ReadService;
import com.g1do.anvil.replay.ReplayService;
import com.g1do.anvil.submit.SubmitService;
import com.g1do.anvil.tenant.TenantGuard;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pool-reuse leak probe for DB-enforced tenant isolation (issue #20).
 *
 * <p>Proves on containerized PG18 with a shared Hikari pool as the restricted
 * {@code anvil_app} role (NOSUPERUSER NOBYPASSRLS): interleaved tenants,
 * repeated checkouts and plan-cache reuse show zero cross-tenant rows;
 * missing context fails closed; no DEFINER/BYPASSRLS path is reachable.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RlsLeakTest {

    private static final String TENANT_A = "t_rls_a";
    private static final String TENANT_B = "t_rls_b";

    // Every tenant-scoped table: V1/V2 (7), cap_grants (V3), audit_events (V4), job_checkpoints (V5).
    private static final List<String> TENANT_TABLES = List.of(
            "documents", "document_versions", "jobs", "attempts", "artifacts", "outbox", "processed_events",
            "cap_grants", "audit_events", "job_checkpoints", "extractions", "extracted_facts",
            "extraction_dead_letters");

    // Interleaved rounds per query path. Each round runs tenant A then tenant B on the
    // shared pool, so connections are reused and repeated statements reach generic plans.
    private static final int ROUNDS = 20;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @TempDir
    Path tempDir;

    private HikariDataSource appPool;
    private JdbcTemplate appJdbc;
    private TransactionTemplate appTx;
    private Seed seedA;
    private Seed seedB;

    @BeforeEach
    void setUp() {
        // Superuser wiring (bypasses RLS by design): clean + seed two tenants.
        // TRUNCATE (no dead tuples) so this class's seeding never bloats the
        // shared container and flips the planner in the next class (same
        // hazard OutboxRelayTest documents for outbox_poll_idx).
        truncate();
        jdbc.execute("DELETE FROM tenants WHERE id NOT IN ('t_single', '" + TENANT_A + "', '" + TENANT_B + "')");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", TENANT_A);
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", TENANT_B);

        seedA = seedTenantChain(TENANT_A, "a");
        seedB = seedTenantChain(TENANT_B, "b");

        appPool = newAppPool();
        appJdbc = new JdbcTemplate(appPool);
        appTx = new TransactionTemplate(new DataSourceTransactionManager(appPool));
    }

    @AfterEach
    void tearDown() {
        if (appPool != null) {
            appPool.close();
            appPool = null;
        }
        truncate();
        jdbc.execute("DELETE FROM tenants WHERE id IN ('" + TENANT_A + "', '" + TENANT_B + "')");
    }

    private void truncate() {
        jdbc.execute("TRUNCATE TABLE extraction_dead_letters, extracted_facts, extractions, job_checkpoints, audit_events, cap_grants, processed_events, outbox, attempts, jobs, document_versions, documents, artifacts");
    }

    private HikariDataSource newAppPool() {
        if (!(dataSource instanceof HikariDataSource hikari) || hikari.getJdbcUrl() == null) {
            throw new IllegalStateException("Hikari DataSource with JDBC URL required");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(hikari.getJdbcUrl());
        config.setUsername("anvil_app");
        config.setPassword("anvil_app");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setPoolName("rls-leak-app-pool-" + UUID.randomUUID());
        // Small pool forces checkout reuse across the interleave loop below.
        return new HikariDataSource(config);
    }

    /** One tenant's seeded IDs, so probes can aim at the other tenant's rows. */
    private record Seed(
            String tenant,
            String idempotencyKey,
            String title,
            UUID docId,
            UUID versionId,
            UUID jobId,
            UUID attemptId,
            UUID outboxId,
            String sha) {
    }

    private Seed seedTenantChain(String tenant, String seed) {
        UUID doc = UUID.randomUUID();
        String title = "title-" + seed;
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                doc, tenant, title, "text/plain");
        UUID ver = UUID.randomUUID();
        String sha = seed.repeat(64).substring(0, 64).replaceAll("[^0-9a-f]", "a");
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                ver, tenant, doc, sha);
        UUID job = UUID.randomUUID();
        String idempotencyKey = "rls-key-" + seed + "-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, idempotencyKey);
        UUID attempt = jdbc.queryForObject(
                "INSERT INTO attempts (tenant_id, job_id, attempt_number, owner, fencing_token, outcome) VALUES (?, ?, 0, ?, 0, 'CLAIMED') RETURNING id",
                UUID.class, tenant, job, "seeder-" + seed);
        jdbc.update(
                "INSERT INTO artifacts (tenant_id, sha256, size_bytes, refcount) VALUES (?, ?, ?, 0)",
                tenant, sha, 16);
        UUID outboxId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, sha256, payload) VALUES (?, ?, ?, ?, ?, 'job.committed', ?, '{}'::jsonb)",
                outboxId, tenant, job, doc, ver, sha);
        jdbc.update(
                "INSERT INTO processed_events (event_id, tenant_id, job_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), tenant, job);
        jdbc.update(
                "INSERT INTO cap_grants (tenant_id, job_id, epoch, revoked, expires_at) "
                        + "VALUES (?, ?, 1, FALSE, clock_timestamp() + INTERVAL '1 hour')",
                tenant, job);
        // The chain head comes from the production helper. The checkpoint row is
        // seeded directly; production writes one only every 100 events.
        String head = AuditChain.append(jdbc, tenant, job, AuditChain.TYPE_SUBMIT, Map.of());
        jdbc.update(
                "INSERT INTO job_checkpoints (tenant_id, job_id, seq, sha) VALUES (?, ?, 0, ?)",
                tenant, job, head);
        // Extraction rows (#37): one extraction with one indexed fact and one dead letter.
        UUID extraction = jdbc.queryForObject(
                "INSERT INTO extractions (tenant_id, document_id, version_id, model, prompt_sha256, status, truncated) "
                        + "VALUES (?, ?, ?, 'seed-model', ?, 'ACCEPTED', false) RETURNING id",
                UUID.class, tenant, doc, ver, "0".repeat(64));
        jdbc.update("INSERT INTO extracted_facts (tenant_id, extraction_id, ordinal, field, value, citation) "
                + "VALUES (?, ?, 0, 'party', ?, ?)", tenant, extraction, "Org-" + seed, "Org-" + seed + " signed");
        jdbc.update("INSERT INTO extraction_dead_letters (tenant_id, extraction_id, reason, detail) "
                + "VALUES (?, ?, 'CITATION_NOT_IN_SOURCE', 'seed')", tenant, extraction);
        return new Seed(tenant, idempotencyKey, title, doc, ver, job, attempt, outboxId, sha);
    }

    @Test
    void rlsForceEnabledOnAllTenantTables() {
        for (String table : TENANT_TABLES) {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?", table);
            assertThat(row.get("relrowsecurity"))
                    .as("RLS enabled on %s", table)
                    .isEqualTo(true);
            assertThat(row.get("relforcerowsecurity"))
                    .as("RLS FORCE on %s", table)
                    .isEqualTo(true);
        }
        // Tenants lookup stays without RLS by design (auth target).
        Map<String, Object> tenantsRow = jdbc.queryForMap(
                "SELECT relrowsecurity FROM pg_class WHERE relname = 'tenants'");
        assertThat(tenantsRow.get("relrowsecurity"))
                .as("tenants table has no RLS")
                .isEqualTo(false);
    }

    @Test
    void appRoleRestrictedAndMigratorOwns() {
        Map<String, Object> app = jdbc.queryForMap(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'anvil_app'");
        assertThat(app.get("rolsuper")).as("app NOSUPERUSER").isEqualTo(false);
        assertThat(app.get("rolbypassrls")).as("app NOBYPASSRLS").isEqualTo(false);

        for (String table : TENANT_TABLES) {
            String owner = jdbc.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = ?",
                    String.class, table);
            assertThat(owner).as("owner of %s", table).isEqualTo("anvil_migrator");
        }

        Integer definers = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = 'public' AND p.prosecdef",
                Integer.class);
        assertThat(definers).as("no SECURITY DEFINER in public").isZero();

        String funcSec = jdbc.queryForObject(
                "SELECT prosecdef FROM pg_proc WHERE proname = 'check_job_state_transition'",
                Boolean.class).toString();
        assertThat(funcSec).as("guard trigger stays INVOKER").isEqualTo("false");
    }

    @Test
    void policiesUseTenantGucOnEveryTable() {
        for (String table : TENANT_TABLES) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_policies WHERE schemaname = 'public' AND tablename = ? "
                            + "AND policyname = 'tenant_isolation'",
                    Integer.class, table);
            assertThat(count).as("policy on %s", table).isEqualTo(1);
            String qual = jdbc.queryForObject(
                    "SELECT qual FROM pg_policies WHERE schemaname = 'public' AND tablename = ? "
                            + "AND policyname = 'tenant_isolation'",
                    String.class, table);
            assertThat(qual).as("USING on %s", table).contains("current_setting('app.tenant'");
            String check = jdbc.queryForObject(
                    "SELECT with_check FROM pg_policies WHERE schemaname = 'public' AND tablename = ? "
                            + "AND policyname = 'tenant_isolation'",
                    String.class, table);
            assertThat(check).as("WITH CHECK on %s", table).contains("current_setting('app.tenant'");
        }
    }

    @Test
    void missingContextFailsClosedWithZeroRows() {
        // Same shared app pool, no SET LOCAL: every tenant table reads zero rows.
        for (String table : TENANT_TABLES) {
            Integer count = appJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).as("missing context on %s fails closed", table).isZero();
        }
        // Control: superuser bypasses RLS and still sees seeded rows.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void setLocalPlusProbeIsolatesTenants() {
        for (String tenant : List.of(TENANT_A, TENANT_B)) {
            String other = tenant.equals(TENANT_A) ? TENANT_B : TENANT_A;
            appTx.execute(status -> {
                TenantGuard.setTenant(appJdbc, tenant);
                for (String table : TENANT_TABLES) {
                    List<String> owners = appJdbc.query(
                            "SELECT tenant_id FROM " + table, (rs, i) -> rs.getString("tenant_id"));
                    assertThat(owners).as("%s as %s", table, tenant).isNotEmpty();
                    assertThat(owners).as("%s visible only to %s", table, tenant)
                            .allSatisfy(id -> assertThat(id).isEqualTo(tenant));
                    Integer cross = appJdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, other);
                    assertThat(cross).as("cross-tenant WHERE as %s on %s", tenant, table).isZero();
                }
                return null;
            });
        }
    }

    @Test
    void poolReuseInterleaveShowsZeroLeaksWithPlanCacheReuse() {
        // Shared pool, small max (5), 120 interleaved checkouts: each checkout sets
        // SET LOCAL + probe, then reads all tenant tables without WHERE (RLS alone
        // must isolate) and with WHERE (defense in depth). Repeated identical SQL
        // forces PG generic-plan reuse; any plan-cache leak would surface as a
        // cross-tenant row.
        int iterations = 120;
        int leaks = 0;
        for (int i = 0; i < iterations; i++) {
            final int iter = i;
            String tenant = (i % 2 == 0) ? TENANT_A : TENANT_B;
            String other = tenant.equals(TENANT_A) ? TENANT_B : TENANT_A;
            Integer crossInTx = appTx.execute(status -> {
                TenantGuard.setTenant(appJdbc, tenant);
                // Probe read-back inside the same checkout.
                String current = TenantGuard.currentTenant(appJdbc);
                assertThat(current).isEqualTo(tenant);
                int cross = 0;
                for (String table : TENANT_TABLES) {
                    // No-WHERE read: RLS alone isolates.
                    List<String> owners = appJdbc.query(
                            "SELECT tenant_id FROM " + table, (rs, idx) -> rs.getString("tenant_id"));
                    for (String id : owners) {
                        if (!tenant.equals(id)) {
                            cross++;
                        }
                    }
                    // Explicit cross-tenant WHERE still fails closed via RLS.
                    Integer explicit = appJdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, other);
                    cross += explicit == null ? 0 : explicit;
                    // Own rows remain visible (no over-blocking).
                    Integer own = appJdbc.queryForObject(
                            "SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?", Integer.class, tenant);
                    assertThat(own).as("own rows visible as %s on %s (iter %d)", tenant, table, iter)
                            .isGreaterThan(0);
                }
                return cross;
            });
            leaks += crossInTx == null ? 0 : crossInTx;
        }
        assertThat(leaks).as("cross-tenant rows over %d interleaved checkouts", iterations).isZero();

        // After all SET LOCAL transactions commit, a fresh checkout without context
        // still sees zero rows: LOCAL reset, no session leak on the pooled link.
        for (String table : TENANT_TABLES) {
            Integer count = appJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).as("pooled link clean after commits on %s", table).isZero();
        }
    }

    @Test
    void tenantScopedRelayAndClaimSeeOnlyOwnTenant() {
        // Hot-path services on the restricted pool stay tenant-scoped.
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        JobService appJobs = new JobService(appJdbc, appManager);
        OutboxRelay appRelay = new OutboxRelay(appJdbc, appManager);
        IdempotentConsumer appConsumer = new IdempotentConsumer(appJdbc, appManager);

        var claimedA = appJobs.claim(TENANT_A, "rls-w-a");
        assertThat(claimedA).isPresent();
        UUID jobA = claimedA.get().jobId();
        String ownerTenantA = jdbc.queryForObject("SELECT tenant_id FROM jobs WHERE id = ?", String.class, jobA);
        assertThat(ownerTenantA).isEqualTo(TENANT_A);

        var claimedB = appJobs.claim(TENANT_B, "rls-w-b");
        assertThat(claimedB).isPresent();
        UUID jobB = claimedB.get().jobId();
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM jobs WHERE id = ?", String.class, jobB))
                .isEqualTo(TENANT_B);
        assertThat(jobB).isNotEqualTo(jobA);

        // Relay per tenant only touches that tenant's outbox.
        int markedA = appRelay.relayOnce((event, headers) -> {
            assertThat(event.tenantId()).isEqualTo(TENANT_A);
            appConsumer.consume(event);
        }, TENANT_A);
        assertThat(markedA).isGreaterThanOrEqualTo(1);
        Integer remainingA = appTx.execute(status -> {
            TenantGuard.setTenant(appJdbc, TENANT_A);
            return appJdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL", Integer.class);
        });
        assertThat(remainingA).isZero();

        // B's outbox is untouched by A's relay (still unsent until B relays).
        Integer remainingB = appTx.execute(status -> {
            TenantGuard.setTenant(appJdbc, TENANT_B);
            return appJdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL", Integer.class);
        });
        assertThat(remainingB).isGreaterThan(0);
    }

    @Test
    void tenantScopedPollStillUsesOutboxIndex() {
        // Seed volume so the planner prefers the partial index (mirrors
        // OutboxRelayTest): mostly-sent history with a small unsent backlog.
        List<UUID> jobIds = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            UUID doc = UUID.randomUUID();
            jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, 'text/plain')",
                    doc, TENANT_A, "rls-idx-" + i);
            UUID ver = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                    ver, TENANT_A, doc, String.format("%064x", i + 9000));
            UUID job = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                    job, TENANT_A, doc, ver, "rls-idx-" + i + "-" + UUID.randomUUID());
            jobIds.add(job);
            jdbc.update("INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, payload) "
                    + "SELECT ?, tenant_id, id, document_id, version_id, 'job.committed', '{}'::jsonb FROM jobs WHERE id = ?",
                    UUID.randomUUID(), job);
        }
        for (UUID jobId : jobIds) {
            jdbc.update("INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, payload, sent_at) "
                    + "SELECT ?, tenant_id, id, document_id, version_id, 'job.committed', '{}'::jsonb, clock_timestamp() FROM jobs WHERE id = ?",
                    UUID.randomUUID(), jobId);
        }
        jdbc.update("INSERT INTO outbox (tenant_id, job_id, doc_id, version_id, type, payload, sent_at) "
                + "SELECT j.tenant_id, j.id, j.document_id, j.version_id, 'job.committed', '{}'::jsonb, clock_timestamp() "
                + "FROM jobs j CROSS JOIN generate_series(1, 10) g WHERE j.tenant_id = '" + TENANT_A + "'");
        jdbc.execute("ANALYZE outbox");
        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT id, tenant_id, job_id, doc_id, version_id, type, sha256 FROM outbox "
                        + "WHERE tenant_id = '" + TENANT_A + "' AND sent_at IS NULL "
                        + "ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED",
                String.class));
        assertThat(plan).contains("outbox_poll_idx");
        assertThat(plan).doesNotContain("Seq Scan");
    }

    @Test
    void appSubmitIdempotentReplayReturnsWinner() throws Exception {
        // Submit as the restricted app role (NOBYPASSRLS, RLS FORCE): the
        // fast-path read and the UNIQUE-race re-read must both see the winner.
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        SubmitService appSubmit = new SubmitService(appJdbc, appManager);

        String key = "app-submit-" + UUID.randomUUID();
        SubmitService.SubmitResult first = appSubmit.submit(TENANT_A, key, "App Doc", "text/plain",
                "app-body");
        SubmitService.SubmitResult second = appSubmit.submit(TENANT_A, key, "App Doc", "text/plain",
                "app-body");
        assertThat(second.docId()).isEqualTo(first.docId());
        assertThat(second.versionId()).isEqualTo(first.versionId());
        assertThat(second.jobId()).isEqualTo(first.jobId());

        String raceKey = "app-race-" + UUID.randomUUID();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Callable<SubmitService.SubmitResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    ready.countDown();
                    if (!go.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start timeout");
                    }
                    return appSubmit.submit(TENANT_A, raceKey, "App Race", "text/plain", "race-body");
                });
            }
            List<Future<SubmitService.SubmitResult>> futures = new ArrayList<>();
            for (Callable<SubmitService.SubmitResult> task : tasks) {
                futures.add(pool.submit(task));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<SubmitService.SubmitResult> results = new ArrayList<>();
            for (Future<SubmitService.SubmitResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            SubmitService.SubmitResult winner = results.get(0);
            for (SubmitService.SubmitResult result : results) {
                assertThat(result.docId()).isEqualTo(winner.docId());
                assertThat(result.versionId()).isEqualTo(winner.versionId());
                assertThat(result.jobId()).isEqualTo(winner.jobId());
            }
            Integer jobs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM jobs WHERE tenant_id = ? AND idempotency_key = ?",
                    Integer.class, TENANT_A, raceKey);
            assertThat(jobs).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void guardRejectsBlankTenant() {
        assertThatThrownBy(() -> appTx.execute(status -> TenantGuard.setTenant(appJdbc, " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Path matrix (issue #74): each test drives one group of query paths as the
    // restricted role on the shared pool, alternating tenants, and probes with the
    // other tenant's IDs. Own calls must still succeed (no over-blocking).

    @Test
    void submitAndClaimStayInTheCallerTenantAcrossPooledInterleave() {
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        SubmitService appSubmit = new SubmitService(appJdbc, appManager);
        JobService appJobs = new JobService(appJdbc, appManager);
        for (int round = 0; round < ROUNDS; round++) {
            for (Seed caller : List.of(seedA, seedB)) {
                Seed other = otherOf(caller);
                // Idempotency keys are scoped per tenant, so the other tenant's key
                // must open the caller's own job, never return the other tenant's.
                SubmitService.SubmitResult submitted = appSubmit.submit(
                        caller.tenant(), other.idempotencyKey(), "Round " + round, "text/plain", "body-" + round);
                assertThat(submitted.jobId()).as("submit as %s, round %d", caller.tenant(), round)
                        .isNotEqualTo(other.jobId());
                assertThat(tenantOfJob(submitted.jobId())).as("submit owner, round %d", round)
                        .isEqualTo(caller.tenant());

                Optional<ClaimedJob> claimed = appJobs.claim(caller.tenant(), "matrix-" + caller.tenant());
                if (round == 0) {
                    assertThat(claimed).as("own queued job claimable by %s", caller.tenant()).isPresent();
                }
                if (claimed.isPresent()) {
                    assertThat(tenantOfJob(claimed.get().jobId())).as("claim owner, round %d", round)
                            .isEqualTo(caller.tenant());
                    assertThat(claimed.get().jobId()).isNotEqualTo(other.jobId());
                }
            }
        }
        assertPooledLinkClean();
    }

    @Test
    void claimNextClaimsEachTenantsJobUnderThatTenantsContext() {
        // The fair claim (#35) reads every tenant's head job, one tenant context per
        // statement. As the restricted role, a wrong or missing context would read 0 rows
        // and claim nothing, so claiming both seeded jobs shows each probe ran as its tenant.
        JobService appJobs = new JobService(appJdbc, new DataSourceTransactionManager(appPool));
        Map<String, UUID> claimed = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            TenantClaim next = appJobs.claimNext("matrix-next").orElseThrow();
            assertThat(tenantOfJob(next.job().jobId())).as("claimNext tenant matches the job's")
                    .isEqualTo(next.tenantId());
            assertThat(claimed.put(next.tenantId(), next.job().jobId())).as("one claim per tenant").isNull();
        }
        assertThat(claimed).containsExactlyInAnyOrderEntriesOf(
                Map.of(TENANT_A, seedA.jobId(), TENANT_B, seedB.jobId()));
        assertThat(appJobs.claimNext("matrix-next")).isEmpty();
        assertPooledLinkClean();
    }

    @Test
    void heartbeatCommitAndCapPathsRefuseAnotherTenantsJobs() {
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        JobService appJobs = new JobService(appJdbc, appManager);
        // Each tenant holds a live lease on its own job and a capability for it.
        Map<String, ClaimedJob> leases = new HashMap<>();
        Map<String, CapGrant> caps = new HashMap<>();
        for (Seed caller : List.of(seedA, seedB)) {
            leases.put(caller.tenant(), appJobs.claim(caller.tenant(), "owner-" + caller.tenant()).orElseThrow());
            caps.put(caller.tenant(), appJobs.issueCap(caller.tenant(), caller.jobId()));
        }
        for (int round = 0; round < ROUNDS; round++) {
            for (Seed caller : List.of(seedA, seedB)) {
                String tenant = caller.tenant();
                ClaimedJob own = leases.get(tenant);
                ClaimedJob foreign = leases.get(otherOf(caller).tenant());
                CapGrant foreignCap = caps.get(otherOf(caller).tenant());
                assertThat(appJobs.heartbeat(tenant, own.jobId(), own.owner(), own.fencingToken()))
                        .as("own heartbeat as %s, round %d", tenant, round).isTrue();
                // The other tenant's job with its real owner and token: 0 rows.
                assertThat(appJobs.heartbeat(tenant, foreign.jobId(), foreign.owner(), foreign.fencingToken()))
                        .as("foreign heartbeat as %s, round %d", tenant, round).isFalse();
                // The caller's own owner and token against the other tenant's job: 0 rows.
                assertThat(appJobs.heartbeat(tenant, foreign.jobId(), own.owner(), own.fencingToken()))
                        .as("foreign job with own token as %s, round %d", tenant, round).isFalse();
                // Commits are denied at the capability gate, plain or presenting the other tenant's grant.
                assertThatThrownBy(() -> appJobs.commitSucceeded(
                        tenant, foreign.jobId(), foreign.owner(), foreign.fencingToken()))
                        .as("foreign commit as %s, round %d", tenant, round)
                        .isInstanceOf(CapDeniedException.class);
                assertThatThrownBy(() -> appJobs.commitFailed(
                        tenant, foreign.jobId(), foreign.owner(), foreign.fencingToken()))
                        .isInstanceOf(CapDeniedException.class);
                assertThatThrownBy(() -> appJobs.commitSucceeded(
                        tenant, foreign.jobId(), foreign.owner(), foreign.fencingToken(),
                        foreignCap.capId(), foreignCap.epoch()))
                        .isInstanceOf(CapDeniedException.class);
                // Revoke affects 0 rows; issue cannot attach a grant to a job the caller does not own.
                appJobs.revokeCap(tenant, foreign.jobId());
                assertThat(revokedOf(foreign.jobId())).as("foreign grant not revoked, round %d", round).isFalse();
                assertThatThrownBy(() -> appJobs.issueCap(tenant, foreign.jobId()))
                        .as("foreign issue as %s, round %d", tenant, round)
                        .isInstanceOf(DataIntegrityViolationException.class);
                // The other tenant's job is untouched: still RUNNING, same owner and token.
                assertThat(stateOfJob(foreign.jobId())).as("foreign state, round %d", round).isEqualTo("RUNNING");
                assertThat(ownerOfJob(foreign.jobId())).isEqualTo(foreign.owner());
                assertThat(fencingOfJob(foreign.jobId())).isEqualTo(foreign.fencingToken());
            }
        }
        for (Seed caller : List.of(seedA, seedB)) {
            ClaimedJob own = leases.get(caller.tenant());
            CapGrant cap = caps.get(caller.tenant());
            assertThat(appJobs.commitSucceeded(
                    caller.tenant(), own.jobId(), own.owner(), own.fencingToken(), cap.capId(), cap.epoch()))
                    .as("own commit as %s", caller.tenant()).isTrue();
        }
        assertPooledLinkClean();
    }

    @Test
    void relayPublishesOnlyTheCallerTenantsEvents() {
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        OutboxRelay appRelay = new OutboxRelay(appJdbc, appManager);
        IdempotentConsumer appConsumer = new IdempotentConsumer(appJdbc, appManager);
        for (int round = 0; round < ROUNDS; round++) {
            for (Seed caller : List.of(seedA, seedB)) {
                Seed other = otherOf(caller);
                UUID ownEvent = insertUnsentOutbox(caller);
                UUID foreignEvent = insertUnsentOutbox(other);
                int foreignUnsent = unsentOutbox(other.tenant());
                List<OutboxEvent> published = new ArrayList<>();
                appRelay.relayOnce((event, headers) -> {
                    published.add(event);
                    appConsumer.consume(event);
                }, caller.tenant());
                assertThat(published).as("events published as %s, round %d", caller.tenant(), round)
                        .extracting(OutboxEvent::eventId).contains(ownEvent).doesNotContain(foreignEvent);
                assertThat(published).allSatisfy(event -> assertThat(event.tenantId()).isEqualTo(caller.tenant()));
                assertThat(unsentOutbox(other.tenant())).as("foreign outbox untouched, round %d", round)
                        .isEqualTo(foreignUnsent);
            }
        }
        assertPooledLinkClean();
    }

    @Test
    void readsAndSearchReturnNothingForAnotherTenantsIds() {
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        ReadService reads = new ReadService(appJdbc, appManager);
        for (int round = 0; round < ROUNDS; round++) {
            for (Seed caller : List.of(seedA, seedB)) {
                String tenant = caller.tenant();
                Seed other = otherOf(caller);
                assertThat(reads.getDocument(tenant, caller.docId())).as("own document, round %d", round).isPresent();
                assertThat(reads.getVersion(tenant, caller.versionId())).isPresent();
                assertThat(reads.getJob(tenant, caller.jobId())).isPresent();
                assertThat(reads.getAttempt(tenant, caller.attemptId())).isPresent();
                assertThat(reads.getArtifact(tenant, caller.sha())).isPresent();
                assertThat(reads.getOutboxEvent(tenant, caller.outboxId())).isPresent();
                // Another tenant's IDs read exactly like missing ones.
                assertThat(reads.getDocument(tenant, other.docId())).as("foreign document, round %d", round).isEmpty();
                assertThat(reads.getVersion(tenant, other.versionId())).isEmpty();
                assertThat(reads.getJob(tenant, other.jobId())).isEmpty();
                assertThat(reads.getAttempt(tenant, other.attemptId())).isEmpty();
                assertThat(reads.getArtifact(tenant, other.sha())).isEmpty();
                assertThat(reads.getOutboxEvent(tenant, other.outboxId())).isEmpty();
                // Title-prefix search returns only the caller's documents, even for the other tenant's prefix.
                assertThat(reads.searchDocuments(tenant, other.title(), null, null))
                        .as("foreign title prefix, round %d", round).isEmpty();
                assertThat(reads.searchDocuments(tenant, "title-", null, null))
                        .as("own title prefix, round %d", round)
                        .extracting(ReadService.DocumentRow::id).contains(caller.docId()).doesNotContain(other.docId());
                assertThat(reads.searchDocuments(tenant, "title-", null, null))
                        .allSatisfy(doc -> assertThat(doc.tenantId()).isEqualTo(tenant));
                // A cursor naming the other tenant's document fails closed to 0 rows.
                assertThat(reads.searchDocuments(tenant, "title-", null, other.docId()))
                        .as("foreign cursor, round %d", round).isEmpty();
                // Listings name only the caller's rows.
                assertThat(reads.listJobs(tenant, null)).as("job listing, round %d", round)
                        .extracting(ReadService.JobRow::id).contains(caller.jobId()).doesNotContain(other.jobId());
                assertThat(reads.listArtifacts(tenant, null)).as("artifact listing, round %d", round)
                        .extracting(ReadService.ArtifactRow::sha256).contains(caller.sha()).doesNotContain(other.sha());
            }
        }
        assertPooledLinkClean();
    }

    @Test
    void auditReadsAndReplayRefuseAnotherTenantsJobs() {
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        AuditReadService audit = new AuditReadService(appJdbc, appManager);
        ReplayService replay = new ReplayService(appJdbc, appManager);
        for (int round = 0; round < ROUNDS; round++) {
            for (Seed caller : List.of(seedA, seedB)) {
                String tenant = caller.tenant();
                Seed other = otherOf(caller);
                assertThat(audit.listEvents(tenant, caller.jobId(), null, null)).as("own chain, round %d", round)
                        .isNotEmpty()
                        .allSatisfy(row -> assertThat(row.tenantId()).isEqualTo(tenant));
                assertThat(audit.listEvents(tenant, other.jobId(), null, null))
                        .as("foreign chain, round %d", round).isEmpty();
                assertThatThrownBy(() -> replay.replay(tenant, other.jobId()))
                        .as("foreign replay, round %d", round).isInstanceOf(ReplayService.ReplayNotFoundException.class);
                assertThatThrownBy(() -> replay.replayFull(tenant, other.jobId()))
                        .as("foreign full replay, round %d", round).isInstanceOf(ReplayService.ReplayNotFoundException.class);
                // The own chain is intact. The seeded job is not terminal, so the replay
                // reports -1: no chain break, state not terminal.
                assertThat(replay.replay(tenant, caller.jobId()).mismatchSeq())
                        .as("own replay, round %d", round).isEqualTo(-1L);
            }
        }
        assertPooledLinkClean();
    }

    @Test
    void casAndPresignedFetchRefuseAnotherTenantsContent() throws Exception {
        DataSourceTransactionManager appManager = new DataSourceTransactionManager(appPool);
        JobService appJobs = new JobService(appJdbc, appManager);
        ContentStore store = new ContentStore(appJdbc, appManager, tempDir.toString());
        CasPresignedService presigned = new CasPresignedService(
                appJdbc, appManager, appJobs, store, "rls-leak-test-secret".getBytes(StandardCharsets.UTF_8));
        // Tenant B stores content for its own job and a presigned token for it.
        byte[] bytesB = "tenant-b-content".getBytes(StandardCharsets.UTF_8);
        String shaB = store.putForJob(seedB.tenant(), seedB.jobId(), bytesB);
        appJobs.issueCap(seedB.tenant(), seedB.jobId());
        String tokenB = presigned.issue(seedB.tenant(), seedB.jobId(), shaB);
        int refcountB = refcountOf(seedB.tenant(), shaB);
        for (int round = 0; round < ROUNDS; round++) {
            for (Seed caller : List.of(seedA, seedB)) {
                String tenant = caller.tenant();
                if (tenant.equals(seedB.tenant())) {
                    assertThat(store.get(tenant, shaB)).as("own bytes, round %d", round).isEqualTo(bytesB);
                    assertThat(store.getForCaller(tenant, tenant, shaB)).isEqualTo(bytesB);
                    assertThat(presigned.fetch(tenant, tokenB)).isEqualTo(bytesB);
                } else {
                    // No file under this tenant's tree and no artifacts row: no bytes.
                    assertThatThrownBy(() -> store.get(tenant, shaB))
                            .as("foreign bytes as %s, round %d", tenant, round)
                            .isInstanceOf(NoSuchFileException.class);
                    assertThatThrownBy(() -> store.getForCaller(tenant, seedB.tenant(), shaB))
                            .isInstanceOf(NoSuchFileException.class);
                    assertThatThrownBy(() -> store.getForCaller(tenant, tenant, shaB))
                            .isInstanceOf(NoSuchFileException.class);
                    assertThatThrownBy(() -> presigned.fetch(tenant, tokenB))
                            .as("foreign token as %s, round %d", tenant, round)
                            .isInstanceOfSatisfying(ResponseStatusException.class,
                                    e -> assertThat(e.getStatusCode().value()).isEqualTo(HttpStatus.FORBIDDEN.value()));
                    // Release finds no row for this tenant: 0 rows.
                    assertThat(store.release(tenant, shaB)).as("foreign release, round %d", round).isFalse();
                }
            }
        }
        assertThat(refcountOf(seedB.tenant(), shaB)).as("tenant B refcount untouched").isEqualTo(refcountB);
        assertPooledLinkClean();
    }

    private Seed otherOf(Seed caller) {
        return caller.tenant().equals(seedA.tenant()) ? seedB : seedA;
    }

    private UUID insertUnsentOutbox(Seed seed) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, sha256, payload) "
                        + "VALUES (?, ?, ?, ?, ?, 'job.committed', ?, '{}'::jsonb)",
                id, seed.tenant(), seed.jobId(), seed.docId(), seed.versionId(), seed.sha());
        return id;
    }

    private int unsentOutbox(String tenant) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE tenant_id = ? AND sent_at IS NULL", Integer.class, tenant);
    }

    private int refcountOf(String tenant, String sha) {
        return jdbc.queryForObject(
                "SELECT refcount FROM artifacts WHERE tenant_id = ? AND sha256 = ?", Integer.class, tenant, sha);
    }

    private String tenantOfJob(UUID jobId) {
        return jdbc.queryForObject("SELECT tenant_id FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String ownerOfJob(UUID jobId) {
        return jdbc.queryForObject("SELECT owner FROM jobs WHERE id = ?", String.class, jobId);
    }

    private String stateOfJob(UUID jobId) {
        return jdbc.queryForObject("SELECT state FROM jobs WHERE id = ?", String.class, jobId);
    }

    private long fencingOfJob(UUID jobId) {
        return jdbc.queryForObject("SELECT fencing_token FROM jobs WHERE id = ?", Long.class, jobId);
    }

    private boolean revokedOf(UUID jobId) {
        return jdbc.queryForObject("SELECT revoked FROM cap_grants WHERE job_id = ?", Boolean.class, jobId);
    }

    /** A fresh pooled checkout with no tenant context reads zero rows from every tenant table. */
    private void assertPooledLinkClean() {
        for (String table : TENANT_TABLES) {
            Integer count = appJdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count).as("pooled link clean on %s", table).isZero();
        }
    }
}
