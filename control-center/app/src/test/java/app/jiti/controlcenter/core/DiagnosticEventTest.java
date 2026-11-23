package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticEventTest {
    @Test public void eventMessageIsRedactedBeforePersistence() {
        DiagnosticEvent event = new DiagnosticEvent(1L, "buddy-tunnel", "ERROR",
                "BUDDY_AUTH_HEADER=Bearer very-secret");
        assertFalse(event.message.contains("very-secret"));
        assertTrue(event.message.contains("[REDACTED]"));
    }
}
