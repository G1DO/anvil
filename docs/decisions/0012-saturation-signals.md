# 0012 — Queue, claim, pool saturation signals with burn alerts

Status: accepted.

## Context

Issue #33 (REQ-4 burn part, O4) needs three staging-queryable saturation
signals plus 1-2 burn alerts that fire on induced burn and stay silent on
green control, without changing fencing, clock, RLS, cap, or CAS behaviour
and without tenant bytes in metrics. No Micrometer/Actuator dependency exists;
`task verify` must stay green including `oasdiff` and SAST.

## Decision

Hand-rolled signals, no new runtime dependencies:

- `observe/SaturationSignals.java`: `anvil_queue_age_seconds` gauge from
  `clock_timestamp() - MIN(created_at) WHERE state='QUEUED'`, computed per
  tenant in its own RLS transaction via `TenantGuard.setTenant` then reduced
  to a global max; `anvil_claim_latency_seconds` p99 from `JobService.claim`
  wall time (try-finally timing only, SQL untouched); `anvil_pool_wait_seconds`
  p99 from `DataSource.getConnection` acquisition samples. Bounded 1024-sample
  rings, static labels only (`state`, `op`, `pool`).
- `observe/BurnAlerts.java`: `QueueBurn` (`queue_age > 60s for 5m`) and
  `ClaimBurn` (`claim p99 > 0.5s or pool-wait p99 > 0.5s for 5m`); pure
  evaluation plus tenant-free payload.
- `observe/ObserveController.java`: `GET /observe/saturation` (JSON) and
  `GET /metrics` (Prometheus text), both unauthenticated like `/healthz` and
  tenant-free; `TenantAuthFilter` already skips non-`/api/` paths so no auth
  change.
- `prometheus/alerts-33.yml`: the same two alerts for staging Prometheus.
- `contracts/openapi.yaml`: additive-only `/observe/saturation` + `/metrics`
  (no break, `oasdiff` green).

## Consequences

- Containerized proof in `SaturationSignalTest` plus `task verify` green;
  staging burn evidence in `docs/faults/burn-33/` (payload, snapshots,
  screenshot note).
- No migration, no contract break, no fencing/RLS/cap/CAS change; `JobService`
  2-arg constructor retained for `RlsLeakTest` compat.
- Rejected: Micrometer/Actuator (new dep + audit surface), full dashboards,
  autoscale/remediation, exhaustive saturation matrix (per issue non-goals).
