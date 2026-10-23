package com.g1do.anvil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.outbox.IdempotentConsumer;
import com.g1do.anvil.outbox.OutboxEvent;
import com.g1do.anvil.outbox.OutboxRelay;
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

    private static final List<String> TENANT_TABLES = List.of(
            "documents", "document_versions", "jobs", "attempts", "artifacts", "outbox", "processed_events");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    private HikariDataSource appPool;
    private JdbcTemplate appJdbc;
    private TransactionTemplate appTx;

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

        seedTenantChain(TENANT_A, "a");
        seedTenantChain(TENANT_B, "b");

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
        jdbc.execute("TRUNCATE TABLE job_checkpoints, audit_events, cap_grants, processed_events, outbox, attempts, jobs, document_versions, documents, artifacts");
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

    private void seedTenantChain(String tenant, String seed) {
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                doc, tenant, "title-" + seed, "text/plain");
        UUID ver = UUID.randomUUID();
        String sha = seed.repeat(64).substring(0, 64).replaceAll("[^0-9a-f]", "a");
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, ?, ?, 1, ?)",
                ver, tenant, doc, sha);
        UUID job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, "rls-key-" + seed + "-" + UUID.randomUUID());
        jdbc.update(
                "INSERT INTO attempts (tenant_id, job_id, attempt_number, owner, fencing_token, outcome) VALUES (?, ?, 0, ?, 0, 'CLAIMED')",
                tenant, job, "seeder-" + seed);
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
}
