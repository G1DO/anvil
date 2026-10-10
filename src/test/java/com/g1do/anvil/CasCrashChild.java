package com.g1do.anvil;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import com.g1do.anvil.cas.ContentStore;

/**
 * Real-crash CAS worker for issue #14.
 *
 * <p>Replicates the production durability order file-by-file without ever
 * touching the {@code artifacts} table, so a SIGKILL at the sleep point
 * leaves exactly the post-crash filesystem a real {@code kill -9} would:
 *
 * <ul>
 * <li>{@code tmp-write}: partial tmp bytes only, final name absent (kill
 * mid-upload before fsync/rename).</li>
 * <li>{@code rename-before-row}: full bytes durably renamed and
 * directory-fsynced, {@code artifacts} row still absent (kill after rename
 * before advertisement).</li>
 * <li>{@code gc-file-before-row}: final file deleted and directory-fsynced,
 * {@code artifacts} row still present (kill during collection after the
 * file delete before the row delete; collection deletes file-then-row).</li>
 * </ul>
 *
 * <p>Kill mechanism is the parent's choice ({@code kill -9} when available,
 * else {@code Process.destroyForcibly}); this process only sleeps awaiting
 * it. Usage:
 * {@code CasCrashChild tmp-write|rename-before-row <root> <tenant> <content>}
 * or {@code CasCrashChild gc-file-before-row <root> <tenant> <sha>}.
 */
public class CasCrashChild {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println(
                    "usage: CasCrashChild tmp-write|rename-before-row <root> <tenant> <content>"
                            + " | CasCrashChild gc-file-before-row <root> <tenant> <sha>");
            System.exit(2);
            return;
        }
        String mode = args[0];
        Path root = Paths.get(args[1]);
        String tenant = args[2];
        Path tenantDir = root.resolve("tenants").resolve(tenant);
        Files.createDirectories(tenantDir);

        switch (mode) {
            case "tmp-write" -> {
                byte[] full = args[3].getBytes(StandardCharsets.UTF_8);
                byte[] partial = Arrays.copyOf(full, Math.max(1, full.length / 2));
                Path tmp = tenantDir.resolve(ContentStore.TMP_PREFIX + UUID.randomUUID() + ".part");
                Files.write(tmp, partial);
                System.out.println("READY " + tmp.getFileName());
                System.out.flush();
                Thread.sleep(60_000L);
                System.out.println("TIMEOUT_WITHOUT_KILL");
                System.out.flush();
                System.exit(4);
            }
            case "rename-before-row" -> {
                byte[] full = args[3].getBytes(StandardCharsets.UTF_8);
                String sha = ContentStore.sha256Hex(full);
                Path tmp = tenantDir.resolve(ContentStore.TMP_PREFIX + UUID.randomUUID() + ".part");
                try (FileChannel channel = FileChannel.open(tmp,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    channel.write(ByteBuffer.wrap(full));
                    channel.force(true);
                }
                Path target = tenantDir.resolve(sha);
                try {
                    Files.move(tmp, target,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                ContentStore.fsyncDirectory(tenantDir);
                Files.deleteIfExists(tmp);
                System.out.println("READY " + sha);
                System.out.flush();
                Thread.sleep(60_000L);
                System.out.println("TIMEOUT_WITHOUT_KILL");
                System.out.flush();
                System.exit(4);
            }
            case "gc-file-before-row" -> {
                String sha = args[3].trim().toLowerCase(java.util.Locale.ROOT);
                Path target = tenantDir.resolve(sha);
                Files.deleteIfExists(target);
                try {
                    ContentStore.fsyncDirectory(tenantDir);
                } catch (Exception ignored) {
                    // Best-effort like the store itself.
                }
                System.out.println("READY " + sha);
                System.out.flush();
                Thread.sleep(60_000L);
                System.out.println("TIMEOUT_WITHOUT_KILL");
                System.out.flush();
                System.exit(4);
            }
            default -> {
                System.err.println("unknown mode: " + mode);
                System.exit(2);
            }
        }
    }
}
