# 0010 — Deterministic replay with checkpoint acceleration

Status: accepted. Builds on 0009 (immutable audit chain) and 0007 (fail-closed reads); does not change fencing, outbox, CAS durability, or isolation contracts.

## Context

Any job must recompute bit-for-bit from its hash chain via API as `{input_sha,output_sha,match}` read-only, with checkpoint acceleration for long chains and tamper detection (never silent success). Owner sign-off G1DO 2026-10-21 (endpoint, artifact, terminal, checkpoint-sha, output-sha, missing handling) plus G3 PASS G1DO 2026-10-22 (Auth/Schema-compat/Contract checklists, P4 N).

## Decision

Replay from Postgres (`src/main/resources/db/migration/V5__job_checkpoints.sql`, `src/main/java/com/g1do/anvil/replay/ReplayService.java`, `src/main/java/com/g1do/anvil/replay/ReplayController.java`):

- Table `job_checkpoints(tenant_id, job_id, seq, sha)`: one row every 100 events per job (`seq % 100 == 0`, `seq > 0`), `sha` is the chain `event_hash` at the boundary (not `output_sha`); `UNIQUE(job_id, seq)` + composite `FOREIGN KEY(tenant_id, job_id)` to `jobs` (tenant-bound, no cross-tenant dedup), migrator-owned, app `SELECT+INSERT` only (`REVOKE ALL` first to defeat the V2 default-privilege grant), `RLS FORCE` + `tenant_isolation` policy, `job_checkpoints_immutable` trigger rejecting `UPDATE`/`DELETE` for every role (cleanup uses `TRUNCATE`, which does not fire row triggers). Interval 100 owned here.
- Writer: `AuditChain.append` inserts the checkpoint in the same txn as the audited effect when `seq > 0 && seq % 100 == 0` (`sha = event_hash`). No orphan in either direction; fencing misses and cap denials append nothing.
- Reader: `ReplayService.replay` (checkpoint path) + `replayFull` (comparison path) in one read-only txn under `TenantGuard.setTenant` (`WHERE tenant_id = ?` plus RLS plus per-row re-check). `input_sha` is `document_versions.sha256`; `artifact_sha256` is the last `cas.put.artifact_sha` in the chain else `""`; `terminal_state` is current `jobs.state` (must be `SUCCEEDED/FAILED/DEAD`, else `match:false`); `output_sha = sha256(canonical CBOR {job_id:str, terminal_state:str, artifact_sha256:str})` via `CanonicalCbor.encodeMap`. Chain verification recomputes each `event_hash` via `AuditChain.computeHash`; any corrupt byte, dropped kept event (seq gap), or swapped `prev_hash` yields `match:false` with the offending `seq`. Checkpoint path reads the latest checkpoint `<= max_seq`, consistency-checks `sha == event_hash` at that seq, verifies the full chain (same-sha guarantee; prefix-skip is residual), and returns `checkpointSeqUsed` as proof the path was taken.
- HTTP: `GET /audit/replay?job_id=` (aliases `/api/audit/replay`, `/api/v1/audit/replay`), tenant solely from `X-Tenant-Id` (`TenantAuthFilter` on `/audit/*`), `limit/cursor` accepted-but-ignored like reads, `tenant` params ignored. Own-tenant terminal returns `200 {input_sha,output_sha,match}`; own-tenant non-terminal returns `200 match:false`; missing/cross-tenant returns generic `404` (same message, no bytes, no oracle); unknown tenant `401`; malformed `400`. JDBC only, no filesystem access.
- Contract: additive-only new path + `ReplayResult` schema in `contracts/openapi.yaml`, version stays `0.1.0` (`oasdiff` gate).

## Consequences

- Intact chains return `match:true` identically on repeat with zero log/job writes; tamper surfaces as `match:false` with seq; long chains (250+ events) write checkpoints at 100/200 and checkpoint replay equals full replay with proof taken across 2 boundaries; cross-tenant denies fail-closed; proven by `ReplayTest` (happy-path, idempotence, tamper, heartbeat-identical, checkpoints, isolation, migration shape, HTTP matrix). O1/O2 suites stay green (multi-table `TRUNCATE`s now list `job_checkpoints` first so parent truncates do not fail on the FK).
- Residual: checkpoint prefix-skip optimization deferred (current path still verifies full chain plus checkpoint consistency; ChainDoS `p99<500ms` out of scope); revoke without grant appends nothing (inherited); offline verifier/CLI/React polish deferred.
- Rejected: `output_sha` from `output_sha` chain (uses `event_hash` instead), checkpoint `sha` as `output_sha`, separate checkpoint transaction (would orphan), worker-clock or random owner in hashes (`JobService` runtime fields excluded by contract).
