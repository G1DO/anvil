# Architecture

Current implemented system: capability-secure, deterministically replayable execution fabric with DB-enforced tenant isolation (single-tenant seed `t_single`, multi-tenant safe).

Stack: Java 25 (Temurin LTS), Spring Boot 4.1.1, Spring Web (MVC), JDBC, Validation, Flyway, PostgreSQL driver. Tests use Testcontainers `postgres:18` (see `src/test/java/com/g1do/anvil/TestcontainersConfiguration.java`). Local run uses Boot Dev Services; `src/main/resources/application.yaml` sets only the app name.

## Map

- C1 context (actors, externals, boundaries, entry contract): `## C1` below, diagram Mermaid `c1.mmd`.
- C2 containers (modules, jobs, store, reads, outbox, jail, database, health): `## C2` below, diagram Mermaid `c2.mmd`.
- C3 per slice (only the container under change): `## C3` below (O3 audit log and replay source).
- Decisions: `docs/decisions/` (0001 durable schema, 0002 fenced claim, 0003 CAS, 0004 outbox, 0005 tenant isolation, 0006 capabilities, 0007 fail-closed reads, 0008 jail, 0009 audit chain).
- Contracts: `docs/contracts/http.md`, source `openapi/openapi.yaml` (0.1.0).

## C1 — System context

Who is inside versus outside, and where tenant trust stops.

Canonical diagram: Mermaid source `c1.mmd` (render with Mermaid preview).

### Actors and externals

- Tenants: HTTP clients sending `X-Tenant-Id` plus `Idempotency-Key`. Tenant is the authority for every read and write; query `tenant` params are ignored.
- Operator: runs `docker compose up --build`, probes `/healthz` + `/readyz`, tails JSON logs by `correlationId`.
- Postgres 18: source of truth, queue, and outbox (Flyway V1–V4, RLS `FORCE`, `anvil_app` `NOSUPERUSER NOBYPASSRLS`).
- Filesystem CAS root (`anvil.cas.root`, default `var/cas`): per-tenant `tenants/{tenant}/{sha}`, no cross-tenant dedup.
- Exec workspaces root (`anvil.exec.root`, default `var/exec-workspaces`): per-tenant writable directory only.
- What is out in v1: broker, K8s, Redis, OpenSearch, gRPC, streaming, gVisor. Polling first; `LISTEN/NOTIFY` wake-up only.

### System data flow

`POST /api/documents` → `X-Tenant-Id` auth → validate → canonical CBOR → one DB transaction (document + version + job + outbox) → `JobService` claim/heartbeat/commit/reconciler → `OutboxRelay` publish + `sent_at` mark → `IdempotentConsumer`. Bytes go through `ContentStore` (filesystem + `artifacts` rows). Containers and externals are drawn in the Mermaid `c1.mmd` view.

### Entry contract

- Routes: `POST /api/documents` (aliases `/api/v1/documents`, `/documents`). Headers `X-Tenant-Id`, `Idempotency-Key`. Body `{"title","mime_type","body"}` where `body` is a JSON string treated as UTF-8 bytes for CBOR `bstr`. See `src/main/java/com/g1do/anvil/submit/SubmitController.java` and `src/main/java/com/g1do/anvil/submit/SubmitService.java`.
- Valid request returns `202` with `{"doc_id","version_id","job_id"}` on first and duplicate delivery; same `(tenant_id, idempotency_key)` returns the same IDs with exactly one job row (`UNIQUE(tenant_id, idempotency_key)`, fast-path re-read plus `DuplicateKeyException` replay of the winner).
- Canonical form: RFC 8949 core-deterministic over `{title, mime_type, body}` only (`jackson-dataformat-cbor` with `SORT_PROPERTIES_ALPHABETICALLY`); `version_sha = sha256(canonical_cbor)`. See `src/main/java/com/g1do/anvil/submit/CanonicalCbor.java`.
- Validation limits live in `SubmitService`: title required ≤4096, `mime_type` required ≤255, `Idempotency-Key` required ≤255, `body` must be present; unknown tenant → `401`.

