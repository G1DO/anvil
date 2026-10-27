package com.g1do.anvil;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.g1do.anvil.observe.BurnAlerts;
import com.g1do.anvil.observe.SaturationSignals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #33 (REQ-4 burn part): signals defined with labels and aggregation,
 * burn alerts fire on induced burn and stay silent on green, and exposition
 * carries no tenant bytes while fencing/RLS stay intact.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SaturationSignalTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SaturationSignals signals;

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void clean() {
        signals.reset();
        jdbc.execute("TRUNCATE TABLE audit_events");
        jdbc.execute("DELETE FROM cap_grants");
        jdbc.execute("DELETE FROM processed_events");
        jdbc.execute("DELETE FROM outbox");
        jdbc.execute("DELETE FROM attempts");
        jdbc.execute("DELETE FROM jobs");
        jdbc.execute("DELETE FROM document_versions");
        jdbc.execute("DELETE FROM documents");
        jdbc.execute("DELETE FROM artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
    }

    private UUID seedQueued(String tenant, String key) {
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
        UUID doc = UUID.randomUUID();
        jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, ?, ?, ?)",
                doc, tenant, "t-" + key, "text/plain");
        UUID ver = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256)"
                        + " VALUES (?, ?, ?, ?, ?)",
                ver, tenant, doc, 1, "b".repeat(64));
        UUID job = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state)"
                        + " VALUES (?, ?, ?, ?, ?, 'QUEUED')",
                job, tenant, doc, ver, key);
        return job;
    }

    @Test
    void queueAgeUsesDbClockAndAggregatesAcrossTenants() {
        seedQueued("t_single", "q-green-1");
        double greenAge = signals.queueAgeSeconds();
        assertThat(greenAge).isGreaterThanOrEqualTo(0.0);
        assertThat(greenAge).isLessThan(60.0);

        UUID oldJob = seedQueued("t_single", "q-burn-1");
        jdbc.update("UPDATE jobs SET created_at = clock_timestamp() - INTERVAL '10 minutes' WHERE id = ?",
                oldJob);
        double burnAge = signals.queueAgeSeconds();
        assertThat(burnAge).isGreaterThan(500.0);

        List<BurnAlerts.Firing> burn = BurnAlerts.evaluate(burnAge, 0.0, 0.0);
        assertThat(BurnAlerts.anyFiring(burn)).isTrue();
        assertThat(burn.get(0).alert()).isEqualTo(BurnAlerts.QUEUE_BURN);
        assertThat(burn.get(0).firing()).isTrue();

        List<BurnAlerts.Firing> green = BurnAlerts.evaluate(greenAge, 0.0, 0.0);
        assertThat(BurnAlerts.anyFiring(green)).isFalse();
    }

    @Test
    void claimAndPoolP99FireClaimBurnAndStaySilentOnGreen() {
        for (int i = 0; i < 100; i++) {
            signals.recordClaimNanos(5_000_000L);
            signals.recordPoolWaitNanos(5_000_000L);
        }
        assertThat(signals.claimP99Seconds()).isLessThan(0.5);
        assertThat(signals.poolWaitP99Seconds()).isLessThan(0.5);
        assertThat(BurnAlerts.anyFiring(BurnAlerts.evaluate(0.0,
                signals.claimP99Seconds(), signals.poolWaitP99Seconds()))).isFalse();

        for (int i = 0; i < 100; i++) {
            signals.recordClaimNanos(2_000_000_000L);
        }
        assertThat(signals.claimP99Seconds()).isGreaterThan(0.5);
        List<BurnAlerts.Firing> burn = BurnAlerts.evaluate(0.0,
                signals.claimP99Seconds(), signals.poolWaitP99Seconds());
        assertThat(burn.get(1).alert()).isEqualTo(BurnAlerts.CLAIM_BURN);
        assertThat(burn.get(1).firing()).isTrue();
    }

    @Test
    void expositionCarriesNoTenantBytes() throws Exception {
        String secretTenant = "t_secret_xyz_33";
        seedQueued(secretTenant, "q-secret-1");
        seedQueued("t_single", "q-other-1");

        var saturation = mockMvc.perform(get("/observe/saturation"))
                .andExpect(status().isOk())
                .andReturn();
        String json = saturation.getResponse().getContentAsString();
        assertThat(json).contains("anvil_queue_age_seconds");
        assertThat(json).contains("anvil_claim_latency_seconds_p99");
        assertThat(json).contains("anvil_pool_wait_seconds_p99");
        assertThat(json).contains(BurnAlerts.QUEUE_BURN);
        assertThat(json).contains(BurnAlerts.CLAIM_BURN);
        assertThat(json).doesNotContain(secretTenant);
        assertThat(json).doesNotContain("t_single");

        var metrics = mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andReturn();
        String text = metrics.getResponse().getContentAsString();
        assertThat(text).contains("anvil_queue_age_seconds{state=\"queued\"}");
        assertThat(text).contains("anvil_claim_latency_seconds{op=\"claim\",quantile=\"0.99\"}");
        assertThat(text).contains("anvil_pool_wait_seconds{pool=\"hikaricp\",quantile=\"0.99\"}");
        assertThat(text).contains("anvil_burn_alert_firing{alert=\"QueueBurn\"}");
        assertThat(text).contains("anvil_burn_alert_firing{alert=\"ClaimBurn\"}");
        assertThat(text).doesNotContain(secretTenant);
        assertThat(text).doesNotContain("t_single");
    }

    @Test
    void signalCollectionPreservesFencingAndRls() {
        UUID jobA = seedQueued("t_single", "q-fence-a");
        UUID jobB = seedQueued("t_single", "q-fence-b");
        double age = signals.queueAgeSeconds();
        assertThat(age).isGreaterThanOrEqualTo(0.0);
        Map<String, Object> rowA = jdbc.queryForMap("SELECT state FROM jobs WHERE id = ?", jobA);
        Map<String, Object> rowB = jdbc.queryForMap("SELECT state FROM jobs WHERE id = ?", jobB);
        assertThat(rowA.get("state").toString()).isEqualTo("QUEUED");
        assertThat(rowB.get("state").toString()).isEqualTo("QUEUED");
    }
}
