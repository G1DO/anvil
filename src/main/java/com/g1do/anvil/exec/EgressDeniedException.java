package com.g1do.anvil.exec;

/**
 * Fail-closed egress denial for untrusted execution (issue #23).
 *
 * <p>Thrown when the untrusted path attempts network egress. The message carries
 * only the denial reason ({@code default-deny} vs {@code blocked target}), never
 * the URL or host, so a probe cannot oracle internal topology via errors.
 */
public class EgressDeniedException extends SecurityException {

    public EgressDeniedException() {
        super("egress denied");
    }

    public EgressDeniedException(String reason) {
        super("egress denied: " + reason);
    }
}
