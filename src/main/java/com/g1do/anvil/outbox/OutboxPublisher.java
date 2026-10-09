package com.g1do.anvil.outbox;

import java.util.Map;

/**
 * Test-stub publish target for the relay (issue #6).
 *
 * <p>Polling first; no broker or streaming platform in this Outcome. The relay
 * calls {@link #publish} synchronously for each polled event; the
 * implementation is expected to forward to the idempotent consumer. Headers
 * always carry {@code event_id} (the stable {@code outbox.id} identity) so the
 * consumer can deduplicate even when the relay re-publishes after a crash.
 */
@FunctionalInterface
public interface OutboxPublisher {

    /**
     * Publishes one event. Throwing aborts the relay before it advances its
     * mark, so the event stays unsent and is re-published on the next poll
     * (at-least-once, never exactly-once).
     */
    void publish(OutboxEvent event, Map<String, String> headers);
}
