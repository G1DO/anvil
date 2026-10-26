# anvil

Capability-secure, deterministically replayable execution fabric. Current state: V1 durable schema plus idempotent transactional submit plus fenced claim/heartbeat/capability-gated commit/reconciler plus crash-safe content store with safe garbage collection plus loss-free outbox relay with idempotent consumer plus DB-enforced tenant isolation plus fail-closed cross-tenant reads (direct-ID, title-prefix search, CAS gateway, TTL-60s presigned URLs) plus immutable per-job hash-chained audit log with tenant-isolated read API plus minimal execution jail (forked child, per-tenant workspace, cleaned env, default-deny egress, redirect-never).

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

One-command prod-like start/verify (mirrors prod deps):

```bash
docker compose up --build
curl -s localhost:8080/healthz
curl -s localhost:8080/readyz
```

Task verbs (same commands CI runs): `task dev|test|build|test:contract|check:sast|check:secrets|check:audit|check:hygiene|diagrams|verify` (see `Taskfile.yml`). CI job keys differ from verb names; commands match: `test→task test`, `sast→task build + task check:sast`, `secrets-scan→task check:secrets`, `audit→task check:audit`, `build→task build`, `contract-check→task test:contract`, `arch-validate→task diagrams`, `hygiene→task check:hygiene`, nightly `verify→task verify`; `task dev` is local-only.

## Documentation

- `docs/architecture/index.md` — repo handbook index and canonical view list (C1/C2/C3/detail, data, deployment; Mermaid sources validated with mermaid-cli).
- `docs/architecture/data-model.md` — tables, RLS, indexes, audit chain shape (SQL in `src/main/resources/db/migration/` is canonical).
- `docs/decisions/` — accepted architecturally significant records (V1 schema guard, fenced claim, crash-safe CAS, loss-free outbox, DB-enforced tenant isolation, capability-gated commits, fail-closed reads, minimal execution jail).
- `CONTRIBUTING.md` — how to work in this repository (setup, commands, branch → PR → `main`, docs updates, review).
- `SECURITY.md` — how to report vulnerabilities and where the trust boundaries are.

## Tests

`./mvnw test` boots the full context against containerized Postgres. Green means the stack is proven: V1 schema guard, submit idempotency, fenced claim plus gate-scale contention and zombie resume, content-store crash/GC plus real-kill proofs, outbox relay ghost/dedup/concurrency plus real-kill proof, DB-enforced tenant isolation plus capability-gated commit (revoke/expire/supersede/cross-tenant denials) plus fail-closed cross-tenant reads (28-case direct-ID/search/CAS/presigned matrix, 0 leaks) plus minimal execution jail (RO-root/env/egress/redirect/cross-tenant invisibility with allowed-tenant control). See `src/test/java/com/g1do/anvil/` for what each suite proves and `docs/decisions/` for why.

## Layout

- `src/main/java/com/g1do/anvil` — application entry plus `tenant/` (X-Tenant-Id auth), `submit/` (CBOR, service, controller), `jobs/` (fenced claim, heartbeat, capability-gated commit, reconciler), `cas/` (crash-safe content store, refcount, garbage collection, tenant-scoped gateway, TTL-60s presigned URLs), `read/` (tenant-isolated direct-ID fetches, title-prefix search, search controller), `audit/` (append-only hash chain writer plus tenant-isolated per-job read API), `exec/` (minimal execution jail, default-deny egress, redirect-never client), `health/` (`/healthz` liveness, `/readyz` readiness, correlation IDs), and `outbox/` (loss-free relay, idempotent consumer)
- `src/main/resources` — config + migrations + `logback-spring.xml` (JSON logs with correlation, no secrets)
- `Dockerfile` (multistage minimal unprivileged) + `docker-compose.yml` (real `postgres:18` + app)
- `src/test` — `contextLoads`, schema proof, submit idempotency proof, fenced claim plus gate-scale and zombie proofs, content-store crash/GC plus real-kill proofs, outbox relay ghost/dedup/concurrency plus real-kill proof, tenant-isolation leak proof, capability-revoke mid-run proof, cross-tenant read matrix proof (direct-ID/search/CAS/presigned, 0 leaks), execution-jail proof (RO-root/env/egress/redirect/cross-tenant/control), audit-chain proof plus audit-read isolation proof (own-tenant order, cross-tenant deny-without-oracle, forged-tenant ignored, cursor replay 0 rows, `401`, RLS proof), health probe proof (`/healthz` no-auth, `/readyz` DB check, correlation echo), plus the Testcontainers configuration
- `docs/` — architecture and decisions; `.github/workflows/ci.yml` gate (`test,sast,secrets-scan,audit,build,arch-validate,hygiene,contract-check` required, `gate` sole required) on Java 25

## Explicitly deferred (not created yet)

- License undecided (`pom.xml` carries no license block on purpose)
- No runbooks — no deploy target
