package app.jiti.controlcenter.core;

public final class RecoveryPolicy {
    public enum ErrorClass { TRANSIENT, PROCESS_MISSING, READINESS_FAILED, AUTHENTICATION, CONFIGURATION, PERMISSION, UNKNOWN }

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

    public Decision decide(ErrorClass error, int attemptsAlreadyMade) {
        if (attemptsAlreadyMade < 0) throw new IllegalArgumentException("attempts must be >= 0");
        if (error == ErrorClass.AUTHENTICATION || error == ErrorClass.CONFIGURATION || error == ErrorClass.PERMISSION)
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
