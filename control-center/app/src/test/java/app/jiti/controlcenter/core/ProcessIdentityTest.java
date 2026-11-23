package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessIdentityTest {
    @Test public void stalePidAndWrongIdentityAreRejected() {
        assertTrue(ProcessIdentity.isStalePid(32221, false, false));
        assertTrue(ProcessIdentity.isStalePid(12, true, false));
        ProcessIdentity p = new ProcessIdentity(44, "tunnel-client run --config buddy.yaml");
        assertTrue(p.matches(44, "tunnel-client", "buddy.yaml"));
        assertFalse(p.matches(44, "tunnel-client", "rea.yaml"));
    }
}
