package com.g1do.anvil.replay;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.audit.AuditChain;
import com.g1do.anvil.submit.CanonicalCbor;
import com.g1do.anvil.tenant.TenantGuard;

/**
 * Deterministic replay from the immutable per-job hash chain (issue #30).
 *
 * <p>Contract (narrowest reasonable):
 * <ul>
 * <li>Read-only: SELECT only, never INSERT/UPDATE/DELETE, so repeated replays
 * are identical and write no log/job rows (proven by row-count test).</li>
 * <li>Every call sets transaction-local {@code app.tenant} via
 * {@link TenantGuard#setTenant} with read-back probe; RLS {@code FORCE}
 * enforces {@code tenant_id = current_setting('app.tenant', true)} underneath,
 * and every statement keeps {@code WHERE tenant_id = ?} plus a per-row Java
 * re-check. Cross-tenant or missing {@code job_id} yields zero rows and the
 * service throws {@link ReplayNotFoundException} (controller maps to generic
 * {@code 404}, no bytes, no oracle).</li>
 * <li>{@code input_sha} is {@code document_versions.sha256} (canonical CBOR
 * over {@code {title, mime_type, body}} only, see {@code CanonicalCbor}).</li>
 * <li>{@code artifact_sha256} is the last {@code cas.put.artifact_sha} in the
 * chain, else {@code ""} (owner sign-off G1DO 2026-10-21).</li>
 * <li>{@code terminal_state} is current {@code jobs.state}; it must be
 * {@code SUCCEEDED/FAILED/DEAD}, else the replay returns {@code match:false}
 * (owner sign-off G1DO 2026-10-21).</li>
 * <li>{@code output_sha = sha256(canonical CBOR of
 * {job_id:str, terminal_state:str, artifact_sha256:str})} via the existing
 * mapper ({@code CanonicalCbor.encodeMap}), owner sign-off G1DO 2026-10-21.</li>
 * <li>Chain verification recomputes each {@code event_hash} via
 * {@code AuditChain.computeHash} over canonical payload bytes; any corrupt
 * byte, dropped kept event (seq gap), or swapped {@code prev_hash} yields
 * {@code match:false} with the offending {@code seq} (never silent success).
 * Heartbeat/reconciler-poll/outbox.sent_at are never logged, so dropping them
 * still recomputes the same output.</li>
 * <li>Checkpoints ({@code job_checkpoints}, every 100 per job, sha =
 * chain {@code event_hash} at {@code seq % 100 == 0}) are read for the
 * accelerated path: latest checkpoint {@code <= max_seq} is consistency-checked
 * ({@code sha == event_hash} at that seq) and its seq is returned as
 * {@code checkpointSeqUsed} (proof the path was taken). Full verification is
 * still performed (same-sha guarantee); prefix-skip is residual future work.
 * {@code audit_events} stays append-only with no deletes in v1.</li>
 * <li>Two steps (issue #75, record 0026): {@link #load} reads the
 * {@link JobChain} in the caller's tenant, and {@link #evaluate}, a pure
 * function, computes the result from it. The API, the CLI and the offline
 * verifier all call {@code evaluate}, so they agree on the same chain. The
 * reported {@code mismatchSeq} is the earliest failure, a chain break or a
 * disagreeing checkpoint.</li>
 * </ul>
 */
