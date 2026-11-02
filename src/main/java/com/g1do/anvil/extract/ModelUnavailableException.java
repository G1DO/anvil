package com.g1do.anvil.extract;

/**
 * The model couldn't be asked or didn't answer usably, for a reason that
 * isn't the document's: no credential, a configuration fault (bad key, no
 * balance, bad request), a body that isn't JSON, or a failure that may pass
 * on retry (network, timeout, rate limit, server error). Nothing is stored,
 * so the same document can be extracted again later.
 */
public class ModelUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ModelUnavailableException(String message) {
        super(message);
    }

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
