package com.g1do.anvil.cas;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.tenant.TenantGuard;

/**
 * Local-filesystem content-addressed store shim (issue #5).
 *
 * <p>Contract (narrowest reasonable):
 * <ul>
 * <li>Identity is {@code sha256(content)} (lowercase hex); per-tenant paths
 * {@code tenants/{tenant}/{sha}} under the configured root; no
 * cross-tenant deduplication (same bytes in two tenants are two files).</li>
 * <li>Durability order is mandatory, not an optimization: tmp-write in the
 * destination directory -&gt; fsync file -&gt; atomic rename -&gt; fsync
 * containing directory, before the content is advertised as present (the
 * {@code artifacts} row is written only after the rename is durable).</li>
 * <li>Readers never observe partial writes: the final name appears only via
 * atomic rename, and every read re-verifies {@code sha256(bytes)} against
 * the address. A mismatch is a torn tail: the file is best-effort removed
 * and {@link CorruptContentException} is thrown instead of serving bytes.</li>
 * <li>Reference counting plus grace protects live content: collection deletes
 * only {@code refcount = 0} rows past {@link #GC_GRACE} and never deletes a
 * referenced SHA. When reference state is ambiguous (DB error, unexpected
 * file, young orphan without a row) collection retains rather than deletes.
 * </li>
 * <li>Timings: {@code GC_grace=1h}, far longer than test time, so collection
 * is deterministic under test (tests backdate {@code created_at} or file
 * mtime to simulate age).</li>
 * <li>Tenant isolation (issue #20): every artifacts transaction sets
 * transaction-local {@code app.tenant} with a read-back probe; RLS
 * {@code FORCE} enforces {@code tenant_id = current_setting('app.tenant',
 * true)} underneath, and every statement keeps {@code WHERE tenant_id = ?}
 * in addition to RLS. Collection iterates tenants so no pass issues a global
 * artifacts query without a tenant.</li>
 * </ul>
 *
 * <p>Crash model: kill -9 during upload leaves only a tmp file (final name
 * absent, reads miss); during rename the rename is atomic (old or new, never
 * torn); during collection a file-then-row order means a crash leaves at
 * worst a row without a file (reads miss, next collection reclaims the row)
 * or a file without a row (retained through grace, reclaimed by the
 * filesystem sweep).
 */
@Service
public class ContentStore {

    /** Grace window before an unreferenced object becomes collectable. */
    public static final Duration GC_GRACE = Duration.ofHours(1);

    /** Tmp files live beside the final name so rename stays on one filesystem. */
    public static final String TMP_PREFIX = ".tmp-";

    private static final Pattern SHA_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private static final String PUT_UPSERT_SQL = """
            INSERT INTO artifacts (tenant_id, sha256, size_bytes, refcount)
            VALUES (?, ?, ?, 0)
            ON CONFLICT (tenant_id, sha256) DO UPDATE
              SET size_bytes = EXCLUDED.size_bytes,
                  updated_at = now()
            """;

    private static final String ACQUIRE_UPSERT_SQL = """
            INSERT INTO artifacts (tenant_id, sha256, size_bytes, refcount)
            VALUES (?, ?, ?, 1)
            ON CONFLICT (tenant_id, sha256) DO UPDATE
              SET refcount = artifacts.refcount + 1,
                  size_bytes = CASE WHEN artifacts.size_bytes = 0
                                    THEN EXCLUDED.size_bytes
                                    ELSE artifacts.size_bytes END,
                  updated_at = now()
            """;

