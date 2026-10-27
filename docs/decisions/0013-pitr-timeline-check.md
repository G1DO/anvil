# 0013 — PITR timeline-check (INV-1, fail-closed, no SLO)

Status: accepted 2026-10-27 by G1DO (INV-1 decision only; no RTO/RPO SLO claimed).

## Context

Issue #34 bullet-1 (REQ-4 restore part, bounded max 3d): rewind repro attempted
against fencing + cap checks (`src/main/java/com/g1do/anvil/jobs/JobService.java:97-184`
`CLAIM_SQL/HEARTBEAT_SQL/COMMIT_SQL_PREFIX + SELECT ... FOR UPDATE + clock_timestamp()`
DB clock only), ending in this recorded decision. Full backup→clean→PITR drill
with measured RTO/RPO, stale-token/RLS/cap post-restore matrix, second-process/
failover, nightly drills are out of scope (later bullets in #34).

Readiness: O4 Active 2026-10-25 by G1DO (target containerized PG clean+WAL to
5min ago) with Verified-Except expiry 2026-10-28; O1/O2/O3 Claimed, O3 Verified
signer !=G1DO pending (Delivery O3 `94f54df` runs `40038821107/40038821133`).
Proceed bullet-1 at risk with no SLO claim; residual O3 gate open. A2 as separate
gate not found (Notion + repo grep 2026-10-27); O4 Active + INV-1 gap accepted
as readiness per owner sign-off 2026-10-27. #32 CLOSED 2026-10-26 (REQ-4 fault
part), #33 merged to `main@13f535f`; branch `34-pitr-epoch` from `origin/main`.

## Decision

Fail-closed timeline gate required; no SLO committed before full drill:

- Refuse traffic if PG timeline rewound until leases expired + caps re-issued
  (`lease_expires_at`/`expires_at` on DB clock). Stale owner/token must affect
  0 rows; missing/revoked/expired cap must deny `403 NEEDS_APPROVAL`
  (`cap_rejected`); re-published identities apply once.
- Repro (`src/test/java/com/g1do/anvil/PitrTimelineCheckTest.java` on
  Testcontainers `postgres:18` per `CONTRIBUTING.md:5`): simulated rewind
  (jobs row to QUEUED/prior token/null lease, rewound attempt + cap removed)
  proves stale heartbeat 0 rows (`false`), stale commit denies (cap gate
  `403`, no row change), fresh claim cannot commit until cap re-issued
  (`403` + `cap_rejected` +1), then re-issue + commit succeeds once with
  exactly-once ledger (1 attempt, `SUCCEEDED` x1). Timeline-ID lookup
  attempted (`pg_control_checkpoint()`), unknown fails closed.
- NO-GO on SLO: no RTO/RPO numbers, no rewound-epoch double effect accepted
  as success; gaps reported below. Full drill (base+WAL to declared target)
  still required before any SLO claim.

## Consequences

- Fencing + cap hold on simulated rewind on containerized PG; O1/O2 green
  (full suite via `task verify`); no contract change (`contracts/openapi.yaml`
  untouched), no migration, no prod target, no secrets in logs. Additive-only:
  1 test + 1 decision + 1 drill log + arch note updates.
- Residual (no SLO): simulated rewind, not real base+WAL PITR — `audit_events`/
  `job_checkpoints` append-only (TRUNCATE-only cleanup) not rewound here; PG
  timeline ID not bumped; timeline-ID observability varies by version/role
  (`pg_control` read typically denied → fail-closed on unknown); cross-timeline
  token reuse still requires full-drill proof; RTO/RPO TBD to full drill.
- Rejected: procedural drain alone (no gate), SLO claim on sim fidelity,
  exhaustive fault matrix / nightly drills in this change (later bullets).
- Drill log: `docs/faults/pitr-timeline-check.log` (test method, target,
  results, gap). Arch: `docs/architecture/data-model.md` residuals +
  `docs/architecture/c3-fault-fencing.mmd` timeline node updated same change
  per `CONTRIBUTING.md:47`.
