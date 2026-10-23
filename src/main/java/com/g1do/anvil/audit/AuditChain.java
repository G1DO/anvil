package com.g1do.anvil.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.submit.CanonicalCbor;

/**
 * Append-only per-job hash chain writer (issue #28).
 *
 * <p>Contract (narrowest reasonable):
 * <ul>
 * <li>Must be called inside the caller's DB transaction after
 * {@code TenantGuard.setTenant}: job-row lock ({@code SELECT ... FOR UPDATE}
 * on {@code jobs}, which the runtime role may lock) plus tail read
 * ({@code ORDER BY seq DESC LIMIT 1}) plus {@code INSERT} share the caller's
 * transaction, so the effect and its audit commit or roll back together (no
 * orphan in either direction). Never opens its own transaction. The tail is
 * deliberately read without {@code FOR UPDATE} on {@code audit_events} because
 * that clause requires {@code UPDATE} privilege, which the runtime role does
 * not hold by design (append-only grants + trigger); serialization comes from
 * the parent job lock instead.</li>
 * <li>Chain: {@code seq} 0-based, {@code prev_hash = 'GENESIS'} for seq 0 else
 * the previous {@code event_hash}. {@code UNIQUE(job_id, prev_hash)} rejects
 * forks; {@code UNIQUE(job_id, seq)} rejects duplicate positions.</li>
 * <li>Hash: {@code sha256_hex(type + 0x00 + job_id + 0x00 + seq + 0x00 +
 * prev_hash + 0x00 + canonical_cbor(payload))} where canonical CBOR uses the
 * existing mapper ({@code SORT_PROPERTIES_ALPHABETICALLY} +
 * {@code ORDER_MAP_ENTRIES_BY_KEYS}). Hashed: type, job_id, seq, prev_hash,
 * deterministic payload. The issue lists type/job/seq/payload; prev_hash is
 * included for chain binding (it is linkage, not in the excluded set).</li>
 * <li>Payload allowlist: only effect identity (doc/version/sha, state,
 * outcome, epoch/cap_id, artifact_sha/size). Rejected always when containing
 * {@code owner, fencing_token, lease_expires_at, attempt, attempt_number,
 * created_at, updated_at, sent_at, tenant_id, tenant, app.tenant}.</li>
 * <li>Types: {@code submit, claim, commit, cap.issue, cap.revoke, cas.put}.
 * Heartbeat, reconciler reclaim ({@code RUNNING -> QUEUED}) and
 * {@code outbox.sent_at} are never appended (callers simply do not call).</li>
 * </ul>
 */
public final class AuditChain {

    /** Genesis predecessor for seq 0. */
    public static final String GENESIS = "GENESIS";

    /** Checkpoint interval: one job_checkpoints row every 100 events (issue #30). */
    public static final int CHECKPOINT_INTERVAL = 100;

    public static final String TYPE_SUBMIT = "submit";
    public static final String TYPE_CLAIM = "claim";
    public static final String TYPE_COMMIT = "commit";
    public static final String TYPE_CAP_ISSUE = "cap.issue";
    public static final String TYPE_CAP_REVOKE = "cap.revoke";
    public static final String TYPE_CAS_PUT = "cas.put";

    private static final Set<String> ALLOWED_TYPES = Set.of(
            TYPE_SUBMIT, TYPE_CLAIM, TYPE_COMMIT, TYPE_CAP_ISSUE, TYPE_CAP_REVOKE, TYPE_CAS_PUT);

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "owner", "fencing_token", "lease_expires_at", "attempt", "attempt_number",
            "created_at", "updated_at", "sent_at", "tenant_id", "tenant", "app.tenant");

    private static final ObjectMapper JSON = new ObjectMapper();

    private AuditChain() {
    }

    /**
     * Appends one event in the caller's transaction.
     *
     * @return the new {@code event_hash}.
     */
    public static String append(
            JdbcTemplate jdbc, String tenantId, UUID jobId, String type, Map<String, Object> payload) {
        String tenant = requireTenant(tenantId);
        requireJobId(jobId);
        String cleanType = requireType(type);
        Map<String, Object> cleanPayload = requireAllowedPayload(payload);

        // Serialize per-job appends on the parent job row (runtime holds UPDATE
        // on jobs; it holds only SELECT+INSERT on audit_events by design).
        // For submit the row was just inserted in this txn, so the lock is free.
        jdbc.query(
                "SELECT id FROM jobs WHERE tenant_id = ? AND id = ? FOR UPDATE",
                (rs, i) -> rs.getObject("id"), tenant, jobId);

        List<Tail> tails = jdbc.query(
                "SELECT seq, event_hash FROM audit_events WHERE tenant_id = ? AND job_id = ? "
                        + "ORDER BY seq DESC LIMIT 1",
                (rs, i) -> new Tail(rs.getLong("seq"), rs.getString("event_hash")),
                tenant, jobId);
        long seq = tails.isEmpty() ? 0L : tails.get(0).seq() + 1L;
        String prevHash = tails.isEmpty() ? GENESIS : tails.get(0).eventHash();

        byte[] canonical = CanonicalCbor.encodeMap(cleanPayload);
        String eventHash = computeHash(cleanType, jobId, seq, prevHash, canonical);

        String payloadJson;
        try {
            payloadJson = JSON.writeValueAsString(cleanPayload);
        } catch (Exception e) {
            throw new IllegalStateException("audit payload JSON encoding failed", e);
        }
        jdbc.update(
                "INSERT INTO audit_events (tenant_id, job_id, seq, prev_hash, event_hash, type, payload) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)",
                tenant, jobId, seq, prevHash, eventHash, cleanType, payloadJson);
        // Checkpoint hook (issue #30): every 100 events per job, same txn.
        // sha is the chain event_hash at the boundary (seq % 100 == 0, seq > 0),
        // not output_sha. Immutable + RLS mirror audit_events (V5).
        if (seq > 0 && seq % CHECKPOINT_INTERVAL == 0) {
            jdbc.update(
                    "INSERT INTO job_checkpoints (tenant_id, job_id, seq, sha) VALUES (?, ?, ?, ?)",
                    tenant, jobId, seq, eventHash);
        }
        return eventHash;
    }

    /**
     * Deterministic event hash over type, job, seq, prev and canonical payload.
     */
    public static String computeHash(
            String type, UUID jobId, long seq, String prevHash, byte[] canonicalPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(type.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(jobId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(seq).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(prevHash.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            if (canonicalPayload != null) {
                digest.update(canonicalPayload);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("audit hash failed", e);
        }
    }

    private record Tail(long seq, String eventHash) {
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

    private static String requireType(String type) {
        if (type == null || !ALLOWED_TYPES.contains(type)) {
            throw new IllegalArgumentException("audit type must be one of " + ALLOWED_TYPES);
        }
        return type;
    }

    private static Map<String, Object> requireAllowedPayload(Map<String, Object> payload) {
        Map<String, Object> clean = payload == null ? Map.of() : payload;
        for (String key : clean.keySet()) {
            if (key == null) {
                throw new IllegalArgumentException("audit payload key must not be null");
            }
            if (FORBIDDEN_KEYS.contains(key)) {
                throw new IllegalArgumentException("audit payload excludes '" + key + "'");
            }
        }
        return clean;
    }
}
