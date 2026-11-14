package com.g1do.anvil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.invoice.InvoiceUploadService;
import com.g1do.anvil.upload.UploadLimits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * #170: a multipart invoice PDF is validated by content, stored in the content
 * store and recorded as a document/version with no job. Invalid input writes
 * no rows, and the contract states the route and its errors.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceUploadTest {

    private static final String TENANT = "t_single";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ContentStore contentStore;

    @Autowired
    PlatformTransactionManager txManager;

    @TempDir
    Path tempDir;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE audit_events");
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

    /** A minimal PDF by structure: magic header at offset 0 and the EOF marker last. */
    private static byte[] pdf(String body) {
        return ("%PDF-1.7\n" + body + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
    }

    private MvcResult upload(String filename, String title, byte[] bytes, String partContentType)
            throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", filename, partContentType, bytes);
        var request = multipart("/api/invoices")
                .file(file)
                .header("X-Tenant-Id", TENANT);
        if (title != null) {
            request.param("title", title);
        }
        return mvc.perform(request).andReturn();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertNoRows(String scenario) {
        assertThat(count("documents")).as("documents after %s", scenario).isZero();
        assertThat(count("document_versions")).as("versions after %s", scenario).isZero();
        assertThat(count("artifacts")).as("artifacts after %s", scenario).isZero();
        assertThat(count("jobs")).as("jobs after %s", scenario).isZero();
        assertThat(count("outbox")).as("outbox after %s", scenario).isZero();
    }

    @Test
    void aMultipartPdfIsStoredWithDocumentAndVersionMetadataAndNoJob() throws Exception {
        byte[] bytes = pdf("1 0 obj << /Type /Catalog >> endobj");

        MvcResult result = upload("invoice.pdf", null, bytes, "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(202);

        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        UUID docId = UUID.fromString(body.get("doc_id").asText());
        UUID versionId = UUID.fromString(body.get("version_id").asText());
        assertThat(body.has("job_id")).isFalse();

        Map<String, Object> document = jdbc.queryForMap(
                "SELECT tenant_id, title, mime_type FROM documents WHERE id = ?", docId);
        assertThat(document.get("tenant_id")).isEqualTo(TENANT);
        assertThat(document.get("title")).isEqualTo("invoice.pdf");
        assertThat(document.get("mime_type")).isEqualTo("application/pdf");

        String sha = ContentStore.sha256Hex(bytes);
        Map<String, Object> version = jdbc.queryForMap(
                "SELECT tenant_id, document_id, version_number, sha256, canonical_cbor "
                        + "FROM document_versions WHERE id = ?",
                versionId);
        assertThat(version.get("tenant_id")).isEqualTo(TENANT);
        assertThat(version.get("document_id")).isEqualTo(docId);
        assertThat(version.get("version_number")).isEqualTo(1);
        assertThat(version.get("sha256")).isEqualTo(sha);
        assertThat(version.get("canonical_cbor")).isNull();

        // The bytes are in the content store at their address, pinned against GC.
        assertThat(contentStore.get(TENANT, sha)).isEqualTo(bytes);
        assertThat(contentStore.pathFor(TENANT, sha).getFileName().toString()).isEqualTo(sha);
        assertThat(contentStore.pathFor(TENANT, sha).toString()).doesNotContain("invoice.pdf");
        Map<String, Object> artifact = jdbc.queryForMap(
                "SELECT size_bytes, refcount FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                TENANT, sha);
        assertThat(artifact.get("size_bytes")).isEqualTo((long) bytes.length);
        assertThat(artifact.get("refcount")).isEqualTo(1);

        // This route stores; it creates no job, outbox event or audit event.
        assertThat(count("jobs")).isZero();
        assertThat(count("outbox")).isZero();
        assertThat(count("audit_events")).isZero();
    }

    @Test
    void anExplicitTitleIsStored() throws Exception {
        MvcResult result = upload("ignored.pdf", "Q1 invoice", pdf("body"), "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(202);

        UUID docId = UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("doc_id").asText());
        assertThat(jdbc.queryForObject("SELECT title FROM documents WHERE id = ?", String.class, docId))
                .isEqualTo("Q1 invoice");
    }

    @Test
    void theFilenameIsReducedToASafeStoredTitle() throws Exception {
        MvcResult result = upload("../../evil\u0000\u0007.pdf", null, pdf("body"), "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(202);

        UUID docId = UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .get("doc_id").asText());
        assertThat(jdbc.queryForObject("SELECT title FROM documents WHERE id = ?", String.class, docId))
                .isEqualTo("evil.pdf");
    }

    @Test
    void aNonPdfIs415AndWritesNoRows() throws Exception {
        MvcResult result = upload("invoice.pdf", null,
                "this is not a pdf".getBytes(StandardCharsets.UTF_8), "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(415);
        assertNoRows("non-PDF content");
    }

    @Test
    void anEmptyFileIs415AndWritesNoRows() throws Exception {
        MvcResult result = upload("invoice.pdf", null, new byte[0], "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(415);
        assertNoRows("empty file");
    }

    @Test
    void aPdfWithoutAnEndOfFileMarkerIs400AndWritesNoRows() throws Exception {
        MvcResult result = upload("invoice.pdf", null,
                "%PDF-1.7\ntruncated".getBytes(StandardCharsets.US_ASCII), "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertNoRows("truncated PDF");
    }

    @Test
    void aMissingFilePartIs400AndWritesNoRows() throws Exception {
        MvcResult result = mvc.perform(multipart("/api/invoices")
                        .param("title", "no file")
                        .header("X-Tenant-Id", TENANT))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertNoRows("missing file part");
    }

    @Test
    void aNonMultipartRequestIs415AndWritesNoRows() throws Exception {
        MvcResult result = mvc.perform(post("/api/invoices")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(415);
        assertNoRows("non-multipart request");
    }

    @Test
    void aBlankTitleIs400AndWritesNoRows() throws Exception {
        MvcResult result = upload("invoice.pdf", "  ", pdf("body"), "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertNoRows("blank title");
    }

    @Test
    void aTitleOverTheLimitIs400AndWritesNoRows() throws Exception {
        MvcResult result = upload("invoice.pdf", "x".repeat(4097), pdf("body"), "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertNoRows("long title");
    }

    @Test
    void aFileOverTheLimitIs413AndWritesNoRows() throws Exception {
        byte[] bytes = new byte[(int) UploadLimits.MAX_BYTES + 1];
        bytes[0] = '%';
        bytes[1] = 'P';
        bytes[2] = 'D';
        bytes[3] = 'F';
        bytes[4] = '-';
        byte[] eof = "%%EOF".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(eof, 0, bytes, bytes.length - eof.length, eof.length);

        MvcResult result = upload("invoice.pdf", null, bytes, "application/pdf");
        assertThat(result.getResponse().getStatus()).isEqualTo(413);
        assertNoRows("oversize file");
    }

    @Test
    void aStorageFailureReturnsAnErrorAndWritesNoMetadata() throws Exception {
        // A regular file where the content root's tenant directory should be makes
        // the durable write fail before any metadata transaction starts.
        Path fileRoot = Files.createTempFile(tempDir, "not-a-directory", ".root");
        ContentStore failingStore = new ContentStore(jdbc, txManager, fileRoot.toString());
        InvoiceUploadService failingUploads = new InvoiceUploadService(jdbc, txManager, failingStore);

        assertThat(catchThrowable(() -> failingUploads.upload(
                        TENANT, new MockMultipartFile("file", "invoice.pdf", "application/pdf", pdf("body")), null)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(500));
        assertNoRows("content store failure");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theContractDocumentsTheRouteAndItsErrors() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(Path.of("contracts/openapi.yaml")));
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) paths.get("/api/invoices")).get("post");
        assertThat(post).isNotNull();

        Map<String, Object> responses = (Map<String, Object>) post.get("responses");
        assertThat(responses).containsKeys("202", "400", "413", "415");

        Map<String, Object> requestBody = (Map<String, Object>) post.get("requestBody");
        assertThat(requestBody).isNotNull();
        assertThat(String.valueOf(requestBody.get("content"))).contains("multipart/form-data");

        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components"))
                .get("schemas");
        String response = String.valueOf(responses.get("202"));
        assertThat(response).contains("InvoiceUploadResponse");
        Map<String, Object> uploadResponse = (Map<String, Object>) schemas.get("InvoiceUploadResponse");
        assertThat((java.util.List<String>) uploadResponse.get("required"))
                .containsExactlyInAnyOrder("doc_id", "version_id");
    }
}
