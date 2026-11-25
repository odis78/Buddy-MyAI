package app.buddy.assistant;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RuntimeHelperBootstrapTest {
    @Test public void oneHelperFailureDoesNotBlockFollowingHelpers() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        List<String> states = new ArrayList<>();
        AtomicInteger secondStarts = new AtomicInteger();

        boolean first = RuntimeHelperBootstrap.startOne(gate,
                () -> { throw new IOException("storage permission missing"); },
                (state, detail) -> states.add(state + ":" + detail),
                "first ready", () -> "first waiting");

        boolean second = RuntimeHelperBootstrap.startOne(gate,
                () -> { secondStarts.incrementAndGet(); return true; },
                (state, detail) -> states.add(state + ":" + detail),
                "second ready", () -> "second waiting");

        assertFalse(first);
        assertTrue(second);
        assertEquals(1, secondStarts.get());
        assertEquals(2, states.size());
        assertEquals("ERROR:storage permission missing", states.get(0));
        assertEquals("READY:second ready", states.get(1));
    }

    @Test public void falseReadinessRetainsTheSpecificSupervisorDiagnostic() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        List<String> states = new ArrayList<>();

        boolean ready = RuntimeHelperBootstrap.startOne(gate,
                () -> false,
                (state, detail) -> states.add(state + ":" + detail),
                "helper ready", () -> "loopback health check failed");

        assertFalse(ready);
        assertEquals("WAITING:loopback health check failed", states.get(0));
    }

    @Test public void stopGatePreventsHelperStartAndDoesNotOverwriteStoppedState() {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        gate.markStopping();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();

        boolean ready = RuntimeHelperBootstrap.startOne(gate,
                () -> { starts.incrementAndGet(); return true; },
                (state, detail) -> writes.incrementAndGet(),
                "helper ready", () -> "helper waiting");

        assertFalse(ready);
        assertEquals(0, starts.get());
        assertEquals(0, writes.get());
    }
}
