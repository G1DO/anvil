# 0007 — Fail-closed cross-tenant reads (direct-ID, search, CAS, presigned)

Status: accepted. Builds on 0005 (DB-enforced tenant isolation) and 0006 (capability-gated commits); does not change submit/claim/heartbeat/reconciler/CAS-durability/outbox semantics.

## Context

Only the submit path existed; no search existed and `ContentStore` isolated paths plus rejected traversal but had no presigned URLs and no RLS on reads. Issue #22 requires every data-read surface (IDs, title-prefix search, content addresses, presigned URLs) to fail closed so a 28-case matrix passes with 0 leaks and no existence oracle.

## Decision

Harden reads with tenant-always plus RLS plus re-check (`src/main/java/com/g1do/anvil/read/ReadService.java`, `src/main/java/com/g1do/anvil/read/DocumentSearchController.java`, `src/main/java/com/g1do/anvil/cas/ContentStore.java`, `src/main/java/com/g1do/anvil/cas/CasPresignedService.java`, `src/main/java/com/g1do/anvil/jobs/JobService.java`):

- Direct-ID: `WHERE id = ? AND tenant_id = ?` (or `tenant_id + sha256` for artifacts) under `TenantGuard.setTenant` plus RLS `FORCE`; cross-tenant returns the same empty as missing (no content, no oracle). Forged claim/commit reuse the tenant-scoped `WHERE` plus the capability gate (`403 NEEDS_APPROVAL`, `cap_rejected++`, 0 rows).
- Minimal search only: `GET /api/documents?query=&limit=&cursor` (aliases `/api/v1/documents`, `/documents`), tenant solely from `X-Tenant-Id` via `TenantAuthFilter`/`TenantContext` (any `tenant`/`tenant_id` param ignored), storage `WHERE tenant_id = :t AND title ILIKE :prefix% ORDER BY created_at, id` (LIKE metacharacters escaped), RLS plus SQL pre-filter plus per-row Java re-check (`ReadService.recheck`). Keyset cursor is a tenant-scoped document UUID: foreign cursors resolve to zero rows (replay returns 0 rows). Limits default `20`, max `100`. No GIN/FTS, no jobs/artifacts full-text (RLS list-filter checks only), no public shares.
- CAS gateway: `getForCaller(caller, resource, sha)` requires `caller == resource` plus an `artifacts` row for `(caller, sha)` under RLS, else caller-scoped `404` (no oracle); `requireTenant` additionally rejects `%` so `%2e%2e`/double-encoding never reaches the filesystem. Same bytes stay two files/two rows; per-tenant GC never deletes the other. The raw hash-verified `get` remains for crash recovery.
- Presigned TTL `60s` (DB clock only): `base64url(payloadJson) + "." + HMAC-SHA256(secret, base64Part)` binding `(tenant, sha, job, cap, epoch, expiry)`. Issue needs artifact/job rows plus a valid current grant; verify re-checks signature (constant-time), expiry, rows, and the presented grant under `FOR UPDATE` via `JobService.checkPresentedGrant`/`fetchValidCurrentGrant` (shared `cap_rejected`). Swapped/tampered/expired/revoked all deny `403` without content. Secret is `anvil.cas.presign-secret` or ephemeral random (restart invalidates, fail closed).

## Consequences

- `ReadIsolationTest` proves the locked 28-case matrix (D01-D08, S01-S08, C01-C06, P01-P06) on containerized Postgres with 0 leaks; D07/D08/P03 exercise the issue-20/21 paths but own no new logic there. Prior suites stay green.
- Residual: presigned URLs are bearer tokens until expiry (60s) or revoke re-check on use; no offline verifier, no public shares, no GIN/FTS, no process-containment change (issue 4).
- Rejected: cross-tenant dedup v1, tenant from query/body, global reads without `WHERE tenant_id`, worker-clock expiry, persistent presigned secrets by default.
