package app.buddy.assistant;

/** Deterministic capped exponential backoff for independent runtime restarts. */
final class RuntimeRecoveryPolicy {
    private final long initialDelayMs;
    private final long maxDelayMs;
    private int failures;

    RuntimeRecoveryPolicy(long initialDelayMs, long maxDelayMs) {
        if (initialDelayMs <= 0 || maxDelayMs < initialDelayMs) {
            throw new IllegalArgumentException("Invalid restart delay bounds");
        }
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    synchronized long nextDelayMillis() {
        int shift = Math.min(failures, 30);
        long delay = initialDelayMs > (Long.MAX_VALUE >> shift)
                ? Long.MAX_VALUE : initialDelayMs << shift;
        failures++;
        return Math.min(delay, maxDelayMs);
    }

    synchronized void reset() { failures = 0; }
    synchronized int failures() { return failures; }
}
