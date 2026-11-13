package com.g1do.anvil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.yaml.snakeyaml.Yaml;

import com.g1do.anvil.upload.UploadLimits;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #145: the chosen upload limit is enforced before the body is parsed, the
 * rejection writes no document or version row, and the contract states it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestBodyLimitTest {

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    private static String submitJson(int bodyBytes) {
        return "{\"title\":\"limit\",\"mime_type\":\"text/plain\",\"body\":\""
                + "x".repeat(bodyBytes) + "\"}";
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** A request that declares an oversize body; reading it would fail the test. */
    private static RequestBuilder declaredOversizeSubmit(String key) {
        return new RequestBuilder() {
            @Override
            public MockHttpServletRequest buildRequest(ServletContext context) {
                MockHttpServletRequest request = new MockHttpServletRequest(context, "POST", "/api/documents") {
                    @Override
                    public int getContentLength() {
                        return (int) UploadLimits.MAX_BYTES + 1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return UploadLimits.MAX_BYTES + 1;
                    }

                    @Override
                    public ServletInputStream getInputStream() {
                        return new ServletInputStream() {
                            @Override
                            public int read() {
                                throw new AssertionError("body was read");
                            }

                            @Override
                            public int read(byte[] bytes, int offset, int length) {
                                throw new AssertionError("body was read");
                            }

                            @Override
                            public boolean isFinished() {
                                return false;
                            }

                            @Override
                            public boolean isReady() {
                                return true;
                            }

                            @Override
                            public void setReadListener(ReadListener readListener) {
                            }
                        };
                    }
                };
                request.addHeader("X-Tenant-Id", "t_single");
                request.addHeader("Idempotency-Key", key);
                request.setContentType(MediaType.APPLICATION_JSON_VALUE);
                return request;
            }
        };
    }

    /** A request whose body has no declared length, as a chunked upload does. */
    private static RequestBuilder chunkedSubmit(String key, String json) {
        return new RequestBuilder() {
            @Override
            public MockHttpServletRequest buildRequest(ServletContext context) {
                MockHttpServletRequest request = new MockHttpServletRequest(context, "POST", "/api/documents") {
                    @Override
                    public int getContentLength() {
                        return -1;
                    }

                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
                request.addHeader("X-Tenant-Id", "t_single");
                request.addHeader("Idempotency-Key", key);
                request.setContentType(MediaType.APPLICATION_JSON_VALUE);
                request.setContent(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return request;
            }
        };
    }

    @Test
    void oversizeJsonIs413BeforeParsingAndWritesNoDocumentOrVersion() throws Exception {
        int documentsBefore = count("documents");
        int versionsBefore = count("document_versions");

        // A valid request over the limit: without the limit this would be 202 and write rows.
        mvc.perform(post("/api/documents")
                        .header("X-Tenant-Id", "t_single")
                        .header("Idempotency-Key", "oversize-valid-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitJson(MAX_BYTES)))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.error").exists());

        // Invalid JSON over the limit: parsing it first would return 400, not 413.
        mvc.perform(post("/api/documents")
                        .header("X-Tenant-Id", "t_single")
                        .header("Idempotency-Key", "oversize-invalid-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{".repeat(MAX_BYTES + 1)))
                .andExpect(status().is(413));

        assertThat(count("documents")).isEqualTo(documentsBefore);
        assertThat(count("document_versions")).isEqualTo(versionsBefore);
    }

    @Test
    void declaredOversizeIs413WithoutReadingTheBody() throws Exception {
        mvc.perform(declaredOversizeSubmit("oversize-declared-" + UUID.randomUUID()))
                .andExpect(status().is(413));
    }

    @Test
    void chunkedOversizeIs413WithoutTrustingContentLength() throws Exception {
        int documentsBefore = count("documents");
        int versionsBefore = count("document_versions");

        // No Content-Length: the limit must hold while the body is streamed.
        mvc.perform(chunkedSubmit("oversize-chunked-" + UUID.randomUUID(), submitJson(MAX_BYTES)))
                .andExpect(status().is(413));

        assertThat(count("documents")).isEqualTo(documentsBefore);
        assertThat(count("document_versions")).isEqualTo(versionsBefore);
    }

    @Test
    void invoicePathUsesTheSameLimitBeforeItsRouteLands() throws Exception {
        // The invoice upload route is #170; its body limit is already on the path.
        mvc.perform(post("/api/invoices")
                        .header("X-Tenant-Id", "t_single")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .content(submitJson(MAX_BYTES)))
                .andExpect(status().is(413));
    }

    @Test
    void codeLimitMatchesTheContract() {
        assertThat(UploadLimits.MAX_BYTES).isEqualTo(MAX_BYTES);
        assertThat(UploadLimits.MAX_PAGES).isEqualTo(50);
    }

    @Test
    @SuppressWarnings("unchecked")
    void contractStatesTheByteLimitPageLimitAnd413() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(Path.of("contracts/openapi.yaml")));

        String info = String.valueOf(((Map<String, Object>) spec.get("info")).get("description"));
        assertThat(info).contains("10 MiB", "10,485,760", "50 pages");

        Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>)
                ((Map<String, Object>) spec.get("paths")).get("/api/documents")).get("post");
        Map<String, Object> responses = (Map<String, Object>) post.get("responses");
        assertThat(responses).containsKey("413");

        String requestBody = String.valueOf(post.get("requestBody"));
        assertThat(requestBody).contains("10 MiB");
    }
}
