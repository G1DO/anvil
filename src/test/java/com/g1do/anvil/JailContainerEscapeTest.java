package com.g1do.anvil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Containerized escape test for issue #73.
 *
 * <p>Runs {@link JailEscapeProbe} inside a container on the production runtime base
 * image, as an unprivileged user, the way the jail would be deployed. Every escape
 * attempt (sibling workspace, shared {@code /tmp}, host processes, loopback, the
 * container's own address, metadata and the internet) must fail, and the control
 * must succeed.
 *
 * <p>Docker's default seccomp and AppArmor profiles refuse the unprivileged user
 * namespaces the sandbox needs, and Docker's masked {@code /proc} paths stop the
 * kernel mounting the sandbox's own {@code /proc}. So the escape run relaxes only
 * those three outer settings; the jail itself is unchanged. The second test keeps
 * Docker's defaults and shows the jail then refuses to run anything rather than
 * running unconfined.
 */
class JailContainerEscapeTest {

    private static final String RUNTIME_BASE = "eclipse-temurin:25.0.3_9-jre";

    private static final List<String> ATTEMPTS = List.of("control-own-workspace",
            "sibling-workspace-read", "sibling-workspace-write", "symlink-to-sibling",
            "shared-tmp-read", "shared-tmp-write", "host-processes-via-proc", "loopback-egress",
            "network-egress");

    private static String imageName;

    @BeforeAll
    static void buildImage() {
        imageName = new ImageFromDockerfile("anvil-jail-escape", false)
                .withDockerfileFromBuilder(b -> b.from(RUNTIME_BASE)
                        .run("apt-get update && apt-get install -y --no-install-recommends bubblewrap"
                                + " && rm -rf /var/lib/apt/lists/*")
                        .run("useradd -m -u 10001 anvil")
                        .user("anvil")
                        .workDir("/home/anvil")
                        .build())
                .get();
    }

    @Test
    void containerizedEscapeAttemptsAllFail() {
        String logs = runProbe(true);

        assertThat(logs).as(logs).contains("PROBE PASSED").contains("EXIT=0");
        for (String attempt : ATTEMPTS) {
            assertThat(logs).as(logs).contains("ATTEMPT " + attempt + " escaped=false");
        }
        assertThat(logs).doesNotContain("escaped=true");
    }

    @Test
    void defaultContainerProfileFailsClosed() {
        String logs = runProbe(false);

        assertThat(logs).as(logs).contains("sandbox unavailable").doesNotContain("escaped=");
        assertThat(logs).doesNotContain("EXIT=0");
    }

    /** Runs the probe and keeps its output in {@code target/} as evidence. */
    private static String runProbe(boolean allowUserNamespaces) {
        String logs = probeLogs(allowUserNamespaces);
        try {
            Files.writeString(Path.of("target", "jail-container-escape-"
                    + (allowUserNamespaces ? "userns" : "default") + ".log"), logs);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return logs;
    }

    /**
     * Runs the probe with the Docker CLI: {@code systempaths=unconfined} has no field in
     * the Docker API client Testcontainers uses, so the run can't go through it.
     */
    private static String probeLogs(boolean allowUserNamespaces) {
        // --pull=never: the image is the one just built, never one fetched from a registry.
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--pull=never"));
        if (allowUserNamespaces) {
            for (String opt : List.of("seccomp=unconfined", "apparmor=unconfined", "systempaths=unconfined")) {
                cmd.addAll(List.of("--security-opt", opt));
            }
        }
        cmd.addAll(List.of(
                "-v", Path.of("target/classes").toAbsolutePath() + ":/app/classes:ro",
                "-v", Path.of("target/test-classes").toAbsolutePath() + ":/app/test-classes:ro",
                imageName, "sh", "-c", "java -cp /app/classes:/app/test-classes "
                        + JailEscapeProbe.class.getName() + " /home/anvil/jail-root 2>&1; echo EXIT=$?"));
        try {
            Process docker = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(docker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            if (!docker.waitFor(3, TimeUnit.MINUTES)) {
                docker.destroyForcibly();
                throw new AssertionError("escape probe container timed out");
            }
            return out.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("escape probe container failed to run", e);
        }
    }
}
