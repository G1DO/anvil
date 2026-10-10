# 0002 — Fenced claim, heartbeat, commit, and reconciler

Status: accepted.

## Context

Multiple workers poll for the same `QUEUED` jobs and leases expire. Without fencing, a slow or suspended worker can commit over the worker that legitimately took over, and a crash between selection and bookkeeping can lose or double the attempt record.

## Decision

Fence every mutation on `(id, tenant_id, owner, fencing_token, expected state)` with the database clock as the only lease clock (`src/main/java/com/g1do/anvil/jobs/JobService.java`):

- Claim is one atomic CTE: `LIMIT 20 FOR UPDATE SKIP LOCKED`, `fencing_token + 1` and `attempt + 1` together, lease `clock_timestamp() + 10s`, plus the `attempts` ledger row in the same transaction.
- Heartbeat extends only a live lease held by the same owner and token; commit moves `RUNNING` to terminal only on a full fencing match, otherwise zero rows.
- Polling reconciler reclaims expired leases to `QUEUED` or exhausts to `DEAD`; timings `lease_ttl=10s, heartbeat_every=3s, max_attempts=5, reconciler_poll=1s`.

## Consequences

- At-least-once execution with idempotent fenced effects; exactly-once is explicitly not asserted.
- Stale workers safely affect zero rows; proven by `JobServiceTest` including gate-scale (zero double-claims/2000) and real-suspend zombie resume.
- Rejected: broker/Redis queue and worker-clock leases; polling keeps the current slice operable without new infrastructure.
