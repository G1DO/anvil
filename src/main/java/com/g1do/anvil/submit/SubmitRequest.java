package com.g1do.anvil.submit;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Minimal submittable document for the execution slice.
 * Full product validation is a later-Outcome concern.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubmitRequest {

    @NotBlank(message = "title must not be blank")
    @Size(max = 4096, message = "title too long")
    private String title;

    @NotBlank(message = "mime_type must not be blank")
    @Size(max = 255, message = "mime_type too long")
    @JsonAlias("mimeType")
    private String mime_type;

    /**
     * Document bytes as a JSON string (UTF-8). Encoded as CBOR {@code bstr}.
     * Present (possibly empty) is valid; missing/null is invalid.
     */
    @NotNull(message = "body must be present")
    private String body;

    public SubmitRequest() {
    }

    public SubmitRequest(String title, String mimeType, String body) {
        this.title = title;
        this.mime_type = mimeType;
        this.body = body;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMime_type() {
        return mime_type;
    }

    public void setMime_type(String mime_type) {
        this.mime_type = mime_type;
    }

    // Camel-case accessor for convenience; JSON still uses mime_type.
    public String getMimeType() {
        return mime_type;
    }

    public void setMimeType(String mimeType) {
        this.mime_type = mimeType;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
