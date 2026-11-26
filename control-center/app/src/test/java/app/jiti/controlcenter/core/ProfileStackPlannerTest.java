package app.jiti.controlcenter.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class ProfileStackPlannerTest {
    @Test public void startupOrderPreservesKnownThreeTunnelSequenceAndExcludesBuddy() {
        assertEquals(Arrays.asList("bridge", "droidmcp", "rea-android"),
                ProfileStackPlanner.startOrder());
        assertFalse(ProfileStackPlanner.startOrder().contains("buddy"));
    }

    @Test public void shutdownOrderIsExactReverseOfStartup() {
        assertEquals(Arrays.asList("rea-android", "droidmcp", "bridge"),
                ProfileStackPlanner.stopOrder());
    }

    @Test public void fullRestartStopsReverseThenStartsForward() {
        List<ProfileStackPlanner.Step> steps = ProfileStackPlanner.restartOrder();
        assertEquals(6, steps.size());
        assertEquals("rea-android", steps.get(0).profileId);
        assertEquals("stop", steps.get(0).operation);
        assertEquals("bridge", steps.get(2).profileId);
        assertEquals("stop", steps.get(2).operation);
        assertEquals("bridge", steps.get(3).profileId);
        assertEquals("start", steps.get(3).operation);
        assertEquals("rea-android", steps.get(5).profileId);
        assertEquals("start", steps.get(5).operation);
        for (ProfileStackPlanner.Step step : steps) assertNotEquals("buddy", step.profileId);
    }
}
