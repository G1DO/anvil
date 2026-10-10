package com.g1do.anvil;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.g1do.anvil.cas.ContentStore;
import com.g1do.anvil.cas.CorruptContentException;
import com.g1do.anvil.cas.GcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ContentStoreTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager txManager;

    @TempDir
    Path tempDir;

    private ContentStore store;

    @BeforeEach
    void clean() throws Exception {
        jdbc.execute("DELETE FROM processed_events");
        jdbc.execute("DELETE FROM outbox");
        jdbc.execute("DELETE FROM attempts");
        jdbc.execute("DELETE FROM jobs");
        jdbc.execute("DELETE FROM document_versions");
        jdbc.execute("DELETE FROM documents");
        jdbc.execute("DELETE FROM artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
        store = new ContentStore(jdbc, txManager, tempDir.toString());
    }

    private void ensureTenant(String tenant) {
        jdbc.update("INSERT INTO tenants (id) VALUES (?) ON CONFLICT DO NOTHING", tenant);
    }

    private void backdateArtifact(String tenant, String sha, String interval) {
        jdbc.update("UPDATE artifacts SET created_at = clock_timestamp() - INTERVAL '" + interval
                + "' WHERE tenant_id = ? AND sha256 = ?", tenant, sha);
    }

    private int refcountOf(String tenant, String sha) {
        Integer ref = jdbc.queryForObject(
                "SELECT refcount FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                Integer.class, tenant, sha);
        return ref == null ? -1 : ref;
    }

    private int artifactRows(String tenant, String sha) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                Integer.class, tenant, sha);
        return count == null ? 0 : count;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(bytes));
    }

    @Test
    void shaAddressAndPerTenantPathsWithNoCrossTenantDedup() throws Exception {
        ensureTenant("t_other");
        byte[] bytes = "same-bytes-both-tenants".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String shaSingle = store.put("t_single", bytes);
        String shaOther = store.put("t_other", bytes);

        assertThat(shaOther).isEqualTo(shaSingle);
        assertThat(shaSingle).isEqualTo(sha256Hex(bytes));
        assertThat(shaSingle).matches("^[0-9a-f]{64}$");

        Path singlePath = store.pathFor("t_single", shaSingle);
        Path otherPath = store.pathFor("t_other", shaOther);
        assertThat(singlePath).isNotEqualTo(otherPath);
        assertThat(Files.isRegularFile(singlePath)).isTrue();
        assertThat(Files.isRegularFile(otherPath)).isTrue();
        assertThat(singlePath.toString()).contains("tenants" + java.io.File.separator + "t_single");
        assertThat(otherPath.toString()).contains("tenants" + java.io.File.separator + "t_other");

        // Independent files: corrupting one tenant does not affect the other.
        assertThat(store.get("t_single", shaSingle)).isEqualTo(bytes);
        assertThat(store.get("t_other", shaOther)).isEqualTo(bytes);
        assertThat(artifactRows("t_single", shaSingle)).isEqualTo(1);
        assertThat(artifactRows("t_other", shaOther)).isEqualTo(1);
    }

    @Test
    void durableWriteIsAddressableWithHashVerification() throws Exception {
        byte[] bytes = "durable-artifact-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = store.put("t_single", bytes);

        assertThat(sha).isEqualTo(sha256Hex(bytes));
        assertThat(store.get("t_single", sha)).isEqualTo(bytes);

        Path target = store.pathFor("t_single", sha);
        assertThat(Files.readAllBytes(target)).isEqualTo(bytes);

        Long size = jdbc.queryForObject(
                "SELECT size_bytes FROM artifacts WHERE tenant_id = 't_single' AND sha256 = ?",
                Long.class, sha);
        assertThat(size).isEqualTo((long) bytes.length);
        assertThat(refcountOf("t_single", sha)).isEqualTo(0);

        // Idempotent second put yields the same address without torn reads.
        String again = store.put("t_single", bytes);
        assertThat(again).isEqualTo(sha);
        assertThat(store.get("t_single", sha)).isEqualTo(bytes);
    }

    @Test
    void crashDuringUploadLeavesNoTornRead() throws Exception {
        Path tenantDir = store.tenantDir("t_single");
        Files.createDirectories(tenantDir);

        // Simulate kill -9 mid-upload: partial tmp only, final name absent.
        byte[] full = "complete-artifact-after-crash".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = sha256Hex(full);
        Path tmp = tenantDir.resolve(ContentStore.TMP_PREFIX + "crash-upload.part");
        Files.write(tmp, "partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // Reader never observes the partial write: final address simply misses.
        assertThatThrownBy(() -> store.get("t_single", sha))
                .isInstanceOf(NoSuchFileException.class);
        assertThat(Files.exists(store.pathFor("t_single", sha))).isFalse();

        // Restart (new instance, same root) then complete the upload.
        ContentStore restarted = new ContentStore(jdbc, txManager, tempDir.toString());
        String stored = restarted.put("t_single", full);
        assertThat(stored).isEqualTo(sha);
        assertThat(restarted.get("t_single", sha)).isEqualTo(full);
        assertThat(sha256Hex(restarted.get("t_single", sha))).isEqualTo(sha);
    }

    @Test
    void crashAfterRenameBeforeAdvertisementStillReadsBack() throws Exception {
        byte[] bytes = "renamed-but-unadvertised".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = sha256Hex(bytes);

        // Simulate kill -9 after durable rename but before the artifacts row:
        // file is fully renamed and fsynced, DB row is absent.
        Path tenantDir = store.tenantDir("t_single");
        Files.createDirectories(tenantDir);
        Path tmp = tenantDir.resolve(ContentStore.TMP_PREFIX + UUID.randomUUID() + ".part");
        Files.write(tmp, bytes);
        ContentStore.fsyncDirectory(tenantDir);
        Path target = tenantDir.resolve(sha);
        Files.move(tmp, target,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        ContentStore.fsyncDirectory(tenantDir);
        assertThat(artifactRows("t_single", sha)).isEqualTo(0);

        // Restart: hash verification passes, zero torn reads.
        ContentStore restarted = new ContentStore(jdbc, txManager, tempDir.toString());
        assertThat(restarted.get("t_single", sha)).isEqualTo(bytes);
        assertThat(sha256Hex(restarted.get("t_single", sha))).isEqualTo(sha);

        // Completing the put advertises the row without rewriting torn bytes.
        assertThat(restarted.put("t_single", bytes)).isEqualTo(sha);
        assertThat(artifactRows("t_single", sha)).isEqualTo(1);
    }

    @Test
    void crashDuringCollectionLeavesNoTornRead() throws Exception {
        byte[] bytes = "gc-crash-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = store.put("t_single", bytes);
        backdateArtifact("t_single", sha, "2 hours");

        // Simulate kill -9 during GC after the file delete but before row delete.
        Files.deleteIfExists(store.pathFor("t_single", sha));
        assertThat(artifactRows("t_single", sha)).isEqualTo(1);

        // Restart: read misses (never torn), next collection reclaims the row.
        ContentStore restarted = new ContentStore(jdbc, txManager, tempDir.toString());
        assertThatThrownBy(() -> restarted.get("t_single", sha))
                .isInstanceOf(NoSuchFileException.class);
        GcResult gc = restarted.collectGarbage();
        assertThat(gc.rowsDeleted()).isEqualTo(1);
        assertThat(artifactRows("t_single", sha)).isEqualTo(0);
        assertThatThrownBy(() -> restarted.get("t_single", sha))
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void killDuringTmpWriteLeavesNoTornRead() throws Exception {
        // System-level crash proof (issue #14): a real subprocess is SIGKILLed
        // mid-upload. CasCrashChild writes only a partial tmp file, prints
        // READY, then sleeps 60s instead of fsync/rename/row. The parent kills
        // it with `kill -9` when available, else destroyForcibly() (SIGKILL on
        // Unix, TerminateProcess on Windows); neither runs cleanup, matching
        // a real kill -9. Post-crash state equals a real crash because the
        // final name appears only via atomic rename, which never ran: the
        // reader observes absence, never partial bytes. CAS order
        // (tmp-write in dest dir -> fsync file -> atomic rename -> fsync dir,
        // then artifacts row) and per-tenant sha256 addressing are unchanged.
        String fullString = "complete-artifact-after-kill";
        byte[] full = fullString.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(full);
        Path tenantDir = store.tenantDir("t_single");
        Files.createDirectories(tenantDir);

        Process child = startCasChild("tmp-write", "t_single", fullString);
        try {
            waitForTmpFile(tenantDir, 30);
            killNineOrDestroy(child);
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isNotZero();

            // Final address simply misses; no torn read.
            assertThat(Files.exists(store.pathFor("t_single", sha))).isFalse();
            ContentStore restarted = new ContentStore(jdbc, txManager, tempDir.toString());
            assertThatThrownBy(() -> restarted.get("t_single", sha))
                    .isInstanceOf(NoSuchFileException.class);

            // Completing the upload heals with hash-verified bytes.
            assertThat(restarted.put("t_single", full)).isEqualTo(sha);
            assertThat(restarted.get("t_single", sha)).isEqualTo(full);
            assertThat(sha256Hex(restarted.get("t_single", sha))).isEqualTo(sha);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void killAfterRenameBeforeAdvertisementStillReadsBack() throws Exception {
        // System-level crash proof (issue #14): SIGKILL after durable rename
        // but before the artifacts row. CasCrashChild replicates put's
        // tmp-write -> fsync file -> atomic rename -> fsync dir, prints READY,
        // then sleeps instead of inserting the row. Killing it leaves a fully
        // renamed, fsynced file with no DB row — the same durable state a
        // real kill -9 between rename and advertisement would leave. Restart
        // hash-verifies zero torn bytes; put then advertises the row.
        String contentString = "renamed-but-unadvertised-via-kill";
        byte[] bytes = contentString.getBytes(StandardCharsets.UTF_8);
        String sha = sha256Hex(bytes);

        Process child = startCasChild("rename-before-row", "t_single", contentString);
        try {
            waitForFinalFile("t_single", sha, 30);
            killNineOrDestroy(child);
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isNotZero();

            assertThat(artifactRows("t_single", sha)).isEqualTo(0);
            ContentStore restarted = new ContentStore(jdbc, txManager, tempDir.toString());
            assertThat(restarted.get("t_single", sha)).isEqualTo(bytes);
            assertThat(sha256Hex(restarted.get("t_single", sha))).isEqualTo(sha);

            assertThat(restarted.put("t_single", bytes)).isEqualTo(sha);
            assertThat(artifactRows("t_single", sha)).isEqualTo(1);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void killDuringCollectionWithLiveReferencesReclaimsOnlyOrphans() throws Exception {
        // System-level crash proof (issue #14): SIGKILL during collection
        // (file deleted, row not yet deleted) plus orphan-GC invariants with
        // live references present. Collection deletes file-then-row, so a
        // crash leaves at worst a row without a file (reads miss, next pass
        // reclaims the row) and never deletes a referenced SHA. CasCrashChild
        // deletes the orphan file, prints READY, then sleeps instead of
        // deleting the row; the parent kills it, then a fresh ContentStore on
        // the same root collects. Referenced past-grace survives, genuinely
        // orphaned past-grace is reclaimed, young is retained.
        byte[] liveBytes = "live-referenced-via-kill".getBytes(StandardCharsets.UTF_8);
        byte[] orphanOldBytes = "orphan-old-via-kill".getBytes(StandardCharsets.UTF_8);
        byte[] orphanYoungBytes = "orphan-young-via-kill".getBytes(StandardCharsets.UTF_8);

        String live = store.put("t_single", liveBytes);
        store.acquire("t_single", live, liveBytes.length);
        backdateArtifact("t_single", live, "2 hours");

        String orphanOld = store.put("t_single", orphanOldBytes);
        backdateArtifact("t_single", orphanOld, "2 hours");

        String orphanYoung = store.put("t_single", orphanYoungBytes);

        Process child = startCasChild("gc-file-before-row", "t_single", orphanOld);
        try {
            waitForNoFinalFile("t_single", orphanOld, 30);
            killNineOrDestroy(child);
            assertThat(child.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(child.exitValue()).isNotZero();

            // Crash mid-collection: file gone, row still present, read misses.
            assertThat(Files.exists(store.pathFor("t_single", orphanOld))).isFalse();
            assertThat(artifactRows("t_single", orphanOld)).isEqualTo(1);
            ContentStore restarted = new ContentStore(jdbc, txManager, tempDir.toString());
            assertThatThrownBy(() -> restarted.get("t_single", orphanOld))
                    .isInstanceOf(NoSuchFileException.class);

            GcResult gc = restarted.collectGarbage();

            // Live reference never deleted even though past grace.
            assertThat(Files.exists(store.pathFor("t_single", live))).isTrue();
            assertThat(artifactRows("t_single", live)).isEqualTo(1);
            assertThat(restarted.get("t_single", live)).isEqualTo(liveBytes);

            // Genuinely orphaned past-grace reclaimed (row + file).
            assertThat(Files.exists(store.pathFor("t_single", orphanOld))).isFalse();
            assertThat(artifactRows("t_single", orphanOld)).isEqualTo(0);

            // Young orphan retained through grace.
            assertThat(Files.exists(store.pathFor("t_single", orphanYoung))).isTrue();
            assertThat(artifactRows("t_single", orphanYoung)).isEqualTo(1);

            assertThat(gc.rowsDeleted()).isGreaterThanOrEqualTo(1);
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }

    @Test
    void tornTailDetectedAndRemovedRatherThanServed() throws Exception {
        byte[] bytes = "valid-content-that-will-tear".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = store.put("t_single", bytes);
        Path target = store.pathFor("t_single", sha);

        // Simulate a truncated torn tail written directly at the final name
        // (something the store itself never does: it only renames complete files).
        byte[] torn = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        Files.write(target, torn,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);

        assertThatThrownBy(() -> store.get("t_single", sha))
                .isInstanceOf(CorruptContentException.class)
                .hasMessageContaining("torn");
        // Removed rather than served: second read misses instead of returning torn bytes.
        assertThat(Files.exists(target)).isFalse();
        assertThatThrownBy(() -> store.get("t_single", sha))
                .isInstanceOf(NoSuchFileException.class);

        // Address heals on next put.
        assertThat(store.put("t_single", bytes)).isEqualTo(sha);
        assertThat(store.get("t_single", sha)).isEqualTo(bytes);
    }

    @Test
    void orphanCollectionDeletesOnlyUnreferencedPastGraceAndNeverReferenced() throws Exception {
        byte[] liveBytes = "live-referenced".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] orphanOldBytes = "orphan-old".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] orphanYoungBytes = "orphan-young".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        String live = store.put("t_single", liveBytes);
        store.acquire("t_single", live, liveBytes.length);
        backdateArtifact("t_single", live, "2 hours");

        String orphanOld = store.put("t_single", orphanOldBytes);
        backdateArtifact("t_single", orphanOld, "2 hours");

        String orphanYoung = store.put("t_single", orphanYoungBytes);

        // Orphan file without a row, old enough to be a crash leftover.
        byte[] rowlessOldBytes = "rowless-old-orphan".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String rowlessOldSha = sha256Hex(rowlessOldBytes);
        Path rowlessOld = store.pathFor("t_single", rowlessOldSha);
        Files.createDirectories(rowlessOld.getParent());
        Files.write(rowlessOld, rowlessOldBytes);
        Files.setLastModifiedTime(rowlessOld,
                FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        // Orphan file without a row, young: crash may have just happened, retain.
        byte[] rowlessYoungBytes = "rowless-young-orphan".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String rowlessYoungSha = sha256Hex(rowlessYoungBytes);
        Path rowlessYoung = store.pathFor("t_single", rowlessYoungSha);
        Files.write(rowlessYoung, rowlessYoungBytes);

        GcResult gc = store.collectGarbage();

        // Live reference is never deleted even though it is past grace.
        assertThat(Files.exists(store.pathFor("t_single", live))).isTrue();
        assertThat(artifactRows("t_single", live)).isEqualTo(1);
        assertThat(store.get("t_single", live)).isEqualTo(liveBytes);

        // Genuinely orphaned bytes past grace are reclaimed (row + file).
        assertThat(Files.exists(store.pathFor("t_single", orphanOld))).isFalse();
        assertThat(artifactRows("t_single", orphanOld)).isEqualTo(0);
        assertThat(Files.exists(rowlessOld)).isFalse();

        // Young orphans are retained through grace (row-backed and rowless).
        assertThat(Files.exists(store.pathFor("t_single", orphanYoung))).isTrue();
        assertThat(artifactRows("t_single", orphanYoung)).isEqualTo(1);
        assertThat(Files.exists(rowlessYoung)).isTrue();

        assertThat(gc.filesDeleted()).isGreaterThanOrEqualTo(2);
        assertThat(gc.rowsDeleted()).isEqualTo(1);
        assertThat(gc.bytesReclaimed()).isGreaterThan(0L);
    }

    @Test
    void graceProtectsYoungUnreferencedRows() throws Exception {
        byte[] bytes = "young-orphan".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = store.put("t_single", bytes);

        GcResult gc = store.collectGarbage();

        assertThat(gc.filesDeleted()).isEqualTo(0);
        assertThat(gc.rowsDeleted()).isEqualTo(0);
        assertThat(Files.exists(store.pathFor("t_single", sha))).isTrue();
        assertThat(store.get("t_single", sha)).isEqualTo(bytes);
    }

    @Test
    void tmpFilesCleanedOnlyPastGrace() throws Exception {
        Path tenantDir = store.tenantDir("t_single");
        Files.createDirectories(tenantDir);

        Path oldTmp = tenantDir.resolve(ContentStore.TMP_PREFIX + "old-crash.part");
        Files.write(oldTmp, "tmp-old".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.setLastModifiedTime(oldTmp,
                FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        Path youngTmp = tenantDir.resolve(ContentStore.TMP_PREFIX + "young-upload.part");
        Files.write(youngTmp, "tmp-young".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        GcResult gc = store.collectGarbage();

        assertThat(Files.exists(oldTmp)).isFalse();
        assertThat(Files.exists(youngTmp)).isTrue();
        assertThat(gc.tmpFilesDeleted()).isEqualTo(1);
    }

    @Test
    void referenceCountingDrivesGcEligibility() throws Exception {
        byte[] bytes = "refcounted".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = store.put("t_single", bytes);
        store.acquire("t_single", sha, bytes.length);
        store.acquire("t_single", sha, bytes.length);
        assertThat(refcountOf("t_single", sha)).isEqualTo(2);
        backdateArtifact("t_single", sha, "2 hours");

        // Still referenced: retained.
        assertThat(store.collectGarbage().rowsDeleted()).isEqualTo(0);
        assertThat(Files.exists(store.pathFor("t_single", sha))).isTrue();

        assertThat(store.release("t_single", sha)).isTrue();
        assertThat(refcountOf("t_single", sha)).isEqualTo(1);
        assertThat(store.collectGarbage().rowsDeleted()).isEqualTo(0);

        assertThat(store.release("t_single", sha)).isTrue();
        assertThat(refcountOf("t_single", sha)).isEqualTo(0);
        // Release below zero is a no-op.
        assertThat(store.release("t_single", sha)).isFalse();

        GcResult gc = store.collectGarbage();
        assertThat(gc.rowsDeleted()).isEqualTo(1);
        assertThat(Files.exists(store.pathFor("t_single", sha))).isFalse();
    }

    @Test
    void rejectsTraversalAndBadShaWithoutEscapingRoot() {
        assertThatThrownBy(() -> store.put("../evil", "x".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("a/b", "x".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get("t_single", "not-a-sha"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.get("t_single", "z".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);

        // No escape: nothing was created outside the root.
        assertThat(tempDir.resolve("evil")).doesNotExist();
    }

    @Test
    void gcGraceIsOneHour() {
        assertThat(ContentStore.GC_GRACE).isEqualTo(Duration.ofHours(1));
    }

    private Process startCasChild(String mode, String tenant, String arg) throws Exception {
        String javaBin = javaBinary();
        String classpath = System.getProperty("java.class.path");
        Process child = new ProcessBuilder(
                javaBin, "-cp", classpath,
                CasCrashChild.class.getName(),
                mode, tempDir.toString(), tenant, arg)
                .redirectErrorStream(true)
                .start();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                    // Drain to avoid blocking the child; diagnostics only.
                }
            } catch (Exception ignored) {
                // Best-effort.
            }
        }, "cas-crash-drain");
        drain.setDaemon(true);
        drain.start();
        return child;
    }

    private void waitForTmpFile(Path tenantDir, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tenantDir)) {
                for (Path entry : stream) {
                    String name = entry.getFileName().toString();
                    if (name.startsWith(ContentStore.TMP_PREFIX)) {
                        return;
                    }
                }
            } catch (Exception ignored) {
                // Tenant dir may not exist yet; keep polling.
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("timed out waiting for crash child tmp file");
    }

    private void waitForFinalFile(String tenant, String sha, int timeoutSeconds) throws Exception {
        Path target = store.pathFor(tenant, sha);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(target)) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("timed out waiting for crash child final file " + sha);
    }

    private void waitForNoFinalFile(String tenant, String sha, int timeoutSeconds) throws Exception {
        Path target = store.pathFor(tenant, sha);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (!Files.exists(target)) {
                return;
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("timed out waiting for crash child to delete " + sha);
    }

    private static String javaBinary() {
        String home = System.getProperty("java.home");
        String bin = home + File.separator + "bin" + File.separator + "java";
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            bin += ".exe";
        }
        return bin;
    }

    private static void killNineOrDestroy(Process process) throws Exception {
        // Prefer `kill -9` (SIGKILL, uncatchable) when available; else
        // destroyForcibly() (SIGKILL on Unix, TerminateProcess on Windows).
        // Both skip finally/JDBC cleanup, matching a real crash.
        long pid = process.pid();
        try {
            Process kill = new ProcessBuilder("kill", "-9", Long.toString(pid)).start();
            kill.waitFor(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50L);
            }
        } catch (Exception unavailable) {
            // No `kill` binary: fall through to destroyForcibly.
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
    }
}
