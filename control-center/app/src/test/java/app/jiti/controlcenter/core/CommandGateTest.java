package app.jiti.controlcenter.core;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.*;

public class CommandGateTest {
    @Test public void defaultGateDeniesEverything() {
        assertFalse(CommandGate.locked().mayExecute("buddy.start", true, true, true));
    }
    @Test public void buddyLifecycleCommandsRemainDenied() {
        CommandGate gate = CommandGate.locked();
        assertFalse(gate.mayExecute("buddy.start", true, true, true));
        assertFalse(gate.mayExecute("buddy.stop", true, true, true));
        assertFalse(gate.mayExecute("profile.restart", true, true, true));
    }
    @Test public void allIndependentGuardsMustPass() {
        HashSet<String> enabled = new HashSet<>(Collections.singletonList("profile.restart"));
        CommandGate gate = new CommandGate(enabled);
        assertTrue(gate.mayExecute("profile.restart", true, true, true));
        assertFalse(gate.mayExecute("profile.restart", false, true, true));
        assertFalse(gate.mayExecute("profile.restart", true, false, true));
        assertFalse(gate.mayExecute("profile.restart", true, true, false));
        assertFalse(gate.mayExecute("other.restart", true, true, true));
    }
}
