package app.buddy.assistant;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Non-secret description of one managed runtime component. */
final class RuntimeComponentSpec {
    final String id;
    final String group;
    final int startupOrder;
    final List<String> dependsOn;

    RuntimeComponentSpec(String id, String group, int startupOrder, List<String> dependsOn) {
        if (!validId(id) || !validId(group)) throw new IllegalArgumentException("Invalid component id or group");
        if (dependsOn == null) throw new IllegalArgumentException("dependsOn is required");
        Set<String> seen = new HashSet<>();
        List<String> copy = new ArrayList<>();
        for (String dependency : dependsOn) {
            if (!validId(dependency) || dependency.equals(id) || !seen.add(dependency)) {
                throw new IllegalArgumentException("Invalid, self or duplicate dependency for " + id);
            }
            copy.add(dependency);
        }
        this.id = id;
        this.group = group;
        this.startupOrder = startupOrder;
        this.dependsOn = Collections.unmodifiableList(copy);
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[a-z][a-z0-9._-]{0,63}");
    }
}
