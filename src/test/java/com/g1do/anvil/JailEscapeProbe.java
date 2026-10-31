package com.g1do.anvil;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.g1do.anvil.exec.ExecJail;
import com.g1do.anvil.exec.JailResult;

/**
 * Escape attempts by untrusted native code inside {@link ExecJail} (issue #73).
 *
 * <p>Each attempt runs a {@code bash -c} script through {@code /usr/bin/env}, which
 * passes the jail's argv checks: the issue names interpreter {@code -c} strings as a
 * way past them. So nothing here is stopped by argv validation; only the kernel
 * sandbox stands between the script and its target. Each attempt reports whether it
 * escaped, with the evidence.
 *
 * <p>{@link ExecJailTest} runs these on the host. {@code JailContainerEscapeTest} runs
 * {@link #main} inside a container, the way the jail would be deployed. This class
 * uses only the JDK and the jail, so it runs there without the test classpath.
 */
public final class JailEscapeProbe {

    static final String TENANT_A = "t_jail_a";
    static final String TENANT_B = "t_jail_b";
    static final String SECRET_B = "bravo-secret-" + UUID.randomUUID();

    /** One escape attempt's outcome. */
    record Attempt(String name, boolean escaped, String evidence) {

        String line() {
            return "ATTEMPT " + name + " escaped=" + escaped + " evidence="
                    + evidence.replaceAll("\\s+", " ").trim();
        }
    }

    private final ExecJail jail;
    private final Path root;

    JailEscapeProbe(ExecJail jail, Path root) {
        this.jail = jail;
        this.root = root;
    }

    /** Runs every attempt in order; the control must succeed, the rest must not escape. */
    List<Attempt> runAll() throws Exception {
        List<Attempt> attempts = new ArrayList<>();
        attempts.add(control());
        attempts.add(siblingRead());
        attempts.add(siblingWrite());
        attempts.add(symlinkToSibling());
        attempts.add(sharedTmpRead());
        attempts.add(sharedTmpWrite());
        attempts.add(hostProcesses());
        attempts.add(loopbackEgress());
        attempts.add(networkEgress());
        return attempts;
    }

    /** Seeds tenant B's secret. */
    void seed() throws IOException {
        jail.writeWorkspaceFile(TENANT_B, "secret-b.txt", SECRET_B.getBytes(StandardCharsets.UTF_8));
    }

    JailResult nativeCode(String tenant, String script) throws Exception {
        return jail.run(tenant, List.of("/usr/bin/env", "bash", "-c", script), Map.of(),
                Duration.ofSeconds(30));
    }

    /** Control: the jailed child can write and read its own workspace. Escaped=false means it worked. */
    Attempt control() throws Exception {
        JailResult r = nativeCode(TENANT_A, "echo own-data > own.txt && cat own.txt");
        Path own = jail.workspaceDir(TENANT_A).resolve("own.txt");
        boolean ok = r.exitCode() == 0 && r.stdout().contains("own-data") && Files.exists(own)
                && Files.readString(own).contains("own-data");
        return new Attempt("control-own-workspace", !ok, "exit=" + r.exitCode() + " out=" + r.stdout());
    }

    /** Reads B's secret by relative path, host path, a workspace binary, and a search of /. */
    Attempt siblingRead() throws Exception {
        seed();
        Path cat = jail.workspaceDir(TENANT_A).resolve("mycat");
        Files.copy(Path.of("/usr/bin/cat"), cat, StandardCopyOption.REPLACE_EXISTING);
        cat.toFile().setExecutable(true);
        Path hostB = jail.workspaceDir(TENANT_B).toRealPath();
        JailResult r = nativeCode(TENANT_A, "cat ../" + TENANT_B + "/secret-b.txt; "
                + "cat " + hostB + "/secret-b.txt; "
                + "./mycat " + hostB + "/secret-b.txt; "
                + "cd .. && ls -R . 2>&1 | head -50; "
                + "grep -rs bravo-secret . --exclude-dir=proc --exclude-dir=sys --exclude-dir=usr --exclude-dir=dev"
                + " 2>/dev/null | head -5");
        boolean escaped = (r.stdout() + r.stderr()).contains(SECRET_B);
        return new Attempt("sibling-workspace-read", escaped, r.stdout() + " " + r.stderr());
    }

