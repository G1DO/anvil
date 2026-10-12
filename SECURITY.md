# Security

## Reporting

Do not put undisclosed vulnerability details into a public issue. Use GitHub private vulnerability reporting (Security Advisories) for this repository so the report stays private until a fix is coordinated. Include affected routes or jobs behavior, reproduction, and impact.

There are no supported releases yet (`0.0.1-SNAPSHOT`); the current scope is the `main` branch.

## Trust boundaries

- HTTP entry: `TenantAuthFilter` requires `X-Tenant-Id` on the submit slice and looks it up in `tenants`; missing or unknown tenants get `401`, and a lookup failure fails closed. `SubmitService` re-validates the tenant inside the submit path.
- Tenant isolation is composite `FOREIGN KEY(tenant_id, xxx_id)` edges plus `UNIQUE(tenant_id, id)` targets plus DB-enforced RLS: `RLS FORCE` on the seven tenant-scoped tables (`documents, document_versions, jobs, attempts, artifacts, outbox, processed_events`; `tenants` excluded as the auth lookup target) with one `tenant_isolation` policy per table (`tenant_id = current_setting('app.tenant', true)`), runtime role `anvil_app` is `NOSUPERUSER NOBYPASSRLS`, views are `security_invoker=true`. Every transaction touching tenant rows sets `SET LOCAL app.tenant` via `TenantGuard.setTenant` with a read-back probe; missing context reads zero rows (fail closed). See `src/main/resources/db/migration/V1__durable_v1_schema.sql`, `src/main/resources/db/migration/V2__tenant_rls.sql`, `src/main/java/com/g1do/anvil/tenant/TenantGuard.java`, `docs/architecture.md`, and `docs/decisions/0005-db-enforced-tenant-isolation.md`.
- Job ownership is fenced on `(id, tenant_id, owner, fencing_token, state)` with the database clock as the only lease clock; stale workers affect zero rows and terminal rows are immutable at the database.
- Content paths are `tenants/{tenant}/{sha}` with tenant names rejected when they escape their directory (`/`, `\`, `..`, reserved names); every read re-verifies `sha256(bytes)` and never serves a torn tail. See `src/main/java/com/g1do/anvil/cas/ContentStore.java`.
- Input validation limits live in `SubmitService` (title, `mime_type`, `Idempotency-Key`, required `body`); CBOR canonicalization covers `{title, mime_type, body}` only.

## Secrets and data

- No secrets are stored in the repository. Postgres comes from Testcontainers in tests and Boot Dev Services on local run.
- No extra threat model or SBOM is maintained for this slice; add either only when its risk (public contract, durable data, release chain) actually requires it.
