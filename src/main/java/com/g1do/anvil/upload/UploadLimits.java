package com.g1do.anvil.upload;

/**
 * The upload limits chosen for #145. The byte limit applies to the whole
 * request body of a JSON document or an invoice PDF, and the page limit to an
 * invoice PDF. Both are contract values: changing one changes
 * {@code contracts/openapi.yaml}, the threat model and the proof test.
 */
public final class UploadLimits {

    /** Whole request body, before parsing. Also the invoice PDF byte limit. */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /** Invoice PDF pages. Enforced by the PDF upload route, not by JSON submit. */
    public static final int MAX_PAGES = 50;

    private UploadLimits() {
    }
}
