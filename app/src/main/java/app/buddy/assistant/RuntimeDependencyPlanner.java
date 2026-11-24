package app.buddy.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Deterministic startup planner. Explicit dependencies are graph edges; startupOrder is only
 * a stable ordering constraint within an operational group and never invents cross-group edges.
 */
final class RuntimeDependencyPlanner {
    private RuntimeDependencyPlanner() { }

    static List<RuntimeComponentSpec> planGroup(List<RuntimeComponentSpec> all, String group) {
        if (all == null || group == null) throw new IllegalArgumentException("components and group are required");
        Map<String, RuntimeComponentSpec> selected = new HashMap<>();
        for (RuntimeComponentSpec spec : all) {
            if (spec == null) throw new IllegalArgumentException("Null component");
            if (spec.group.equals(group) && selected.put(spec.id, spec) != null) {
                throw new IllegalArgumentException("Duplicate component id: " + spec.id);
            }
        }
        if (selected.isEmpty()) return Collections.emptyList();

        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (RuntimeComponentSpec spec : selected.values()) {
            indegree.put(spec.id, 0);
            outgoing.put(spec.id, new ArrayList<String>());
        }
        for (RuntimeComponentSpec spec : selected.values()) {
            for (String dependency : spec.dependsOn) {
                if (!selected.containsKey(dependency)) {
                    throw new IllegalArgumentException("Missing or cross-group dependency " + dependency + " for " + spec.id);
                }
                indegree.put(spec.id, indegree.get(spec.id) + 1);
                outgoing.get(dependency).add(spec.id);
            }
        }

        Comparator<RuntimeComponentSpec> order = Comparator
                .comparingInt((RuntimeComponentSpec s) -> s.startupOrder)
                .thenComparing(s -> s.id);
        PriorityQueue<RuntimeComponentSpec> ready = new PriorityQueue<>(order);
        for (RuntimeComponentSpec spec : selected.values()) {
            if (indegree.get(spec.id) == 0) ready.add(spec);
        }

        List<RuntimeComponentSpec> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            RuntimeComponentSpec next = ready.remove();
            result.add(next);
            for (String dependent : outgoing.get(next.id)) {
                int left = indegree.get(dependent) - 1;
                indegree.put(dependent, left);
                if (left == 0) ready.add(selected.get(dependent));
            }
        }
        if (result.size() != selected.size()) throw new IllegalArgumentException("Dependency cycle in group " + group);
        return Collections.unmodifiableList(result);
    }

    static List<String> groupsInOrder(List<RuntimeComponentSpec> all) {
        if (all == null) throw new IllegalArgumentException("components are required");
        // Validate before sorting: Comparator dereferences each component.
        for (RuntimeComponentSpec spec : all) {
            if (spec == null) throw new IllegalArgumentException("Null component");
        }
        List<RuntimeComponentSpec> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt((RuntimeComponentSpec s) -> s.startupOrder).thenComparing(s -> s.id));
        Set<String> seen = new HashSet<>();
        List<String> groups = new ArrayList<>();
        for (RuntimeComponentSpec spec : sorted) {
            if (seen.add(spec.group)) groups.add(spec.group);
        }
        return Collections.unmodifiableList(groups);
    }
}
