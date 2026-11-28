package app.buddy.assistant;

/** Serializes helper process starts against final shutdown cleanup. */
final class RuntimeLifecycleGate {
    interface StartAction { boolean start() throws Exception; }

    private volatile boolean stopping;

    synchronized Boolean startIfActive(StartAction action) throws Exception {
        if (stopping || Thread.currentThread().isInterrupted()) return null;
        return action.start();
    }

    void markStopping() { stopping = true; }

    synchronized boolean runIfActive(Runnable action) {
        if (stopping) return false;
        action.run();
        return true;
    }

    synchronized void cleanup(Runnable action) {
        stopping = true;
        action.run();
    }
}
