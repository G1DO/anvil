package com.g1do.anvil.replay;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.g1do.anvil.audit.AuditReadService;
import com.g1do.anvil.tenant.TenantContext;

/**
 * Deterministic replay endpoint (issue #30).
 *
 * <p>Contract:
 * <ul>
 * <li>Route {@code GET /audit/replay} (aliases {@code /api/audit/replay},
 * {@code /api/v1/audit/replay}) with {@code ?job_id=} (required) plus
 * {@code limit/cursor} aliases accepted-but-ignored like reads and
 * {@code tenant}/{@code tenant_id} accepted-but-ignored (tenant authority is
 * the header only).</li>
 * <li>Tenant solely from {@code X-Tenant-Id} via {@link TenantContext}
 * (set by {@code TenantAuthFilter}); missing/unknown tenant is {@code 401}.</li>
 * <li>Own-tenant terminal jobs return {@code 200} with
 * {@code {input_sha, output_sha, match}}; non-terminal own-tenant jobs return
 * {@code 200} with {@code match:false} (owner sign-off G1DO 2026-10-21).</li>
 * <li>Missing or cross-tenant {@code job_id} returns generic {@code 404}
 * (same message, no bytes, no oracle). Malformed {@code job_id}/cursor is
 * {@code 400}. Read-only, JDBC only, no filesystem access.</li>
 * </ul>
 */
@RestController
public class ReplayController {

    private final ReplayService replays;

    public ReplayController(ReplayService replays) {
        this.replays = replays;
    }

    public record ReplayDto(
            String input_sha,
            String output_sha,
            boolean match) {
    }

    @GetMapping({ "/audit/replay", "/api/audit/replay", "/api/v1/audit/replay" })
    public ResponseEntity<ReplayDto> replay(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestParam(value = "job_id", required = false) String jobIdParam,
            // Accepted but ignored: replay always covers the full chain.
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            // Accepted but deliberately ignored: tenant authority is the header only.
            @RequestParam(value = "tenant", required = false) String ignoredTenant,
            @RequestParam(value = "tenant_id", required = false) String ignoredTenantId) {

        String tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : tenantHeader;
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-Tenant-Id");
        }
        String tenant = tenantId.trim();

        if (jobIdParam == null || jobIdParam.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing job_id");
        }
        UUID jobId;
        try {
            jobId = UUID.fromString(jobIdParam.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid job_id");
        }

        // Cursor alias validated like reads (malformed -> 400), otherwise ignored.
        try {
            AuditReadService.parseCursor(cursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
        }

        ReplayService.ReplayResult result;
        try {
            result = replays.replay(tenant, jobId);
        } catch (ReplayService.ReplayNotFoundException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bad request");
        }
        return ResponseEntity.ok(new ReplayDto(result.inputSha(), result.outputSha(), result.match()));
    }
}
