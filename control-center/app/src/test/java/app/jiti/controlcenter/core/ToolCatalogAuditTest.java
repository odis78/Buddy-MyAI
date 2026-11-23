package app.jiti.controlcenter.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class ToolCatalogAuditTest {
    @Test public void missingOptionalToolsDoNotFailBaseReadiness() {
        List<String> live = new ArrayList<>();
        for (ToolSpec spec : ToolCatalog.all()) if (!spec.optionalFeature) live.add(spec.name);
        ToolCatalogAudit audit = ToolCatalogAudit.analyze(live, live.size(), 1);
        assertTrue(audit.baseCatalogReady());
        assertEquals(ToolCatalog.optionalCount(), audit.missingOptional.size());
        assertTrue(audit.missingBase.isEmpty());
    }

    @Test public void missingBaseToolFailsReadinessAndExtrasAreReported() {
        List<String> live = new ArrayList<>();
        for (ToolSpec spec : ToolCatalog.all()) if (!spec.optionalFeature) live.add(spec.name);
        live.remove(0);
        live.add("unexpected_tool");
        ToolCatalogAudit audit = ToolCatalogAudit.analyze(live, live.size(), 2);
        assertFalse(audit.baseCatalogReady());
        assertEquals(1, audit.missingBase.size());
        assertTrue(audit.extra.contains("unexpected_tool"));
        assertEquals(2, audit.pageCount);
    }

    @Test public void duplicateNamesAreCountedOnceInUniqueSet() {
        ToolCatalogAudit audit = ToolCatalogAudit.analyze(
                java.util.Arrays.asList("read_screen", "read_screen"), 2, 1);
        assertEquals(1, audit.liveNames.size());
        assertEquals(2, audit.reportedCount);
    }
}
