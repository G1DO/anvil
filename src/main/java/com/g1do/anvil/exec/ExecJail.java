package com.g1do.anvil.exec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
 * {@link SecurityException}, and denies anything that normalizes outside the
 * workspace. The tenant name itself follows the same directory-escape rules as
 * {@code ContentStore} ({@code /, \, .., %} and reserved names rejected).</li>
 * <li>Forked child: {@link #run} launches a real child {@link Process} with its
 * working directory set to the tenant workspace. There is no in-process
 * untrusted execution path.</li>
 * <li>Clean environment ({@code env -i} equivalent): the child's environment is
 * cleared before launch; only {@code PATH=/usr/bin:/bin},
 * {@code ANVIL_TENANT=<tenant>}, and explicitly passed {@code extraEnv} entries
 * survive. Parent secrets and cross-tenant tokens are never inherited.
 * {@code ANVIL_TENANT} and {@code PATH} cannot be overridden via
 * {@code extraEnv}.</li>
 * <li>Cross-tenant invisibility at the API layer:
 * {@link #readWorkspaceFile(String, String, String)} denies with a
 * caller-scoped {@link NoSuchFileException} (no content, no oracle) when
 * {@code caller != resource}, mirroring the CAS gateway. A jailed child's
 * listing of its own workspace contains only its own files.</li>
 * </ul>
 *
 * <p>Residual (explicitly out of scope for v1): no mount, user, or network
 * namespace is created, so this class alone does not make the OS kernel deny a
 * raw {@code ../sibling} or {@code /tmp} access from arbitrary shell. Untrusted
 * callers must route every file operand through {@link #resolveInWorkspace} /
 * {@link #readWorkspaceFile} and must execute {@code argv} directly (no shell)
 * or with validated workspace-relative operands only. Full containerization,
 * nftables DROP, DNS-rebind hardening, and non-canonical IP decoding are
 * deferred; network egress from the untrusted path is separately default-denied
 * by {@link JailEgress}.
 */
@Service
public class ExecJail {

    /** Minimal safe {@code PATH} for jailed children ({@code env -i} equivalent). */
    public static final String SAFE_PATH = "/usr/bin:/bin";

    /** Default execution timeout when the caller passes {@code null}. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern ENV_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

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
     *         {@code ..} segment, contains {@code %} or a null byte, or
     *         normalizes outside the workspace (read-only-root enforcement at
     *         the API layer: only the workspace is writable/addressable).
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
        return resolved;
    }

    /**
     * Writes {@code content} to a workspace-relative path, creating parents.
     *
     * @throws SecurityException when the path escapes the workspace.
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
     * @throws SecurityException when the path escapes the workspace.
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
     * {@code extraEnv} entries.
     *
     * @param tenantId authenticated tenant scoping the workspace.
     * @param argv command plus args, executed directly without a shell.
     * @param extraEnv explicit extra variables (allowlist); {@code null} means none.
     *        Keys must match {@code [A-Za-z_][A-Za-z0-9_]*}; {@code ANVIL_TENANT}
     *        and {@code PATH} entries are ignored (forced).
     * @param timeout how long to wait; {@code null} means {@link #DEFAULT_TIMEOUT}.
     * @return captured exit code, stdout, and stderr.
     * @throws IllegalStateException when the child exceeds the timeout (killed).
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
                env.put(key, value);
            }
        }

        Process process = builder.start();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
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
        return new JailResult(process.exitValue(), out, err);
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
