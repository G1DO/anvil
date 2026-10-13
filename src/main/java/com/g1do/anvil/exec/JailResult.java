package com.g1do.anvil.exec;

/**
 * Captured result of one jailed child execution (issue #23).
 *
 * @param exitCode process exit code.
 * @param stdout captured standard output (UTF-8).
 * @param stderr captured standard error (UTF-8).
 */
public record JailResult(int exitCode, String stdout, String stderr) {
}
