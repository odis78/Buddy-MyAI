package app.jiti.controlcenter.core;

import org.junit.Test;
import java.util.Collections;
import java.util.HashSet;
import static org.junit.Assert.*;

public class CommandGateTest {
    @Test public void defaultGateDeniesEverything() {
        assertFalse(CommandGate.locked().mayExecute("buddy.start", true, true, true));
    }
    @Test public void buddyStartAndStopAreExplicitlyAllowlisted() {
        HashSet<String> enabled = new HashSet<>();
        enabled.add("buddy.start");
        enabled.add("buddy.stop");
        CommandGate gate = new CommandGate(enabled);
        assertTrue(gate.mayExecute("buddy.start", true, true, true));
        assertTrue(gate.mayExecute("buddy.stop", true, true, true));
        assertFalse(gate.mayExecute("buddy.start", true, false, true));
        assertFalse(gate.mayExecute("buddy.stop", true, true, false));
    }
    @Test public void allIndependentGuardsMustPass() {
        HashSet<String> enabled = new HashSet<>(Collections.singletonList("buddy.restart"));
        CommandGate gate = new CommandGate(enabled);
        assertTrue(gate.mayExecute("buddy.restart", true, true, true));
        assertFalse(gate.mayExecute("buddy.restart", false, true, true));
        assertFalse(gate.mayExecute("buddy.restart", true, false, true));
        assertFalse(gate.mayExecute("buddy.restart", true, true, false));
        assertFalse(gate.mayExecute("other.restart", true, true, true));
    }
}
