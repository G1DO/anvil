package com.g1do.anvil.jobs;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Timeline refusal for claims, heartbeats and commits (issue #34).
 *
 * <p>Thrown when the database timeline is unknown, or differs from the one
 * stored in {@code db_timeline} because a restore rewound the database. Maps
 * to {@code 503 TIMELINE_UNCONFIRMED}: zero job rows change, and the caller
 * retries after {@link JobService#confirmTimeline()} succeeds.
 */
public class TimelineUnconfirmedException extends ResponseStatusException {

    public TimelineUnconfirmedException(String detail) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "TIMELINE_UNCONFIRMED: " + detail);
    }

    public TimelineUnconfirmedException(String detail, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "TIMELINE_UNCONFIRMED: " + detail, cause);
    }
}
