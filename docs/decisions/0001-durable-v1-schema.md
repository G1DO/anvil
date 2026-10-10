# 0001 — Durable V1 schema with legal-transition guard

Status: accepted.

## Context

Submit, claim, and relay need one durable place for the single-tenant slice where a crash or a zombie worker cannot leave the system in an illegal state. Application-only checks can be bypassed by a second writer, a manual update, or a resumed worker holding a stale view.

## Decision

Keep a single `jobs` store for all states and enforce legality in Postgres (`src/main/resources/db/migration/V1__durable_v1_schema.sql`):

- Legal transitions only (`QUEUED -> RUNNING`, `RUNNING -> QUEUED/SUCCEEDED/FAILED/DEAD`, `QUEUED -> FAILED/DEAD`); same-state heartbeat updates allowed.
- Terminal rows (`SUCCEEDED/FAILED/DEAD`) fully immutable: any `UPDATE` on a terminal row fails, so zombies cannot mutate completed work.
- Tenant isolation via composite `FOREIGN KEY(tenant_id, xxx_id)` edges, not RLS.
- Dedicated partial indexes for claim (`jobs_claim_idx`), running lookup, lease expiry, and relay poll (`outbox_poll_idx`); `active_jobs` / `claimable_jobs` / `unclaimed_outbox` views keep claim paths off terminals.

## Consequences

- Illegal transitions fail at the database even if application fencing is bypassed; proven by `DurableV1SchemaTest`.
- No RLS or policies to operate; cross-tenant references are rejected by foreign keys.
- Future migrations must preserve the guard; changing legal transitions requires a new migration plus a superseding ADR.
