package com.g1do.anvil.invoice;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.g1do.anvil.tenant.TenantContext;

/**
 * Invoice PDF upload (#170).
 *
 * <p>{@code POST /api/invoices} takes {@code multipart/form-data} with a
 * required {@code file} part and an optional {@code title}. It stores the bytes
 * in the content store and writes document/version metadata, then returns
 * {@code 202 {doc_id, version_id}}. It creates no job, writes no outbox event
 * and does not parse or extract the PDF.
 */
@RestController
public class InvoiceUploadController {

    private final InvoiceUploadService service;

    public InvoiceUploadController(InvoiceUploadService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/invoices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<InvoiceUploadResponse> upload(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "title", required = false) String title) {

        // Auth already ran in TenantAuthFilter; keep the header fallback so
        // direct controller/service flows stay consistent.
        String tenantId = TenantContext.getTenantId() != null ? TenantContext.getTenantId() : tenantHeader;
        if (tenantId == null || tenantId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "missing X-Tenant-Id");
        }

        InvoiceUploadService.UploadResult result = service.upload(tenantId.trim(), file, title);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new InvoiceUploadResponse(result.docId(), result.versionId()));
    }
}
