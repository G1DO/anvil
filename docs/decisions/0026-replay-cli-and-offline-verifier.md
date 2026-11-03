# 0026 — Replay from the command line, and an export verified with no database

Status: proposed 2026-11-03 (#75). Builds on the immutable audit chain (record 0009) and deterministic replay (record 0010). It doesn't change the chain, the hashes, the API or the schema.

## Context

A job could be replayed only through `GET /audit/replay`. An operator couldn't check a job when the API was down. Nobody could check a chain without a database connection, for example a chain kept for an audit or handed to someone outside the system. Records 0009 and 0010 deferred both the CLI and the offline verifier.

#75 asks for three things:
- a command that replays a job and prints `input_sha`, `output_sha` and `match`, identical to the API's result;
- an offline verifier that checks an exported chain with no database and reports the first bad sequence number when the chain is tampered with;
- the same tenant scoping as the API for both.

## Decision

**One replay, three callers.**
- `ReplayService` is split in two. `load` reads what replay needs in the caller's tenant: the job's state, its input root, its chain and its checkpoints. `evaluate` is a pure function from that to `{input_sha, output_sha, match}`.
- The API, `anvil replay` and `anvil verify` all call `evaluate`, so for the same chain they can't disagree.
- When both a chain break and a checkpoint disagreement exist, `evaluate` now reports the earlier one. Before, it reported the checkpoint. `match` is unchanged in every case, and the API returns no sequence number.

**The CLI.** It is `com.g1do.anvil.cli.AnvilCli`, in the same jar, and starts no Spring context, so no worker or relay runs. Operators run it with `task audit:replay`, `task audit:export` and `task audit:verify`, or from the deployed jar through Spring Boot's `PropertiesLauncher`.
- `replay` and `export` connect to Postgres with the service's own `SPRING_DATASOURCE_*` variables, not through the API.
- The session is set to read-only.
- If the login bypasses row-level security (a superuser or `BYPASSRLS`, like the Compose default `anvil`), the CLI switches to `anvil_app` with `SET ROLE`. If the role still bypasses RLS, it refuses to run.
- The tenant must exist in `tenants`, as `TenantAuthFilter` requires. After that, `ReplayService` reads under `SET LOCAL app.tenant`, so another tenant's job is "not found", exactly like a missing one.
- `replay` prints the API's response body through the same record and Jackson 3, byte for byte.
- Exit statuses:
  - 0: match
  - 1: no match
  - 2: usage or malformed input
  - 3: not found
  - 4: unknown tenant
  - 5: database error

**The export format.** The format is `anvil-chain/1`, one JSON object holding exactly what `load` read:
- `tenant_id`, `job_id`, `state` and `input_sha`;
- every event with its stored hashes, tenant, job and payload;
- the checkpoints.

`export` never overwrites a file. A later format gets a new name; a reader of `anvil-chain/1` refuses any other.

**The offline verifier.**
- `verify` parses the file as untrusted input. It reads at most 64 MiB, and refuses duplicate keys, unknown or missing fields, and wrong types.
- It sorts the events by `seq` (as the database orders them) and drops events of another tenant or job (as `scope` drops them from the database).
- It then runs `evaluate`. For an intact export, it prints the API's three fields, plus:
  - `first_bad_seq`: the first sequence number that fails;
  - `problem`: why `match` is false;
  - `head`: the last event's hash.
- An export of another tenant's or another job's chain is "not found", like a missing one.

**What the hashes don't cover is bound to what they do.** The header's `input_sha` and `state` aren't hashed, so editing them could turn a failed job into a match. The verifier refuses that:
- `input_sha` must equal the `sha256` its submit event recorded;
- `state` must equal the outcome of the last commit event, which every terminal transition appends (`JobService` commit and reconcile);
- a terminal state with no commit event, or a commit event under a non-terminal state, is a mismatch.

## Consequences

- An operator can replay a job, or keep its chain and verify it later, with the API down. Verification needs neither the API nor the database. `ReplayCliTest` proves:
  - CLI and API output are byte-identical for an intact, a running, a tampered and a checkpointed job;
  - an export verifies offline to the API's result;
  - each kind of tampering reports its first bad seq: an edited payload, a dropped event, a relinked or rehashed event, a changed type, a swapped artifact, an event moved to another tenant, a disagreeing checkpoint;
  - an edited header can't change the verdict;
  - tenant scoping holds for both, and the CLI reads as `anvil_app`, read-only, with nothing written.
- The CLI opens a second way into tenant data: database credentials instead of `X-Tenant-Id`. It holds the same rules as the API, and adds RLS for logins the API in Compose runs without. An export is tenant data at rest, and whoever holds the file holds the chain.
- **Residual: no key signs the chain.** Anyone who can write the file, or the database, can rewrite a chain whole with every hash recomputed, and it still verifies. `head` is printed so a value kept elsewhere can be compared. Anchoring heads outside the database, or signing exports, is future work.
- **Residual: an export is capped at 64 MiB.** Verification time grows with chain length, as replay's does (#76).
- **Rejected:**
  - An export route on the API. It needs the API running, which is the case #75 is about.
  - Starting the Spring application for the CLI. It would start workers and the relay.
  - Trusting the export header.
  - Writing to an existing file.
