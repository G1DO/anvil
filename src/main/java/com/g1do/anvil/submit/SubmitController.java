package com.g1do.anvil.submit;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.g1do.anvil.tenant.TenantContext;

import jakarta.validation.Valid;

/**
 * Single-transaction submit path.
 *
 * <p>Assumed contract (narrowest reasonable; issue #3 leaves path/shape
 * unspecified):
 * <ul>
 * <li>{@code POST /api/documents} with {@code X-Tenant-Id} and
 * {@code Idempotency-Key} headers and JSON
 * {@code {"title","mime_type","body"}}.</li>
 * <li>Valid request returns {@code 202} with
 * {@code {"doc_id","version_id","job_id"}} on both first and duplicate
 * delivery.</li>
 * <li>{@code body} is a JSON string treated as UTF-8 bytes for CBOR
 * {@code bstr}.</li>
 * </ul>
 */
@RestController
public class SubmitController {

    private final SubmitService service;

    public SubmitController(SubmitService service) {
        this.service = service;
    }

    @PostMapping({ "/api/documents", "/api/v1/documents", "/documents" })
    public ResponseEntity<SubmitResponse> submit(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody SubmitRequest request) {

        // Auth already ran in TenantAuthFilter; prefer TenantContext but fall back
        // to the header so direct MockMvc/service flows stay consistent.
        String tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : tenantHeader;
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-Tenant-Id");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missing Idempotency-Key");
        }

        SubmitService.SubmitResult result = service.submit(
                tenantId.trim(), idempotencyKey.trim(), request.getTitle(),
                request.getMime_type(), request.getBody());

        SubmitResponse response = new SubmitResponse(result.docId(), result.versionId(), result.jobId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
