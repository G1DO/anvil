# 0009 — Immutable per-job hash-chained audit log

Status: accepted. Builds on 0005 (DB-enforced tenant isolation) and 0006 (capability-gated commits); does not change fencing, outbox, CAS durability, or isolation contracts.

## Context

Replay needs a tamper-evident source: submit, claim, commit, capability-epoch, and CAS-link steps must be recorded as an append-only per-job hash chain in Postgres. Application-only logs can be rewritten, forked on duplicate delivery, or orphaned by a crash between the effect and the record.

## Decision

Record the chain in Postgres (`src/main/resources/db/migration/V4__audit_events.sql`, `src/main/java/com/g1do/anvil/audit/AuditChain.java`):

- Table `audit_events(tenant_id, job_id, seq, prev_hash, event_hash, type, payload)`: 0-based `seq`, `prev_hash = 'GENESIS'` for seq 0 else the previous `event_hash`; `UNIQUE(job_id, seq)` plus `UNIQUE(job_id, prev_hash)` (fork guard: two events cannot share one predecessor), composite `FOREIGN KEY(tenant_id, job_id)` to `jobs` (tenant-bound, no cross-tenant dedup), migrator-owned, app `SELECT+INSERT` only (`REVOKE ALL` first to defeat the V2 default-privilege grant), `RLS FORCE` + `tenant_isolation` policy, `audit_events_immutable` trigger rejecting `UPDATE`/`DELETE` for every role (cleanup uses `TRUNCATE`, which does not fire row triggers).
- Hash `sha256_hex(type + 0x00 + job_id + 0x00 + seq + 0x00 + prev_hash + 0x00 + canonical_cbor(payload))` with the existing mapper (`CanonicalCbor.encodeMap`, `SORT_PROPERTIES_ALPHABETICALLY` + `ORDER_MAP_ENTRIES_BY_KEYS`). The issue lists type/job/seq/payload; `prev_hash` is included for chain binding (linkage, not in the excluded set). Excluded always from payload (enforced by `AuditChain`): `owner, fencing_token, lease_expires_at, attempt, created_at/sent_at/updated_at`, tenant context.
- Logged in the same DB transaction as the effect (job-row `FOR UPDATE` serializes appends; the tail is read without `FOR UPDATE` on the child because that clause requires `UPDATE` privilege the runtime does not hold): `submit` (`SubmitService`), `claim` + `commit` (`SUCCEEDED`/`FAILED`, plus reconciler `DEAD` via `UPDATE ... RETURNING`), `cap.issue` (`epoch, cap_id`) / `cap.revoke` (`epoch`), `cas.put` (`artifact_sha, size_bytes` via `ContentStore.putForJob/linkArtifactToJob`). Not logged: `heartbeat`, reconciler reclaim (`RUNNING -> QUEUED`), empty polls, `outbox.sent_at`. Duplicate resubmit returns the winner without a second `submit` event; fencing misses and cap denials append nothing (rollback leaves no orphan).
- Raw `ContentStore.put/acquire` without a job stays unaudited for non-job content; the job-scoped variants are the audited path.

## Consequences

- Replay has a per-job tamper-evident source: fork/duplicate-position rejected by `UNIQUE`, mutation rejected by trigger + grants, cross-tenant reference rejected by composite FK, missing context reads 0 rows; proven by `AuditChainTest` (shape, hash linkage, same-txn crash atomicity, no-fork, non-logged steps, immutability, RLS). `JobServiceTest`, `RlsLeakTest`, `SubmitIdempotencyTest`, `OutboxRelayTest` stay green (cleanup now `TRUNCATE`s `audit_events` first; `TRUNCATE` lists include the child so parent truncates do not fail on the FK).
- Residual: audit reads/replay endpoint/checkpoints/CLI/offline verifier deferred (out of scope); revoke without an existing grant appends nothing (no epoch); `SELECT ... FOR UPDATE` on `audit_events` is intentionally avoided (would require `UPDATE` privilege).
- Rejected: separate audit transaction (would orphan), worker-clock timestamps in the hash, `UPDATE`/`DELETE` grants for the runtime role, tail locking on the child table.
