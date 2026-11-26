package app.jiti.controlcenter.core;

/**
 * Serializes manual and automatic infrastructure operations.
 *
 * A manual request never runs concurrently with an automatic operation. If an
 * automatic operation is already executing, the manual request is queued and
 * the active recovery cycle is asked to stop scheduling further automatic work.
 * This class deliberately does not execute commands; callers must hand the
 * returned request to the single operation runner.
 */
public final class OperationCoordinator {
    private static final OperationCoordinator SHARED = new OperationCoordinator();

    public static OperationCoordinator shared() {
        return SHARED;
    }

    public enum Kind { MANUAL, AUTOMATIC }

    public enum Status {
        STARTED,
        QUEUED_AFTER_AUTOMATIC,
        REJECTED_AUTOMATION_DISABLED,
        REJECTED_MANUAL_IN_PROGRESS,
        REJECTED_MANUAL_ALREADY_QUEUED,
        REJECTED_OPERATION_IN_PROGRESS,
        REJECTED_AUTOMATIC_TARGET
    }

    public static final class Request {
        public final long id;
        public final String componentId;
        public final Kind kind;
        public final String operation;

        private Request(long id, String componentId, Kind kind, String operation) {
            this.id = id;
            this.componentId = componentId;
            this.kind = kind;
            this.operation = operation;
        }
    }

    public static final class Submission {
        public final Status status;
        public final Request request;

        private Submission(Status status, Request request) {
            this.status = status;
            this.request = request;
        }

        public boolean accepted() {
            return status == Status.STARTED || status == Status.QUEUED_AFTER_AUTOMATIC;
        }
    }

    private long nextId = 1L;
    private Request active;
    private Request pendingManual;
    private boolean automaticEnabled;
    private boolean cancelActiveAutomatic;

    /** Automation is opt-in and starts disabled. */
    public synchronized void setAutomaticEnabled(boolean enabled) {
        automaticEnabled = enabled;
        if (!enabled && active != null && active.kind == Kind.AUTOMATIC) {
            cancelActiveAutomatic = true;
        }
    }

    public synchronized boolean isAutomaticEnabled() {
        return automaticEnabled;
    }

    public synchronized Submission requestManual(String componentId) {
        return requestManual(componentId, "manual");
    }

    public synchronized Submission requestManual(String componentId, String operation) {
        String target = validateTarget(componentId);
        String action = validateOperation(operation);
        if (active != null && active.kind == Kind.MANUAL) {
            return new Submission(Status.REJECTED_MANUAL_IN_PROGRESS, null);
        }
        if (pendingManual != null) {
            return new Submission(Status.REJECTED_MANUAL_ALREADY_QUEUED, null);
        }

        Request request = new Request(nextId++, target, Kind.MANUAL, action);
        if (active == null) {
            active = request;
            return new Submission(Status.STARTED, request);
        }

        // The only remaining active kind is AUTOMATIC. Do not interrupt an
        // already-issued OS command, but stop its recovery cycle and give the
        // manual operation the next exclusive slot.
        pendingManual = request;
        cancelActiveAutomatic = true;
        return new Submission(Status.QUEUED_AFTER_AUTOMATIC, request);
    }

    public synchronized Submission requestAutomatic(String componentId) {
        return requestAutomatic(componentId, "recover.start");
    }

    public synchronized Submission requestAutomatic(String componentId, String operation) {
        String target = validateTarget(componentId);
        String action = validateOperation(operation);
        if (!"bridge".equals(target) && !"droidmcp".equals(target)
                && !"rea-android".equals(target)) {
            return new Submission(Status.REJECTED_AUTOMATIC_TARGET, null);
        }
        if (!automaticEnabled) {
            return new Submission(Status.REJECTED_AUTOMATION_DISABLED, null);
        }
        if (pendingManual != null || (active != null && active.kind == Kind.MANUAL)) {
            return new Submission(Status.REJECTED_MANUAL_IN_PROGRESS, null);
        }
        if (active != null) {
            return new Submission(Status.REJECTED_OPERATION_IN_PROGRESS, null);
        }

        Request request = new Request(nextId++, target, Kind.AUTOMATIC, action);
        active = request;
        cancelActiveAutomatic = false;
        return new Submission(Status.STARTED, request);
    }

    /**
     * Stops future automatic work. If a command is already in the OS, it is
     * allowed to report completion; no parallel stop/restart is attempted.
     *
     * @return true when an automatic operation was active and has been asked
     *         to stop scheduling its remaining recovery steps.
     */
    public synchronized boolean cancelAutomatic() {
        automaticEnabled = false;
        if (active != null && active.kind == Kind.AUTOMATIC) {
            cancelActiveAutomatic = true;
            return true;
        }
        return false;
    }

    public synchronized boolean shouldCancelActiveAutomatic() {
        return active != null && active.kind == Kind.AUTOMATIC && cancelActiveAutomatic;
    }

    /**
     * Completes only the matching active request. A stale callback cannot
     * release a newer operation. Returns the next request to execute, if any.
     */
    public synchronized Request complete(long requestId) {
        if (active == null || active.id != requestId) return null;
        active = null;
        cancelActiveAutomatic = false;
        if (pendingManual != null) {
            active = pendingManual;
            pendingManual = null;
            return active;
        }
        return null;
    }

    public synchronized Request activeRequest() {
        return active;
    }

    public synchronized boolean hasPendingManual() {
        return pendingManual != null;
    }

    public synchronized boolean isBusy() {
        return active != null;
    }

    private static String validateOperation(String operation) {
        if (operation == null || operation.trim().isEmpty()
                || operation.contains("/") || operation.contains("..")) {
            throw new IllegalArgumentException("valid operation id required");
        }
        return operation.trim();
    }

    private static String validateTarget(String componentId) {
        if (componentId == null || componentId.trim().isEmpty()
                || componentId.contains("/") || componentId.contains("..")) {
            throw new IllegalArgumentException("valid component id required");
        }
        return componentId.trim();
    }
}
