package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** User-visible foreground owner for the ChatGPT-to-Buddy Tunnel Client process. */
public final class BuddyRuntimeService extends Service {
    static final String ACTION_START = "app.buddy.assistant.runtime.START";
    static final String ACTION_STOP = "app.buddy.assistant.runtime.STOP";
    private static final String TAG = "BuddyRuntime";
    private static final String CHANNEL = "buddy_runtime";
    private static final int NOTIFICATION_ID = 7316;

    private ScheduledExecutorService executor;
    private ScheduledExecutorService helperExecutor;
    private RuntimeSupervisor supervisor;
    private RuntimeSupervisor bridgeSupervisor;
    private RuntimeSupervisor droidMcpSupervisor;
    private RuntimeRecoveryPolicy recovery;
    private ScheduledFuture<?> monitorTask;
    private ScheduledFuture<?> retryTask;
    private ScheduledFuture<?> reaMonitorTask;
    private ScheduledFuture<?> reaRetryTask;
    private RuntimeRecoveryPolicy reaRecovery;
    private volatile boolean stopping;
    private final RuntimeLifecycleGate helperLifecycle = new RuntimeLifecycleGate();
    private boolean retryScheduled;
    private boolean reaRetryScheduled;
    private volatile boolean reaChannelConfigured;
    private volatile boolean mainRemoteVerified;
    private volatile boolean bridgeRemoteVerified;
    private volatile boolean droidRemoteVerified;

