package app.jiti.controlcenter.core;

public final class InFlightGate {
    private boolean inFlight;

    public synchronized boolean tryAcquire() {
        if (inFlight) return false;
        inFlight = true;
        return true;
    }

    public synchronized void release() {
        inFlight = false;
    }

    public synchronized boolean isInFlight() {
        return inFlight;
    }
}
