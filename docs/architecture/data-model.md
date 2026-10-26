# Data model — ANVIL

Source files: `src/main/resources/db/migration/V1__durable_v1_schema.sql` through `V5__job_checkpoints.sql`. This note states intent and relationships; SQL is canonical. DB-enforced vs app-enforced are labeled — they differ.

## Tables (purpose + key)
- `tenants(id)` — auth lookup target only; no RLS by design (`V2` comment), `CHECK (id <> '')`, seeded `t_single` (`V1`).
- `documents(id, tenant_id, title, mime_type)` + `document_versions(id, tenant_id, document_id, sha256)` — versions of a doc; `sha256` is the app's canonical-CBOR hash (see `CanonicalCbor.java`; `V1` pins only lowercase-hex shape).
- `jobs(id, tenant_id, state, owner, fencing_token, attempt, lease_expires_at, idempotency_key)` — active-jobs store, all states; `UNIQUE(tenant_id, idempotency_key)`; terminals immutable via `job_state_guard` (INSERTs may seed any valid state; same-state non-terminal updates such as heartbeat allowed).
- `attempts(tenant_id, job_id, attempt_number)` — exactly-once ledger per claim; `UNIQUE(tenant_id, job_id, attempt_number)`.
- `artifacts(tenant_id, sha256, refcount)` — per-tenant content, no cross-tenant dedup; path `tenants/{tenant}/{sha}` is the PK shape; crash-safe write protocol (tmp-write → fsync → rename → row) is `ContentStore.java`, not SQL.
- `outbox(id, tenant_id, sent_at)` + `processed_events(event_id)` — at-least-once relay + idempotent consumer; `ON CONFLICT DO NOTHING` is the consumer contract (`IdempotentConsumer.java`); `processed_events` has no FK to `outbox` so progress survives retention.
- `cap_grants(id, tenant_id, job_id, epoch, revoked, expires_at)` — capability epochs; `UNIQUE(tenant_id, job_id)` + composite FK to `jobs`; the valid-current-epoch/non-revoked/non-expired check runs in `JobService` in the same transaction (`SELECT ... FOR UPDATE` + DB clock), not as a CHECK.
- `audit_events(id, tenant_id, job_id, seq, prev_hash, event_hash, type, payload JSONB)` — append-only chain; `UNIQUE(job_id, seq)` + `UNIQUE(job_id, prev_hash)` reject duplicate position/fork but do not enforce hash linkage — linkage (`GENESIS` at 0, else prior `event_hash`; `seq` gaps allowed) is `AuditChain.append`. `type IN ('submit','claim','commit','cap.issue','cap.revoke','cas.put')`; payload excludes owner/token/lease/attempt/timestamps/tenant context. Cleanup must `TRUNCATE audit_events` before deleting jobs: row `DELETE` (including FK-cascade from `jobs`) is rejected by the immutable trigger.
- `job_checkpoints(id, tenant_id, job_id, seq, sha)` — replay acceleration index (issue #30); one row every 100 events per job (`seq % 100 == 0`, `seq > 0`), `sha` is the chain `event_hash` at the boundary (not `output_sha`); `UNIQUE(job_id, seq)` + composite FK to `jobs`; written in the same txn as the audited effect by `AuditChain.append`. Append-only like `audit_events` (trigger rejects `UPDATE`/`DELETE`); cleanup must `TRUNCATE job_checkpoints` (and `audit_events`) before deleting jobs. Interval 100 owned here.

## Isolation (DB-enforced)
- `RLS FORCE` + one permissive `tenant_isolation` policy per table, `FOR ALL TO PUBLIC USING (tenant_id = current_setting('app.tenant', true)) WITH CHECK` (same expression): all ten (`documents, document_versions, jobs, attempts, artifacts, outbox, processed_events, audit_events, cap_grants, job_checkpoints`).
- Runtime `anvil_app` is `NOSUPERUSER NOBYPASSRLS`; migrator owns tables/views; views `active_jobs, claimable_jobs, unclaimed_outbox` are `security_invoker=true`, no `SECURITY DEFINER`. Runtime holds full DML except `audit_events` and `job_checkpoints` (`SELECT+INSERT` only — defeats the `V2` default-privilege grant). `SET LOCAL app.tenant` + read-back probe is `TenantGuard.java` (runtime); fail-closed 0 rows on missing context is DDL-enforced by `current_setting(...,true)` NULL semantics.

## Indexes (rationale: which query/policy)
- `jobs_claim_idx (tenant_id,created_at,id) WHERE state='QUEUED'` — claim scan; `jobs_running_lookup_idx`, `jobs_lease_expiry_idx` — reconciler/heartbeat.
- `outbox_poll_idx (id) WHERE sent_at IS NULL` — relay poll (tenant-scoped variant still uses it).
- `audit_events_job_seq_idx (job_id,seq)`, `audit_events_tenant_job_seq_idx (tenant_id,job_id,seq)` — chain reads; `job_checkpoints_job_seq_idx (job_id,seq)`, `job_checkpoints_tenant_job_seq_idx (tenant_id,job_id,seq)` — checkpoint reads; `documents_tenant_idx`, `document_versions_lookup_idx`, `attempts_job_lookup_idx`, `artifacts_gc_idx WHERE refcount=0`, `processed_events_tenant_idx` — lookups + GC.
- Claim/relay `LIMIT 20/100` are canonical-query contracts (`V1` comments), not CHECKs; `clock_timestamp()` is the only lease/cap/presigned clock (`created_at` uses `now()` and is never hashed).

## Verify (catalogs, not prose)
- `flyway info`; `\d jobs` / `\d audit_events` / `\d job_checkpoints`; `select * from pg_policies where tablename in (...)`; `select * from pg_indexes where tablename in (...)`.
- Views: `active_jobs`, `claimable_jobs` (lease predicate `clock_timestamp()`), `unclaimed_outbox`.
- Residuals: post-grant TOCTOU by definition; `cap_grants` expiry window is one hour per `V3` comment; see `docs/decisions/0001-durable-v1-schema.md`, `0005-db-enforced-tenant-isolation.md`, `0009-immutable-audit-chain.md`, `0010-deterministic-replay.md`, and `docs/security/threat-model.md`. Diagrams: canonical list in `index.md` (`c1.mmd`, `c2.mmd`, C3/detail, data, deployment).
