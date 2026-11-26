package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class OperationCoordinatorTest {
    @Test public void automationIsDisabledUntilExplicitlyEnabled() {
        OperationCoordinator coordinator = new OperationCoordinator();
        assertEquals(OperationCoordinator.Status.REJECTED_AUTOMATION_DISABLED,
                coordinator.requestAutomatic("bridge").status);
        assertFalse(coordinator.isBusy());
    }

    @Test public void manualRequestQueuesAheadOfAnyFurtherAutomaticRecovery() {
        OperationCoordinator coordinator = new OperationCoordinator();
        coordinator.setAutomaticEnabled(true);
        OperationCoordinator.Submission automatic = coordinator.requestAutomatic("bridge");
        assertEquals(OperationCoordinator.Status.STARTED, automatic.status);

        OperationCoordinator.Submission manual = coordinator.requestManual("droidmcp", "profile.restart");
        assertEquals(OperationCoordinator.Status.QUEUED_AFTER_AUTOMATIC, manual.status);
        assertTrue(coordinator.shouldCancelActiveAutomatic());
        assertEquals(OperationCoordinator.Kind.AUTOMATIC, coordinator.activeRequest().kind);

        OperationCoordinator.Request next = coordinator.complete(automatic.request.id);
        assertNotNull(next);
        assertEquals(manual.request.id, next.id);
        assertEquals(OperationCoordinator.Kind.MANUAL, next.kind);
        assertEquals("droidmcp", next.componentId);
        assertEquals("profile.restart", next.operation);
        assertFalse(coordinator.shouldCancelActiveAutomatic());
    }

    @Test public void buddyCannotBeAnAutomaticRecoveryTarget() {
        OperationCoordinator coordinator = new OperationCoordinator();
        coordinator.setAutomaticEnabled(true);
        assertEquals(OperationCoordinator.Status.REJECTED_AUTOMATIC_TARGET,
                coordinator.requestAutomatic("buddy").status);
        assertFalse(coordinator.isBusy());
    }

    @Test public void noManualAndAutomaticOperationCanRunAtTheSameTime() {
        OperationCoordinator coordinator = new OperationCoordinator();
        OperationCoordinator.Submission manual = coordinator.requestManual("buddy");
        coordinator.setAutomaticEnabled(true);

        OperationCoordinator.Submission automatic = coordinator.requestAutomatic("bridge");
        assertEquals(OperationCoordinator.Status.REJECTED_MANUAL_IN_PROGRESS, automatic.status);
        assertEquals(manual.request.id, coordinator.activeRequest().id);
    }

    @Test public void duplicateManualRequestsAreRejectedWhileOneIsActiveOrQueued() {
        OperationCoordinator coordinator = new OperationCoordinator();
        coordinator.setAutomaticEnabled(true);
        OperationCoordinator.Submission automatic = coordinator.requestAutomatic("bridge");
        OperationCoordinator.Submission queued = coordinator.requestManual("droidmcp");
        assertEquals(OperationCoordinator.Status.QUEUED_AFTER_AUTOMATIC, queued.status);
        assertEquals(OperationCoordinator.Status.REJECTED_MANUAL_ALREADY_QUEUED,
                coordinator.requestManual("rea-android").status);

        OperationCoordinator.Request next = coordinator.complete(automatic.request.id);
        assertNotNull(next);
        assertEquals(OperationCoordinator.Status.REJECTED_MANUAL_IN_PROGRESS,
                coordinator.requestManual("rea-android").status);
    }

    @Test public void cancelAutomationStopsFutureRecoveryButPreservesQueuedManualCommand() {
        OperationCoordinator coordinator = new OperationCoordinator();
        coordinator.setAutomaticEnabled(true);
        OperationCoordinator.Submission automatic = coordinator.requestAutomatic("bridge");
        OperationCoordinator.Submission manual = coordinator.requestManual("rea-bridge");
        assertTrue(coordinator.cancelAutomatic());
        assertFalse(coordinator.isAutomaticEnabled());
        assertTrue(coordinator.shouldCancelActiveAutomatic());

        OperationCoordinator.Request next = coordinator.complete(automatic.request.id);
        assertNotNull(next);
        assertEquals(manual.request.id, next.id);
        assertEquals(OperationCoordinator.Kind.MANUAL, next.kind);
        assertEquals(OperationCoordinator.Status.REJECTED_AUTOMATION_DISABLED,
                coordinator.requestAutomatic("bridge").status);
    }

    @Test public void staleCompletionCannotReleaseAnotherOperation() {
        OperationCoordinator coordinator = new OperationCoordinator();
        OperationCoordinator.Submission first = coordinator.requestManual("buddy");
        assertNull(coordinator.complete(first.request.id + 100));
        assertTrue(coordinator.isBusy());
        assertEquals(first.request.id, coordinator.activeRequest().id);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidTargetIsRejected() {
        new OperationCoordinator().requestManual("../buddy");
    }
}
