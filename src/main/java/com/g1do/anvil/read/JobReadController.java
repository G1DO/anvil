package com.g1do.anvil.read;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.g1do.anvil.tenant.TenantContext;

/**
 * Tenant-scoped job-state read for the operator console (issue #77).
 *
 * <p>Contract:
 * <ul>
 * <li>Route {@code GET /api/jobs/{id}} returns {@code {id, tenant_id, state}}
 * for the caller's job.</li>
 * <li>Tenant comes solely from {@code X-Tenant-Id} via
 * {@link TenantContext} (set by {@code TenantAuthFilter}); missing or unknown
 * tenant is {@code 401}.</li>
 * <li>A malformed, missing or another tenant's job is the same generic
 * {@code 404}, exactly as the replay route treats it: no bytes and no
 * existence oracle.</li>
 * <li>Reads through {@link ReadService#getJob}, which sets transaction-local
 * {@code app.tenant} with a read-back probe, runs under RLS {@code FORCE}, and
 * keeps {@code WHERE tenant_id = ?} plus the per-row re-check.</li>
 * </ul>
 */
@RestController
public class JobReadController {

    private final ReadService reads;

    public JobReadController(ReadService reads) {
        this.reads = reads;
    }

    public record JobDto(UUID id, String tenant_id, String state) {
    }

    public record ErrorDto(String error) {
    }

    @GetMapping("/api/jobs/{id}")
    public ResponseEntity<?> get(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @PathVariable("id") String id) {

        String tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : tenantHeader;
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-Tenant-Id");
        }
        String tenant = tenantId.trim();

        UUID jobId;
        try {
            jobId = UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            // Malformed looks exactly like missing: no oracle for a guessed ID.
            return notFound();
        }

        Optional<ReadService.JobRow> row = reads.getJob(tenant, jobId);
        if (row.isEmpty()) {
            return notFound();
        }
        return ResponseEntity.ok(new JobDto(row.get().id(), row.get().tenantId(), row.get().state()));
    }

    private static ResponseEntity<ErrorDto> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorDto("not found"));
    }
}