    /** Writes into B's workspace by relative and host path. */
    Attempt siblingWrite() throws Exception {
        seed();
        Path hostB = jail.workspaceDir(TENANT_B).toRealPath();
        JailResult r = nativeCode(TENANT_A, "mkdir -p ../" + TENANT_B + "; echo pwn > ../" + TENANT_B
                + "/pwn-rel; echo pwn > " + hostB + "/pwn-abs; echo pwn > " + root.toRealPath()
                + "/pwn-root; true");
        boolean escaped = Files.exists(hostB.resolve("pwn-rel")) || Files.exists(hostB.resolve("pwn-abs"))
                || Files.exists(root.resolve("pwn-root")) || Files.exists(root.resolve(TENANT_B).resolve("pwn-rel"));
        return new Attempt("sibling-workspace-write", escaped, "exit=" + r.exitCode() + " err=" + r.stderr());
    }

    /** Follows symlinks to B planted by the host and made by the child itself. */
    Attempt symlinkToSibling() throws Exception {
        seed();
        Path wsA = jail.workspaceDir(TENANT_A);
        Path hostB = jail.workspaceDir(TENANT_B).toRealPath();
        Files.deleteIfExists(wsA.resolve("to-b-abs"));
        Files.deleteIfExists(wsA.resolve("to-b-rel"));
        Files.createSymbolicLink(wsA.resolve("to-b-abs"), hostB);
        Files.createSymbolicLink(wsA.resolve("to-b-rel"), Path.of("../" + TENANT_B));
        JailResult r = nativeCode(TENANT_A, "cat to-b-abs/secret-b.txt; cat to-b-rel/secret-b.txt; "
                + "ln -sfn " + hostB + " mine && cat mine/secret-b.txt; echo pwn > to-b-abs/pwn-link; true");
        boolean escaped = (r.stdout() + r.stderr()).contains(SECRET_B) || Files.exists(hostB.resolve("pwn-link"));
        return new Attempt("symlink-to-sibling", escaped, r.stdout() + " " + r.stderr());
    }

    /** Reads a file the host left in the shared /tmp. */
    Attempt sharedTmpRead() throws Exception {
        String name = "anvil-jail-marker-" + UUID.randomUUID();
        String content = "shared-tmp-" + UUID.randomUUID();
        Path marker = Path.of("/tmp", name);
        Files.writeString(marker, content);
        try {
            JailResult r = nativeCode(TENANT_A, "cat /tmp/" + name + "; ls -a /tmp");
            boolean escaped = (r.stdout() + r.stderr()).contains(content) || r.stdout().contains(name);
            return new Attempt("shared-tmp-read", escaped, r.stdout() + " " + r.stderr());
        } finally {
            Files.deleteIfExists(marker);
        }
    }

    /** Writes a file into the shared /tmp. */
    Attempt sharedTmpWrite() throws Exception {
        String name = "anvil-jail-pwn-" + UUID.randomUUID();
        Path target = Path.of("/tmp", name);
        try {
            JailResult r = nativeCode(TENANT_A, "echo pwn > /tmp/" + name + " && ls /tmp");
            boolean escaped = Files.exists(target);
            return new Attempt("shared-tmp-write", escaped, "exit=" + r.exitCode() + " out=" + r.stdout());
        } finally {
            Files.deleteIfExists(target);
        }
    }

    /** Looks for the parent JVM through /proc, the way out of a mount-only jail. */
    Attempt hostProcesses() throws Exception {
        long parent = ProcessHandle.current().pid();
        JailResult r = nativeCode(TENANT_A, "for p in /proc/[0-9]*; do tr '\\0' ' ' < $p/cmdline; echo; done; "
                + "test -e /proc/" + parent + "/root/etc/passwd && echo HOST''ROOT''SEEN; "
                + "test -e /proc/" + parent + "/environ && cat /proc/" + parent + "/environ | tr '\\0' ' '");
        String out = r.stdout().toLowerCase(Locale.ROOT);
        boolean escaped = out.contains("java") || out.contains("hostrootseen") || out.contains("home=");
        return new Attempt("host-processes-via-proc", escaped, r.stdout() + " " + r.stderr());
    }

