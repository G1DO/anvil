package com.g1do.anvil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Seed and trace for a failed fault run (issue #78).
 *
 * <p>Each fault test draws its random choices from {@code FAULT_SEED} (a fixed
 * default in the gate, the workflow run's ID on a scheduled run) and records
 * what it did as trace lines. When a test fails, the seed, the trace, the
 * failure and the command that reruns it go to
 * {@code target/fault-runs/<test>.md} (or {@code FAULT_REPORT_DIR}). The
 * scheduled {@code faults} workflow opens an issue from those files.
 */
final class FaultReport implements BeforeEachCallback, TestWatcher {

    static final Path DEFAULT_DIR = Path.of("target", "fault-runs");

    private static final List<String> TRACE = Collections.synchronizedList(new ArrayList<>());

    /** {@code FAULT_SEED} when set, else {@code fallback}. */
    static long seed(long fallback) {
        String value = System.getenv("FAULT_SEED");
        return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
    }

    static Path dir() {
        String value = System.getenv("FAULT_REPORT_DIR");
        return value == null || value.isBlank() ? DEFAULT_DIR : Path.of(value);
    }

    /** Records one step of the running test's trace; safe from worker threads. */
    static void step(String line) {
        TRACE.add(line);
    }

    static List<String> trace() {
        synchronized (TRACE) {
            return List.copyOf(TRACE);
        }
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        TRACE.clear();
    }

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        String test = context.getRequiredTestClass().getSimpleName()
                + "#" + context.getRequiredTestMethod().getName();
        long seed = seedOf(context);
        write(dir(), test, seed, trace(), String.valueOf(cause),
                "FAULT_SEED=" + seed + " ./mvnw -B -ntp -Dtest='" + test + "' test");
    }

    private static long seedOf(ExtensionContext context) {
        return context.getTestInstance()
                .filter(Seeded.class::isInstance)
                .map(instance -> ((Seeded) instance).seed())
                .orElse(seed(0L));
    }

    /** Writes one report and returns its path. The first line carries the seed for the issue title. */
    static Path write(Path dir, String test, long seed, List<String> trace, String failure, String rerun) {
        StringBuilder body = new StringBuilder()
                .append("### ").append(test).append(" (seed ").append(seed).append(")\n\n")
                .append("Seed: `").append(seed).append("`\n\n")
                .append("Failure: ").append(failure).append("\n\n")
                .append("Rerun: `").append(rerun).append("`\n\n")
                .append("Trace:\n\n```\n");
        trace.forEach(line -> body.append(line).append('\n'));
        body.append("```\n");
        try {
            Files.createDirectories(dir);
            // The seed keeps reports from parallel runs of one test apart when they're merged.
            Path file = dir.resolve(test.replace('#', '-') + "-" + seed + ".md");
            Files.writeString(file, body, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A fault test that knows which seed it ran with. */
    interface Seeded {

        long seed();
    }
}
