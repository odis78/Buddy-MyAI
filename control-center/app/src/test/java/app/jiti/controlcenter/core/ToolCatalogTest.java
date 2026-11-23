package app.jiti.controlcenter.core;

import org.junit.Test;
import java.util.HashSet;
import static org.junit.Assert.*;

public class ToolCatalogTest {
    @Test public void matchesAuditedBuddyCatalogShape() {
        assertEquals(52, ToolCatalog.all().size());
        assertEquals(44, ToolCatalog.baseCount());
        assertEquals(8, ToolCatalog.optionalCount());
        HashSet<String> names = new HashSet<>();
        for (ToolSpec tool : ToolCatalog.all()) assertTrue("duplicate tool " + tool.name, names.add(tool.name));
    }
    @Test public void privilegedAndSensitiveToolsAreMarked() {
        ToolSpec shell = ToolCatalog.all().stream().filter(t -> t.name.equals("phone_shell")).findFirst().orElseThrow();
        assertEquals(ToolSpec.Risk.PRIVILEGED, shell.risk);
        ToolSpec location = ToolCatalog.all().stream().filter(t -> t.name.equals("location")).findFirst().orElseThrow();
        assertEquals(ToolSpec.Risk.SENSITIVE_READ, location.risk);
    }
}
