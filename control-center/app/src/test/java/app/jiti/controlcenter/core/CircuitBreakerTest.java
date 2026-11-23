package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class CircuitBreakerTest {
    @Test public void repeatedSameFailureOpensAndCooldownAllowsOneProbe() {
        CircuitBreaker breaker = new CircuitBreaker(2, 500);
        breaker.recordFailure("connection-refused", 1000);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        breaker.recordFailure("connection-refused", 1100);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.mayProbe(1200));
        assertTrue(breaker.mayProbe(1600));
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        breaker.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }
    @Test public void changedFailureSignatureStartsNewStreak() {
        CircuitBreaker breaker = new CircuitBreaker(2, 100);
        breaker.recordFailure("network", 1);
        breaker.recordFailure("permission", 2);
        assertEquals(1, breaker.consecutiveFailures());
    }
}
