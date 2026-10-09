package com.g1do.anvil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.g1do.anvil.outbox.IdempotentConsumer;
import com.g1do.anvil.outbox.OutboxEvent;
import com.g1do.anvil.outbox.OutboxPublisher;
import com.g1do.anvil.outbox.OutboxRelay;
import com.g1do.anvil.submit.SubmitService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxRelayTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxRelay relay;

    @Autowired
    IdempotentConsumer consumer;

    @Autowired
    SubmitService submitService;

    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        truncate();
    }

    @AfterEach
    void tearDown() {
        // TRUNCATE (no dead tuples) so the heavy index seeding in this class
        // does not bloat the shared container and flip the planner in the
        // next class from outbox_poll_idx to a Seq Scan.
        truncate();
    }

    private void truncate() {
        jdbc.execute("TRUNCATE TABLE processed_events, outbox, attempts, jobs, document_versions, documents, artifacts");
        jdbc.execute("DELETE FROM tenants WHERE id <> 't_single'");
        jdbc.update("INSERT INTO tenants (id) VALUES ('t_single') ON CONFLICT DO NOTHING");
    }

    private void submitOne(String key) {
        submitService.submit("t_single", key, "Title " + key, "text/plain", "body-" + key);
    }

    private List<UUID> outboxIdsOrdered() {
        return jdbc.query("SELECT id FROM outbox ORDER BY id", (rs, i) -> (UUID) rs.getObject("id"));
    }

    private Map<String, Object> outboxRow(UUID eventId) {
        return jdbc.queryForMap("SELECT * FROM outbox WHERE id = ?", eventId);
    }

    private record RecordedPublish(OutboxEvent event, Map<String, String> headers) {
    }

    private static class RecordingPublisher implements OutboxPublisher {
        final ConcurrentLinkedQueue<RecordedPublish> published = new ConcurrentLinkedQueue<>();
        final AtomicInteger applyCount = new AtomicInteger();
        final IdempotentConsumer consumer;

        RecordingPublisher(IdempotentConsumer consumer) {
            this.consumer = consumer;
        }

        @Override
        public void publish(OutboxEvent event, Map<String, String> headers) {
            published.add(new RecordedPublish(event, Map.copyOf(headers)));
            if (consumer.consume(event)) {
                applyCount.incrementAndGet();
            }
        }
    }

    @Test
    void relayPublishesInOrderWithStableIdentityAndMarksOnlyAfterPublish() {
        submitOne("relay-order-1-" + UUID.randomUUID());
        submitOne("relay-order-2-" + UUID.randomUUID());
        submitOne("relay-order-3-" + UUID.randomUUID());

        List<UUID> expectedOrder = outboxIdsOrdered();
        assertThat(expectedOrder).hasSize(3);

        RecordingPublisher publisher = new RecordingPublisher(consumer);
        int marked = relay.relayOnce(publisher::publish);

        assertThat(marked).isEqualTo(3);
        assertThat(publisher.published).hasSize(3);
        List<UUID> publishedOrder = new ArrayList<>();
        for (RecordedPublish p : publisher.published) {
            publishedOrder.add(p.event().eventId());
        }
        // Relay polls in ORDER BY id batches.
        assertThat(publishedOrder).containsExactlyElementsOf(expectedOrder);

        for (RecordedPublish p : publisher.published) {
            OutboxEvent event = p.event();
            Map<String, Object> row = outboxRow(event.eventId());
            // Stable identity: event_id is the outbox PK.
            assertThat(event.eventId()).isEqualTo(row.get("id"));
            assertThat(event.tenantId()).isEqualTo("t_single");
            assertThat(event.jobId()).isEqualTo(row.get("job_id"));
            assertThat(event.docId()).isEqualTo(row.get("doc_id"));
            assertThat(event.versionId()).isEqualTo(row.get("version_id"));
            assertThat(event.type()).isEqualTo("job.committed");
            assertThat(event.sha256()).isEqualTo(row.get("sha256"));
            assertThat(event.sha256()).matches("^[0-9a-f]{64}$");
            // event_id header carries the same stable identity.
            assertThat(p.headers()).containsEntry("event_id", event.eventId().toString());
            // Mark advanced only after successful publish.
            assertThat(row.get("sent_at")).isNotNull();
        }

        // Consumer applied each effect exactly once.
        assertThat(publisher.applyCount.get()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class))
                .isEqualTo(3);

        // Progress survives restarts: a fresh relay instance sees no unsent rows.
        OutboxRelay restarted = new OutboxRelay(jdbc, txManager);
        RecordingPublisher afterRestart = new RecordingPublisher(consumer);
        assertThat(restarted.relayOnce(afterRestart::publish)).isEqualTo(0);
        assertThat(afterRestart.published).isEmpty();
    }

    @Test
    void relayGhostCrashBetweenPublishAndMarkRepublishesSameIdentityWithSingleApply() {
        submitOne("relay-ghost-" + UUID.randomUUID());
        UUID eventId = outboxIdsOrdered().get(0);

        List<OutboxEvent> ghostPublishes = new ArrayList<>();
        OutboxPublisher ghost = (event, headers) -> {
            ghostPublishes.add(event);
            assertThat(headers).containsEntry("event_id", event.eventId().toString());
            // Deliver to the consumer before the crash: effect is applied once.
            assertThat(consumer.consume(event)).isTrue();
            throw new RuntimeException("simulated crash after publish before mark");
        };

        assertThatThrownBy(() -> relay.relayOnce(ghost))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated crash");
        assertThat(ghostPublishes).hasSize(1);
        assertThat(ghostPublishes.get(0).eventId()).isEqualTo(eventId);
        // Crash before mark: no gap, no progress.
        assertThat(outboxRow(eventId).get("sent_at")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);

        // Restart re-publishes the same stable identity.
        RecordingPublisher restarted = new RecordingPublisher(consumer);
        int marked = relay.relayOnce(restarted::publish);
        assertThat(marked).isEqualTo(1);
        assertThat(restarted.published).hasSize(1);
        assertThat(restarted.published.peek().event().eventId()).isEqualTo(eventId);
        // Second delivery did not re-apply: single processed_events entry.
        assertThat(restarted.applyCount.get()).isEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(outboxRow(eventId).get("sent_at")).isNotNull();
    }

    @Test
    void duplicatedDeliveriesYieldSingleAppliedEffect() {
        submitOne("relay-dupe-" + UUID.randomUUID());

        RecordingPublisher publisher = new RecordingPublisher(consumer);
        assertThat(relay.relayOnce(publisher::publish)).isEqualTo(1);
        assertThat(publisher.applyCount.get()).isEqualTo(1);
        OutboxEvent event = publisher.published.peek().event();

        // Duplicate deliveries from the relay still yield a single effect.
        assertThat(consumer.consume(event)).isFalse();
        assertThat(consumer.consume(event)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?",
                Integer.class, event.eventId())).isEqualTo(1);

        // Idempotency lives in the database, not in relay memory: a fresh
        // consumer instance against the same database still dedups.
        IdempotentConsumer fresh = new IdempotentConsumer(jdbc, txManager);
        assertThat(fresh.consume(event)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void concurrentRelaysDoNotLoseOrDoubleApply() throws Exception {
        int events = 20;
        for (int i = 0; i < events; i++) {
            submitOne("relay-conc-" + i + "-" + UUID.randomUUID());
        }
        List<UUID> expected = outboxIdsOrdered();
        assertThat(expected).hasSize(events);

        RecordingPublisher shared = new RecordingPublisher(consumer);
        int racers = 2;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return relay.relayOnce(shared::publish);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            int markedConcurrent = 0;
            for (Future<Integer> f : futures) {
                markedConcurrent += f.get(60, TimeUnit.SECONDS);
            }
            // Drain any remainder single-threaded so the test asserts no loss
            // rather than racing the last mark.
            int drained = 0;
            int one;
            do {
                one = relay.relayOnce(shared::publish);
                drained += one;
            } while (one > 0);
            assertThat(markedConcurrent + drained).isEqualTo(events);

            // Every committed event was published at least once (no loss).
            List<UUID> publishedIds = new ArrayList<>();
            for (RecordedPublish p : shared.published) {
                publishedIds.add(p.event().eventId());
            }
            assertThat(publishedIds).containsAll(expected);
            // At-least-once may duplicate publishes under concurrency.
            assertThat(shared.published.size()).isGreaterThanOrEqualTo(events);
            // Exactly-once application per identity regardless of redelivery.
            assertThat(shared.applyCount.get()).isEqualTo(events);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE sent_at IS NULL",
                    Integer.class)).isEqualTo(0);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class))
                    .isEqualTo(events);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void relayBatchLimitIsOneHundred() {
        assertThat(OutboxRelay.POLL_LIMIT).isEqualTo(100);
    }

    @Test
    void canonicalRelayPollUsesOutboxPollIndex() {
        // Mirror DurableV1SchemaTest seeding so the planner prefers the partial
        // index: mostly-sent history with a small unsent backlog.
        List<UUID> jobIds = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            UUID doc = UUID.randomUUID();
            jdbc.update("INSERT INTO documents (id, tenant_id, title, mime_type) VALUES (?, 't_single', ?, 'text/plain')",
                    doc, "title-" + i);
            UUID ver = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO document_versions (id, tenant_id, document_id, version_number, sha256) VALUES (?, 't_single', ?, 1, ?)",
                    ver, doc, String.format("%064x", i + 5000));
            UUID job = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO jobs (id, tenant_id, document_id, version_id, idempotency_key, state) VALUES (?, 't_single', ?, ?, ?, 'QUEUED')",
                    job, doc, ver, "relay-idx-" + i + "-" + UUID.randomUUID());
            jobIds.add(job);
            jdbc.update("INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, payload) "
                    + "SELECT ?, tenant_id, id, document_id, version_id, 'job.committed', '{}'::jsonb FROM jobs WHERE id = ?",
                    UUID.randomUUID(), job);
        }
        for (UUID jobId : jobIds) {
            jdbc.update("INSERT INTO outbox (id, tenant_id, job_id, doc_id, version_id, type, payload, sent_at) "
                    + "SELECT ?, tenant_id, id, document_id, version_id, 'job.committed', '{}'::jsonb, clock_timestamp() FROM jobs WHERE id = ?",
                    UUID.randomUUID(), jobId);
        }
        jdbc.update("INSERT INTO outbox (tenant_id, job_id, doc_id, version_id, type, payload, sent_at) "
                + "SELECT j.tenant_id, j.id, j.document_id, j.version_id, 'job.committed', '{}'::jsonb, clock_timestamp() "
                + "FROM jobs j CROSS JOIN generate_series(1, 10) g WHERE j.tenant_id = 't_single'");
        jdbc.execute("ANALYZE outbox");

        String plan = String.join("\n", jdbc.queryForList(
                "EXPLAIN SELECT id, tenant_id, job_id, doc_id, version_id, type, sha256 FROM outbox "
                        + "WHERE sent_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED",
                String.class));
        assertThat(plan).contains("outbox_poll_idx");
        assertThat(plan).doesNotContain("Seq Scan");
    }
}
