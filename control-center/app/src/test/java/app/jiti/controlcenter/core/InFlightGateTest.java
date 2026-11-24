package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class InFlightGateTest {
    @Test public void rejectsDuplicateAcquisitionUntilReleased() {
        InFlightGate gate = new InFlightGate();
        assertTrue(gate.tryAcquire());
        assertTrue(gate.isInFlight());
        assertFalse(gate.tryAcquire());
        gate.release();
        assertFalse(gate.isInFlight());
        assertTrue(gate.tryAcquire());
    }

    @Test public void releaseIsIdempotent() {
        InFlightGate gate = new InFlightGate();
        gate.release();
        gate.release();
        assertFalse(gate.isInFlight());
        assertTrue(gate.tryAcquire());
    }
}
