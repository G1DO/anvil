package com.g1do.anvil.extract;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.g1do.anvil.tenant.TenantContext;

/**
 * Extraction routes (#37, record 0022).
 *
 * <ul>
 * <li>{@code POST /api/documents/{id}/extract} extracts the document's latest
 * version once and reports the stored result.</li>
 * <li>{@code GET /api/documents/{id}/facts} serves only facts whose citation
 * passed the exact substring check. Dead letters are never served.</li>
 * </ul>
 *
 * <p>The tenant comes only from {@code X-Tenant-Id}, checked by
 * {@code TenantAuthFilter}. A forged, malformed, missing or other tenant's ID
 * gets the same {@code 404} with the same body, before any model call, so the
 * answer doesn't reveal whether the document exists.
 */
@RestController
public class ExtractionController {

    static final Map<String, String> NOT_FOUND = Map.of("error", "not_found");
    static final Map<String, String> UNAVAILABLE = Map.of("error", "model_unavailable");

    private final ExtractionService service;

    public ExtractionController(ExtractionService service) {
        this.service = service;
    }

    public record ExtractionDto(UUID extraction_id, UUID document_id, UUID version_id, String status, int facts,
            int quarantined, boolean truncated) {
    }

    public record FactDto(String field, String value, String citation) {
    }

    public record FactsDto(UUID document_id, UUID version_id, String status, List<FactDto> facts) {
    }

    @PostMapping("/api/documents/{id}/extract")
    public ResponseEntity<?> extract(@PathVariable("id") String id) {
        Optional<UUID> docId = parse(id);
        if (docId.isEmpty()) {
            return notFound();
        }
        Optional<ExtractionService.Result> r;
        try {
            r = service.extract(tenant(), docId.get());
        } catch (ModelUnavailableException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(UNAVAILABLE);
        }
        return r.<ResponseEntity<?>>map(x -> ResponseEntity.ok(new ExtractionDto(x.extractionId(), x.documentId(),
                x.versionId(), x.status(), x.facts(), x.quarantined(), x.truncated()))).orElseGet(ExtractionController::notFound);
    }

    @GetMapping("/api/documents/{id}/facts")
    public ResponseEntity<?> facts(@PathVariable("id") String id) {
        Optional<UUID> docId = parse(id);
        if (docId.isEmpty()) {
            return notFound();
        }
        return service.facts(tenant(), docId.get()).<ResponseEntity<?>>map(f -> ResponseEntity.ok(new FactsDto(
                f.documentId(), f.versionId(), f.status(),
                f.facts().stream().map(x -> new FactDto(x.field(), x.value(), x.citation())).toList())))
                .orElseGet(ExtractionController::notFound);
    }

    private static String tenant() {
        String t = TenantContext.getTenantId();
        if (t == null || t.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-Tenant-Id");
        }
        return t.trim();
    }

    private static Optional<UUID> parse(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static ResponseEntity<?> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(NOT_FOUND);
    }
}