    private static final String RELEASE_SQL = """
            UPDATE artifacts
            SET refcount = refcount - 1,
                updated_at = now()
            WHERE tenant_id = ? AND sha256 = ? AND refcount > 0
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Path root;

    public ContentStore(JdbcTemplate jdbc, PlatformTransactionManager txManager,
            @Value("${anvil.cas.root:var/cas}") String root) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.root = Paths.get(root);
    }

    /**
     * Durably stores {@code content} for {@code tenantId} and returns its
     * {@code sha256} address. Idempotent: storing the same bytes twice yields
     * the same address without torn reads.
     */
    public String put(String tenantId, byte[] content) throws IOException {
        String tenant = requireTenant(tenantId);
        requireContent(content);
        String sha = sha256Hex(content);

        Path tenantDir = tenantDir(tenant);
        Files.createDirectories(tenantDir);
        Path target = tenantDir.resolve(sha);

        // Fast path: valid content already present, just ensure advertisement.
        if (Files.isRegularFile(target)) {
            try {
                byte[] existing = Files.readAllBytes(target);
                if (sha256Hex(existing).equals(sha)) {
                    tx.execute(status -> {
                        TenantGuard.setTenant(jdbc, tenant);
                        return jdbc.update(PUT_UPSERT_SQL, tenant, sha, (long) content.length);
                    });
                    return sha;
                }
                // Torn tail at the final name: remove before rewriting.
                Files.deleteIfExists(target);
                fsyncDirectory(tenantDir);
            } catch (NoSuchFileException gone) {
                // Raced with collection; fall through to write.
            }
        }

        // Mandatory durability order: tmp-write -> fsync file -> rename -> fsync dir.
        Path tmp = tenantDir.resolve(TMP_PREFIX + UUID.randomUUID() + ".part");
        try (FileChannel channel = FileChannel.open(tmp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(content));
            channel.force(true);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            fsyncDirectory(tenantDir);
        } finally {
            Files.deleteIfExists(tmp);
        }

        tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return jdbc.update(PUT_UPSERT_SQL, tenant, sha, (long) content.length);
        });
        return sha;
    }

    /**
     * Reads the content addressed by {@code shaHex} for {@code tenantId},
     * verifying {@code sha256(bytes)} before returning.
     *
     * @throws NoSuchFileException when no complete object exists at the address.
     * @throws CorruptContentException when the file is a truncated/torn tail;
     *         the file is best-effort removed and never served.
     */
    public byte[] get(String tenantId, String shaHex) throws IOException {
        String tenant = requireTenant(tenantId);
        String sha = requireSha(shaHex);
        Path target = tenantDir(tenant).resolve(sha);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw new NoSuchFileException(address(tenant, sha));
        }
        if (!sha256Hex(bytes).equals(sha)) {
            // Torn tail: remove rather than serve so a later read misses and a
            // later put can heal the address.
            try {
                Files.deleteIfExists(target);
                fsyncDirectory(target.getParent());
            } catch (IOException ignored) {
                // Best-effort: the throw below still prevents serving torn bytes.
            }
            throw new CorruptContentException(
                    "torn content at " + address(tenant, sha) + ": hash mismatch, removed");
        }
        return bytes;
    }

    /**
     * Authenticated content read for issue #22 (C01/C02/C06).
     *
     * <p>Contract: {@code callerTenant} is the authenticated caller (from
     * {@code X-Tenant-Id}); {@code resourceTenant} is the namespace being read.
     * The call denies without content when {@code caller != resource} (C01, C06
     * direct-FS bypass), when no {@code artifacts} row exists for
     * {@code (caller, sha)} under RLS (C02), or when the file is absent. All
     * such denials throw {@link NoSuchFileException} with the caller-scoped
     * address so a cross-tenant probe is indistinguishable from a missing
     * address (no existence oracle). Traversal ({@code ../}, {@code %2e%2e},
     * double-encoding) is rejected by {@link #requireTenant}/
     * {@link #requireSha} with {@link IllegalArgumentException} (C03/C04).
     * The raw {@link #get} stays available for crash recovery (rowless files
     * remain hash-readable); this gateway adds RLS + tenant-match on top.
     */
    public byte[] getForCaller(String callerTenant, String resourceTenant, String shaHex)
            throws IOException {
        String caller = requireTenant(callerTenant);
        String resource = requireTenant(resourceTenant);
        String sha = requireSha(shaHex);
        if (!caller.equals(resource)) {
            throw new NoSuchFileException(address(caller, sha));
        }
        Boolean hasRow = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, caller);
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                    Integer.class, caller, sha);
            return count != null && count > 0;
        });
        if (!Boolean.TRUE.equals(hasRow)) {
            throw new NoSuchFileException(address(caller, sha));
        }
        return get(caller, sha);
    }

    /**
     * Increments the reference count for {@code (tenant, sha)}, creating the
     * row with {@code refcount = 1} when absent. Live (referenced) rows are
     * never collected.
     */
    public void acquire(String tenantId, String shaHex, long sizeBytes) {
        String tenant = requireTenant(tenantId);
        String sha = requireSha(shaHex);
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return jdbc.update(ACQUIRE_UPSERT_SQL, tenant, sha, sizeBytes);
        });
    }

    /**
     * Decrements the reference count when held.
     *
     * @return {@code true} when exactly one row was decremented.
     */
    public boolean release(String tenantId, String shaHex) {
        String tenant = requireTenant(tenantId);
        String sha = requireSha(shaHex);
        Boolean ok = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return jdbc.update(RELEASE_SQL, tenant, sha) == 1;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Single conservative collection pass.
     *
     * <p>Phase 1 reclaims database-advertised orphans: rows with
     * {@code refcount = 0} older than {@link #GC_GRACE} (by the database
     * clock). Each candidate is re-checked with {@code FOR UPDATE}; a row
     * that became referenced is retained. The file is deleted first, then
     * the row, so a crash leaves at worst a row without a file (reclaimed on
     * the next pass) and never deletes a referenced SHA.
     *
     * <p>Phase 2 sweeps the filesystem: files without a database row are
     * orphans from a crash between rename and advertisement. Only files older
     * than {@link #GC_GRACE} (by file mtime) are removed; young files are
     * retained through grace. Tmp files older than grace are removed. Any
     * ambiguity (DB error, unexpected name, unparseable tenant) retains.
     */
    public GcResult collectGarbage() throws IOException {
        int filesDeleted = 0;
        long bytesReclaimed = 0L;
        int rowsDeleted = 0;
        int tmpDeleted = 0;

        // Per-tenant candidates: one transaction cannot hold two tenants, and a
        // global artifacts query without tenant context would fail closed (zero
        // rows) under RLS. Tenants table has no RLS so listing is safe.
        List<String> tenants = jdbc.query("SELECT id FROM tenants", (rs, i) -> rs.getString("id"));
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (String listTenant : tenants) {
            final String lt = listTenant;
            List<Map<String, Object>> one = tx.execute(status -> {
                TenantGuard.setTenant(jdbc, lt);
                return jdbc.queryForList("""
                        SELECT tenant_id, sha256 FROM artifacts
                        WHERE tenant_id = ?
                          AND refcount = 0
                          AND created_at <= clock_timestamp() - INTERVAL '1 hour'
                        """, lt);
            });
            if (one != null) {
                candidates.addAll(one);
            }
        }
        for (Map<String, Object> candidate : candidates) {
            String tenant = (String) candidate.get("tenant_id");
            String sha = (String) candidate.get("sha256");
            Reclaim reclaim = tx.execute(status -> {
                TenantGuard.setTenant(jdbc, tenant);
                List<Map<String, Object>> locked = jdbc.queryForList(
                        "SELECT size_bytes FROM artifacts "
                                + "WHERE tenant_id = ? AND sha256 = ? AND refcount = 0 "
                                + "AND created_at <= clock_timestamp() - INTERVAL '1 hour' FOR UPDATE",
                        tenant, sha);
                if (locked.isEmpty()) {
                    return null;
                }
                long reclaimed = 0L;
                boolean fileGone = false;
                try {
                    Path target;
                    try {
                        target = tenantDir(tenant).resolve(requireSha(sha));
                    } catch (IllegalArgumentException bad) {
                        return null;
                    }
                    try {
                        if (Files.isRegularFile(target)) {
                            reclaimed = Files.size(target);
                            Files.deleteIfExists(target);
                            fsyncDirectory(target.getParent());
                            fileGone = true;
                        }
                    } catch (NoSuchFileException gone) {
                        // Already gone; still reclaim the row below.
                    } catch (IOException io) {
                        status.setRollbackOnly();
                        return null;
                    }
                } catch (RuntimeException e) {
                    status.setRollbackOnly();
                    throw e;
                }
                int rows = jdbc.update(
                        "DELETE FROM artifacts WHERE tenant_id = ? AND sha256 = ?", tenant, sha);
                if (rows == 0) {
                    status.setRollbackOnly();
                    return null;
                }
                return new Reclaim(fileGone, reclaimed);
            });
            if (reclaim != null) {
                if (reclaim.fileGone()) {
                    filesDeleted++;
                    bytesReclaimed += reclaim.bytes();
                }
                rowsDeleted++;
            }
        }

        Sweep sweep = sweepFilesystem();
        filesDeleted += sweep.filesDeleted();
        bytesReclaimed += sweep.bytesReclaimed();
        rowsDeleted += sweep.rowsDeleted();
        tmpDeleted += sweep.tmpDeleted();

        return new GcResult(filesDeleted, bytesReclaimed, rowsDeleted, tmpDeleted);
    }

    /** Filesystem address for debugging and not-found messages. */
    public String address(String tenantId, String shaHex) {
        return "tenants/" + tenantId + "/" + shaHex;
    }

    /** Resolved final path for {@code (tenant, sha)}; public for crash tests. */
    public Path pathFor(String tenantId, String shaHex) {
        return tenantDir(requireTenant(tenantId)).resolve(requireSha(shaHex));
    }

    /** Content root's tenant directory; public for crash tests. */
    public Path tenantDir(String tenantId) {
        return root.resolve("tenants").resolve(requireTenant(tenantId));
    }

    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record Reclaim(boolean fileGone, long bytes) {
    }

    private record Sweep(int filesDeleted, long bytesReclaimed, int rowsDeleted, int tmpDeleted) {
    }

    private Sweep sweepFilesystem() {
        Path tenants = root.resolve("tenants");
        if (!Files.isDirectory(tenants)) {
            return new Sweep(0, 0L, 0, 0);
        }
        int filesDeleted = 0;
        long bytesReclaimed = 0L;
        int tmpDeleted = 0;
        Instant cutoff = Instant.now().minus(GC_GRACE);
        List<Path> tenantDirs = new ArrayList<>();
        try (DirectoryStream<Path> tenantsStream = Files.newDirectoryStream(tenants)) {
            for (Path tenantDir : tenantsStream) {
                if (!Files.isDirectory(tenantDir)) {
                    continue;
                }
                tenantDirs.add(tenantDir);
            }
        } catch (IOException e) {
            // Ambiguous: retain everything.
            return new Sweep(0, 0L, 0, 0);
        }
        for (Path tenantDir : tenantDirs) {
            List<Path> entries = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tenantDir)) {
                for (Path entry : stream) {
                    entries.add(entry);
                }
            } catch (IOException e) {
                continue;
            }
            for (Path entry : entries) {
                String name;
                try {
                    if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    name = entry.getFileName().toString();
                } catch (SecurityException e) {
                    continue;
                }
                try {
                    if (name.startsWith(TMP_PREFIX) || name.endsWith(".part") || name.endsWith(".tmp")) {
                        if (isOlderThan(entry, cutoff)) {
                            long size = sizeOrZero(entry);
                            Files.deleteIfExists(entry);
                            tmpDeleted++;
                            bytesReclaimed += size;
                        }
                        continue;
                    }
                    if (!SHA_PATTERN.matcher(name).matches()) {
                        continue;
                    }
                    String tenant = tenantDir.getFileName().toString();
                    boolean hasRow;
                    try {
                        // Tenant-scoped existence check inside its own transaction
                        // with SET LOCAL: without context RLS returns zero rows,
                        // which would look like "no row" and risk deleting a live
                        // file, so the context is mandatory here.
                        final String checkTenant = tenant;
                        final String checkSha = name;
                        Integer count = tx.execute(status -> {
                            try {
                                TenantGuard.setTenant(jdbc, checkTenant);
                            } catch (IllegalArgumentException badTenant) {
                                status.setRollbackOnly();
                                return null;
                            }
                            return jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                                    Integer.class, checkTenant, checkSha);
                        });
                        if (count == null) {
                            // Ambiguous (bad tenant name or rolled-back check):
                            // retain rather than delete.
                            continue;
                        }
                        hasRow = count > 0;
                    } catch (RuntimeException e) {
                        // Ambiguous reference state: retain rather than delete.
                        continue;
                    }
                    if (hasRow) {
                        continue;
                    }
                    // File without a row: crash orphan. Grace protects a
                    // just-renamed file whose row has not been written yet.
                    if (isOlderThan(entry, cutoff)) {
                        long size = sizeOrZero(entry);
                        Files.deleteIfExists(entry);
                        filesDeleted++;
                        bytesReclaimed += size;
                    }
                } catch (IOException e) {
                    // Per-file ambiguity: retain and continue sweeping.
                    continue;
                }
            }
            try {
                fsyncDirectory(tenantDir);
            } catch (IOException ignored) {
                // Best-effort durability of deletions.
            }
        }
        return new Sweep(filesDeleted, bytesReclaimed, 0, tmpDeleted);
    }

    private static long sizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean isOlderThan(Path file, Instant cutoff) {
        try {
            FileTime mtime = Files.getLastModifiedTime(file);
            return mtime.toInstant().isBefore(cutoff);
        } catch (IOException e) {
            // Cannot determine age: retain (conservative).
            return false;
        }
    }

    /**
     * Forces the containing directory so the rename (or delete) survives a
     * crash. Mandatory on Linux; best-effort on platforms that cannot open a
     * directory channel (Windows).
     *
     * <p>Public so crash tests can reproduce the durable-rename sequence
     * without going through {@link #put}.
     */
    public static void fsyncDirectory(Path dir) throws IOException {
        if (dir == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException e) {
            if (isWindows()) {
                return;
            }
            throw e;
        } catch (UnsupportedOperationException e) {
            if (isWindows()) {
                return;
            }
            throw new IOException("directory fsync unsupported", e);
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        String tenant = tenantId.trim();
        if (tenant.contains("/") || tenant.contains("\\") || tenant.contains("..")) {
            throw new IllegalArgumentException("tenantId escapes its directory");
        }
        // Encoded traversal (issue #22 C04): reject '%' so '%2e%2e', '%252e' and
        // any double-encoding never reaches the filesystem, even before decoding.
        if (tenant.contains("%")) {
            throw new IllegalArgumentException("tenantId contains encoded traversal");
        }
        if (tenant.equals(".") || tenant.equalsIgnoreCase("null")) {
            throw new IllegalArgumentException("tenantId is reserved");
        }
        return tenant;
    }

    static String requireSha(String shaHex) {
        if (shaHex == null) {
            throw new IllegalArgumentException("sha256 must not be null");
        }
        String sha = shaHex.trim().toLowerCase(java.util.Locale.ROOT);
        if (!SHA_PATTERN.matcher(sha).matches()) {
            throw new IllegalArgumentException("sha256 must be lowercase hex [0-9a-f]{64}");
        }
        return sha;
    }

    private static void requireContent(byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
    }
}
