package com.g1do.anvil.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Single claimed job: the new owner, fencing token and attempt after the
 * atomic claim.
 *
 * <p>The {@code fencingToken} must be presented on every subsequent
 * heartbeat/commit; a stale token receives zero updated rows and causes no
 * state change. {@code attempt} is the 1-based claim count for this job and
 * maps to {@code attempts.attempt_number} for the ledger row written in the
 * same transaction.
 */
public record ClaimedJob(
        UUID jobId,
        String owner,
        long fencingToken,
        int attempt,
        OffsetDateTime leaseExpiresAt) {
}
