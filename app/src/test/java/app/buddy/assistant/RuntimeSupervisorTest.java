package app.buddy.assistant;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class RuntimeSupervisorTest {
    private static final class TestContext extends ContextWrapper {
        TestContext() { super(null); }
        @Override public Context getApplicationContext() { return this; }
    }

    @Test public void restoresInterruptWhenStarterIsInterruptedBeforeProcessAssignment() throws Exception {
        Thread.interrupted(); // keep this test isolated from the runner thread's prior state
        RuntimeSupervisor supervisor = new RuntimeSupervisor(
                new TestContext(),
                new File(System.getProperty("java.io.tmpdir"), "buddy-supervisor-interrupt-test"),
                () -> { throw new InterruptedException("startup cancelled"); },
                false);
        try {
            try {
                supervisor.start();
                fail("interrupted startup unexpectedly succeeded");
            } catch (InterruptedException expected) {
                assertTrue("interrupt flag must be restored", Thread.currentThread().isInterrupted());
                assertEquals("startup cancelled", supervisor.lastError());
            }
            assertEquals(RuntimeStateMachine.State.STOPPED, supervisor.state());
        } finally {
            Thread.interrupted(); // clear the runner thread for following tests
        }
    }
}
