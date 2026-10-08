package com.g1do.anvil.jobs;

/**
 * Counts from a single reconciler pass.
 *
 * @param releasedToQueued jobs moved {@code RUNNING -> QUEUED} after an
 *                         expired lease with attempts remaining; they become
 *                         claimable again with a new fencing token on next
 *                         claim.
 * @param markedDead       jobs moved to {@code DEAD} because
 *                         {@code attempt >= max_attempts} (queued exhausted
 *                         plus expired running exhausted); they are never
 *                         re-offered.
 */
public record ReconcileResult(int releasedToQueued, int markedDead) {
}
