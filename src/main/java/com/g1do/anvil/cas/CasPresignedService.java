package com.g1do.anvil.cas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.g1do.anvil.jobs.CapGrant;
import com.g1do.anvil.jobs.JobService;
import com.g1do.anvil.tenant.TenantGuard;

/**
 * Tenant-scoped presigned CAS URLs with 60s TTL (issue #22 P01-P06).
 *
 * <p>Contract:
 * <ul>
 * <li>TTL is {@code 60s} ({@link #TTL_SECONDS}); expiry uses the database clock
 * ({@code clock_timestamp()}) only, never the worker clock.</li>
 * <li>No cross-tenant dedup: every URL is bound to {@code (tenant, sha, job,
 * cap, epoch, expiry)} and signed with HMAC-SHA256. The tenant in the URL must
 * equal the caller; swapping it (P02) or tampering sha/expiry/signature (P05)
 * denies with {@code 403}.</li>
 * <li>Issue requires an {@code artifacts} row for {@code (tenant, sha)}
 * ({@code WHERE tenant_id} plus RLS) and a valid job row plus a valid current
 * capability grant for {@code (tenant, job)}; otherwise {@code 403} (P01, P06
 * attacker-scoped URLs only).</li>
 * <li>Verify re-checks signature, expiry, artifact/job rows, and the presented
 * capability under {@code FOR UPDATE} via {@link JobService#checkPresentedGrant}:
 * revoke/expire/supersede denies with {@code 403 NEEDS_APPROVAL} and increments
 * {@code cap_rejected} (P03, sharing the commit-gate counter).</li>
 * <li>Token format is {@code base64url(payloadJson) + "." + sigHex} where the
 * payload is {@code {"t","sha","job","cap","epoch","exp"}} and the signature is
 * {@code HMAC-SHA256(secret, base64Part)}. The secret is configured via
 * {@code anvil.cas.presign-secret}; when blank an ephemeral random key is
 * generated at startup (restart invalidates outstanding URLs, fail closed).</li>
 * </ul>
 */
@Service
public class CasPresignedService {

