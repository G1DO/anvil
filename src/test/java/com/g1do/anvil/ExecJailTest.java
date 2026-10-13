package com.g1do.anvil;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.g1do.anvil.exec.EgressDeniedException;
import com.g1do.anvil.exec.ExecJail;
import com.g1do.anvil.exec.JailEgress;
import com.g1do.anvil.exec.JailResult;
import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Minimal jail proofs for issue #23.
 *
 * <p>Proves on containerized Postgres (tenant identity exists for workspace
 * scoping) plus real forked children: read-only-root at the API layer,
 * cleaned environment, default-deny egress, never-follow redirects,
 * cross-tenant file invisibility, and an allowed-tenant control that still
 * succeeds. No external network is used (redirect proof binds loopback only).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ExecJailTest {

    private static final String TENANT_A = "t_jail_a";
    private static final String TENANT_B = "t_jail_b";

    @Autowired
    JdbcTemplate jdbc;

    @TempDir
    Path tempDir;

    private ExecJail jail;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", TENANT_A);
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", TENANT_B);
        jail = new ExecJail(tempDir.toString());
    }

    private static void assumeUnixShell() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")),
                "requires /bin/sh");
    }

    @Test
    void roRootWriteDenied() throws Exception {
        // Absolute paths, parent traversal, encoded traversal all deny fail-closed.
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "/etc/passwd"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "/"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "../evil"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, ".."))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "a/../../evil"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "%2e%2e"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "a%2e.txt"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.writeWorkspaceFile(TENANT_A, "../evil", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.workspaceDir("../evil"))
                .isInstanceOf(IllegalArgumentException.class);

        // No escape: nothing was created outside the exec root.
        assertThat(tempDir.resolve("evil")).doesNotExist();
        assertThat(tempDir.resolve("etc")).doesNotExist();

        // Control: a workspace-relative write resolves inside and round-trips.
        jail.writeWorkspaceFile(TENANT_A, "ok/nested.txt", "ok".getBytes(StandardCharsets.UTF_8));
        Path resolved = jail.resolveInWorkspace(TENANT_A, "ok/nested.txt");
        assertThat(resolved.startsWith(jail.workspaceDir(TENANT_A))).isTrue();
        assertThat(Files.readAllBytes(resolved)).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void envClean() throws Exception {
        assumeUnixShell();

        // The jailed child sees exactly its tenant id, not the parent environment.
        JailResult tenantProbe = jail.run(TENANT_A,
                List.of("/bin/sh", "-c", "printenv ANVIL_TENANT"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(tenantProbe.exitCode()).isEqualTo(0);
        assertThat(tenantProbe.stdout().trim()).isEqualTo(TENANT_A);

        JailResult pathProbe = jail.run(TENANT_A,
                List.of("/bin/sh", "-c", "printenv PATH"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(pathProbe.exitCode()).isEqualTo(0);
        assertThat(pathProbe.stdout().trim()).isEqualTo(ExecJail.SAFE_PATH);

        // HOME is present in virtually every parent environment but must not leak.
        JailResult envProbe = jail.run(TENANT_A,
                List.of("/bin/sh", "-c", "env"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(envProbe.exitCode()).isEqualTo(0);
        assertThat(envProbe.stdout()).contains("ANVIL_TENANT=" + TENANT_A);
        assertThat(envProbe.stdout()).contains("PATH=" + ExecJail.SAFE_PATH);
        assertThat(envProbe.stdout()).doesNotContain("HOME=");
        String parentHome = System.getenv("HOME");
        if (parentHome != null && !parentHome.isBlank()) {
            assertThat(envProbe.stdout()).doesNotContain(parentHome);
        }

        // Explicit allowlist entries survive; forced entries cannot be overridden.
        JailResult allowProbe = jail.run(TENANT_A,
                List.of("/bin/sh", "-c", "printenv ANVIL_OK"),
                Map.of("ANVIL_OK", "yes"), Duration.ofSeconds(10));
        assertThat(allowProbe.stdout().trim()).isEqualTo("yes");

        JailResult forcedProbe = jail.run(TENANT_A,
                List.of("/bin/sh", "-c", "printenv ANVIL_TENANT"),
                Map.of("ANVIL_TENANT", "evil"), Duration.ofSeconds(10));
        assertThat(forcedProbe.stdout().trim()).isEqualTo(TENANT_A);
    }

    @Test
    void egressDenied() {
        // Metadata, link-local, loopback, and private targets deny without any network.
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(EgressDeniedException.class).hasMessageContaining("denied");
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://127.0.0.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://10.0.0.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://192.168.1.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://172.16.0.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://[::1]/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://localhost/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://metadata.google.internal/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("ftp://example.com/file"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("http://user@127.0.0.1/"))
                .isInstanceOf(EgressDeniedException.class);

        // Default-deny: even a public host denies with no allowlist, without network.
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://example.com/"))
                .isInstanceOf(EgressDeniedException.class)
                .hasMessageContaining("denied");

        // Blocked vs default-deny are distinct: public is not "blocked", just not allowed.
        assertThat(JailEgress.isBlockedTarget("http://169.254.169.254/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("http://127.0.0.1/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://example.com/")).isFalse();

        // Client contract: never follows redirects.
        assertThat(JailEgress.newClient().followRedirects())
                .isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void redirectNotFollowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/target", exchange -> {
                byte[] body = "target-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            int port = server.getAddress().getPort();

            // The untrusted path would deny loopback via checkAllowed before any
            // fetch (proven above); this step proves the client itself never
            // follows a redirect when it is used.
            HttpClient client = JailEgress.newClient();
            assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/redirect"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.body()).doesNotContain("target-ok");
            assertThat(response.headers().firstValue("Location")).isPresent();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crossTenantFileInvisible() throws Exception {
        assumeUnixShell();
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/ls")), "requires /bin/ls");
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/cat")), "requires /bin/cat");

        jail.writeWorkspaceFile(TENANT_A, "secret-a.txt",
                "alpha-secret".getBytes(StandardCharsets.UTF_8));
        jail.writeWorkspaceFile(TENANT_B, "secret-b.txt",
                "bravo-secret".getBytes(StandardCharsets.UTF_8));

        // Control: owner reads back through the authenticated gateway.
        assertThat(jail.readWorkspaceFile(TENANT_A, TENANT_A, "secret-a.txt"))
                .isEqualTo("alpha-secret".getBytes(StandardCharsets.UTF_8));

        // Cross-tenant denies without content and without an oracle.
        assertThatThrownBy(() -> jail.readWorkspaceFile(TENANT_A, TENANT_B, "secret-b.txt"))
                .isInstanceOf(NoSuchFileException.class)
                .satisfies(e -> {
                    assertThat(e.getMessage()).contains(TENANT_A);
                    assertThat(e.getMessage())
                            .doesNotContain("bravo-secret");
                });
        assertThatThrownBy(
                () -> jail.readWorkspaceFile(TENANT_A, TENANT_A, "../" + TENANT_B + "/secret-b.txt"))
                .isInstanceOf(SecurityException.class);

        // A forked child listing its own workspace sees only its own files.
        JailResult listing = jail.run(TENANT_A,
                List.of("/bin/ls", "-1"), Map.of(), Duration.ofSeconds(10));
        assertThat(listing.exitCode()).isEqualTo(0);
        assertThat(listing.stdout()).contains("secret-a.txt");
        assertThat(listing.stdout()).doesNotContain("secret-b.txt");
        assertThat(listing.stdout()).doesNotContain("bravo-secret");

        // A forked child reads its own file by workspace-relative path.
        JailResult cat = jail.run(TENANT_A,
                List.of("/bin/cat", "secret-a.txt"), Map.of(), Duration.ofSeconds(10));
        assertThat(cat.exitCode()).isEqualTo(0);
        assertThat(cat.stdout()).isEqualTo("alpha-secret");
    }

    @Test
    void controlExecutionPasses() throws Exception {
        assumeUnixShell();

        assertThat(jail.workspaceDir(TENANT_A)).isNotEqualTo(jail.workspaceDir(TENANT_B));

        jail.writeWorkspaceFile(TENANT_A, "input.txt",
                "hello".getBytes(StandardCharsets.UTF_8));
        JailResult result = jail.run(TENANT_A,
                List.of("/bin/sh", "-c", "cat input.txt > out.txt && cat out.txt"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("hello");
        assertThat(jail.readWorkspaceFile(TENANT_A, TENANT_A, "out.txt"))
                .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }
}
