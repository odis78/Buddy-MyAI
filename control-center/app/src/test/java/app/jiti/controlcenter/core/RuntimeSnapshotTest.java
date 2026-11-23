package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeSnapshotTest {
    @Test public void diagnosticDetailsAreRedactedAtConstruction() {
        RuntimeSnapshot snapshot = new RuntimeSnapshot("buddy-tunnel", ComponentStatus.ERROR,
                123L, 5, 1, 0, "api_key=topsecret");
        assertFalse(snapshot.detail.contains("topsecret"));
        assertTrue(snapshot.detail.contains("[REDACTED]"));
    }
}
