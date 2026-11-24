package app.buddy.assistant;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class RuntimeDependencyPlannerTest {
    private RuntimeComponentSpec spec(String id, String group, int order, String... deps) {
        return new RuntimeComponentSpec(id, group, order, Arrays.asList(deps));
    }

    @Test public void preservesOperationalGroupOrderWithoutInventingDependencies() {
        List<RuntimeComponentSpec> input = Arrays.asList(
                spec("rea_android", "rea", 30),
                spec("buddy_tunnel", "buddy", 10, "buddy_mcp"),
                spec("rea_bridge", "rea", 10),
                spec("rea_filesystem", "rea", 20),
                spec("buddy_mcp", "buddy", 0));
        List<RuntimeComponentSpec> rea = RuntimeDependencyPlanner.planGroup(input, "rea");
        assertEquals(Arrays.asList("rea_bridge", "rea_filesystem", "rea_android"),
                Arrays.asList(rea.get(0).id, rea.get(1).id, rea.get(2).id));
        List<RuntimeComponentSpec> buddy = RuntimeDependencyPlanner.planGroup(input, "buddy");
        assertEquals(Arrays.asList("buddy_mcp", "buddy_tunnel"),
                Arrays.asList(buddy.get(0).id, buddy.get(1).id));
    }

    @Test public void rejectsMissingDependency() {
        try {
            RuntimeDependencyPlanner.planGroup(Collections.singletonList(spec("tunnel", "buddy", 1, "mcp")), "buddy");
            fail("missing dependency accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsCrossGroupDependency() {
        List<RuntimeComponentSpec> input = Arrays.asList(spec("mcp", "buddy", 1), spec("rea", "rea", 2, "mcp"));
        try {
            RuntimeDependencyPlanner.planGroup(input, "rea");
            fail("cross-group dependency accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsCycles() {
        List<RuntimeComponentSpec> input = Arrays.asList(spec("a", "g", 1, "b"), spec("b", "g", 2, "a"));
        try {
            RuntimeDependencyPlanner.planGroup(input, "g");
            fail("cycle accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void rejectsDuplicateComponentIds() {
        List<RuntimeComponentSpec> input = Arrays.asList(spec("a", "g", 1), spec("a", "g", 2));
        try {
            RuntimeDependencyPlanner.planGroup(input, "g");
            fail("duplicate accepted");
        } catch (IllegalArgumentException expected) { }
    }
}
