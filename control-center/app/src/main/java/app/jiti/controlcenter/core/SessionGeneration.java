package app.jiti.controlcenter.core;

/**
 * Monotonically invalidates asynchronous work when a service session changes.
 * Tokens are captured when work is dispatched and must be checked before a
 * callback mutates state or schedules follow-up work.
 */
public final class SessionGeneration {
    private long generation;

    public synchronized long begin() {
        return ++generation;
    }

    public synchronized long invalidate() {
        return ++generation;
    }

    public synchronized long current() {
        return generation;
    }

    public synchronized boolean isCurrent(long token) {
        return token != 0L && token == generation;
    }
}
