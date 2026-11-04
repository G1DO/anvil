# anvil

A multi-tenant job service whose results can be trusted after crashes and audited after the fact.

## Problem and users

Teams that run work on behalf of many tenants hit three problems:

- **Crashes cause lost or doubled work.** A worker crash, a worker that resumes after its lease expired (a "zombie"), or a relay crash between publishing an event and recording it can lose a job or apply it twice.
- **Tenants leak into each other.** Shared connections, cached query plans or a guessable ID can show one tenant another tenant's data, or reveal that it exists.
- **Results can't be checked.** Nobody can prove afterwards what a job did, or detect that its record was altered.

ANVIL is for:

- **Tenant clients** that submit documents and jobs over HTTP (`X-Tenant-Id`, `Idempotency-Key`), read their documents, and read or replay their jobs' audit chains.
- **Operators** who run the service, watch its health and saturation signals, inspect a job's state, chain and replay result in a browser, and replay or verify a job's chain from the command line.

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
   - The [operator console](http://localhost:8080/console/) shows a job's current state (`GET /api/jobs/{id}`), its audit chain and its replay result. It uses the API's tenant scoping for every request, and a missing or another tenant's job takes the same generic not-found path: [`OperatorConsoleTest`](src/test/java/com/g1do/anvil/OperatorConsoleTest.java).
   - **A long chain replays from its latest checkpoint within a bound.** Replay reads the checkpoint and the events after it, not the prefix, and a declared 100,000-event chain replays at p99 8 ms against a 500 ms bound on containerized Postgres. A consistent prefix rewrite is caught at the checkpoint boundary; a privileged partial edit is caught by `replayFull` or the offline verifier. Why: [record 0027](docs/decisions/0027-replay-from-latest-checkpoint.md); [measurement](load/runs/replay-latency/report.md).
   - **Without the running API.** Why: [record 0026](docs/decisions/0026-replay-cli-and-offline-verifier.md).
     - `anvil replay` reads the database directly and prints the API's result for the same job, byte for byte.
     - `anvil verify` checks an exported chain with no database connection. It gives the API's result for an intact export, and on a tampered one it reports the first bad sequence number. Editing the export's job state or input root can't turn the verdict, because both are checked against the chain. A file can't prove which tenant it belongs to, or that nothing after the terminal commit was cut, and a chain rewritten whole still verifies. Compare `head` with a value kept elsewhere (record 0026).
     - Both scope by tenant as the API does: another tenant's job is `not found`, like a missing one. The CLI reads as the runtime role under row-level security, read-only.
     - Proven by [`ReplayCliTest`](src/test/java/com/g1do/anvil/ReplayCliTest.java).
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
6. **Extracted facts cite their source, stay in their tenant, and poison never reaches the index.** Why: [record 0022](docs/decisions/0022-single-bounded-extraction.md).
   - There is one extraction path, and it cuts a document to 500 characters in code before any model call: [`ExtractorTest`](src/test/java/com/g1do/anvil/extract/ExtractorTest.java).
   - A fact is indexed only when its citation is an exact substring of the text the model saw and holds its value. Anything else is quarantined: [`ExtractorTest`](src/test/java/com/g1do/anvil/extract/ExtractorTest.java), [`ExtractHarnessTest`](src/test/java/com/g1do/anvil/extract/ExtractHarnessTest.java).
   - Forged, malformed and other tenants' document IDs get the same `404` before any model call, and identical content in two tenants is extracted separately, with no shared cache or dedup: [`ExtractionIsolationTest`](src/test/java/com/g1do/anvil/ExtractionIsolationTest.java).
   - Poison input and rejected facts go to a dead-letter table with a reason, and no route serves it: [`ExtractionIsolationTest`](src/test/java/com/g1do/anvil/ExtractionIsolationTest.java).
   - **Model calls are cached per tenant and capped, and can be stopped.** Why: [record 0023](docs/decisions/0023-restrict-extraction-behind-budget-and-kill-switch.md).
     - A repeat of the same text, prompt and model in the same tenant makes no model call. Another tenant never hits that cache. Quarantined, failing or revoked entries are never served: [`ExtractionCostTest`](src/test/java/com/g1do/anvil/ExtractionCostTest.java), [`ExtractionFallbackTest`](src/test/java/com/g1do/anvil/extract/ExtractionFallbackTest.java).
     - Each call takes one from the tenant's budget, even across processes. With no budget left or the kill-switch off, the frozen rules answer with zero model calls, and the result is marked as fallback: [`ExtractionCostTest`](src/test/java/com/g1do/anvil/ExtractionCostTest.java).
     - Cost and latency are published in the [cost report](eval/runs/extract-v1-deepseek-flash-cost/cost.md): about $0.0001 to $0.0003 per document, with model-call p95 under 1.5 s.
   - **Quality and injection resistance are measured at full scale, every night.** Why: [record 0024](docs/decisions/0024-full-scale-eval-and-nightly-live-run.md).
     - The full set has 660 documents with 3,367 gold facts and 72 injection cases in thirteen languages, 20 of them base64-encoded. It is generated from a seed and frozen, and the gate scores it with 10,000-resample intervals and McNemar's test against the baseline: [`FullSetTest`](src/test/java/com/g1do/anvil/eval/FullSetTest.java), [`EvalStatsTest`](src/test/java/com/g1do/anvil/eval/EvalStatsTest.java).
     - The live model scores F1 0.996 [0.995, 0.998] against the baseline's 0.632, and 0 of 72 attacks succeed, none in any language or in base64: [report](eval/runs/extract-v1-deepseek-flash-full-v1/report.md).
     - The [`eval-nightly`](.github/workflows/eval-nightly.yml) workflow runs the full set against the live model every night. It fails, and files one issue, when the model falls below the baseline or drifts below the committed run: [`EvalNightlyTest`](src/test/java/com/g1do/anvil/eval/EvalNightlyTest.java), [`EvalGateTest`](src/test/java/com/g1do/anvil/eval/EvalGateTest.java). Its runs are on the [eval-nightly workflow page](https://github.com/G1DO/anvil/actions/workflows/eval-nightly.yml).
   - **Load and the full-text index are measured before they're claimed.** Why: [record 0025](docs/decisions/0025-gin-budget-before-soak.md).
     - A GIN index on fact values was measured over one soak day against a budget set beforehand, on a fresh Postgres server per variant. Neither variant met it, so the schema has none, and no soak numbers are claimed. Dropping such an index is its kill-switch, and extraction works without it: [`GinIndexTest`](src/test/java/com/g1do/anvil/GinIndexTest.java), [GIN report](load/runs/gin-budget/report.md).
     - One process tops out near 170 extractions a second, because each model call holds a request thread. So 140 requests per second is at its edge: p95 2.2 s to 9.2 s, and 0.7% to 19% not started, depending on how many clients wait. A 10x flood is queued, not refused, and recovers at once. The tables grow about 4 MiB per 1,000 documents, and autovacuum keeps them under 8% wasted. No soak numbers are claimed, because the index is over budget: [load summary](load/README.md), [`LoadRigTest`](src/test/java/com/g1do/anvil/load/LoadRigTest.java).
7. **Saturation is visible.** Queue age, claim latency and pool wait are exported without tenant data. The burn alerts fire on induced burn and stay silent when healthy: [`SaturationSignalTest`](src/test/java/com/g1do/anvil/SaturationSignalTest.java).

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
- A declared 100,000-event chain replays from its latest checkpoint at p99 8 ms, under the 500 ms bound, with the same `output_sha` as a full replay: [`ReplayTest`](src/test/java/com/g1do/anvil/ReplayTest.java), [measurement](load/runs/replay-latency/report.md).
- The CLI prints the API's replay result for the same job, and an exported chain verifies with no database, reporting the first bad sequence number when tampered, with the API's tenant scoping: [`ReplayCliTest`](src/test/java/com/g1do/anvil/ReplayCliTest.java).
- The browser console shows a job's state, audit chain and replay result, and a missing job and another tenant's job look the same: [`OperatorConsoleTest`](src/test/java/com/g1do/anvil/OperatorConsoleTest.java).
- A seeded fencing break is caught in under 500 simulator runs: [`FenceSimTest`](src/test/java/com/g1do/anvil/FenceSimTest.java).
- The fencing model keeps a single holder and no double effect: [`FenceModelTest`](src/test/java/com/g1do/anvil/FenceModelTest.java).
- Process suspend, kill and restart, and duplicate delivery each produce a single effect: [`FaultProofTest`](src/test/java/com/g1do/anvil/FaultProofTest.java).
- A tenant never exceeds its quota of running jobs, and a quiet tenant's jobs keep moving while another tenant floods the workers: [`FairClaimTest`](src/test/java/com/g1do/anvil/FairClaimTest.java).
- Extraction sends a model at most 500 characters, indexes only facts with an exact citation, and dead-letters the rest: [`ExtractorTest`](src/test/java/com/g1do/anvil/extract/ExtractorTest.java).
- Extraction shows 0 cross-tenant reads, results or dedup hits, and never serves a dead letter: [`ExtractionIsolationTest`](src/test/java/com/g1do/anvil/ExtractionIsolationTest.java).
- A cache hit, an exhausted budget and the kill-switch each make 0 model calls, and the budget is never overspent: [`ExtractionCostTest`](src/test/java/com/g1do/anvil/ExtractionCostTest.java).
- The full set of 732 documents reports precision, recall and F1 with 10,000-resample intervals and McNemar against the baseline, and its 72 injection cases report attack success by encoding and language: [`FullSetTest`](src/test/java/com/g1do/anvil/eval/FullSetTest.java).
- The full set runs nightly against the live model, fails when it drifts below the committed run, and files one issue: [`EvalNightlyTest`](src/test/java/com/g1do/anvil/eval/EvalNightlyTest.java), [`FileIssueScriptTest`](src/test/java/com/g1do/anvil/FileIssueScriptTest.java).
- The full-text index was measured against its budget before any soak number, and over budget it stays out of the schema; dropping it leaves extraction working: [`GinIndexTest`](src/test/java/com/g1do/anvil/GinIndexTest.java).
- Load at 140 rps and a 10x flood report p95, error rate and table growth, and the soak refuses to run while the full-text index is over budget: [`LoadRigTest`](src/test/java/com/g1do/anvil/load/LoadRigTest.java).
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
task eval         # eval gate on the frozen set (EVAL_CANDIDATE, default the committed extraction run)
task eval:extract # harness run: the extraction path over a frozen set (EVAL_SET; needs ANVIL_MODEL_API_KEY)
task eval:full    # eval gate on the full set (EVAL_CANDIDATE, default the committed full-set run)
task eval:nightly # what the nightly workflow runs: the full set against the live model, then its gate
task load:gin     # measure the full-text index against record 0025's budget
task load:steady  # 140 extraction requests per second for 5 minutes (also load:flood; load:soak needs a within-budget GIN verdict)
task -x audit:replay -- --tenant T --job J               # replay a job from the database, without the API
task -x audit:export -- --tenant T --job J --out F.json  # write the job's chain to a file (never overwrites)
task -x audit:verify -- --tenant T --job J F.json        # verify that file with no database
```

The load runs start the app with a stub model that has the live model's recorded latency, containerized Postgres and k6 on this host. They need Docker and write to `load/runs/`. A committed run is never overwritten. The soak refuses to run until `task load:gin` says the budget is met, which it doesn't today (record 0025). `LOAD_OUT=<dir>` and arguments after `--`, such as `PRE_VUS=300`, write a run elsewhere with changed k6 settings.

A failed fault run writes its seed, trace and rerun command to `target/fault-runs/`.

The replay CLI (`AnvilCli`, record 0026) starts no Spring context, so it runs nothing but the command.
- `replay` and `export` connect with `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`, the variables the service reads. The session is read-only, and a login that bypasses row-level security is switched to `anvil_app` first, or refused if it can't be.
- `replay` prints `{input_sha, output_sha, match}`. `verify` adds `first_bad_seq`, `problem` and `head`, the last event's hash. Compare `head` with one you kept, because a chain rewritten whole with every hash recomputed still verifies.
- Exit statuses: 0 match, 1 no match, 2 usage or malformed input, 3 not found, 4 unknown tenant, 5 database error, 6 failure (the output could not be written, or an unexpected error; never 1).
- Run them with `task -x`. Without `-x`, `task` replaces any non-zero status with 201, so the status above is lost.
- In the deployed image: `docker compose exec app java -cp app.jar -Dloader.main=com.g1do.anvil.cli.AnvilCli org.springframework.boot.loader.launch.PropertiesLauncher replay --tenant T --job J`.

To run the stack the way it's deployed:

```bash
docker compose up --build
curl -s localhost:8080/healthz    # liveness
curl -s localhost:8080/readyz     # readiness (checks the database)
```

Open <http://localhost:8080/console/> to inspect a job. Enter a tenant and a job ID; the
console sends `X-Tenant-Id` to the same API routes as any other client, shows the job's
state, chain and replay result, and shows the same "Job not found" for a missing job and
another tenant's job.

Compose reads `POSTGRES_PASSWORD` from your environment or an untracked `.env`; the names are in `.env.example`. With nothing set it uses a local-only default.

Extraction calls the model configured under `anvil.extraction` in `application.yaml` (DeepSeek's chat completions API, `deepseek-flash`) with the key in `ANVIL_MODEL_API_KEY`. `ANVIL_MODEL` and `ANVIL_MODEL_BASE_URL` override the model and URL for the app and `task eval:extract` alike. Without a key the app still starts, and extraction answers `503` without calling anything. A tenant also needs a call budget, which an operator grants in `extraction_budgets`. Without one, or with the kill-switch in `extraction_switch` off, the rules answer and the result is marked as fallback. The statements are in [record 0023](docs/decisions/0023-restrict-extraction-behind-budget-and-kill-switch.md).

To run one test class: `./mvnw -B -ntp -Dtest=JobServiceTest test`.

HTTP API (spec in `contracts/openapi.yaml`):

| Route | What it does |
| --- | --- |
| `POST /api/documents` | Submit a document |
| `GET /api/documents?query=&limit=&cursor=` | Title-prefix search |
| `POST /api/documents/{id}/extract` | Extract cited facts from a document, once per version, prompt and model; from the tenant's cache, the model, or the rules fallback |
| `GET /api/documents/{id}/facts` | The document's indexed facts, marked when they come from the fallback |
| `GET /api/jobs/{id}` | A job's current state, scoped like the other reads |
| `GET /audit/events?job_id=` | A job's audit chain |
| `GET /audit/replay?job_id=` | Replay a job |
| `GET /observe/saturation`, `GET /metrics` | Saturation signals |
| `GET /healthz`, `GET /readyz` | Health probes |
| `GET /console/` | Operator console page (browser; calls the routes above with `X-Tenant-Id`) |

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
| Frozen eval sets and gate fixtures | `eval/sets/pilot-v1/` (record 0021), `eval/sets/full-v1/` (record 0024; built by `src/test/java/com/g1do/anvil/eval/FullSetGenerator.java`), never edited; `eval/fixtures/` |
| Extraction harness runs and the cost report | `eval/runs/` (records 0022, 0023, 0024) |
| Load script, GIN budget and load reports | `load/extract.js`, `load/runs/` (record 0025) |
| Operator console | `src/main/resources/static/console/` (`index.html`, `console.js`, `console.css`) and `src/main/java/com/g1do/anvil/console/ConsoleController.java` |
| Replay CLI, export format and offline verifier | `src/main/java/com/g1do/anvil/cli/AnvilCli.java`; `replay/ChainExport.java` (`anvil-chain/1`) and `replay/OfflineVerifier.java` (record 0026) |
| Replay from the latest checkpoint and its latency report | `src/main/java/com/g1do/anvil/replay/ReplayService.java`; `load/runs/replay-latency/` (record 0027) |
| Filing a failed scheduled run | `scripts/file-issue.sh` (one issue per title while it's open) |
| Fencing model (TLA+ spec) | [`docs/faults/fencing.tla`](docs/faults/fencing.tla) |
| Gate | `.github/workflows/gate.yml`, which runs the same `task` commands as `Taskfile.yml` |
| Scheduled fault runs | `.github/workflows/faults.yml` (`task faults:sim`, `task faults:pg`, `task faults:issue`) |
| Nightly live eval | `.github/workflows/eval-nightly.yml` (`task eval:nightly`, `task eval:issue`; needs the `ANVIL_MODEL_API_KEY` repository secret) |

The breaking-change check (`task test:contract`) watches `contracts/openapi.yaml`, `src/main/resources/db/migration/` and `eval/sets/`.

`task eval` scores a candidate's predictions (one JSON line per document) against the frozen eval set and its baseline. It prints precision, recall and F1 with intervals, the paired F1 difference, attack success and the citation check, and writes the report to `target/eval/report.md`. It fails on a difference interval wholly below 0, a rise in attack success, or a citation that isn't an exact substring of its document. By default it scores the committed extraction run, `eval/runs/extract-v1-deepseek-flash` (record 0022). `task eval:full` does the same on the full set, with McNemar's test and attack success by encoding and language (record 0024).
