package app.jiti.controlcenter.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DependencyPlanner {
    public static final class RestartPlan {
        public final Set<String> affectedComponents;
        public final List<String> stopOrder;
        public final List<String> startOrder;

        RestartPlan(Set<String> affected, List<String> stop, List<String> start) {
            this.affectedComponents = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(affected));
            this.stopOrder = java.util.Collections.unmodifiableList(new ArrayList<>(stop));
            this.startOrder = java.util.Collections.unmodifiableList(new ArrayList<>(start));
        }
    }

    private DependencyPlanner() {}

    public static List<String> startupOrder(ComponentRegistry registry, Set<String> selected) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> marks = new HashMap<>();
        for (String id : selected) visit(id, registry, marks, result);
        return result;
    }

    public static List<String> shutdownOrder(ComponentRegistry registry, Set<String> selected) {
        List<String> order = startupOrder(registry, selected);
        java.util.Collections.reverse(order);
        return order;
    }

    /** Includes roots and all transitive dependants, but not their upstream dependencies. */
    public static Set<String> downstreamClosure(ComponentRegistry registry, Set<String> roots) {
        LinkedHashSet<String> included = new LinkedHashSet<>();
        for (String root : roots) {
            if (registry.get(root) == null) throw new IllegalArgumentException("unknown component: " + root);
            included.add(root);
        }
        boolean changed;
        do {
            changed = false;
            for (ComponentSpec candidate : registry.all()) {
                if (included.contains(candidate.id)) continue;
                for (String dependency : candidate.dependencies) {
                    if (included.contains(dependency)) {
                        included.add(candidate.id);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
        return java.util.Collections.unmodifiableSet(included);
    }

    /** Plan only the failing component and its downstream impact; upstream services remain running. */
    public static RestartPlan restartPlan(ComponentRegistry registry, Set<String> roots) {
        Set<String> affected = downstreamClosure(registry, roots);
        List<String> start = topologicalWithin(registry, affected);
        List<String> stop = new ArrayList<>(start);
        java.util.Collections.reverse(stop);
        return new RestartPlan(affected, stop, start);
    }

    private static List<String> topologicalWithin(ComponentRegistry registry, Set<String> included) {
        List<String> result = new ArrayList<>();
        Map<String, Integer> marks = new HashMap<>();
        for (String id : included) visitWithin(id, registry, included, marks, result);
        return result;
    }

    private static void visitWithin(String id, ComponentRegistry registry, Set<String> included,
                                    Map<String, Integer> marks, List<String> out) {
        ComponentSpec spec = registry.get(id);
        if (spec == null) throw new IllegalArgumentException("unknown component: " + id);
        Integer mark = marks.get(id);
        if (mark != null) {
            if (mark == 1) throw new IllegalArgumentException("dependency cycle at " + id);
            return;
        }
        marks.put(id, 1);
        for (String dependency : spec.dependencies) {
            if (included.contains(dependency)) visitWithin(dependency, registry, included, marks, out);
        }
        marks.put(id, 2);
        out.add(id);
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
}
