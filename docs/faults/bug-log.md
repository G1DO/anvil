# 3 real claim/relay/CAS bugs for #32 (disjoint from seeded breaks)

Scope: real observations on containerized PG (`postgres:18` via Testcontainers),
disjoint from seeded `BROKEN_FENCE` (ignores token), `BROKEN_LEASE` (no lease /
non-atomic double claim), `BROKEN_RELAY` (mark-before-publish loss). No prod
target, no secrets, no migration.

## BUG-01 — RelayCrashChild unscoped poll (relay, test fidelity)

- Seed: `FaultProofTest#restartLosesNothingAndDedupesToOneApplyRealKill`,
  `OutboxRelayTest#relayKillBetweenPublishAndMarkRepublishesSameIdentityWithSingleApply`
  on `t_single`, single outbox row.
- Trace:
  - `src/test/java/com/g1do/anvil/RelayCrashChild.java:47-49`
    `SELECT id, tenant_id, job_id FROM outbox WHERE sent_at IS NULL ORDER BY id LIMIT 1`
    (no `WHERE tenant_id = ?`), vs production
    `src/main/java/com/g1do/anvil/outbox/OutboxRelay.java:60-68`
    `WHERE tenant_id = ? AND sent_at IS NULL ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED`.
  - Post-kill durable state still exact: `sent_at IS NULL` + 1 `processed_events`
    (`FaultProofTest` asserts both), then restart re-publishes same `event_id`
    with single apply.
- Impact: test-only; single-tenant suite cannot steal another tenant's row, but a
  future multi-tenant fault test could poll the wrong tenant's event.
- Decision: quarantine (no code change in this issue to avoid touching proven
  kill fidelity; production relay stays tenant-scoped). Fix if multi-tenant fault
  tests land: pass tenant to child and add `WHERE tenant_id = ?`.

## BUG-02 — Reconciler DEAD retains owner/token/lease (claim, residual)

- Seed: exhausted job (`attempt >= max_attempts`), `RUNNING` expired, on
  containerized PG; `JobService.reconcile/ reconcileAll`.
- Trace:
  - `src/main/java/com/g1do/anvil/jobs/JobService.java:579-584`
    `UPDATE jobs SET state='DEAD' ... RETURNING id` keeps `owner`,
    `fencing_token`, `lease_expires_at` on the terminal row.
  - `SELECT state, owner FROM jobs WHERE id = ?` after deaden shows
    `DEAD` with stale owner (verified in `FaultProofTest` ledger path and
    `JobServiceTest#reconcilerReleasesAbandonedAndDeadensExhausted`).
  - Claim path still safe: `claimable_jobs` view filters `state='QUEUED'`,
    `CLAIM_SQL` filters `state='QUEUED'`, guard trigger rejects any terminal
    mutation (`V1__durable_v1_schema.sql:171-195`).
- Impact: none on fencing (terminals never re-offered, zombie commit 0 rows);
  forensics retains last holder, which is useful.
- Decision: quarantine (documented residual; no behaviour change per #32
  instrumentation rule). Fix only if a future fair-claim ordering (#35) needs
  owner cleared on DEAD — then migration + contract review.

## BUG-03 — CAS GC file-before-row leaves row-without-file until next sweep (CAS)

- Seed: `CasCrashChild gc-file-before-row` (kill between file delete and row
  delete) on containerized PG + filesystem; `ContentStore.collectGarbage`.
- Trace:
  - `src/main/java/com/g1do/anvil/cas/ContentStore.java:342-343`
    "file is deleted first, then the row, so a crash leaves at worst a row
    without a file (reclaimed on the next pass)".
  - `src/main/java/com/g1do/anvil/cas/ContentStore.java:400-407`
    `NoSuchFileException` (already gone) still reclaims the row below;
    `Files.deleteIfExists` + `fsyncDirectory` before
    `DELETE FROM artifacts`.
  - `get` fails closed on missing file (hash-reverify / torn-tail miss), so a
    dangling row never serves bytes; next `collectGarbage` deletes the row.
- Impact: transient dangling row after a crash in the GC window; self-heals on
  next pass, no loss (bytes already unreferenced, `refcount=0` + grace).
- Decision: quarantine (by-design crash window with next-pass reclamation;
  no code change). Fix only if a future restore drill (#34) needs single-pass
  atomicity — then row-before-file with orphan-file sweep (already covered by
  Phase 2) + migration review.

All three are quarantined residuals with traces above; no fencing, clock, RLS,
cap, or CAS behaviour changed in #32.
