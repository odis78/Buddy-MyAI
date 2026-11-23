package app.jiti.controlcenter.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ComponentSpec {
    public final String id;
    public final String title;
    public final String category;
    public final String detail;
    public final EvidenceStatus evidence;
    public final Set<String> dependencies;
    public final boolean controllable;
    public final String endpoint;

    public ComponentSpec(String id, String title, String category, String detail,
                         EvidenceStatus evidence, Set<String> dependencies,
                         boolean controllable, String endpoint) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id required");
        this.id = id;
        this.title = title;
        this.category = category;
        this.detail = detail;
        this.evidence = evidence;
        this.dependencies = Collections.unmodifiableSet(new LinkedHashSet<>(dependencies));
        this.controllable = controllable;
        this.endpoint = endpoint;
    }
}
