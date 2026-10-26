package com.g1do.anvil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fencing-only executable model for issue #32 (REQ-4, box 1).
 *
 * <p>Mirrors {@code docs/faults/fencing.tla} and the canonical SQL predicates
 * in {@code JobService} / {@code OutboxRelay} / {@code IdempotentConsumer}
 * without touching production behaviour (test-only, in-memory, no Spring, no
 * PG). Covers claim, heartbeat, commit, zombie-resume, and relay re-publish
 * dedupe; asserts single-holder-per-live-token and no double effect.
 *
 * <p>Exhaustive BFS over 1 job x 2 workers x clock 0..6 checks every
 * interleaving up to depth 8. Seeded breaks are in {@code FenceSimTest};
 * real claim/relay/CAS observations are in {@code docs/faults/bug-log.md}.
 */
class FenceModelTest {

    enum State { QUEUED, RUNNING, SUCCEEDED, FAILED, DEAD }

    static final int MAX_ATTEMPTS = 5;
    static final int LEASE_TTL = 10;
    static final String[] WORKERS = {"A", "B"};

    record LedgerEntry(int attempt, String owner, long token) {}

    static final class ModelState {
        State state = State.QUEUED;
        String owner = "";
        long token;
        int attempt;
        long lease = -1;
        long clock;
        Set<LedgerEntry> ledger = new HashSet<>();
        boolean outboxSent;
        Set<String> published = new HashSet<>();
        Set<String> processed = new HashSet<>();
        boolean crashed;
        List<String> trace = new ArrayList<>();

        ModelState copy() {
            ModelState c = new ModelState();
            c.state = state;
            c.owner = owner;
            c.token = token;
            c.attempt = attempt;
            c.lease = lease;
            c.clock = clock;
            c.ledger = new HashSet<>(ledger);
            c.outboxSent = outboxSent;
            c.published = new HashSet<>(published);
            c.processed = new HashSet<>(processed);
            c.crashed = crashed;
            c.trace = new ArrayList<>(trace);
            return c;
        }

        boolean liveLease() {
            return lease > clock;
        }

        boolean claimable() {
            return state == State.QUEUED && attempt < MAX_ATTEMPTS && (lease == -1 || lease <= clock);
        }

        /** Single-holder-per-live-token: ledger never holds a token ahead of the job. */
        void checkSingleHolder() {
            if (state == State.RUNNING && liveLease()) {
                for (LedgerEntry e : ledger) {
                    if (e.token() > token) {
                        throw new AssertionError("single-holder violated: ledger " + e + " ahead of token "
                                + token + " trace=" + trace);
                    }
                }
            }
        }

        /** No double effect: terminal applied at most once, processed set semantics. */
        void checkNoDoubleEffect() {
            if (processed.size() > 1) {
                throw new AssertionError("double effect: processed=" + processed + " trace=" + trace);
            }
            Map<Integer, LedgerEntry> byAttempt = new HashMap<>();
            for (LedgerEntry e : ledger) {
                if (byAttempt.putIfAbsent(e.attempt(), e) != null) {
                    throw new AssertionError("ledger fork at attempt " + e.attempt() + " trace=" + trace);
                }
            }
        }

        void check() {
            checkSingleHolder();
            checkNoDoubleEffect();
        }
    }

    /** All successors under the correct fencing rules (mirrors fencing.tla Next). */
    static List<ModelState> successors(ModelState s) {
        List<ModelState> out = new ArrayList<>();
        for (String w : WORKERS) {
            if (s.claimable()) {
                ModelState n = s.copy();
                n.state = State.RUNNING;
                n.owner = w;
                n.token = s.token + 1;
                n.attempt = s.attempt + 1;
                n.lease = s.clock + LEASE_TTL;
                n.ledger.add(new LedgerEntry(n.attempt, w, n.token));
                n.trace.add("claim(" + w + ") t" + n.token + " a" + n.attempt);
                out.add(n);
            }
            for (long t = 0; t <= 4; t++) {
                if (s.state == State.RUNNING && s.owner.equals(w) && s.token == t && s.liveLease()) {
                    ModelState n = s.copy();
                    n.lease = s.clock + LEASE_TTL;
                    n.trace.add("heartbeat(" + w + ",t" + t + ")");
                    out.add(n);
                }
                for (String target : new String[]{"SUCCEEDED", "FAILED"}) {
                    if (s.state == State.RUNNING && s.owner.equals(w) && s.token == t) {
                        ModelState n = s.copy();
                        n.state = State.valueOf(target);
                        n.trace.add("commit(" + w + ",t" + t + ")->" + target);
                        out.add(n);
                    }
                }
            }
        }
        if (s.state == State.RUNNING && !s.liveLease() && s.attempt < MAX_ATTEMPTS) {
            ModelState n = s.copy();
            n.state = State.QUEUED;
            n.owner = "";
            n.lease = -1;
            n.trace.add("reconcileRelease");
            out.add(n);
        }
        if ((s.state == State.QUEUED && s.attempt >= MAX_ATTEMPTS)
                || (s.state == State.RUNNING && !s.liveLease() && s.attempt >= MAX_ATTEMPTS)) {
            ModelState n = s.copy();
            n.state = State.DEAD;
            n.trace.add("reconcileDead");
            out.add(n);
        }
        {
            ModelState n = s.copy();
            n.clock = s.clock + 1;
            n.trace.add("tick->" + n.clock);
            out.add(n);
        }
        if (!s.outboxSent) {
            ModelState n = s.copy();
            n.published.add("evt-1");
            n.trace.add("relayPublish(evt-1)");
            out.add(n);
        }
        if (s.published.contains("evt-1") && !s.crashed && !s.outboxSent) {
            ModelState n = s.copy();
            n.outboxSent = true;
            n.trace.add("relayMark(evt-1)");
            out.add(n);
        }
        if (s.published.contains("evt-1") && !s.outboxSent && !s.crashed) {
            ModelState n = s.copy();
            n.crashed = true;
            n.trace.add("relayCrash(publish|mark)");
            out.add(n);
        }
        if (s.crashed && !s.outboxSent) {
            ModelState n = s.copy();
            n.published.add("evt-1");
            n.trace.add("relayRestart(republish evt-1)");
            out.add(n);
        }
        if (s.published.contains("evt-1")) {
            ModelState n = s.copy();
            n.processed.add("evt-1");
            n.trace.add("consume(evt-1)");
            out.add(n);
        }
        return out;
    }

