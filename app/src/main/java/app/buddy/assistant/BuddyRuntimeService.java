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
    private static final int TERMUX_BRIDGE_PORT_PRIMARY = 18080;
    private static final int TERMUX_BRIDGE_PORT_LEGACY = 8080;
    private static final int DROIDMCP_PORT = 41507;
    private RuntimeSupervisor reaSupervisor;
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
        reaSupervisor = new RuntimeSupervisor(app, new java.io.File(app.getFilesDir(), "runtime/rea-tunnel"),
                () -> RuntimeProcessLauncher.startReaConfigured(app), false);
        helperExecutor = Executors.newScheduledThreadPool(2, r -> {
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
        scheduleReaAttempt(0);
        startReaMonitor();
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
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
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

    private synchronized void scheduleReaAttempt(long delayMs) {
        if (helperExecutor == null || stopping || reaRetryScheduled) return;
        reaRetryScheduled = true;
        reaRetryTask = helperExecutor.schedule(() -> {
            synchronized (BuddyRuntimeService.this) { reaRetryScheduled = false; }
            attemptReaStart();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void attemptReaStart() {
        if (stopping || reaSupervisor == null) return;
        try {
            ReaRuntimeInstaller.ensureReady(getApplicationContext(),
                    message -> {
                        if (!stopping) {
                            updateNotification(getString(R.string.runtime_notification_rea_installing, message));
                        }
                    });
            if (stopping || Thread.currentThread().isInterrupted()) return;

            // The real Termux Bridge and DroidMCP are already managed by Termux.
            // Buddy only observes their loopback health endpoints and never starts a duplicate
            // Tunnel Client with the same remote tunnel IDs.
            boolean reaReady = RuntimeHelperBootstrap.startOne(helperLifecycle,
                    () -> reaSupervisor.start(),
                    (state, detail) -> setReaPersistentState(state, detail),
                    "Transport ready; awaiting MCP discovery (207 tools, 6 prompts expected)",
                    () -> reaSupervisor.lastError());
            if (stopping || Thread.currentThread().isInterrupted()) return;

            if (reaReady) {
                reaRecovery.reset();
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
            Log.w(TAG, "Helper runtime bootstrap failed: " + message);
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

            boolean bridgeTransport = bridgeSupervisor != null && bridgeSupervisor.isReady();
            boolean droidTransport = droidMcpSupervisor != null && droidMcpSupervisor.isReady();
            boolean reaTransport = reaSupervisor != null && reaSupervisor.isReady();

            boolean bridgeMcp = bridgeTransport && bridgeSupervisor.isMcpDiscovered(6);
            boolean droidMcp = droidTransport && droidMcpSupervisor.isMcpDiscovered(5);
            boolean reaMcp = reaTransport && reaSupervisor.isMcpDiscovered(207);
            boolean bridgeRemote = bridgeMcp && bridgeSupervisor.isRemoteConnected(6);
            boolean droidRemote = droidMcp && droidMcpSupervisor.isRemoteConnected(5);
            boolean reaRemote = reaMcp && reaSupervisor.isRemoteConnected(207);

            if (!bridgeTransport) {
                setBridgePersistentState("DEGRADED", bridgeSupervisor == null ? "Supervisor unavailable" : bridgeSupervisor.lastError());
            } else if (bridgeRemote) {
                setBridgePersistentState("REMOTE_CONNECTED", "MCP 6/6; recent control-plane poll and accepted response confirmed");
            } else if (bridgeMcp) {
                setBridgePersistentState("MCP_DISCOVERED", "MCP 6/6 confirmed; waiting for recent remote poll and accepted response");
            } else {
                setBridgePersistentState("TRANSPORT_READY", "Process and /readyz confirmed; waiting for MCP initialize/tools/list (6 expected)");
            }

            if (!droidTransport) {
                setDroidPersistentState("DEGRADED", droidMcpSupervisor == null ? "Supervisor unavailable" : droidMcpSupervisor.lastError());
            } else if (droidRemote) {
                setDroidPersistentState("REMOTE_CONNECTED", "MCP 5/5; recent control-plane poll and accepted response confirmed");
            } else if (droidMcp) {
                setDroidPersistentState("MCP_DISCOVERED", "MCP 5/5 confirmed; waiting for recent remote poll and accepted response");
            } else {
                setDroidPersistentState("TRANSPORT_READY", "Process and /readyz confirmed; waiting for MCP initialize/tools/list (5 expected)");
            }

            if (!reaTransport) {
                setReaPersistentState("DEGRADED", reaSupervisor == null ? "Supervisor unavailable" : reaSupervisor.lastError());
            } else if (reaRemote) {
                setReaPersistentState("REMOTE_CONNECTED", "MCP 207/207; recent control-plane poll and accepted response confirmed; 6/6 prompts prevalidated");
            } else if (reaMcp) {
                setReaPersistentState("MCP_DISCOVERED", "MCP 207/207 confirmed; waiting for recent remote poll and accepted response; 6/6 prompts prevalidated");
            } else {
                setReaPersistentState("TRANSPORT_READY", "Process and /readyz confirmed; waiting for MCP initialize/tools/list (207 expected; 6 prompts prevalidated)");
            }

            if (bridgeRemote && droidRemote && reaRemote
                    && supervisor != null && supervisor.isRemoteConnected(0)) {
                reaRecovery.reset();
                updateNotification(getString(R.string.runtime_notification_ready_with_rea));
                return;
            }

            boolean transportFailure = !bridgeTransport || !droidTransport || !reaTransport;
            if (transportFailure) {
                long delay = reaRecovery.nextDelayMillis();
                updateNotification(getString(R.string.runtime_notification_rea_retry,
                        Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
                scheduleReaAttempt(delay);
            } else if (bridgeMcp && droidMcp && reaMcp) {
                reaRecovery.reset();
                updateNotification(getString(R.string.runtime_notification_waiting_remote));
            } else {
                updateNotification(getString(R.string.runtime_notification_waiting_mcp));
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
            helperLifecycle.cleanup(() -> {
                if (bridgeSupervisor != null) bridgeSupervisor.stop();
                if (droidMcpSupervisor != null) droidMcpSupervisor.stop();
                if (reaSupervisor != null) reaSupervisor.stop();
            });
            setPersistentState("STOPPED", "");
            setBridgePersistentState("STOPPED", "");
            setDroidPersistentState("STOPPED", "");
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
        helperLifecycle.cleanup(() -> {
            if (bridgeSupervisor != null) bridgeSupervisor.stop();
            if (droidMcpSupervisor != null) droidMcpSupervisor.stop();
            if (reaSupervisor != null) reaSupervisor.stop();
        });
        if (executor != null) executor.shutdownNow();
        if (helperExecutor != null) helperExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
