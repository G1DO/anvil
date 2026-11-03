package com.g1do.anvil.replay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.g1do.anvil.replay.ReplayService.ChainEvent;
import com.g1do.anvil.replay.ReplayService.JobChain;
import com.g1do.anvil.replay.ReplayService.ReplayNotFoundException;
import com.g1do.anvil.replay.ReplayService.ReplayResult;

/**
 * Verifies an exported chain with no database (issue #75, record 0026).
 *
 * <p>It runs {@link ReplayService#evaluate}, the replay the API runs, on the
 * export, so an intact export of a job gives the API's {@code input_sha},
 * {@code output_sha} and {@code match}, and a tampered one fails at the same
 * sequence number. The header checks (the input root against the submit event,
 * the state against the commit event) are in {@code evaluate} too, so the
 * export and the database can't disagree about them.
 *
 * <p>Tenant scoping is the API's: the caller names a tenant and a job, an
 * export of another tenant's or another job's chain is "not found" exactly
 * like a missing one, and events of another tenant or job inside an export
 * are dropped before verification, as {@link ReplayService#scope} drops them
 * from the database.
 *
 * <p>What a verdict cannot show, because no hash in the export covers it
 * (record 0026, threat model): the tenant label, so a file relabelled to
 * another tenant verifies as that tenant; the checkpoints, so removing them
 * removes the cross-check; and the events after the terminal commit, which
 * can be cut without changing any hash the file checks. A chain rewritten
 * whole, every hash recomputed, also still verifies. Compare {@code head} with
 * a value kept elsewhere to catch that.
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
        // -1 means the chain is intact and the job is not terminal: no sequence number fails.
        Long firstBad = r.mismatchSeq() != null && r.mismatchSeq() >= 0 ? r.mismatchSeq() : null;
        String head = kept.get(kept.size() - 1).eventHash();
        return new Verdict(r.inputSha(), r.outputSha(), r.match(), firstBad, r.problem(), head);
    }
}
