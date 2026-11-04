# 0027 — Replay a long chain from its latest checkpoint

Status: accepted 2026-11-03 (#76), with the V11 guard added 2026-11-04. Builds on the immutable chain (record 0009), deterministic replay (record 0010), and the CLI and offline verifier (record 0026). It changes the replay read path and adds the additive V10 migration plus the V11 summary guard; it does not change the API response, the hashes, the export format or tenant scoping.

## Context

Replay used checkpoints but still loaded and verified every event. Its cost grew with chain length, so a tenant could make replay slow by building a very long chain. Record 0010 deferred starting from the latest checkpoint without re-reading the prefix, and with it the `p99 < 500 ms` chain-DoS bound. Record 0026 left the same cost in the offline verifier.

## Decision

**The checkpoint is a trusted summary of the prefix.**
- V10 adds four nullable columns to `job_checkpoints`: `artifact_sha` (the last valid `cas.put` at or before the boundary, or `''`), `input_sha` (the first submit input root), and `commit_seq` / `commit_outcome` (the last commit at or before the boundary, or both null). From V11 a `BEFORE INSERT` trigger derives and validates them in the database; `AuditChain.append` supplies only tenant, job, seq and sha. A non-boundary seq, an event missing at the boundary, a boundary event that does not link to seq - 1, or a sha that does not match the event is rejected, and a supplied summary is overwritten with the chain-derived values. Existing pre-V10 checkpoints keep null values.
- `ReplayService.replay` reads the latest checkpoint and only events with `seq >= checkpoint.seq`. It recomputes the boundary event's hash, requires it to equal the checkpoint's `sha`, then verifies the suffix's sequence contiguity, `prev_hash` links and hashes. `output_sha` is computed from the suffix's last artifact, or the checkpoint's summary when the artifact is before the boundary. The unhashed header is bound to the checkpoint's `input_sha` and commit summary, plus any commit in the suffix.
- If the latest checkpoint predates V10, `replay` falls back to the whole chain. `load` still reads the whole chain for `anvil export`; `replayFull` is the full-chain comparison and audit path. `anvil replay` gets the fast path with the API; `anvil export` and the offline verifier still read and check the whole chain.

**Tampering before the checkpoint.**
- `audit_events` and `job_checkpoints` are append-only for every role, so a prefix event cannot be edited or deleted in place, and an existing checkpoint cannot be rewritten. The fast path also checks the boundary event against the checkpoint's stored `sha`: a forger who rewrites a prefix event and rehashes the chain through the boundary changes that event's hash, so replay refuses it at the boundary. A rewrite that leaves the boundary event unchanged is invisible to the fast path, because it never reads the prefix; the full replay catches the broken link at the boundary.
- A privileged actor who bypasses the triggers and edits only an earlier payload, without rehashing, is not read by the fast path. That edit is still caught by `replayFull` (the full audit path), and by the offline verifier on an export. If the same actor rewrites the prefix, the checkpoint, and every later hash, that is the existing whole-chain-rewrite residual: no key signs the chain (record 0026).
- The runtime role cannot rewrite or fake a checkpoint. It holds only `SELECT` and `INSERT` on `job_checkpoints`, and an immutability trigger rejects `UPDATE`/`DELETE` for every role. V11 also guards every insert: it accepts only a positive 100-event boundary whose `sha` equals the audit event at that seq and whose event links to seq - 1, then derives `artifact_sha`, `input_sha`, `commit_seq` and `commit_outcome` from the chain instead of trusting the writer (first submit root, last valid `cas.put` artifact, last commit). A non-boundary insert, a gap-jumping boundary, a non-matching sha or a forged summary is therefore rejected or overwritten. An actor who can bypass triggers (a superuser or `session_replication_role = replica`) is outside this control and remains covered by the whole-chain-rewrite residual.

**Latency.**
- Declared chain length: 100,000 events, checkpoint every 100, latest checkpoint at seq 99,900, so replay reads 100 events. The measurement uses the service replay call on the suite's containerized `postgres:18.6`, 100 samples after 5 warm-ups. p50 4 ms, p95 5 ms, p99 8 ms, max 10 ms, against the 500 ms bound: [report](../../load/runs/replay-latency/report.md) and [result](../../load/runs/replay-latency/result.json).
- `ReplayTest.replayOfDeclaredLongChainStaysUnderTheLatencyBound` rebuilds the fixture, asserts the output equals `replayFull`, and fails above 500 ms. `ReplayTest.theCommittedReplayLatencyReportDeclaresTheBound` keeps the recorded measurement honest.

## Consequences

- Replay cost is now the checkpoint lookup plus at most one interval (100 events) of chain, not the whole prefix. The offline verifier still reads an export of at most 8 MiB, as record 0026 says; exports are not part of the fast path.
- The checkpoint summary is tenant data in the same table as before, under the same RLS policy and grants. It carries no new tenant content beyond the input root and artifact hash the chain already holds.
- A V10 checkpoint has a larger row; one checkpoint is written per 100 events, in the same transaction as the event. The V11 trigger adds a boundary check and a few indexed prefix lookups per checkpoint, not per replay.
- Proven by `ReplayTest`:
  - `checkpointReplayStartsAtLatestCheckpointAndMatchesFull` (read starts at seq 200; header and artifact from the checkpoint; same `output_sha` as full);
  - `aConsistentRewriteBeforeTheCheckpointIsCaughtAtTheBoundary` (mismatch at the checkpoint seq);
  - `aPrefixRewriteThatStopsBeforeTheBoundaryIsCaughtByFullReplayOnly` (the documented fast-path residual, with full replay reporting the broken link);
  - `aPartialPrefixEditIsBlockedInPlaceAndCaughtByFullReplay` (trigger refuses the edit; full replay reports the first edited seq);
  - `suffixTamperAfterTheCheckpointReturnsMismatchNotAnException`, `boundaryEventTamperReturnsMismatchNotAnException` and `deletedSuffixEventReturnsMismatchNotAnException` (tamper at or after the boundary is a verdict, never an error);
  - `checkpointGuardRejectsNonBoundaryAndWrongSha`, `checkpointGuardRejectsACheckpointThatJumpsOverAGap`, `checkpointGuardDerivesTheSameSummaryAsFullReplayForExtraEvents` and `forgedCheckpointSummaryIsOverwrittenFromTheChain` (the V11 trigger rejects, links or overwrites a writer-supplied checkpoint);
  - `orphanCheckpointFallsThroughToFullReadAndReportsItsSeq` and `preV10CheckpointWithoutSummaryFallsBackToTheFullChain` (fallbacks report the checkpoint or read the whole chain);
  - `replayOfDeclaredLongChainStaysUnderTheLatencyBound` and `theCommittedReplayLatencyReportDeclaresTheBound`.
- Residual: a privileged partial prefix edit, or a rewrite that leaves the boundary event unchanged, is invisible to the fast path. That is the price of not reading the prefix; full replay remains the audit path. The fast path also trusts the checkpoint row and its V11-derived summary, so an actor who can bypass triggers can still rewrite the checkpoint; that is the whole-chain-rewrite residual.
- Rejected: trusting an unverified checkpoint; reading a fixed prefix window; a signed checkpoint key (not in this issue; the whole-chain rewrite residual already covers a keyless chain); changing `anvil-chain/1` to carry checkpoint summaries (the offline verifier reads the whole chain).
