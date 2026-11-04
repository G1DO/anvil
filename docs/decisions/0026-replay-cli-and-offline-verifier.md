# 0026 — Replay from the command line, and an export verified with no database

Status: proposed 2026-11-03 (#75). Builds on the immutable audit chain (record 0009) and deterministic replay (record 0010). It doesn't change the chain, the hashes, the API's response or the schema. It does make the API's replay stricter, as the first decision below says. Record 0027 (#76) later lets the database replay path read from the latest checkpoint; the offline verifier still reads the whole export.

## Context

A job could be replayed only through `GET /audit/replay`. An operator couldn't check a job when the API was down. Nobody could check a chain without a database connection, for example a chain kept for an audit or handed to someone outside the system. Records 0009 and 0010 deferred both the CLI and the offline verifier.

#75 asks for three things:
- a command that replays a job and prints `input_sha`, `output_sha` and `match`, identical to the API's result;
- an offline verifier that checks an exported chain with no database and reports the first bad sequence number when the chain is tampered with;
- the same tenant scoping as the API for both.

## Decision

**One replay, three callers.**
- `ReplayService` is split in two. `load` reads what replay needs in the caller's tenant: the job's state, its input root, its checkpoints and its chain. `evaluate` is a pure function from that to `{input_sha, output_sha, match}`, and the reason a chain fails.
- The API, `anvil replay` and `anvil verify` all call `evaluate`, so for the same chain, state and input root they give the same answer.
- `evaluate` runs three checks, and the first failure decides the verdict:
  1. the chain: seqs contiguous from 0, each `prev_hash` linked, each `event_hash` recomputed. Every checkpoint must name an event of the chain and carry that event's hash. `mismatchSeq` is the earliest failing seq;
  2. the header: `input_sha` must equal the `sha256` of the submit event. The `state` must equal the outcome of the last commit event, which every terminal transition appends, and a terminal `state` must have one;
  3. the state must be terminal.
- **This changes the API too, and makes it stricter.** Its replay used to check only the latest checkpoint and did not read the header. Every chain the system writes passes the new checks, so no honest job changes its answer. A database edit to the state or the input root, or to an earlier checkpoint, is now refused by the API as well as by the CLI.

**The CLI.** It is `com.g1do.anvil.cli.AnvilCli`, in the same jar, and starts no Spring context, so no worker or relay runs. Operators run it with `task audit:replay`, `task audit:export` and `task audit:verify`, or from the deployed jar through Spring Boot's `PropertiesLauncher`.
- `replay` and `export` connect to Postgres with the service's own `SPRING_DATASOURCE_*` variables, not through the API. The session is read-only.
- If the login bypasses row-level security (a superuser or `BYPASSRLS`, like the Compose default `anvil`), the CLI switches to `anvil_app` with `SET ROLE`. If it can't, or the role still bypasses RLS, the CLI refuses to read.
- The tenant must exist in `tenants`, as `TenantAuthFilter` requires. After that, `ReplayService` reads under `SET LOCAL app.tenant`, so another tenant's job is "not found", exactly like a missing one.
- `replay` prints the API's response body through the same record and Jackson 3, byte for byte.
- Exit statuses:
  - 0: match
  - 1: no match
  - 2: usage or malformed input
  - 3: not found
  - 4: unknown tenant
  - 5: database error
  - 6: failure: the output could not be written, or an unexpected error occurred. It is never 1, which is a verdict of no match.
- A result that does not reach stdout is a failure, not a success, so `anvil export > file` on a full disk exits 6.
- Errors show control characters escaped, so text from a file can't drive the terminal. A connection error shows neither the URL nor the password.
- `export --out` creates the file owner-only, never overwrites one (a symlink included), and removes a file whose write failed, so a retry is not blocked by it.
- `task` replaces a failing command's status with 201 unless it runs with `-x`. The operator's status is the CLI's, so use `task -x audit:verify -- ...`.

**The export format.** The format is `anvil-chain/1`, one JSON object holding exactly what `load` read: `tenant_id`, `job_id`, `state` and `input_sha`; every event with its stored hashes, tenant, job and payload; and the checkpoints. `export` never overwrites a file. A later format gets a new name; a reader of `anvil-chain/1` refuses any other.

**The offline verifier.**
- `verify` parses the file as untrusted input. It reads at most 8 MiB (below), and refuses duplicate keys, unknown or missing fields, wrong types, anything after the JSON object, and any lone surrogate in a string.
- It sorts the events by `seq` (as the database orders them) and drops events of another tenant or job (as `scope` drops them from the database). Then it runs `evaluate`.
- For an intact export it prints the API's three fields, plus `first_bad_seq` (the first failing sequence number), `problem` (why `match` is false) and `head` (the last event's hash).
- An export of another tenant's or another job's chain is "not found", like a missing one.

