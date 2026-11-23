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
import org.json.JSONArray;

import java.util.HashSet;
import java.util.Set;

import app.jiti.controlcenter.MainActivity;
import app.jiti.controlcenter.core.DiagnosticEvent;
import app.jiti.controlcenter.core.ComponentStatus;
import app.jiti.controlcenter.core.RuntimeSnapshot;
import app.jiti.controlcenter.core.ToolCatalog;
import app.jiti.controlcenter.core.ToolSpec;
import app.jiti.controlcenter.storage.EventStore;
import app.jiti.controlcenter.termux.TermuxCommandClient;
import app.jiti.controlcenter.termux.TermuxCommandResult;
import app.jiti.controlcenter.termux.TermuxOperation;
import app.jiti.controlcenter.termux.TunnelProcessIdentity;

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
    private long lastAuthProbeAtEpochMillis = 0L;
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
            if (!startAsForegroundSafely()) {
                monitoring = false;
                running = false;
                saveSnapshot("jiti-monitor-service", ComponentStatus.BLOCKED, "Android refused foreground service start", 0);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            log("system", "MONITOR_STARTED", "User started read-only monitoring");
            saveSnapshot("jiti-monitor-service", ComponentStatus.RUNNING, "Foreground monitor active", 0);
            handler.removeCallbacks(poll);
            handler.post(poll);
        } else if (!startAsForegroundSafely()) {
            monitoring = false;
            running = false;
            releaseWakeLock();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private boolean startAsForegroundSafely() {
        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            boolean wakeLockActive = renewWakeLock();
            if (!wakeLockActive) notificationText = "Monitor active; CPU wake lock unavailable";
            updateNotification();
            return true;
        } catch (Exception e) {
            log("system", "FOREGROUND_START_FAILED", e.getClass().getSimpleName());
            releaseWakeLock();
            return false;
        }
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
            }
            saveSnapshot("termux-run-command", ComponentStatus.BLOCKED, install.message, 0);
            notificationText = "Termux setup required";
            updateNotification();
            scheduleNext();
            return;
        }
        if (!lastGateState.isEmpty()) {
            log("termux", "READY", "RUN_COMMAND permission and result callback gate passed");
            lastGateState = "";
        }
        inFlight = true;
        TermuxCommandClient.run(this, TermuxOperation.BUDDY_TUNNEL_HEALTH, this::onHealthResult);
    }

    private void onHealthResult(TermuxCommandResult result) {
        boolean healthEndpointsReady = false;
        String summary = "health command exit=" + result.exitCode + " termuxErr=" + result.internalError;
        try {
            JSONObject report = new JSONObject(result.stdout);
            healthEndpointsReady = result.succeeded() && "ok".equals(report.optString("result"));
            summary = "healthz=" + ok(report, "healthz")
                    + " readyz=" + ok(report, "readyz")
                    + " controlPlanePoll=" + nestedOk(report, "control_plane_poll")
                    + " pidRunning=" + nestedOk(report, "process");
        } catch (Exception ignored) {
            if (!result.stderr.isEmpty()) summary += " " + result.stderr;
        }

        saveSnapshot("termux", result.internalError == -1 ? ComponentStatus.RUNNING : ComponentStatus.UNAVAILABLE,
                "Termux RUN_COMMAND result received; exit=" + result.exitCode, 0);
        saveSnapshot("termux-run-command", result.internalError == -1 ? ComponentStatus.AVAILABLE : ComponentStatus.BLOCKED,
                result.internalError == -1 ? "Termux accepted external command" : "RUN_COMMAND did not complete; check Android permission, allow-external-apps, and Termux service availability", 0);
        final boolean endpointsReady = healthEndpointsReady;
        final String healthSummary = summary;
        final int pid = extractPid(result.stdout);
        final boolean pidPresent = processRunning(result.stdout);

        if (!pidPresent || pid <= 0) {
            ComponentStatus processState = pidPresent ? ComponentStatus.ERROR
                    : result.internalError != -1 || result.exitCode == 127
                    ? ComponentStatus.UNAVAILABLE : hasProcessReport(result.stdout)
                    ? ComponentStatus.STOPPED : ComponentStatus.ERROR;
            completeHealthCheck(endpointsReady, false, processState, pid,
                    healthSummary + "; process identity unavailable");
            return;
        }

        TermuxCommandClient.runProcessExecutable(this, pid, executable -> {
            boolean executableMatches = executable.succeeded()
                    && TunnelProcessIdentity.matchesExecutable(executable.stdout);
            if (!executableMatches) {
                completeHealthCheck(endpointsReady, false, ComponentStatus.PID_PRESENT, pid,
                        healthSummary + "; PID exists but executable identity did not match");
                return;
            }
            TermuxCommandClient.runProcessCmdline(this, pid, commandLine -> {
                TermuxCommandClient.InstallState currentInstall = TermuxCommandClient.inspect(this);
                String home = currentInstall.dataDir == null ? "" : currentInstall.dataDir + "/files/home";
                boolean configMatches = commandLine.succeeded() && !home.isEmpty()
                        && TunnelProcessIdentity.matchesBuddyConfig(commandLine.stdout, home);
                completeHealthCheck(endpointsReady, configMatches,
                        configMatches ? ComponentStatus.RUNNING : ComponentStatus.PID_PRESENT,
                        pid, healthSummary + "; " + (configMatches
                                ? "Buddy executable and config identity verified"
                                : "executable matched but Buddy config identity did not"));
            });
        });
    }

    private void completeHealthCheck(boolean endpointsReady, boolean identityVerified,
                                     ComponentStatus processStatus, int pid, String detail) {
        saveSnapshot("tunnel-client", processStatus, detail, pid);
        boolean ready = endpointsReady && identityVerified;
        saveSnapshot("buddy-tunnel", ready ? ComponentStatus.READY : ComponentStatus.NOT_READY,
                detail, pid);
        String state = ready ? "READY" : "NOT_READY";
        if (!state.equals(lastHealthState)) {
            lastHealthState = state;
            log("buddy-tunnel", state, detail);
        }
        notificationText = ready ? "Buddy tunnel READY • identity verified"
                : "Buddy tunnel NOT READY • identity unverified";
        updateNotification();
        long now = System.currentTimeMillis();
        if (ready && now - lastAuthProbeAtEpochMillis >= 300000L) {
            lastAuthProbeAtEpochMillis = now;
            TermuxCommandClient.runBuddyMcpAuthProbe(this, pid, this::onAuthenticatedMcpResult);
        } else {
            TermuxCommandClient.run(this, TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE, this::onMcpChallenge);
        }
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
                : "UNAUTHENTICATED_200_WARNING".equals(state) ? ComponentStatus.ERROR
                : (result.internalError != -1 || result.exitCode == 127) ? ComponentStatus.UNAVAILABLE
                : ComponentStatus.NOT_READY;
        RuntimeSnapshot authSnapshot = eventStore.snapshot("buddy-mcp-auth");
        if (mcpStatus == ComponentStatus.RUNNING && authSnapshot != null
                && authSnapshot.status == ComponentStatus.READY
                && System.currentTimeMillis() - authSnapshot.checkedAtEpochMillis <= 600000L) {
            mcpStatus = ComponentStatus.READY;
        }
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

    private void onAuthenticatedMcpResult(TermuxCommandResult result) {
        JSONObject report = null;
        String state = "";
        try {
            report = new JSONObject(result.stdout);
            state = report.optString("result");
        } catch (Exception ignored) { }

        if (result.succeeded() && "authenticated_ready".equals(state) && report != null) {
            JSONArray tools = report.optJSONArray("tools");
            Set<String> liveNames = new HashSet<>();
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    String name = tools.optString(i, "");
                    if (!name.isEmpty()) liveNames.add(name);
                }
            }
            Set<String> expectedBase = new HashSet<>();
            Set<String> expectedOptional = new HashSet<>();
            for (ToolSpec spec : ToolCatalog.all()) {
                (spec.optionalFeature ? expectedOptional : expectedBase).add(spec.name);
            }
            Set<String> missingBase = new HashSet<>(expectedBase);
            missingBase.removeAll(liveNames);
            Set<String> missingOptional = new HashSet<>(expectedOptional);
            missingOptional.removeAll(liveNames);
            Set<String> extra = new HashSet<>(liveNames);
            extra.removeAll(expectedBase);
            extra.removeAll(expectedOptional);
            String detail = "Authenticated tools/list: live=" + report.optInt("tool_count", liveNames.size())
                    + ", base expected=" + expectedBase.size() + ", missing base=" + missingBase
                    + ", optional missing=" + missingOptional + ", extra=" + extra
                    + ", pages=" + report.optInt("page_count", 1)
                    + ", auth source name=" + report.optString("auth_source", "unknown");
            saveSnapshot("buddy-mcp", ComponentStatus.READY, "Authenticated MCP initialize succeeded", 0);
            saveSnapshot("buddy-mcp-auth", ComponentStatus.READY,
                    "Authenticated initialize and tools/list succeeded; " + detail, 0);
            saveSnapshot("buddy-tools", missingBase.isEmpty() ? ComponentStatus.READY : ComponentStatus.NOT_READY,
                    detail, 0);
            log("buddy-mcp-auth", "AUTHENTICATED_READY", detail);
            notificationText = "Buddy MCP authenticated • " + liveNames.size() + " tools";
            updateNotification();
            inFlight = false;
            scheduleNext();
            return;
        }

        ComponentStatus authStatus;
        String detail;
        if (!result.succeeded()) {
            authStatus = result.exitCode == 127 ? ComponentStatus.UNAVAILABLE : ComponentStatus.NOT_READY;
            detail = "Authenticated MCP probe command failed; exit=" + result.exitCode
                    + ", internalError=" + result.internalError;
        } else {
            switch (state) {
                case "missing_auth_env":
                case "process_env_unavailable":
                    authStatus = ComponentStatus.BLOCKED;
                    detail = "Buddy tunnel process environment did not expose BUDDY_AUTH_HEADER or BUDDY_TOKEN";
                    break;
                case "auth_rejected":
                case "invalid_auth_header":
                case "initialize_mcp_error":
                case "tools_list_mcp_error":
                    authStatus = ComponentStatus.ERROR;
                    detail = "Authenticated MCP handshake rejected; state=" + state
                            + ", HTTP=" + (report == null ? 0 : report.optInt("http_status", 0));
                    break;
                default:
                    authStatus = ComponentStatus.NOT_READY;
                    detail = "Authenticated MCP probe did not complete; state=" + state;
                    break;
            }
        }
        saveSnapshot("buddy-mcp-auth", authStatus, detail, 0);
        saveSnapshot("buddy-tools", authStatus == ComponentStatus.UNAVAILABLE
                ? ComponentStatus.UNAVAILABLE : authStatus == ComponentStatus.BLOCKED
                ? ComponentStatus.BLOCKED : ComponentStatus.NOT_READY, detail, 0);
        log("buddy-mcp-auth", authStatus.name(), detail);
        notificationText = "Buddy MCP auth probe: " + state;
        updateNotification();
        TermuxCommandClient.run(this, TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE, this::onMcpChallenge);
    }

    private void onProcessSnapshot(TermuxCommandResult result) {
        String text = result.stdout == null ? "" : result.stdout.toLowerCase(java.util.Locale.ROOT);
        boolean tunnelSeen = text.contains("tunnel-client");
        log("buddy-tunnel", "PROCESS_SNAPSHOT",
                "tunnelClientSeen=" + tunnelSeen
                        + " termuxSeen=" + text.contains("termux")
                        + " buddySeen=" + text.contains("buddy"));
        // Aggregate process-name inventory is diagnostic only; it must not overwrite profile-specific PID identity.
        inFlight = false;
        scheduleNext();
    }

    private boolean hasProcessReport(String json) {
        try {
            JSONObject report = new JSONObject(json);
            return report.optJSONObject("process") != null;
        } catch (Exception ignored) { return false; }
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
                .setSubText(wakeLock != null && wakeLock.isHeld()
                        ? "CPU wake lock active • stop to release"
                        : "CPU wake lock unavailable; checks may be delayed")
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

    private boolean renewWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager power = getSystemService(PowerManager.class);
                if (power == null) return false;
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "app.jiti.controlcenter:monitor");
                wakeLock.setReferenceCounted(false);
            }
            if (wakeLock.isHeld()) wakeLock.release();
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            boolean held = wakeLock.isHeld();
            if (held && "Monitor active; CPU wake lock unavailable".equals(notificationText)) {
                notificationText = "Buddy monitoring active";
                updateNotification();
            }
            return held;
        } catch (Exception e) {
            log("system", "WAKE_LOCK_UNAVAILABLE", e.getClass().getSimpleName());
            return false;
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
