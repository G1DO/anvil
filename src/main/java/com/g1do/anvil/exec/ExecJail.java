package com.g1do.anvil.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Minimal execution jail for untrusted work (issue #23).
 *
 * <p>Contract (narrowest reasonable):
 * <ul>
 * <li>Per-tenant writable workspace only: the workspace for {@code tenant} is
 * {@code <root>/<tenant>} ({@code anvil.exec.root}, default
 * {@code var/exec-workspaces}). Every file access through this class resolves
 * via {@link #resolveInWorkspace}, which denies absolute paths, {@code ..}
 * segments, {@code %} (encoded traversal), and null bytes with
 * {@link SecurityException}, denies anything that normalizes outside the
 * workspace, and denies any path with a symlink component (fail-closed: even
 * a symlink pointing inside still denies). The tenant name itself follows the
 * same directory-escape rules as {@code ContentStore} ({@code /, \, .., %}
 * and reserved names rejected).</li>
 * <li>Forked child: {@link #run} launches a real child {@link Process} with its
 * working directory set to the tenant workspace. There is no in-process
 * untrusted execution path. Shell interpreters ({@code sh}, {@code bash},
 * {@code dash}, {@code ash}, {@code zsh}, {@code ksh}, {@code fish},
 * {@code csh}, {@code tcsh}, {@code pwsh}/{@code powershell}) are denied, every
 * operand containing an absolute path, a {@code ..} segment, or {@code %} is
 * denied, workspace executables (relative-with-slash or absolute inside the
 * workspace) are denied, and operands resolving through a symlink are denied.
 * Stdout/stderr capture is capped at {@value #MAX_CAPTURE_BYTES} bytes per
 * stream (truncated + flag).</li>
 * <li>Clean environment ({@code env -i} equivalent): the child's environment is
 * cleared before launch; only {@code PATH=/usr/bin:/bin},
 * {@code ANVIL_TENANT=<tenant>}, and explicitly passed {@code extraEnv} entries
 * survive. Parent secrets and cross-tenant tokens are never inherited.
 * {@code ANVIL_TENANT} and {@code PATH} cannot be overridden via
 * {@code extraEnv} (ignored); loader/debug keys ({@code LD_*}, {@code DYLD_*},
 * {@code PYTHON*}, {@code PERL*}, {@code RUBY*}, {@code NODE_*}, {@code IFS},
 * {@code BASH_ENV}, {@code ENV}, {@code ZDOTDIR}) are denied.</li>
 * <li>Cross-tenant invisibility at the API layer:
 * {@link #readWorkspaceFile(String, String, String)} denies with a
 * caller-scoped {@link NoSuchFileException} (no content, no oracle) when
 * {@code caller != resource}, mirroring the CAS gateway. A jailed child's
 * listing of its own workspace contains only its own files.</li>
 * </ul>
 *
 * <p>Residual (explicitly out of scope for v1): no mount, user, or network
 * namespace is created, so the OS kernel still permits a raw
 * {@code ../sibling} or {@code /tmp} access from arbitrary native code once it
 * runs (interpreter {@code -c} strings, workspace binaries, or {@code /proc}
 * bypass the argv allowlist; use is restricted to trusted callers composing
 * direct {@code argv} with validated workspace-relative operands). TOCTOU
 * between symlink check and use remains; full containerization,
 * nftables DROP, DNS-rebind hardening, and IP decoding are
 * deferred; network egress from the untrusted path is separately default-denied
 * by {@link JailEgress}.
 */
@Service
public class ExecJail {

    /** Minimal safe {@code PATH} for jailed children ({@code env -i} equivalent). */
    public static final String SAFE_PATH = "/usr/bin:/bin";

    /** Default execution timeout when the caller passes {@code null}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** Max captured bytes per stream; beyond this output is truncated (flag set). */
    public static final int MAX_CAPTURE_BYTES = 1024 * 1024;

    private static final Pattern ENV_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private static final Set<String> SHELL_BASENAMES = Set.of(
            "sh", "bash", "dash", "ash", "zsh", "ksh", "fish", "csh", "tcsh",
            "pwsh", "powershell");

    private final Path root;

    public ExecJail(@Value("${anvil.exec.root:var/exec-workspaces}") String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("exec root must not be blank");
        }
        this.root = Paths.get(root.trim());
    }

    /**
     * Returns the tenant workspace directory, creating it when absent.
     *
     * @throws IllegalArgumentException when the tenant name escapes its directory.
     */
    public Path workspaceDir(String tenantId) throws IOException {
        String tenant = requireTenant(tenantId);
        Path workspace = root.resolve(tenant);
        Files.createDirectories(workspace);
        return workspace;
    }

    /**
     * Resolves {@code userPath} inside the tenant workspace, fail-closed.
     *
     * @throws IllegalArgumentException when tenant or path is blank/null.
     * @throws SecurityException when the path is absolute, contains a
     *         {@code ..} segment, contains {@code %} or a null byte,
     *         normalizes outside the workspace, or traverses a symlink
     *         (read-only-root enforcement at the API layer: only the workspace
     *         is writable/addressable, with no symlink escape).
     */
    public Path resolveInWorkspace(String tenantId, String userPath) throws IOException {
        String tenant = requireTenant(tenantId);
        if (userPath == null || userPath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (userPath.contains("\0")) {
            throw new SecurityException("path contains null byte");
        }
        if (userPath.contains("%")) {
            throw new SecurityException("path contains encoded traversal");
        }
        Path relative;
        try {
            relative = Paths.get(userPath);
        } catch (Exception e) {
            throw new SecurityException("unresolvable path");
        }
        if (relative.isAbsolute()) {
            throw new SecurityException("absolute path escapes workspace");
        }
        for (Path part : relative) {
            if (part.toString().equals("..")) {
                throw new SecurityException("path escapes workspace");
            }
        }
        Path workspace = workspaceDir(tenant);
        Path resolved = workspace.resolve(relative).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new SecurityException("path escapes workspace");
        }
        assertNoSymlinkEscape(workspace, resolved);
        return resolved;
    }

    /**
     * Writes {@code content} to a workspace-relative path, creating parents.
     *
     * @throws SecurityException when the path escapes the workspace or traverses a symlink.
     */
    public void writeWorkspaceFile(String tenantId, String relativePath, byte[] content)
            throws IOException {
        String tenant = requireTenant(tenantId);
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        Path target = resolveInWorkspace(tenant, relativePath);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Re-check after mkdirs: a parent could have been swapped for a symlink.
        Path workspace = workspaceDir(tenant);
        assertNoSymlinkEscape(workspace, target);
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("path traverses symlink");
        }
        Files.write(target, content,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Authenticated workspace read: denies without content when
     * {@code caller != resource} (caller-scoped {@link NoSuchFileException}, no
     * oracle), mirroring the CAS gateway.
     *
     * @throws NoSuchFileException when the tenants differ or the file is absent.
     * @throws SecurityException when the path escapes the workspace or traverses a symlink.
     */
    public byte[] readWorkspaceFile(String callerTenant, String resourceTenant, String relativePath)
            throws IOException {
        String caller = requireTenant(callerTenant);
        String resource = requireTenant(resourceTenant);
        String rel = relativePath == null ? "" : relativePath.trim();
        if (!caller.equals(resource)) {
            throw new NoSuchFileException("workspaces/" + caller + "/" + rel);
        }
        Path target = resolveInWorkspace(caller, relativePath);
        if (Files.isSymbolicLink(target)) {
            throw new SecurityException("path traverses symlink");
        }
        try {
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw new NoSuchFileException("workspaces/" + caller + "/" + rel);
        }
    }

    /**
     * Runs {@code argv} as a forked child in the tenant workspace with a cleaned
     * environment.
     *
     * <p>The child is executed directly (no shell); the caller chooses
     * {@code argv}. Its working directory is the tenant workspace, its
     * environment is cleared first ({@code env -i} equivalent) and then set to
     * exactly {@code PATH}, {@code ANVIL_TENANT}, plus validated
     * {@code extraEnv} entries. Shell interpreters are denied, every operand
     * with an absolute path, a {@code ..} segment, or {@code %} is denied,
     * workspace executables are denied, and operands traversing a symlink are
     * denied. Stdout/stderr are capped at {@value #MAX_CAPTURE_BYTES} bytes per
     * stream (truncated + flag).
     *
     * @param tenantId authenticated tenant scoping the workspace.
     * @param argv command plus args, executed directly without a shell.
     * @param extraEnv explicit extra variables (allowlist); {@code null} means none.
     *        Keys must match {@code [A-Za-z_][A-Za-z0-9_]*}; {@code ANVIL_TENANT}
     *        and {@code PATH} entries are ignored (forced); loader/debug keys
     *        ({@code LD_*}, {@code DYLD_*}, {@code PYTHON*}, {@code PERL*},
     *        {@code RUBY*}, {@code NODE_*}, {@code IFS}, {@code BASH_ENV},
     *        {@code ENV}, {@code ZDOTDIR}) are denied.
     * @param timeout how long to wait; {@code null} means {@link #DEFAULT_TIMEOUT}.
     * @return captured exit code, stdout, stderr, and truncation flag.
     * @throws IllegalStateException when the child exceeds the timeout (killed).
     * @throws SecurityException when argv escapes the workspace, uses a shell,
     *         targets a workspace executable, or traverses a symlink.
     */
    public JailResult run(String tenantId, List<String> argv, Map<String, String> extraEnv,
            Duration timeout) throws IOException, InterruptedException {
        String tenant = requireTenant(tenantId);
        if (argv == null || argv.isEmpty()) {
            throw new IllegalArgumentException("argv must not be empty");
        }
        for (String arg : argv) {
            if (arg == null) {
                throw new IllegalArgumentException("argv entry must not be null");
            }
            if (arg.contains("\0")) {
                throw new IllegalArgumentException("argv entry contains null byte");
            }
        }
        if (argv.get(0).isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        Duration wait = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (wait.isNegative() || wait.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        Path workspace = workspaceDir(tenant);
        Path realWorkspace;
        try {
            realWorkspace = workspace.toRealPath();
        } catch (IOException e) {
            realWorkspace = workspace.toAbsolutePath().normalize();
        }
        assertArgvJailed(workspace, realWorkspace, argv);

        ProcessBuilder builder = new ProcessBuilder(argv);
        builder.directory(workspace.toFile());
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PATH", SAFE_PATH);
        env.put("ANVIL_TENANT", tenant);
        if (extraEnv != null) {
            for (Map.Entry<String, String> entry : extraEnv.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null) {
                    throw new IllegalArgumentException("extraEnv key/value must not be null");
                }
                if (!ENV_KEY.matcher(key).matches()) {
                    throw new IllegalArgumentException("extraEnv key invalid: " + key);
                }
                if (key.contains("\0") || value.contains("\0")) {
                    throw new IllegalArgumentException("extraEnv contains null byte");
                }
                if (key.equals("ANVIL_TENANT") || key.equals("PATH")) {
                    continue;
                }
                if (isBlockedEnvKey(key)) {
                    throw new IllegalArgumentException("extraEnv key denied: " + key);
                }
                env.put(key, value);
            }
        }

        Process process = builder.start();
        CappedOutputStream stdout = new CappedOutputStream(MAX_CAPTURE_BYTES);
        CappedOutputStream stderr = new CappedOutputStream(MAX_CAPTURE_BYTES);
        Thread outDrain = drain(process.getInputStream(), stdout);
        Thread errDrain = drain(process.getErrorStream(), stderr);
        outDrain.start();
        errDrain.start();
        boolean finished;
        try {
            finished = process.waitFor(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IllegalStateException("jail execution timed out");
        }
        outDrain.join(5_000L);
        errDrain.join(5_000L);
        String out = stdout.toString(StandardCharsets.UTF_8);
        String err = stderr.toString(StandardCharsets.UTF_8);
        return new JailResult(process.exitValue(), out, err,
                stdout.isTruncated() || stderr.isTruncated());
    }

    private static Thread drain(InputStream in, ByteArrayOutputStream out) {
        Thread thread = new Thread(() -> {
            try (InputStream input = in) {
                input.transferTo(out);
            } catch (IOException ignored) {
                // Best-effort capture; exit code below is authoritative.
            }
        }, "exec-jail-drain");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Fail-closed symlink guard: denies any symlink component between the
     * workspace (exclusive) and {@code resolved} (inclusive), and denies when
     * the longest existing prefix canonicalizes outside the real workspace.
     */
    private static void assertNoSymlinkEscape(Path workspace, Path resolved) throws IOException {
        Path cur = resolved;
        while (cur != null && cur.startsWith(workspace) && !cur.equals(workspace)) {
            if (Files.isSymbolicLink(cur)) {
                throw new SecurityException("path traverses symlink");
            }
            cur = cur.getParent();
        }
        Path existing = resolved;
        while (existing != null && existing.startsWith(workspace)
                && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.startsWith(workspace)) {
            return;
        }
        Path realWorkspace;
        try {
            realWorkspace = workspace.toRealPath();
        } catch (IOException e) {
            throw new SecurityException("path escapes workspace via symlink");
        }
        if (Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path realExisting;
            try {
                realExisting = existing.toRealPath();
            } catch (NoSuchFileException gone) {
                return;
            } catch (IOException e) {
                throw new SecurityException("path escapes workspace via symlink");
            }
            if (!realExisting.startsWith(realWorkspace)) {
                throw new SecurityException("path escapes workspace via symlink");
            }
        }
        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            Path realTarget;
            try {
                realTarget = resolved.toRealPath();
            } catch (NoSuchFileException gone) {
                return;
            } catch (IOException e) {
                throw new SecurityException("path escapes workspace via symlink");
            }
            if (!realTarget.startsWith(realWorkspace)) {
                throw new SecurityException("path escapes workspace via symlink");
            }
        }
    }

    /**
     * API-layer argv confinement (no kernel namespace in v1): denies shell
     * interpreters, absolute/{@code ..}/{@code %} operands, workspace
     * executables, and operands traversing a symlink.
     */
    private static void assertArgvJailed(Path workspace, Path realWorkspace, List<String> argv)
            throws IOException {
        String cmd = argv.get(0);
        String base;
        try {
            Path cmdPathTmp = Paths.get(cmd);
            Path fileName = cmdPathTmp.getFileName();
            base = (fileName == null ? cmd : fileName.toString()).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new SecurityException("unresolvable command");
        }
        if (SHELL_BASENAMES.contains(base)) {
            throw new SecurityException("shell execution denied");
        }
        try {
            Path cmdPath = Paths.get(cmd);
            if (!cmdPath.isAbsolute() && cmd.contains("/")) {
                Path candidate = workspace.resolve(cmdPath).normalize();
                if (candidate.startsWith(workspace)) {
                    throw new SecurityException("workspace executables denied");
                }
            } else if (cmdPath.isAbsolute()) {
                Path normCmd = cmdPath.normalize();
                if (normCmd.startsWith(workspace) || normCmd.startsWith(realWorkspace)) {
                    throw new SecurityException("workspace executables denied");
                }
                if (Files.exists(normCmd, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Path realCmd = normCmd.toRealPath();
                        if (realCmd.startsWith(realWorkspace)) {
                            throw new SecurityException("workspace executables denied");
                        }
                    } catch (NoSuchFileException gone) {
                        // Dangling link outside the workspace: let exec fail naturally.
                    }
                }
            }
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("unresolvable command");
        }
        for (int i = 1; i < argv.size(); i++) {
            String arg = argv.get(i);
            if (arg.contains("%")) {
                throw new SecurityException("argv contains encoded traversal");
            }
            Path p;
            try {
                p = Paths.get(arg);
            } catch (Exception e) {
                throw new SecurityException("unresolvable argv");
            }
            if (p.isAbsolute()) {
                throw new SecurityException("argv escapes workspace");
            }
            for (Path part : p) {
                if (part.toString().equals("..")) {
                    throw new SecurityException("argv escapes workspace");
                }
            }
            try {
                Path candidate = workspace.resolve(p).normalize();
                if (candidate.startsWith(workspace)) {
                    assertNoSymlinkEscape(workspace, candidate);
                }
            } catch (SecurityException se) {
                throw new SecurityException("argv traverses symlink");
            }
        }
    }

    private static boolean isBlockedEnvKey(String key) {
        if (key.equals("IFS") || key.equals("ENV") || key.equals("BASH_ENV")
                || key.equals("ZDOTDIR")) {
            return true;
        }
        return key.startsWith("LD_") || key.startsWith("DYLD_")
                || key.startsWith("PYTHON") || key.startsWith("PERL")
                || key.startsWith("RUBY") || key.startsWith("NODE_");
    }

    /** Capped capture: truncates beyond {@link #MAX_CAPTURE_BYTES} and flags it. */
    private static final class CappedOutputStream extends ByteArrayOutputStream {
        private final int max;
        private boolean truncated;

        CappedOutputStream(int max) {
            this.max = max;
        }

        @Override
        public synchronized void write(int b) {
            if (size() < max) {
                super.write(b);
            } else {
                truncated = true;
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int remaining = max - size();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int toWrite = Math.min(remaining, len);
            super.write(b, off, toWrite);
            if (toWrite < len) {
                truncated = true;
            }
        }

        synchronized boolean isTruncated() {
            return truncated;
        }
    }

    static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        String tenant = tenantId.trim();
        if (tenant.contains("/") || tenant.contains("\\") || tenant.contains("..")) {
            throw new IllegalArgumentException("tenantId escapes its directory");
        }
        if (tenant.contains("%")) {
            throw new IllegalArgumentException("tenantId contains encoded traversal");
        }
        if (tenant.contains("\0")) {
            throw new IllegalArgumentException("tenantId contains null byte");
        }
        if (tenant.equals(".") || tenant.equalsIgnoreCase("null")) {
            throw new IllegalArgumentException("tenantId is reserved");
        }
        return tenant;
    }
}
