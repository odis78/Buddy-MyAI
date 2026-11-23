package app.jiti.controlcenter.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import app.jiti.controlcenter.MainActivity;
import app.jiti.controlcenter.core.DiagnosticEvent;
import app.jiti.controlcenter.core.ComponentStatus;
import app.jiti.controlcenter.core.RuntimeSnapshot;
import app.jiti.controlcenter.storage.EventStore;
import app.jiti.controlcenter.termux.TermuxCommandClient;
import app.jiti.controlcenter.termux.TermuxCommandResult;
import app.jiti.controlcenter.termux.TermuxOperation;

public final class MonitorService extends Service {
    public static final String ACTION_STOP = "app.jiti.controlcenter.action.STOP_MONITOR";
    private static final String CHANNEL_ID = "jiti_monitor";
    private static final int NOTIFICATION_ID = 4101;
    private static final long POLL_INTERVAL_MS = 60000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;

    public static volatile boolean running = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EventStore eventStore;
    private PowerManager.WakeLock wakeLock;
    private boolean monitoring;
    private boolean inFlight;
    private String lastHealthState = "";
    private String lastMcpState = "";
    private String lastGateState = "";
    private String notificationText = "Ожидание первой проверки";

    private final Runnable poll = this::pollOnce;

    @Override public void onCreate() {
        super.onCreate();
        eventStore = new EventStore(this);
        createNotificationChannel();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if ((Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                || notificationManager == null || !notificationManager.areNotificationsEnabled()) {
            running = false;
            monitoring = false;
            handler.removeCallbacks(poll);
            releaseWakeLock();
            log("system", "BLOCKED", "POST_NOTIFICATIONS permission is not granted");
            saveSnapshot("jiti-monitor-service", ComponentStatus.BLOCKED, "Notification permission required", 0);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!monitoring) {
            monitoring = true;
            running = true;
            startAsForeground();
            log("system", "MONITOR_STARTED", "User started read-only monitoring");
            saveSnapshot("jiti-monitor-service", ComponentStatus.RUNNING, "Foreground monitor active", 0);
            handler.removeCallbacks(poll);
            handler.post(poll);
        } else {
            startAsForeground();
        }
        return START_STICKY;
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        renewWakeLock();
    }

    private void pollOnce() {
        if (!monitoring) return;
        renewWakeLock();
        saveSnapshot("jiti-monitor-service", ComponentStatus.RUNNING, "Foreground monitor active", 0);
        saveSnapshot("jiti-control-center", ComponentStatus.RUNNING, "Monitor process active", 0);
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications == null || !notifications.areNotificationsEnabled()
                || (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            log("system", "BLOCKED", "Notification permission or app notification channel was disabled");
            stopMonitoring();
            return;
        }
        if (inFlight) {
            scheduleNext();
            return;
        }
        TermuxCommandClient.InstallState install = TermuxCommandClient.inspect(this);
        if (!install.canInvoke()) {
            if (!install.message.equals(lastGateState)) {
                lastGateState = install.message;
                log("termux", "BLOCKED", install.message);
                saveSnapshot("termux-run-command", ComponentStatus.BLOCKED, install.message, 0);
            }
            notificationText = "Termux setup required";
            updateNotification();
            scheduleNext();
            return;
        }
        if (!lastGateState.isEmpty()) {
            log("termux", "READY", "RUN_COMMAND permission and result callback gate passed");
            lastGateState = "";
        }
        saveSnapshot("termux-run-command", ComponentStatus.AVAILABLE, "RUN_COMMAND permission and version gate passed", 0);
        inFlight = true;
        TermuxCommandClient.run(this, TermuxOperation.BUDDY_TUNNEL_HEALTH, this::onHealthResult);
    }

    private void onHealthResult(TermuxCommandResult result) {
        boolean ready = false;
        String summary = "health command exit=" + result.exitCode + " termuxErr=" + result.internalError;
        try {
            JSONObject report = new JSONObject(result.stdout);
            ready = result.succeeded() && "ok".equals(report.optString("result"));
            summary = "healthz=" + ok(report, "healthz")
                    + " readyz=" + ok(report, "readyz")
                    + " controlPlanePoll=" + nestedOk(report, "control_plane_poll")
                    + " processRunning=" + nestedOk(report, "process");
        } catch (Exception ignored) {
            if (!result.stderr.isEmpty()) summary += " " + result.stderr;
        }
        saveSnapshot("termux", result.internalError == -1 ? ComponentStatus.RUNNING : ComponentStatus.UNAVAILABLE,
                "Termux RUN_COMMAND result received; exit=" + result.exitCode, 0);
        int tunnelPid = extractPid(result.stdout);
        saveSnapshot("buddy-tunnel", ready ? ComponentStatus.READY : ComponentStatus.NOT_READY, summary, tunnelPid);
        saveSnapshot("tunnel-client", processRunning(result.stdout) ? ComponentStatus.RUNNING : ComponentStatus.STOPPED,
                "Tunnel Client health PID check; executable/config identity not yet verified", tunnelPid);
        String state = ready ? "READY" : "NOT_READY";
        if (!state.equals(lastHealthState)) {
            lastHealthState = state;
            log("buddy-tunnel", state, summary);
        }
        notificationText = ready ? "Buddy tunnel: READY" : "Buddy tunnel: NOT READY";
        updateNotification();

        TermuxCommandClient.run(this, TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE, this::onMcpChallenge);
    }

    private void onMcpChallenge(TermuxCommandResult result) {
        String code = result.stdout == null ? "" : result.stdout.trim();
        String state;
        if (result.succeeded() && "401".equals(code)) state = "LISTENER_UP_AUTH_REQUIRED";
        else if (result.succeeded() && "200".equals(code)) state = "UNAUTHENTICATED_200_WARNING";
        else state = "LISTENER_UNCONFIRMED";
        saveSnapshot("termux-curl", result.internalError == -1 && result.exitCode != 127
                ? ComponentStatus.AVAILABLE : ComponentStatus.UNAVAILABLE,
                "curl challenge execution exit=" + result.exitCode, 0);
        ComponentStatus mcpStatus = "LISTENER_UP_AUTH_REQUIRED".equals(state) ? ComponentStatus.RUNNING
                : "UNAUTHENTICATED_200_WARNING".equals(state) ? ComponentStatus.ERROR : ComponentStatus.NOT_READY;
        saveSnapshot("buddy-mcp", mcpStatus, "Unauthenticated loopback challenge HTTP " + (code.isEmpty() ? "no-response" : code), 0);
        if (!state.equals(lastMcpState)) {
            lastMcpState = state;
            log("buddy-mcp", state, "Unauthenticated loopback challenge HTTP " + (code.isEmpty() ? "no-response" : code));
        }
        if ("LISTENER_UP_AUTH_REQUIRED".equals(state)) {
            notificationText = lastHealthState.equals("READY")
                    ? "Buddy tunnel READY • MCP listener up" : "MCP listener up • tunnel not ready";
        } else if ("UNAUTHENTICATED_200_WARNING".equals(state)) {
            notificationText = "Warning: MCP returned HTTP 200 without auth";
        } else {
            notificationText = "Tunnel " + lastHealthState + " • MCP listener unconfirmed";
        }
        updateNotification();
        if ("NOT_READY".equals(lastHealthState)) {
            TermuxCommandClient.run(this, TermuxOperation.PROCESS_LIST, this::onProcessSnapshot);
        } else {
            inFlight = false;
            scheduleNext();
        }
    }

    private void onProcessSnapshot(TermuxCommandResult result) {
        String text = result.stdout == null ? "" : result.stdout.toLowerCase(java.util.Locale.ROOT);
        boolean tunnelSeen = text.contains("tunnel-client");
        log("buddy-tunnel", "PROCESS_SNAPSHOT",
                "tunnelClientSeen=" + tunnelSeen
                        + " termuxSeen=" + text.contains("termux")
                        + " buddySeen=" + text.contains("buddy"));
        saveSnapshot("tunnel-client", tunnelSeen ? ComponentStatus.RUNNING : ComponentStatus.STOPPED,
                "Process-name snapshot; PID identity not yet verified", 0);
        inFlight = false;
        scheduleNext();
    }

    private boolean processRunning(String json) {
        try {
            JSONObject report = new JSONObject(json);
            JSONObject process = report.optJSONObject("process");
            return process != null && process.optBoolean("running");
        } catch (Exception ignored) { return false; }
    }

    private int extractPid(String json) {
        try {
            JSONObject report = new JSONObject(json);
            JSONObject process = report.optJSONObject("process");
            return process == null ? 0 : process.optInt("pid", 0);
        } catch (Exception ignored) { return 0; }
    }

    private void saveSnapshot(String id, ComponentStatus status, String detail, int pid) {
        try {
            RuntimeSnapshot previous = eventStore == null ? null : eventStore.snapshot(id);
            int errors = previous == null ? 0 : previous.errorCount;
            int retries = previous == null ? 0 : previous.retryCount;
            if (status == ComponentStatus.ERROR || status == ComponentStatus.NOT_READY
                    || status == ComponentStatus.BLOCKED || status == ComponentStatus.UNAVAILABLE) errors++;
            eventStore.saveSnapshot(new RuntimeSnapshot(id, status, System.currentTimeMillis(),
                    pid, errors, retries, detail));
        } catch (Exception ignored) { }
    }

    private boolean ok(JSONObject parent, String key) {
        JSONObject child = parent.optJSONObject(key);
        return child != null && child.optBoolean("ok");
    }

    private String nestedOk(JSONObject parent, String key) {
        JSONObject child = parent.optJSONObject(key);
        return child == null ? "not-reported" : Boolean.toString(child.optBoolean("ok"));
    }

    private void scheduleNext() {
        if (monitoring) {
            renewWakeLock();
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, POLL_INTERVAL_MS);
        }
    }

