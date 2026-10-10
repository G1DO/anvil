# anvil

Capability-secure, deterministically replayable execution fabric. Current state: V1 durable schema plus idempotent transactional submit plus fenced claim/heartbeat/commit/reconciler plus crash-safe content store with safe garbage collection plus loss-free outbox relay with idempotent consumer.

## Stack

- Java 25 (Temurin LTS), Spring Boot 4.1.1, Maven wrapper
- Runtime: Spring Web (MVC), JDBC, Validation, Flyway, PostgreSQL driver
- Tests: Testcontainers (spins up `postgres:18`)

## Prerequisites

- Java 25
- Docker running — tests fail without it (Testcontainers provisions Postgres, no local install needed)

## Quickstart

```bash
./mvnw test
./mvnw spring-boot:run
```

Postgres comes automatically: `@ServiceConnection PostgreSQLContainer` in tests, Boot Dev Services on local run.

## Documentation

- `docs/architecture.md` — how the implemented system works (submit, fenced claim, content store, outbox relay, database, verification proofs, timings).
- `docs/decisions/` — accepted architecturally significant records (V1 schema guard, fenced claim, crash-safe CAS, loss-free outbox).
- `CONTRIBUTING.md` — how to work in this repository (setup, commands, branch → PR → `main`, docs updates, review).
- `SECURITY.md` — how to report vulnerabilities and where the trust boundaries are.

## Tests

`./mvnw test` boots the full context against containerized Postgres. Green means the stack is proven: V1 schema guard, submit idempotency, fenced claim plus gate-scale contention and zombie resume, content-store crash/GC plus real-kill proofs, outbox relay ghost/dedup/concurrency plus real-kill proof. See `docs/architecture.md` for what each suite proves.

## Layout

- `src/main/java/com/g1do/anvil` — application entry plus `tenant/` (X-Tenant-Id auth), `submit/` (CBOR, service, controller), `jobs/` (fenced claim, heartbeat, commit, reconciler), `cas/` (crash-safe content store, refcount, garbage collection), and `outbox/` (loss-free relay, idempotent consumer)
- `src/main/resources` — config + migrations
- `src/test` — `contextLoads`, schema proof, submit idempotency proof, fenced claim plus gate-scale and zombie proofs, content-store crash/GC plus real-kill proofs, outbox relay ghost/dedup/concurrency plus real-kill proof, plus the Testcontainers configuration
- `docs/` — architecture and decisions; `.github/workflows/ci.yml` runs `./mvnw -B -ntp test` on Java 25

## Explicitly deferred (not created yet)

- License undecided (`pom.xml` carries no license block on purpose)
- No runbooks — no deploy target