    static void start(Context context) {
        Intent intent = new Intent(context, BuddyRuntimeService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, BuddyRuntimeService.class).setAction(ACTION_STOP);
        context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "buddy-runtime-supervisor");
            thread.setDaemon(true);
            return thread;
        });
        Context app = getApplicationContext();
        supervisor = new RuntimeSupervisor(app);
        bridgeSupervisor = new RuntimeSupervisor(app, new java.io.File(app.getFilesDir(), "runtime/bridge-tunnel"),
                () -> RuntimeProcessLauncher.startBridgeConfigured(app), true);
        droidMcpSupervisor = new RuntimeSupervisor(app, new java.io.File(app.getFilesDir(), "runtime/droidmcp-tunnel"),
                () -> RuntimeProcessLauncher.startDroidMcpConfigured(app), true);
        helperExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "buddy-rea-supervisor");
            thread.setDaemon(true);
            return thread;
        });
        recovery = new RuntimeRecoveryPolicy(1000, 60000);
        reaRecovery = new RuntimeRecoveryPolicy(5000, 120000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }
        // A stop request owns this Service instance until its supervisors are torn
        // down. Never revive a shut-down helperExecutor in the same instance.
        if (stopping) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        promoteToForeground(getString(R.string.runtime_notification_starting));
        scheduleAttempt(0);
        return START_STICKY;
    }

    private synchronized void scheduleAttempt(long delayMs) {
        if (executor == null || stopping || retryScheduled) return;
        retryScheduled = true;
        retryTask = executor.schedule(() -> {
            synchronized (BuddyRuntimeService.this) { retryScheduled = false; }
            attemptStart();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void attemptStart() {
        if (stopping || supervisor == null) return;
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("runtime", MODE_PRIVATE);
            boolean reaEnabled = prefs.getBoolean("rea_enabled", true);
            // Prepare Buddy's private Linux/REA runtime before cutover. This installs and verifies
            // local components but does not open any tunnel while a legacy process still owns its ID.
            if (reaEnabled && !ReaRuntimeInstaller.isReady(this)) scheduleReaAttempt(0);

            // The old Termux main tunnel owns health port 39073; Test220/Test223/Test224 have separate MCP ports.
            // Never duplicate the existing main tunnel identity or mistake this build's own listener for a conflict.
            if (hasLegacyMainConflict()) {
                setPersistentState("EXTERNAL_CONFLICT",
                        "Another Buddy MCP endpoint is already listening; no duplicate main tunnel was started.");
                updateNotification(getString(R.string.runtime_notification_external_conflict));
                scheduleAttempt(TimeUnit.SECONDS.toMillis(30));
                return;
            }
            boolean desiredReaChannel = reaEnabled && ReaRuntimeInstaller.isReady(this);
            if (supervisor.isReady() && desiredReaChannel != reaChannelConfigured) supervisor.stop();
            if (!supervisor.isReady()) mainRemoteVerified = false;
            boolean ready = supervisor.start();
            if (ready && !supervisor.isMcpDiscovered(0)) {
                setPersistentState("DEGRADED", supervisor.diagnosticsSummary() + " · MCP initialize/tools/list not yet complete");
                updateNotification(getString(R.string.runtime_notification_retry, 30));
                scheduleAttempt(TimeUnit.SECONDS.toMillis(30));
                return;
            }
            if (ready && !mainRemoteVerified && !supervisor.isRemoteConnected(0)) {
                setPersistentState("CONTROL_PLANE_WAITING",
                        supervisor.diagnosticsSummary() + " · waiting for the first accepted remote response");
                updateNotification(getString(R.string.runtime_notification_retry, 30));
                scheduleAttempt(TimeUnit.SECONDS.toMillis(30));
                return;
            }
            if (ready && !mainRemoteVerified) mainRemoteVerified = true;
            if (ready && !supervisor.isControlPlanePolling(0)) {
                setPersistentState("CONTROL_PLANE_WAITING",
                        supervisor.diagnosticsSummary() + " · prior remote response verified; control-plane polling is not fresh");
                updateNotification(getString(R.string.runtime_notification_retry, 30));
                scheduleAttempt(TimeUnit.SECONDS.toMillis(30));
                return;
            }
            if (ready) {
                recovery.reset();
                reaChannelConfigured = desiredReaChannel;
                setPersistentState("READY", supervisor.diagnosticsSummary() + " · reconnects=" + recovery.failures());
                if (reaChannelConfigured) {
                    setReaPersistentState("CHANNEL_CONFIGURED",
                            "REA 6.5.0 is a second MCP channel under Buddy's existing tunnel; doctor 207/207 tools and 6/6 prompts · " + supervisor.diagnosticsSummary());
                    updateNotification(getString(R.string.runtime_notification_ready_with_rea));
                    scheduleReaAttempt(0);
                } else if (!reaEnabled) {
                    setReaPersistentState("DISABLED", "REA channel disabled; no separate REA tunnel is created.");
                    updateNotification(getString(R.string.runtime_notification_ready));
                } else {
                    setReaPersistentState("INSTALL_REQUIRED",
                            "Installing REA, Bridge and read-only DroidMCP inside Buddy's Alpine/PRoot runtime.");
                    updateNotification(getString(R.string.runtime_notification_rea_installing, "Preparing internal MCP servers"));
                    scheduleReaAttempt(0);
                }
                startMonitor();
            } else {
                setPersistentState("WAITING", supervisor.diagnosticsSummary() + " · reconnectAttempt=" + recovery.failures() + " · lastError=" + supervisor.lastError());
                long delay = recovery.nextDelayMillis();
                updateNotification(getString(R.string.runtime_notification_retry,
                        Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
                scheduleAttempt(delay);
            }
        } catch (IllegalStateException e) {
            String message = safeMessage(e);
            setPersistentState("NEEDS_SETUP", message);
            updateNotification(getString(R.string.runtime_notification_setup));
            Log.w(TAG, "Runtime setup required: " + message);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        } catch (Exception e) {
            if (stopping) return;
            String message = safeMessage(e);
            setPersistentState("ERROR", supervisor.diagnosticsSummary() + " · reconnectAttempt=" + recovery.failures() + " · lastError=" + message);
            Log.w(TAG, "Tunnel Client start failed: " + message);
            long delay = recovery.nextDelayMillis();
            updateNotification(getString(R.string.runtime_notification_retry,
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
            scheduleAttempt(delay);
        }
    }

    private synchronized void scheduleReaAttempt(long delayMs) {
        if (helperExecutor == null || stopping || reaRetryScheduled) return;
        reaRetryScheduled = true;
        reaRetryTask = helperExecutor.schedule(() -> {
            synchronized (BuddyRuntimeService.this) { reaRetryScheduled = false; }
            attemptReaStart();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void attemptReaStart() {
        if (stopping || supervisor == null || helperExecutor == null) return;
        try {
            ReaRuntimeInstaller.ensureReady(getApplicationContext(), message -> {
                if (!stopping) updateNotification(getString(R.string.runtime_notification_rea_installing, message));
            });
            if (stopping || Thread.currentThread().isInterrupted()) return;
            if (!reaChannelConfigured) {
                if (supervisor == null || !supervisor.isReady()) {
                    setReaPersistentState("INSTALL_READY",
                            "Buddy Linux, REA, Bridge and DroidMCP are installed and verified; waiting for legacy tunnel ports to be released.");
                    updateNotification(getString(R.string.runtime_notification_external_conflict));
                    return;
                }
                if (executor != null) executor.execute(() -> {
                    if (stopping || supervisor == null || !supervisor.isReady()) return;
                    supervisor.stop();
                    scheduleAttempt(0);
                });
                return;
            }
            boolean bridgeReady;
            if (!bridgeSupervisor.isProcessAlive() && RuntimeHealthProbe.isLoopbackPortOpen(18080)) {
                bridgeRemoteVerified = false;
                setBridgePersistentState("EXTERNAL_CONFLICT",
                        "Legacy Bridge health port 18080 is occupied; Buddy will not create a duplicate tunnel or stop the existing process.");
                bridgeReady = false;
            } else {
                if (!bridgeSupervisor.isReady()) bridgeRemoteVerified = false;
                bridgeReady = RuntimeHelperBootstrap.startOne(helperLifecycle,
                        () -> bridgeSupervisor.start(),
                        (state, detail) -> setBridgePersistentState(state, "READY".equals(state) ? detail + " · " + bridgeSupervisor.diagnosticsSummary() : detail),
                        "6 tools; internal Bridge 0.1.3",
                        () -> bridgeSupervisor.lastError());
                if (bridgeReady && !bridgeSupervisor.isMcpDiscovered(6)) {
                    setBridgePersistentState("DEGRADED", bridgeSupervisor.diagnosticsSummary() + " · 6-tool catalog not yet complete");
                    bridgeReady = false;
                } else if (bridgeReady && !bridgeRemoteVerified) {
                    if (bridgeSupervisor.isRemoteConnected(6)) bridgeRemoteVerified = true;
                    else {
                        setBridgePersistentState("DEGRADED", bridgeSupervisor.diagnosticsSummary() + " · waiting for the first accepted remote response");
                        bridgeReady = false;
                    }
                }
                if (bridgeReady && bridgeRemoteVerified && !bridgeSupervisor.isControlPlanePolling(6)) {
                    setBridgePersistentState("DEGRADED", bridgeSupervisor.diagnosticsSummary() + " · control-plane polling is not fresh");
                    bridgeReady = false;
                }
            }
            if (stopping || Thread.currentThread().isInterrupted()) return;
            boolean droidReady;
            if (!droidMcpSupervisor.isProcessAlive() && RuntimeHealthProbe.isLoopbackPortOpen(44611)) {
                droidRemoteVerified = false;
                setDroidPersistentState("EXTERNAL_CONFLICT",
                        "Legacy DroidMCP health port 44611 is occupied; Buddy will not create a duplicate tunnel or stop the existing process.");
                droidReady = false;
            } else {
                if (!droidMcpSupervisor.isReady()) droidRemoteVerified = false;
                droidReady = RuntimeHelperBootstrap.startOne(helperLifecycle,
                        () -> droidMcpSupervisor.start(),
                        (state, detail) -> setDroidPersistentState(state, "READY".equals(state) ? detail + " · " + droidMcpSupervisor.diagnosticsSummary() : detail),
                        "read-only filesystem tools; internal DroidMCP 0.4.0",
                        () -> droidMcpSupervisor.lastError());
                if (droidReady && !droidMcpSupervisor.isMcpDiscovered(5)) {
                    setDroidPersistentState("DEGRADED", droidMcpSupervisor.diagnosticsSummary() + " · 5-tool catalog not yet complete");
                    droidReady = false;
                } else if (droidReady && !droidRemoteVerified) {
                    if (droidMcpSupervisor.isRemoteConnected(5)) droidRemoteVerified = true;
                    else {
                        setDroidPersistentState("DEGRADED", droidMcpSupervisor.diagnosticsSummary() + " · waiting for the first accepted remote response");
                        droidReady = false;
                    }
                }
                if (droidReady && droidRemoteVerified && !droidMcpSupervisor.isControlPlanePolling(5)) {
                    setDroidPersistentState("DEGRADED", droidMcpSupervisor.diagnosticsSummary() + " · control-plane polling is not fresh");
                    droidReady = false;
                }
            }
            if (stopping || Thread.currentThread().isInterrupted()) return;
            if (bridgeReady && droidReady) {
                reaRecovery.reset();
                setReaPersistentState("CHANNEL_CONFIGURED",
                        "REA 6.5.0 is available as the main tunnel's rea channel; doctor 207/207 tools, 6/6 prompts · " + supervisor.diagnosticsSummary());
                updateNotification(getString(R.string.runtime_notification_ready_with_rea));
                startReaMonitor();
            } else {
                long delay = reaRecovery.nextDelayMillis();
                updateNotification(getString(R.string.runtime_notification_rea_retry,
                        Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
                scheduleReaAttempt(delay);
            }
        } catch (Exception e) {
            if (stopping) return;
            String message = safeMessage(e);
            setBridgePersistentState("ERROR", message);
            setDroidPersistentState("ERROR", message);
            setReaPersistentState("ERROR", message);
            Log.w(TAG, "Buddy-local helper bootstrap failed: " + message);
            long delay = reaRecovery.nextDelayMillis();
            updateNotification(getString(R.string.runtime_notification_rea_retry,
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
            scheduleReaAttempt(delay);
        }
    }

    private void startReaMonitor() {
        if (reaMonitorTask != null && !reaMonitorTask.isDone()) return;
        if (helperExecutor == null) return;
        reaMonitorTask = helperExecutor.scheduleWithFixedDelay(() -> {
            if (stopping) return;
            boolean mainReady = supervisor != null && supervisor.isReady() && supervisor.isMcpDiscovered(0) && mainRemoteVerified && supervisor.isControlPlanePolling(0);
            boolean bridgeConflict = (bridgeSupervisor == null || !bridgeSupervisor.isProcessAlive()) && RuntimeHealthProbe.isLoopbackPortOpen(18080);
            boolean droidConflict = (droidMcpSupervisor == null || !droidMcpSupervisor.isProcessAlive()) && RuntimeHealthProbe.isLoopbackPortOpen(44611);
            boolean bridgeReady = bridgeSupervisor != null && bridgeSupervisor.isReady() && bridgeSupervisor.isMcpDiscovered(6) && bridgeRemoteVerified && bridgeSupervisor.isControlPlanePolling(6);
            boolean droidReady = droidMcpSupervisor != null && droidMcpSupervisor.isReady() && droidMcpSupervisor.isMcpDiscovered(5) && droidRemoteVerified && droidMcpSupervisor.isControlPlanePolling(5);
            if (mainReady && bridgeReady && droidReady) return;
            if (bridgeConflict) {
                bridgeRemoteVerified = false;
                setBridgePersistentState("EXTERNAL_CONFLICT",
                        "Legacy Bridge health port 18080 is occupied; Buddy will not create a duplicate helper.");
            } else if (!bridgeReady) {
                setBridgePersistentState("DEGRADED", bridgeSupervisor == null ? "Bridge supervisor unavailable" : bridgeSupervisor.diagnosticsSummary() + " · lastError=" + bridgeSupervisor.lastError());
            }
            if (droidConflict) {
                droidRemoteVerified = false;
                setDroidPersistentState("EXTERNAL_CONFLICT",
                        "Legacy DroidMCP health port 44611 is occupied; Buddy will not create a duplicate helper.");
            } else if (!droidReady) {
                setDroidPersistentState("DEGRADED", droidMcpSupervisor == null ? "DroidMCP supervisor unavailable" : droidMcpSupervisor.diagnosticsSummary() + " · lastError=" + droidMcpSupervisor.lastError());
            }
            if (!mainReady) {
                setPersistentState("DEGRADED", supervisor == null ? "Main supervisor unavailable" : supervisor.lastError());
                if (recovery != null) scheduleAttempt(recovery.nextDelayMillis());
            }
            long delay = reaRecovery.nextDelayMillis();
            updateNotification(getString(R.string.runtime_notification_rea_retry,
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
            scheduleReaAttempt(delay);
        }, 30, 30, TimeUnit.SECONDS);
    }

    /** Older Buddy builds may still own their MCP port. Do not mistake this build's own listener for a conflict. */
    private boolean hasLegacyMainConflict() {
        int[] legacyPorts = {39073, 18766, 18767, 18768};
        for (int port : legacyPorts) {
            if (port != McpServer.PORT && RuntimeHealthProbe.isLoopbackPortOpen(port)) return true;
        }
        return false;
    }

    private void startMonitor() {
        if (monitorTask != null && !monitorTask.isDone()) return;
        monitorTask = executor.scheduleWithFixedDelay(() -> {
            if (stopping || supervisor == null) return;
            boolean legacyMainConflict = hasLegacyMainConflict();
            if (legacyMainConflict) {
                if (supervisor.isReady()) supervisor.stop();
                setPersistentState("EXTERNAL_CONFLICT",
                        "An older Buddy MCP endpoint became active; Buddy stopped only its own duplicate main tunnel.");
                updateNotification(getString(R.string.runtime_notification_external_conflict));
                scheduleAttempt(TimeUnit.SECONDS.toMillis(30));
                return;
            }
            if (supervisor.isReady() && supervisor.isMcpDiscovered(0) && mainRemoteVerified && supervisor.isControlPlanePolling(0)) return;
            String message = supervisor.lastError();
            setPersistentState("DEGRADED", supervisor.diagnosticsSummary() + " · reconnectAttempt=" + recovery.failures() + " · lastError=" + message);
            long delay = recovery.nextDelayMillis();
            updateNotification(getString(R.string.runtime_notification_retry,
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
            scheduleAttempt(delay);
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void requestStop() {
        stopping = true;
        mainRemoteVerified = false;
        bridgeRemoteVerified = false;
        droidRemoteVerified = false;
        helperLifecycle.markStopping();
        if (monitorTask != null) monitorTask.cancel(true);
        if (retryTask != null) retryTask.cancel(true);
        if (reaMonitorTask != null) reaMonitorTask.cancel(true);
        if (reaRetryTask != null) reaRetryTask.cancel(true);
        if (helperExecutor != null) helperExecutor.shutdownNow();
        if (executor == null) {
            if (supervisor != null) supervisor.stop();
            helperLifecycle.cleanup(() -> {
                if (bridgeSupervisor != null) bridgeSupervisor.stop();
                if (droidMcpSupervisor != null) droidMcpSupervisor.stop();
            });
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        executor.execute(() -> {
            if (monitorTask != null) monitorTask.cancel(true);
            if (retryTask != null) retryTask.cancel(true);
            if (supervisor != null) supervisor.stop();
            helperLifecycle.cleanup(() -> {
                if (bridgeSupervisor != null) bridgeSupervisor.stop();
                if (droidMcpSupervisor != null) droidMcpSupervisor.stop();
            });
            setPersistentState("STOPPED", "Buddy runtime stopped.");
            setBridgePersistentState("STOPPED", "Internal Bridge stopped gracefully.");
            setDroidPersistentState("STOPPED", "Internal read-only DroidMCP stopped gracefully.");
            setReaPersistentState("STOPPED", "REA remains installed inside Buddy's Alpine runtime; its channel stops with Buddy's main tunnel.");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
    }

    private void promoteToForeground(String message) {
        Notification notification = buildNotification(message);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String message) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private Notification buildNotification(String message) {
        Intent stop = new Intent(this, BuddyRuntimeService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 7317, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent open = new Intent(this, RuntimeSettingsActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 7318, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.runtime_notification_title))
                .setContentText(message)
                .setContentIntent(openPending)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        getString(R.string.runtime_notification_stop), stopPending)
                .build();
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    getString(R.string.runtime_notification_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.runtime_notification_channel_description));
            manager.createNotificationChannel(channel);
        }
    }

    private void setBridgePersistentState(String state, String detail) {
        getSharedPreferences("runtime", MODE_PRIVATE).edit()
                .putString("bridge_state", state)
                .putString("bridge_detail", detail == null ? "" : detail)
                .putLong("bridge_state_updated_at", System.currentTimeMillis())
                .apply();
    }

    private void setDroidPersistentState(String state, String detail) {
        getSharedPreferences("runtime", MODE_PRIVATE).edit()
                .putString("droidmcp_state", state)
                .putString("droidmcp_detail", detail == null ? "" : detail)
                .putLong("droidmcp_state_updated_at", System.currentTimeMillis())
                .apply();
    }

    private void setReaPersistentState(String state, String detail) {
        getSharedPreferences("runtime", MODE_PRIVATE).edit()
                .putString("rea_state", state)
                .putString("rea_detail", detail == null ? "" : detail)
                .putLong("rea_state_updated_at", System.currentTimeMillis())
                .apply();
    }

    private void setPersistentState(String state, String detail) {
        getSharedPreferences("runtime", MODE_PRIVATE).edit()
                .putString("state", state)
                .putString("detail", detail == null ? "" : detail)
                .putLong("state_updated_at", System.currentTimeMillis())
                .apply();
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    @Override public void onDestroy() {
        stopping = true;
        helperLifecycle.markStopping();
        if (monitorTask != null) monitorTask.cancel(true);
        if (retryTask != null) retryTask.cancel(true);
        if (reaMonitorTask != null) reaMonitorTask.cancel(true);
        if (reaRetryTask != null) reaRetryTask.cancel(true);
        if (supervisor != null) supervisor.stop();
        helperLifecycle.cleanup(() -> {
            if (bridgeSupervisor != null) bridgeSupervisor.stop();
            if (droidMcpSupervisor != null) droidMcpSupervisor.stop();
        });
        if (executor != null) executor.shutdownNow();
        if (helperExecutor != null) helperExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
