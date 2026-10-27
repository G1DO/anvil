# Architecture index — ANVIL

Canonical view list. Mermaid sources live in this directory and are validated by `task diagrams`. SQL in `src/main/resources/db/migration/` is canonical for schema; `data-model.md` states intent only.

## Context and containers

- `c1.mmd` — system context: tenant client, operator, ANVIL, PostgreSQL 18, CAS store.
- `c2.mmd` — containers: API service, workers, Postgres, CAS; health probes and composition.

## Slices and detail (one file per maintained view)

- `c3-audit-chain.mmd` — O3 deterministic replay slice: hash chain, checkpoints, replay endpoint.
- `c3-api.mmd` — API detail: auth filter, submit path, reads, audit reads, URLs, health.
- `c3-workers.mmd` — worker detail: fenced claim, heartbeat, capability-gated commit, reconciler.
- `c3-exec-jail.mmd` — execution jail detail: workspace, env, output caps, default-deny egress.
- `c3-fault-fencing.mmd` — fault-fencing model view: claim, heartbeat, commit, reconcile transitions + PITR timeline-check sim node.
- `job-lifecycle.mmd` — job state lifecycle: QUEUED, RUNNING, SUCCEEDED, FAILED, DEAD.
- `sequence-submit-claim-commit.mmd` — submit to claim to commit interaction.
- `sequence-reconcile-outbox.mmd` — reconciler and outbox relay interaction.

## Data and deployment

- `data-model.md` — tables, RLS, indexes, audit chain shape (SQL canonical).
- `er-diagram.mmd` — entity relationships across tenants, documents, jobs, CAS, outbox, audit.
- `deployment.mmd` — compose host: app image, PostgreSQL 18, volume, probes.

## Decisions

- `../decisions/` — accepted architecturally significant records (V1 schema through pitr-timeline-check 0013).
- `../security/threat-model.md` — trust boundaries for tenant isolation, capabilities, reads, replay, jail.

Verify: `task diagrams`.
