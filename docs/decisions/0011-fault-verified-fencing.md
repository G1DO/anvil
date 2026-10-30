# 0011 — Fault-verified fencing with deterministic sim and reduced real-PG proof

Status: accepted.

## Context

Issue #32 (REQ-4 fault part) needs proof that seeded fencing breaks are caught
fast and that zombie/restart/dupe cause a single effect, without full nightly
runs, exhaustive matrices, dashboards, or live-live migration. O1/O2 suites must
stay green; instrumentation must not change fencing, clock, RLS, cap, or CAS
behaviour. Existing proofs (`JobServiceTest` gate-scale + real-suspend,
`OutboxRelayTest` ghost + real-kill + concurrency) already cover the happy path;
missing are a fencing-only model, a fast deterministic sim for seeded breaks,
and a reduced containerized-PG subset tying suspend/restart/dupe together with
logs in-repo.

## Decision

Fencing-only model plus deterministic sim plus reduced real-PG subset, all
test-only (no `src/main` change):

- Model (`docs/faults/fencing.tla`, `FenceModelTest`): claim, heartbeat,
  commit, zombie-resume, relay re-publish dedupe; asserts
  single-holder-per-live-token and no double effect over 1800 exhaustive states
  (maxDepth 8). TLC was not run when this was accepted; #103 runs it in the gate
  (`task check:tla`), and its output is recorded in the PR, not committed. Java
  checker mirrors the spec action-for-action.
- Sim (`FenceSimTest`): seeded `BROKEN_FENCE` (ignores token), `BROKEN_LEASE`
  (no lease, non-atomic double claim), `BROKEN_RELAY` (mark-before-publish
  loss), each detected in under 500 runs (0/1/0) with seed + trace in
  `src/test/resources/fault-sim/` and `docs/faults/traces/`. Correct fencing
  never violates over 200 runs.
- Real PG (`FaultProofTest` on Testcontainers `postgres:18`): suspend-past-lease
  via DB clock + reconciler + stale-owner 0 rows with exactly-once ledger;
  real `kill -9` of `RelayCrashChild` between publish and mark then restart
  re-publishes same `event_id` with single apply; dupe delivery applies once.
  Wall-clock 12s SIGSTOP past the real 10s TTL remains proven by
  `JobServiceTest#zombieFenceRealSuspend` (not duplicated here for speed).
- Bugs (`docs/faults/bug-log.md`): 3 real claim/relay/CAS observations with
  seed, trace, and quarantine (unscoped crash-child poll, DEAD retains owner,
  GC file-before-row dangling row), disjoint from seeded breaks.
- Arch (`docs/architecture/c3-fault-fencing.mmd`): new fault view linking
  model/sim/PG proofs to `JobService`/`OutboxRelay`/`IdempotentConsumer`.

## Consequences

- Seeded breaks caught in 0-1 runs (<500); zombie/restart/dupe single effect on
  containerized PG with real kill fidelity; full suite green via `task verify`.
- No contract change (`contracts/openapi.yaml` untouched), no migration, no prod
  target; `Real-users/Loss-cost` stays deferred to INV-1/PITR drill (#34).
- Rejected: 100-nightly + 5-weekly full runs, exhaustive fault matrix,
  dashboards/runbooks, live-live migration, O3 replay API changes, O5 eval.
  #78 later added the nightly and weekly runs (`.github/workflows/faults.yml`)
  and a nine-case real-PG matrix (`FaultMatrixTest`): the six failure-model
  points plus GIN, autovacuum and pool exhaustion.
