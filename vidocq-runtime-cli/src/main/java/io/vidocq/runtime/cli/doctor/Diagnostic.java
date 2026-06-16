package io.vidocq.runtime.cli.doctor;

/**
 * Result of a single environment / project health check.
 *
 * <p>Pure data type — produced by {@link Diagnostics} and rendered by the
 * {@code doctor} command. The {@code hint} is optional remediation advice,
 * shown for failing/warning checks (or for all checks in verbose mode).
 *
 * @param name   short label of the check (e.g. {@code "Java version"})
 * @param status outcome of the check
 * @param detail human-readable detail of what was found
 * @param hint   remediation advice, or {@code null} when none applies
 */
public record Diagnostic(String name, Status status, String detail, String hint) {

    /** Severity of a {@link Diagnostic}, in increasing order. */
    public enum Status {
        /** Everything is fine. */
        OK,
        /** Non-fatal: the CLI works but something is sub-optimal. */
        WARN,
        /** Fatal for the checked capability; {@code doctor} exits non-zero. */
        FAIL
    }

    public static Diagnostic ok(String name, String detail) {
        return new Diagnostic(name, Status.OK, detail, null);
    }

    public static Diagnostic warn(String name, String detail, String hint) {
        return new Diagnostic(name, Status.WARN, detail, hint);
    }

    public static Diagnostic fail(String name, String detail, String hint) {
        return new Diagnostic(name, Status.FAIL, detail, hint);
    }
}
