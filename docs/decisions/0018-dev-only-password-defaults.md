# 0018 — Dev-only password defaults

Status: proposed 2026-10-29 (#101).

## Context

Three places in the repository fall back to a literal password when no value is configured:

- `src/main/resources/db/migration/V2__tenant_rls.sql`, lines 29 and 32. When the migration creates the roles `anvil_migrator` and `anvil_app`, it sets each role's password to a literal value.
- `docker-compose.yml`, lines 9 and 27. The database and the app read `POSTGRES_PASSWORD`, with a literal fallback.
- `Taskfile.yml`, line 165 (`task diagrams`). It reads `POSTGRES_PASSWORD`, with the same literal fallback.

Three facts make "dev-only" a claim the code doesn't back:

- **Flyway runs V2 at every startup, in every environment.** It creates a role only if the role doesn't exist, and when it does, it sets the literal password. No later migration changes that password. So any database where V2 creates the roles gets these passwords, production included.
- **Compose connects the app as `anvil`, not as `anvil_app`.** The official Postgres image creates `POSTGRES_USER` as a superuser. A superuser bypasses row-level security, so the app's connection in Compose isn't subject to the tenant policies.
- **The tests log in as `anvil_app` with the same literal password.** Several test classes do this, so a change to V2 also changes the tests.

Alternatives considered:

- **Edit V2 to read the password from the environment.** Flyway checks each applied migration's checksum, so every database that already ran V2 would fail validation. Any fix has to be a new migration.
- **Remove the Compose and Taskfile fallbacks now.** Then `docker compose up` and `task diagrams` fail until a value is set. That changes the local workflow and the README, which is more than this record covers.
- **Keep the fallbacks, record them as dev-only, and leave the production path open.** This is the decision below.

## Decision

- The three literal fallbacks are dev-only. They are for a developer's own machine, and no production deployment may depend on them.
- This record changes no code, no Compose file, no Taskfile and no migration. V2 stays as it is. Any fix is a new migration.
- Production path: open.
  - Owner: G1DO (repository owner).
  - Revisit by: 2026-12-31, or before the first deployment outside a developer's machine, whichever comes first.
  - Open questions: how production supplies the database password, and which role the app connects as.

## Consequences

- "Dev-only" is a rule that nothing in the code enforces. A deployment can start with the fallbacks, and V2 sets the role passwords in any database where it creates them. Until the production path is decided, a production database built by this code would have passwords that are known from the repository.
- The Compose app runs as a superuser, so row-level security doesn't apply to it. Choosing the app's role belongs to the production-path decision.
- `docs/threat-model.md` (the secrets row) and `SECURITY.md` both say the repository holds no secret values. The three fallbacks are literal values, so both statements are wrong until the production change updates them. The threat-model update ships with that change, not with this record.
