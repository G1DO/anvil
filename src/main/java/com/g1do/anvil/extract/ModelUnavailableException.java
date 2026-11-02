package com.g1do.anvil.extract;

/**
 * The model couldn't be asked: it isn't configured, or the call failed in a
 * way that may pass on retry (network, timeout, rate limit, server error).
 * Nothing is stored, so the same document can be extracted again later.
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
