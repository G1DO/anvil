package com.g1do.anvil;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;

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
 * Jail proofs for issues #23 and #73.
 *
 * <p>Proves on containerized Postgres (tenant identity exists for workspace
 * scoping) plus real forked children: read-only-root at the API layer,
 * cleaned environment, cross-tenant file invisibility, and an allowed-tenant
 * control that still succeeds. Brokered egress denies the SSRF matrix,
 * re-checks DNS at connect time, verifies TLS and never follows redirects,
 * and a denied request takes the same time whether the target exists or not.
 * No external network is used: remote hosts are stub resolvers and loopback
 * servers.
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
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/printenv"))
                || Files.isExecutable(Path.of("/bin/printenv")), "requires printenv");
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/env"))
                || Files.isExecutable(Path.of("/bin/env")), "requires env");

        String printenv = Files.isExecutable(Path.of("/usr/bin/printenv"))
                ? "/usr/bin/printenv" : "/bin/printenv";
        String envBin = Files.isExecutable(Path.of("/usr/bin/env"))
                ? "/usr/bin/env" : "/bin/env";

        // The jailed child sees exactly its tenant id, not the parent environment.
        JailResult tenantProbe = jail.run(TENANT_A,
                List.of(printenv, "ANVIL_TENANT"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(tenantProbe.exitCode()).isEqualTo(0);
        assertThat(tenantProbe.stdout().trim()).isEqualTo(TENANT_A);

        JailResult pathProbe = jail.run(TENANT_A,
                List.of(printenv, "PATH"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(pathProbe.exitCode()).isEqualTo(0);
        assertThat(pathProbe.stdout().trim()).isEqualTo(ExecJail.SAFE_PATH);

        // HOME is present in virtually every parent environment but must not leak.
        JailResult envProbe = jail.run(TENANT_A,
                List.of(envBin),
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
                List.of(printenv, "ANVIL_OK"),
                Map.of("ANVIL_OK", "yes"), Duration.ofSeconds(10));
        assertThat(allowProbe.stdout().trim()).isEqualTo("yes");

        JailResult forcedProbe = jail.run(TENANT_A,
                List.of(printenv, "ANVIL_TENANT"),
                Map.of("ANVIL_TENANT", "evil"), Duration.ofSeconds(10));
        assertThat(forcedProbe.stdout().trim()).isEqualTo(TENANT_A);
    }

    @Test
    void egressDenied() {
        // Metadata, link-local, loopback, and private targets deny without any network.
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://169.254.169.254/latest/meta-data/"))
                .isInstanceOf(EgressDeniedException.class).hasMessageContaining("denied");
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://127.0.0.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://10.0.0.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://192.168.1.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://172.16.0.1/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://[::1]/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://localhost/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://metadata.google.internal/"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("ftp://example.com/file"))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://user@127.0.0.1/"))
                .isInstanceOf(EgressDeniedException.class);

        // Default-deny: even a public host denies with no allowlist, without network.
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://example.com/"))
                .isInstanceOf(EgressDeniedException.class)
                .hasMessageContaining("denied");

        // Blocked vs default-deny are distinct: public is not "blocked", just not allowed.
        assertThat(JailEgress.isBlockedTarget("https://169.254.169.254/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://127.0.0.1/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://example.com/")).isFalse();

        // Connect-time path: a non-allowlisted host is denied before DNS or any connect.
        assertThatThrownBy(() -> JailEgress.open("https://example.com/", Set.of(),
                host -> {
                    throw new AssertionError("resolved a denied host");
                },
                (address, host, timeout) -> {
                    throw new AssertionError("connected to a denied host");
                }))
                .isInstanceOf(EgressDeniedException.class);
    }

    @Test
    void redirectNotFollowed() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger targetHits = new AtomicInteger();
        try {
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            server.createContext("/target", exchange -> {
                targetHits.incrementAndGet();
                byte[] body = "target-ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            JailEgress.Connector toServer = toLocal(server);

            // The broker never follows a redirect: a 3xx response is a denial.
            assertThatThrownBy(() -> JailEgress.fetch("https://public.test/redirect",
                    Set.of("public.test"), publicResolver(), toServer))
                    .isInstanceOf(EgressDeniedException.class)
                    .hasMessageContaining("redirect");
            assertThat(targetHits.get()).isZero();

            // Control: the same server's 200 comes back through the broker.
            JailEgress.Response ok = JailEgress.fetch("https://public.test/target",
                    Set.of("public.test"), publicResolver(), toServer);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(new String(ok.body(), StandardCharsets.UTF_8)).isEqualTo("target-ok");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void crossTenantFileInvisible() throws Exception {
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
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/cat")), "requires /bin/cat");
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/cp")), "requires /bin/cp");

        assertThat(jail.workspaceDir(TENANT_A)).isNotEqualTo(jail.workspaceDir(TENANT_B));

        jail.writeWorkspaceFile(TENANT_A, "input.txt",
                "hello".getBytes(StandardCharsets.UTF_8));
        JailResult copy = jail.run(TENANT_A,
                List.of("/bin/cp", "input.txt", "out.txt"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(copy.exitCode()).isEqualTo(0);
        JailResult result = jail.run(TENANT_A,
                List.of("/bin/cat", "out.txt"),
                Map.of(), Duration.ofSeconds(10));
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("hello");
        assertThat(jail.readWorkspaceFile(TENANT_A, TENANT_A, "out.txt"))
                .isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void adversarialChildDenied() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/cat")), "requires /bin/cat");

        jail.writeWorkspaceFile(TENANT_A, "secret-a.txt",
                "alpha-secret".getBytes(StandardCharsets.UTF_8));
        jail.writeWorkspaceFile(TENANT_B, "secret-b.txt",
                "bravo-secret".getBytes(StandardCharsets.UTF_8));

        // Direct cross-tenant read via ../ denies at the API layer (argv validation).
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("/bin/cat", "../" + TENANT_B + "/secret-b.txt"), Map.of(),
                Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        // Absolute read of another tenant's file denies.
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("/bin/cat", tempDir.resolve(TENANT_B + "/secret-b.txt").toString()),
                Map.of(), Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        // Absolute read of /etc/passwd denies.
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("/bin/cat", "/etc/passwd"), Map.of(), Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        // Shell interpreters deny (no shell with untrusted argv).
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("/bin/sh", "-c", "cat secret-a.txt"), Map.of(),
                Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        // Writes outside the workspace deny and create nothing outside.
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("/usr/bin/touch", "../evil-from-child"), Map.of(),
                Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("/usr/bin/touch", "/tmp/pwn-jail-test"), Map.of(),
                Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        assertThat(tempDir.resolve("evil-from-child")).doesNotExist();
        assertThat(Path.of("/tmp/pwn-jail-test")).doesNotExist();
        // Workspace executables deny (relative-with-slash and absolute-inside).
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("./evil"), Map.of(), Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of(jail.workspaceDir(TENANT_A).resolve("evil").toString()),
                Map.of(), Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        // Nothing leaked: sibling secret never appears in any child output.
        JailResult listing = jail.run(TENANT_A,
                List.of("/bin/ls", "-1"), Map.of(), Duration.ofSeconds(10));
        assertThat(listing.stdout()).doesNotContain("bravo-secret");
    }

    @Test
    void symlinkEscapeDenied() throws Exception {
        jail.writeWorkspaceFile(TENANT_B, "secret-b.txt",
                "bravo-secret".getBytes(StandardCharsets.UTF_8));
        Path workspaceA = jail.workspaceDir(TENANT_A);
        Path workspaceB = jail.workspaceDir(TENANT_B);

        // Link-to-sibling denies on resolve, read, and write.
        Files.createSymbolicLink(workspaceA.resolve("link-sibling"),
                Path.of("../" + TENANT_B));
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "link-sibling/secret-b.txt"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.readWorkspaceFile(TENANT_A, TENANT_A,
                "link-sibling/secret-b.txt"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.writeWorkspaceFile(TENANT_A,
                "link-sibling/evil.txt", "x".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.run(TENANT_A,
                List.of("/bin/cat", "link-sibling/secret-b.txt"), Map.of(),
                Duration.ofSeconds(10)))
                .isInstanceOf(SecurityException.class);
        assertThat(workspaceB.resolve("evil.txt")).doesNotExist();

        // Link-to-/tmp denies.
        Files.createSymbolicLink(workspaceA.resolve("link-tmp"), Path.of("/tmp"));
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "link-tmp/x"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> jail.readWorkspaceFile(TENANT_A, TENANT_A, "link-tmp/x"))
                .isInstanceOf(SecurityException.class);

        // Parent-dir symlink denies children beneath it.
        Files.createDirectories(workspaceA.resolve("real"));
        Files.createSymbolicLink(workspaceA.resolve("link-parent"), Path.of("real"));
        // Sanity: the link itself was created, but any path through it denies
        // fail-closed (even though it points inside).
        assertThatThrownBy(() -> jail.resolveInWorkspace(TENANT_A, "link-parent/file.txt"))
                .isInstanceOf(SecurityException.class);

        // Final-component symlink to a sibling file denies.
        Files.createSymbolicLink(workspaceA.resolve("evil-link"),
                Path.of("../" + TENANT_B + "/secret-b.txt"));
        assertThatThrownBy(() -> jail.readWorkspaceFile(TENANT_A, TENANT_A, "evil-link"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void dangerousEnvDenied() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/cat")), "requires /bin/cat");
        for (String key : List.of("LD_PRELOAD", "LD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES",
                "PYTHONPATH", "PERL5LIB", "RUBYLIB", "NODE_OPTIONS", "IFS", "BASH_ENV",
                "ENV", "ZDOTDIR")) {
            assertThatThrownBy(() -> jail.run(TENANT_A,
                    List.of("/bin/cat", "secret-a.txt"), Map.of(key, "evil"),
                    Duration.ofSeconds(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("denied");
        }
    }

    @Test
    void outputCapped() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/cat")), "requires /bin/cat");
        byte[] big = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'x');
        jail.writeWorkspaceFile(TENANT_A, "big.bin", big);
        JailResult result = jail.run(TENANT_A,
                List.of("/bin/cat", "big.bin"), Map.of(), Duration.ofSeconds(15));
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdout().length()).isEqualTo(ExecJail.MAX_CAPTURE_BYTES);
    }

    @Test
    void egressNonCanonicalDenied() {
        // Single-integer and hex-integer forms deny even when allowlisted.
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://2130706433/",
                Set.of("2130706433")))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://0x7f000001/",
                Set.of("0x7f000001")))
                .isInstanceOf(EgressDeniedException.class);
        // Expanded loopback/unspecified deny even when allowlisted.
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://[0:0:0:0:0:0:0:1]/",
                Set.of("0:0:0:0:0:0:0:1")))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://[0:0:0:0:0:0:0:0]/",
                Set.of("0:0:0:0:0:0:0:0")))
                .isInstanceOf(EgressDeniedException.class);
        // IPv4-mapped IPv6 denies even when allowlisted.
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://[::ffff:127.0.0.1]/",
                Set.of("::ffff:127.0.0.1")))
                .isInstanceOf(EgressDeniedException.class);
        assertThatThrownBy(() -> JailEgress.checkAllowed("https://[::ffff:10.0.0.1]/",
                Set.of("::ffff:10.0.0.1")))
                .isInstanceOf(EgressDeniedException.class);
        assertThat(JailEgress.isBlockedTarget("https://2130706433/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://[0:0:0:0:0:0:0:1]/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://[::ffff:127.0.0.1]/")).isTrue();
    }

    // ---- Issue #73: kernel sandbox and brokered egress ----

    /** Medians of denied existing vs missing targets must be within this (issue #73). */
    static final Duration TIMING_TOLERANCE = Duration.ofMillis(1);







    @Test
    void hostnameRebindDeniedAtConnect() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress("rebind.test", new byte[] {93, (byte) 184, (byte) 216, 34});
        List<InetAddress> blocked = List.of(
                InetAddress.ofLiteral("127.0.0.1"), InetAddress.ofLiteral("169.254.169.254"),
                InetAddress.ofLiteral("10.0.0.5"), InetAddress.ofLiteral("100.100.100.200"),
                InetAddress.ofLiteral("::1"), InetAddress.ofLiteral("fd00:ec2::254"),
                InetAddress.ofLiteral("fe80::1"), InetAddress.ofLiteral("64:ff9b::a9fe:a9fe"));
        try (ServerSocket remote = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            for (InetAddress rebound : blocked) {
                AtomicInteger lookups = new AtomicInteger();
                List<InetSocketAddress> connects = new CopyOnWriteArrayList<>();
                JailEgress.Resolver rebinding = host -> lookups.getAndIncrement() == 0
                        ? List.of(publicAddress) : List.of(rebound);
                JailEgress.Connector recording = (address, host, timeout) -> {
                    connects.add(address);
                    Socket socket = new Socket();
                    socket.connect(remote.getLocalSocketAddress(), 1000);
                    return socket;
                };

                // The allowlist check passes, with no DNS.
                JailEgress.checkAllowed("https://rebind.test/", Set.of("rebind.test"));
                assertThat(lookups.get()).isZero();

                // First connect: resolves public, connects to exactly that vetted address.
                try (Socket first = JailEgress.open("https://rebind.test/", Set.of("rebind.test"),
                        rebinding, recording)) {
                    assertThat(first.isConnected()).isTrue();
                }
                assertThat(connects).hasSize(1);
                assertThat(connects.get(0).getAddress()).isEqualTo(publicAddress);
                assertThat(connects.get(0).isUnresolved()).isFalse();

                // The name now re-resolves to a blocked address: denied at connect time, no connect.
                assertThatThrownBy(() -> JailEgress.open("https://rebind.test/", Set.of("rebind.test"),
                        rebinding, recording))
                        .as("rebound to " + rebound)
                        .isInstanceOf(EgressDeniedException.class);
                assertThat(connects).hasSize(1);
            }
        }

        // A round-robin answer with any blocked address is denied whole.
        assertThatThrownBy(() -> JailEgress.open("https://rebind.test/", Set.of("rebind.test"),
                host -> List.of(publicAddress, InetAddress.ofLiteral("10.0.0.5")),
                (address, host, timeout) -> {
                    throw new AssertionError("connected despite a blocked answer");
                }))
                .isInstanceOf(EgressDeniedException.class);
    }

    @Test
    void ssrfMatrixAllDenied() throws Exception {
        Map<String, List<InetAddress>> dns = Map.of(
                "rebind-loopback.test", List.of(InetAddress.ofLiteral("127.0.0.1")),
                "rebind-metadata.test", List.of(InetAddress.ofLiteral("169.254.169.254")),
                "rebind-private.test", List.of(InetAddress.ofLiteral("10.0.0.5")),
                "rebind-cgnat.test", List.of(InetAddress.ofLiteral("100.100.100.200")),
                "rebind-v6.test", List.of(InetAddress.ofLiteral("fd00::1")),
                "rebind-mixed.test", List.of(InetAddress.ofLiteral("93.184.216.34"), InetAddress.ofLiteral("10.0.0.5")));
        JailEgress.Resolver resolver = host -> {
            List<InetAddress> answer = dns.get(host);
            if (answer == null) {
                throw new UnknownHostException(host);
            }
            return answer;
        };
        AtomicInteger connects = new AtomicInteger();
        JailEgress.Connector connector = (address, host, timeout) -> {
            connects.incrementAndGet();
            throw new IOException("matrix case reached connect: " + address);
        };

        Map<String, List<String>> matrix = new LinkedHashMap<>();
        matrix.put("loopback", List.of("https://127.0.0.1/", "https://127.1.2.3/", "https://localhost/",
                "https://LOCALHOST./", "https://foo.localhost/", "https://[::1]/", "https://[0:0:0:0:0:0:0:1]/",
                "https://[0000::0001]/", "https://127.0.0.1:8080/admin"));
        matrix.put("metadata", List.of("https://169.254.169.254/latest/meta-data/",
                "https://metadata.google.internal/computeMetadata/v1/", "https://metadata/",
                "https://[fd00:ec2::254]/", "https://100.100.100.200/", "https://169.254.170.2/v2/credentials"));
        matrix.put("link-local", List.of("https://169.254.1.1/", "https://[fe80::1]/", "https://[fe80::1%25eth0]/",
                "https://[febf::1]/"));
        matrix.put("private", List.of("https://10.0.0.1/", "https://172.16.0.1/", "https://172.31.255.255/",
                "https://192.168.0.1/", "https://100.64.0.1/", "https://0.0.0.0/", "https://[::]/",
                "https://[fc00::1]/", "https://[fd12:3456::1]/", "https://[fec0::1]/", "https://198.18.0.1/",
                "https://224.0.0.1/", "https://255.255.255.255/"));
        matrix.put("ipv6-mapped", List.of("https://[::ffff:127.0.0.1]/", "https://[::ffff:7f00:1]/",
                "https://[::ffff:169.254.169.254]/", "https://[0:0:0:0:0:ffff:a00:1]/", "https://[::127.0.0.1]/",
                "https://[64:ff9b::a9fe:a9fe]/", "https://[64:ff9b::7f00:1]/", "https://[64:ff9b:1::1]/",
                "https://[2002:a9fe:a9fe::1]/", "https://[2002:7f00:1::]/", "https://[2001::1]/",
                "https://[2001:db8::1]/"));
        matrix.put("ipv6-non-global", List.of("https://[100::1]/", "https://[2001:2::1]/", "https://[2001:10::1]/",
                "https://[3fff::1]/", "https://[5f00::1]/"));
        matrix.put("non-canonical", List.of("https://2130706433/", "https://0x7f000001/", "https://0x7f.0.0.1/",
                "https://0177.0.0.1/", "https://127.1/", "https://0x7f.1/", "https://017700000001/",
                "https://2852039166/", "https://0xa9.0xfe.0xa9.0xfe/", "https://0251.0376.0251.0376/",
                "https://169.254.43518/", "https://10.1/", "https://127.0.0.1./", "https://0x0a000001/",
                "https://0/", "https://1.2.3.4.5/", "https://999.1.1.1/", "https://08.1.1.1/"));
        matrix.put("rebinding-name", List.of("https://rebind-loopback.test/", "https://rebind-metadata.test/",
                "https://rebind-private.test/", "https://rebind-cgnat.test/", "https://rebind-v6.test/",
                "https://rebind-mixed.test/"));
        matrix.put("scheme-and-userinfo", List.of("file:///etc/passwd", "gopher://127.0.0.1:70/_x",
                "ftp://public.test/", "http://public.test/", "dict://public.test:11211/", "jar:http://public.test!/x",
                "https://user@public.test/", "https://public.test@127.0.0.1/",
                "https://public.test:80@169.254.169.254/", "https:///nohost", "https://public.test:0/",
                "https://pub lic.test/"));

        int cases = 0;
        for (Map.Entry<String, List<String>> group : matrix.entrySet()) {
            for (String url : group.getValue()) {
                Set<String> allowlist = allowlistFor(url);
                assertThatThrownBy(() -> JailEgress.open(url, allowlist, resolver, connector))
                        .as(group.getKey() + ": " + url + " with allowlist " + allowlist)
                        .isInstanceOf(EgressDeniedException.class)
                        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(url));
                assertThat(connects.get()).as(group.getKey() + ": " + url + " reached connect").isZero();
                cases++;
            }
        }

        // Redirects from an allowlisted host to metadata, loopback, or another allowlisted host.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger followed = new AtomicInteger();
        try {
            for (String[] hop : new String[][] {{"/to-metadata", "https://169.254.169.254/latest/meta-data/"},
                    {"/to-loopback", "https://127.0.0.1/"}, {"/to-allowed", "https://public.test/ok"},
                    {"/to-relative", "/ok"}}) {
                server.createContext(hop[0], exchange -> {
                    exchange.getResponseHeaders().add("Location", hop[1]);
                    exchange.sendResponseHeaders(hop[0].equals("/to-allowed") ? 307 : 302, -1);
                    exchange.close();
                });
            }
            server.createContext("/ok", exchange -> {
                followed.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
            for (String path : List.of("/to-metadata", "/to-loopback", "/to-allowed", "/to-relative")) {
                assertThatThrownBy(() -> JailEgress.fetch("https://public.test" + path,
                        Set.of("public.test"), publicResolver(), toLocal(server)))
                        .as("redirect " + path)
                        .isInstanceOf(EgressDeniedException.class)
                        .hasMessageContaining("redirect");
                cases++;
            }
            assertThat(followed.get()).as("no redirect was followed").isZero();

            // Control: the matrix isn't vacuous. The allowlisted public host itself is fetched.
            assertThat(JailEgress.fetch("https://public.test/ok", Set.of("public.test"),
                    publicResolver(), toLocal(server)).status()).isEqualTo(200);
        } finally {
            server.stop(0);
        }
        // Control: public addresses pass the address check, so ranges are denied, not everything.
        for (String address : List.of("93.184.216.34", "2606:4700:4700::1111", "64:ff9b::5db8:d822",
                "2002:5db8:d822::1")) {
            assertThat(JailEgress.isBlockedAddress(InetAddress.ofLiteral(address))).as(address).isFalse();
        }
        assertThat(cases).as("SSRF matrix size").isGreaterThanOrEqualTo(80);
    }

    @Test
    void nonCanonicalLiteralsAreDecodedNotJustDenied() throws Exception {
        // 0x5db8d822, 1572395042, 0135.184.216.34 and 93.184.55330 are 93.184.216.34, so the
        // allowlist entry matches after decoding.
        List<InetSocketAddress> connects = new ArrayList<>();
        List<String> hosts = new ArrayList<>();
        JailEgress.Connector recording = (address, host, timeout) -> {
            connects.add(address);
            hosts.add(host);
            return new Socket();
        };
        for (String url : List.of("https://0x5db8d822/", "https://1572395042/", "https://0135.184.216.34/",
                "https://93.184.55330/")) {
            JailEgress.checkAllowed(url, Set.of("93.184.216.34"));
            JailEgress.open(url, Set.of("93.184.216.34"), host -> {
                throw new AssertionError("an IP literal never goes to DNS");
            }, recording).close();
        }
        assertThat(connects).extracting(a -> a.getAddress().getHostAddress())
                .containsOnly("93.184.216.34");
        // The certificate is checked against the decoded address, not the encoding.
        assertThat(hosts).containsOnly("93.184.216.34");
        // And the same encodings of a blocked address are denied by what they decode to.
        assertThat(JailEgress.isBlockedTarget("https://0xa9fea9fe/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://127.1/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://0x7f.1:8080/")).isTrue();
        assertThat(JailEgress.isBlockedTarget("https://0x5db8d822/")).isFalse();
        assertThat(JailEgress.isBlockedTarget("https://93.184.55330/")).isFalse();
    }

    @Test
    void deniedEgressTimingHidesExistence() throws Exception {
        // An existing internal name (resolving is slow) vs a missing one (fails fast).
        AtomicInteger lookups = new AtomicInteger();
        JailEgress.Resolver resolver = host -> {
            lookups.incrementAndGet();
            if (host.equals("exists.internal.test")) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of(InetAddress.ofLiteral("10.0.0.7"));
            }
            throw new UnknownHostException(host);
        };
        JailEgress.Connector connector = (address, host, timeout) -> {
            throw new AssertionError("denied request connected");
        };
        Set<String> allowlist = Set.of("public.test");
        String[] messages = new String[2];
        Duration gap = medianGap(
                () -> messages[0] = deniedMessage(() -> JailEgress.open("https://exists.internal.test/", allowlist,
                        resolver, connector)),
                () -> messages[1] = deniedMessage(() -> JailEgress.open("https://missing.internal.test/", allowlist,
                        resolver, connector)));
        assertThat(lookups.get()).as("a denied request never resolves").isZero();
        assertThat(messages[0]).isEqualTo(messages[1]);
        assertThat(gap).as("egress denial gap").isLessThanOrEqualTo(TIMING_TOLERANCE);
    }


    @Test
    void productionConnectorIsTlsAndPlaintextIsDenied() throws Exception {
        // Plaintext egress is denied before any lookup or connect, even when allowlisted.
        assertThatThrownBy(() -> JailEgress.open("http://public.test/", Set.of("public.test"),
                host -> {
                    throw new AssertionError("resolved a plaintext URL");
                },
                (address, host, timeout) -> {
                    throw new AssertionError("connected a plaintext URL");
                }))
                .isInstanceOf(EgressDeniedException.class);

        // The production connector speaks TLS: a plaintext server at the vetted address fails
        // the handshake instead of being read in the clear.
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            InetSocketAddress pinned = new InetSocketAddress(
                    InetAddress.getByAddress("public.test", new byte[] {127, 0, 0, 1}), server.getAddress().getPort());
            assertThatThrownBy(() -> JailEgress.TLS.connect(pinned, "public.test", Duration.ofSeconds(5)).close())
                    .isInstanceOf(IOException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void tlsConnectorVerifiesCertificateAndHostName() throws Exception {
        // A TLS server on loopback whose certificate names only public.test.
        SSLContext tls = selfSignedContext("public.test");
        List<String> sni = new CopyOnWriteArrayList<>();
        try (SSLServerSocket server = (SSLServerSocket) tls.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> serveOk(server, sni), "tls-test-server");
            acceptor.setDaemon(true);
            acceptor.start();
            InetSocketAddress loopback = new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
            JailEgress.Connector trusting = JailEgress.tls(tls.getSocketFactory());
            JailEgress.Connector toServer = (address, host, timeout) -> {
                assertThat(address.getAddress().getHostAddress()).isEqualTo("93.184.216.34");
                return trusting.connect(loopback, host, timeout);
            };

            // Control: a full fetch through the broker over TLS, with SNI for the URL's host.
            JailEgress.Response ok = JailEgress.fetch("https://public.test/", Set.of("public.test"),
                    publicResolver(), toServer);
            assertThat(ok.status()).isEqualTo(200);
            assertThat(new String(ok.body(), StandardCharsets.UTF_8)).isEqualTo("ok");
            assertThat(sni).containsExactly("public.test");

            // A trusted certificate for another name is refused: the host name is verified.
            assertThatThrownBy(() -> JailEgress.fetch("https://other.test/", Set.of("other.test"),
                    publicResolver(), toServer))
                    .isInstanceOf(SSLHandshakeException.class)
                    .hasStackTraceContaining("other.test");

            // The check is against the URL's host, whatever name the address carries.
            InetSocketAddress namedPublic = new InetSocketAddress(
                    InetAddress.getByAddress("public.test", loopback.getAddress().getAddress()), loopback.getPort());
            assertThatThrownBy(() -> trusting.connect(namedPublic, "other.test", Duration.ofSeconds(5)).close())
                    .isInstanceOf(SSLHandshakeException.class);

            // The production connector doesn't trust a certificate outside the JVM's trust store.
            assertThatThrownBy(() -> JailEgress.TLS.connect(loopback, "public.test", Duration.ofSeconds(5)).close())
                    .isInstanceOf(SSLHandshakeException.class);
        }
    }

    /** An {@link SSLContext} whose key and only trust anchor is a fresh self-signed certificate for {@code name}. */
    private SSLContext selfSignedContext(String name) throws Exception {
        Path keystore = tempDir.resolve("tls-" + UUID.randomUUID() + ".p12");
        char[] password = UUID.randomUUID().toString().toCharArray();
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "server", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=" + name, "-ext", "SAN=dns:" + name, "-validity", "2",
                "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", new String(password))
                .redirectErrorStream(true).start();
        String out = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(keytool.waitFor()).as(out).isZero();
        KeyStore keys = KeyStore.getInstance(keystore.toFile(), password);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("server", keys.getCertificate("server"));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, password);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return context;
    }

    /** Answers {@code 200 ok} to each TLS client, recording the SNI names it asked for. */
    private static void serveOk(SSLServerSocket server, List<String> sni) {
        while (!server.isClosed()) {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                socket.setSoTimeout(5_000);
                socket.startHandshake();
                ((ExtendedSSLSession) socket.getSession()).getRequestedServerNames()
                        .forEach(n -> sni.add(((SNIHostName) n).getAsciiName()));
                InputStream in = socket.getInputStream();
                // Read the request up to its blank line, then answer.
                int matched = 0;
                while (matched < 4) {
                    int c = in.read();
                    if (c == -1) {
                        break;
                    }
                    matched = c == "\r\n\r\n".charAt(matched) ? matched + 1 : (c == '\r' ? 1 : 0);
                }
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                        .getBytes(StandardCharsets.US_ASCII));
            } catch (IOException e) {
                // A refused handshake is what the mismatch cases expect; keep serving.
            }
        }
    }

    @FunctionalInterface
    private interface Denied {
        void call() throws Exception;
    }

    private static String deniedMessage(Denied denied) {
        try {
            denied.call();
        } catch (EgressDeniedException | NoSuchFileException e) {
            return e.getClass().getSimpleName() + ":" + e.getMessage().replaceAll("exists|missing", "_");
        } catch (Exception e) {
            throw new AssertionError("expected a denial", e);
        }
        throw new AssertionError("expected a denial");
    }

    /** |median(a) - median(b)| over interleaved trials after a warm-up. */
    private static Duration medianGap(Runnable a, Runnable b) {
        int warmup = 200;
        int trials = 201;
        for (int i = 0; i < warmup; i++) {
            a.run();
            b.run();
        }
        long[] ta = new long[trials];
        long[] tb = new long[trials];
        for (int i = 0; i < trials; i++) {
            long t0 = System.nanoTime();
            a.run();
            long t1 = System.nanoTime();
            b.run();
            long t2 = System.nanoTime();
            ta[i] = t1 - t0;
            tb[i] = t2 - t1;
        }
        Arrays.sort(ta);
        Arrays.sort(tb);
        return Duration.ofNanos(Math.abs(ta[trials / 2] - tb[trials / 2]));
    }

    /** Allowlists the URL's own host, so a denial is never just default-deny. */
    private static Set<String> allowlistFor(String url) {
        Set<String> hosts = new HashSet<>(Set.of("public.test"));
        try {
            String host = URI.create(url).getHost();
            if (host != null) {
                hosts.add(host);
            }
        } catch (IllegalArgumentException ignored) {
            // Unparseable URLs keep the base allowlist.
        }
        return hosts;
    }

    private static JailEgress.Resolver publicResolver() {
        return host -> List.of(InetAddress.getByAddress(host, new byte[] {93, (byte) 184, (byte) 216, 34}));
    }

    /**
     * Stands in for the remote host: connects the vetted address to a local plaintext test
     * server, in place of {@link JailEgress#TLS}.
     */
    private static JailEgress.Connector toLocal(HttpServer server) {
        return (address, host, timeout) -> {
            assertThat(address.getAddress().getHostAddress()).isEqualTo("93.184.216.34");
            Socket socket = new Socket();
            socket.connect(server.getAddress(), (int) timeout.toMillis());
            return socket;
        };
    }
}
