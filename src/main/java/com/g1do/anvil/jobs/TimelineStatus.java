package com.g1do.anvil.jobs;

/** Outcome of {@link JobService#confirmTimeline()} (issue #34). */
public enum TimelineStatus {

    /** The stored timeline matches the server's; commits are allowed. */
    CONFIRMED,

    /** The timeline changed, but a lease is still live on the database clock. */
    WAITING_FOR_LEASES,

    /** The server's timeline can't be read; commits stay refused. */
    UNKNOWN
}