@Service
public class ReplayService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ReplayService(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
    }

    public record ReplayResult(
            String inputSha,
            String outputSha,
            boolean match,
            Long mismatchSeq,
            Long checkpointSeqUsed) {
    }

    /** Thrown when the job/chain is missing or not owned (maps to 404, no oracle). */
    public static class ReplayNotFoundException extends RuntimeException {
        public ReplayNotFoundException(String message) {
            super(message);
        }
    }

    /** One audit event as replay reads it: the stored row with its payload parsed from JSON. */
    public record ChainEvent(
            long seq,
            String prevHash,
            String eventHash,
            String type,
            String tenantId,
            UUID jobId,
            Map<String, Object> payload) {
    }

    /** One {@code job_checkpoints} row. */
    public record Checkpoint(long seq, String sha) {
    }

    /**
     * Everything a replay reads, and nothing else (issue #75): the job's
     * current state, its input root, its chain in {@code seq} order and its
     * checkpoints. {@link #load} reads it from the database in the caller's
     * tenant; {@code ChainExport} writes it to a file and reads it back, so the
     * offline verifier evaluates exactly what the API would.
     */
    public record JobChain(
            String tenantId,
            UUID jobId,
            String state,
            String inputSha,
            List<ChainEvent> events,
            List<Checkpoint> checkpoints) {

        public JobChain {
            events = List.copyOf(events);
            checkpoints = List.copyOf(checkpoints);
        }
    }

    /**
     * Checkpoint-accelerated replay (reads checkpoints, verifies full chain).
     */
    public ReplayResult replay(String tenantId, UUID jobId) {
        return evaluate(load(tenantId, jobId), true);
    }

    /**
     * Full replay without consulting checkpoints (test comparison path).
     */
    public ReplayResult replayFull(String tenantId, UUID jobId) {
        return evaluate(load(tenantId, jobId), false);
    }

    /**
     * Reads one job's chain in one transaction under the caller's tenant
     * context ({@code WHERE tenant_id = ?} plus RLS plus the per-row re-check
     * in {@link #scope}). Read-only.
     *
     * @throws ReplayNotFoundException when the job, its version or its chain is
     *         missing or belongs to another tenant (no oracle).
     */
    public JobChain load(String tenantId, UUID jobId) {
        String tenant = requireTenant(tenantId);
        requireJobId(jobId);
        return tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);

            // Job row: state + version link (tenant-scoped, RLS underneath).
            List<JobRef> jobRefs = jdbc.query(
                    "SELECT document_id, version_id, state FROM jobs WHERE tenant_id = ? AND id = ?",
                    (rs, i) -> new JobRef(
                            (UUID) rs.getObject("document_id"),
                            (UUID) rs.getObject("version_id"),
                            rs.getString("state")),
                    tenant, jobId);
            if (jobRefs.isEmpty()) {
                throw new ReplayNotFoundException("not found");
            }
            JobRef job = jobRefs.get(0);

            // Input root: document_versions.sha256 (canonical CBOR hash).
            List<String> versionShas = jdbc.query(
                    "SELECT sha256 FROM document_versions WHERE tenant_id = ? AND id = ?",
                    (rs, i) -> rs.getString("sha256"),
                    tenant, job.versionId());
            if (versionShas.isEmpty()) {
                throw new ReplayNotFoundException("not found");
            }

            // Chain ORDER BY seq, tenant- and job-scoped, then re-checked per row.
            List<ChainEvent> kept = scope(tenant, jobId, jdbc.query(
                    "SELECT tenant_id, job_id, seq, prev_hash, event_hash, type, payload "
                            + "FROM audit_events WHERE tenant_id = ? AND job_id = ? ORDER BY seq",
                    (rs, i) -> mapChainRow(rs),
                    tenant, jobId));

            List<Checkpoint> cps = jdbc.query(
                    "SELECT seq, sha FROM job_checkpoints WHERE tenant_id = ? AND job_id = ? ORDER BY seq",
                    (rs, i) -> new Checkpoint(rs.getLong("seq"), rs.getString("sha")),
                    tenant, jobId);
            return new JobChain(tenant, jobId, job.state(), versionShas.get(0), kept, cps);
        });
    }

    /**
     * The per-row re-check (defense in depth, mirrors {@code AuditReadService}):
     * keeps only the caller's tenant's events of this job.
     *
     * @throws ReplayNotFoundException when none are left (no oracle).
     */
    public static List<ChainEvent> scope(String tenant, UUID jobId, List<ChainEvent> chain) {
        List<ChainEvent> kept = new ArrayList<>(chain.size());
        for (ChainEvent r : chain) {
            if (r != null && tenant.equals(r.tenantId()) && jobId.equals(r.jobId())) {
                kept.add(r);
            }
        }
        if (kept.isEmpty()) {
            throw new ReplayNotFoundException("not found");
        }
        return kept;
    }

    /**
     * The replay itself, a pure function of what {@link #load} read, so the
     * API, the CLI and the offline verifier give the same answer for the same
     * chain. The chain must already be {@link #scope scoped} to its tenant and job.
     *
     * <p>{@code mismatchSeq} is the first sequence number that fails: the
     * earliest chain break, or the checkpoint that disagrees with the chain if
     * it comes first. With an intact chain and a non-terminal job it is
     * {@code -1}: no chain break, state not terminal.
     */
    public static ReplayResult evaluate(JobChain chain, boolean useCheckpoint) {
        UUID jobId = chain.jobId();
        List<ChainEvent> kept = chain.events();
        String outputSha = outputSha(jobId, chain.state(), lastArtifactSha(kept));

        // Full hash-linkage verification.
        Long mismatch = verifyChain(jobId, kept);

        // Checkpoint path: latest checkpoint <= max_seq, consistency-checked.
        Long checkpointUsed = null;
        if (useCheckpoint) {
            long maxSeq = kept.get(kept.size() - 1).seq();
            Checkpoint best = null;
            for (Checkpoint cp : chain.checkpoints()) {
                if (cp.seq() <= maxSeq && (best == null || cp.seq() > best.seq())) {
                    best = cp;
                }
            }
            if (best != null) {
                checkpointUsed = best.seq();
                // Consistency: checkpoint sha must equal stored event_hash at that seq.
                String storedAtCheckpoint = null;
                for (ChainEvent r : kept) {
                    if (r.seq() == best.seq()) {
                        storedAtCheckpoint = r.eventHash();
                        break;
                    }
                }
                if (storedAtCheckpoint == null || !storedAtCheckpoint.equals(best.sha())) {
                    // Checkpoint/chain divergence: tamper, never silent success.
                    long at = mismatch == null ? best.seq() : Math.min(mismatch, best.seq());
                    return new ReplayResult(chain.inputSha(), outputSha, false, at, checkpointUsed);
                }
            }
        }

        boolean terminalOk = isTerminal(chain.state());
        boolean match = mismatch == null && terminalOk;
        Long mismatchSeq = mismatch != null ? mismatch : (terminalOk ? null : -1L);
        return new ReplayResult(chain.inputSha(), outputSha, match, mismatchSeq, checkpointUsed);
    }

    /**
     * Verifies seq contiguity, prev linkage, and event_hash recomputation.
     *
     * @return offending seq on first break, else null.
     */
    static Long verifyChain(UUID jobId, List<ChainEvent> chain) {
        String prev = AuditChain.GENESIS;
        long expectedSeq = -1;
        boolean first = true;
        for (ChainEvent row : chain) {
            if (first) {
                expectedSeq = row.seq();
                // First row must start at 0 for an intact chain; a gap at the
                // start (e.g. dropped genesis) is a mismatch at that seq.
                if (row.seq() != 0) {
                    return row.seq();
                }
                first = false;
            } else {
                expectedSeq = expectedSeq + 1;
                if (row.seq() != expectedSeq) {
                    return row.seq();
                }
            }
            if (!prev.equals(row.prevHash())) {
                return row.seq();
            }
            byte[] canonical = CanonicalCbor.encodeMap(row.payload());
            String recomputed = AuditChain.computeHash(
                    row.type(), jobId, row.seq(), row.prevHash(), canonical);
            if (!recomputed.equals(row.eventHash())) {
                return row.seq();
            }
            prev = row.eventHash();
        }
        return null;
    }

    private static String lastArtifactSha(List<ChainEvent> chain) {
        String last = "";
        for (ChainEvent row : chain) {
            if (AuditChain.TYPE_CAS_PUT.equals(row.type()) && row.payload() != null) {
                Object v = row.payload().get("artifact_sha");
                if (v instanceof String s && s.matches("^[0-9a-f]{64}$")) {
                    last = s;
                }
            }
        }
        return last;
    }

    public static String outputSha(UUID jobId, String terminalState, String artifactSha256) {
        Map<String, String> m = Map.of(
                "artifact_sha256", artifactSha256 == null ? "" : artifactSha256,
                "job_id", jobId.toString(),
                "terminal_state", terminalState == null ? "" : terminalState);
        byte[] cbor = CanonicalCbor.encodeMap(m);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(cbor);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("output sha failed", e);
        }
    }

    /** Whether a job state is final: {@code SUCCEEDED}, {@code FAILED} or {@code DEAD}. */
    public static boolean isTerminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "DEAD".equals(state);
    }

    private record JobRef(UUID documentId, UUID versionId, String state) {
    }

    private static ChainEvent mapChainRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChainEvent(rs.getLong("seq"), rs.getString("prev_hash"), rs.getString("event_hash"),
                rs.getString("type"), rs.getString("tenant_id"), (UUID) rs.getObject("job_id"),
                parsePayload(rs.getString("payload")));
    }

    /**
     * Parses a stored payload's JSON the one way replay does, so the canonical
     * CBOR the hash covers is the same from the database and from an export.
     */
    public static Map<String, Object> parsePayload(String payloadJson) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(payloadJson == null ? "{}" : payloadJson, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new IllegalStateException("audit payload decode failed", e);
        }
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        return tenantId.trim();
    }

    private static void requireJobId(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId must not be null");
        }
    }
}
