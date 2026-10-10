# 0004 — Loss-free outbox relay with idempotent consumer

Status: accepted.

## Context

Submit must not lose its event if the process crashes after committing, and must not require a distributed transaction between the database and a broker. Publishing before the commit (dual-write) can emit events for rolled-back work; marking before publishing can lose events.

## Decision

Transactional outbox plus at-least-once relay with database-side dedup (`src/main/java/com/g1do/anvil/outbox/OutboxRelay.java`, `src/main/java/com/g1do/anvil/outbox/IdempotentConsumer.java`):

- Submit writes the `outbox` row in the same transaction as document, version, and job; relay transactions are strictly separate.
- Relay polls `WHERE sent_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`, publishes each event carrying stable identity `outbox.id` (`event_id` in payload and header), then marks `sent_at` in a separate transaction guarded by `WHERE sent_at IS NULL`. A publish failure skips the mark, so restart re-publishes the same identity.
- Consumer applies once via `INSERT INTO processed_events ON CONFLICT DO NOTHING`; progress is durable `sent_at`, never relay memory.

## Consequences

- Crash between publish and mark is a ghost redelivery, never a loss; concurrent relays may duplicate publish but the consumer still applies once (proven by `OutboxRelayTest` including real-kill proofs).
- Exactly-once delivery is explicitly not asserted.
- Rejected: publish-then-commit dual-write and in-memory dedup.
