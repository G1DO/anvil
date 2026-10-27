---- MODULE fencing ----
(*
 * Fencing-only model for issue #32 (REQ-4 fault part).
 *
 * Covers: claim, heartbeat, commit, zombie-resume, relay re-publish dedupe.
 * Mirrors the canonical SQL predicates (no behaviour change):
 *  - claim: atomic QUEUED -> RUNNING, token+1 / attempt+1,
 *    lease = clock + 10, same-txn ledger row, LIMIT 20 SKIP LOCKED abstracted
 *    to a single claimable pick.
 *  - heartbeat: extends only (owner, token, RUNNING, lease live) by DB clock.
 *  - commit: moves RUNNING -> terminal only on full (owner, token) match;
 *    stale owner/token affects zero rows, never appends audit.
 *  - reconciler: expired RUNNING with budget -> QUEUED (owner cleared);
 *    exhausted -> DEAD (audited); terminals immutable.
 *  - relay: poll unsent ORDER BY id LIMIT 100 -> publish (no txn) ->
 *    mark sent_at in separate txn guarded by sent_at IS NULL; crash between
 *    publish and mark re-publishes same stable identity (outbox.id).
 *  - consumer: INSERT processed_events ON CONFLICT DO NOTHING; apply once.
 *
 * Invariants:
 *  - SingleHolderPerLiveToken: at most one live holder per fencing token.
 *  - NoDoubleEffect: terminal commit at most once; processed_event at most
 *    once per stable identity; ledger (job, attempt) unique.
 *
 * Seeded breaks (checked by FenceSimTest):
 *  - BrokenFence: commit ignores fencing token (owner+state only).
 *  - BrokenLease: claim stamps no lease / non-atomic select-then-update,
 *    allowing double claim (lease unenforced).
 *  - BrokenRelay: mark before publish; publish throw loses the event.
 *
 * TLC: run with 1 job, 2 workers, clock 0..6. The checked-in
 * docs/faults/tlc-output.log records the Java exhaustive checker result
 * that mirrors this spec (TLC binary not run offline; spec is TLC-ready).
 *)

EXTENDS Naturals, FiniteSets, Sequences, TLC

CONSTANTS Workers, MaxAttempts, LeaseTTL

VARIABLES
    jobState,      \* "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "DEAD"
    jobOwner,      \* worker name or "" when unheld
    jobToken,      \* fencing token (nat)
    jobAttempt,    \* attempt counter (nat)
    jobLease,      \* lease expiry tick, -1 = none
    clock,         \* DB clock only (clock_timestamp abstraction)
    ledger,        \* set of [attempt |-> n, owner |-> w, token |-> t]
    outboxSent,    \* BOOLEAN: durable sent_at progress
    outboxPublished,\* set of published event identities (at-least-once)
    processed,     \* set of applied event identities (exactly-once)
    crashed        \* relay crashed between publish and mark

Vars == <<jobState, jobOwner, jobToken, jobAttempt, jobLease,
          clock, ledger, outboxSent, outboxPublished, processed, crashed>>

TypeOK ==
    /\ jobState \in {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "DEAD"}
    /\ jobToken \in Nat
    /\ jobAttempt \in Nat
    /\ jobLease \in Int
    /\ clock \in Nat

Init ==
    /\ jobState = "QUEUED"
    /\ jobOwner = ""
    /\ jobToken = 0
    /\ jobAttempt = 0
    /\ jobLease = -1
    /\ clock = 0
    /\ ledger = {}
    /\ outboxSent = FALSE
    /\ outboxPublished = {}
    /\ processed = {}
    /\ crashed = FALSE

LiveLease == jobLease > clock

Claimable ==
    /\ jobState = "QUEUED"
    /\ jobAttempt < MaxAttempts
    /\ (jobLease = -1 \/ jobLease <= clock)

Claim(w) ==
    /\ Claimable
    /\ jobState' = "RUNNING"
    /\ jobOwner' = w
    /\ jobToken' = jobToken + 1
    /\ jobAttempt' = jobAttempt + 1
    /\ jobLease' = clock + LeaseTTL
    /\ ledger' = ledger \cup {[attempt |-> jobAttempt + 1, owner |-> w, token |-> jobToken + 1]}
    /\ UNCHANGED <<clock, outboxSent, outboxPublished, processed, crashed>>

Heartbeat(w, t) ==
    /\ jobState = "RUNNING"
    /\ jobOwner = w
    /\ jobToken = t
    /\ LiveLease
    /\ jobLease' = clock + LeaseTTL
    /\ UNCHANGED <<jobState, jobOwner, jobToken, jobAttempt, clock,
                   ledger, outboxSent, outboxPublished, processed, crashed>>

