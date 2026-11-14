package com.g1do.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #145/#170: the same 10 MiB limit applies to a multipart invoice PDF, and the
 * real route accepts a PDF below it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceMultipartLimitTest {

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    /** The 50-page 300 dpi scan measured in record 0029; multipart framing still fits. */
    private static final int EVIDENCE_PDF_BYTES = 9_188_257;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

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
    }

    @Test
    void aPdfBelowTheLimitIsAccepted() throws Exception {
        HttpResponse<String> response = post(EVIDENCE_PDF_BYTES, false);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"doc_id\"", "\"version_id\"");
        assertThat(count("documents")).isEqualTo(1);
    }

    @Test
    void aDeclaredOversizePdfIs413BeforeMultipartParsing() throws Exception {
        HttpResponse<String> response = post(MAX_BYTES + 1, false);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("request body exceeds " + MAX_BYTES + " bytes");
        assertNoRows();
    }

    @Test
    void aChunkedOversizePdfIs413FromTheMultipartLimit() throws Exception {
        HttpResponse<String> response = post(MAX_BYTES + 1, true);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("request body exceeds " + MAX_BYTES + " bytes");
        assertNoRows();
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void assertNoRows() {
        assertThat(count("documents")).isZero();
        assertThat(count("document_versions")).isZero();
        assertThat(count("artifacts")).isZero();
    }

    private HttpResponse<String> post(int fileBytes, boolean chunked) throws Exception {
        String boundary = "anvil-" + UUID.randomUUID();
        byte[] multipart = multipartBody(boundary, pdfOfSize(fileBytes));
        HttpRequest.BodyPublisher publisher = chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(multipart))
                : HttpRequest.BodyPublishers.ofByteArray(multipart);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/invoices"))
                .header("X-Tenant-Id", "t_single")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(publisher)
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** A byte sequence of exactly {@code size} bytes that the upload route reads as a PDF. */
    private static byte[] pdfOfSize(int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) 'x');
        byte[] head = "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII);
        byte[] tail = "\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        if (size >= head.length + tail.length) {
            System.arraycopy(head, 0, bytes, 0, head.length);
            System.arraycopy(tail, 0, bytes, size - tail.length, tail.length);
        }
        return bytes;
    }

    private static byte[] multipartBody(String boundary, byte[] file) {
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"invoice.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream(head.length + file.length + tail.length);
        out.writeBytes(head);
        out.writeBytes(file);
        out.writeBytes(tail);
        return out.toByteArray();
    }
}
