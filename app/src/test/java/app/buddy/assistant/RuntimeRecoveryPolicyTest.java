package app.buddy.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeRecoveryPolicyTest {
    @Test public void exponentialDelayIsCappedAndResettable() {
        RuntimeRecoveryPolicy policy = new RuntimeRecoveryPolicy(1000, 8000);
        assertEquals(1000, policy.nextDelayMillis());
        assertEquals(2000, policy.nextDelayMillis());
        assertEquals(4000, policy.nextDelayMillis());
        assertEquals(8000, policy.nextDelayMillis());
        assertEquals(8000, policy.nextDelayMillis());
        assertEquals(5, policy.failures());
        policy.reset();
        assertEquals(0, policy.failures());
        assertEquals(1000, policy.nextDelayMillis());
    }

    @Test public void rejectsInvalidDelayBounds() {
        try {
            new RuntimeRecoveryPolicy(0, 1000);
            fail("zero initial delay accepted");
        } catch (IllegalArgumentException expected) { }
        try {
            new RuntimeRecoveryPolicy(2000, 1000);
            fail("max below initial accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
