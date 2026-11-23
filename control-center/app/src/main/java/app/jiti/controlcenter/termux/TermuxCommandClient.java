package app.jiti.controlcenter.termux;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class TermuxCommandClient {
    public static final String TERMUX_PACKAGE = "com.termux";
    private static final String RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    private static final String EXTRA_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION";
    private static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    public interface Callback { void onResult(TermuxCommandResult result); }

    public static final class InstallState {
        public final boolean installed;
        public final boolean permissionGranted;
        public final String versionName;
        public final String dataDir;
        public final String message;
        InstallState(boolean installed, boolean permissionGranted, String versionName, String dataDir, String message) {
            this.installed = installed;
            this.permissionGranted = permissionGranted;
            this.versionName = versionName;
            this.dataDir = dataDir;
            this.message = message;
        }
        public boolean versionSupportsResultCallback() {
            try {
                String[] parts = versionName.split("\\.");
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1].replaceAll("[^0-9].*$", ""));
                return major > 0 || minor >= 109;
            } catch (Exception ignored) { return false; }
        }
        public boolean canInvoke() { return installed && permissionGranted && versionSupportsResultCallback(); }
    }

    private static final AtomicInteger NEXT_ID = new AtomicInteger(1000);
    private static final ConcurrentHashMap<Integer, Callback> CALLBACKS = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<Integer, Runnable> TIMEOUTS = new ConcurrentHashMap<>();
    private static final long COMMAND_TIMEOUT_MS = 30000;

    private TermuxCommandClient() {}

    public static InstallState inspect(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo app = pm.getApplicationInfo(TERMUX_PACKAGE, 0);
            PackageInfo info = pm.getPackageInfo(TERMUX_PACKAGE, 0);
            boolean permission = context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
            InstallState state = new InstallState(true, permission, info.versionName, app.dataDir, "");
            String message = !permission ? "Требуется RUN_COMMAND permission"
                    : !state.versionSupportsResultCallback() ? "Termux version must be >= 0.109 for result callbacks"
                    : "Termux установлен; RUN_COMMAND permission granted";
            return new InstallState(true, permission, info.versionName, app.dataDir, message);
        } catch (PackageManager.NameNotFoundException e) {
            return new InstallState(false, false, "", "", "Пакет com.termux не найден");
        } catch (Exception e) {
            return new InstallState(false, false, "", "", "Не удалось проверить Termux: " + e.getClass().getSimpleName());
        }
    }

    public static void run(Context context, TermuxOperation operation, Callback callback) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        try {
            String prefix = state.dataDir + "/files/usr";
            String home = state.dataDir + "/files/home";
            runSpec(context, state, TermuxCommandSpec.forOperation(operation, prefix, home), operation, callback);
        } catch (Exception e) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0,
                    "Termux command rejected: " + e.getClass().getSimpleName(), false));
        }
    }

    public static void runBuddyLaunchPreflight(Context context, Callback callback) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        try {
            String prefix = state.dataDir + "/files/usr";
            TermuxCommandSpec spec = TermuxCommandSpec.forBuddyLaunch(prefix, "preflight");
            runSpec(context, state, spec, TermuxOperation.BUDDY_LAUNCH, callback);
        } catch (Exception e) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0,
                    "Buddy launch preflight rejected: " + e.getClass().getSimpleName(), false));
        }
    }

    public static String startBuddyTunnel(Context context) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) return state.message;
        try {
            String prefix = state.dataDir + "/files/usr";
            TermuxCommandSpec spec = TermuxCommandSpec.forBuddyLaunch(prefix, "start");
            startBackgroundTask(context, state, spec,
                    "JITI: start Buddy Tunnel Client", "Validated Buddy profile background task");
            return "";
        } catch (Exception e) {
            return "Buddy Tunnel Client start request failed: " + e.getClass().getSimpleName();
        }
    }

    public static void runBuddyLaunchStatus(Context context, Callback callback) {
        run(context, TermuxOperation.BUDDY_LAUNCH_STATUS, callback);
    }

    public static void runBuddyStopPreflight(Context context, Callback callback) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        try {
            String prefix = state.dataDir + "/files/usr";
            TermuxCommandSpec spec = TermuxCommandSpec.forBuddyStop(prefix, "preflight");
            runSpec(context, state, spec, TermuxOperation.BUDDY_STOP, callback);
        } catch (Exception e) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0,
                    "Buddy stop preflight rejected: " + e.getClass().getSimpleName(), false));
        }
    }

    public static void runBuddyStop(Context context, Callback callback) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        try {
            String prefix = state.dataDir + "/files/usr";
            TermuxCommandSpec spec = TermuxCommandSpec.forBuddyStop(prefix, "stop");
            runSpec(context, state, spec, TermuxOperation.BUDDY_STOP, callback);
        } catch (Exception e) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0,
                    "Buddy stop request rejected: " + e.getClass().getSimpleName(), false));
        }
    }

    private static void startBackgroundTask(Context context, InstallState state, TermuxCommandSpec spec,
                                            String commandLabel, String description) {
        if (!spec.isSafeFixedCommand()) throw new SecurityException("Command rejected by fixed-operation policy");
        Intent intent = new Intent();
        intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
        intent.setAction(ACTION_RUN_COMMAND);
        intent.putExtra(EXTRA_PATH, spec.executable);
        intent.putExtra(EXTRA_ARGUMENTS, spec.arguments.toArray(new String[0]));
        intent.putExtra(EXTRA_WORKDIR, state.dataDir + "/files/home");
        intent.putExtra(EXTRA_BACKGROUND, true);
        intent.putExtra(EXTRA_LABEL, commandLabel);
        intent.putExtra(EXTRA_DESCRIPTION, description);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void runBuddyMcpAuthProbe(Context context, int pid, Callback callback) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        try {
            String prefix = state.dataDir + "/files/usr";
            TermuxCommandSpec spec = TermuxCommandSpec.forBuddyMcpAuthProbe(prefix, pid);
            runSpec(context, state, spec, TermuxOperation.BUDDY_MCP_AUTH_PROBE, callback);
        } catch (Exception e) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0,
                    "Authenticated MCP probe rejected: " + e.getClass().getSimpleName(), false));
        }
    }

    public static void runProcessExecutable(Context context, int pid, Callback callback) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        try {
            TermuxCommandSpec spec = TermuxCommandSpec.forProcessExecutable(pid);
            runSpec(context, state, spec, TermuxOperation.PROCESS_EXECUTABLE, callback);
        } catch (Exception e) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0,
                    "Executable identity probe rejected: " + e.getClass().getSimpleName(), false));
        }
    }

    public static void runProcessCmdline(Context context, int pid, Callback callback) {
        InstallState state = inspect(context);
        if (!state.canInvoke()) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        try {
            TermuxCommandSpec spec = TermuxCommandSpec.forProcessCmdline(pid);
            runSpec(context, state, spec, TermuxOperation.PROCESS_CMDLINE, callback);
        } catch (Exception e) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0,
                    "Process identity probe rejected: " + e.getClass().getSimpleName(), false));
        }
    }

    private static void runSpec(Context context, InstallState state, TermuxCommandSpec spec,
                                TermuxOperation operation, Callback callback) {
        int requestId = 0;
        try {
            if (!spec.isSafeFixedCommand()) throw new SecurityException("Command rejected by fixed-operation policy");
            String home = state.dataDir + "/files/home";
            requestId = NEXT_ID.incrementAndGet();
            if (callback != null) {
                CALLBACKS.put(requestId, callback);
                final int timeoutId = requestId;
                Runnable timeout = () -> deliver(new TermuxCommandResult(timeoutId, "", "", -1, 0,
                        "Termux command timed out or Termux service did not return a result", false));
                TIMEOUTS.put(requestId, timeout);
                MAIN.postDelayed(timeout, COMMAND_TIMEOUT_MS);
            }

            Intent resultIntent = new Intent(context, TermuxResultService.class);
            resultIntent.putExtra(TermuxResultService.EXTRA_EXECUTION_ID, requestId);
            int pendingFlags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) pendingFlags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent resultPendingIntent = PendingIntent.getService(context, requestId, resultIntent, pendingFlags);

            Intent intent = new Intent();
            intent.setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE);
            intent.setAction(ACTION_RUN_COMMAND);
            intent.putExtra(EXTRA_PATH, spec.executable);
            intent.putExtra(EXTRA_ARGUMENTS, spec.arguments.toArray(new String[0]));
            intent.putExtra(EXTRA_WORKDIR, home);
            intent.putExtra(EXTRA_BACKGROUND, spec.background);
            intent.putExtra(EXTRA_LABEL, label(operation));
            intent.putExtra(EXTRA_DESCRIPTION, "JITI Control Center read-only diagnostic");
            intent.putExtra(EXTRA_PENDING_INTENT, resultPendingIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            if (requestId != 0) { CALLBACKS.remove(requestId); cancelTimeout(requestId); }
            deliver(callback, new TermuxCommandResult(requestId, "", "", -1, 0,
                    "Termux command could not be started: " + e.getClass().getSimpleName(), false));
        }
    }

    static void deliver(TermuxCommandResult result) {
        Callback callback = CALLBACKS.remove(result.executionId);
        cancelTimeout(result.executionId);
        deliver(callback, result);
    }

    private static void cancelTimeout(int id) {
        Runnable timeout = TIMEOUTS.remove(id);
        if (timeout != null) MAIN.removeCallbacks(timeout);
    }

    private static void deliver(Callback callback, TermuxCommandResult result) {
        if (callback != null) MAIN.post(() -> callback.onResult(result));
    }

    private static String label(TermuxOperation operation) {
        switch (operation) {
            case IDENTITY: return "JITI: Termux identity check";
            case TUNNEL_CLIENT_VERSION: return "JITI: Tunnel Client version check";
            case PROCESS_LIST: return "JITI: Android process list";
            case BUDDY_MCP_AUTH_PROBE: return "JITI: authenticated Buddy MCP initialize and tools/list";
            case BUDDY_LAUNCH: return "JITI: Buddy Tunnel Client preflight";
            case BUDDY_STOP: return "JITI: stop verified Buddy Tunnel Client";
            case BUDDY_LAUNCH_STATUS: return "JITI: read Buddy launcher status";
            case PROCESS_EXECUTABLE: return "JITI: verify Tunnel Client executable";
            case PROCESS_CMDLINE: return "JITI: verify Tunnel Client config identity";
            default: return "JITI read-only diagnostic";
        }
    }
}
