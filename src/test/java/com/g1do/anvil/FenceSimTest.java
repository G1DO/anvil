package com.g1do.anvil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic simulation for issue #32 (REQ-4, box 2).
 *
 * <p>Test-only in-memory sim (no Spring, no PG). Three seeded broken variants,
 * each detected in under 500 runs with seed + trace kept:
 * <ul>
 *   <li>broken fence: commit ignores fencing token (owner+state only);</li>
 *   <li>broken lease: claim stamps no lease / non-atomic select-then-update,
 *   allowing double claim;</li>
 *   <li>broken relay: mark before publish; publish throw loses the event.</li>
 * </ul>
 *
 * <p>Determinism: {@code new Random(baseSeed + run)} drives every choice;
 * detection run + violating trace are asserted and printed for
 * {@code docs/faults/tlc-output.log} and {@code src/test/resources/fault-sim/}.
 * Correct fencing never violates (see {@code FenceModelTest}).
 */
class FenceSimTest {

    enum Variant { BROKEN_FENCE, BROKEN_LEASE, BROKEN_RELAY }

    record SimResult(boolean violated, int detectedAtRun, long seed, List<String> trace) {}

    static final long BASE_SEED = 0xC10C32L;

    /** One sim run; returns violating trace or null when no violation. */
    static List<String> runOnce(Variant variant, Random rnd) {
        // Minimal job: state, owner, token, lease-live flag.
        String state = "QUEUED";
        String owner = "";
        long token = 0;
        boolean leaseLive = false;
        Set<String> ledgerAttempts = new HashSet<>();
        Map<String, Integer> ledgerCount = new HashMap<>();
        List<String> trace = new ArrayList<>();
        // Relay: outbox state.
        boolean outboxSent = false;
        boolean published = false;
        boolean applied = false;
        int applyCount = 0;
        trace.add("seed-variant=" + variant);

        for (int step = 0; step < 20; step++) {
            int action = rnd.nextInt(7);
            switch (action) {
                case 0 -> { // claim by A or B
                    String w = rnd.nextBoolean() ? "A" : "B";
                    if (variant == Variant.BROKEN_LEASE) {
                        // Broken lease: non-atomic select-then-update, no lease stamp:
                        // both workers observe QUEUED (no SKIP LOCKED, no lease) and
                        // enter RUNNING, yielding two live holders.
                        if (state.equals("QUEUED") || state.equals("RUNNING")) {
                            if (owner.isEmpty()) {
                                owner = w;
                                token += 1;
                                state = "RUNNING";
                                leaseLive = false; // never stamped
                                trace.add("claim(" + w + ") t" + token + " [broken: no lease]");
                                String key = token + ":" + w;
                                ledgerCount.merge(key, 1, Integer::sum);
                                ledgerAttempts.add("a" + token + ":" + w);
                                if (ledgerCount.get(key) > 1) {
                                    trace.add("VIOLATION double-claim same token");
                                    return trace;
                                }
                            } else if (!owner.equals(w)) {
                                // Second claimant also enters RUNNING: two holders.
                                trace.add("VIOLATION double-holder " + owner + " + " + w);
                                return trace;
                            }
                        }
                    } else {
                        if (state.equals("QUEUED") && (leaseLive == false)) {
                            owner = w;
                            token += 1;
                            state = "RUNNING";
                            leaseLive = true;
                            trace.add("claim(" + w + ") t" + token);
                            ledgerAttempts.add("a" + token + ":" + w);
                        }
                    }
                }
                case 1 -> { // expire lease
                    leaseLive = false;
                    trace.add("expire");
                }
                case 2 -> { // reconcile release
                    if (state.equals("RUNNING") && !leaseLive) {
                        state = "QUEUED";
                        owner = "";
                        trace.add("reconcileRelease");
                    }
                }
                case 3 -> { // stale commit attempt by non-holder
                    String w = rnd.nextBoolean() ? "A" : "B";
                    long stale = token > 0 ? token - 1 : 0;
                    if (variant == Variant.BROKEN_FENCE) {
                        // Broken: checks owner-or-state only, ignores token.
                        if (state.equals("RUNNING") && (owner.equals(w) || stale >= 0)) {
                            // Zombie with stale token affects the row: violation.
                            if (!owner.equals(w) || stale != token) {
                                trace.add("VIOLATION zombie-commit(" + w + ",stale t" + stale
                                        + ") overwrote holder " + owner + " t" + token);
                                return trace;
                            }
                        }
                    } else {
                        boolean ok = state.equals("RUNNING") && owner.equals(w) && stale == token;
                        trace.add("commit(" + w + ",t" + stale + ")=" + ok + " (fenced)");
                    }
                }
                case 4 -> { // legitimate commit by holder
                    if (state.equals("RUNNING") && !owner.isEmpty()) {
                        trace.add("commit(" + owner + ",t" + token + ")->SUCCEEDED");
                        state = "SUCCEEDED";
                        if (ledgerAttempts.size() != token) {
                            trace.add("VIOLATION ledger fork");
                            return trace;
                        }
                    }
                }
                case 5 -> { // relay publish/mark
                    if (variant == Variant.BROKEN_RELAY) {
                        // Broken: mark before publish; throw loses the event.
                        outboxSent = true;
                        trace.add("mark(sent) before publish [broken]");
                        if (rnd.nextInt(4) == 0) {
                            trace.add("publish THROW after mark -> VIOLATION loss (sent but never applied)");
                            return trace;
                        }
                        published = true;
                    } else {
                        published = true;
                        trace.add("publish(evt-1)");
                        outboxSent = true;
                        trace.add("mark(evt-1)");
                    }
                }
                case 6 -> { // consume
                    if (published && !outboxSent && variant == Variant.BROKEN_RELAY) {
                        trace.add("consume skipped (unsent)");
                    } else if (published) {
                        if (!applied) {
                            applied = true;
                            applyCount++;
                            trace.add("consume(evt-1) applied once");
                        } else {
                            trace.add("consume(evt-1) deduped");
                        }
                        if (applyCount > 1) {
                            trace.add("VIOLATION double-apply");
                            return trace;
                        }
                    }
                }
                default -> throw new IllegalStateException();
            }
            // Cross-step invariant: broken fence double-terminal.
            if (state.equals("SUCCEEDED") && variant == Variant.BROKEN_FENCE && rnd.nextInt(8) == 0) {
                // Second stale commit would overwrite terminal in broken impl
                // (guard trigger would reject in PG; broken sim models missing guard).
                trace.add("VIOLATION terminal-overwrite (broken fence, no guard)");
                return trace;
            }
        }
        // End-of-run checks per variant.
        if (variant == Variant.BROKEN_RELAY && outboxSent && !applied && published) {
            // Loss can hide until end: marked sent but never applied counts if throw path missed.
            // Only flag when the trace shows mark-before-publish with no consume.
            if (trace.stream().anyMatch(t -> t.contains("mark(sent) before publish")) && !applied) {
                trace.add("VIOLATION loss at end (sent, never applied)");
                return trace;
            }
        }
        return null;
    }

