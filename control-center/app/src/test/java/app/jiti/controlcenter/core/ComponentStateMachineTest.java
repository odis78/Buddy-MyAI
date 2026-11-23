package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ComponentStateMachineTest {
    @Test public void normalProbeAndStartupTransitionsAreAllowed() {
        assertEquals(ComponentStatus.CHECKING,
                ComponentStateMachine.transition(ComponentStatus.UNKNOWN, ComponentStatus.CHECKING));
        assertEquals(ComponentStatus.RUNNING,
                ComponentStateMachine.transition(ComponentStatus.STARTING, ComponentStatus.RUNNING));
        assertEquals(ComponentStatus.READY,
                ComponentStateMachine.transition(ComponentStatus.RUNNING, ComponentStatus.READY));
        assertEquals(ComponentStatus.NOT_READY,
                ComponentStateMachine.transition(ComponentStatus.READY, ComponentStatus.NOT_READY));
    }
    @Test(expected = IllegalStateException.class)
    public void unknownCannotJumpDirectlyToRunning() {
        ComponentStateMachine.transition(ComponentStatus.UNKNOWN, ComponentStatus.RUNNING);
    }
}
