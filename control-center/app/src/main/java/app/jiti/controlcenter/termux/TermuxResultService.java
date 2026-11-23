package app.jiti.controlcenter.termux;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

public final class TermuxResultService extends Service {
    static final String EXTRA_EXECUTION_ID = "app.jiti.controlcenter.execution_id";
    private static final String EXTRA_RESULT_BUNDLE = "result";

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent == null) return START_NOT_STICKY;
            int executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, 0);
            Bundle bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE);
            if (bundle == null) {
                TermuxCommandClient.deliver(new TermuxCommandResult(executionId, "", "",
                        -1, 0, "Termux result bundle missing", false));
                return START_NOT_STICKY;
            }
            String stdout = bundle.getString("stdout", "");
            String stderr = bundle.getString("stderr", "");
            int exitCode = bundle.getInt("exitCode", -1);
            int internalError = bundle.getInt("err", -1);
            String internalErrorMessage = bundle.getString("errmsg", "");
            int outLength = parseLength(bundle.getString("stdout_original_length"));
            int errLength = parseLength(bundle.getString("stderr_original_length"));
            boolean truncated = outLength > stdout.length() || errLength > stderr.length();
            TermuxCommandClient.deliver(new TermuxCommandResult(executionId, stdout, stderr,
                    exitCode, internalError, internalErrorMessage, truncated));
        } finally {
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private int parseLength(String value) {
        try { return Integer.parseInt(value); }
        catch (Exception ignored) { return -1; }
    }
}
