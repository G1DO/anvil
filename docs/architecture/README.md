# Architecture

Mermaid diagrams, one file each. `task check:diagrams` renders them all in the gate. Update a diagram in the same PR as the change it shows.

| Diagram | Shows |
| --- | --- |
| [context.mmd](context.mmd) | ANVIL, its users (tenant client, operator), and what it talks to: PostgreSQL, the content store, exec workspaces, the model provider, exported chain files |
| [containers.mmd](containers.mmd) | The running parts (API service, operator console, workers, operator CLI, exec jail, Postgres, content store), the model provider, exported chains, and the trust boundaries between them |
| [components.mmd](components.mmd) | The building blocks inside the API service (including the operator console and extraction path), the operator CLI (replay, export, offline verify), the workers, and the exec jail |
| [flow-submit-claim-commit.mmd](flow-submit-claim-commit.mmd) | Submit → claim → commit, including a duplicate submit, an invalid capability, and a zombie worker with a stale fencing token |
| [flow-reconcile-outbox.mmd](flow-reconcile-outbox.mmd) | Reconciler and outbox relay, including a failed publish and a redelivery that applies once |
| [state-job.mmd](state-job.mmd) | Job lifecycle: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `DEAD` |
| [state-capability.mmd](state-capability.mmd) | Capability grant lifecycle: issued, revoked, expired, deleted by `confirmTimeline`, and re-issued |
| [deployment.mmd](deployment.mmd) | Where each container runs: one Compose host with the app image and PostgreSQL |
| `data-model.mmd` | Tables and relationships, generated from the dev database by `task diagrams`; not committed |

The exec jail exists and is tested, but no production code calls it yet. The diagrams show that link dashed.

## Numbers and Constraints

These are the system-wide numbers that the schema notes refer to. Each names the file that sets it and the test that holds it.

| Number | Set in | Held by |
| --- | --- | --- |
| Claim batch: `LIMIT 20` | `JobService.CLAIM_LIMIT` and `CLAIM_SQL` in `src/main/java/com/g1do/anvil/jobs/JobService.java` | `JobServiceTest.claimTimingsMatchIssueConstraints` |
| Relay batch: `LIMIT 100` | `OutboxRelay.POLL_LIMIT` and `POLL_BY_TENANT_SQL` in `src/main/java/com/g1do/anvil/outbox/OutboxRelay.java` | `OutboxRelayTest.relayBatchLimitIsOneHundred` |
| Checkpoint interval: every 100 events | `AuditChain.CHECKPOINT_INTERVAL` in `src/main/java/com/g1do/anvil/audit/AuditChain.java` | `ReplayTest.checkpointsEvery100AndReplayEqualsFull` |
| Capability grant lifetime: one hour | `ISSUE_CAP_SQL` in `src/main/java/com/g1do/anvil/jobs/JobService.java` (`clock_timestamp() + INTERVAL '1 hour'`) | No test holds the one-hour value; `CapRevokeMidRunTest.expiredCapDeniesCommit` holds the expiry denial. |

No recovery-time or data-loss target is committed; the restore drill measures them but does not promise them (see [record 0016](../decisions/0016-restore-timeline-gate.md)).

## Schema notes

The SQL in `src/main/resources/db/migration/` is the schema. These are the rules the SQL alone doesn't make obvious.

- **Enforced by the application, not the database:**
  - Audit hash linkage: event 0 links to `GENESIS`, every later event to the previous `event_hash`, and `seq` gaps are allowed. This lives in `AuditChain.append`. The database only rejects a duplicate `(job_id, seq)` or `(job_id, prev_hash)`.
  - The capability check (current epoch, not revoked, not expired). It runs in `JobService`, in the commit's transaction, with `SELECT ... FOR UPDATE` and the database clock.
  - The content store's write order (temp write → fsync → rename → row). It lives in `ContentStore`.
