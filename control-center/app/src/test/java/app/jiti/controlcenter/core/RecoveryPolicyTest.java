package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoveryPolicyTest {
    @Test public void transientErrorsUseBoundedExponentialBackoff() {
        RecoveryPolicy p = new RecoveryPolicy(2, 100, 500);
        assertEquals(100, p.decide(RecoveryPolicy.ErrorClass.TRANSIENT, 0).delayMillis);
        assertEquals(200, p.decide(RecoveryPolicy.ErrorClass.TRANSIENT, 1).delayMillis);
        assertFalse(p.decide(RecoveryPolicy.ErrorClass.TRANSIENT, 2).retry);
    }
    @Test public void authAndConfigErrorsNeverRetryAutomatically() {
        RecoveryPolicy p = new RecoveryPolicy(5, 100, 1000);
        assertTrue(p.decide(RecoveryPolicy.ErrorClass.AUTHENTICATION, 0).openCircuit);
        assertFalse(p.decide(RecoveryPolicy.ErrorClass.CONFIGURATION, 0).retry);
        assertFalse(p.decide(RecoveryPolicy.ErrorClass.PERMISSION, 0).retry);
    }
}
