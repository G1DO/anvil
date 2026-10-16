package com.g1do.anvil.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.g1do.anvil.tenant.TenantContext;

/**
 * Tenant-isolated audit event read API for issue #29.
 *
 * <p>Contract:
 * <ul>
 * <li>Route {@code GET /audit/events} (aliases {@code /api/audit/events},
 * {@code /api/v1/audit/events}) with {@code ?job_id=&limit=&cursor}. Tenant comes solely
 * from {@code X-Tenant-Id} via {@link TenantContext} (set by
 * {@code TenantAuthFilter}); any {@code tenant}/{@code tenant_id} query param
 * is accepted but ignored (forged tenant ignored, fail-closed).</li>
 * <li>Storage is {@code WHERE tenant_id = :t AND job_id = :j ORDER BY seq}
 * with RLS underneath plus a SQL pre-filter and a per-row Java re-check in
 * {@link AuditReadService}. No GIN/FTS, no replay computation, no filesystem
 * access.</li>
 * <li>Cross-tenant {@code job_id} returns the same empty {@code 200 []} as a
 * missing {@code job_id} (no oracle). A cursor belonging to another tenant
 * (or another job, or missing) returns 0 rows. Missing/unknown tenant is
 * {@code 401}; malformed {@code job_id}/cursor is {@code 400}.</li>
 * </ul>
 */
@RestController
public class AuditEventsController {

    private final AuditReadService reads;

    public AuditEventsController(AuditReadService reads) {
        this.reads = reads;
    }

    public record AuditEventDto(
            UUID id,
            String tenant_id,
            UUID job_id,
            long seq,
            String prev_hash,
            String event_hash,
            String type,
            JsonNode payload,
            OffsetDateTime created_at) {
    }

    @GetMapping({ "/audit/events", "/api/audit/events", "/api/v1/audit/events" })
    public ResponseEntity<List<AuditEventDto>> list(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestParam(value = "job_id", required = false) String jobIdParam,
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

        UUID cursorId;
        try {
            cursorId = AuditReadService.parseCursor(cursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
        }

        List<AuditReadService.AuditEventRow> rows = reads.listEvents(tenant, jobId, limit, cursorId);
        List<AuditEventDto> dtos = rows.stream()
                .map(r -> new AuditEventDto(
                        r.id(), r.tenantId(), r.jobId(), r.seq(),
                        r.prevHash(), r.eventHash(), r.type(), r.payload(), r.createdAt()))
                .toList();
        return ResponseEntity.ok(dtos);
    }
}