- **Checkpoints:** one `job_checkpoints` row every 100 events per job (`seq % 100 == 0`, `seq > 0`). Its `sha` is the chain's `event_hash` at that boundary, not the job's `output_sha`. It's written in the same transaction as the audited effect. V10 adds the prefix replay summary (`artifact_sha`, `input_sha`, `commit_seq`/`commit_outcome`), so replay reads the latest checkpoint and the events after it rather than the whole prefix (record 0027).
- **Append-only tables:** `audit_events` and `job_checkpoints` reject `UPDATE` and `DELETE`, which also blocks the cascade from `jobs`. Clean-up must `TRUNCATE` both before deleting jobs. The runtime role has only `SELECT` and `INSERT` on them.
- **Tenants:** `tenants` has no row-level security, because it's the authentication lookup. It's seeded with `t_single`. Every other table except `db_timeline` carries `tenant_id`, with composite foreign keys to `UNIQUE(tenant_id, id)` targets.
- **Timeline:** `db_timeline` holds at most one row, the `(system_identifier, timeline_id)` the service last confirmed. V6 doesn't seed it: a missing row refuses like a mismatch until the first confirm. It has no `tenant_id` and no row-level security. `TimelineGuard` compares it with the server's on every claim, heartbeat and commit, and only `JobService.confirmTimeline` changes it, in the same transaction that raises tokens and deletes grants after a restore (record 0016).
- **Outbox:** `processed_events` has no foreign key to `outbox`, so consumer progress survives outbox retention. `ON CONFLICT DO NOTHING` is the consumer's dedup contract.
- **Clocks:** `clock_timestamp()` is the only clock for leases, capabilities and presigned URLs. Claim due times compare `next_run_at` with `now()`, the claim transaction's start on the database clock, so the index can bound the scan. `created_at` and `next_run_at` default to `now()`; `created_at` is never hashed.
- **Batch sizes:** claim (`LIMIT 20`) and relay (`LIMIT 100`) are contracts of their canonical queries, backed by partial indexes: `jobs_claim_order_idx` on queued jobs in claim order, `outbox_poll_idx` on unsent rows, and `artifacts_gc_idx` on unreferenced content. `jobs_claim_idx` (V1) no longer backs the claim.
- **Fair claim:** `tenants.max_running` is each tenant's quota of `RUNNING` jobs, default 1000. Claims for one tenant serialize on a transaction-scoped advisory lock before counting. A claim takes only jobs with `next_run_at <= now()`, in the order `(penalty, next_run_at, created_at, id)`, where the penalty is the tenant's `RUNNING` count. Rows that existed before V7 share the migration's `next_run_at` and keep their arrival order (record 0020).
- **Extraction (V8):** `extractions` has one row per `(tenant_id, version_id, prompt_sha256, model)`, so a version is extracted once per tenant, prompt and model, and a repeat returns the stored result. The key holds the tenant, so identical content in two tenants never shares a result. `extracted_facts` is the index; the application inserts only facts whose citation passed the exact substring check, and `GET /api/documents/{id}/facts` serves facts only from it. `extraction_dead_letters` keeps quarantined input and facts with a reason; no route serves its rows, and `POST …/extract` reports only a count. All three have RLS `FORCE`, cascade from `documents`, and are append-only for the runtime role (record 0022).
- **Extraction cost controls (V9):** `extractions.source` says where a result came from:
  - `MODEL`: the extractor, or a screen before it.
  - `CACHE`: copied from an earlier accepted model result of the same tenant with the same `content_sha256`, prompt and model. `cache_of` names that result.
  - `RULES`: the fallback, marked with `fallback_reason` and stored under the rules' own model and hash.

  The cache is those rows, so there is no cross-tenant hit. The other V9 tables:
  - `extraction_revocations`: revoked results, never served or reused.
  - `extraction_budgets`: each tenant's model calls. No row means none. The runtime role may only spend (trigger).
  - `extraction_switch`: one row, the kill-switch. It holds no tenant data and has no RLS.

  Operators write all three; the runtime role only reads revocations and the switch (record 0023).
- **Capability grants:** they expire after one hour (`V3`).
- **Dead jobs:** keep their last owner, token and lease. They're never offered again: the reconciler moves a job with `attempt >= max_attempts` to `DEAD`, and a claim takes only `QUEUED` rows with `attempt < max_attempts`.

Check the live catalog with `flyway info`, `\d <table>`, `pg_policies` and `pg_indexes`.
