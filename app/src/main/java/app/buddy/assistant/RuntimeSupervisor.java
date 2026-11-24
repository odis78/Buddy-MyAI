package app.buddy.assistant;

import android.content.Context;

import java.io.File;
import java.util.concurrent.TimeUnit;

/** Owns one exact Process handle; it never kills a process by a stale PID alone. */
final class RuntimeSupervisor {
    private static final long START_TIMEOUT_MS = 30000;
    private static final long POLL_INTERVAL_MS = 500;
    private static final long STOP_TIMEOUT_MS = 5000;

    private final Context app;
    private final RuntimeStateMachine machine = new RuntimeStateMachine();
    private Process tunnelProcess;
    private File runtimeDir;
    private String lastError = "";

    RuntimeSupervisor(Context context) {
        this.app = context.getApplicationContext();
        this.runtimeDir = new File(app.getFilesDir(), "runtime/tunnel-client");
    }

    synchronized boolean start() throws Exception {
        if (machine.state() == RuntimeStateMachine.State.READY
                && tunnelProcess != null && tunnelProcess.isAlive()
                && RuntimeHealthProbe.isReady(new File(runtimeDir, "health.url"))) {
            return true;
        }
        if (machine.state() == RuntimeStateMachine.State.READY
                || machine.state() == RuntimeStateMachine.State.UNHEALTHY
                || machine.state() == RuntimeStateMachine.State.STARTING) {
            if (machine.state() != RuntimeStateMachine.State.UNHEALTHY) {
                machine.transition(RuntimeStateMachine.State.UNHEALTHY);
            }
            stopInternal();
        } else if (machine.state() == RuntimeStateMachine.State.STOPPING) {
            stopInternal();
        }

        machine.transition(RuntimeStateMachine.State.STARTING);
        if (!RuntimeHealthProbe.isBuddyMcpAvailable()) {
            lastError = "Buddy MCP listener on 127.0.0.1:8766 is not active";
            machine.transition(RuntimeStateMachine.State.UNHEALTHY);
            stopInternal();
            return false;
        }
        try {
            tunnelProcess = RuntimeProcessLauncher.startConfigured(app);
            long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (tunnelProcess == null || !tunnelProcess.isAlive()) break;
                if (RuntimeHealthProbe.isReady(new File(runtimeDir, "health.url"))) {
                    RuntimeProcessLauncher.cleanupSecretFiles(runtimeDir);
                    machine.transition(RuntimeStateMachine.State.READY);
                    lastError = "";
                    return true;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            lastError = tunnelProcess != null && !tunnelProcess.isAlive()
                    ? "Tunnel Client exited before readiness" : "Tunnel Client readiness timeout";
            machine.transition(RuntimeStateMachine.State.UNHEALTHY);
            stopInternal();
            return false;
        } catch (Exception e) {
            lastError = safeMessage(e);
            if (machine.state() == RuntimeStateMachine.State.STARTING) {
                machine.transition(RuntimeStateMachine.State.UNHEALTHY);
            }
            stopInternal();
            throw e;
        }
    }

    synchronized boolean isReady() {
        boolean ready = tunnelProcess != null && tunnelProcess.isAlive()
                && RuntimeHealthProbe.isBuddyMcpAvailable()
                && RuntimeHealthProbe.isReady(new File(runtimeDir, "health.url"));
        if (!ready && machine.state() == RuntimeStateMachine.State.READY) {
            machine.transition(RuntimeStateMachine.State.UNHEALTHY);
            lastError = "Tunnel Client health check failed";
        }
        return ready;
    }

    synchronized void stop() {
        if (machine.state() == RuntimeStateMachine.State.STOPPED) {
            RuntimeProcessLauncher.cleanupSecretFiles(runtimeDir);
            return;
        }
        stopInternal();
    }

    synchronized RuntimeStateMachine.State state() { return machine.state(); }
    synchronized String lastError() { return lastError; }

    private void stopInternal() {
        RuntimeStateMachine.State current = machine.state();
        if (current != RuntimeStateMachine.State.STOPPING
                && current != RuntimeStateMachine.State.STOPPED) {
            machine.transition(RuntimeStateMachine.State.STOPPING);
        }
        if (tunnelProcess != null) {
            try {
                tunnelProcess.destroy();
                if (!tunnelProcess.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    tunnelProcess.destroyForcibly();
                    tunnelProcess.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                tunnelProcess.destroyForcibly();
            } catch (Exception ignored) {
                tunnelProcess.destroyForcibly();
            }
            tunnelProcess = null;
        }
        RuntimeProcessLauncher.cleanupSecretFiles(runtimeDir);
        if (machine.state() == RuntimeStateMachine.State.STOPPING) {
            machine.transition(RuntimeStateMachine.State.STOPPED);
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
