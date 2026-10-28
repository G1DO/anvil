# Architecture

Mermaid diagrams, one file each. `task check:diagrams` renders them all in the gate. Update a diagram in the same PR as the change it shows.

| Diagram | Shows |
| --- | --- |
| [context.mmd](context.mmd) | ANVIL, its users (tenant client, operator), and what it talks to: PostgreSQL, the content store, exec workspaces |
| [containers.mmd](containers.mmd) | The running parts (API service, workers, exec jail, Postgres, content store) and the trust boundaries between them |
| [components.mmd](components.mmd) | The building blocks inside the API service, the workers, and the exec jail |
| [flow-submit-claim-commit.mmd](flow-submit-claim-commit.mmd) | Submit → claim → commit, including a duplicate submit, an invalid capability, and a zombie worker with a stale fencing token |
| [flow-reconcile-outbox.mmd](flow-reconcile-outbox.mmd) | Reconciler and outbox relay, including a failed publish and a redelivery that applies once |
| [state-job.mmd](state-job.mmd) | Job lifecycle: `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `DEAD` |
| [deployment.mmd](deployment.mmd) | Where each container runs: one Compose host with the app image and PostgreSQL |
| `data-model.mmd` | Tables and relationships, generated from the dev database by `task diagrams`; not committed |

The exec jail exists and is tested, but no production code calls it yet. The diagrams show that link dashed.

## Schema notes

The SQL in `src/main/resources/db/migration/` is the schema. These are the rules the SQL alone doesn't make obvious.

- **Enforced by the application, not the database:**
  - Audit hash linkage: event 0 links to `GENESIS`, every later event to the previous `event_hash`, and `seq` gaps are allowed. This lives in `AuditChain.append`. The database only rejects a duplicate `(job_id, seq)` or `(job_id, prev_hash)`.
  - The capability check (current epoch, not revoked, not expired). It runs in `JobService`, in the commit's transaction, with `SELECT ... FOR UPDATE` and the database clock.
  - The content store's write order (temp write → fsync → rename → row). It lives in `ContentStore`.
- **Checkpoints:** one `job_checkpoints` row every 100 events per job (`seq % 100 == 0`, `seq > 0`). Its `sha` is the chain's `event_hash` at that boundary, not the job's `output_sha`. It's written in the same transaction as the audited effect.
- **Append-only tables:** `audit_events` and `job_checkpoints` reject `UPDATE` and `DELETE`, which also blocks the cascade from `jobs`. Clean-up must `TRUNCATE` both before deleting jobs. The runtime role has only `SELECT` and `INSERT` on them.
- **Tenants:** `tenants` has no row-level security, because it's the authentication lookup. It's seeded with `t_single`. Every other table except `db_timeline` carries `tenant_id`, with composite foreign keys to `UNIQUE(tenant_id, id)` targets.
- **Timeline:** `db_timeline` holds one row, the `(system_identifier, timeline_id)` the service last confirmed. It has no `tenant_id` and no row-level security. `TimelineGuard` compares it with the server's on every claim, heartbeat and commit, and only `JobService.confirmTimeline` changes it, in the same transaction that raises tokens and deletes grants after a restore (record 0016).
- **Outbox:** `processed_events` has no foreign key to `outbox`, so consumer progress survives outbox retention. `ON CONFLICT DO NOTHING` is the consumer's dedup contract.
- **Clocks:** `clock_timestamp()` is the only clock for leases, capabilities and presigned URLs. `created_at` uses `now()` and is never hashed.
- **Batch sizes:** claim (`LIMIT 20`) and relay (`LIMIT 100`) are contracts of their canonical queries, backed by partial indexes: `jobs_claim_idx` on queued jobs, `outbox_poll_idx` on unsent rows, and `artifacts_gc_idx` on unreferenced content.
- **Capability grants:** they expire after one hour (`V3`).
- **Dead jobs:** keep their last owner, token and lease. They're never offered again; see #35.

Check the live catalog with `flyway info`, `\d <table>`, `pg_policies` and `pg_indexes`.
