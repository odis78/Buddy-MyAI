package app.jiti.controlcenter.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Deny-by-default execution gate. A component is not executable merely because
 * it appears in the registry; an exact operation contract must be reviewed and enabled.
 */
public final class CommandGate {
    private final Set<String> enabledOperationIds;

    public CommandGate(Set<String> enabledOperationIds) {
        this.enabledOperationIds = Collections.unmodifiableSet(new HashSet<>(enabledOperationIds));
    }

    public boolean mayExecute(String operationId, boolean contractVerified,
                              boolean userConfirmed, boolean targetIdentityVerified) {
        return operationId != null
                && !isForbiddenBuddyLifecycle(operationId)
                && enabledOperationIds.contains(operationId)
                && contractVerified
                && userConfirmed
                && targetIdentityVerified;
    }

    private static boolean isForbiddenBuddyLifecycle(String operationId) {
        return "buddy.start".equals(operationId)
                || "buddy.stop".equals(operationId)
                || "buddy.restart".equals(operationId);

    }

    public static CommandGate locked() {
        return new CommandGate(Collections.<String>emptySet());
    }
}
