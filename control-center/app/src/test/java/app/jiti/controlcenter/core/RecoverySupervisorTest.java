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

    @Test public void repeatedIdenticalProcessFailuresOpenCircuit() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        RecoverySupervisor.Plan first = supervisor.plan("droidmcp",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 1000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECOVER, first.action);
        assertEquals(1, first.attempts);

        RecoverySupervisor.Plan second = supervisor.plan("droidmcp",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 10000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.CIRCUIT_OPEN, second.action);
        assertEquals(1, supervisor.attempts("droidmcp"));
    }

    @Test public void successfulManualRecoveryResetsRetryBudget() {
        RecoverySupervisor supervisor = new RecoverySupervisor();
        supervisor.setEnabled(true);
        supervisor.plan("rea-android", RecoveryPolicy.ErrorClass.PROCESS_MISSING,
                "process_missing", 1000L, true, 0L, 0.5);
        supervisor.recordSuccess("rea-android");
        assertEquals(0, supervisor.attempts("rea-android"));
        RecoverySupervisor.Plan next = supervisor.plan("rea-android",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "process_missing", 20000L, true, 0L, 0.5);
        assertEquals(RecoverySupervisor.Action.RECOVER, next.action);
    }

    @Test(expected = IllegalArgumentException.class)
    public void buddyIsNeverAnAutomaticRecoveryTarget() {
        new RecoverySupervisor().plan("buddy",
                RecoveryPolicy.ErrorClass.PROCESS_MISSING, "missing", 1000L, true, 0L, 0.5);
    }
}
