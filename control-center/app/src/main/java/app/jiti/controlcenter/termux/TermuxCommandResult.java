package app.jiti.controlcenter.termux;

import app.jiti.controlcenter.core.SecretRedactor;

public final class TermuxCommandResult {
    public final int executionId;
    public final String stdout;
    public final String stderr;
    public final int exitCode;
    public final int internalError;
    public final String internalErrorMessage;
    public final boolean truncated;

    public TermuxCommandResult(int executionId, String stdout, String stderr, int exitCode,
                               int internalError, String internalErrorMessage, boolean truncated) {
        this.executionId = executionId;
        this.stdout = safe(stdout);
        this.stderr = safe(stderr);
        this.exitCode = exitCode;
        this.internalError = internalError;
        this.internalErrorMessage = safe(internalErrorMessage);
        this.truncated = truncated;
    }

    private static String safe(String value) {
        if (value == null) return "";
        String redacted = SecretRedactor.redact(value);
        return redacted.length() > 16384 ? redacted.substring(0, 16384) + "\n[OUTPUT TRUNCATED]" : redacted;
    }

    public boolean succeeded() {
        return exitCode == 0 && internalError == -1;
    }
}
