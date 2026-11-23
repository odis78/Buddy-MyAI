package app.jiti.controlcenter.core;

public final class RuntimeSnapshot {
    public final String componentId;
    public final ComponentStatus status;
    public final long checkedAtEpochMillis;
    public final int pid;
    public final int errorCount;
    public final int retryCount;
    public final String detail;

    public RuntimeSnapshot(String componentId, ComponentStatus status, long checkedAtEpochMillis,
                           int pid, int errorCount, int retryCount, String detail) {
        if (componentId == null || componentId.trim().isEmpty()) throw new IllegalArgumentException("componentId required");
        if (errorCount < 0 || retryCount < 0) throw new IllegalArgumentException("counters must be >= 0");
        this.componentId = componentId;
        this.status = status == null ? ComponentStatus.UNKNOWN : status;
        this.checkedAtEpochMillis = checkedAtEpochMillis;
        this.pid = pid;
        this.errorCount = errorCount;
        this.retryCount = retryCount;
        this.detail = SecretRedactor.redact(detail);
    }
}
