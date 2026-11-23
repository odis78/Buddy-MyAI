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
        if (!state.installed || !state.permissionGranted) {
            deliver(callback, new TermuxCommandResult(0, "", "", -1, 0, state.message, false));
            return;
        }
        int requestId = 0;
        try {
            String prefix = state.dataDir + "/files/usr";
            String home = state.dataDir + "/files/home";
            TermuxCommandSpec spec = TermuxCommandSpec.forOperation(operation, prefix, home);
            if (!spec.isSafeFixedCommand()) throw new SecurityException("Command rejected by fixed-operation policy");

            requestId = NEXT_ID.incrementAndGet();
            if (callback != null) CALLBACKS.put(requestId, callback);

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
            context.startService(intent);
        } catch (Exception e) {
            if (requestId != 0) CALLBACKS.remove(requestId);
            deliver(callback, new TermuxCommandResult(requestId, "", "", -1, 0,
                    "Termux command could not be started: " + e.getClass().getSimpleName(), false));
        }
    }

    static void deliver(TermuxCommandResult result) {
        Callback callback = CALLBACKS.remove(result.executionId);
        deliver(callback, result);
    }

    private static void deliver(Callback callback, TermuxCommandResult result) {
        if (callback != null) MAIN.post(() -> callback.onResult(result));
    }

    private static String label(TermuxOperation operation) {
        switch (operation) {
            case IDENTITY: return "JITI: Termux identity check";
            case TUNNEL_CLIENT_VERSION: return "JITI: Tunnel Client version check";
            case PROCESS_LIST: return "JITI: Android process list";
            default: return "JITI read-only diagnostic";
        }
    }
}
