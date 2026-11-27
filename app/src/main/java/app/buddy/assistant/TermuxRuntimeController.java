package app.buddy.assistant;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Narrow, fixed-command adapter for Termux's documented RUN_COMMAND service. */
final class TermuxRuntimeController {
    static final String PACKAGE = "com.termux";
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    static final String ACTION = "com.termux.RUN_COMMAND";
    static final String EXTRA_COMMAND_ID = "buddy_command_id";
    private static final String SERVICE = "com.termux.app.RunCommandService";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final AtomicInteger REQUESTS = new AtomicInteger(8400);

    private TermuxRuntimeController() { }

    static boolean isInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    static boolean hasPermission(Context context) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static void installManager(Context context) {
        try {
            byte[] script = readAsset(context, "runtime/buddy-termux-tunnels.sh");
            String encoded = Base64.encodeToString(script, Base64.NO_WRAP);
            String command = "set -eu; mkdir -p \"$HOME/bin\"; "
                    + "target=\"$HOME/bin/buddy-tunnels\"; tmp=\"$target.installing\"; "
                    + "printf '%s' '" + encoded + "' | base64 -d > \"$tmp\"; chmod 700 \"$tmp\"; "
                    + "if [ -e \"$target\" ]; then "
                    + "if cmp -s \"$tmp\" \"$target\"; then rm -f \"$tmp\"; echo BUDDY_MANAGER_ALREADY_INSTALLED; "
                    + "else rm -f \"$tmp\"; echo BUDDY_MANAGER_CONFLICT; exit 3; fi; "
                    + "else mv \"$tmp\" \"$target\"; echo BUDDY_MANAGER_INSTALLED; fi";
            dispatch(context, "install", command);
        } catch (Exception e) {
            recordDispatchError(context, "install", e);
        }
    }

    static void reconcile(Context context, boolean bridgeEnabled, boolean droidEnabled) {
        String bridge = bridgeEnabled ? "on" : "off";
        String droid = droidEnabled ? "on" : "off";
        dispatch(context, "reconcile", "exec \"$HOME/bin/buddy-tunnels\" reconcile " + bridge + " " + droid);
    }

    static void stop(Context context) {
        dispatch(context, "stop", "exec \"$HOME/bin/buddy-tunnels\" stop all");
    }

    static void status(Context context) {
        dispatch(context, "status", "exec \"$HOME/bin/buddy-tunnels\" status");
    }

    private static void dispatch(Context context, String commandId, String command) {
        if (!isInstalled(context)) {
            recordError(context, commandId, "Termux is not installed.");
            return;
        }
        if (!hasPermission(context)) {
            recordError(context, commandId, "Grant Buddy the Termux RUN_COMMAND permission first.");
            return;
        }
        try {
            Intent callback = new Intent(context, TermuxCommandResultReceiver.class)
                    .setAction("app.buddy.assistant.TERMUX_RESULT." + commandId + "." + REQUESTS.get());
            callback.putExtra(EXTRA_COMMAND_ID, commandId);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent result = PendingIntent.getBroadcast(context, REQUESTS.incrementAndGet(),
                    callback, flags);

            Intent intent = new Intent(ACTION)
                    .setComponent(new ComponentName(PACKAGE, SERVICE))
                    .putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH)
                    .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command})
                    .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                    .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result)
                    .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Buddy tunnel manager: " + commandId)
                    .putExtra(EXTRA_COMMAND_ID, commandId);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception e) {
            recordDispatchError(context, commandId, e);
        }
    }

    private static byte[] readAsset(Context context, String path) throws Exception {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void recordDispatchError(Context context, String commandId, Exception error) {
        recordError(context, commandId, error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static void recordError(Context context, String commandId, String message) {
        context.getSharedPreferences("runtime", Context.MODE_PRIVATE).edit()
                .putString("termux_last_command", commandId)
                .putInt("termux_last_exit_code", -1)
                .putString("termux_last_error", message == null ? "Termux command failed." : message)
                .putLong("termux_last_result_at", System.currentTimeMillis())
                .apply();
    }
}
