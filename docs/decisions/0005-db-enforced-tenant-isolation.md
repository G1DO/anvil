# 0005 — DB-enforced tenant isolation with RLS FORCE

Status: accepted. Supersedes the "no RLS" portion of 0001 for runtime isolation (FK edges retained).

## Context

V1 carried `tenant_id` on every row with composite FK edges but no policies, so a pooled-connection or plan-cache reuse bug could read or effect another tenant. All later gating depends on the database itself refusing cross-tenant access.

## Decision

Enforce tenant context in Postgres (`src/main/resources/db/migration/V2__tenant_rls.sql`, `src/main/java/com/g1do/anvil/tenant/TenantGuard.java`):

- Separate roles: `anvil_migrator` owns tables/views/function; runtime `anvil_app` is `NOSUPERUSER NOBYPASSRLS` with least-privilege DML grants, no `CREATE` on `public`.
- `RLS FORCE` on all tenant-scoped tables (`documents, document_versions, jobs, attempts, artifacts, outbox, processed_events`; `tenants` excluded as the auth lookup target) with one policy per table `tenant_id = current_setting('app.tenant', true)` (`USING` + `WITH CHECK`, `TO PUBLIC`); missing context yields `NULL = NULL-is-not-true`, i.e. zero rows (fail closed). Views set `security_invoker=true`; `check_job_state_transition` stays `SECURITY INVOKER`; no `SECURITY DEFINER` in `public`.
- Every transaction touching tenant rows calls `TenantGuard.setTenant` first: `SELECT set_config('app.tenant', ?, true)` (`SET LOCAL`, resets on commit/rollback) plus a `current_setting('app.tenant', true)` read-back probe that throws on mismatch.
- Every hot-path statement keeps an explicit `WHERE tenant_id = ?` in addition to RLS. Cross-tenant passes iterate `tenants` and run one tenant transaction each (`reconcileAll`, unscoped `relayOnce`, CAS `collectGarbage`); single-statement callers (heartbeat/commit, CAS upserts) are wrapped in a transaction for the same reason.

## Consequences

- Connection/pool/plan-cache reuse cannot leak: `SET LOCAL` never survives commit, RLS blocks cross-tenant reads/writes even when the predicate tries, and the probe fails the transaction rather than serving wrong rows; proven by `RlsLeakTest` (interleave + reuse + DEFINER/BYPASSRLS negative checks, 0 leaks).
- Bootstrap superuser wiring (pre-O2 tests) still bypasses RLS by design and stays green; restricted-pool behavior is proven separately.
- Rejected: session-level `SET` (survives checkout), `BYPASSRLS`/admin relay path, global updates without `tenant_id`.
