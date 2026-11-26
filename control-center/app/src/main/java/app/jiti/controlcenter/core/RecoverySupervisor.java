package app.jiti.controlcenter.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounded recovery planner for the three non-Buddy REA tunnel profiles.
 * It plans actions only; the caller owns execution and must use the shared
 * OperationCoordinator before invoking any Termux lifecycle command.
 */
public final class RecoverySupervisor {
    public enum Action {
        DISABLED,
        WAIT_FOR_NETWORK,
        WAIT_FOR_COOLDOWN,
        RECOVER,
        RECHECK_ONLY,
        MANUAL_REQUIRED,
        CIRCUIT_OPEN,
        EXHAUSTED
    }

    public static final class Plan {
        public final Action action;
        public final long delayMillis;
        public final int attempts;
        public final String reason;

        private Plan(Action action, long delayMillis, int attempts, String reason) {
            this.action = action;
            this.delayMillis = delayMillis;
            this.attempts = attempts;
            this.reason = reason;
        }
    }

    private static final class ProfileState {
        final CircuitBreaker breaker = new CircuitBreaker(2, 300000L);
        int attempts;
        long nextAllowedAt;
    }

    private final Map<String, ProfileState> profiles = new HashMap<>();
    private final RecoveryPolicy policy;
    private boolean enabled;

    public RecoverySupervisor() {
        this(new RecoveryPolicy(2, 5000L, 60000L));
    }

    public RecoverySupervisor(RecoveryPolicy policy) {
        if (policy == null) throw new IllegalArgumentException("recovery policy required");
        this.policy = policy;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    public synchronized Plan plan(String profileId, RecoveryPolicy.ErrorClass error,
                                  String failureSignature, long nowMillis,
                                  boolean networkAvailable, long retryAfterMillis,
                                  double jitterSample) {
        requireProfile(profileId);
        if (!enabled) return new Plan(Action.DISABLED, 0L, attempts(profileId), "automation_disabled");
        if (!networkAvailable) {
            return new Plan(Action.WAIT_FOR_NETWORK, 0L, attempts(profileId), "validated_network_unavailable");
        }
        if (error == null) return new Plan(Action.MANUAL_REQUIRED, 0L, attempts(profileId), "unknown_failure");

        ProfileState state = state(profileId);
        String signature = failureSignature == null || failureSignature.trim().isEmpty()
                ? error.name() : failureSignature.trim();

        if (error == RecoveryPolicy.ErrorClass.REMOTE_POLICY
                || error == RecoveryPolicy.ErrorClass.AUTHENTICATION
                || error == RecoveryPolicy.ErrorClass.CONFIGURATION
                || error == RecoveryPolicy.ErrorClass.PERMISSION
                || error == RecoveryPolicy.ErrorClass.UNKNOWN) {
            state.breaker.recordFailure(signature, nowMillis);
            return new Plan(Action.MANUAL_REQUIRED, 0L, state.attempts,
                    "non_retryable_" + error.name().toLowerCase(java.util.Locale.ROOT));
        }

        if (error == RecoveryPolicy.ErrorClass.RATE_LIMITED) {
            long delay = RecoveryErrorClassifier.boundedRetryAfterMillis(
                    retryAfterMillis, 60000L, 900000L);
            state.nextAllowedAt = Math.max(state.nextAllowedAt, nowMillis + delay);
            return new Plan(Action.RECHECK_ONLY, delay, state.attempts, "http_429_rate_limited_no_process_restart");
        }

        if (nowMillis < state.nextAllowedAt) {
            return new Plan(Action.WAIT_FOR_COOLDOWN, state.nextAllowedAt - nowMillis,
                    state.attempts, "profile_cooldown");
        }
        if (!state.breaker.mayProbe(nowMillis)) {
            return new Plan(Action.CIRCUIT_OPEN, 0L, state.attempts, "circuit_breaker_open");
        }

        state.breaker.recordFailure(signature, nowMillis);
        if (state.breaker.state() == CircuitBreaker.State.OPEN) {
            return new Plan(Action.CIRCUIT_OPEN, 0L, state.attempts, "repeated_identical_failure");
        }

        RecoveryPolicy.Decision decision = policy.decide(error, state.attempts);
        if (!decision.retry) {
            return new Plan(Action.EXHAUSTED, 0L, state.attempts, "retry_budget_exhausted");
        }
        state.attempts++;
        long delay = RecoveryPolicy.applyJitter(decision.delayMillis, 20, jitterSample);
        state.nextAllowedAt = nowMillis + delay;
        return new Plan(Action.RECOVER, delay, state.attempts, "bounded_recovery_attempt");
    }

    public synchronized void recordSuccess(String profileId) {
        requireProfile(profileId);
        ProfileState state = state(profileId);
        state.attempts = 0;
        state.nextAllowedAt = 0L;
        state.breaker.recordSuccess();
    }

    public synchronized int attempts(String profileId) {
        requireProfile(profileId);
        return state(profileId).attempts;
    }

    private ProfileState state(String profileId) {
        ProfileState state = profiles.get(profileId);
        if (state == null) {
            state = new ProfileState();
            profiles.put(profileId, state);
        }
        return state;
    }

    private static void requireProfile(String profileId) {
        if (!"bridge".equals(profileId) && !"droidmcp".equals(profileId)
                && !"rea-android".equals(profileId)) {
            throw new IllegalArgumentException("only the three REA tunnel profiles may be auto-managed");
        }
    }
}
