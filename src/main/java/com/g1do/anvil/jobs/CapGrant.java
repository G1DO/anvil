package com.g1do.anvil.jobs;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Revocable capability grant for one job (issue #21).
 *
 * <p>The {@code capId} is the stable grant identity for
 * {@code (tenant_id, job_id)}; {@code epoch} starts at 1 and bumps on every
 * re-issue (superseding the previous epoch). A commit must present the epoch
 * it holds; the commit transaction re-checks {@code epoch + revoked +
 * expires_at} under {@code FOR UPDATE} with the database clock only.
 */
public record CapGrant(
        UUID capId,
        String tenantId,
        UUID jobId,
        long epoch,
        OffsetDateTime expiresAt) {
}
