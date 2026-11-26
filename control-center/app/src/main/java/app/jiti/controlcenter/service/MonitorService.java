package app.jiti.controlcenter.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import app.jiti.controlcenter.MainActivity;
import app.jiti.controlcenter.core.DiagnosticEvent;
import app.jiti.controlcenter.core.ComponentStatus;
import app.jiti.controlcenter.core.RuntimeSnapshot;
import app.jiti.controlcenter.core.OperationCoordinator;
import app.jiti.controlcenter.core.RecoveryErrorClassifier;
import app.jiti.controlcenter.core.RecoveryPolicy;
import app.jiti.controlcenter.core.RecoverySupervisor;
import app.jiti.controlcenter.core.ToolCatalogAudit;
import app.jiti.controlcenter.storage.EventStore;
import app.jiti.controlcenter.termux.TermuxCommandClient;
import app.jiti.controlcenter.termux.TermuxCommandResult;
import app.jiti.controlcenter.termux.TermuxOperation;
import app.jiti.controlcenter.termux.TunnelProcessIdentity;
import app.jiti.controlcenter.termux.TunnelProfileIdentity;

public final class MonitorService extends Service {
    public static final String ACTION_STOP = "app.jiti.controlcenter.action.STOP_MONITOR";
    public static final String ACTION_POLL_NOW = "app.jiti.controlcenter.action.POLL_NOW";
    private static final String CHANNEL_ID = "jiti_monitor";
    private static final int NOTIFICATION_ID = 4101;
    private static final long POLL_INTERVAL_MS = 60000L;
    private static final long WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final String RECOVERY_PREFS = "jiti_auto_recovery";
    private static final String PREF_AUTO_ENABLED = "enabled";
    private static final String[] AUTO_RECOVERY_PROFILES = {"bridge", "droidmcp", "rea-android"};
    private static final TermuxOperation[] AUXILIARY_PROFILE_HEALTH = new TermuxOperation[]{
            TermuxOperation.BRIDGE_TUNNEL_HEALTH,
            TermuxOperation.DROIDMCP_TUNNEL_HEALTH,
            TermuxOperation.REA_ANDROID_TUNNEL_HEALTH
    };

    public static volatile boolean running = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EventStore eventStore;
    private SharedPreferences recoveryPreferences;
    private final RecoverySupervisor recoverySupervisor = new RecoverySupervisor();
    private final Map<String, PendingRecovery> pendingRecoveries = new LinkedHashMap<>();
    private final Map<String, Runnable> scheduledRecoveryTasks = new LinkedHashMap<>();
    private PowerManager.WakeLock wakeLock;
    private boolean monitoring;
    private boolean inFlight;
    private boolean pollRequested;
    private String lastHealthState = "";
    private String lastMcpState = "";
    private String lastGateState = "";
    private long lastAuthProbeAtEpochMillis = 0L;
    private String notificationText = "Ожидание первой проверки";

    private final Runnable poll = this::pollOnce;

    private static final class PendingRecovery {
        final String profileId;
        final String mode;
        final long delayMillis;
        final int attempt;
        final String reason;

        PendingRecovery(String profileId, String mode, long delayMillis, int attempt, String reason) {
            this.profileId = profileId;
            this.mode = mode;
            this.delayMillis = delayMillis;
            this.attempt = attempt;
            this.reason = reason;
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        eventStore = new EventStore(this);
        recoveryPreferences = getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE);
        restoreRecoveryState();
        syncRecoverySettings();
        createNotificationChannel();
    }

    public static boolean isAutoRecoveryEnabled(Context context) {
        return context.getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
                .getBoolean(PREF_AUTO_ENABLED, false);
    }