**The size cap.** Measured with `-Xmx`, using worst-case exports of the smallest events the format allows:
- 8 MiB (31,696 events) loads in a 64 MiB heap;
- 63 MiB (248,764 events) needs 256 to 512 MiB, and in a 64 MiB heap fails with an OutOfMemoryError, which exits 6.

So the cap is 8 MiB, about 15,000 realistic events. A job's chain is tens of events.

## Consequences

- An operator can replay a job, or keep its chain and verify it later, with the API down. Verification needs neither the API nor the database. `ReplayCliTest` and `ReplayTest` prove:
  - CLI and API output are byte-identical for an intact, a running, a database-tampered and a checkpointed job;
  - an export verifies offline to the API's result;
  - each kind of tampering reports its first bad seq: an edited payload, a dropped event, a relinked or rehashed event, a changed type, a swapped artifact, an event moved to another tenant, and a disagreeing checkpoint;
  - the earliest bad checkpoint is reported, a correct duplicate checkpoint can't hide a wrong one, and a checkpoint that names no event of the chain (a cut tail) is reported;
  - a database edit to the state, or to a payload, is refused by the API, the CLI replay and the verifier alike;
  - an edited header can't change the verdict;
  - malformed input (a lone surrogate anywhere, trailing content, a file over the cap) is refused without a verdict, and an error can't drive the terminal;
  - a result that does not reach stdout, or an unexpected error, exits 6;
  - tenant scoping holds for both, and the CLI's own connection reads as `anvil_app`, read-only, before it reads anything, and refuses a login that can't become it.
- The CLI opens a second way into tenant data: database credentials instead of `X-Tenant-Id`. It holds the same rules as the API, and adds RLS for logins that the API in Compose runs without. An export is tenant data at rest, and whoever holds the file holds the chain.
- **Residual: no key signs the chain.** Anyone who can write the file, or the database, can rewrite a chain whole with every hash recomputed, and it still verifies. `head` is printed so a value kept elsewhere can be compared. Anchoring heads outside the database, or signing exports, is future work.
- **The fast database replay and an export can disagree on a privileged prefix edit** (record 0027). Replay from a V10 checkpoint reads only the suffix and trusts the checkpoint row and its V11-derived summary; `anvil verify` reads the whole export. A superuser who bypasses the append-only triggers and edits an earlier payload without rehashing, or rewrites the prefix but leaves the boundary event unchanged, is caught by the verifier and by `replayFull`, but not by the fast replay. An in-place edit is refused for every role; a rewrite that rehashes through the boundary fails at the checkpoint boundary.
- **Residual: what no hash covers.** The file's hashes don't cover four things, so a verdict can't rule them out:
  - the tenant label: relabelling every `tenant_id` in a file makes it verify as the new tenant, and `head` is unchanged, so an operator must know which tenant a file came from;
  - the checkpoints: removing one removes the check it made, so a tail rewritten with every hash recomputed passes if the checkpoints beyond the edit are gone;
  - the events after a terminal commit (such as `cap.revoke`): cutting them leaves the verdict and changes only `head`, and cutting a `cas.put` there also changes `output_sha`, which the file has no expected value to check it against.
- **Residual: an export is capped at 8 MiB.** Verification time grows with chain length, as the offline verifier reads the whole export. The database replay path is bounded from record 0027 (#76); the CLI still exports the whole chain.
- **Rejected:**
  - An export route on the API. It needs the API running, which is the case #75 is about.
  - Starting the Spring application for the CLI. It would start workers and the relay.
  - Trusting the export header.
  - Writing to an existing file.
