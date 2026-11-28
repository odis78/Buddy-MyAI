package app.buddy.assistant;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Best-effort boot restart for a runtime the user explicitly enabled for auto-start. */
public final class BuddyRuntimeBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Context app = context.getApplicationContext();
        boolean autoStart = app.getSharedPreferences("runtime", Context.MODE_PRIVATE)
                .getBoolean("auto_start", false);
        if (!autoStart) return; // Buddy can still initialize its internal MCP helpers after the API key is configured.
        try {
            BuddyRuntimeService.start(app);
        } catch (RuntimeException e) {
            Log.w("BuddyRuntime", "Boot auto-start was not permitted: " + e.getClass().getSimpleName());
        }
    }
}
