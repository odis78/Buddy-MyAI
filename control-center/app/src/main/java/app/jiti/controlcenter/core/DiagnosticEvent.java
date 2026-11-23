package app.jiti.controlcenter.core;

public final class DiagnosticEvent {
    public final long timestampEpochMillis;
    public final String componentId;
    public final String eventType;
    public final String message;

    public DiagnosticEvent(long timestampEpochMillis, String componentId, String eventType, String message) {
        this.timestampEpochMillis = timestampEpochMillis;
        this.componentId = componentId == null ? "system" : componentId;
        this.eventType = eventType == null ? "INFO" : eventType;
        this.message = SecretRedactor.redact(message);
    }
}
