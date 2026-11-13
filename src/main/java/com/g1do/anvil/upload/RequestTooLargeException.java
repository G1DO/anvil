package com.g1do.anvil.upload;

/** The request body crossed {@link UploadLimits#MAX_BYTES} while it was read. */
public class RequestTooLargeException extends RuntimeException {

    public RequestTooLargeException() {
        super("request body exceeds " + UploadLimits.MAX_BYTES + " bytes");
    }
}
