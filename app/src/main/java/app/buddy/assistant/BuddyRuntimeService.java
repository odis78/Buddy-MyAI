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
    private RuntimeSupervisor reaSupervisor;
    private RuntimeRecoveryPolicy recovery;
    private ScheduledFuture<?> monitorTask;
    private ScheduledFuture<?> retryTask;
    private ScheduledFuture<?> reaMonitorTask;
    private ScheduledFuture<?> reaRetryTask;
    private RuntimeRecoveryPolicy reaRecovery;
    private volatile boolean stopping;
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
                () -> RuntimeProcessLauncher.startReaConfigured(app), true);
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
        stopping = false;
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
            boolean ready = supervisor.start();
            if (ready) {
                recovery.reset();
                setPersistentState("READY", "");
                updateNotification(getString(R.string.runtime_notification_ready));
                startMonitor();
                scheduleReaAttempt(0);
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
                    message -> updateNotification(getString(R.string.runtime_notification_rea_installing, message)));
            boolean ready = reaSupervisor.start();
            if (ready) {
                reaRecovery.reset();
                setReaPersistentState("READY", "207 tools; 6 prompts");
                updateNotification(getString(R.string.runtime_notification_ready_with_rea));
                startReaMonitor();
            } else {
                String message = reaSupervisor.lastError();
                setReaPersistentState("WAITING", message);
                long delay = reaRecovery.nextDelayMillis();
                updateNotification(getString(R.string.runtime_notification_rea_retry,
                        Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
                scheduleReaAttempt(delay);
            }
        } catch (Exception e) {
            if (stopping) return;
            String message = safeMessage(e);
            setReaPersistentState("ERROR", message);
            Log.w(TAG, "REA runtime bootstrap failed: " + message);
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
            if (stopping || reaSupervisor == null) return;
            if (reaSupervisor.isReady()) return;
            String message = reaSupervisor.lastError();
            setReaPersistentState("DEGRADED", message);
            long delay = reaRecovery.nextDelayMillis();
            updateNotification(getString(R.string.runtime_notification_rea_retry,
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
            scheduleReaAttempt(delay);
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void startMonitor() {
        if (monitorTask != null && !monitorTask.isDone()) return;
        monitorTask = executor.scheduleWithFixedDelay(() -> {
            if (stopping || supervisor == null) return;
            if (supervisor.isReady()) return;
            String message = supervisor.lastError();
            setPersistentState("DEGRADED", message);
            long delay = recovery.nextDelayMillis();
            updateNotification(getString(R.string.runtime_notification_retry,
                    Math.max(1, TimeUnit.MILLISECONDS.toSeconds(delay))));
            scheduleAttempt(delay);
        }, 15, 15, TimeUnit.SECONDS);
    }

    private void requestStop() {
        stopping = true;
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
            if (reaSupervisor != null) reaSupervisor.stop();
            setPersistentState("STOPPED", "");
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
        if (monitorTask != null) monitorTask.cancel(true);
        if (retryTask != null) retryTask.cancel(true);
        if (reaMonitorTask != null) reaMonitorTask.cancel(true);
        if (reaRetryTask != null) reaRetryTask.cancel(true);
        if (supervisor != null) supervisor.stop();
        if (reaSupervisor != null) reaSupervisor.stop();
        if (executor != null) executor.shutdownNow();
        if (helperExecutor != null) helperExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
