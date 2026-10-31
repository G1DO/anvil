package com.g1do.anvil.jobs;

/**
 * A job claimed by {@link JobService#claimNext(String)}, with the tenant it
 * belongs to. The worker presents {@code tenantId} with the job's owner and
 * fencing token on every heartbeat and commit, exactly as after
 * {@link JobService#claim(String, String)}.
 */
public record TenantClaim(String tenantId, ClaimedJob job) {
}