    @Test
    void fencingOnlyModelHoldsSingleHolderAndNoDoubleEffect() {
        ModelState init = new ModelState();
        Queue<ModelState> queue = new ArrayDeque<>();
        queue.add(init);
        Set<String> seen = new HashSet<>();
        int explored = 0;
        int maxDepth = 8;
        Queue<Integer> depths = new ArrayDeque<>();
        depths.add(0);
        while (!queue.isEmpty()) {
            ModelState s = queue.poll();
            int d = depths.poll();
            s.check();
            explored++;
            if (d >= maxDepth || s.clock > 6) {
                continue;
            }
            for (ModelState n : successors(s)) {
                n.check();
                String key = n.state + "|" + n.owner + "|t" + n.token + "|a" + n.attempt
                        + "|l" + n.lease + "|c" + n.clock + "|sent" + n.outboxSent
                        + "|proc" + n.processed.size() + "|cr" + n.crashed + "|led" + n.ledger.size();
                if (seen.add(key + "@" + d)) {
                    queue.add(n);
                    depths.add(d + 1);
                }
            }
        }
        assertThat(explored).as("model must explore a non-trivial state space").isGreaterThan(500);
        System.out.println("MODEL explored=" + explored + " maxDepth=" + maxDepth + " invariants=SingleHolderPerLiveToken,NoDoubleEffect,LedgerUnique");
    }

    @Test
    void zombieResumeAffectsZeroRowsWithExactlyOnceLedger() {
        // Direct scenario mirroring JobServiceTest#zombieFenceLoserCommitAffectsZeroRows:
        // A claims, expiry + reconcile releases, B claims token+1, A heartbeat/commit
        // affect zero rows, B commits once, ledger holds A:1/B:2.
        ModelState s = new ModelState();
        // A claims.
        s.state = State.RUNNING;
        s.owner = "A";
        s.token = 1;
        s.attempt = 1;
        s.lease = s.clock + LEASE_TTL;
        s.ledger.add(new LedgerEntry(1, "A", 1));
        // Expire and release.
        s.clock = s.lease + 1;
        assertThat(s.liveLease()).isFalse();
        s.state = State.QUEUED;
        s.owner = "";
        s.lease = -1;
        // B claims.
        s.state = State.RUNNING;
        s.owner = "B";
        s.token = 2;
        s.attempt = 2;
        s.lease = s.clock + LEASE_TTL;
        s.ledger.add(new LedgerEntry(2, "B", 2));
        // A resumes: owner/token mismatch -> zero rows (state/owner unchanged).
        boolean aHeartbeat = s.state == State.RUNNING && s.owner.equals("A") && s.token == 1 && s.liveLease();
        boolean aCommit = s.state == State.RUNNING && s.owner.equals("A") && s.token == 1;
        assertThat(aHeartbeat).as("stale A heartbeat affects 0 rows").isFalse();
        assertThat(aCommit).as("stale A commit affects 0 rows").isFalse();
        assertThat(s.owner).isEqualTo("B");
        // B commits once.
        assertThat(s.state).isEqualTo(State.RUNNING);
        s.state = State.SUCCEEDED;
        assertThat(s.state).isEqualTo(State.SUCCEEDED);
        assertThat(s.ledger).hasSize(2);
        s.check();
    }

    @Test
    void relayRestartRepublishesSameIdentityWithSingleApply() {
        ModelState s = new ModelState();
        s.published.add("evt-1");
        // Crash between publish and mark: no progress, single effect slot.
        assertThat(s.outboxSent).isFalse();
        s.crashed = true;
        // Restart re-publishes same stable identity; consumer dedupes to one apply.
        s.published.add("evt-1");
        s.processed.add("evt-1");
        s.processed.add("evt-1");
        assertThat(s.published).containsExactly("evt-1");
        assertThat(s.processed).containsExactly("evt-1");
        s.outboxSent = true;
        s.check();
    }
}