    public static void setAutoRecoveryEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
                .edit().putBoolean(PREF_AUTO_ENABLED, enabled).apply();
        if (enabled) OperationCoordinator.shared().setAutomaticEnabled(true);
        else OperationCoordinator.shared().cancelAutomatic();
    }

    public static void setManualHold(Context context, String profileId, boolean hold) {
        if (!"bridge".equals(profileId) && !"droidmcp".equals(profileId)
                && !"rea-android".equals(profileId)) return;
        context.getSharedPreferences(RECOVERY_PREFS, MODE_PRIVATE)
                .edit().putBoolean("manual_hold." + profileId, hold).apply();
    }

    private boolean isManualHold(String profileId) {
        return recoveryPreferences != null
                && recoveryPreferences.getBoolean("manual_hold." + profileId, false);
    }

    private void syncRecoverySettings() {
        boolean enabled = monitoring && isAutoRecoveryEnabled(this);
        recoverySupervisor.setEnabled(enabled);
        OperationCoordinator.shared().setAutomaticEnabled(enabled);
        if (!enabled) {
            pendingRecoveries.clear();
            for (Runnable task : scheduledRecoveryTasks.values()) handler.removeCallbacks(task);
            scheduledRecoveryTasks.clear();
        }
    }

    private void restoreRecoveryState() {
        if (recoveryPreferences == null) return;
        for (String profile : AUTO_RECOVERY_PROFILES) {
            try {
                recoverySupervisor.restore(profile,
                        recoveryPreferences.getInt("attempts." + profile, 0),
                        recoveryPreferences.getLong("next_allowed." + profile, 0L),
                        recoveryPreferences.getInt("consecutive." + profile, 0));
            } catch (Exception ignored) {
                log("system", "RECOVERY_STATE_INVALID", "Ignored invalid saved state for " + profile);
            }
        }
    }

    private void persistRecoveryState(String profile) {
        if (recoveryPreferences == null) return;
        RecoverySupervisor.Snapshot snapshot = recoverySupervisor.snapshot(profile);
        recoveryPreferences.edit()
                .putInt("attempts." + profile, snapshot.attempts)
                .putLong("next_allowed." + profile, snapshot.nextAllowedAt)
                .putInt("consecutive." + profile, snapshot.consecutiveFailures)
                .apply();
    }

    private boolean hasValidatedInternet() {
        try {
            ConnectivityManager manager = getSystemService(ConnectivityManager.class);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception ignored) {
            return false;
        }
    }

    private int findNumericField(JSONObject object, String... names) {
        if (object == null) return -1;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            String normalized = key.toLowerCase(java.util.Locale.ROOT)
                    .replace("_", "").replace("-", "");
            for (String name : names) {
                if (normalized.equals(name)) {
                    if (value instanceof Number) return ((Number) value).intValue();
                    try { return Integer.parseInt(String.valueOf(value)); } catch (Exception ignored) { }
                }
            }
            if (value instanceof JSONObject) {
                int nested = findNumericField((JSONObject) value, names);
                if (nested >= 0) return nested;
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    if (item instanceof JSONObject) {
                        int nested = findNumericField((JSONObject) item, names);
                        if (nested >= 0) return nested;
                    }
                }
            }
        }
        return -1;
    }

    private int findHttpStatus(JSONObject report) {
        return Math.max(0, findNumericField(report, "httpstatus", "statuscode", "httpstatuscode"));
    }

    private long findLongField(JSONObject object, String... names) {
        if (object == null) return -1L;
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            String normalized = key.toLowerCase(java.util.Locale.ROOT)
                    .replace("_", "").replace("-", "");
            for (String name : names) {
                if (normalized.equals(name)) {
                    if (value instanceof Number) return ((Number) value).longValue();
                    try { return Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { }
                }
            }
            if (value instanceof JSONObject) {
                long nested = findLongField((JSONObject) value, names);
                if (nested >= 0L) return nested;
            } else if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                for (int i = 0; i < array.length(); i++) {
                    Object item = array.opt(i);
                    if (item instanceof JSONObject) {
                        long nested = findLongField((JSONObject) item, names);
                        if (nested >= 0L) return nested;
                    }
                }
            }
        }
        return -1L;
    }

    private long findRetryAfterMillis(JSONObject report) {
        long millis = findLongField(report, "retryafterms", "retryaftermillis");
        if (millis >= 0L) return Math.min(millis, 900000L);
        long seconds = findLongField(report, "retryafterseconds", "retryafter");
        if (seconds >= 0L) return seconds >= 900L ? 900000L : seconds * 1000L;
        return -1L;
    }

    private String profileIdForOperation(TermuxOperation operation) {
        switch (operation) {
            case BRIDGE_TUNNEL_HEALTH: return "bridge";
            case DROIDMCP_TUNNEL_HEALTH: return "droidmcp";
            case REA_ANDROID_TUNNEL_HEALTH: return "rea-android";
            default: return "";
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopMonitoring();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_POLL_NOW.equals(intent.getAction())) {
            syncRecoverySettings();
            if (!monitoring) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            if (inFlight) pollRequested = true;
            else {
                handler.removeCallbacks(poll);
                handler.post(poll);
            }
            return START_STICKY;
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
            syncRecoverySettings();
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
        if (!monitoring) { inFlight = false; return; }
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
            ComponentStatus processState = hasProcessError(result.stdout) ? ComponentStatus.ERROR
                    : pidPresent ? ComponentStatus.ERROR
                    : result.internalError != -1 || result.exitCode == 127
                    ? ComponentStatus.UNAVAILABLE : hasProcessReport(result.stdout)
                    ? ComponentStatus.STOPPED : ComponentStatus.ERROR;
            completeHealthCheck(endpointsReady, false, processState, pid,
                    healthSummary + "; process identity unavailable");
            return;
        }

        TermuxCommandClient.InstallState currentInstall = TermuxCommandClient.inspect(this);
        String home = currentInstall.dataDir == null ? "" : currentInstall.dataDir + "/files/home";
        TermuxCommandClient.runProcessExecutable(this, pid, executable -> {
            if (!monitoring) { inFlight = false; return; }
            boolean executableMatches = executable.succeeded() && !home.isEmpty()
                    && TunnelProcessIdentity.matchesExecutable(executable.stdout, home);
            if (!executableMatches) {
                completeHealthCheck(endpointsReady, false, ComponentStatus.PID_PRESENT, pid,
                        healthSummary + "; PID exists but executable identity did not match");
                return;
            }
            TermuxCommandClient.runProcessCmdline(this, pid, commandLine -> {
                if (!monitoring) { inFlight = false; return; }
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
        if (!monitoring) { inFlight = false; return; }
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
        if (!monitoring) { inFlight = false; return; }
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
            runAuxiliaryProfileChecks(0);
        }
    }

    private void onAuthenticatedMcpResult(TermuxCommandResult result) {
        if (!monitoring) { inFlight = false; return; }
        if (result.internalError == -1 && result.exitCode != 127) {
            saveSnapshot("termux-python", ComponentStatus.AVAILABLE, "Python authenticated probe executed", 0);
        } else if (result.internalError == -1 && result.exitCode == 127) {
            saveSnapshot("termux-python", ComponentStatus.UNAVAILABLE, "Python executable not found", 0);
        }
        JSONObject report = null;
        String state = "";
        try {
            report = new JSONObject(result.stdout);
            state = report.optString("result");
        } catch (Exception ignored) { }

        if (result.succeeded() && "authenticated_ready".equals(state) && report != null) {
            JSONArray tools = report.optJSONArray("tools");
            List<String> liveNames = new ArrayList<>();
            if (tools != null) {
                for (int i = 0; i < tools.length(); i++) {
                    String name = tools.optString(i, "");
                    if (!name.isEmpty()) liveNames.add(name);
                }
            }
            ToolCatalogAudit audit = ToolCatalogAudit.analyze(liveNames,
                    report.optInt("tool_count", liveNames.size()), report.optInt("page_count", 1));
            String detail = audit.summary(report.optString("auth_source", "unknown"));
            saveSnapshot("buddy-mcp", ComponentStatus.READY, "Authenticated MCP initialize succeeded", 0);
            saveSnapshot("buddy-mcp-auth", ComponentStatus.READY,
                    "Authenticated initialize and tools/list succeeded; " + detail, 0);
            saveSnapshot("buddy-tools", audit.baseCatalogReady() ? ComponentStatus.READY : ComponentStatus.NOT_READY,
                    detail, 0);
            log("buddy-mcp-auth", "AUTHENTICATED_READY", detail);
            notificationText = "Buddy MCP authenticated • " + audit.reportedCount + " tools";
            updateNotification();
            runAuxiliaryProfileChecks(0);
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
        if (!monitoring) { inFlight = false; return; }
        String text = result.stdout == null ? "" : result.stdout.toLowerCase(java.util.Locale.ROOT);
        boolean tunnelSeen = text.contains("tunnel-client");
        log("buddy-tunnel", "PROCESS_SNAPSHOT",
                "tunnelClientSeen=" + tunnelSeen
                        + " termuxSeen=" + text.contains("termux")
                        + " buddySeen=" + text.contains("buddy"));
        // Aggregate process-name inventory is diagnostic only; it must not overwrite profile-specific PID identity.
        runAuxiliaryProfileChecks(0);
    }

    private void runAuxiliaryProfileChecks(int index) {
        if (!monitoring) {
            inFlight = false;
            return;
        }
        if (index >= AUXILIARY_PROFILE_HEALTH.length) {
            inFlight = false;
            dispatchPendingAutomaticRecovery();
            updateNotification();
            scheduleNext();
            return;
        }
        TermuxOperation operation = AUXILIARY_PROFILE_HEALTH[index];
        TermuxCommandClient.run(this, operation,
                result -> checkAuxiliaryProfileIdentity(operation, result,
                        () -> runAuxiliaryProfileChecks(index + 1)));
    }

    private void checkAuxiliaryProfileIdentity(TermuxOperation operation, TermuxCommandResult health,
                                               Runnable next) {
        if (!monitoring) { inFlight = false; return; }
        String componentId = profileComponentId(operation);
        JSONObject report;
        JSONObject process;
        int pid;
        boolean endpointsReady;
        try {
            report = new JSONObject(health.stdout);
            process = report.optJSONObject("process");
            if (process == null) {
                ComponentStatus status = health.internalError != -1 || health.exitCode == 127
                        ? ComponentStatus.UNAVAILABLE : ComponentStatus.ERROR;
                String detail = "Health report has no process object";
                recordProfileStatus(componentId, status, 0, detail);
                handleRecoveryObservation(profileIdForOperation(operation), RecoveryPolicy.ErrorClass.UNKNOWN,
                        "health_report_missing_process", -1L, detail);
                next.run();
                return;
            }
            if (!process.optString("error", "").isEmpty()) {
                String detail = "Tunnel PID file could not be read or parsed";
                recordProfileStatus(componentId, ComponentStatus.ERROR, process.optInt("pid", 0), detail);
                handleRecoveryObservation(profileIdForOperation(operation), RecoveryPolicy.ErrorClass.CONFIGURATION,
                        "pid_file_invalid", -1L, detail);
                next.run();
                return;
            }
            if (!process.optBoolean("running")) {
                String detail = "Tunnel Client process is not running";
                recordProfileStatus(componentId, ComponentStatus.STOPPED, process.optInt("pid", 0), detail);
                int httpStatus = findHttpStatus(report);
                RecoveryPolicy.ErrorClass error = httpStatus > 0
                        ? RecoveryErrorClassifier.fromHttpStatus(httpStatus)
                        : RecoveryPolicy.ErrorClass.PROCESS_MISSING;
                String signature = httpStatus > 0 ? "http_" + httpStatus : "process_missing";
                handleRecoveryObservation(profileIdForOperation(operation), error,
                        signature, findRetryAfterMillis(report), detail);
                next.run();
                return;
            }
            pid = process.optInt("pid", 0);
            if (pid <= 0) {
                String detail = "Health report says running but PID is invalid";
                recordProfileStatus(componentId, ComponentStatus.ERROR, 0, detail);
                handleRecoveryObservation(profileIdForOperation(operation), RecoveryPolicy.ErrorClass.CONFIGURATION,
                        "invalid_pid", -1L, detail);
                next.run();
                return;
            }
            endpointsReady = health.succeeded() && "ok".equals(report.optString("result"));
        } catch (Exception e) {
            ComponentStatus status = health.internalError != -1 || health.exitCode == 127
                    ? ComponentStatus.UNAVAILABLE : ComponentStatus.ERROR;
            String detail = "Invalid health JSON";
            recordProfileStatus(componentId, status, 0, detail);
            handleRecoveryObservation(profileIdForOperation(operation), RecoveryPolicy.ErrorClass.UNKNOWN,
                    "invalid_health_json", -1L, detail);
            next.run();
            return;
        }

        final int targetPid = pid;
        final boolean ready = endpointsReady;
        TermuxCommandClient.InstallState install = TermuxCommandClient.inspect(this);
        final String home = install.dataDir == null ? "" : install.dataDir + "/files/home";
        TermuxCommandClient.runProcessExecutable(this, targetPid, executable -> {
            if (!monitoring) { inFlight = false; return; }
            if (!executable.succeeded() || home.isEmpty()
                    || !TunnelProfileIdentity.matchesExecutable(executable.stdout, home)) {
                String detail = "PID exists but exact Tunnel Client executable identity does not match";
                recordProfileStatus(componentId, ComponentStatus.PID_PRESENT, targetPid, detail);
                handleRecoveryObservation(profileIdForOperation(operation), RecoveryPolicy.ErrorClass.CONFIGURATION,
                        "process_identity_mismatch", -1L, detail);
                next.run();
                return;
            }
            TermuxCommandClient.runProcessCmdline(this, targetPid, commandLine -> {
                if (!monitoring) { inFlight = false; return; }
                boolean identity = commandLine.succeeded() && !home.isEmpty()
                        && TunnelProfileIdentity.matchesProfile(executable.stdout, commandLine.stdout,
                        home, profileConfigRelativePath(operation), profileConfigFlag(operation));
                ComponentStatus status = !identity ? ComponentStatus.PID_PRESENT
                        : ready ? ComponentStatus.READY : ComponentStatus.NOT_READY;
                String detail = !identity
                        ? "Tunnel Client executable matched but profile config identity did not"
                        : ready ? "Health endpoints and exact profile process identity verified"
                        : "Exact profile process identity verified; health endpoints are not ready";
                recordProfileStatus(componentId, status, targetPid, detail);
                if (ready) {
                    recordRecoverySuccess(profileIdForOperation(operation), detail);
                } else if (!identity) {
                    handleRecoveryObservation(profileIdForOperation(operation), RecoveryPolicy.ErrorClass.CONFIGURATION,
                            "profile_config_identity_mismatch", -1L, detail);
                } else {
                    int httpStatus = findHttpStatus(report);
                    RecoveryPolicy.ErrorClass error = httpStatus > 0
                            ? RecoveryErrorClassifier.fromHttpStatus(httpStatus)
                            : RecoveryPolicy.ErrorClass.READINESS_FAILED;
                    String signature = httpStatus > 0 ? "http_" + httpStatus : "readiness_failed";
                    handleRecoveryObservation(profileIdForOperation(operation), error,
                            signature, findRetryAfterMillis(report), detail);
                }
                next.run();
            });
        });
    }

    private void recordRecoverySuccess(String profileId, String detail) {
        if (profileId == null || profileId.isEmpty()) return;
        RecoverySupervisor.Snapshot before = recoverySupervisor.snapshot(profileId);
        recoverySupervisor.recordSuccess(profileId);
        persistRecoveryState(profileId);
        pendingRecoveries.remove(profileId);
        Runnable scheduled = scheduledRecoveryTasks.remove(profileId);
        if (scheduled != null) handler.removeCallbacks(scheduled);
        if (before.attempts > 0 || before.consecutiveFailures > 0) {
            log(profileComponentIdForId(profileId), "AUTO_RECOVERY_STABLE",
                    "Profile returned to READY; retry budget reset. " + detail);
        }
    }

    private void handleRecoveryObservation(String profileId, RecoveryPolicy.ErrorClass error,
                                          String signature, long retryAfterMillis, String detail) {
        if (profileId == null || profileId.isEmpty()) return;
        if (!isAutoRecoveryEnabled(this)) {
            recoverySupervisor.setEnabled(false);
            return;
        }
        recoverySupervisor.setEnabled(true);
        if (isManualHold(profileId)) {
            log(profileComponentIdForId(profileId), "AUTO_RECOVERY_HELD",
                    "Manual hold is active; automatic action suppressed.");
            return;
        }
        boolean networkAvailable = hasValidatedInternet();
        RecoverySupervisor.Plan plan = recoverySupervisor.plan(profileId, error, signature,
                System.currentTimeMillis(), networkAvailable, retryAfterMillis,
                ThreadLocalRandom.current().nextDouble());
        persistRecoveryState(profileId);
        String event = "action=" + plan.action + ", attempts=" + plan.attempts
                + ", delayMs=" + plan.delayMillis + ", reason=" + plan.reason;
        log(profileComponentIdForId(profileId), "AUTO_RECOVERY_PLAN", event + "; " + detail);
        if (plan.action == RecoverySupervisor.Action.RECOVER) {
            String mode = error == RecoveryPolicy.ErrorClass.PROCESS_MISSING ? "start" : "restart";
            pendingRecoveries.put(profileId, new PendingRecovery(profileId, mode,
                    plan.delayMillis, plan.attempts, plan.reason));
        } else if (plan.action == RecoverySupervisor.Action.MANUAL_REQUIRED
                || plan.action == RecoverySupervisor.Action.CIRCUIT_OPEN
                || plan.action == RecoverySupervisor.Action.EXHAUSTED) {
            notificationText = "Автовосстановление остановлено: требуется ручная проверка • " + profileId;
            updateNotification();
        } else if (plan.action == RecoverySupervisor.Action.RECHECK_ONLY
                || plan.action == RecoverySupervisor.Action.WAIT_FOR_COOLDOWN
                || plan.action == RecoverySupervisor.Action.WAIT_FOR_NETWORK) {
            notificationText = "Проверка туннеля отложена • " + profileId;
            updateNotification();
        }
    }

    private void dispatchPendingAutomaticRecovery() {
        if (!monitoring || !isAutoRecoveryEnabled(this) || pendingRecoveries.isEmpty()) return;
        for (PendingRecovery pending : new ArrayList<>(pendingRecoveries.values())) {
            pendingRecoveries.remove(pending.profileId);
            if (scheduledRecoveryTasks.containsKey(pending.profileId)) continue;
            Runnable task = () -> {
                scheduledRecoveryTasks.remove(pending.profileId);
                if (!monitoring || !isAutoRecoveryEnabled(this)) return;
                executeAutomaticRecovery(pending);
            };
            scheduledRecoveryTasks.put(pending.profileId, task);
            handler.postDelayed(task, Math.max(0L, pending.delayMillis));
            break; // One lifecycle mutation per monitor cycle.
        }
    }

    private void executeAutomaticRecovery(PendingRecovery pending) {
        if (!monitoring || !isAutoRecoveryEnabled(this) || isManualHold(pending.profileId)) return;
        if (!hasValidatedInternet()) {
            log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_DEFERRED",
                    "Validated network disappeared before preflight; no process command sent.");
            return;
        }
        // Revalidate immediately before mutation. A manual start may have
        // repaired the profile while this recovery was waiting in cooldown.
        TermuxCommandClient.runProfileControl(this, pending.profileId, "preflight", preflight -> {
            if (!monitoring || !isAutoRecoveryEnabled(this) || isManualHold(pending.profileId)) return;
            JSONObject report = null;
            String state = "invalid_preflight";
            try {
                report = new JSONObject(preflight.stdout);
                state = report.optString("result", "unknown");
            } catch (Exception ignored) { }
            if (preflight.succeeded() && "already_running".equals(state)) {
                recordRecoverySuccess(pending.profileId,
                        "Preflight found the profile already fully ready; stale recovery cancelled.");
                log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_SUPERSEDED",
                        "Profile became READY before the scheduled recovery command.");
                dispatchPendingAutomaticRecovery();
                return;
            }
            if (preflight.succeeded() && "process_running_not_ready".equals(state) && report != null) {
                int httpStatus = findHttpStatus(report);
                if (httpStatus >= 300) {
                    RecoveryPolicy.ErrorClass error = RecoveryErrorClassifier.fromHttpStatus(httpStatus);
                    handleRecoveryObservation(pending.profileId, error, "preflight_http_" + httpStatus,
                            findRetryAfterMillis(report), "Preflight reported an HTTP policy/transport failure.");
                    dispatchPendingAutomaticRecovery();
                    return;
                }
            }

            String mode;
            if (preflight.succeeded() && "ready_to_start".equals(state)) {
                // No validated process remains. Starting is safer than asking
                // restart to stop a stale PID record.
                mode = "start";
            } else if (preflight.succeeded() && "process_running_not_ready".equals(state)
                    && "restart".equals(pending.mode)) {
                mode = "restart";
            } else if (preflight.succeeded() && "process_running_not_ready".equals(state)) {
                log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_DEFERRED",
                        "A process appeared after the original missing-process observation; it is not restarted.");
                dispatchPendingAutomaticRecovery();
                return;
            } else {
                String safeState = report == null ? "invalid_preflight" : state;
                log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_BLOCKED",
                        "Preflight refused automatic mutation; state=" + safeState
                                + ", exit=" + preflight.exitCode
                                + ", internalError=" + preflight.internalError);
                notificationText = "Автовосстановление заблокировано проверкой • " + pending.profileId;
                updateNotification();
                dispatchPendingAutomaticRecovery();
                return;
            }

            if (!hasValidatedInternet()) {
                log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_DEFERRED",
                        "Validated network disappeared after preflight; no lifecycle command sent.");
                return;
            }
            PendingRecovery verified = new PendingRecovery(pending.profileId, mode,
                    pending.delayMillis, pending.attempt, pending.reason);
            beginAutomaticRecovery(verified);
        });
    }

    private void beginAutomaticRecovery(PendingRecovery pending) {
        if (!monitoring || !isAutoRecoveryEnabled(this) || isManualHold(pending.profileId)) return;
        OperationCoordinator coordinator = OperationCoordinator.shared();
        OperationCoordinator.Submission submission = coordinator.requestAutomatic(
                pending.profileId, "recover." + pending.mode);
        if (!submission.accepted()) {
            log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_SKIPPED",
                    "Exclusive operation slot unavailable: " + submission.status);
            return;
        }
        long requestId = submission.request.id;
        notificationText = "Автовосстановление: " + pending.profileId + " • " + pending.mode;
        updateNotification();
        log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_STARTED",
                "Attempt " + pending.attempt + "; " + pending.reason);
        TermuxCommandClient.runProfileControl(this, pending.profileId, pending.mode, result -> {
            JSONObject report = null;
            String state = "invalid_result";
            try {
                report = new JSONObject(result.stdout);
                state = report.optString("result", "unknown");
            } catch (Exception ignored) { }
            boolean success = result.succeeded()
                    && ("started_ready".equals(state) || "already_running".equals(state));
            if (success) {
                recordRecoverySuccess(pending.profileId,
                        "Automatic " + pending.mode + " completed and local readiness was confirmed.");
                notificationText = "Туннель восстановлен • " + pending.profileId;
                log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_SUCCEEDED",
                        "Attempt " + pending.attempt + " confirmed by " + state);
            } else {
                persistRecoveryState(pending.profileId);
                String safeState = report == null ? "invalid_result" : state;
                log(profileComponentIdForId(pending.profileId), "AUTO_RECOVERY_FAILED",
                        "Attempt " + pending.attempt + " failed; state=" + safeState
                                + ", exit=" + result.exitCode + ", internalError=" + result.internalError);
                notificationText = "Автовосстановление не удалось • " + pending.profileId;
            }
            OperationCoordinator.Request next = coordinator.complete(requestId);
            if (next != null && next.kind == OperationCoordinator.Kind.MANUAL) {
                sendBroadcast(new Intent(MainActivity.ACTION_MANUAL_REQUEST_READY)
                        .setPackage(getPackageName()));
            } else {
                dispatchPendingAutomaticRecovery();
            }
            updateNotification();
        });
    }

    private String profileComponentIdForId(String profileId) {
        if ("bridge".equals(profileId)) return "rea-bridge";
        if ("droidmcp".equals(profileId)) return "droidmcp";
        if ("rea-android".equals(profileId)) return "rea-android";
        return "system";
    }

    private String profileComponentId(TermuxOperation operation) {
        switch (operation) {
            case BRIDGE_TUNNEL_HEALTH: return "rea-bridge";
            case DROIDMCP_TUNNEL_HEALTH: return "droidmcp";
            case REA_ANDROID_TUNNEL_HEALTH: return "rea-android";
            default: return "unknown-profile";
        }
    }

    private String profileConfigRelativePath(TermuxOperation operation) {
        switch (operation) {
            case BRIDGE_TUNNEL_HEALTH: return "/.config/tunnel-client-bridge-013/bridge-013.yaml";
            case DROIDMCP_TUNNEL_HEALTH: return "/droidmcp-stage-v0.4.0/profile-dedicated/droidmcp-filesystem.yaml";
            case REA_ANDROID_TUNNEL_HEALTH: return "/.config/rea-tunnel/profile/rea-android.yaml";
            default: return "";
        }
    }

    private String profileConfigFlag(TermuxOperation operation) {
        return operation == TermuxOperation.REA_ANDROID_TUNNEL_HEALTH ? "--profile-file" : "--config";
    }

    private void recordProfileStatus(String id, ComponentStatus status, int pid, String detail) {
        RuntimeSnapshot previous = null;
        try {
            previous = eventStore == null ? null : eventStore.snapshot(id);
        } catch (Exception ignored) { }
        saveSnapshot(id, status, detail, pid);
        if (previous == null || previous.status != status) log(id, status.name(), detail);
    }

    private boolean hasProcessError(String json) {
        try {
            JSONObject report = new JSONObject(json);
            JSONObject process = report.optJSONObject("process");
            return process != null && !process.optString("error", "").isEmpty();
        } catch (Exception ignored) { return false; }
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
            boolean failure = status == ComponentStatus.ERROR || status == ComponentStatus.NOT_READY
                    || status == ComponentStatus.BLOCKED || status == ComponentStatus.UNAVAILABLE;
            String safeDetail = detail == null ? "" : detail;
            boolean newFailure = failure && (previous == null || previous.status != status
                    || !safeDetail.equals(previous.detail));
            if (newFailure) errors++;
            eventStore.saveSnapshot(new RuntimeSnapshot(id, status, System.currentTimeMillis(),
                    pid, errors, retries, safeDetail));
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
            if (pollRequested) {
                pollRequested = false;
                handler.post(poll);
            } else {
                handler.postDelayed(poll, POLL_INTERVAL_MS);
            }
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

    private void clearPendingAutomaticRecoveries(String reason) {
        int cancelled = scheduledRecoveryTasks.size();
        for (Runnable task : scheduledRecoveryTasks.values()) handler.removeCallbacks(task);
        scheduledRecoveryTasks.clear();
        pendingRecoveries.clear();
        if (cancelled > 0) {
            log("system", "AUTO_RECOVERY_QUEUE_CLEARED",
                    reason + "; cancelled delayed tasks=" + cancelled);
        }
    }

    private void stopMonitoring() {
        monitoring = false;
        running = false;
        inFlight = false;
        pollRequested = false;
        handler.removeCallbacks(poll);
        clearPendingAutomaticRecoveries("monitor_stopped");
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
        clearPendingAutomaticRecoveries("service_destroyed");
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

    public static void pollNow(Context context) {
        Intent intent = new Intent(context, MonitorService.class);
        intent.setAction(ACTION_POLL_NOW);
        context.startService(intent);
    }
}