    /** Connects to a server on the host's loopback. */
    Attempt loopbackEgress() throws Exception {
        try (Listener listener = Listener.on(InetAddress.getLoopbackAddress())) {
            JailResult r = nativeCode(TENANT_A, connectScript(List.of("127.0.0.1/" + listener.port())));
            boolean escaped = r.stdout().contains("CONNECTED") || listener.accepted() > 0;
            return new Attempt("loopback-egress", escaped,
                    "accepted=" + listener.accepted() + " out=" + r.stdout());
        }
    }

    /** Connects to the host's own address, metadata, and a public resolver. */
    Attempt networkEgress() throws Exception {
        InetAddress hostAddress = siteAddress();
        try (Listener listener = Listener.on(hostAddress)) {
            JailResult r = nativeCode(TENANT_A, connectScript(List.of(
                    hostAddress.getHostAddress() + "/" + listener.port(),
                    "169.254.169.254/80", "1.1.1.1/53", "8.8.8.8/53")));
            boolean escaped = r.stdout().contains("CONNECTED") || listener.accepted() > 0;
            return new Attempt("network-egress", escaped,
                    "host=" + hostAddress.getHostAddress() + " accepted=" + listener.accepted() + " out=" + r.stdout()
                            + " routes=" + r.stderr());
        }
    }

    /** A bash script that tries each host/port with /dev/tcp and prints CONNECTED or BLOCKED. */
    static String connectScript(List<String> targets) {
        StringBuilder script = new StringBuilder();
        for (String target : targets) {
            script.append("if timeout 3 bash -c 'exec 3<>/dev/tcp/").append(target)
                    .append("' 2>/dev/null; then echo CONNECTED ").append(target)
                    .append("; else echo BLOCKED ").append(target).append("; fi; ");
        }
        script.append("cat /proc/net/route >&2; cat /proc/net/if_inet6 >&2");
        return script.toString();
    }

    /** The first non-loopback IPv4 address of this machine (or container). */
    static InetAddress siteAddress() throws IOException {
        for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!nic.isUp() || nic.isLoopback()) {
                continue;
            }
            for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                if (address instanceof Inet4Address) {
                    return address;
                }
            }
        }
        throw new IOException("no non-loopback IPv4 address");
    }

    /** A TCP listener that counts accepted connections. */
    static final class Listener implements AutoCloseable {
        private final ServerSocket server;
        private final AtomicInteger accepted = new AtomicInteger();

        private Listener(ServerSocket server) {
            this.server = server;
            Thread acceptor = new Thread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        accepted.incrementAndGet();
                        socket.close();
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "jail-probe-listener");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        static Listener on(InetAddress address) throws IOException {
            ServerSocket server = new ServerSocket();
            server.bind(new InetSocketAddress(address, 0));
            return new Listener(server);
        }

        int port() {
            return server.getLocalPort();
        }

        int accepted() {
            return accepted.get();
        }

        @Override
        public void close() {
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing a listener can't change any attempt's outcome.
            }
        }
    }

    /**
     * Runs every attempt against a jail rooted at {@code args[0]} and prints one
     * {@code ATTEMPT} line each. Exits 1 when any attempt escaped or the control failed.
     */
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "jail-root");
        Files.createDirectories(root);
        ExecJail jail = new ExecJail(root.toString());
        boolean failed = false;
        for (Attempt attempt : new JailEscapeProbe(jail, root).runAll()) {
            System.out.println(attempt.line());
            failed |= attempt.escaped();
        }
        System.out.println(failed ? "PROBE FAILED" : "PROBE PASSED");
        System.exit(failed ? 1 : 0);
    }
}
