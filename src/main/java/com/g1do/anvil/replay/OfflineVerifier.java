package com.g1do.anvil.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.g1do.anvil.audit.AuditChain;
import com.g1do.anvil.replay.ReplayService.ChainEvent;
import com.g1do.anvil.replay.ReplayService.JobChain;
import com.g1do.anvil.replay.ReplayService.ReplayNotFoundException;
import com.g1do.anvil.replay.ReplayService.ReplayResult;

/**
 * Verifies an exported chain with no database (issue #75, record 0026).
 *
 * <p>It runs {@link ReplayService#evaluate}, the replay the API runs, on the
 * export, so an intact export of a job gives the API's {@code input_sha},
 * {@code output_sha} and {@code match}. Two further checks cover what the
 * hashes don't: the export's {@code input_sha} must be the {@code sha256} its
 * submit event recorded, and its {@code state} must be the outcome of its last
 * commit event (every terminal transition appends one), so editing either
 * can't turn a failed job into a match.
 *
 * <p>Tenant scoping is the API's: the caller names a tenant and a job, an
 * export of another tenant's or another job's chain is "not found" exactly
 * like a missing one, and events of another tenant or job inside an export
 * are dropped before verification, as {@link ReplayService#scope} drops them
 * from the database. The export holds no secret that proves who wrote it, so
 * a chain rewritten whole, every hash recomputed, still verifies: compare
 * {@code head} with a value kept elsewhere to catch that.
 */
public final class OfflineVerifier {

    private OfflineVerifier() {
    }

    /**
     * The verdict. {@code firstBadSeq} is the first sequence number that fails,
     * or {@code null}; {@code problem} says why {@code match} is false, or is
     * {@code null}; {@code head} is the last event's hash.
     */
    public record Verdict(
            String inputSha,
            String outputSha,
            boolean match,
            Long firstBadSeq,
            String problem,
            String head) {
    }

    /**
     * @throws ReplayNotFoundException when the export is another tenant's or
     *         another job's, or holds none of this job's events.
     */
    public static Verdict verify(JobChain export, String tenantId, UUID jobId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (jobId == null) {
            throw new IllegalArgumentException("jobId must not be null");
        }
        String tenant = tenantId.trim();
        if (!tenant.equals(export.tenantId()) || !jobId.equals(export.jobId())) {
            throw new ReplayNotFoundException("not found");
        }
        // ORDER BY seq, as replay reads them, then the same per-row re-check.
        List<ChainEvent> ordered = new ArrayList<>(export.events());
        ordered.sort(Comparator.comparingLong(ChainEvent::seq));
        List<ChainEvent> kept = ReplayService.scope(tenant, jobId, ordered);
        JobChain chain = new JobChain(tenant, jobId, export.state(), export.inputSha(), kept, export.checkpoints());

        ReplayResult r = ReplayService.evaluate(chain, true);
        String head = kept.get(kept.size() - 1).eventHash();
        if (r.mismatchSeq() != null && r.mismatchSeq() >= 0) {
            Long chainBreak = ReplayService.verifyChain(jobId, kept);
            String problem = r.mismatchSeq().equals(chainBreak)
                    ? "the chain breaks at seq " + r.mismatchSeq()
                    : "the checkpoint at seq " + r.mismatchSeq() + " does not match the chain";
            return new Verdict(r.inputSha(), r.outputSha(), false, r.mismatchSeq(), problem, head);
        }

        String bound = bindHeader(chain);
        if (bound != null) {
            return new Verdict(r.inputSha(), r.outputSha(), false, null, bound, head);
        }
        if (!r.match()) {
            return new Verdict(r.inputSha(), r.outputSha(), false, null,
                    "the job is not terminal: " + export.state(), head);
        }
        return new Verdict(r.inputSha(), r.outputSha(), true, null, null, head);
    }

    /** The export's unhashed header against what its intact chain recorded, or {@code null} when they agree. */
    private static String bindHeader(JobChain chain) {
        ChainEvent submit = null;
        ChainEvent lastCommit = null;
        for (ChainEvent e : chain.events()) {
            if (submit == null && AuditChain.TYPE_SUBMIT.equals(e.type())) {
                submit = e;
            }
            if (AuditChain.TYPE_COMMIT.equals(e.type())) {
                lastCommit = e;
            }
        }
        if (submit == null) {
            return "no submit event records the input_sha";
        }
        if (!chain.inputSha().equals(submit.payload().get("sha256"))) {
            return "input_sha does not match the submit event at seq " + submit.seq();
        }
        boolean terminal = ReplayService.isTerminal(chain.state());
        if (lastCommit == null) {
            return terminal ? "state " + chain.state() + " has no commit event" : null;
        }
        if (!chain.state().equals(lastCommit.payload().get("outcome"))) {
            return "state " + chain.state() + " does not match the commit event at seq " + lastCommit.seq();
        }
        return null;
    }
}
