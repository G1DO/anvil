# anvil

A multi-tenant job service whose results can be trusted after crashes and audited after the fact.

## Problem and users

Teams that run work on behalf of many tenants hit three problems:

- **Crashes cause lost or doubled work.** A worker crash, a worker that resumes after its lease expired (a "zombie"), or a relay crash between publishing an event and recording it can lose a job or apply it twice.
- **Tenants leak into each other.** Shared connections, cached query plans or a guessable ID can show one tenant another tenant's data, or reveal that it exists.
- **Results can't be checked.** Nobody can prove afterwards what a job did, or detect that its record was altered.

ANVIL is for:

- **Tenant clients** that submit documents and jobs over HTTP (`X-Tenant-Id`, `Idempotency-Key`), read their documents, and read or replay their jobs' audit chains.
- **Operators** who run the service and watch its health and saturation signals.

## Guarantees

Each guarantee links to the tests that prove it. All tests run against real PostgreSQL in containers, restored from a backup before the suite starts.

1. **No lost work, no double effect.** Execution is at-least-once with fenced, idempotent effects; it is never exactly-once.
   - A duplicate submit returns the same IDs and creates one job: [`SubmitIdempotencyTest`](src/test/java/com/g1do/anvil/SubmitIdempotencyTest.java).
   - Two workers never claim the same job. A stale or zombie worker affects 0 rows. Terminal jobs are never re-run: [`JobServiceTest`](src/test/java/com/g1do/anvil/JobServiceTest.java), [`DurableV1SchemaTest`](src/test/java/com/g1do/anvil/DurableV1SchemaTest.java).
   - A relay crash between publish and mark-sent re-publishes the same event, which applies once: [`OutboxRelayTest`](src/test/java/com/g1do/anvil/OutboxRelayTest.java).
   - Content survives a crash during write or garbage collection, and a torn file is never served: [`ContentStoreTest`](src/test/java/com/g1do/anvil/ContentStoreTest.java).
2. **No tenant can read or change another tenant's data, or learn that it exists.**
   - The database enforces isolation even with pooled connections and cached plans: [`RlsLeakTest`](src/test/java/com/g1do/anvil/RlsLeakTest.java).
   - A 28-case matrix over direct IDs, search, content addresses and presigned URLs shows 0 leaks: [`ReadIsolationTest`](src/test/java/com/g1do/anvil/ReadIsolationTest.java). The same holds for audit reads: [`AuditReadIsolationTest`](src/test/java/com/g1do/anvil/AuditReadIsolationTest.java).
   - Revoking a capability mid-run blocks the commit (0 rows, `403 NEEDS_APPROVAL`): [`CapRevokeMidRunTest`](src/test/java/com/g1do/anvil/CapRevokeMidRunTest.java).
   - Jailed execution runs in a kernel sandbox. Untrusted native code in it can't read or write another tenant's workspace or the shared `/tmp`, and has no network route. This holds on the host and inside a container: [`ExecJailTest`](src/test/java/com/g1do/anvil/ExecJailTest.java), [`JailContainerEscapeTest`](src/test/java/com/g1do/anvil/JailContainerEscapeTest.java). Why: [record 0019](docs/decisions/0019-kernel-sandboxed-exec-jail.md).
   - Brokered egress is HTTPS-only with verified certificates, denies an 80-plus-case SSRF matrix, re-checks DNS at connect time, never follows redirects, and takes the same time (within 1 ms) for an existing and a missing target: [`ExecJailTest`](src/test/java/com/g1do/anvil/ExecJailTest.java).
3. **Any job can be replayed bit-for-bit from its audit chain, and tampering is detected.**
   - Every effect is recorded in an append-only, hash-linked chain in the same transaction: [`AuditChainTest`](src/test/java/com/g1do/anvil/AuditChainTest.java).
   - `GET /audit/replay` returns `{input_sha, output_sha, match: true}` for an intact chain. A corrupted, dropped or re-linked event gives a mismatch. Checkpoint replay gives the same result: [`ReplayTest`](src/test/java/com/g1do/anvil/ReplayTest.java).
