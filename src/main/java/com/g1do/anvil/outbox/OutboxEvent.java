package com.g1do.anvil.outbox;

import java.util.UUID;

/**
 * Single committed outbox event: the stable identity plus the minimal
 * {@code job.committed} payload (issue #6).
 *
 * <p>Identity is {@code eventId}, which is the {@code outbox.id} primary key
 * (a UUID). The relay publishes this identity both in the payload and in the
 * {@code event_id} header so a crash between publish and mark re-publishes the
 * same identity rather than skipping it.
 */
public record OutboxEvent(
        UUID eventId,
        String tenantId,
        UUID jobId,
        UUID docId,
        UUID versionId,
        String type,
        String sha256) {
}
