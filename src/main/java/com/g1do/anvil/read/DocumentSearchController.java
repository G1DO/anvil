package com.g1do.anvil.read;

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

import com.g1do.anvil.tenant.TenantContext;

/**
 * Minimal document search for issue #22.
 *
 * <p>Contract:
 * <ul>
 * <li>Route {@code GET /api/documents} (aliases {@code /api/v1/documents},
 * {@code /documents}) with {@code ?query=&limit=&cursor}. Tenant comes solely
 * from {@code X-Tenant-Id} via {@link TenantContext} (set by
 * {@code TenantAuthFilter}); any {@code tenant}/{@code tenant_id} query param
 * is accepted but ignored (S06 forged tenant ignored, S05 omitted tenant
 * defaults to the header, fail-closed).</li>
 * <li>Storage is {@code WHERE tenant_id = :t AND title ILIKE :prefix%
 * ORDER BY created_at, id} with RLS underneath plus a SQL pre-filter and a
 * per-row Java re-check in {@link ReadService}. No GIN/FTS, no jobs/artifacts
 * full-text, no public shares.</li>
 * <li>Cursor is the last-seen document UUID; a cursor belonging to another
 * tenant returns 0 rows (S07).</li>
 * </ul>
 */
@RestController
public class DocumentSearchController {

    private final ReadService reads;

    public DocumentSearchController(ReadService reads) {
        this.reads = reads;
    }

    public record DocDto(
            UUID id, String tenant_id, String title, String mime_type, OffsetDateTime created_at) {
    }

    @GetMapping({ "/api/documents", "/api/v1/documents", "/documents" })
    public ResponseEntity<List<DocDto>> search(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestParam(value = "query", required = false) String query,
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

        UUID cursorId;
        try {
            cursorId = ReadService.parseCursor(cursor);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cursor");
        }

        List<ReadService.DocumentRow> rows = reads.searchDocuments(tenant, query, limit, cursorId);
        List<DocDto> dtos = rows.stream()
                .map(r -> new DocDto(r.id(), r.tenantId(), r.title(), r.mimeType(), r.createdAt()))
                .toList();
        return ResponseEntity.ok(dtos);
    }
}
