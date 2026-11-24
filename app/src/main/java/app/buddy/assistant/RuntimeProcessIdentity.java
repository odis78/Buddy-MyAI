package app.buddy.assistant;

import java.io.File;

/** PID is never sufficient: include process start time and executable/config identity. */
final class RuntimeProcessIdentity {
    final long pid;
    final long startTimeTicks;
    final String executable;
    final String configSha256;

    RuntimeProcessIdentity(long pid, long startTimeTicks, String executable, String configSha256) {
        if (pid <= 0 || startTimeTicks <= 0) throw new IllegalArgumentException("Invalid process identity numbers");
        if (executable == null || executable.trim().isEmpty()) throw new IllegalArgumentException("Executable is required");
        if (!isSha256(configSha256)) throw new IllegalArgumentException("A SHA-256 config identity is required");
        this.pid = pid;
        this.startTimeTicks = startTimeTicks;
        this.executable = canonical(executable);
        this.configSha256 = configSha256.toLowerCase(java.util.Locale.ROOT);
    }

    boolean matches(RuntimeProcessIdentity observed) {
        return observed != null
                && pid == observed.pid
                && startTimeTicks == observed.startTimeTicks
                && executable.equals(observed.executable)
                && configSha256.equals(observed.configSha256);
    }

    private static String canonical(String path) {
        try { return new File(path).getCanonicalPath(); }
        catch (Exception ignored) { return new File(path).getAbsolutePath(); }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }
}
