package com.g1do.anvil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.htmlunit.WebClient;
import org.htmlunit.html.DomElement;
import org.htmlunit.html.HtmlInput;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.audit.AuditReadService;
import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.ClaimedJob;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.replay.ReplayService;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance check for issue #77: an operator inspects a job in a browser.
 *
 * <p>Runs the real console page in HtmlUnit against the embedded server and
 * containerized Postgres. The page fetches the same endpoints an operator
 * would:
 * <ul>
 * <li>{@code GET /api/jobs/{id}} for the job's current state;</li>
 * <li>{@code GET /audit/events?job_id=} for the audit chain;</li>
 * <li>{@code GET /audit/replay?job_id=} for the replay result.</li>
 * </ul>
 *
 * <p>The cross-tenant case checks both the browser and the new job route: a
 * missing job and another tenant's job produce the same generic 404/body and
 * the same console text, with no chain rows, hashes or state from the other
 * tenant.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperatorConsoleTest {

    private static final String A = "t_console_a";
    private static final String B = "t_console_b";

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SubmitService submits;

    @Autowired
    JobService jobs;

    @Autowired
    AuditReadService auditReads;

    @Autowired
    ReplayService replays;

    @Autowired
    PlatformTransactionManager txManager;

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

    /** Leaves no rows behind: job_checkpoints cannot be deleted, so later classes would fail on them. */
    @AfterEach
    void leaveNoRows() {
        clean();
    }

    @Test
    void operatorSeesJobStateAuditChainAndReplayInTheBrowser() throws Exception {
        UUID job = succeeded(A, "console");
        ReplayService.ReplayResult expectedReplay = replays.replay(A, job);
        List<AuditReadService.AuditEventRow> expectedEvents = auditReads.listEvents(A, job, 100, null);

        try (WebClient browser = browser()) {
            HtmlPage page = inspect(browser, A, job.toString());

            assertThat(page.getElementById("result").hasAttribute("hidden")).isFalse();
            assertThat(page.getElementById("not-found").hasAttribute("hidden")).isTrue();
            assertThat(page.getElementById("job-state").getTextContent().trim()).isEqualTo("SUCCEEDED");
            assertThat(page.getElementById("job-id-value").getTextContent().trim()).isEqualTo(job.toString());
            assertThat(page.getElementById("tenant-value").getTextContent().trim()).isEqualTo(A);

            assertThat(page.getElementById("replay-match").getTextContent().trim()).isEqualTo("true");
            assertThat(page.getElementById("replay-input-sha").getTextContent().trim())
                    .isEqualTo(expectedReplay.inputSha());
            assertThat(page.getElementById("replay-output-sha").getTextContent().trim())
                    .isEqualTo(expectedReplay.outputSha());

            List<DomElement> rows = auditRows(page);
            assertThat(rows).hasSize(expectedEvents.size());
            for (int i = 0; i < rows.size(); i++) {
                List<DomElement> cells = rows.get(i).getByXPath("./td");
                assertThat(cells.get(0).getTextContent().trim()).isEqualTo(Long.toString(expectedEvents.get(i).seq()));
                assertThat(cells.get(1).getTextContent().trim()).isEqualTo(expectedEvents.get(i).type());
                assertThat(cells.get(2).getTextContent().trim()).isEqualTo(expectedEvents.get(i).eventHash());
                assertThat(cells.get(3).getTextContent().trim()).isEqualTo(expectedEvents.get(i).prevHash());
            }
        }
    }

    @Test
    void anotherTenantsJobLooksExactlyLikeAMissingJob() throws Exception {
        UUID theirs = succeeded(B, "theirs");
        UUID missing = UUID.randomUUID();
        List<AuditReadService.AuditEventRow> theirEvents = auditReads.listEvents(B, theirs, 100, null);

        try (WebClient browser = browser()) {
            HtmlPage foreign = inspect(browser, A, theirs.toString());
            HtmlPage absent = inspect(browser, A, missing.toString());

            assertThat(foreign.getElementById("result").hasAttribute("hidden"))
                    .as("a cross-tenant job renders no result").isTrue();
            assertThat(foreign.getElementById("not-found").hasAttribute("hidden"))
                    .as("a cross-tenant job takes the generic not-found path").isFalse();
            assertThat(foreign.getElementById("not-found").asNormalizedText())
                    .isEqualTo(absent.getElementById("not-found").asNormalizedText());
            assertThat(foreign.getElementById("status").getTextContent())
                    .isEqualTo(absent.getElementById("status").getTextContent());
            assertThat(auditRows(foreign)).isEmpty();
            for (AuditReadService.AuditEventRow event : theirEvents) {
                assertThat(foreign.asNormalizedText())
                        .as("no event hash of the other tenant's chain")
                        .doesNotContain(event.eventHash());
            }

            // Control: the owner sees the same job, state and chain.
            HtmlPage owner = inspect(browser, B, theirs.toString());
            assertThat(owner.getElementById("job-state").getTextContent().trim()).isEqualTo("SUCCEEDED");
            assertThat(auditRows(owner)).hasSize(theirEvents.size());
        }

        // The new job route has the same generic 404 body for missing, malformed and cross-tenant IDs.
        HttpResponse<String> cross = get("/api/jobs/" + theirs, A);
        HttpResponse<String> absent = get("/api/jobs/" + missing, A);
        HttpResponse<String> malformed = get("/api/jobs/not-a-uuid", A);
        assertThat(cross.statusCode()).isEqualTo(404);
        assertThat(absent.statusCode()).isEqualTo(404);
        assertThat(malformed.statusCode()).isEqualTo(404);
        assertThat(cross.body()).isEqualTo(absent.body()).isEqualTo(malformed.body());
        assertThat(json.readTree(cross.body()).get("error").asText()).isEqualTo("not found");
    }

    @Test
    void anUnknownTenantIsRefusedAsTheApiRefusesIt() throws Exception {
        UUID job = succeeded(A, "unknown");
        try (WebClient browser = browser()) {
            HtmlPage page = inspect(browser, "t_console_nobody", job.toString());
            assertThat(page.getElementById("result").hasAttribute("hidden")).isTrue();
            assertThat(page.getElementById("not-found").hasAttribute("hidden")).isTrue();
            assertThat(page.getElementById("status").getTextContent().trim()).isEqualTo("Tenant not found.");
        }
        assertThat(get("/api/jobs/" + job, "t_console_nobody").statusCode()).isEqualTo(401);
    }

    /** The page's assets are relative, so it only works from the path with the slash. */
    @Test
    void theConsoleWithoutASlashRedirectsToThePage() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/console")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/console/");
    }

    // ---- browser helpers ----

    private WebClient browser() {
        WebClient browser = new WebClient();
        browser.getOptions().setJavaScriptEnabled(true);
        browser.getOptions().setCssEnabled(false);
        browser.getOptions().setThrowExceptionOnScriptError(true);
        browser.getOptions().setThrowExceptionOnFailingStatusCode(false);
        return browser;
    }

    private HtmlPage inspect(WebClient browser, String tenant, String jobId) throws Exception {
        HtmlPage page = browser.getPage("http://localhost:" + port + "/console/");
        ((HtmlInput) page.getElementById("tenant")).setValueAttribute(tenant);
        ((HtmlInput) page.getElementById("job-id")).setValueAttribute(jobId);
        page.getElementById("inspect").click();
        for (int i = 0; i < 100 && !settled(page); i++) {
            browser.waitForBackgroundJavaScript(100);
        }
        assertThat(settled(page))
                .as("console finished rendering for tenant %s job %s", tenant, jobId)
                .isTrue();
        return page;
    }

    private static boolean settled(HtmlPage page) {
        String status = page.getElementById("status").getTextContent().trim();
        if ("Inspecting...".equals(status)) {
            return false;
        }
        return !page.getElementById("result").hasAttribute("hidden")
                || !page.getElementById("not-found").hasAttribute("hidden")
                || !status.isEmpty();
    }

    private static List<DomElement> auditRows(HtmlPage page) {
        List<?> rows = page.getByXPath("//table[@id='audit-events']/tbody/tr");
        return rows.stream().map(DomElement.class::cast).toList();
    }

    private HttpResponse<String> get(String path, String tenant) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", tenant)
                .GET()
                .build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    // ---- jobs ----

    /** submit, claim, cap.issue, cas.put, commit: a SUCCEEDED job with an artifact. */
    private UUID succeeded(String tenant, String key) throws Exception {
        UUID job = submits.submit(tenant, "console-" + key + "-" + UUID.randomUUID(), "Console", "text/plain",
                "console-body-" + key).jobId();
        ClaimedJob claimed = jobs.claim(tenant, "console-w").orElseThrow();
        assertThat(claimed.jobId()).isEqualTo(job);
        CapGrant cap = jobs.issueCap(tenant, job);
        new ContentStore(jdbc, txManager, tmp.resolve("cas").toString())
                .putForJob(tenant, job, ("console-bytes-" + key).getBytes(StandardCharsets.UTF_8));
        assertThat(jobs.commitSucceeded(tenant, job, "console-w", claimed.fencingToken(),
                cap.capId(), cap.epoch())).isTrue();
        return job;
    }
}
