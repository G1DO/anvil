# anvil

Capability-secure, deterministically replayable execution fabric. Current state: V1 durable schema plus idempotent transactional submit plus fenced claim/heartbeat/commit/reconciler plus crash-safe content store with safe garbage collection.

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

## Submit API (issue #3)

- `POST /api/documents` (aliases `/api/v1/documents`, `/documents`) with headers
  `X-Tenant-Id: t_single` and `Idempotency-Key: <key>` and JSON
  `{"title","mime_type","body"}` (`body` is a JSON string treated as UTF-8 bytes for CBOR `bstr`).
- Valid request returns `202` with `{"doc_id","version_id","job_id"}` on both first and duplicate delivery;
  same `(tenant_id, idempotency_key)` returns the same IDs with exactly one job row.
- Auth -> Validate -> Canonicalize CBOR -> persist in one DB transaction
  (document plus version plus job plus outbox); validation or persistence failure leaves none behind.
- CBOR canonical form: RFC 8949 core-deterministic over `{title, mime_type, body}` only
  (`jackson-dataformat-cbor` with `SORT_PROPERTIES_ALPHABETICALLY`);
  `version_sha = sha256(canonical_cbor)`; dupe enforcement is the DB `UNIQUE(tenant_id, idempotency_key)`.

## Claim API (issue #4)

- `JobService.claim(tenant, owner)` claims one `QUEUED` job atomically: candidate
  selection `WHERE tenant_id + QUEUED + unheld/expired lease + attempt < max_attempts
  ORDER BY created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED`, then `QUEUED -> RUNNING`
  with `fencing_token + 1`, `attempt + 1`, `lease = clock_timestamp() + 10s`.
  The `attempts` ledger row (`UNIQUE(tenant_id, job_id, attempt_number)`) is written
  in the same transaction: zero lost, zero doubled.
- `heartbeat(tenant, job, owner, token)` extends only a live lease held by the same
  owner and token (`RUNNING` plus `lease_expires_at > clock_timestamp()`).
- `commitSucceeded/Failed(tenant, job, owner, token)` moves `RUNNING -> SUCCEEDED/FAILED`
  only when id plus owner plus fencing token plus `RUNNING` all match; a stale owner
  affects zero rows and causes no state change (terminal rows are additionally
  immutable via `job_state_guard`).
- `reconcile(tenant)` / `reconcileAll()` is the polling reconciler (intended `1s`):
  expired `RUNNING` with attempts left returns to `QUEUED` (reclaimable with a new
  token); `QUEUED` or expired `RUNNING` at or beyond `max_attempts` moves to `DEAD`
  and is never re-offered. Terminals are never claimable.
- Timings: `lease_ttl=10s, heartbeat_every=3s, claim LIMIT 20, max_attempts=5,
  reconciler_poll=1s`; `clock_timestamp()` is the only lease clock.
  At-least-once with idempotent fenced effects; exactly-once is not asserted.

## Content store (issue #5)

- `ContentStore.put(tenant, bytes)` returns `sha256(bytes)` (lowercase hex) and
  stores under `tenants/{tenant}/{sha}` (`anvil.cas.root`, default `var/cas`);
  no cross-tenant deduplication. `get(tenant, sha)` re-verifies the hash.
- Durability order is mandatory: tmp-write in the destination directory ->
  fsync file -> atomic rename -> fsync directory, then the `artifacts` row
  is written. The final name appears only via atomic rename, so readers never
  see partial bytes; a hash mismatch is a torn tail and is removed rather
  than served (`CorruptContentException`).
- `acquire(tenant, sha, size)` / `release(tenant, sha)` drive
  `artifacts.refcount`. `collectGarbage()` deletes only `refcount = 0` rows
  older than `GC_grace=1h` (DB clock), re-checking each row `FOR UPDATE` so a
  newly referenced SHA is retained, then sweeps crash orphans (files without
  rows, old tmp files) by file mtime. Any ambiguity retains rather than deletes.

## How the database works today

- `application.yaml` sets only the app name; there is no datasource URL.
- Postgres comes automatically: `@ServiceConnection PostgreSQLContainer` in tests, Boot Dev Services on local run.
- `src/main/resources/db/migration/V1__durable_v1_schema.sql` is the source of truth for the single-tenant slice (tenants, documents, versions, jobs, attempts, artifacts, outbox, processed_events) with the transition guard and claim/relay indexes.

## Layout

- `src/main/java/com/g1do/anvil` — application entry plus `tenant/` (X-Tenant-Id auth), `submit/` (CBOR, service, controller), `jobs/` (fenced claim, heartbeat, commit, reconciler), and `cas/` (crash-safe content store, refcount, garbage collection)
- `src/main/resources` — config + migrations (`static/`, `templates/` are unused Boot defaults)
- `src/test` — `contextLoads`, V1 schema proof, submit idempotency proof, fenced claim proof, content-store crash/GC proof, plus the Testcontainers configuration

## Tests

`./mvnw test` boots the full context against containerized Postgres. Green means the stack is proven.

## Explicitly deferred (not created yet)

- License undecided (`pom.xml` carries no license block on purpose)
- No ADRs yet — the first one lands with the Postgres version pin or the first table
- No CONTRIBUTING — single contributor; dev commands live here
- No runbooks — no deploy target
