package com.g1do.anvil.submit;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 202 response shape, identical on first and duplicate delivery.
 */
public record SubmitResponse(
        @JsonProperty("doc_id") UUID docId,
        @JsonProperty("version_id") UUID versionId,
        @JsonProperty("job_id") UUID jobId) {
}
