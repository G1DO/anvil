# Architecture

Current implemented system: capability-secure, deterministically replayable execution fabric with DB-enforced tenant isolation (single-tenant seed `t_single`, multi-tenant safe).

Stack: Java 25 (Temurin LTS), Spring Boot 4.1.1, Spring Web (MVC), JDBC, Validation, Flyway, PostgreSQL driver. Tests use Testcontainers `postgres:18` (see `src/test/java/com/g1do/anvil/TestcontainersConfiguration.java`). Local run uses Boot Dev Services; `src/main/resources/application.yaml` sets only the app name.

## Map

- C1 context (actors, externals, boundaries, entry contract): `docs/architecture/c1.md`, diagram Structurizr `C1-SystemContext` in `docs/architecture/structurizr/workspace.dsl`.
- C2 containers (modules, jobs, store, reads, outbox, jail, database, health): `docs/architecture/c2.md`, diagram Structurizr `C2-Containers` in `docs/architecture/structurizr/workspace.dsl`.
- C3 per slice (only the container under change): `docs/architecture/slices/o3/c3.md` (O3 audit log and replay source).
- Decisions: `docs/decisions/` (0001 durable schema, 0002 fenced claim, 0003 CAS, 0004 outbox, 0005 tenant isolation, 0006 capabilities, 0007 fail-closed reads, 0008 jail, 0009 audit chain).
- Contracts: `docs/contracts/http.md`, source `openapi/openapi.yaml` (0.1.0).

## Verification proofs

`./mvnw test` boots the full context against containerized Postgres. The test classes prove:

- `DurableV1SchemaTest`: tables, legal-transition guard (terminals immutable), claim/relay indexes used (no `Seq Scan`).
- `SubmitIdempotencyTest`: duplicate `(tenant, key)` returns the same IDs with one job row; validation/persistence failure leaves nothing behind.
- `JobServiceTest`: fencing (stale owner affects zero rows), heartbeat/commit guards, reconciler (`QUEUED` reclaim vs `DEAD`), attempts ledger exactly once, gate-scale contention (naive select-then-update double-claims ≥1/500; atomic CTE zero double-claims/2000 with ledger exact and terminals never offered), zombie resume with real suspend past TTL (resumed worker's heartbeat/commit affect zero rows).
- `ContentStoreTest`: crash-safe put/get (tmp-write, atomic rename, torn-tail removal), refcount GC (referenced SHAs retained, old orphans reclaimed, young retained), real-kill proofs during tmp-write, rename-before-row, and GC file-before-row with hash-verified reads and zero torn bytes.
- `OutboxRelayTest`: ghost redelivery (kill between publish and mark re-publishes the same `event_id` with one `processed_events` row), dedup (`ON CONFLICT DO NOTHING`), concurrent relays apply once.
- `RlsLeakTest`: DB-enforced isolation on containerized Postgres with a shared Hikari pool as `anvil_app` — `RLS FORCE` + policies + `NOSUPERUSER NOBYPASSRLS` + migrator ownership + no `SECURITY DEFINER` proven from catalogs; `SET LOCAL app.tenant` + read-back probe per transaction; interleaved tenants over 120 checkouts plus plan-cache reuse show 0 cross-tenant rows; missing context reads 0 rows; pooled links clean after commit; tenant-scoped claim/relay see only own tenant; tenant-scoped relay poll still uses `outbox_poll_idx`.
- `CapRevokeMidRunTest`: capability-gated commits on containerized Postgres — claim → revoke/expire/supersede mid-run → commit denies atomically (0 rows, `403 NEEDS_APPROVAL`, `cap_rejected++`); valid-cap control (explicit-cap and current-grant paths) still commits; cross-tenant cap (A authorizing B, valid epoch/expiry) denies; `cap_grants` RLS/`FORCE`/policy/ownership/composite-FK proven from catalogs.
- `ReadIsolationTest`: 28-case cross-tenant read matrix on containerized Postgres — direct-ID 8, search/listing 8, CAS 6, presigned TTL-60s 6; 0 rows leaked.
- `ExecJailTest`: minimal jail on containerized Postgres plus forked children — RO-root denied, symlink escape denied, adversarial child denied, env clean, output capped 1 MiB/stream, egress denied plus non-canonical denied, redirect not followed (`NEVER`), cross-tenant file invisible, allowed-tenant control succeeds.
- `AuditChainTest`: immutable per-job hash chain on containerized Postgres — shape (`UNIQUE(job_id, prev_hash)` fork guard, composite FK, `RLS FORCE`/policy, migrator ownership, app `SELECT+INSERT` only, immutable trigger), full `submit→claim→cap.issue→cas.put→commit` chain hash-linked, duplicate resubmit no fork, `UPDATE`/`DELETE` rejected, cross-tenant FK rejected, effect+append crash atomicity.
- `AuditReadIsolationTest`: tenant-isolated audit reads — own-tenant `seq` order, cross-tenant empty same-as-missing, forged tenant ignored, foreign cursor 0 rows, `401`/`400` paths, RLS catalog proof.
- `HealthControllerTest`: `/healthz` no-auth liveness, `/readyz` DB readiness, correlation echo.

## Timings and limits

`lease_ttl=10s, heartbeat_every=3s, claim LIMIT 20, max_attempts=5, reconciler_poll=1s, relay LIMIT 100, GC_grace=1h, cap_ttl=1h, presigned_ttl=60s, search_default_limit=20, search_max_limit=100, audit_read_default_limit=20, audit_read_max_limit=100, exec_default_timeout=30s, jail_PATH=/usr/bin:/bin, jail_capture_cap=1MiB/stream, egress default-deny, http NEVER`. `clock_timestamp()` is the only lease/cap/presigned clock. Polling first; no broker, Redis, or external queue.