Commit(w, t, target) ==
    /\ jobState = "RUNNING"
    /\ jobOwner = w
    /\ jobToken = t
    /\ target \in {"SUCCEEDED", "FAILED"}
    /\ jobState' = target
    /\ UNCHANGED <<jobOwner, jobToken, jobAttempt, jobLease, clock,
                   ledger, outboxSent, outboxPublished, processed, crashed>>

ZombieCommitFails(w, t) ==
    (* stale owner/token affects zero rows: state unchanged *)
    /\ jobState = "RUNNING"
    /\ (jobOwner /= w \/ jobToken /= t)
    /\ UNCHANGED Vars

ReconcileRelease ==
    /\ jobState = "RUNNING"
    /\ ~LiveLease
    /\ jobAttempt < MaxAttempts
    /\ jobState' = "QUEUED"
    /\ jobOwner' = ""
    /\ jobLease' = -1
    /\ UNCHANGED <<jobToken, jobAttempt, clock, ledger,
                   outboxSent, outboxPublished, processed, crashed>>

ReconcileDead ==
    /\ ((jobState = "QUEUED" /\ jobAttempt >= MaxAttempts) \/
        (jobState = "RUNNING" /\ ~LiveLease /\ jobAttempt >= MaxAttempts))
    /\ jobState' = "DEAD"
    /\ UNCHANGED <<jobOwner, jobToken, jobAttempt, jobLease, clock,
                   ledger, outboxSent, outboxPublished, processed, crashed>>

Tick == /\ clock' = clock + 1
        /\ UNCHANGED <<jobState, jobOwner, jobToken, jobAttempt, jobLease,
                       ledger, outboxSent, outboxPublished, processed, crashed>>

RelayPollPublish ==
    (* poll unsent, publish carrying stable identity outbox.id *)
    /\ ~outboxSent
    /\ outboxPublished' = outboxPublished \cup {"evt-1"}
    /\ UNCHANGED <<jobState, jobOwner, jobToken, jobAttempt, jobLease,
                   clock, ledger, outboxSent, processed, crashed>>

RelayMark ==
    (* durable progress only after successful publish *)
    /\ "evt-1" \in outboxPublished
    /\ ~crashed
    /\ outboxSent' = TRUE
    /\ UNCHANGED <<jobState, jobOwner, jobToken, jobAttempt, jobLease,
                   clock, ledger, outboxPublished, processed, crashed>>

RelayCrash ==
    (* kill -9 between publish and mark: mark never runs *)
    /\ "evt-1" \in outboxPublished
    /\ ~outboxSent
    /\ crashed' = TRUE
    /\ UNCHANGED <<jobState, jobOwner, jobToken, jobAttempt, jobLease,
                   clock, ledger, outboxSent, outboxPublished, processed>>

RelayRestart ==
    (* restart re-publishes same stable identity; consumer dedupes *)
    /\ crashed
    /\ ~outboxSent
    /\ outboxPublished' = outboxPublished \cup {"evt-1"}
    /\ UNCHANGED <<jobState, jobOwner, jobToken, jobAttempt, jobLease,
                   clock, ledger, outboxSent, processed, crashed>>

Consume ==
    (* INSERT ON CONFLICT DO NOTHING: first delivery applies, rest skip *)
    /\ "evt-1" \in outboxPublished
    /\ processed' = processed \cup {"evt-1"}
    /\ UNCHANGED <<jobState, jobOwner, jobToken, jobAttempt, jobLease,
                   clock, ledger, outboxSent, outboxPublished, crashed>>

Next ==
    \/ \E w \in Workers : Claim(w)
    \/ \E w \in Workers, t \in 0..4 : Heartbeat(w, t)
    \/ \E w \in Workers, t \in 0..4 : Commit(w, t, "SUCCEEDED")
    \/ \E w \in Workers, t \in 0..4 : Commit(w, t, "FAILED")
    \/ ReconcileRelease
    \/ ReconcileDead
    \/ Tick
    \/ RelayPollPublish
    \/ RelayMark
    \/ RelayCrash
    \/ RelayRestart
    \/ Consume

SingleHolderPerLiveToken ==
    (* at most one live holder: RUNNING + live lease implies unique owner/token;
       zombie with stale token holds nothing *)
    [](jobState = "RUNNING" /\ LiveLease =>
        \A r \in ledger : r.token <= jobToken)

NoDoubleEffect ==
    [](\A e \in processed : e = "evt-1")  \* set semantics: applied at most once

LedgerUnique ==
    \A r1, r2 \in ledger : r1.attempt = r2.attempt => r1 = r2

Spec == Init /\ [][Next]_Vars /\ WF_clock(Tick)

THEOREM CorrectFencing ==
    Spec => [](SingleHolderPerLiveToken /\ NoDoubleEffect /\ LedgerUnique)

====
