package app.jiti.controlcenter.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecoverySupervisorTest {
    @Test public void automationIsOffByDefault() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        RecoverySupervisor.Plan plan = supervisor.plan("bridge",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "missing", 1000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.DISABLED, plan.action);
    }

    @Test public void networkLossNeverStartsOrRestartsAProfile() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        RecoverySupervisor.Plan plan = supervisor.plan("bridge",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "missing", 1000L, false, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.WAIT_FOR_NETWORK, plan.action);
        assertEquals(0, supervisor.attempts("bridge"));
    }

    @Test public void http403RequiresManualInterventionAndNeverRetries() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        RecoverySupervisor.Plan plan = supervisor.plan("bridge",
                RecoveryErrorClassifier.fromHttpStatus(403), "http_403_policy", 1000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.MANUAL_REQUIRED, plan.action);
        assertEquals(0, supervisor.attempts("bridge"));
    }

    @Test public void http429SchedulesOnlyAHealthRecheckNotAProcessRestart() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        RecoverySupervisor.Plan plan = supervisor.plan("bridge",
                RecoveryErrorClassifier.fromHttpStatus(429), "http_429", 1000L, true, 120000L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECHECK_ONLY, plan.action);
        assertEquals(120000L, plan.delayMillis);
        assertEquals(0, supervisor.attempts("bridge"));
    }

    @Test public void transientControlPlaneFailureSchedulesRecheckWithoutRestart() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        RecoverySupervisor.Plan first = supervisor.plan("bridge",
                RecoveryPolicy.ErrorClass.TRANSIENT, "http_503", 1000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECHECK_ONLY, first.action);
        assertEquals(5000L, first.delayMillis);
        assertEquals(0, first.attempts);

        RecoverySupervisor.Plan duringCooldown = supervisor.plan("bridge",
                RecoveryPolicy.ErrorClass.TRANSIENT, "http_503", 2000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.WAIT_FOR_COOLDOWN, duringCooldown.action);
        assertEquals(0, supervisor.attempts("bridge"));
    }

    @Test public void retryAfterIsCappedAndNeverConsumesRestartBudget() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        RecoverySupervisor.Plan plan = supervisor.plan("bridge",
                RecoveryPolicy.ErrorClass.RATE_LIMITED, "http_429",
                1000L, true, 9_999_999L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECHECK_ONLY, plan.action);
        assertEquals(900000L, plan.delayMillis);
        assertEquals(0, supervisor.attempts("bridge"));
    }

    @Test public void repeatedIdenticalProcessFailuresOpenCircuit() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        RecoverySupervisor.Plan firstObservation = supervisor.plan("droidmcp",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 1000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.WAIT_FOR_COOLDOWN, firstObservation.action);

        RecoverySupervisor.Plan firstAttempt = supervisor.plan("droidmcp",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 62000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECOVER, firstAttempt.action);
        assertEquals(1, firstAttempt.attempts);

        RecoverySupervisor.Plan secondAttempt = supervisor.plan("droidmcp",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 124000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECOVER, secondAttempt.action);
        assertEquals(2, secondAttempt.attempts);

        RecoverySupervisor.Plan circuitOpen = supervisor.plan("droidmcp",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 186000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.CIRCUIT_OPEN, circuitOpen.action);
        assertEquals(2, supervisor.attempts("droidmcp"));
    }

    @Test public void successfulManualRecoveryResetsRetryBudget() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        supervisor.plan("rea-android", RecoveryPolicy.ErrorClass.PROCESS_MISSING,
                "process_missing", 1000L, true, 0L, 0.5);
        supervisor.recordSuccess("rea-android");
        assertEquals(0, supervisor.attempts("rea-android"));
        supervisor.plan("rea-android", RecoveryPolicy.ErrorClass.PROCESS_MISSING,
                "process_missing", 20000L, true, 0L, 0.5);
        RecoverySupervisor.Plan next = supervisor.plan("rea-android",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 82000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECOVER, next.action);
    }

    @Test public void retryBudgetAndCooldownSurviveSupervisorRecreation() {
        RecoverySupervisor first = new RecoverySupervisor();
        first.setEnabled(true);
        first.plan("bridge", RecoveryPolicy.ErrorClass.PROCESS_MISSING,
                "process_missing", 1000L, true, 0L, 0.5);
        first.plan("bridge", RecoveryPolicy.ErrorClass.PROCESS_MISSING,
                "process_missing", 62000L, true, 0L, 0.5);
        RecoverySupervisor.Snapshot snapshot = first.snapshot("bridge");

        RecoverySupervisor restored = new RecoverySupervisor();
        restored.restore("bridge", snapshot);
        restored.setEnabled(true);
        RecoverySupervisor.Plan plan = restored.plan("bridge",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing",
                2000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.WAIT_FOR_COOLDOWN, plan.action);
        assertEquals(1, plan.attempts);
    }

    @Test public void retryBudgetExhaustsAcrossSupervisorRecreation() {
        RecoverySupervisor first = new RecoverySupervisor();
        first.setEnabled(true);
        first.plan("bridge", RecoveryPolicy.ErrorClass.PROCESS_MISSING,
                "missing-a", 1000L, true, 0L, 0.5);
        first.plan("bridge", RecoveryPolicy.ErrorClass.PROCESS_MISSING,
                "missing-a", 62000L, true, 0L, 0.5);
        first.plan("bridge", RecoveryPolicy.ErrorClass.TRANSIENT,
                "missing-b", 124000L, true, 0L, 0.5);
        RecoverySupervisor restored = new RecoverySupervisor();
        restored.restore("bridge", first.snapshot("bridge"));
        restored.setEnabled(true);
        RecoverySupervisor.Plan exhausted = restored.plan("bridge",
                RecoveryPolicy.ErrorClass.TRANSIENT, "missing-c",
                200000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.EXHAUSTED, exhausted.action);
    }

    @Test(expected = IllegalArgumentException.class)
    public void buddyIsNeverAnAutomaticRecoveryTarget() {
        new RecoverySupervisor().plan("buddy",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "missing", 1000L, true, 0L, 0.5);
    }
}
