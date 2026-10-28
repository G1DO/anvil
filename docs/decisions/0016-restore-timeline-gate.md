# 0016 — Fail-closed timeline gate after a database restore

Status: proposed 2026-10-28 (#34). Builds the gate that record 0013 requires. No recovery-time or data-loss target is committed.

## Context

A point-in-time restore rewinds `jobs.fencing_token` and `cap_grants`. A worker that claimed or got a grant after the restore target still holds that token or grant, and the restored database can hand the same token or grant epoch out again. Record 0013 showed that fencing and capability checks hold on a simulated rewind and required a gate that refuses commits while the timeline is unknown or rewound, until leases expire and capabilities are re-issued.

The service needs to notice a restore by itself, including after it restarts. Anything it remembers in memory is lost on restart. Anything it stores in the database is rewound with the restore.

## Decision

- **Detect from inside the database.** `db_timeline` (V6) holds one row: the `(system_identifier, timeline_id)` pair the service last confirmed. A restore rewinds this row too, while Postgres moves to a new timeline when recovery promotes, so the stored pair stops matching the server's. `timeline_id` comes from the current WAL file name, which changes at promotion before any checkpoint. A restore into a fresh cluster changes `system_identifier`.
- **Refuse while unconfirmed.** Claim, heartbeat and both commit paths call `TimelineGuard.requireConfirmed` in their transaction. A mismatch, a missing row, or a timeline that can't be read (for example `pg_control_system()` revoked, or the server still in recovery) throws `TimelineUnconfirmedException` (`503 TIMELINE_UNCONFIRMED`) before any job row changes. Heartbeat is refused too, so a lost-timeline worker can't keep a restored lease alive forever.
- **Confirm only when it's safe.** `JobService.confirmTimeline()` runs in one transaction. It returns `WAITING_FOR_LEASES` and changes nothing while any lease is live on the database clock. Otherwise it:
  - raises every open job's `fencing_token` by `max_attempts`. A claim needs `attempt < max_attempts` and raises token and attempt together, so the lost timeline issued at most `max_attempts - attempt` more tokens. The next claim is above all of them.
  - deletes every capability grant and audits each as `cap.revoke`. Every commit is denied until a grant is re-issued, and a re-issue creates a new grant id, so a grant id and epoch held from the lost timeline never match again.
  - stores the server's pair, which reopens the gate.
- **Workers reopen the gate.** `claim` calls `confirmTimeline()` when the gate refuses, so the first poll after the leases expire confirms the timeline. An operator doesn't need to step in.
- **Prove it on a real restore.** `PitrRestoreDrillTest` runs a backup, clean and point-in-time restore on containerized Postgres to a named restore point and prints the measured recovery time and data loss. Every Spring context in the suite runs against a database restored the same way (`TestcontainersConfiguration`), so the full suite passing is the post-restore check.

Alternatives considered:
- **Revoke grants instead of deleting them.** A re-issue would keep the grant id and bump the epoch, which can land on an epoch the lost timeline already issued.
- **Remember the timeline in the service.** A restart forgets it, and the restored database then looks normal.
- **An operator runbook alone, with no gate.** That depends on someone remembering the steps during an incident. Record 0013 rejected it.

## Consequences

- Every claim, heartbeat and commit runs one more query, which reads the control file through `pg_control_system()`.
- After a restore, no job commits until the restored leases expire (at most `LEASE_TTL_SECONDS`) and each job's grant is re-issued. The drill counts that wait in its recovery time.
- The full suite takes longer: the shared database is restored once per run, and the drill restores its own.
- Gaps, not covered by the gate:
  - **A restore that keeps the timeline isn't detected.** Examples are a storage snapshot rolled back in place, or a base backup started without `recovery.signal`. Both keep `system_identifier` and `timeline_id`.
  - **Effects outside the database repeat.** A job that committed after the target runs and commits again on the restored database. Re-published events keep their outbox id, so a consumer whose dedup store wasn't restored applies them once. A job submitted after the target is gone, though. If the client retries it, the retry gets a new job and a new event id, which such a consumer applies again.
  - **Content files written after the target stay on disk** without an `artifacts` row.
  - **The old primary must be gone.** If it keeps running next to the restored one (split brain or failover), the gate can't see its writes. That's out of scope here.
  - **The measured numbers aren't a target.** They come from one small database in a disposable container on the gate runner, with the WAL archive on local disk. The recovery point is the declared restore point, not the last archived WAL.
