package app.jiti.controlcenter.core;

/** Per-component breaker for repeated identical failures. */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int threshold;
    private final long cooldownMillis;
    private int consecutiveFailures;
    private String lastFailureSignature = "";
    private long openedAtMillis;
    private State state = State.CLOSED;

    public CircuitBreaker(int threshold, long cooldownMillis) {
        if (threshold < 1 || cooldownMillis < 0) throw new IllegalArgumentException("invalid breaker settings");
        this.threshold = threshold;
        this.cooldownMillis = cooldownMillis;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        lastFailureSignature = "";
        state = State.CLOSED;
        openedAtMillis = 0;
    }

    public synchronized void recordFailure(String signature, long nowMillis) {
        String normalized = signature == null || signature.trim().isEmpty() ? "unknown" : signature.trim();
        if (!normalized.equals(lastFailureSignature)) consecutiveFailures = 0;
        lastFailureSignature = normalized;
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= threshold) {
            state = State.OPEN;
            openedAtMillis = nowMillis;
        }
    }

    public synchronized boolean mayProbe(long nowMillis) {
        if (state == State.CLOSED) return true;
        if (state == State.OPEN && nowMillis - openedAtMillis >= cooldownMillis) {
            state = State.HALF_OPEN;
            return true;
        }
        return false;
    }

    public synchronized State state() { return state; }
    public synchronized int consecutiveFailures() { return consecutiveFailures; }
}
