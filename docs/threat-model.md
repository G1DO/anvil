# Threat model

## What are we building?

A multi-tenant job service. Tenants submit documents, jobs run and commit results, and every step is recorded in a per-job hash chain that can be replayed. One tenant must never read or change another tenant's data, and must not be able to tell whether another tenant's data exists.

**Assets**
- Tenant rows in `documents`, `document_versions`, `jobs`, `attempts`, `artifacts`, `outbox`, `processed_events`, `audit_events`, `cap_grants` and `job_checkpoints`.
- Per-tenant content bytes at `tenants/{tenant}/{sha}`.
- Capability grants, which authorize every commit.
- Each job's audit chain and checkpoints.

**Trust boundaries** (marked in `docs/architecture/containers.mmd`)
1. **HTTP entry.** Tenant clients send untrusted requests and documents.
2. **Database.** Every query runs as the runtime role under row-level security.
3. **Exec jail.** Tenant-supplied commands run here. No production code calls it yet.
4. **Network egress** from the jail.
5. **Content store.** Files on disk, reached through presigned URLs.

## What can go wrong?

- **A forged or missing tenant identity** reads or writes another tenant's rows.
- **Connection-pool reuse or a cached plan** leaks rows across tenants.
- **Cross-tenant IDs, search cursors or content hashes** reveal whether another tenant's data exists (an oracle).
- **A stale or zombie worker** commits after its lease moved on, or a revoked capability still commits.
- **A database restore** rewinds fencing tokens and capability grants, so a worker from the lost timeline holds a token or grant that the restored database issues again.
- **Path traversal or symlinks** in content paths or workspace files escape the tenant's directory.
- **A presigned URL** is swapped to another tenant, tampered with, replayed after expiry, or used after its capability is revoked.
- **Audit chain tampering:** an event is edited, deleted, forked or dropped, and replay reports a false match.
- **Jailed code** reads a sibling workspace, inherits secrets through environment variables, floods output, or reaches the network (metadata endpoints, internal addresses, redirects).
- **Unbounded input:** oversized titles, bodies or keys.
- **A secret is committed,** or a dependency or CI action is compromised.

## What will we do about it?