    /** Presigned URL time-to-live in seconds. */
    public static final long TTL_SECONDS = 60L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JobService jobs;
    private final ContentStore store;
    private final byte[] secret;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public CasPresignedService(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            JobService jobs,
            ContentStore store,
            @Value("${anvil.cas.presign-secret:}") String secretConfig) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.jobs = jobs;
        this.store = store;
        if (secretConfig != null && !secretConfig.isBlank()) {
            this.secret = secretConfig.getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] ephemeral = new byte[32];
            new java.security.SecureRandom().nextBytes(ephemeral);
            this.secret = ephemeral;
        }
    }

    /** Test-only constructor with an explicit secret. */
    public CasPresignedService(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            JobService jobs,
            ContentStore store,
            byte[] secret) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.jobs = jobs;
        this.store = store;
        this.secret = secret.clone();
    }

    public record PresignedGrant(
            String tenantId, String sha256, UUID jobId, UUID capId, long epoch, long expiresAtEpoch) {
    }

    /**
     * Issues a presigned token for {@code (tenant, job, sha)} valid for
     * {@link #TTL_SECONDS} from the database clock.
     *
     * @throws ResponseStatusException {@code 403} when the artifact/job/grant is
     *         missing or invalid for this tenant (P01: victim sha as attacker).
     */
    public String issue(String tenantId, UUID jobId, String shaHex) {
        long expiry = dbNowEpoch() + TTL_SECONDS;
        return issueAtExpiry(tenantId, jobId, shaHex, expiry);
    }

    /**
     * Issues a presigned token with an explicit expiry (DB-clock epoch seconds).
     * Public for the P04 expired-URL proof: callers mint an already-expired token
     * with a valid signature, then verify it denies as expired.
     */
    public String issueAtExpiry(String tenantId, UUID jobId, String shaHex, long expiryEpoch) {
        String tenant = ContentStore.requireTenant(tenantId);
        requireJobId(jobId);
        String sha = ContentStore.requireSha(shaHex);

        // Artifact + job must exist for this tenant (WHERE tenant_id + RLS).
        Boolean ok = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, tenant);
            Integer artifacts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                    Integer.class, tenant, sha);
            Integer jobRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM jobs WHERE id = ? AND tenant_id = ?",
                    Integer.class, jobId, tenant);
            return artifacts != null && artifacts > 0 && jobRows != null && jobRows > 0;
        });
        if (!Boolean.TRUE.equals(ok)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "no content for tenant");
        }

        // Current grant must be valid; shares cap_rejected on denial.
        CapGrant grant = jobs.fetchValidCurrentGrant(tenant, jobId);

        return mint(tenant, sha, jobId, grant.capId(), grant.epoch(), expiryEpoch);
    }

    /**
     * Authorizes a presigned token for {@code callerTenant}: verifies signature,
     * expiry (DB clock), artifact/job rows, and the presented capability.
     *
     * @throws ResponseStatusException {@code 403} on tenant mismatch (P02),
     *         expiry (P04), tampering (P05), or missing rows.
     * @throws com.g1do.anvil.jobs.CapDeniedException {@code 403 NEEDS_APPROVAL}
     *         on revoked/expired/superseded/cross-tenant capability (P03),
     *         incrementing {@code cap_rejected}.
     */
    public PresignedGrant authorize(String callerTenant, String token) {
        String caller = safeTenant(callerTenant);
        if (caller == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }
        String[] parts = token.trim().split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }
        String base64 = parts[0];
        String sigHex = parts[1].toLowerCase(java.util.Locale.ROOT);

        byte[] payloadBytes;
        try {
            payloadBytes = Base64.getUrlDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }
        String expected = hmacHex(base64.getBytes(StandardCharsets.UTF_8));
        if (!constantTimeEquals(expected, sigHex)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }

        Map<?, ?> payload;
        try {
            payload = json.readValue(payloadBytes, Map.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }
        String tokenTenant;
        String sha;
        UUID jobId;
        UUID capId;
        long epoch;
        long exp;
        try {
            tokenTenant = ((String) payload.get("t")).trim();
            sha = ((String) payload.get("sha")).trim().toLowerCase(java.util.Locale.ROOT);
            jobId = UUID.fromString(((String) payload.get("job")).trim());
            capId = UUID.fromString(((String) payload.get("cap")).trim());
            epoch = ((Number) payload.get("epoch")).longValue();
            exp = ((Number) payload.get("exp")).longValue();
            ContentStore.requireTenant(tokenTenant);
            ContentStore.requireSha(sha);
            requireJobId(jobId);
            if (capId == null || epoch < 1) {
                throw new IllegalArgumentException("bad cap");
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }

        // P02: URL tenant must equal the caller; swapping denies without content.
        if (!caller.equals(tokenTenant)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }

        // P04: expiry uses the database clock only.
        long now = dbNowEpoch();
        if (exp <= now) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "expired");
        }

        // Artifact + job rows must still exist for the caller (WHERE + RLS).
        Boolean rowsOk = tx.execute(status -> {
            TenantGuard.setTenant(jdbc, caller);
            Integer artifacts = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM artifacts WHERE tenant_id = ? AND sha256 = ?",
                    Integer.class, caller, sha);
            Integer jobRows = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM jobs WHERE id = ? AND tenant_id = ?",
                    Integer.class, jobId, caller);
            return artifacts != null && artifacts > 0 && jobRows != null && jobRows > 0;
        });
        if (!Boolean.TRUE.equals(rowsOk)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "denied");
        }

        // P03: capability re-check under FOR UPDATE; revoke/expire/supersede denies
        // with CapDeniedException (403 NEEDS_APPROVAL, cap_rejected++).
        jobs.checkPresentedGrant(caller, jobId, capId, epoch);

        return new PresignedGrant(caller, sha, jobId, capId, epoch, exp);
    }

    /**
     * Authorizes {@code token} for {@code callerTenant} and returns the addressed
     * bytes via the tenant-matched CAS gateway (hash-verified, never torn).
     */
    public byte[] fetch(String callerTenant, String token) throws IOException {
        PresignedGrant grant = authorize(callerTenant, token);
        return store.getForCaller(grant.tenantId(), grant.tenantId(), grant.sha256());
    }

    /** Database-clock epoch seconds (DB clock only). */
    long dbNowEpoch() {
        OffsetDateTime now = jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
        if (now == null) {
            throw new IllegalStateException("database clock unavailable");
        }
        return now.toEpochSecond();
    }

    private String mint(String tenant, String sha, UUID jobId, UUID capId, long epoch, long expiry) {
        try {
            Map<String, Object> payload = Map.of(
                    "t", tenant,
                    "sha", sha,
                    "job", jobId.toString(),
                    "cap", capId.toString(),
                    "epoch", epoch,
                    "exp", expiry);
            byte[] payloadBytes = json.writeValueAsBytes(payload);
            String base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadBytes);
            String sig = hmacHex(base64.getBytes(StandardCharsets.UTF_8));
            return base64 + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException("presign mint failed", e);
        }
    }

    private String hmacHex(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(message);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeTenant(String tenantId) {
        try {
            return ContentStore.requireTenant(tenantId);
        } catch (Exception e) {
            return null;
        }
    }

    private static void requireJobId(UUID jobId) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId must not be null");
        }
    }
}
