package app.buddy.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives the result callback for a command explicitly dispatched through Termux RUN_COMMAND. */
public final class TermuxCommandResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String commandId = intent.getStringExtra(TermuxRuntimeController.EXTRA_COMMAND_ID);
        if (commandId == null || !commandId.matches("[a-z_]{1,32}")) {
            String action = intent.getAction();
            commandId = action != null && action.contains(".install.") ? "install"
                    : action != null && action.contains(".reconcile.") ? "reconcile"
                    : action != null && action.contains(".status.") ? "status"
                    : action != null && action.contains(".stop.") ? "stop" : "unknown";
        }
        Bundle result = intent.getBundleExtra("result");
        int exitCode = result == null ? -1 : result.getInt("exitCode", -1);
        String stdout = result == null ? "" : result.getString("stdout", "");
        String stderr = result == null ? "" : result.getString("stderr", "");
        String error = result == null ? "Termux did not return a command result bundle." : result.getString("errmsg", "");
        if (stdout.length() > 4000) stdout = stdout.substring(stdout.length() - 4000);
        if (stderr.length() > 2000) stderr = stderr.substring(stderr.length() - 2000);
        if (error.length() > 1000) error = error.substring(0, 1000);

        android.content.SharedPreferences prefs = context.getSharedPreferences("runtime", Context.MODE_PRIVATE);
        prefs.edit()
                .putString("termux_last_command", commandId)
                .putInt("termux_last_exit_code", exitCode)
                .putString("termux_last_stdout", stdout)
                .putString("termux_last_stderr", stderr)
                .putString("termux_last_error", error)
                .putLong("termux_last_result_at", System.currentTimeMillis())
                .apply();

        if ("install".equals(commandId) && exitCode == 0) {
            String expectedHash = prefs.getString("termux_manager_expected_hash", "");
            prefs.edit()
                    .putBoolean("termux_manager_installed", true)
                    .putString("termux_manager_installed_hash", expectedHash)
                    .apply();
            BuddyRuntimeService.termuxManagerReady(context);
        }
    }
}
