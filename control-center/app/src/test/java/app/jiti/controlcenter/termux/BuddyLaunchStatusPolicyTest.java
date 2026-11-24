package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class BuddyLaunchStatusPolicyTest {
    @Test public void acceptsStatusWrittenAfterCurrentRequest() {
        assertTrue(BuddyLaunchStatusPolicy.isFreshForRequest(105L, 110L, 104L));
    }

    @Test public void rejectsStatusFromEarlierRequest() {
        assertFalse(BuddyLaunchStatusPolicy.isFreshForRequest(103L, 110L, 104L));
    }

    @Test public void rejectsStatusOlderThanThirtySeconds() {
        assertFalse(BuddyLaunchStatusPolicy.isFreshForRequest(70L, 101L, 70L));
    }

    @Test public void rejectsFutureOrMissingTimestamps() {
        assertFalse(BuddyLaunchStatusPolicy.isFreshForRequest(111L, 110L, 104L));
        assertFalse(BuddyLaunchStatusPolicy.isFreshForRequest(0L, 110L, 104L));
        assertFalse(BuddyLaunchStatusPolicy.isFreshForRequest(105L, 110L, 0L));
    }
}
