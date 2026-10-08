package com.g1do.anvil.cas;

import java.io.IOException;

/**
 * Stored bytes failed address verification: {@code sha256(bytes) != expected}.
 *
 * <p>Thrown instead of serving a torn tail. The corrupt file is best-effort
 * removed before throwing so a later read observes absence rather than torn
 * bytes and a later {@code put} can heal the address.
 */
public class CorruptContentException extends IOException {

    public CorruptContentException(String message) {
        super(message);
    }
}
