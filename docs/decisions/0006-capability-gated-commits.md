# 0006 — Capability-gated commits with revocable tenant-bound grants

Status: accepted. Builds on 0005 (DB-enforced tenant isolation); does not change claim/heartbeat/reconciler fencing.

## Context

Commits (`RUNNING -> SUCCEEDED/FAILED`) were fenced on `(id, tenant_id, owner, fencing_token, RUNNING)` but had no revocable authority: once claimed, nothing could deny the effect mid-run, and a grant for one tenant could not be distinguished from another. Issue #21 requires every commit to be gated so a mid-run revoke denies atomically (0 rows, caller denial, rejection counted).

## Decision

Gate every commit on `cap_grants` (`src/main/resources/db/migration/V3__cap_grants.sql`, `src/main/java/com/g1do/anvil/jobs/JobService.java`):

- Table `cap_grants(id, tenant_id, job_id, epoch, revoked, expires_at)`: one authoritative row per `(tenant_id, job_id)` (`UNIQUE`), composite `FOREIGN KEY(tenant_id, job_id)` to `jobs` (tenant-bound, no cross-tenant dedup), migrator-owned, app DML, `RLS FORCE` + `tenant_isolation` policy (`tenant_id = current_setting('app.tenant', true)`).
- Lifecycle: `issueCap` inserts epoch 1 (`expires_at = clock_timestamp() + 1h`, DB clock only); re-issue bumps `epoch + 1`, clears `revoked`, refreshes expiry (supersedes old epoch); `revokeCap` sets `revoked=TRUE`. Expiry tests use `UPDATE ... clock_timestamp() - interval` (DB clock, never worker clock).
- Commit check in the same transaction as the effect, after `TenantGuard.setTenant` and before any `jobs` write: lock the grant `FOR UPDATE` (`WHERE tenant_id = ? AND job_id = ?` for the current-grant path, `WHERE id = ? AND tenant_id = ? AND job_id = ?` for the explicit-cap path) and validate `epoch + revoked + expires_at` with `clock_timestamp()` only. Missing/revoked/expired/superseded (epoch mismatch)/cross-tenant (zero rows for `id+tenant+job`) throws `CapDeniedException` (`403 NEEDS_APPROVAL`), commits 0 job rows, increments `cap_rejected` (`JobService.capRejectedCount`). Fencing failures still return `false` with no exception and no counter.
- Every statement keeps `WHERE tenant_id = ?` in addition to RLS.

## Consequences

- Mid-run revoke/expire/supersede is always observed when committed before the commit txn's `FOR UPDATE` lock (same txn holds the row lock across check and effect: 0 rows, `403`, counted); proven by `CapRevokeMidRunTest` (revoke/expire/supersede/cross-tenant denials + valid-cap controls + catalog/RLS/FK proofs). `JobServiceTest` (fencing, zombie, reconciler, gate-scale) and `RlsLeakTest` stay green.
- Residual TOCTOU: revoke committed after the commit txn commits is too late by definition; worker-side caching between issue and commit must re-present the held epoch (stale epoch denies as superseded). Documented as residual, not closed.
- No cross-tenant dedup introduced: grants, CAS (`artifacts` per-tenant PK), and content paths stay per-tenant.
- Rejected: worker-clock expiry, session-level tenant state, global commit without `tenant_id`, in-memory-only revocation (must be durable + locked in the commit txn).
