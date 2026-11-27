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
    static final String ACTION_CONNECT_TERMUX = "app.buddy.assistant.runtime.CONNECT_TERMUX";
    static final String ACTION_TERMUX_MANAGER_READY = "app.buddy.assistant.runtime.TERMUX_MANAGER_READY";
    private static final String TAG = "BuddyRuntime";
    private static final String CHANNEL = "buddy_runtime";
    private static final int NOTIFICATION_ID = 7316;

    private ScheduledExecutorService executor;
    private ScheduledExecutorService helperExecutor;
    private RuntimeSupervisor supervisor;
    private static final int TERMUX_BRIDGE_PORT_PRIMARY = 18080;
    private static final int TERMUX_BRIDGE_PORT_LEGACY = 8080;
    private static final int DROIDMCP_PORT = 41507;
    private RuntimeRecoveryPolicy recovery;
    private ScheduledFuture<?> monitorTask;
    private ScheduledFuture<?> retryTask;
    private volatile boolean stopping;
    private boolean retryScheduled;
    private volatile boolean reaChannelConfigured;

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
        helperExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "buddy-termux-connection-monitor");
            thread.setDaemon(true);
            return thread;
        });
        recovery = new RuntimeRecoveryPolicy(1000, 60000);
    }

    static void connectTermux(Context context) {
        Intent intent = new Intent(context, BuddyRuntimeService.class).setAction(ACTION_CONNECT_TERMUX);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void termuxManagerReady(Context context) {
        Intent intent = new Intent(context, BuddyRuntimeService.class).setAction(ACTION_TERMUX_MANAGER_READY);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (stopping) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        promoteToForeground(getString(R.string.runtime_notification_starting));
        if (ACTION_CONNECT_TERMUX.equals(action)) {
            if (!TermuxRuntimeController.isInstalled(this)) {
                setTermuxPersistentState("NOT_INSTALLED", "Install Termux to connect Bridge and DroidMCP.");
            } else if (!TermuxRuntimeController.hasPermission(this)) {
                setTermuxPersistentState("NEEDS_PERMISSION", "Grant Buddy the Termux RUN_COMMAND permission.");
            } else {
                setTermuxPersistentState("INSTALLING", "Installing Buddy's isolated Termux tunnel manager.");
                TermuxRuntimeController.installManager(this);
            }
            startConnectionMonitor();
            return START_STICKY;
        }
        if (ACTION_TERMUX_MANAGER_READY.equals(action)) {
            setTermuxPersistentState("MANAGER_READY", "Buddy's Termux manager is installed; reconciling Bridge and DroidMCP.");
            reconcileTermuxConnections();
            startConnectionMonitor();
            return START_STICKY;
        }
        if (supervisor != null && supervisor.state() == RuntimeStateMachine.State.READY) {
            supervisor.stop();
        }
        scheduleAttempt(0);
        reconcileTermuxConnections();
        startConnectionMonitor();
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
        if (!Secrets.has(this, RuntimeProcessLauncher.API_KEY_SECRET)) {
            setPersistentState("NEEDS_SETUP", "API key required for the Buddy tunnel.");
            updateNotification(getString(R.string.runtime_notification_setup));
            return;
        }
        String configuredTunnelId = getSharedPreferences("runtime", MODE_PRIVATE)
                .getString("tunnel_id", RuntimeProcessLauncher.DEFAULT_TUNNEL_ID);
        if (configuredTunnelId == null || !configuredTunnelId.matches("tunnel_[a-fA-F0-9]{32}")
                || RuntimeProcessLauncher.RESERVED_TEST220_TUNNEL_ID.equalsIgnoreCase(configuredTunnelId)) {
            setPersistentState("NEEDS_SETUP", getString(R.string.runtime_unique_tunnel_id_required));
            updateNotification(getString(R.string.runtime_notification_setup));
            return;
        }
        try {
            boolean ready = supervisor.start();
            if (ready) {
                recovery.reset();
                updateMainMcpState();
                startMonitor();
            } else {
                setPersistentState("WAITING", supervisor.lastError());
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
        } catch (Exception e) {
            if (stopping) return;
            String message = safeMessage(e);
            setPersistentState("ERROR", message);
            Log.w(TAG, "Tunnel Client start failed: " + message);
            long delay = recovery.nextDelayMillis();
            updateNotification(getString(R.string.runtime_notification_retry,
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
            scheduleAttempt(delay);
        }
    }

    private void reconcileTermuxConnections() {
        if (stopping || helperExecutor == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences("runtime", MODE_PRIVATE);
        if (!prefs.getBoolean("termux_manager_installed", false)) return;
        if (!TermuxRuntimeController.isInstalled(this)) {
            setTermuxPersistentState("NOT_INSTALLED", "Termux is not installed.");
            return;
        }
        if (!TermuxRuntimeController.hasPermission(this)) {
            setTermuxPersistentState("NEEDS_PERMISSION", "Grant Buddy the Termux RUN_COMMAND permission.");
            return;
        }
        boolean bridge = prefs.getBoolean("monitor_bridge", true);
        boolean droid = prefs.getBoolean("monitor_droidmcp", true);
        helperExecutor.execute(() -> {
            if (!stopping) {
                TermuxRuntimeController.reconcile(this, bridge, droid);
                setTermuxPersistentState("RECONCILING", "Requested Bridge=" + bridge + ", DroidMCP=" + droid + " from Termux.");
            }
        });
    }

    private void startConnectionMonitor() {
        if (monitorTask != null && !monitorTask.isDone()) return;
        if (helperExecutor == null) return;
        monitorTask = helperExecutor.scheduleWithFixedDelay(() -> {
            if (stopping) return;
            android.content.SharedPreferences prefs = getSharedPreferences("runtime", MODE_PRIVATE);
            boolean monitorBridge = prefs.getBoolean("monitor_bridge", true);
            boolean monitorDroid = prefs.getBoolean("monitor_droidmcp", true);
            boolean reaToggle = prefs.getBoolean("rea_enabled", true);
            boolean hasApiKey = Secrets.has(this, RuntimeProcessLauncher.API_KEY_SECRET);
            boolean reaInstalled = reaToggle && hasApiKey && ReaRuntimeInstaller.isReady(this);
            boolean bridgePrimary = monitorBridge && RuntimeHealthProbe.isReadyAtPort(TERMUX_BRIDGE_PORT_PRIMARY);
            boolean bridgeLegacy = monitorBridge && RuntimeHealthProbe.isReadyAtPort(TERMUX_BRIDGE_PORT_LEGACY);
            boolean bridgeConflict = monitorBridge && bridgePrimary && bridgeLegacy;
            int bridgePort = bridgePrimary ? TERMUX_BRIDGE_PORT_PRIMARY
                    : bridgeLegacy ? TERMUX_BRIDGE_PORT_LEGACY : 0;
            boolean bridgeTransport = !monitorBridge || (bridgePort > 0 && !bridgeConflict);
            boolean droidTransport = !monitorDroid || RuntimeHealthProbe.isReadyAtPort(DROIDMCP_PORT);
            boolean bridgeMcp = !monitorBridge || (bridgeTransport && RuntimeHealthProbe.isMcpDiscoveredAtPort(bridgePort, 6));
            boolean droidMcp = !monitorDroid || (droidTransport && RuntimeHealthProbe.isMcpDiscoveredAtPort(DROIDMCP_PORT, 5));
            boolean bridgeRemote = !monitorBridge || (bridgeMcp && RuntimeHealthProbe.isRemoteConnectedAtPort(bridgePort, 6));
            boolean droidRemote = !monitorDroid || (droidMcp && RuntimeHealthProbe.isRemoteConnectedAtPort(DROIDMCP_PORT, 5));

            if (!TermuxRuntimeController.isInstalled(this)) {
                setTermuxPersistentState("NOT_INSTALLED", "Install Termux to enable the two external MCP channels.");
            } else if (!TermuxRuntimeController.hasPermission(this)) {
                setTermuxPersistentState("NEEDS_PERMISSION", "Buddy needs the Termux RUN_COMMAND permission.");
            } else if (!prefs.getBoolean("termux_manager_installed", false)) {
                String lastError = prefs.getString("termux_last_error", "");
                setTermuxPersistentState("NOT_CONNECTED", lastError.isEmpty()
                        ? "Use Connect Termux once to install Buddy's separate, reversible manager script."
                        : lastError + " Enable allow-external-apps=true in Termux if RUN_COMMAND was blocked.");
            } else {
                setTermuxPersistentState("CONNECTED", "Buddy manages only Bridge 0.1.3 and DroidMCP 0.4.0; the legacy Termux REA tunnel is not started.");
            }

            if (!monitorBridge) {
                setBridgePersistentState("DISABLED", "Bridge management and monitoring are disabled.");
            } else if (bridgeConflict) {
                setBridgePersistentState("CONFLICT", "Both Termux Bridge health ports 18080 and 8080 are live. Buddy will not start a duplicate.");
            } else if (!bridgeTransport) {
                setBridgePersistentState("STARTING_OR_UNAVAILABLE", "Waiting for the Termux Bridge manager on 127.0.0.1:18080 or :8080.");
            } else if (bridgeRemote) {
                setBridgePersistentState("REMOTE_CONNECTED", "Termux Bridge MCP 6/6; recent control-plane poll and accepted response confirmed on port " + bridgePort);
            } else if (bridgeMcp) {
                setBridgePersistentState("MCP_DISCOVERED", "Bridge MCP 6/6 discovered; waiting for accepted remote delivery on port " + bridgePort);
            } else {
                setBridgePersistentState("TRANSPORT_READY", "Termux Bridge /readyz confirmed on port " + bridgePort + "; waiting for MCP initialize/tools/list.");
            }

            if (!monitorDroid) {
                setDroidPersistentState("DISABLED", "DroidMCP management and monitoring are disabled.");
            } else if (!droidTransport) {
                setDroidPersistentState("STARTING_OR_UNAVAILABLE", "Waiting for Termux DroidMCP on 127.0.0.1:41507.");
            } else if (droidRemote) {
                setDroidPersistentState("REMOTE_CONNECTED", "DroidMCP MCP 5/5; recent control-plane poll and accepted response confirmed.");
            } else if (droidMcp) {
                setDroidPersistentState("MCP_DISCOVERED", "DroidMCP MCP 5/5 discovered; waiting for accepted remote delivery.");
            } else {
                setDroidPersistentState("TRANSPORT_READY", "Termux DroidMCP /readyz confirmed; waiting for MCP initialize/tools/list.");
            }

            if (!reaToggle) {
                reaChannelConfigured = false;
                setReaPersistentState("DISABLED", "REA channel disabled; no separate REA tunnel is created.");
            } else if (!hasApiKey) {
                setReaPersistentState("NEEDS_SETUP", "Buddy API key is required to include the internal REA channel.");
            } else if (reaInstalled && supervisor != null && supervisor.isReady() && reaChannelConfigured) {
                setReaPersistentState("CHANNEL_CONFIGURED", "REA 6.5.0 runs inside Buddy's existing Alpine/PRoot and is exposed as channel 'rea' under the Buddy tunnel. Local doctor: 207/207 tools, 6/6 prompts.");
            } else if (!reaInstalled) {
                setReaPersistentState("INSTALL_REQUIRED", "Buddy-local REA 6.5.0 is not ready yet; the main Buddy tunnel remains independent.");
                if (!retryScheduled) scheduleAttempt(60000);
            } else {
                setReaPersistentState("CHANNEL_PENDING", "REA is installed; Buddy tunnel must restart to attach its MCP channel.");
                if (!retryScheduled) scheduleAttempt(1000);
            }

            boolean mainMcp = supervisor != null && supervisor.isMcpDiscovered(0);
            boolean mainRemote = supervisor != null && supervisor.isRemoteConnected(0);
            if (mainRemote && bridgeRemote && droidRemote) {
                updateNotification(getString(R.string.runtime_notification_ready_with_rea));
                return;
            }
            if (!hasApiKey) {
                updateNotification(getString(R.string.runtime_notification_setup));
            } else if (bridgeConflict) {
                updateNotification(getString(R.string.runtime_notification_termux_conflict));
            } else if ((monitorBridge && !bridgeTransport) || (monitorDroid && !droidTransport)) {
                updateNotification(getString(R.string.runtime_notification_waiting_termux));
            } else if (!mainMcp || !bridgeMcp || !droidMcp) {
                updateNotification(getString(R.string.runtime_notification_waiting_mcp));
            } else if (!mainRemote || !bridgeRemote || !droidRemote) {
                updateNotification(getString(R.string.runtime_notification_waiting_remote));
            }
        }, 0, 15, TimeUnit.SECONDS);
    }

    private void updateMainMcpState() {
        if (supervisor != null && supervisor.isRemoteConnected(0)) {
            setPersistentState("REMOTE_CONNECTED", "MCP catalog discovered; recent control-plane poll and accepted response confirmed");
            updateNotification(getString(R.string.runtime_notification_ready));
        } else if (supervisor != null && supervisor.isMcpDiscovered(0)) {
            setPersistentState("MCP_DISCOVERED", "MCP initialize and complete tools/list confirmed; waiting for recent remote poll and accepted response");
            updateNotification(getString(R.string.runtime_notification_waiting_remote));
        } else {
            setPersistentState("TRANSPORT_READY", "Tunnel Client process and /readyz confirmed; waiting for MCP initialize/tools/list");
            updateNotification(getString(R.string.runtime_notification_waiting_mcp));
        }
    }

    private void startMonitor() {
        if (monitorTask != null && !monitorTask.isDone()) return;
        monitorTask = executor.scheduleWithFixedDelay(() -> {
            if (stopping || supervisor == null) return;
            if (!supervisor.isReady()) {
                String message = supervisor.lastError();
                setPersistentState("DEGRADED", message);
                long delay = recovery.nextDelayMillis();
                updateNotification(getString(R.string.runtime_notification_retry,
                        Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
                scheduleAttempt(delay);
                return;
            }
            updateMainMcpState();
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void requestStop() {
        stopping = true;
        helperLifecycle.markStopping();
        if (monitorTask != null) monitorTask.cancel(true);
        if (retryTask != null) retryTask.cancel(true);
        if (reaMonitorTask != null) reaMonitorTask.cancel(true);
        if (reaRetryTask != null) reaRetryTask.cancel(true);
        if (helperExecutor != null) helperExecutor.shutdownNow();
        if (executor == null) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        executor.execute(() -> {
            if (monitorTask != null) monitorTask.cancel(true);
            if (retryTask != null) retryTask.cancel(true);
            if (supervisor != null) supervisor.stop();
            // The external Termux-owned Bridge and DroidMCP processes are not stopped by Buddy.
            helperLifecycle.cleanup(() -> {
                if (reaSupervisor != null) reaSupervisor.stop();
            });
            setPersistentState("STOPPED", "");
            setBridgePersistentState("NOT_MONITORED", "Buddy monitor stopped; external Termux process is not affected.");
            setDroidPersistentState("NOT_MONITORED", "Buddy monitor stopped; external Termux process is not affected.");
            setReaPersistentState("STOPPED", "");
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
        // Termux owns the external Bridge and DroidMCP processes; Buddy never kills them.
        helperLifecycle.cleanup(() -> {
            if (reaSupervisor != null) reaSupervisor.stop();
        });
        if (executor != null) executor.shutdownNow();
        if (helperExecutor != null) helperExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