| Threat | Control | Proven by |
| --- | --- | --- |
| Forged or missing tenant | `TenantAuthFilter` requires `X-Tenant-Id` on submit, read, audit and replay paths, and looks it up in `tenants`. Missing or unknown returns `401`; a failed lookup fails closed. `SubmitService` re-checks the tenant inside its transaction. Tenant query parameters are ignored. | `SubmitIdempotencyTest.missingOrUnknownTenantIsUnauthorizedWithNoRows`, `AuditReadIsolationTest.forgedTenantParamIgnored` |
| Cross-tenant rows | `RLS FORCE` with one `tenant_isolation` policy per tenant table (`tenant_id = current_setting('app.tenant', true)`). Runtime role `anvil_app` is `NOSUPERUSER NOBYPASSRLS`. Views are `security_invoker`. `TenantGuard` sets `SET LOCAL app.tenant` with a read-back probe, so a missing context reads 0 rows. Composite foreign keys keep references inside one tenant. | `RlsLeakTest`, `DurableV1SchemaTest.crossTenantReferencesRejected` |
| Existence oracle | Direct-ID reads, prefix search, audit reads and content reads filter by tenant, run under RLS, and re-check every row. A cross-tenant ID looks exactly like a missing one (empty result or a generic `404`). A foreign cursor yields 0 rows. | `ReadIsolationTest` (28 cases), `AuditReadIsolationTest`, `ReplayTest.crossTenantDenyFailClosed` |
| Stale worker, revoked capability | Every mutation is fenced on `(id, tenant_id, owner, fencing_token, state)` with the database clock. A commit also needs a current, unrevoked, unexpired capability grant, checked in the same transaction. Denial affects 0 rows, returns `403 NEEDS_APPROVAL`, and counts `cap_rejected`. Terminal rows are immutable. | `JobServiceTest`, `CapRevokeMidRunTest`, `FaultProofTest` |
| Restore rewind | `db_timeline` stores the confirmed `(system_identifier, timeline_id)`, and a restore rewinds it while Postgres moves to a new timeline. Claim, heartbeat and commit refuse (`503 TIMELINE_UNCONFIRMED`, 0 rows) while it doesn't match the server's or can't be read. `confirmTimeline` reopens only once no lease is live on the database clock. It raises every open job's token by `max_attempts` and deletes every grant, so each commit waits for a re-issued grant with a new id. Confirm is database-wide: any tenant's claim can run it, and it changes every tenant's tokens and grants. `anvil_app` can write `db_timeline`, so the gate detects restores; it isn't a control against the runtime role. | `PitrTimelineCheckTest`, `PitrRestoreDrillTest` |
| Path escape | Tenant names that contain `/`, `\`, `..`, `%`-encoding or reserved names are rejected. Every content read re-hashes the bytes and never serves a torn tail. `ContentStore.getForCaller` needs caller = owner plus an `artifacts` row, otherwise it returns a caller-scoped `404`. | `ContentStoreTest.rejectsTraversalAndBadShaWithoutEscapingRoot`, `ContentStoreTest.tornTailDetectedAndRemovedRatherThanServed` |
| Presigned URL abuse | HMAC bound to tenant, sha, job, capability, epoch and expiry; 60-second lifetime on the database clock. A swapped tenant, tampering, expiry or a revoked capability returns `403`. | `ReadIsolationTest` |
| Audit tampering | Appended in the same transaction as the effect. `UNIQUE(job_id, seq)` and `UNIQUE(job_id, prev_hash)` block forks. Immutability triggers block edits and deletes, and the runtime role can only `SELECT` and `INSERT`. Replay recomputes the chain and reports a mismatch, never a silent success. Replay itself writes nothing. | `AuditChainTest`, `ReplayTest` |
| Jail escape | Per-tenant working directory. `resolveInWorkspace` denies absolute paths, `..`, `%`, null bytes and symlinks. The environment is cleared to `PATH` and `ANVIL_TENANT` plus an allowlist, and loader or debug keys are denied. Shell interpreters and workspace executables are denied. Output is capped at 1 MiB per stream, with a 30-second timeout. `readWorkspaceFile` denies cross-tenant callers without an oracle. | `ExecJailTest` |
| Jail egress | `JailEgress.checkAllowed` denies every URL in v1. Loopback, metadata, link-local, private and non-canonical addresses are always denied. The HTTP client never follows redirects. Denials give only a generic reason. | `ExecJailTest.egressDenied`, `ExecJailTest.redirectNotFollowed`, `ExecJailTest.egressNonCanonicalDenied` |
| Unbounded input | `SubmitService` limits title, `mime_type`, `Idempotency-Key` and body. Canonical CBOR covers only `{title, mime_type, body}`. | `SubmitIdempotencyTest.invalidPayloadLeavesZeroRows` |
| Secrets and supply chain | No secret values in the repo; Compose reads `POSTGRES_PASSWORD` from the environment. The gate scans full git history with gitleaks, fails on newly added vulnerable dependencies, and pins CI actions to commit SHAs. GitHub push protection blocks secret pushes. Logs are JSON with correlation IDs and no secrets. | The `gate` check |

## Did we do enough?

Not fully. These risks are accepted for now:

- **The jail is minimal** (#73). It has no mount, user or network namespace, so native code can reach `../sibling` or `/tmp`. Interpreter `-c`, workspace binaries, `/proc` and symlink races bypass the argv allowlist, which is why `run` is for trusted callers only. Egress has no DNS-rebind re-check and no network-layer drop. Non-canonical IPs are denied without being decoded, and there's no full SSRF matrix.
- **The leak matrix leaves out sweeps.** `RlsLeakTest` interleaves two tenants on one pool across all 10 tenant-scoped tables and the submit, claim, heartbeat, commit, capability, relay, read, search, audit read, replay and CAS paths (#74). Reconcile and garbage collection sweep every tenant by design and aren't in the matrix.
- **Replay cost grows with chain length** (#76). Replay re-verifies the whole chain, so a long chain makes it slow (chain DoS).
- **Some restores aren't detected** (#34, record 0016). A storage snapshot rolled back in place, or a base backup started without `recovery.signal`, keeps the timeline, so the gate stays open. Effects outside the database from after the restore target can repeat, and the old primary must be gone before the restored one serves.
- **A time-of-check gap after each grant check.** A revoke that lands after a commit's grant check takes effect from the next commit.
- **Some steps aren't logged:** heartbeat, reclaim, empty polls and `outbox.sent_at`. Replay's output is the same without them.
