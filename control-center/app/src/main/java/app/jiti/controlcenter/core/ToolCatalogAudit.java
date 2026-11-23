package app.jiti.controlcenter.core;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ToolCatalogAudit {
    public final Set<String> liveNames;
    public final Set<String> missingBase;
    public final Set<String> missingOptional;
    public final Set<String> extra;
    public final int reportedCount;
    public final int pageCount;

    private ToolCatalogAudit(Set<String> liveNames, Set<String> missingBase,
                             Set<String> missingOptional, Set<String> extra,
                             int reportedCount, int pageCount) {
        this.liveNames = liveNames;
        this.missingBase = missingBase;
        this.missingOptional = missingOptional;
        this.extra = extra;
        this.reportedCount = reportedCount;
        this.pageCount = pageCount;
    }

    public static ToolCatalogAudit analyze(Collection<String> names, int reportedCount, int pageCount) {
        Set<String> live = new LinkedHashSet<>();
        if (names != null) for (String name : names) if (name != null && !name.isEmpty()) live.add(name);
        Set<String> base = new LinkedHashSet<>();
        Set<String> optional = new LinkedHashSet<>();
        for (ToolSpec spec : ToolCatalog.all()) {
            (spec.optionalFeature ? optional : base).add(spec.name);
        }
        Set<String> missingBase = new LinkedHashSet<>(base);
        missingBase.removeAll(live);
        Set<String> missingOptional = new LinkedHashSet<>(optional);
        missingOptional.removeAll(live);
        Set<String> extra = new LinkedHashSet<>(live);
        extra.removeAll(base);
        extra.removeAll(optional);
        return new ToolCatalogAudit(live, missingBase, missingOptional, extra,
                Math.max(reportedCount, 0), Math.max(pageCount, 0));
    }

    public boolean baseCatalogReady() { return missingBase.isEmpty(); }

    public String summary(String authSource) {
        return "Authenticated tools/list: live=" + reportedCount
                + ", unique=" + liveNames.size()
                + ", base expected=" + ToolCatalog.baseCount()
                + ", missing base=" + missingBase
                + ", optional missing=" + missingOptional
                + ", extra=" + extra
                + ", pages=" + pageCount
                + ", auth source name=" + (authSource == null ? "unknown" : authSource);
    }
}
