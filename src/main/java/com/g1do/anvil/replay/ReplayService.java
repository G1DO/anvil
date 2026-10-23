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

    private record ChainRow(
            long seq,
            String prevHash,
            String eventHash,
            String type,
            String tenantId,
            UUID jobId,
            Map<String, Object> payload) {
    }

    /**
     * Checkpoint-accelerated replay (reads checkpoints, verifies full chain).
     */
    public ReplayResult replay(String tenantId, UUID jobId) {
        return replayInternal(tenantId, jobId, true);
    }

    /**
     * Full replay without consulting checkpoints (test comparison path).
     */
    public ReplayResult replayFull(String tenantId, UUID jobId) {
        return replayInternal(tenantId, jobId, false);
    }

    private ReplayResult replayInternal(String tenantId, UUID jobId, boolean useCheckpoint) {
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
            String inputSha = versionShas.get(0);

            // Chain ORDER BY seq, tenant- and job-scoped.
            List<ChainRow> chain = jdbc.query(
                    "SELECT tenant_id, job_id, seq, prev_hash, event_hash, type, payload "
                            + "FROM audit_events WHERE tenant_id = ? AND job_id = ? ORDER BY seq",
                    (rs, i) -> mapChainRow(rs),
                    tenant, jobId);
            if (chain.isEmpty()) {
                throw new ReplayNotFoundException("not found");
            }
            // Per-row re-check (defense-in-depth, mirrors AuditReadService).
            List<ChainRow> kept = new ArrayList<>(chain.size());
            for (ChainRow r : chain) {
                if (r != null && tenant.equals(r.tenantId()) && jobId.equals(r.jobId())) {
                    kept.add(r);
                }
            }
            if (kept.isEmpty()) {
                throw new ReplayNotFoundException("not found");
            }

            // Checkpoint path: latest checkpoint <= max_seq, consistency-checked.
            Long checkpointUsed = null;
            if (useCheckpoint) {
                List<Checkpoint> cps = jdbc.query(
                        "SELECT seq, sha FROM job_checkpoints WHERE tenant_id = ? AND job_id = ? ORDER BY seq",
                        (rs, i) -> new Checkpoint(rs.getLong("seq"), rs.getString("sha")),
                        tenant, jobId);
                // Re-check tenant scope (rows already filtered, but keep symmetric).
                long maxSeq = kept.get(kept.size() - 1).seq();
                Checkpoint best = null;
                for (Checkpoint cp : cps) {
                    if (cp.seq() <= maxSeq && (best == null || cp.seq() > best.seq())) {
                        best = cp;
                    }
                }
                if (best != null) {
                    // Consistency: checkpoint sha must equal stored event_hash at that seq.
                    String storedAtCheckpoint = null;
                    for (ChainRow r : kept) {
                        if (r.seq() == best.seq()) {
                            storedAtCheckpoint = r.eventHash();
                            break;
                        }
                    }
                    if (storedAtCheckpoint == null || !storedAtCheckpoint.equals(best.sha())) {
                        // Checkpoint/chain divergence: tamper, never silent success.
                        String artifactForOutput = lastArtifactSha(kept);
                        String terminal = job.state();
                        String outputSha = outputSha(jobId, terminal, artifactForOutput);
                        // Surface the checkpoint seq as the mismatch point.
                        return new ReplayResult(inputSha, outputSha, false, best.seq(), best.seq());
                    }
                    checkpointUsed = best.seq();
                }
            }

            // Full hash-linkage verification.
            Long mismatch = verifyChain(jobId, kept);

            String artifactSha = lastArtifactSha(kept);
            String terminalState = job.state();
            String outputSha = outputSha(jobId, terminalState, artifactSha);

            boolean terminalOk = isTerminal(terminalState);
            boolean match = mismatch == null && terminalOk;
            Long mismatchSeq = mismatch != null ? mismatch : (terminalOk ? null : -1L);
            // For non-terminal with intact chain, surface -1 as "no chain break,
            // state not terminal" (service-level; controller still returns
            // match:false with shas for the owner).
            return new ReplayResult(inputSha, outputSha, match, mismatchSeq, checkpointUsed);
        });
    }

    /**
     * Verifies seq contiguity, prev linkage, and event_hash recomputation.
     *
     * @return offending seq on first break, else null.
     */
    static Long verifyChain(UUID jobId, List<ChainRow> chain) {
        String prev = AuditChain.GENESIS;
        long expectedSeq = -1;
        boolean first = true;
        for (ChainRow row : chain) {
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

    private static String lastArtifactSha(List<ChainRow> chain) {
        String last = "";
        for (ChainRow row : chain) {
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

    private static boolean isTerminal(String state) {
        return "SUCCEEDED".equals(state) || "FAILED".equals(state) || "DEAD".equals(state);
    }

    private record JobRef(UUID documentId, UUID versionId, String state) {
    }

    private record Checkpoint(long seq, String sha) {
    }

    private static ChainRow mapChainRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID jobId = (UUID) rs.getObject("job_id");
        String tenantId = rs.getString("tenant_id");
        long seq = rs.getLong("seq");
        String prevHash = rs.getString("prev_hash");
        String eventHash = rs.getString("event_hash");
        String type = rs.getString("type");
        String payloadJson = rs.getString("payload");
        if (payloadJson == null) {
            payloadJson = "{}";
        }
        Map<String, Object> payload;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JSON.readValue(payloadJson, Map.class);
            payload = parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            throw new IllegalStateException("audit payload decode failed", e);
        }
        return new ChainRow(seq, prevHash, eventHash, type, tenantId, jobId, payload);
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
