package app.buddy.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

public class RuntimeStateMachineTest {
    @Test public void followsHealthyAndRecoveryLifecycle() {
        RuntimeStateMachine machine = new RuntimeStateMachine();
        machine.transition(RuntimeStateMachine.State.STARTING);
        machine.transition(RuntimeStateMachine.State.READY);
        machine.transition(RuntimeStateMachine.State.UNHEALTHY);
        machine.transition(RuntimeStateMachine.State.STARTING);
        machine.transition(RuntimeStateMachine.State.STOPPING);
        machine.transition(RuntimeStateMachine.State.STOPPED);
        assertEquals(RuntimeStateMachine.State.STOPPED, machine.state());
    }

    @Test public void rejectsReadyWithoutStarting() {
        RuntimeStateMachine machine = new RuntimeStateMachine();
        try { machine.transition(RuntimeStateMachine.State.READY); fail("illegal transition accepted"); }
        catch (IllegalStateException expected) { }
    }
}