## C2 — Containers

Major applications, stores, and communication paths for the whole system. C1 is in the section above; O3 slice internals live in `## C3` below.

Canonical diagram: Mermaid source `c2.mmd` (render with Mermaid preview).

### Modules

Application entry plus `tenant/` (`X-Tenant-Id` auth), `submit/` (CBOR, service, controller), `jobs/` (fenced claim, heartbeat, capability-gated commit, reconciler), `cas/` (crash-safe store, gateway, TTL-60s presigned URLs), `read/` (direct-ID fetches, title-prefix search), `audit/` (append-only chain writer plus read API), `exec/` (minimal jail, default-deny egress), `health/` (`/healthz`, `/readyz`, correlation IDs), `outbox/` (relay, idempotent consumer). See `src/main/java/com/g1do/anvil`.

### Jobs: fenced claim, heartbeat, commit, reconciler

See `src/main/java/com/g1do/anvil/jobs/JobService.java`.

- Claim is one atomic statement: candidates `WHERE tenant_id + state='QUEUED' + unheld/expired lease + attempt < max_attempts ORDER BY created_at, id LIMIT 20 FOR UPDATE SKIP LOCKED` (uses `jobs_claim_idx`), then `QUEUED -> RUNNING` with `fencing_token + 1`, `attempt + 1`, `lease = clock_timestamp() + 10s`. The `attempts` ledger row (`UNIQUE(tenant_id, job_id, attempt_number)`) is written in the same transaction: zero lost, zero doubled.
- Heartbeat extends only a live lease held by the same owner and token (`RUNNING` plus `lease_expires_at > clock_timestamp()`).
- `commitSucceeded/Failed` moves `RUNNING -> SUCCEEDED/FAILED` only when id plus owner plus fencing token plus `RUNNING` all match; a stale owner affects zero rows. Terminal rows (`SUCCEEDED/FAILED/DEAD`) are additionally immutable via the `job_state_guard` trigger.
- Capability gate (issue #21): every commit requires a valid `cap_grants` row for `(tenant_id, job_id)` (`V3__cap_grants.sql`: `UNIQUE(tenant_id, job_id)`, composite `FOREIGN KEY(tenant_id, job_id)`, `RLS FORCE`). `issueCap` epoch 1 (re-issue supersedes), `revokeCap` sets `revoked=TRUE`, expiry `clock_timestamp() + 1h` (DB clock only). Missing/revoked/expired/superseded/cross-tenant denies atomically: 0 job rows, `403 NEEDS_APPROVAL`, `cap_rejected++`. Post-grant TOCTOU is residual by definition.
- `reconcile(tenant)` / `reconcileAll()` polling reconciler (intended `1s`): expired `RUNNING` with attempts left returns to `QUEUED`; at or beyond `max_attempts` moves to `DEAD`. Terminals never claimable.

Job lifecycle state diagram (Mermaid source): `detail/job-lifecycle.mmd` — render with Mermaid preview; no pasted copy in this file.

- Only the database clock (`clock_timestamp()`) decides leases. At-least-once with idempotent fenced effects; exactly-once is not asserted.

### Content store

See `src/main/java/com/g1do/anvil/cas/ContentStore.java`.

- `put(tenant, bytes)` returns `sha256(bytes)`, stored under `tenants/{tenant}/{sha}`; no cross-tenant deduplication. `get(tenant, sha)` re-verifies the hash.
- Durability order: tmp-write in destination directory → fsync file → atomic rename → fsync directory, then the `artifacts` row. Hash mismatch is a torn tail and is removed, never served (`CorruptContentException`).
- `acquire` / `release` drive `artifacts.refcount`. `collectGarbage()` deletes only `refcount = 0` rows older than `GC_grace=1h` (DB clock), re-checking each row `FOR UPDATE`; crash orphans swept by file mtime. Ambiguity retains.

### Reads: direct-ID, search, CAS gateway, presigned

See `src/main/java/com/g1do/anvil/read/ReadService.java`, `DocumentSearchController.java`, `src/main/java/com/g1do/anvil/cas/ContentStore.java` (`getForCaller`), `CasPresignedService.java`.

- Direct-ID fetches: every call sets `app.tenant` with read-back probe and queries `WHERE id = ? AND tenant_id = ?` under RLS `FORCE`. Cross-tenant IDs return empty same-as-missing (no oracle). Forged claim/commit deny via tenant-scoped `WHERE` plus capability gate.
- Search `GET /api/documents?query=&limit=&cursor`: tenant solely from `X-Tenant-Id`; `WHERE tenant_id = :t AND title ILIKE :prefix% ORDER BY created_at, id`, RLS underneath plus per-row re-check. No GIN/FTS. Cursor is a tenant-scoped document UUID; cross replay returns 0 rows. Limits default `20`, max `100`.
- CAS gateway `getForCaller(caller, resource, sha)`: denies without content when `caller != resource` or no `artifacts` row for `(caller, sha)`; traversal (`../`, `%2e%2e`) rejected before the filesystem.
- Presigned URLs (TTL `60s`, DB clock): `base64url(payload) + "." + HMAC-SHA256` binding `(tenant, sha, job, cap, epoch, expiry)`. Revoke/expire/supersede/cross denies `403 NEEDS_APPROVAL`.

### Outbox relay and consumer

See `src/main/java/com/g1do/anvil/outbox/OutboxRelay.java` and `IdempotentConsumer.java`.

- `relayOnce` per tenant polls `WHERE tenant_id = ? AND sent_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED` (uses `outbox_poll_idx`), publishes with stable identity (`outbox.id` as `event_id`), marks `sent_at` in a separate transaction after publish. Crash between publish and mark re-publishes rather than losing.
- `consume(event)` runs `INSERT INTO processed_events ON CONFLICT DO NOTHING` and applies only when the insert wins.

### Execution jail

See `src/main/java/com/g1do/anvil/exec/ExecJail.java`, `JailEgress.java`.

- Filesystem: per-tenant workspace only (`<anvil.exec.root>/<tenant>`). `resolveInWorkspace` denies absolute paths, `..`, `%`, null bytes, symlink traversals. `readWorkspaceFile(caller, resource, rel)` denies cross-tenant without oracle.
- Process: forked child, working directory = tenant workspace, cleared env (`env -i` equivalent; only `PATH=/usr/bin:/bin`, `ANVIL_TENANT`, validated extras). Shell interpreters, absolute/`..`/`%` operands, workspace executables deny. Stdout/stderr capped 1 MiB per stream. No shell.
- Egress default-deny (`JailEgress.checkAllowed`, no allowlist in v1); `HttpClient` with `followRedirects(NEVER)`. Residual: no mount/user/network namespace, no DNS-rebind re-check, no nftables DROP.

### Database

Migrations in `src/main/resources/db/migration/`: V1 durable slice (`tenants, documents, document_versions, jobs, attempts, artifacts, outbox, processed_events`, `job_state_guard`, claim/relay indexes, `claimable_jobs` views); V2 RLS `FORCE` (migrator owns, `anvil_app` `NOSUPERUSER NOBYPASSRLS`, one policy per table, `security_invoker=true` views); V3 `cap_grants`; V4 `audit_events` (see `## C3` below). Every tenant transaction sets `SET LOCAL app.tenant` via `TenantGuard.setTenant` with read-back probe; missing context reads 0 rows.

### Health and observability

See `src/main/java/com/g1do/anvil/health/HealthController.java`, `CorrelationIdFilter.java`, `src/main/resources/logback-spring.xml`, `Dockerfile`, `docker-compose.yml`.

- `GET /healthz` liveness, no dependency checks; `GET /readyz` readiness with `SELECT 1` (200 ready, 503 not-ready). Both bypass tenant auth. Same `/readyz` is the startup probe where orchestration requires it.
- Structured JSON logs (Logstash encoder) with `correlationId` from `X-Request-Id`/`X-Correlation-Id` or generated UUID, echoed as `X-Correlation-Id`; no secrets.
- `Dockerfile` multistage minimal unprivileged (`temurin:25-jre`, user `anvil:10001`). `docker-compose.yml` mirrors prod (real `postgres:18` + app). One-command start `docker compose up --build`; verify `curl localhost:8080/healthz` then `curl localhost:8080/readyz`.

## C3 — O3 slice: immutable audit log and replay source

Per-slice internals for the container under change (O3). Whole-system context is in `## C1` and `## C2` above.

Mermaid note: no C3 view file exists for this slice (absent=omitted). Whole-system views are `c1.mmd` and `c2.mmd`; O3 slice internals live in this section only.

### Status

Chain writer + read API implemented (issues #28, #29 merged). Replay endpoint with checkpoint acceleration is open (issue #30). No fencing, outbox, CAS, or isolation regression.

### Chain

See `src/main/java/com/g1do/anvil/audit/AuditChain.java`, `src/main/resources/db/migration/V4__audit_events.sql`, `src/main/java/com/g1do/anvil/submit/CanonicalCbor.java`.

- Table `audit_events(tenant_id, job_id, seq, prev_hash, event_hash, type, payload)`: per-job 0-based `seq`, `prev_hash = 'GENESIS'` for seq 0 else previous `event_hash`; `UNIQUE(job_id, seq)` plus `UNIQUE(job_id, prev_hash)` fork guard; composite `FOREIGN KEY(tenant_id, job_id)` to `jobs`; migrator-owned, app `SELECT+INSERT` only; `RLS FORCE` + `tenant_isolation`; `audit_events_immutable` trigger rejects `UPDATE`/`DELETE`.
- `event_hash = sha256_hex(type + 0x00 + job_id + 0x00 + seq + 0x00 + prev_hash + 0x00 + canonical_cbor(payload))` with the existing mapper (`SORT_PROPERTIES_ALPHABETICALLY` + `ORDER_MAP_ENTRIES_BY_KEYS`). Serialization via locking the parent `jobs` row `FOR UPDATE`.
- Logged in the same DB transaction as the effect: `submit` (`doc_id, version_id, sha256`), `claim` (`RUNNING`), `commit` (`SUCCEEDED`/`FAILED`/`DEAD` incl. reconciler `DEAD`), `cap.issue`/`cap.revoke` (epoch), `cas.put` (`artifact_sha, size_bytes`). Payloads never contain `owner, fencing_token, lease_expires_at, attempt, created_at/sent_at/updated_at` or tenant context. Not logged: `heartbeat`, reclaim, empty polls, `outbox.sent_at`. Duplicate resubmit adds no second `submit` event.

### Read API

See `src/main/java/com/g1do/anvil/audit/AuditReadService.java`, `AuditEventsController.java`.

- `GET /audit/events?job_id=&limit=&cursor` (aliases `/api/audit/events`, `/api/v1/audit/events`): caller-tenant-only chain `ORDER BY seq` (`WHERE tenant_id = ? AND job_id = ?`, RLS underneath, per-row re-check). Tenant solely from `X-Tenant-Id`; cross-tenant `job_id` returns empty `200 []` same-as-missing. Cursor is a tenant- and job-scoped event UUID (malformed `400`, foreign/missing 0 rows). Limits default `20`, max `100`. JDBC only.

### Replay contract (issue #30, open)

- `input_sha = document_versions.sha256` (canonical CBOR over `{title,mime_type,body}`); `output_sha = sha256(canonical CBOR of {job_id, terminal_state, artifact_sha256 or ""})`.
- Intact chain returns `{input_sha,output_sha,match:true}`; replays identical; replay writes no log/job rows.
- Corrupt one byte, drop one kept event, or swap `prev_hash` yields observable mismatch; dropping an unlogged heartbeat still recomputes same output.
- `job_checkpoints(job_id,seq,sha)` every 100 events per `job_id`; replay from latest checkpoint equals full replay. Cross-tenant replay denies fail-closed.

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
