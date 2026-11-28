package app.buddy.assistant;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class RuntimeLifecycleGateTest {
    @Test public void shutdownWaitsForAnInFlightStartBeforeCleanup() throws Exception {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicBoolean cleanupRan = new AtomicBoolean(false);
        AtomicBoolean cleanupOverlappedStart = new AtomicBoolean(false);
        AtomicBoolean startActionActive = new AtomicBoolean(false);

        Thread starter = new Thread(() -> {
            try {
                gate.startIfActive(() -> {
                    startActionActive.set(true);
                    startEntered.countDown();
                    if (!releaseStart.await(3, TimeUnit.SECONDS)) throw new AssertionError("start release timed out");
                    startActionActive.set(false);
                    return true;
                });
            } catch (Exception e) { throw new AssertionError(e); }
        });
        starter.start();
        assertTrue("start did not enter", startEntered.await(2, TimeUnit.SECONDS));

        gate.markStopping();
        Thread cleanup = new Thread(() -> gate.cleanup(() -> {
            cleanupOverlappedStart.set(startActionActive.get());
            cleanupRan.set(true);
        }));
        cleanup.start();
        Thread.sleep(80);
        assertFalse("cleanup ran while start was still in flight", cleanupRan.get());

        releaseStart.countDown();
        starter.join(2000);
        cleanup.join(2000);
        assertFalse("starter thread remained alive", starter.isAlive());
        assertFalse("cleanup thread remained alive", cleanup.isAlive());
        assertTrue(cleanupRan.get());
        assertFalse(cleanupOverlappedStart.get());
    }

    @Test public void noHelperCanStartAfterStopWasRequested() throws Exception {
        RuntimeLifecycleGate gate = new RuntimeLifecycleGate();
        AtomicInteger starts = new AtomicInteger();
        gate.markStopping();
        Boolean result = gate.startIfActive(() -> { starts.incrementAndGet(); return true; });
        assertNull(result);
        assertEquals(0, starts.get());
    }
}
