# Threat model — ANVIL (Auth/sensitive slice)

Scope: multi-tenant submit/read/CAS/audit/exec paths where one tenant must never read or effect another tenant's data. Single-tenant O1 out of scope for cross-tenant; O2/O3 in scope.

## Assets
- Tenant rows: `documents, document_versions, jobs, attempts, artifacts, outbox, processed_events, audit_events, cap_grants` (all `tenant_id`-scoped, `src/main/resources/db/migration/V1__durable_v1_schema.sql` through `V4__audit_events.sql`).
- Bytes: per-tenant CAS `tenants/{tenant}/{sha}`, hash-verified on read (`src/main/java/com/g1do/anvil/cas/ContentStore.java`).
- Capabilities: `cap_grants(tenant_id, job_id, epoch)` gating every commit (`src/main/resources/db/migration/V3__cap_grants.sql`).
- Audit chain: per-job `audit_events(tenant_id, job_id, seq, prev_hash, event_hash)` fork-guarded append-only (`src/main/resources/db/migration/V4__audit_events.sql`, `src/main/java/com/g1do/anvil/audit/AuditChain.java`).

## Boundaries and controls
- Entry: `TenantAuthFilter` requires `X-Tenant-Id` on submit/read/audit paths; unknown tenant `401`. `SubmitService` re-validates tenant inside the submit transaction.
- Database: `RLS FORCE` + one `tenant_isolation` policy per table (`tenant_id = current_setting('app.tenant', true)`); runtime `anvil_app` is `NOSUPERUSER NOBYPASSRLS`; migrator owns tables/views; views `security_invoker=true`. Every tenant transaction sets `SET LOCAL app.tenant` via `TenantGuard` with read-back probe; missing context reads 0 rows (fail closed). Proven by `RlsLeakTest` (pool reuse + plan-cache reuse, 0 leaks).
- References: composite `FOREIGN KEY(tenant_id, xxx_id)` + `UNIQUE(tenant_id, id)` targets; cross-tenant IDs return empty same-as-missing (no oracle) in `ReadService`, `AuditReadService`, `ContentStore.getForCaller`, `CasPresignedService` (TTL 60s, HMAC-bound, DB clock).
- Capabilities: commit requires valid non-revoked non-expired current-epoch grant; stale owner affects 0 rows, `403 NEEDS_APPROVAL`, `cap_rejected++`. Proven by `CapRevokeMidRunTest`.
- Reads: `GET /api/documents?query=&limit=&cursor` prefix search `WHERE tenant_id=:t`, RLS + per-row re-check, cursor tenant-scoped (foreign 0 rows); `GET /audit/events?job_id=&limit=&cursor` caller-chain only `ORDER BY seq`, same fail-closed rules. Proven by `ReadIsolationTest` (28-case, 0 leaks) and `AuditReadIsolationTest`.
- Execution: `ExecJail` per-tenant workspace, `resolveInWorkspace` denies absolute/`..`/`%`/null/symlink; cleared env (`PATH`, `ANVIL_TENANT` only + allowlist); output capped 1 MiB/stream; egress default-deny (`JailEgress.checkAllowed` denies all v1, `followRedirects(NEVER)`). Proven by `ExecJailTest`.
- Chain: same-transaction append as effect (`submit/claim/commit/cap.issue/cap.revoke/cas.put`); `UNIQUE(job_id, seq)` + `UNIQUE(job_id, prev_hash)` fork guard; `audit_events_immutable` trigger rejects `UPDATE`/`DELETE`; app `SELECT+INSERT` only. Proven by `AuditChainTest`.

## Residual risk (accepted, not closed)
- Post-grant TOCTOU by definition; heartbeat/reclaim/empty polls/`outbox.sent_at` not logged (replay-identical by design).
- No mount/user/network namespace in v1; interpreter `-c`, workspace binaries, `/proc`, symlink TOCTOU bypass argv allowlist — `run` is trusted-caller-only.
- No DNS-rebind re-check, non-canonical IP encodings deny without decoding, no nftables DROP, no full SSRF matrix.
- See `docs/decisions/0005-db-enforced-tenant-isolation.md`, `0006-capability-gated-commits.md`, `0007-fail-closed-reads.md`, `0008-minimal-exec-jail.md`, `0009-immutable-audit-chain.md`.

## Review rule
- Changes touching auth, sensitive data, destructive migrations, or delivery/security controls require independent review when available; solo uses gate-green + self-review of diff (checks never weakened).
