package app.jiti.controlcenter.termux;

import org.junit.Test;
import static org.junit.Assert.*;

public class TermuxCommandSpecTest {
    @Test public void onlyFixedAbsoluteReadOnlyOperationsAreAvailable() {
        for (TermuxOperation op : TermuxOperation.values()) {
            TermuxCommandSpec spec = TermuxCommandSpec.forOperation(op,
                    "/data/data/com.termux/files/usr",
                    "/data/data/com.termux/files/home");
            assertTrue(spec.isSafeFixedCommand());
            assertTrue(spec.background);
            assertTrue(spec.executable.startsWith("/"));
        }
    }
    @Test(expected = IllegalArgumentException.class)
    public void relativePathsAreRejected() {
        TermuxCommandSpec.forOperation(TermuxOperation.IDENTITY, "usr", "home");
    }
}
