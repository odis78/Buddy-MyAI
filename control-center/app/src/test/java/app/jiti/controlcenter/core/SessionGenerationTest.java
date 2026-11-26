package app.jiti.controlcenter.core;

import org.junit.Test;

import static org.junit.Assert.*;

public class SessionGenerationTest {
    @Test public void oldSessionTokenIsRejectedAfterStopAndRestart() {
        SessionGeneration sessions = new SessionGeneration();
        long first = sessions.begin();
        assertTrue(sessions.isCurrent(first));

        sessions.invalidate();
        long second = sessions.begin();

        assertNotEquals(first, second);
        assertFalse(sessions.isCurrent(first));
        assertTrue(sessions.isCurrent(second));
    }

    @Test public void invalidatedSessionCannotResumeBeforeNextSessionStarts() {
        SessionGeneration sessions = new SessionGeneration();
        long token = sessions.begin();

        sessions.invalidate();

        assertFalse(sessions.isCurrent(token));
        assertFalse(sessions.isCurrent(sessions.current() - 1L));
    }

    @Test public void staleCompletionCannotInvalidateNewerSession() {
        SessionGeneration sessions = new SessionGeneration();
        long first = sessions.begin();
        long second = sessions.begin();

        assertFalse(sessions.isCurrent(first));
        assertTrue(sessions.isCurrent(second));
    }
}
