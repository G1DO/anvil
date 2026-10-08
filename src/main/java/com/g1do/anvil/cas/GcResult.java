package com.g1do.anvil.cas;

/**
 * Counts from a single garbage-collection pass.
 *
 * @param filesDeleted    tenant content files removed from disk.
 * @param bytesReclaimed  sum of reclaimed file sizes in bytes.
 * @param rowsDeleted     artifact rows removed from the database.
 * @param tmpFilesDeleted crash-orphaned temporary files removed.
 */
public record GcResult(int filesDeleted, long bytesReclaimed, int rowsDeleted, int tmpFilesDeleted) {

    public static GcResult empty() {
        return new GcResult(0, 0L, 0, 0);
    }
}
