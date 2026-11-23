package app.jiti.controlcenter.core;

import org.junit.Test;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Collections;
import static org.junit.Assert.*;

public class DependencyPlannerTest {
    @Test public void dependenciesStartBeforeDependantsAndStopInReverse() {
        ComponentRegistry registry = ComponentRegistry.historicalBaseline();
        LinkedHashSet<String> selected = new LinkedHashSet<>(Arrays.asList("e2e-buddy"));
        List<String> start = DependencyPlanner.startupOrder(registry, selected);
        assertTrue(start.indexOf("android") < start.indexOf("buddy-app"));
        assertTrue(start.indexOf("buddy-app") < start.indexOf("buddy-accessibility"));
        assertTrue(start.indexOf("buddy-accessibility") < start.indexOf("buddy-mcp"));
        assertTrue(start.indexOf("buddy-mcp") < start.indexOf("buddy-tunnel"));
        assertTrue(start.indexOf("remote-tunnel-buddy") < start.indexOf("buddy-tunnel"));
        assertTrue(start.indexOf("buddy-tunnel") < start.indexOf("chatgpt-connector-buddy"));
        List<String> stop = DependencyPlanner.shutdownOrder(registry, selected);
        assertEquals(start.get(0), stop.get(stop.size() - 1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownSelectedComponentRejected() {
        DependencyPlanner.startupOrder(ComponentRegistry.historicalBaseline(),
                new LinkedHashSet<>(Arrays.asList("missing")));
    }

    @Test public void restartPlanIncludesOnlyRootAndDownstreamDependants() {
        ComponentRegistry registry = ComponentRegistry.historicalBaseline();
        DependencyPlanner.RestartPlan plan = DependencyPlanner.restartPlan(registry,
                new LinkedHashSet<>(Collections.singletonList("buddy-mcp")));
        assertTrue(plan.affectedComponents.contains("buddy-mcp"));
        assertTrue(plan.affectedComponents.contains("buddy-tunnel"));
        assertTrue(plan.affectedComponents.contains("chatgpt-connector-buddy"));
        assertTrue(plan.affectedComponents.contains("e2e-buddy"));
        assertFalse(plan.affectedComponents.contains("buddy-accessibility"));
        assertFalse(plan.affectedComponents.contains("buddy-app"));
        assertFalse(plan.affectedComponents.contains("termux"));
        assertTrue(plan.stopOrder.indexOf("e2e-buddy") < plan.stopOrder.indexOf("buddy-tunnel"));
        assertTrue(plan.startOrder.indexOf("buddy-mcp") < plan.startOrder.indexOf("buddy-tunnel"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void dependencyCycleRejected() {
        ComponentSpec a = new ComponentSpec("a", "A", "test", "", EvidenceStatus.UNVERIFIED,
                new LinkedHashSet<>(Collections.singletonList("b")), false, null);
        ComponentSpec b = new ComponentSpec("b", "B", "test", "", EvidenceStatus.UNVERIFIED,
                new LinkedHashSet<>(Collections.singletonList("a")), false, null);
        ComponentRegistry registry = new ComponentRegistry(Arrays.asList(a, b));
        DependencyPlanner.startupOrder(registry, new LinkedHashSet<>(Collections.singletonList("a")));
    }
}
