# 0014 — Additive-only contract and schema changes

Status: accepted. This writes down a constraint the project has followed since its first slice; it wasn't recorded here until 2026-10-27.

## Context

`contracts/openapi.yaml` and the Flyway migrations in `src/main/resources/db/migration/` are the interfaces other code depends on: clients depend on the API, the running service on the schema. A breaking change to either is hard to reverse once anything depends on it. The project's planning notes set the constraint "additive-only migrations" and "additive-only contracts; version bump plus CI contract check on break".

The code differs from those notes on one point. Nothing enforces a version bump; an intentional API break is allowed by adding a line to `contracts/oasdiff-ignore.txt`. The code is what's recorded here.

Alternatives: not recorded at the time.

## Decision

- **Contract.** API changes are additive. The gate runs `oasdiff breaking --fail-on ERR` against `main` (`task test:contract:api`). An intentional break needs a line in `contracts/oasdiff-ignore.txt`; the version isn't bumped automatically.
- **Schema.** Applied migrations are never edited or removed, so Flyway checksums stay valid. New migrations add, they don't destroy. The gate fails on an edited or removed migration, and on a new migration with `DROP`, `RENAME`, `TRUNCATE`, `DELETE FROM`, `SET NOT NULL` or a column type change (`task test:contract:schema`).
- **Breaking changes** go expand → migrate → contract. Only the contract step may be destructive, and it's listed in `contracts/schema-breaks.txt` with its reason.

## Consequences

- Old columns, endpoints and fields live until their contract step, so the schema and spec carry some dead weight.
- Every intentional break is visible in review: a line in one of the two ignore files.
- Once production data exists, the workflow also requires migrations tested on production-like data, and destructive steps only after a restore point. That isn't in place yet; restore is #34.
