package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryErrorClassifierTest {
    @Test public void forbidden403OpensCircuitWithoutAutomaticRetry() {
        RecoveryPolicy.ErrorClass error = RecoveryErrorClassifier.fromHttpStatus(403);
        assertEquals(RecoveryPolicy.ErrorClass.REMOTE_POLICY, error);
        RecoveryPolicy.Decision decision = new RecoveryPolicy(3, 1000, 10000).decide(error, 0);
        assertFalse(decision.retry);
        assertTrue(decision.openCircuit);
    }

    @Test public void rateLimited429UsesBoundedRetryBudget() {
        RecoveryPolicy.ErrorClass error = RecoveryErrorClassifier.fromHttpStatus(429);
        assertEquals(RecoveryPolicy.ErrorClass.RATE_LIMITED, error);
        RecoveryPolicy.Decision first = new RecoveryPolicy(2, 1000, 4000).decide(error, 0);
        RecoveryPolicy.Decision second = new RecoveryPolicy(2, 1000, 4000).decide(error, 1);
        RecoveryPolicy.Decision exhausted = new RecoveryPolicy(2, 1000, 4000).decide(error, 2);
        assertTrue(first.retry);
        assertEquals(1000, first.delayMillis);
        assertTrue(second.retry);
        assertEquals(2000, second.delayMillis);
        assertFalse(exhausted.retry);
        assertTrue(exhausted.openCircuit);
    }

    @Test public void networkLossAndTimeoutAreTransientButTlsAndBadEndpointAreNot() {
        assertEquals(RecoveryPolicy.ErrorClass.TRANSIENT,
                RecoveryErrorClassifier.fromTransportFailure(
                        RecoveryErrorClassifier.TransportFailure.NETWORK_UNAVAILABLE));
        assertEquals(RecoveryPolicy.ErrorClass.TRANSIENT,
                RecoveryErrorClassifier.fromTransportFailure(
                        RecoveryErrorClassifier.TransportFailure.CONNECT_TIMEOUT));
        assertEquals(RecoveryPolicy.ErrorClass.TRANSIENT,
                RecoveryErrorClassifier.fromTransportFailure(
                        RecoveryErrorClassifier.TransportFailure.CONNECTION_RESET));
        assertEquals(RecoveryPolicy.ErrorClass.CONFIGURATION,
                RecoveryErrorClassifier.fromTransportFailure(
                        RecoveryErrorClassifier.TransportFailure.TLS_CERTIFICATE));
        assertEquals(RecoveryPolicy.ErrorClass.CONFIGURATION,
                RecoveryErrorClassifier.fromTransportFailure(
                        RecoveryErrorClassifier.TransportFailure.INVALID_ENDPOINT));
    }

    @Test public void retryAfterIsHonoredButNeverExceedsConfiguredCap() {
        assertEquals(30000, RecoveryErrorClassifier.boundedRetryAfterMillis(30000, 1000, 60000));
        assertEquals(60000, RecoveryErrorClassifier.boundedRetryAfterMillis(90000, 1000, 60000));
        assertEquals(1000, RecoveryErrorClassifier.boundedRetryAfterMillis(-1, 1000, 60000));
        assertEquals(0, RecoveryErrorClassifier.boundedRetryAfterMillis(0, 1000, 60000));
    }

    @Test public void authenticationAndConfigurationErrorsRemainNonRetryable() {
        RecoveryPolicy policy = new RecoveryPolicy(5, 100, 1000);
        assertFalse(policy.decide(RecoveryErrorClassifier.fromHttpStatus(401), 0).retry);
        assertFalse(policy.decide(RecoveryErrorClassifier.fromHttpStatus(400), 0).retry);
        assertFalse(policy.decide(RecoveryErrorClassifier.fromHttpStatus(404), 0).retry);
    }
}
