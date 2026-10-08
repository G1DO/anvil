package com.g1do.anvil.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-stub idempotent consumer (issue #6).
 *
 * <p>Contract (narrowest reasonable):
 * <ul>
 * <li>Single consumer transaction:
 * {@code INSERT INTO processed_events(event_id) ON CONFLICT DO NOTHING};
 * the test effect is applied only when the insert succeeds. Redelivery of the
 * same stable event identity within the relay's at-least-once guarantee is
 * therefore a no-op.</li>
 * <li>Idempotency lives in the database ({@code processed_events} primary
 * key), not in relay or consumer memory: a fresh instance against the same
 * database still dedups.</li>
 * <li>The durable {@code processed_events} row is the minimal effect needed
 * to prove once-only application in this Outcome; downstream indexing,
 * search and AI side effects belong to later Outcomes and would add
 * non-idempotent side effects out of scope here.</li>
 * </ul>
 */
@Service
public class IdempotentConsumer {

    private static final String CLAIM_SQL = """
            INSERT INTO processed_events (event_id, tenant_id, job_id)
            VALUES (?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IdempotentConsumer(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Applies one event exactly once per stable identity.
     *
     * @return {@code true} when this call applied the effect (first delivery
     *         of the identity), {@code false} when the identity was already
     *         processed (redelivery).
     */
    public boolean consume(OutboxEvent event) {
        if (event == null || event.eventId() == null) {
            throw new IllegalArgumentException("event and eventId must not be null");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Boolean applied = tx.execute(status -> {
            int inserted = jdbc.update(CLAIM_SQL, event.eventId(), event.tenantId(), event.jobId());
            // Test effect lives here: only when the insert won the race.
            // The processed_events row itself is the minimal durable effect.
            return inserted == 1;
        });
        return Boolean.TRUE.equals(applied);
    }
}
