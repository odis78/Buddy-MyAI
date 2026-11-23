package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeSnapshotTest {
    @Test public void snapshotRedactsDetailAndPreservesCounters() {
        RuntimeSnapshot snapshot = new RuntimeSnapshot("buddy-tunnel", ComponentStatus.READY,
                1000L, 1234, 2, 1, "BUDDY_TOKEN=private-value");
        assertEquals(ComponentStatus.READY, snapshot.status);
        assertEquals(2, snapshot.errorCount);
        assertEquals(1, snapshot.retryCount);
        assertFalse(snapshot.detail.contains("private-value"));
    }
    @Test(expected = IllegalArgumentException.class)
    public void negativeCountersAreRejected() {
        new RuntimeSnapshot("buddy-tunnel", ComponentStatus.ERROR, 1000L, 0, -1, 0, "bad");
    }
}
