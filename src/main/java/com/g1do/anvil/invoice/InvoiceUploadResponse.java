package com.g1do.anvil.invoice;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 202 response for an accepted invoice PDF. No job is created. */
public record InvoiceUploadResponse(
        @JsonProperty("doc_id") UUID docId,
        @JsonProperty("version_id") UUID versionId) {
}
