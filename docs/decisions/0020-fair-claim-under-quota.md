# 0020 — Fair claim across tenants under a per-tenant quota

Status: proposed 2026-10-31 (#35). Changes how record 0002's claim picks a job. Fencing, leases and the reconciler are unchanged.

## Context

Record 0002's claim takes one tenant's queued jobs in arrival order (`ORDER BY created_at, id`) with no limit. A worker polls a tenant by name, and nothing caps how many of the pool's workers one tenant holds. A tenant that floods the queue can keep every worker busy, so other tenants' jobs wait behind it. Record 0015 left fair claiming to #35.

Constraints from earlier records:
- **Isolation (0005).** Every transaction runs as `anvil_app` under row-level security, with one tenant context per statement. There are no `SECURITY DEFINER` functions, so no single query can see every tenant's jobs.
- **Fencing (0002, 0011).** Claim must stay one atomic statement that raises token and attempt together, with the ledger row and audit event in the same transaction.
- **Clocks.** The database clock is the only clock that decides anything.

## Decision

- **Quota.** `tenants.max_running` (V7, default 1000, at least 1) is the most jobs a tenant may hold in `RUNNING` at once. Every claim counts the tenant's `RUNNING` rows and claims nothing at the quota. A job whose lease expired still counts until the reconciler releases it, because its worker can still commit until then.
- **Exact under concurrency.** A claim first takes `pg_advisory_xact_lock(hashtextextended('anvil.claim:' || tenant, 0))`, then counts. Claims are the only step that adds a `RUNNING` row, so claims for one tenant serialize and can't overshoot. Each statement in read committed takes a new snapshot, so the count sees every claim that held the lock before. A hash collision only serializes two tenants' claims. A row lock on `tenants` would need `UPDATE` on a table without row-level security, so it was rejected.
- **Due time.** `jobs.next_run_at` (V7, default `now()`) is when a job may first be claimed. A claim takes only rows with `next_run_at <= clock_timestamp()`. Submit leaves the default, and the reconciler doesn't change it when it releases a job.
- **Order.** Jobs are claimed in the order `(penalty, next_run_at, created_at, id)`:
  - **Penalty:** the tenant's `RUNNING` count at the time of the claim. The tenant running fewer jobs goes first. This is a strict first key, not a weight, so a tenant with nothing running always beats one that has work in flight.
  - **`next_run_at`:** earliest first, on the database clock.
  - **Tiebreak:** `created_at` (arrival), then `id` in Postgres `uuid` order (bytewise, the order of the lowercase text form).
  - The index `jobs_claim_order_idx (tenant_id, next_run_at, created_at, id) WHERE state = 'QUEUED'` serves it.
- **Two entry points, one claim.** `claim(tenant, owner)` keeps its signature and applies the order within one tenant, where the penalty is the same for every job. The new `claimNext(owner)` applies it across tenants:
  - One transaction reads every tenant's quota, `RUNNING` count and head job, one tenant context per statement, as `confirmTimeline` does (record 0016).
  - It drops tenants at their quota or with nothing due, and ranks the rest by their head job's order key.
  - It tries each tenant in rank order with the same claim transaction as `claim`, which re-checks the quota under the lock. The first job claimed wins.
  - Both entry points go through the same lock, quota check and claim statement, so the fault proofs that use `claim` run on the new path. `FaultMatrixTest`'s multi-worker cases use `claimNext`.
- **Migration.** Rows that exist when V7 runs all get the migration's time as `next_run_at`, so they tie and keep their arrival order through the tiebreak. `claimable_jobs` gains the `next_run_at` predicate and stays `security_invoker`.

Alternatives considered:
- **One cross-tenant query** (a view or function over every tenant's jobs). Without `SECURITY DEFINER` or `BYPASSRLS` it would see no rows, and adding either undoes record 0005.
- **A summary table of per-tenant counts without row-level security**, kept up by triggers. That puts every tenant's queue depth in a table any tenant's transaction can read, and adds a write to every state change.
- **A weighted penalty** (for example `next_run_at + running × interval`). It lets an old enough flood job beat a quiet tenant's new job, so a quiet tenant's wait depends on how far behind the flood is.
- **A quota alone, with arrival order.** If the quota is at or above the pool size, a flood still holds every worker. `FairClaimTest.floodAboveThePoolStillLetsAQuietTenantThrough` is that case.

## Consequences

- **Proven by** `FairClaimTest`:
  - **Quota:** 64 simultaneous claims against a quota of 5 get exactly 5, both through `claim` and through `claimNext`. Without the lock the same race got 10.
  - **Order:** `next_run_at` on the database clock, then `created_at`, then `id`. Across tenants, the penalty comes first.
  - **Flood:** 8 workers drain an 800-job flood while a quiet tenant submits 20 jobs. Each quiet job is claimed within 16 flood claims of its submit (runs here saw at most 3), and all finish while the flood still has work queued. This holds with the flood's quota at 3, and at 1000, where the flood fills the pool. Without the penalty, a quiet job waited 420 flood claims.
  - **Unchanged behaviour:** fencing, leases, the reconciler, terminal immutability and capability denials hold on `claimNext`.
  - `RlsLeakTest` runs `claimNext` as `anvil_app`.
- **The order is exact for one worker and best-effort for many.** Tenants are ranked from one snapshot, and another worker can take a tenant's head job before this worker's claim. The quota is always exact.
- **Cost.** `claimNext` reads every tenant in one transaction, three statements each, so its time grows with the number of tenants. Claims for one tenant serialize on its lock, which caps one tenant's claim rate at roughly one claim transaction at a time. In the flood test that limit, not the pool size, decided how many flood jobs ran at once until each job was held for 40 ms.
- **The default quota (1000) caps nothing in practice.** No production worker pool exists yet to size it against. Without a lower quota, fairness comes from the penalty: a flood can still take every idle worker, but each worker that frees up goes to a tenant running fewer jobs. Operators set `max_running` per tenant.
- **The quota counts jobs, not cost.** A tenant with few long or heavy jobs holds its share for longer.
- `jobs_claim_idx` (V1) no longer backs the claim. Dropping it is a contract step of its own.
