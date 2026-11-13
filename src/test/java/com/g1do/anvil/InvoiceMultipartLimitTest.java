package com.g1do.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #145: the same 10 MiB limit applies to a multipart invoice PDF. The probe
 * route stands in for #170; it exists only in this test until that route lands.
 */
@Import({ TestcontainersConfiguration.class, InvoiceMultipartLimitTest.InvoiceProbe.class })
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvoiceMultipartLimitTest {

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    /** The 50-page 300 dpi scan measured in record 0029; multipart framing still fits. */
    private static final int EVIDENCE_PDF_BYTES = 9_188_257;

    @LocalServerPort
    int port;

    @RestController
    static class InvoiceProbe {

        @PostMapping("/api/invoices")
        ResponseEntity<Map<String, String>> upload(@RequestParam("file") MultipartFile file) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("bytes", Long.toString(file.getSize())));
        }
    }

    @Test
    void aPdfBelowTheLimitIsAccepted() throws Exception {
        HttpResponse<String> response = post(EVIDENCE_PDF_BYTES, false);

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("\"bytes\":\"" + EVIDENCE_PDF_BYTES + "\"");
    }

    @Test
    void aDeclaredOversizePdfIs413BeforeMultipartParsing() throws Exception {
        HttpResponse<String> response = post(MAX_BYTES + 1, false);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("request body exceeds " + MAX_BYTES + " bytes");
    }

    @Test
    void aChunkedOversizePdfIs413FromTheMultipartLimit() throws Exception {
        HttpResponse<String> response = post(MAX_BYTES + 1, true);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("request body exceeds " + MAX_BYTES + " bytes");
    }

    private HttpResponse<String> post(int fileBytes, boolean chunked) throws Exception {
        String boundary = "anvil-" + UUID.randomUUID();
        byte[] multipart = multipartBody(boundary, new byte[fileBytes]);
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
