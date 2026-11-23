package app.jiti.controlcenter.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import static org.junit.Assert.*;

public class DependencyPlannerTest {
    @Test public void dependenciesStartBeforeDependantsAndStopInReverse() {
        ComponentRegistry registry = ComponentRegistry.historicalBaseline();
        LinkedHashSet<String> selected = new LinkedHashSet<>(Arrays.asList("e2e-buddy"));
        List<String> start = DependencyPlanner.startupOrder(registry, selected);
        assertTrue(start.indexOf("android") < start.indexOf("buddy-app"));
        assertTrue(start.indexOf("buddy-app") < start.indexOf("buddy-accessibility"));
        assertTrue(start.indexOf("buddy-mcp") < start.indexOf("buddy-tunnel"));
        List<String> stop = DependencyPlanner.shutdownOrder(registry, selected);
        assertEquals(start.get(0), stop.get(stop.size() - 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownSelectedComponentRejected() {
        DependencyPlanner.startupOrder(ComponentRegistry.historicalBaseline(),
                new LinkedHashSet<>(Arrays.asList("missing")));
    }
}