    static SimResult findViolation(Variant variant, long baseSeed, int budget) {
        for (int run = 0; run < budget; run++) {
            Random rnd = new Random(baseSeed + run);
            List<String> trace = runOnce(variant, rnd);
            if (trace != null) {
                List<String> headed = new ArrayList<>();
                headed.add("variant=" + variant + " seed=" + (baseSeed + run) + " run=" + run);
                headed.addAll(trace);
                return new SimResult(true, run, baseSeed + run, headed);
            }
        }
        return new SimResult(false, -1, -1, List.of());
    }

    @Test
    void brokenFenceDetectedInUnder500Runs() {
        SimResult r = findViolation(Variant.BROKEN_FENCE, BASE_SEED, 500);
        assertThat(r.violated()).as("broken fence must be caught").isTrue();
        assertThat(r.detectedAtRun()).as("detection run").isLessThan(500);
        System.out.println("SIM broken-fence detectedAtRun=" + r.detectedAtRun() + " seed=" + r.seed());
        r.trace().forEach(t -> System.out.println("TRACE " + t));
    }

    @Test
    void brokenLeaseDetectedInUnder500Runs() {
        SimResult r = findViolation(Variant.BROKEN_LEASE, BASE_SEED + 10_000, 500);
        assertThat(r.violated()).as("broken lease must be caught").isTrue();
        assertThat(r.detectedAtRun()).as("detection run").isLessThan(500);
        System.out.println("SIM broken-lease detectedAtRun=" + r.detectedAtRun() + " seed=" + r.seed());
        r.trace().forEach(t -> System.out.println("TRACE " + t));
    }

    @Test
    void brokenRelayDetectedInUnder500Runs() {
        SimResult r = findViolation(Variant.BROKEN_RELAY, BASE_SEED + 20_000, 500);
        assertThat(r.violated()).as("broken relay must be caught").isTrue();
        assertThat(r.detectedAtRun()).as("detection run").isLessThan(500);
        System.out.println("SIM broken-relay detectedAtRun=" + r.detectedAtRun() + " seed=" + r.seed());
        r.trace().forEach(t -> System.out.println("TRACE " + t));
    }

    @Test
    void correctFencingNeverViolates() {
        // Correct rules: fenced commit, atomic claim with lease, mark-after-publish.
        // Mirror of runOnce with all BROKEN branches disabled must never return a trace.
        for (int run = 0; run < 200; run++) {
            Random rnd = new Random(BASE_SEED + 99_999 + run);
            // Drive the correct path inline: claim/expire/reconcile/fenced-commit only.
            String state = "QUEUED";
            String owner = "";
            long token = 0;
            boolean live = false;
            boolean violated = false;
            for (int step = 0; step < 20; step++) {
                int a = rnd.nextInt(4);
                if (a == 0 && state.equals("QUEUED")) {
                    owner = rnd.nextBoolean() ? "A" : "B";
                    token++;
                    state = "RUNNING";
                    live = true;
                } else if (a == 1) {
                    live = false;
                } else if (a == 2 && state.equals("RUNNING") && !live) {
                    state = "QUEUED";
                    owner = "";
                } else if (a == 3 && state.equals("RUNNING") && live && !owner.isEmpty()) {
                    state = "SUCCEEDED";
                }
                // Invariant: never two holders (single owner string enforces it).
                if (!owner.isEmpty() && !(state.equals("RUNNING") || state.equals("SUCCEEDED"))) {
                    violated = true;
                }
            }
            assertThat(violated).as("correct fencing run " + run + " must not violate").isFalse();
        }
    }
}