    private void log(String component, String type, String message) {
        try {
            if (eventStore != null) eventStore.append(new DiagnosticEvent(
                    System.currentTimeMillis(), component, type, message));
        } catch (Exception ignored) {
            // Logging failure must never crash the monitor.
        }
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 4102, open,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Intent stop = new Intent(this, MonitorService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 4103, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("JITI Control Center")
                .setContentText(notificationText)
                .setSubText("CPU wake lock active • stop to release")
                .setContentIntent(content)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_media_pause, "Остановить", stopIntent)
                .build();
    }

    private int immutableFlag() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "JITI monitoring", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Persistent status for user-started local infrastructure monitoring");
        manager.createNotificationChannel(channel);
    }

    private void renewWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager power = getSystemService(PowerManager.class);
                if (power == null) return;
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "app.jiti.controlcenter:monitor");
                wakeLock.setReferenceCounted(false);
            }
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        } catch (Exception e) {
            log("system", "WAKE_LOCK_UNAVAILABLE", e.getClass().getSimpleName());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) { }
    }

    private void stopMonitoring() {
        monitoring = false;
        running = false;
        handler.removeCallbacks(poll);
        releaseWakeLock();
        log("system", "MONITOR_STOPPED", "User stopped monitoring");
        saveSnapshot("jiti-monitor-service", ComponentStatus.STOPPED, "User stopped foreground monitor", 0);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        // Keep the foreground monitor active when the app task is swiped away.
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        monitoring = false;
        running = false;
        handler.removeCallbacksAndMessages(null);
        releaseWakeLock();
        if (eventStore != null) eventStore.close();
        super.onDestroy();
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }
}
