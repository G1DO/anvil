package com.g1do.anvil.exec;

/**
 * Captured result of one jailed child execution (issue #23).
 *
 * @param exitCode process exit code.
 * @param stdout captured standard output (UTF-8, capped).
 * @param stderr captured standard error (UTF-8, capped).
 * @param truncated {@code true} when either stream exceeded the cap and was truncated.
 */
public record JailResult(int exitCode, String stdout, String stderr, boolean truncated) {
}
