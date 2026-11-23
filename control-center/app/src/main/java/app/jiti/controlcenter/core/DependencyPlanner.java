package app.jiti.controlcenter.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DependencyPlanner {
    private DependencyPlanner() {}

    public static List<String> startupOrder(ComponentRegistry registry, Set<String> selected) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> marks = new HashMap<>();
        for (String id : selected) visit(id, registry, marks, result);
        return result;
    }

    private static void visit(String id, ComponentRegistry registry, Map<String, Integer> marks, List<String> out) {
        ComponentSpec spec = registry.get(id);
        if (spec == null) throw new IllegalArgumentException("unknown component: " + id);
        Integer mark = marks.get(id);
        if (mark != null) {
            if (mark == 1) throw new IllegalArgumentException("dependency cycle at " + id);
            return;
        }
        marks.put(id, 1);
        for (String dependency : spec.dependencies) visit(dependency, registry, marks, out);
        marks.put(id, 2);
        out.add(id);
    }

    public static List<String> shutdownOrder(ComponentRegistry registry, Set<String> selected) {
        List<String> order = startupOrder(registry, selected);
        java.util.Collections.reverse(order);
        return order;
    }
}
