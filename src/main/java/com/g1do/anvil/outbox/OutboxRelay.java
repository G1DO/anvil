package com.g1do.anvil.outbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.g1do.anvil.tenant.TenantGuard;

/**
 * Loss-free outbox relay with at-least-once publish (issue #6).
 *
 * <p>Contract (narrowest reasonable):
 * <ul>
 * <li>Transactional outbox only; the submit transaction writes the
 * {@code outbox} row and this relay runs in transactions strictly separate
 * from submit (never publish-then-commit dual-write).</li>
 * <li>Each poll reads {@code WHERE tenant_id = ? AND sent_at IS NULL ORDER BY id
 * LIMIT 100 FOR UPDATE SKIP LOCKED} (uses {@code outbox_poll_idx}) and
 * publishes each event carrying its stable identity ({@code outbox.id} as
 * {@code event_id} in both payload and {@code event_id} header).</li>
 * <li>Boundary per event: poll (own transaction) -&gt; publish (no
 * transaction) -&gt; {@code UPDATE outbox SET sent_at} in a separate
 * transaction after publish. The mark advances only after successful publish;
 * a publish throw skips the mark so the event is re-published on restart
 * rather than lost. Exactly-once delivery is explicitly not asserted.</li>
 * <li>Progress is the durable {@code sent_at} column, so it survives relay
 * restarts; no in-memory mark is kept. Concurrent relays overlapping in
 * {@code SELECT} skip each other's locked rows via {@code SKIP LOCKED}; polls
 * interleaved between another relay's publish and mark may both publish the
 * same row (at-least-once), but the second mark is a no-op
 * ({@code WHERE tenant_id = ? AND sent_at IS NULL}) and the idempotent
 * consumer still applies the effect once.</li>
 * <li>Tenant isolation (issue #20): every poll/mark transaction sets
 * transaction-local {@code app.tenant} with a read-back probe; RLS
 * {@code FORCE} enforces {@code tenant_id = current_setting('app.tenant',
 * true)} underneath, and every statement keeps {@code WHERE tenant_id = ?}
 * in addition to RLS. The unscoped {@link #relayOnce(OutboxPublisher)}
 * iterates tenants and relays each in its own tenant transaction (one
 * transaction cannot hold two tenants).</li>
 * <li>Polling first; no broker or streaming platform.</li>
 * </ul>
 */
@Service
public class OutboxRelay {

    /** Batch window per poll; appears literally as {@code LIMIT 100}. */
    public static final int POLL_LIMIT = 100;

    /**
     * Canonical relay poll: unsent rows for one tenant in {@code ORDER BY id}
     * batches with {@code FOR UPDATE SKIP LOCKED}. Must keep using
     * {@code outbox_poll_idx} (the {@code sent_at IS NULL} predicate matches
     * the partial index; {@code tenant_id = ?} is an additional filter).
     */
    private static final String POLL_BY_TENANT_SQL = """
            SELECT id, tenant_id, job_id, doc_id, version_id, type, sha256
            FROM outbox
            WHERE tenant_id = ?
              AND sent_at IS NULL
            ORDER BY id
            LIMIT 100
            FOR UPDATE SKIP LOCKED
            """;

    /**
     * Durable progress mark: separate transaction after publish. The
     * {@code AND tenant_id = ? AND sent_at IS NULL} guard makes concurrent
     * marking idempotent and tenant-scoped:
     * the loser updates zero rows but the consumer still dedups the duplicate
     * publish.
     */
    private static final String MARK_SENT_SQL = """
            UPDATE outbox
            SET sent_at = clock_timestamp()
            WHERE id = ?
              AND tenant_id = ?
              AND sent_at IS NULL
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Single relay pass across all tenants: iterates {@code tenants} and relays
     * each in its own tenant transaction.
     *
     * @return the number of events marked sent in this pass (progress
     *         advanced); {@code 0} when no unsent row was available.
     */
    public int relayOnce(OutboxPublisher publisher) {
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
        List<String> tenants = jdbc.query("SELECT id FROM tenants", (rs, i) -> rs.getString("id"));
        int total = 0;
        for (String tenant : tenants) {
            total += relayOnce(publisher, tenant);
        }
        return total;
    }

    /**
     * Single tenant-scoped relay pass: polls one batch for {@code tenantId} and
     * publishes each event before marking it sent.
     *
     * @return the number of events marked sent in this pass for this tenant.
     */
    public int relayOnce(OutboxPublisher publisher, String tenantId) {
        if (publisher == null) {
            throw new IllegalArgumentException("publisher must not be null");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        String tenant = tenantId.trim();
        List<OutboxEvent> batch = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            return jdbc.query(
                    POLL_BY_TENANT_SQL,
                    (rs, i) -> new OutboxEvent(
                            (UUID) rs.getObject("id"),
                            rs.getString("tenant_id"),
                            (UUID) rs.getObject("job_id"),
                            (UUID) rs.getObject("doc_id"),
                            (UUID) rs.getObject("version_id"),
                            rs.getString("type"),
                            rs.getString("sha256")),
                    tenant);
        });
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        int marked = 0;
        for (OutboxEvent event : batch) {
            // Publish outside any DB transaction: a throw skips the mark below
            // so the event is re-published after restart (no gap).
            publisher.publish(event, Map.of("event_id", event.eventId().toString()));
            Integer updated = tx.execute(status -> {
                TenantGuard.setTenant(jdbc, tenant);
                return jdbc.update(MARK_SENT_SQL, event.eventId(), tenant);
            });
            if (updated != null && updated == 1) {
                marked++;
            }
        }
        return marked;
    }
}
