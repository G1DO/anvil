package com.g1do.anvil.jobs;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Capability denial for job commits (issue #21).
 *
 * <p>Thrown when the presented capability is missing, revoked, expired,
 * superseded (stale epoch), or bound to a different tenant/job. Maps to
 * {@code 403 NEEDS_APPROVAL}: the caller holds no valid authority for the
 * effect, zero job rows are committed, and the {@code cap_rejected} counter is
 * incremented. Stale fencing (wrong owner/token) is not a capability denial:
 * it returns {@code false} with no exception and no counter increment.
 */
public class CapDeniedException extends ResponseStatusException {

    public CapDeniedException() {
        super(HttpStatus.FORBIDDEN, "NEEDS_APPROVAL");
    }

    public CapDeniedException(String detail) {
        super(HttpStatus.FORBIDDEN, "NEEDS_APPROVAL: " + detail);
    }
}
