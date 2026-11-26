package app.jiti.controlcenter.core;

public final class RecoveryPolicy {
    public enum ErrorClass { TRANSIENT, RATE_LIMITED, PROCESS_MISSING, READINESS_FAILED, AUTHENTICATION, REMOTE_POLICY, CONFIGURATION, PERMISSION, UNKNOWN }

    public static final class Decision {
        public final boolean retry;
        public final boolean openCircuit;
        public final long delayMillis;
        Decision(boolean retry, boolean openCircuit, long delayMillis) {
            this.retry = retry; this.openCircuit = openCircuit; this.delayMillis = delayMillis;
        }
    }

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public RecoveryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {
        if (maxAttempts < 0 || baseDelayMillis < 0 || maxDelayMillis < baseDelayMillis)
            throw new IllegalArgumentException("invalid retry policy");
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = baseDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
    }

    /**
     * Applies symmetric jitter to a bounded backoff. sample must be supplied
     * by the scheduler in [0,1], which keeps this policy deterministic in tests.
     */
    public static long applyJitter(long delayMillis, int jitterPercent, double sample) {
        if (delayMillis < 0 || jitterPercent < 0 || jitterPercent > 50
                || Double.isNaN(sample) || sample < 0.0 || sample > 1.0) {
            throw new IllegalArgumentException("invalid jitter parameters");
        }
        double spread = jitterPercent / 100.0;
        double multiplier = 1.0 - spread + (2.0 * spread * sample);
        double adjusted = delayMillis * multiplier;
        return adjusted >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(adjusted);
    }

    public Decision decide(ErrorClass error, int attemptsAlreadyMade) {
        if (attemptsAlreadyMade < 0) throw new IllegalArgumentException("attempts must be >= 0");
        if (error == ErrorClass.AUTHENTICATION || error == ErrorClass.REMOTE_POLICY
                || error == ErrorClass.CONFIGURATION || error == ErrorClass.PERMISSION)
            return new Decision(false, true, 0);
        if (attemptsAlreadyMade >= maxAttempts)
            return new Decision(false, true, 0);
        if (error == ErrorClass.UNKNOWN)
            return new Decision(false, true, 0);
        long delay = baseDelayMillis;
        for (int i = 0; i < attemptsAlreadyMade && delay < maxDelayMillis; i++)
            delay = Math.min(maxDelayMillis, delay > maxDelayMillis / 2 ? maxDelayMillis : delay * 2);
        return new Decision(true, false, delay);
    }
}
