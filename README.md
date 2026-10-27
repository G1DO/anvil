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

Each guarantee links to the tests that prove it. All tests run against real PostgreSQL in containers.

1. **No lost work, no double effect.** Execution is at-least-once with fenced, idempotent effects; it is never exactly-once.
   - A duplicate submit returns the same IDs and creates one job: [`SubmitIdempotencyTest`](src/test/java/com/g1do/anvil/SubmitIdempotencyTest.java).
   - Two workers never claim the same job. A stale or zombie worker affects 0 rows. Terminal jobs are never re-run: [`JobServiceTest`](src/test/java/com/g1do/anvil/JobServiceTest.java), [`DurableV1SchemaTest`](src/test/java/com/g1do/anvil/DurableV1SchemaTest.java).
   - A relay crash between publish and mark-sent re-publishes the same event, which applies once: [`OutboxRelayTest`](src/test/java/com/g1do/anvil/OutboxRelayTest.java).
   - Content survives a crash during write or garbage collection, and a torn file is never served: [`ContentStoreTest`](src/test/java/com/g1do/anvil/ContentStoreTest.java).
2. **No tenant can read or change another tenant's data, or learn that it exists.**
   - The database enforces isolation even with pooled connections and cached plans: [`RlsLeakTest`](src/test/java/com/g1do/anvil/RlsLeakTest.java).
   - A 28-case matrix over direct IDs, search, content addresses and presigned URLs shows 0 leaks: [`ReadIsolationTest`](src/test/java/com/g1do/anvil/ReadIsolationTest.java). The same holds for audit reads: [`AuditReadIsolationTest`](src/test/java/com/g1do/anvil/AuditReadIsolationTest.java).
   - Revoking a capability mid-run blocks the commit (0 rows, `403 NEEDS_APPROVAL`): [`CapRevokeMidRunTest`](src/test/java/com/g1do/anvil/CapRevokeMidRunTest.java).
   - Jailed execution can't see another tenant's files or reach the network: [`ExecJailTest`](src/test/java/com/g1do/anvil/ExecJailTest.java).
3. **Any job can be replayed bit-for-bit from its audit chain, and tampering is detected.**
   - Every effect is recorded in an append-only, hash-linked chain in the same transaction: [`AuditChainTest`](src/test/java/com/g1do/anvil/AuditChainTest.java).
   - `GET /audit/replay` returns `{input_sha, output_sha, match: true}` for an intact chain. A corrupted, dropped or re-linked event gives a mismatch. Checkpoint replay gives the same result: [`ReplayTest`](src/test/java/com/g1do/anvil/ReplayTest.java).
4. **Fencing holds under real faults.**
   - A deterministic simulator catches each seeded fencing break in under 500 runs: [`FenceSimTest`](src/test/java/com/g1do/anvil/FenceSimTest.java).
   - The fencing model keeps a single holder and no double effect: [`FenceModelTest`](src/test/java/com/g1do/anvil/FenceModelTest.java). It mirrors [`docs/faults/fencing.tla`](docs/faults/fencing.tla); the model-checker output is in [`docs/faults/tlc-output.log`](docs/faults/tlc-output.log).
   - Process suspend, kill and restart, and duplicate delivery each produce a single effect: [`FaultProofTest`](src/test/java/com/g1do/anvil/FaultProofTest.java).
   - A simulated point-in-time restore that rewinds the epoch lets a stale owner affect 0 rows with no double effect: [`PitrTimelineCheckTest`](src/test/java/com/g1do/anvil/PitrTimelineCheckTest.java). Why: [record 0013](docs/decisions/0013-pitr-timeline-check.md).
5. **Saturation is visible.** Queue age, claim latency and pool wait are exported without tenant data. The burn alerts fire on induced burn and stay silent when healthy: [`SaturationSignalTest`](src/test/java/com/g1do/anvil/SaturationSignalTest.java).

Not yet guaranteed (#34): a database restore with measured recovery time and data loss, and refusing commits when the database timeline is unknown or rewound.

## How to run

You need Java 25, Docker and [Task](https://taskfile.dev).

```bash
task dev       # run locally; Spring Boot Dev Services starts Postgres
task test      # full suite against Testcontainers Postgres (also compiles with lint)
task verify    # everything the gate runs
```

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
| Fencing model and its checker output | [`docs/faults/`](docs/faults/) |
| Gate | `.github/workflows/gate.yml`, which runs the same `task` commands as `Taskfile.yml` |

The breaking-change check (`task test:contract`) watches `contracts/openapi.yaml` and `src/main/resources/db/migration/`.
