package com.g1do.anvil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.g1do.anvil.submit.CanonicalCbor;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubmitIdempotencyTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmitService submitService;

    private final ObjectMapper jsonMapper = new ObjectMapper();

    @BeforeEach
    void clean() {
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

    private String submitJson(String title, String mime, String body) throws Exception {
        return jsonMapper.writeValueAsString(Map.of("title", title, "mime_type", mime, "body", body));
    }

    private JsonNode postSubmit(String tenant, String key, String json) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/documents")
                        .header("X-Tenant-Id", tenant == null ? "" : tenant)
                        .header("Idempotency-Key", key == null ? "" : key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.doc_id").exists())
                .andExpect(jsonPath("$.version_id").exists())
                .andExpect(jsonPath("$.job_id").exists())
                .andReturn();
        // When tenant/key are empty strings the filter still rejects; this helper is
        // only used for valid-auth cases.
        return jsonMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void dupePostSameKeyTwiceReturnsIdenticalIdsWithOneJobRow() throws Exception {
        String key = "dupe-" + UUID.randomUUID();
        String payload = submitJson("Dupe Doc", "text/plain", "hello-dupe");

        JsonNode first = postSubmit("t_single", key, payload);
        JsonNode second = postSubmit("t_single", key, payload);

        assertThat(second.get("doc_id").asText()).isEqualTo(first.get("doc_id").asText());
        assertThat(second.get("version_id").asText()).isEqualTo(first.get("version_id").asText());
        assertThat(second.get("job_id").asText()).isEqualTo(first.get("job_id").asText());

        Integer jobs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE tenant_id = 't_single' AND idempotency_key = ?",
                Integer.class, key);
        assertThat(jobs).isEqualTo(1);

        UUID jobId = UUID.fromString(first.get("job_id").asText());
        UUID docId = UUID.fromString(first.get("doc_id").asText());
        UUID versionId = UUID.fromString(first.get("version_id").asText());

        // All four rows exist and are linked.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_versions WHERE id = ?", Integer.class,
                versionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE job_id = ?", Integer.class, jobId))
                .isEqualTo(1);
    }

    @Test
    void differentKeysCreateDistinctJobs() throws Exception {
        String key1 = "key-a-" + UUID.randomUUID();
        String key2 = "key-b-" + UUID.randomUUID();

        JsonNode r1 = postSubmit("t_single", key1, submitJson("Doc A", "text/plain", "body-a"));
        JsonNode r2 = postSubmit("t_single", key2, submitJson("Doc A", "text/plain", "body-a"));

        assertThat(r2.get("job_id").asText()).isNotEqualTo(r1.get("job_id").asText());
        assertThat(r2.get("doc_id").asText()).isNotEqualTo(r1.get("doc_id").asText());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE tenant_id = 't_single' AND idempotency_key IN (?, ?)",
                Integer.class, key1, key2);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void invalidPayloadLeavesZeroRows() throws Exception {
        String key = "bad-" + UUID.randomUUID();
        int docsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class);
        int versionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM document_versions", Integer.class);
        int jobsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class);
        int outboxBefore = jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class);

        // Blank title is invalid.
        mockMvc.perform(post("/api/documents")
                        .header("X-Tenant-Id", "t_single")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("", "text/plain", "body")))
                .andExpect(status().isBadRequest());

        // Missing body is invalid.
        mockMvc.perform(post("/api/documents")
                        .header("X-Tenant-Id", "t_single")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"mime_type\":\"text/plain\"}"))
                .andExpect(status().isBadRequest());

        // Missing Idempotency-Key is invalid.
        mockMvc.perform(post("/api/documents")
                        .header("X-Tenant-Id", "t_single")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("T", "text/plain", "b")))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM documents", Integer.class)).isEqualTo(docsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document_versions", Integer.class))
                .isEqualTo(versionsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isEqualTo(jobsBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class)).isEqualTo(outboxBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE tenant_id = 't_single' AND idempotency_key = ?",
                Integer.class, key)).isEqualTo(0);
    }

    @Test
    void outboxRowExistsInSameCommittedTransactionAsJob() throws Exception {
        String key = "outbox-" + UUID.randomUUID();
        JsonNode res = postSubmit("t_single", key, submitJson("Outbox Doc", "text/plain", "outbox-body"));

        UUID jobId = UUID.fromString(res.get("job_id").asText());
        UUID docId = UUID.fromString(res.get("doc_id").asText());
        UUID versionId = UUID.fromString(res.get("version_id").asText());

        Map<String, Object> outbox = jdbc.queryForMap("SELECT * FROM outbox WHERE job_id = ?", jobId);
        assertThat(outbox.get("tenant_id")).isEqualTo("t_single");
        assertThat(outbox.get("doc_id")).isEqualTo(docId);
        assertThat(outbox.get("version_id")).isEqualTo(versionId);
        assertThat(outbox.get("type")).isEqualTo("job.committed");
        assertThat(outbox.get("sent_at")).isNull();

        String outboxSha = (String) outbox.get("sha256");
        String versionSha = jdbc.queryForObject("SELECT sha256 FROM document_versions WHERE id = ?",
                String.class, versionId);
        assertThat(outboxSha).isEqualTo(versionSha);
        assertThat(outboxSha).matches("^[0-9a-f]{64}$");

        // Canonical bytes stored and hash matches sha256(canonical_cbor).
        byte[] stored = (byte[]) jdbc.queryForObject("SELECT canonical_cbor FROM document_versions WHERE id = ?",
                byte[].class, versionId);
        assertThat(stored).isNotNull();
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        String recomputed = java.util.HexFormat.of().formatHex(md.digest(stored));
        assertThat(recomputed).isEqualTo(versionSha);
    }

    @Test
    void concurrentDuplicateSubmitRaceOneJobRowSameIds() throws Exception {
        String key = "race-" + UUID.randomUUID();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threads);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            List<Callable<SubmitService.SubmitResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return submitService.submit("t_single", key, "Race Doc", "text/plain", "race-body");
                });
            }
            List<Future<SubmitService.SubmitResult>> futures = new ArrayList<>();
            for (Callable<SubmitService.SubmitResult> t : tasks) {
                futures.add(pool.submit(t));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<SubmitService.SubmitResult> results = new ArrayList<>();
            for (Future<SubmitService.SubmitResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            SubmitService.SubmitResult first = results.get(0);
            for (SubmitService.SubmitResult r : results) {
                assertThat(r.docId()).isEqualTo(first.docId());
                assertThat(r.versionId()).isEqualTo(first.versionId());
                assertThat(r.jobId()).isEqualTo(first.jobId());
            }
            Integer jobs = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM jobs WHERE tenant_id = 't_single' AND idempotency_key = ?",
                    Integer.class, key);
            assertThat(jobs).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void canonicalCborIsDeterministicAndExcludesMetadata() throws Exception {
        CanonicalCbor.Encoded a = CanonicalCbor.encode("T", "text/plain", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        CanonicalCbor.Encoded b = CanonicalCbor.encode("T", "text/plain", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(a.sha256Hex()).isEqualTo(b.sha256Hex());
        assertThat(a.cbor()).isEqualTo(b.cbor());

        CanonicalCbor.Encoded different = CanonicalCbor.encode("T", "text/plain", "other".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(different.sha256Hex()).isNotEqualTo(a.sha256Hex());

        // Only participating fields are encoded; tenant/key/timestamps are excluded.
        ObjectMapper cborMapper = new ObjectMapper(new CBORFactory());
        JsonNode tree = cborMapper.readTree(a.cbor());
        assertThat(tree.size()).isEqualTo(3);
        assertThat(tree.has("title")).isTrue();
        assertThat(tree.has("mime_type")).isTrue();
        assertThat(tree.has("body")).isTrue();
    }

    @Test
    void missingOrUnknownTenantIsUnauthorizedWithNoRows() throws Exception {
        String key = "auth-" + UUID.randomUUID();
        int jobsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class);

        mockMvc.perform(post("/api/documents")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("T", "text/plain", "b")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/documents")
                        .header("X-Tenant-Id", "t_unknown")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson("T", "text/plain", "b")))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM jobs", Integer.class)).isEqualTo(jobsBefore);
    }
}