4. **Fencing holds under real faults.**
   - A deterministic simulator catches each seeded fencing break in under 500 runs: [`FenceSimTest`](src/test/java/com/g1do/anvil/FenceSimTest.java).
   - The fencing model keeps a single holder and no double effect: [`FenceModelTest`](src/test/java/com/g1do/anvil/FenceModelTest.java). It mirrors [`docs/faults/fencing.tla`](docs/faults/fencing.tla).
   - TLC model-checks [`docs/faults/fencing.tla`](docs/faults/fencing.tla) on every gate run, with TLC pinned to 1.7.4 and a SHA-256 check. The job is `tla` in [`gate.yml`](.github/workflows/gate.yml) and runs `task check:tla`. Its runs are on the [gate workflow page](https://github.com/G1DO/anvil/actions/workflows/gate.yml).
   - Process suspend, kill and restart, and duplicate delivery each produce a single effect: [`FaultProofTest`](src/test/java/com/g1do/anvil/FaultProofTest.java).
- Each of the nine fault-matrix cases shows a single effect and no lost work: [`FaultMatrixTest`](src/test/java/com/g1do/anvil/FaultMatrixTest.java).
- A failed scheduled fault run opens an issue with its seed and trace: [`FaultScheduleTest`](src/test/java/com/g1do/anvil/FaultScheduleTest.java), [`FenceSimTest`](src/test/java/com/g1do/anvil/FenceSimTest.java).
   - The real-Postgres fault matrix covers a crash before and after the effect, a lost ack, duplicate and out-of-order delivery, a stuck lease, a GIN pending-list flush, autovacuum and connection-pool exhaustion. Each case shows a single effect and no lost work: [`FaultMatrixTest`](src/test/java/com/g1do/anvil/FaultMatrixTest.java).
   - The [`faults`](.github/workflows/faults.yml) workflow runs the simulator 100 times nightly and the real-Postgres fault suite 5 times weekly, each from a fresh seed. A failed run opens an issue with its seed and trace: [`FaultScheduleTest`](src/test/java/com/g1do/anvil/FaultScheduleTest.java), [`FenceSimTest`](src/test/java/com/g1do/anvil/FenceSimTest.java). Its runs are on the [faults workflow page](https://github.com/G1DO/anvil/actions/workflows/faults.yml).
   - A simulated point-in-time restore that rewinds the epoch lets a stale owner affect 0 rows with no double effect: [`PitrTimelineCheckTest`](src/test/java/com/g1do/anvil/PitrTimelineCheckTest.java). Why: [record 0013](docs/decisions/0013-pitr-timeline-check.md).
   - When the database timeline is unknown or rewound, claims, heartbeats and commits are refused (`503 TIMELINE_UNCONFIRMED`) until leases expire and capabilities are re-issued: [`PitrTimelineCheckTest`](src/test/java/com/g1do/anvil/PitrTimelineCheckTest.java). Why: [record 0016](docs/decisions/0016-restore-timeline-gate.md).
   - A backup → clean → point-in-time restore to a declared target reports its measured recovery time and data loss. Afterwards, tokens and grants from either side of the target affect 0 rows, a re-published event applies once, and isolation and capability checks hold: [`PitrRestoreDrillTest`](src/test/java/com/g1do/anvil/PitrRestoreDrillTest.java). The whole suite runs against a restored database.
5. **One tenant's flood can't starve the others.**
   - A claim never takes a tenant past its quota of running jobs, even with 64 claims at once. Jobs are claimed in the order of a penalty (the tenant's running count), then `next_run_at` on the database clock, then arrival: [`FairClaimTest`](src/test/java/com/g1do/anvil/FairClaimTest.java). Why: [record 0020](docs/decisions/0020-fair-claim-under-quota.md).
   - While one tenant floods 8 workers, a quiet tenant's jobs are each claimed within 16 flood claims of their submit. This holds whether the flood's quota is below the pool or above it: [`FairClaimTest`](src/test/java/com/g1do/anvil/FairClaimTest.java).
6. **Saturation is visible.** Queue age, claim latency and pool wait are exported without tenant data. The burn alerts fire on induced burn and stay silent when healthy: [`SaturationSignalTest`](src/test/java/com/g1do/anvil/SaturationSignalTest.java).

Not yet guaranteed: a target for recovery time or data loss (the drill measures them; it doesn't promise them), or detecting a restore that keeps the database timeline, such as a storage snapshot rolled back in place. See [record 0016](docs/decisions/0016-restore-timeline-gate.md).

## Success

Success is measured by the guarantees above. Each measure names the test that shows it.

- A duplicate submit returns the same IDs and creates one job: [`SubmitIdempotencyTest`](src/test/java/com/g1do/anvil/SubmitIdempotencyTest.java).
- Two workers never claim the same job, and a stale or zombie worker affects 0 rows: [`JobServiceTest`](src/test/java/com/g1do/anvil/JobServiceTest.java).
- A relay crash between publish and mark re-publishes an event that applies once: [`OutboxRelayTest`](src/test/java/com/g1do/anvil/OutboxRelayTest.java).
- A crash during a content write or garbage collection never serves a torn file: [`ContentStoreTest`](src/test/java/com/g1do/anvil/ContentStoreTest.java).
- The database enforces tenant isolation with pooled connections and cached plans, with 0 leaks: [`RlsLeakTest`](src/test/java/com/g1do/anvil/RlsLeakTest.java).
- The 28-case read matrix and the audit reads show 0 cross-tenant leaks: [`ReadIsolationTest`](src/test/java/com/g1do/anvil/ReadIsolationTest.java), [`AuditReadIsolationTest`](src/test/java/com/g1do/anvil/AuditReadIsolationTest.java).
- A capability revoked mid-run blocks the commit (0 rows, `403 NEEDS_APPROVAL`): [`CapRevokeMidRunTest`](src/test/java/com/g1do/anvil/CapRevokeMidRunTest.java).
- Untrusted native code in the jail can't reach another tenant's workspace, the shared `/tmp` or the network, on the host and in a container, and every SSRF matrix case is denied: [`ExecJailTest`](src/test/java/com/g1do/anvil/ExecJailTest.java), [`JailContainerEscapeTest`](src/test/java/com/g1do/anvil/JailContainerEscapeTest.java).
- A job replays bit-for-bit from its audit chain, and a corrupted, dropped or re-linked event is detected: [`AuditChainTest`](src/test/java/com/g1do/anvil/AuditChainTest.java), [`ReplayTest`](src/test/java/com/g1do/anvil/ReplayTest.java).
- A seeded fencing break is caught in under 500 simulator runs: [`FenceSimTest`](src/test/java/com/g1do/anvil/FenceSimTest.java).
- The fencing model keeps a single holder and no double effect: [`FenceModelTest`](src/test/java/com/g1do/anvil/FenceModelTest.java).
- Process suspend, kill and restart, and duplicate delivery each produce a single effect: [`FaultProofTest`](src/test/java/com/g1do/anvil/FaultProofTest.java).
- A tenant never exceeds its quota of running jobs, and a quiet tenant's jobs keep moving while another tenant floods the workers: [`FairClaimTest`](src/test/java/com/g1do/anvil/FairClaimTest.java).
- An unconfirmed or rewound database timeline refuses claims, heartbeats and commits: [`PitrTimelineCheckTest`](src/test/java/com/g1do/anvil/PitrTimelineCheckTest.java).
- A backup, clean and point-in-time restore reports its measured recovery time and data loss, with no target claimed: [`PitrRestoreDrillTest`](src/test/java/com/g1do/anvil/PitrRestoreDrillTest.java).

## How to run

You need Java 25, Docker, [Task](https://taskfile.dev) and [bubblewrap](https://github.com/containers/bubblewrap) (`bwrap`, with unprivileged user namespaces allowed), which the exec jail runs every child under.

```bash
task dev       # run locally; starts no Postgres, and startup fails without a configured datasource
task test      # full suite against Testcontainers Postgres restored from a backup (also compiles with lint)
task verify    # everything the gate runs
task faults:sim   # the nightly simulator runs (FAULT_SEED, FAULT_SIM_RUNS, FAULT_SIM_BREAK)
task faults:pg    # one weekly real-Postgres fault run (FAULT_SEED)
```

A failed fault run writes its seed, trace and rerun command to `target/fault-runs/`.

To run the stack the way it's deployed:

```bash
docker compose up --build
curl -s localhost:8080/healthz    # liveness
curl -s localhost:8080/readyz     # readiness (checks the database)
```

Compose reads `POSTGRES_PASSWORD` from your environment or an untracked `.env`; the names are in `.env.example`. With nothing set it uses a local-only default.

To run one test class: `./mvnw -B -ntp -Dtest=JobServiceTest test`.

HTTP API (spec in `contracts/openapi.yaml`):

| Route | What it does |
| --- | --- |
| `POST /api/documents` | Submit a document |
| `GET /api/documents?query=&limit=&cursor=` | Title-prefix search |
| `GET /audit/events?job_id=` | A job's audit chain |
| `GET /audit/replay?job_id=` | Replay a job |
| `GET /observe/saturation`, `GET /metrics` | Saturation signals |
| `GET /healthz`, `GET /readyz` | Health probes |

## Where things are

| What | Where |
| --- | --- |
| Code | `src/main/java/com/g1do/anvil/` |
| Tests | `src/test/java/com/g1do/anvil/` |
| Migrations (schema) | `src/main/resources/db/migration/` |
| API contract | `contracts/openapi.yaml` |
| Intentional breaks | `contracts/oasdiff-ignore.txt` (API), `contracts/schema-breaks.txt` (schema) |
| Exact dependency versions | `pom.xml`: Spring Boot BOM plus explicit versions, no ranges |
| Architecture diagrams | [`docs/architecture/`](docs/architecture/README.md) |
| Decisions | [`docs/decisions/`](docs/decisions/) |
| Threat model | [`docs/threat-model.md`](docs/threat-model.md) |
| Fencing model (TLA+ spec) | [`docs/faults/fencing.tla`](docs/faults/fencing.tla) |
| Gate | `.github/workflows/gate.yml`, which runs the same `task` commands as `Taskfile.yml` |
| Scheduled fault runs | `.github/workflows/faults.yml` (`task faults:sim`, `task faults:pg`, `task faults:issue`) |

The breaking-change check (`task test:contract`) watches `contracts/openapi.yaml` and `src/main/resources/db/migration/`.
