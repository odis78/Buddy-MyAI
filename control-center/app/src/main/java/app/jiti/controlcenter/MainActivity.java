package app.jiti.controlcenter;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

import app.jiti.controlcenter.core.CommandGate;
import app.jiti.controlcenter.core.OperationCoordinator;
import app.jiti.controlcenter.core.ProfileStackPlanner;
import app.jiti.controlcenter.core.InFlightGate;
import app.jiti.controlcenter.core.ComponentRegistry;
import app.jiti.controlcenter.core.ComponentSpec;
import app.jiti.controlcenter.core.EvidenceStatus;
import app.jiti.controlcenter.core.ToolCatalog;
import app.jiti.controlcenter.core.ToolSpec;
import app.jiti.controlcenter.termux.TermuxCommandClient;
import app.jiti.controlcenter.termux.TermuxCommandResult;
import app.jiti.controlcenter.termux.TermuxOperation;
import app.jiti.controlcenter.termux.TunnelProfileIdentity;
import app.jiti.controlcenter.service.MonitorService;
import app.jiti.controlcenter.storage.EventStore;
import app.jiti.controlcenter.core.DiagnosticEvent;
import app.jiti.controlcenter.core.ComponentStatus;
import app.jiti.controlcenter.core.RuntimeSnapshot;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static final String EXPECTED_TUNNELS_SCRIPT_SHA256 = "1efc8db1cc8b7e91a7f16546e5adad1206a0649b036dcc2afa048a4a87056b54";
    private static final int BG = 0xFF10151D;
    private static final int CARD = 0xFF1B2430;
    private static final int FG = 0xFFF1F5F9;
    private static final int MUTED = 0xFF9AA8B8;
    private static final int AMBER = 0xFFFFC46B;
    private static final int GREEN = 0xFF7DD3A7;
    private final ComponentRegistry registry = ComponentRegistry.historicalBaseline();
    private EventStore eventStore;
    private final CommandGate commandGate = new CommandGate(
            new HashSet<>(Arrays.asList("buddy.start", "buddy.stop",
                    "profile.start", "profile.stop", "profile.restart"))); 
    private final OperationCoordinator profileOperationCoordinator = OperationCoordinator.shared();
    private final Map<Long, Runnable> queuedManualActions = new HashMap<>();
    private final Map<String, List<Button>> profileActionButtons = new HashMap<>();
    private Button globalCheckButton;
    private Button globalStartButton;
    private Button globalRestartButton;
    private Button globalStopButton;
    private TextView globalControlStatusView;
    private TextView globalControlOutputView;
    private TextView eventLogView;
    private TextView termuxStatusView;
    private Button termuxCheckButton;
    private Button monitorStartButton;
    private Button monitorStopButton;
    private Button buddyStartButton;
    private Button buddyStopButton;
    private Button buddyRestartButton;
    private TextView buddyControlStatusView;
    private final Map<String, TextView> liveStatusViews = new HashMap<>();
    private static final int REQUEST_NOTIFICATIONS = 4201;
    private boolean diagnosticsInFlight;
    private static final InFlightGate BUDDY_OPERATION_GATE = new InFlightGate();
    private long buddyLaunchRequestedAtEpochSec;
    private int buddyStatusPollAttempts;
    private static final int MAX_BUDDY_STATUS_POLLS = 4;
    private final android.os.Handler uiRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable uiRefreshRunnable = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            refreshEventLog();
            refreshLiveStatuses();
            refreshTermuxControls();
            uiRefreshHandler.postDelayed(this, 15000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        eventStore = new EventStore(this);
        saveProbeSnapshot("jiti-control-center", ComponentStatus.RUNNING, "MainActivity active");
        saveProbeSnapshot("jiti-event-store", ComponentStatus.AVAILABLE, "SQLite event journal available");
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(28));
        scroll.addView(page);

        page.addView(text("JITI CONTROL CENTER", 22, FG, true));
        addTop(page, text("Реестр инфраструктуры • 0.2.0-alpha", 13, MUTED, false), 5);
        addCard(page, text("РЕЖИМ РУЧНОГО УПРАВЛЕНИЯ. После безопасной проверки Termux доступны подтверждаемые операции с каждым REA-туннелем и последовательное управление REA-стеком. Buddy управляется отдельно и не затрагивается общими командами. Автоматическое восстановление пока отключено до завершения тестов.", 13, AMBER, true), 12);

        addSection(page, "Управление системой");
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        globalControlStatusView = text("Ручное управление готовится после безопасной проверки Termux.", 11, MUTED, false);
        globalControlOutputView = text("Проверка ещё не запускалась.", 10, MUTED, false);
        globalCheckButton = button("Проверить всё");
        globalCheckButton.setOnClickListener(v -> runTermuxDiagnostics(globalCheckButton, globalControlOutputView));
        globalStartButton = button("Запустить всё");
        globalStartButton.setOnClickListener(v -> requestProfileStackOperation("start"));
        globalRestartButton = button("Перезапустить всё");
        globalRestartButton.setOnClickListener(v -> requestProfileStackOperation("restart"));
        globalStopButton = button("Остановить всё");
        globalStopButton.setOnClickListener(v -> requestProfileStackOperation("stop"));
        controls.addView(globalCheckButton, matchWrap());
        controls.addView(globalStartButton, matchWrap());
        controls.addView(globalRestartButton, matchWrap());
        controls.addView(globalStopButton, matchWrap());
        addTop(controls, text("Команды «всё» управляют тремя REA-туннелями (Bridge, DroidMCP, REA Android). Buddy независим и этими командами не затрагивается.", 10, MUTED, false), 5);
        addTop(controls, globalControlStatusView, 7);
        addTop(controls, globalControlOutputView, 5);
        addCard(page, controls, 8);

        addTermuxSection(page);

        addSection(page, "Реестр компонентов (" + registry.all().size() + ")");
        String currentGroup = "";
        for (ComponentSpec item : registry.all()) {
            if (!item.category.equals(currentGroup)) {
                currentGroup = item.category;
                addTop(page, text(currentGroup.toUpperCase(), 11, MUTED, true), 14);
            }
            addComponentCard(page, item);
        }

        addSection(page, "Каталог MCP-функций (" + ToolCatalog.baseCount() + " базовых + " + ToolCatalog.optionalCount() + " браузерных)");
        addCard(page, text("Аудит исходников Buddy 2.3.17-JITI.1: 44 базовых инструмента и ещё 8 инструментов фонового браузера. Все 44 базовых имени точно совпадают с текущим каталогом Buddy MCP; 8 браузерных инструментов в этом подключении не опубликованы. Текущую настройку Buddy приложение пока не читает.", 12, MUTED, false), 8);
        String currentToolGroup = "";
        StringBuilder toolNames = new StringBuilder();
        for (ToolSpec tool : ToolCatalog.all()) {
            if (!tool.group.equals(currentToolGroup)) {
                if (toolNames.length() > 0) addCard(page, text(toolNames.toString(), 12, FG, false), 6);
                currentToolGroup = tool.group;
                toolNames = new StringBuilder(currentToolGroup).append(":\n");
            }
            if (toolNames.charAt(toolNames.length() - 1) != '\n') toolNames.append(", ");
            toolNames.append(tool.name);
            if (tool.risk == ToolSpec.Risk.PRIVILEGED) toolNames.append(" [PRIVILEGED]");
            else if (tool.risk == ToolSpec.Risk.SENSITIVE_READ) toolNames.append(" [SENSITIVE]");
        }
        if (toolNames.length() > 0) addCard(page, text(toolNames.toString(), 12, FG, false), 6);

        addSection(page, "Диагностика");
        addCard(page, text("Текущая live-проверка запускается вручную в блоке Termux выше. MCP probe пока проверяет только локальный listener и 401 challenge; authenticated initialize ещё не реализован.", 13, MUTED, false), 8);

        addSection(page, "Журнал событий");
        eventLogView = text(buildEventLog(), 11, MUTED, false);
        addCard(page, eventLogView, 8);
        setContentView(scroll);
    }



    private void addTermuxSection(LinearLayout page) {
        addSection(page, "Подключение Termux");
        TermuxCommandClient.InstallState state = TermuxCommandClient.inspect(this);
        String status = (state.installed ? "Termux " + state.versionName + " • " : "") + state.message
                + "\n" + termuxPolicyText();
        termuxStatusView = text(status + "\nМониторинг удерживает CPU активным между проверками и расходует больше батареи; остановка снимает wake lock. Настройки Termux JITI не меняет автоматически.", 12,
                termuxBridgeVerified() ? GREEN : AMBER, true);
        addCard(page, termuxStatusView, 8);

        TextView output = text("Проверка ещё не запускалась.", 11, MUTED, false);
        termuxCheckButton = button("Безопасная проверка Termux");
        termuxCheckButton.setEnabled(state.canInvoke());
        termuxCheckButton.setOnClickListener(v -> runTermuxDiagnostics(termuxCheckButton, output));

        Button settings = button("Настройки JITI");
        settings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(termuxCheckButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row1.addView(settings, new LinearLayout.LayoutParams(0, dp(44), 1f));

        monitorStartButton = button("Запустить мониторинг");
        monitorStartButton.setEnabled(state.canInvoke() && !MonitorService.running);
        monitorStartButton.setOnClickListener(v -> requestNotificationAndStart());
        monitorStopButton = button("Остановить мониторинг");
        monitorStopButton.setEnabled(MonitorService.running);
        monitorStopButton.setOnClickListener(v -> {
            MonitorService.stop(this);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshTermuxControls, 500);
        });

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(monitorStartButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row2.addView(monitorStopButton, new LinearLayout.LayoutParams(0, dp(44), 1f));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(row1);
        addTop(card, row2, 6);

        buddyControlStatusView = text("Управление Buddy заблокировано до проверки Termux и запуска монитора.", 11, MUTED, false);
        buddyStartButton = button("Запустить Buddy");
        buddyStartButton.setOnClickListener(v -> requestBuddyStart());
        buddyStopButton = button("Остановить Buddy");
        buddyStopButton.setOnClickListener(v -> confirmBuddyStop());
        buddyRestartButton = button("Перезапустить Buddy");
        buddyRestartButton.setOnClickListener(v -> confirmBuddyRestart());
        LinearLayout row3 = new LinearLayout(this);
        row3.addView(buddyStartButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row3.addView(buddyStopButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        LinearLayout row4 = new LinearLayout(this);
        row4.addView(buddyRestartButton, new LinearLayout.LayoutParams(0, dp(44), 1f));
        addTop(card, buddyControlStatusView, 8);
        addTop(card, row3, 4);
        addTop(card, row4, 4);
        addTop(card, output, 8);
        addCard(page, card, 8);
    }

    private boolean acquireBuddyOperation(String requestedAction) {
        if (BUDDY_OPERATION_GATE.tryAcquire()) return true;
        String message = "Другая операция Buddy ещё выполняется. Дождитесь её результата; повторная команда не отправлена.";
        if (buddyControlStatusView != null) buddyControlStatusView.setText(message);
        logControlEvent("buddy-tunnel", "OPERATION_BUSY", "Rejected duplicate Buddy " + requestedAction + " request while another operation is in flight");
        return false;
    }

    private void requestBuddyStart() {
        preflightBuddyStart(true);
    }

    private void preflightBuddyStart(boolean askConfirmation) {
        if (!termuxBridgeVerified() || !MonitorService.running) {
            refreshTermuxControls();
            return;
        }
        if (!acquireBuddyOperation("запуск")) return;
        refreshTermuxControls();
        buddyControlStatusView.setText("Проверка бинарника, YAML, env-файла и PID перед запуском…");
        TermuxCommandClient.runBuddyLaunchPreflight(this, result -> {
            if (isFinishing() || isDestroyed()) { finishBuddyOperation(); return; }
            JSONObject report = null;
            try { report = new JSONObject(result.stdout); } catch (Exception ignored) { }
            String state = report == null ? "invalid_preflight_result" : report.optString("result");
            if (!result.succeeded() || report == null) {
                buddyControlStatusView.setText("Preflight не выполнен: " + result.internalErrorMessage);
                logControlEvent("buddy-tunnel", "START_PREFLIGHT_FAILED", "Buddy launch preflight command failed");
                finishBuddyOperation();
                return;
            }
            if ("already_running".equals(state)) {
                int pid = report.optInt("pid", 0);
                buddyControlStatusView.setText("Buddy Tunnel Client уже запущен; PID=" + pid + ". Дубликат не создавался.");
                saveProbeSnapshot("tunnel-client", ComponentStatus.RUNNING, "Launch preflight verified existing binary and Buddy config");
                MonitorService.pollNow(this);
                finishBuddyOperation();
                return;
            }
            if (!"ready_to_start".equals(state)) {
                String reason = report.optString("reason", state);
                buddyControlStatusView.setText("Запуск заблокирован: " + reason
                        + (report.has("missing_keys") ? " • отсутствуют ключи: " + report.optJSONArray("missing_keys") : ""));
                saveProbeSnapshot("buddy-tunnel", ComponentStatus.BLOCKED, "Buddy launch preflight: " + reason);
                logControlEvent("buddy-tunnel", "START_BLOCKED", "Buddy launch preflight blocked: " + reason);
                finishBuddyOperation();
                return;
            }
            String message = "Проверки прошли. Будет запущен существующий Tunnel Client v0.0.19 с текущим Buddy YAML. "
                    + "JITI не меняет YAML и runtime.env. Процесс будет запущен как управляемая фоновая задача Termux.";
            if (report.optBoolean("stale_pid_file")) {
                message += " Обнаружен устаревший PID-файл; он будет удалён только после повторной проверки, что PID не существует.";
            }
            if (askConfirmation) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Запустить Buddy Tunnel?")
                        .setMessage(message)
                        .setPositiveButton("Запустить", (dialog, which) -> performBuddyStart())
                        .setNegativeButton("Отмена", (dialog, which) -> finishBuddyOperation())
                        .setOnCancelListener(dialog -> finishBuddyOperation())
                        .show();
            } else {
                performBuddyStart();
            }
            refreshTermuxControls();
        });
    }

    private void performBuddyStart() {
        if (!BUDDY_OPERATION_GATE.isInFlight()) return;
        if (!commandGate.mayExecute("buddy.start", true, true, true)) {
            buddyControlStatusView.setText("CommandGate заблокировал запуск: отсутствует подтверждение или проверка контракта/цели.");
            logControlEvent("buddy-tunnel", "COMMAND_GATE_BLOCKED", "Buddy start rejected by CommandGate");
            finishBuddyOperation();
            return;
        }
        buddyLaunchRequestedAtEpochSec = System.currentTimeMillis() / 1000L;
        buddyStatusPollAttempts = 0;
        String error = TermuxCommandClient.startBuddyTunnel(this);
        if (!error.isEmpty()) {
            buddyControlStatusView.setText(error);
            logControlEvent("buddy-tunnel", "START_REQUEST_FAILED", error);
            finishBuddyOperation();
            return;
        }
        buddyControlStatusView.setText("Запрос запуска отправлен в Termux RUN_COMMAND. Ожидается подтверждение процесса и healthz/readyz…");
        saveProbeSnapshot("buddy-tunnel", ComponentStatus.STARTING, "Termux background task start requested; health verification pending");
        logControlEvent("buddy-tunnel", "START_REQUESTED", "Buddy Tunnel Client start requested through Termux RUN_COMMAND");
        scheduleBuddyLauncherStatusRead();
        MonitorService.pollNow(this);
        refreshTermuxControls();
    }

    private void scheduleBuddyLauncherStatusRead() {
        uiRefreshHandler.postDelayed(this::readBuddyLauncherStatus, 1200L);
    }

    private void finishBuddyStatusPolling(String message) {
        buddyControlStatusView.setText(message);
        finishBuddyOperation();
    }

    private void retryBuddyLauncherStatus(String message) {
        buddyStatusPollAttempts++;
        if (buddyStatusPollAttempts < MAX_BUDDY_STATUS_POLLS) {
            buddyControlStatusView.setText(message + " Повторная проверка "
                    + (buddyStatusPollAttempts + 1) + "/" + MAX_BUDDY_STATUS_POLLS + "…");
            scheduleBuddyLauncherStatusRead();
        } else {
            finishBuddyStatusPolling(message + " Новое подтверждение от Termux не получено; запуск не считается подтверждённым.");
        }
    }

    private void readBuddyLauncherStatus() {
        TermuxCommandClient.runBuddyLaunchStatus(this, result -> {
            if (isFinishing() || isDestroyed()) { finishBuddyOperation(); return; }
            if (!result.succeeded()) {
                retryBuddyLauncherStatus("Статус запуска пока недоступен: " + result.internalErrorMessage + ".");
                return;
            }
            try {
                JSONObject report = new JSONObject(result.stdout);
                String state = report.optString("result", "unknown");
                long timestamp = report.optLong("timestamp", 0L);
                long now = System.currentTimeMillis() / 1000L;
                if (!app.jiti.controlcenter.termux.BuddyLaunchStatusPolicy.isFreshForRequest(
                        timestamp, now, buddyLaunchRequestedAtEpochSec)) {
                    retryBuddyLauncherStatus("Получен старый статус Buddy Launcher; ожидаю подтверждение именно этой команды.");
                    return;
                }
                if ("exec_failed".equals(state) || "blocked".equals(state)) {
                    buddyControlStatusView.setText("Запуск Buddy заблокирован: " + state
                            + (report.has("reason") ? " • " + report.optString("reason") : ""));
                    saveProbeSnapshot("buddy-tunnel", ComponentStatus.NOT_READY, "Launcher status: " + state);
                    logControlEvent("buddy-tunnel", "START_FAILED", "Buddy launcher status: " + state);
                } else if ("launch_requested".equals(state)) {
                    buddyControlStatusView.setText("Termux подтвердил передачу команды Tunnel Client. Проверка реального процесса и healthz/readyz продолжается; туннель пока не объявлен готовым.");
                    MonitorService.pollNow(this);
                } else if ("already_running".equals(state)) {
                    buddyControlStatusView.setText("Buddy уже работал до запроса. Проверка процесса и healthz/readyz продолжается.");
                    MonitorService.pollNow(this);
                } else {
                    buddyControlStatusView.setText("Launcher: " + state
                            + (report.has("reason") ? " • " + report.optString("reason") : ""));
                }
            } catch (Exception e) {
                retryBuddyLauncherStatus("Не удалось разобрать статус запуска Buddy.");
                return;
            }
            finishBuddyOperation();
        });
    }

    private void confirmBuddyStop() {
        preflightBuddyStop(false);
    }

    private void confirmBuddyRestart() {
        preflightBuddyStop(true);
    }

    private void preflightBuddyStop(boolean restart) {
        if (!acquireBuddyOperation("остановка/перезапуск")) return;
        refreshTermuxControls();
        buddyControlStatusView.setText("Read-only preflight: проверка PID, executable и точного Buddy YAML…");
        TermuxCommandClient.runBuddyStopPreflight(this, result -> {
            if (isFinishing() || isDestroyed()) { finishBuddyOperation(); return; }
            JSONObject report = null;
            try { report = new JSONObject(result.stdout); } catch (Exception ignored) { }
            String state = report == null ? "invalid_stop_preflight" : report.optString("result");
            if (!result.succeeded() || report == null) {
                buddyControlStatusView.setText("Stop preflight не выполнен: " + result.internalErrorMessage);
                logControlEvent("buddy-tunnel", "STOP_PREFLIGHT_FAILED", "Buddy stop identity preflight failed");
                finishBuddyOperation();
                return;
            }
            if ("ready_to_stop".equals(state)) {
                int pid = report.optInt("pid", 0);
                String title = restart ? "Перезапустить Buddy Tunnel?" : "Остановить Buddy Tunnel?";
                String message = "PID " + pid + " совпадает с точным Tunnel Client binary и Buddy YAML. "
                        + "После подтверждения будет отправлен только SIGTERM. SIGKILL и массовое завершение процессов запрещены.";
                new android.app.AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton(restart ? "Перезапустить" : "Остановить",
                                (dialog, which) -> performBuddyStop(restart ? () -> preflightBuddyStart(false) : null))
                        .setNegativeButton("Отмена", (dialog, which) -> finishBuddyOperation())
                        .setOnCancelListener(dialog -> finishBuddyOperation())
                        .show();
                return;
            }
            if ("already_stopped".equals(state) || "stale_pid_file".equals(state)) {
                if (restart) {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Buddy уже остановлен")
                            .setMessage("Работающий процесс не найден. JITI повторит launch preflight и запустит только проверенный Buddy профиль.")
                            .setPositiveButton("Запустить", (dialog, which) -> {
                                finishBuddyOperation();
                                preflightBuddyStart(false);
                            })
                            .setNegativeButton("Отмена", (dialog, which) -> finishBuddyOperation())
                            .setOnCancelListener(dialog -> finishBuddyOperation())
                            .show();
                } else {
                    buddyControlStatusView.setText("Buddy уже остановлен. Устаревший PID-файл не удалялся.");
                    finishBuddyOperation();
                }
                return;
            }
            String reason = report.optString("reason", state);
            buddyControlStatusView.setText("Остановка заблокирована: " + state + " • " + reason
                    + ". Процесс не затрагивался.");
            saveProbeSnapshot("buddy-tunnel", ComponentStatus.BLOCKED, "Buddy stop preflight: " + state);
            logControlEvent("buddy-tunnel", "STOP_BLOCKED", "Buddy stop preflight blocked: " + state);
            finishBuddyOperation();
        });
    }

    private void performBuddyStop(Runnable afterStopped) {
        if (!BUDDY_OPERATION_GATE.isInFlight()) return;
        if (!commandGate.mayExecute("buddy.stop", true, true, true)) {
            buddyControlStatusView.setText("CommandGate заблокировал остановку: отсутствует подтверждение или проверка PID identity.");
            logControlEvent("buddy-tunnel", "COMMAND_GATE_BLOCKED", "Buddy stop rejected by CommandGate");
            finishBuddyOperation();
            return;
        }
        buddyControlStatusView.setText("Повторная проверка PID identity и отправка SIGTERM…");
        buddyStopButton.setEnabled(false);
        TermuxCommandClient.runBuddyStop(this, result -> {
            if (isFinishing() || isDestroyed()) { finishBuddyOperation(); return; }
            JSONObject report = null;
            try { report = new JSONObject(result.stdout); } catch (Exception ignored) { }
            String state = report == null ? "invalid_stop_result" : report.optString("result");
            if (!result.succeeded()) state = "stop_command_failed";
            Runnable followUp = null;
            if (result.succeeded() && ("stopped".equals(state) || "already_stopped".equals(state) || "stale_pid_file".equals(state))) {
                buddyControlStatusView.setText("Остановка подтверждена: " + state + ". SIGKILL не применялся.");
                saveProbeSnapshot("tunnel-client", ComponentStatus.STOPPED, "Buddy stop result: " + state);
                saveProbeSnapshot("buddy-tunnel", ComponentStatus.STOPPED, "Buddy Tunnel Client stopped or not running");
                logControlEvent("buddy-tunnel", "STOP_CONFIRMED", "Buddy stop result: " + state);
                MonitorService.pollNow(this);
                followUp = afterStopped;
            } else {
                String reason = report == null ? result.internalErrorMessage : report.optString("reason", state);
                buddyControlStatusView.setText("Остановка не подтверждена: " + state + " • " + reason
                        + ". Процесс не принудительно завершался.");
                saveProbeSnapshot("buddy-tunnel", "pid_identity_mismatch".equals(state)
                        ? ComponentStatus.BLOCKED : ComponentStatus.NOT_READY, "Buddy stop result: " + state);
                logControlEvent("buddy-tunnel", "STOP_NOT_CONFIRMED", "Buddy stop result: " + state);
            }
            finishBuddyOperation();
            if (followUp != null) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(followUp, 500L);
            }
        });
    }

    private void finishBuddyOperation() {
        BUDDY_OPERATION_GATE.release();
        refreshTermuxControls();
    }

    private void logControlEvent(String component, String type, String message) {
        if (eventStore != null) {
            eventStore.append(new DiagnosticEvent(System.currentTimeMillis(), component, type, message));
            refreshEventLog();
        }
    }

    private void runTermuxDiagnostics(Button button, TextView output) {
        if (diagnosticsInFlight) return;
        diagnosticsInFlight = true;
        refreshTermuxControls();
        output.setText("Read-only preflight запущен…");
        runTermuxStep(button, output, 0);
    }

    private void runTermuxStep(Button button, TextView output, int index) {
        TermuxOperation[] operations = new TermuxOperation[]{
                TermuxOperation.IDENTITY,
                TermuxOperation.TUNNEL_CLIENT_VERSION,
                TermuxOperation.CURL_VERSION,
                TermuxOperation.PYTHON_VERSION,
                TermuxOperation.BUDDY_ENV_SOURCE_AUDIT,
                TermuxOperation.TUNNELS_SCRIPT_AUDIT,
                TermuxOperation.TUNNELS_SCRIPT_SHA256,
                TermuxOperation.MCP_ENDPOINT_AUDIT,
                TermuxOperation.BUDDY_TUNNEL_HEALTH,
                TermuxOperation.BRIDGE_TUNNEL_HEALTH,
                TermuxOperation.DROIDMCP_TUNNEL_HEALTH,
                TermuxOperation.REA_ANDROID_TUNNEL_HEALTH,
                TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE,
                TermuxOperation.PROCESS_LIST
        };
        if (index >= operations.length) {
            output.append("\n\nRead-only preflight завершён. MCP 401 подтверждает только доступность listener и требование авторизации, но не успешную аутентификацию.");
            diagnosticsInFlight = false;
            refreshTermuxControls();
            return;
        }
        TermuxOperation operation = operations[index];
        output.append("\n\n[" + operationLabel(operation) + "] running…");
        TermuxCommandClient.run(this, operation, result -> {
            if (isFinishing() || isDestroyed()) {
                diagnosticsInFlight = false;
                return;
            }
            appendCommandResult(output, operation, result);
            if (operation == TermuxOperation.IDENTITY && !result.succeeded()) {
                output.append("\nRemaining probes skipped: RUN_COMMAND did not return a successful result.");
                diagnosticsInFlight = false;
                refreshTermuxControls();
                return;
            }
            if (isReaProfileHealthOperation(operation)) {
                verifyReaProfileIdentity(operation, result,
                        () -> runTermuxStep(button, output, index + 1));
            } else {
                runTermuxStep(button, output, index + 1);
            }
        });
    }

    private String operationLabel(TermuxOperation operation) {
        switch (operation) {
            case IDENTITY: return "TERMUX IDENTITY";
            case TUNNEL_CLIENT_VERSION: return "TUNNEL CLIENT VERSION";
            case CURL_VERSION: return "CURL VERSION";
            case PYTHON_VERSION: return "PYTHON VERSION";
            case BUDDY_ENV_SOURCE_AUDIT: return "BUDDY ENV SOURCE AUDIT (NAMES ONLY)";
            case TUNNELS_SCRIPT_AUDIT: return "EXISTING TUNNELS SCRIPT (READ ONLY)";
            case TUNNELS_SCRIPT_SHA256: return "TUNNELS MANAGER SHA-256";
            case MCP_ENDPOINT_AUDIT: return "REDACTED MCP ENDPOINT CONFIG AUDIT";
            case BUDDY_TUNNEL_HEALTH: return "BUDDY TUNNEL HEALTH";
            case BRIDGE_TUNNEL_HEALTH: return "REA-TERMUX-BRIDGE HEALTH";
            case DROIDMCP_TUNNEL_HEALTH: return "DROIDMCP HEALTH";
            case REA_ANDROID_TUNNEL_HEALTH: return "REA-TERMUX-ANDROID HEALTH";
            case BUDDY_MCP_LISTENER_CHALLENGE: return "BUDDY MCP AUTH CHALLENGE";
            case PROCESS_LIST: return "PROCESS INVENTORY";
            default: return operation.name();
        }
    }

    private void appendCommandResult(TextView output, TermuxOperation operation, TermuxCommandResult result) {
        output.append("\nexit=" + result.exitCode + " termuxErr=" + result.internalError);
        if (!result.internalErrorMessage.isEmpty()) output.append("\n" + result.internalErrorMessage);
        String body = result.stdout;
        if (operation == TermuxOperation.BUDDY_ENV_SOURCE_AUDIT) {
            if (result.exitCode == 1) body = "No matching variable names found in the known files.";
            else if (result.exitCode == 2) body = (body.isEmpty() ? "" : body + "\n")
                    + "Partial scan: some candidate files may be missing or unreadable. Output contains key names only.";
        }
        if (operation == TermuxOperation.PROCESS_LIST) body = relevantProcesses(body);
        if (operation == TermuxOperation.BUDDY_TUNNEL_HEALTH || isReaProfileHealthOperation(operation)) body = summarizeHealth(body);
        if (operation == TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE) {
            String code = body.trim();
            if ("401".equals(code)) body = "HTTP 401: локальный MCP listener отвечает и требует Bearer auth. Authenticated initialize ещё не проверен.";
            else if ("200".equals(code)) body = "HTTP 200 без Authorization: проверьте политику доступа MCP listener.";
            else body = "HTTP status: " + (code.isEmpty() ? "нет ответа" : code);
        }
        if (!body.isEmpty()) output.append("\n" + body.trim());
        if (!result.stderr.isEmpty()) output.append("\nSTDERR: " + result.stderr.trim());
        if (result.truncated) output.append("\n[OUTPUT TRUNCATED]");
        if (eventStore != null && !isFinishing() && !isDestroyed()) {
            updateSnapshotForProbe(operation, result);

            String message = "operation=" + operation.name() + " exit=" + result.exitCode + " termuxErr=" + result.internalError;
            boolean expectedNoMatch = operation == TermuxOperation.BUDDY_ENV_SOURCE_AUDIT
                    && result.exitCode == 1 && result.internalError == -1;
            eventStore.append(new DiagnosticEvent(System.currentTimeMillis(), "termux",
                    result.succeeded() || expectedNoMatch ? "PROBE_OK" : "PROBE_FAILED", message));
            refreshEventLog();
            refreshLiveStatuses();
            refreshTermuxControls();
        }
    }

    private String summarizeHealth(String json) {
        try {
            JSONObject report = new JSONObject(json);
            JSONObject healthz = report.optJSONObject("healthz");
            JSONObject readyz = report.optJSONObject("readyz");
            JSONObject poll = report.optJSONObject("control_plane_poll");
            JSONObject process = report.optJSONObject("process");
            return "result=" + report.optString("result", "unknown")
                    + " healthz=" + (healthz != null && healthz.optBoolean("ok"))
                    + " readyz=" + (readyz != null && readyz.optBoolean("ok"))
                    + " controlPlanePoll=" + (poll == null ? "not reported" : poll.optBoolean("ok"))
                    + " processRunning=" + (process == null ? "not checked" : process.optBoolean("running"));
        } catch (Exception e) {
            return json.isEmpty() ? "No health report returned." : json;
        }
    }

    private String relevantProcesses(String source) {
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\n")) {
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("tunnel-client") || lower.contains("termux") || lower.contains("buddy") || lower.contains("rea")) {
                if (result.length() > 0) result.append('\n');
                result.append(line);
            }
        }
        return result.length() == 0 ? "No matching Termux/Buddy/REA/Tunnel Client process names in ps -A output." : result.toString();
    }


    private void requestNotificationAndStart() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
            return;
        }
        MonitorService.start(this);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::refreshTermuxControls, 500);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestNotificationAndStart();
            } else if (eventStore != null) {
                eventStore.append(new DiagnosticEvent(System.currentTimeMillis(), "system",
                        "NOTIFICATION_PERMISSION_DENIED", "Foreground monitoring was not started"));
                refreshEventLog();
            }
        }
    }

    private String buildEventLog() {
        if (eventStore == null) return "Журнал пока пуст.";
        java.util.List<DiagnosticEvent> events = eventStore.recent(20);
        if (events.isEmpty()) return "Журнал пока пуст. Ошибки: 0 • блокировки: 0 • автоматические перезапуски: отключены.";
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        StringBuilder value = new StringBuilder("Ошибки: " + eventStore.countType("PROBE_FAILED")
                + " • NOT_READY: " + eventStore.countType("NOT_READY")
                + " • блокировки: " + eventStore.countType("BLOCKED")
                + " • автоматические перезапуски: отключены\n\n");
        for (DiagnosticEvent event : events) {
            value.append(format.format(new Date(event.timestampEpochMillis)))
                    .append("  ").append(event.componentId).append(" / ")
                    .append(event.eventType).append("  ")
                    .append(event.message).append("\n");
        }
        return value.toString();
    }

    private void refreshEventLog() {
        if (eventLogView != null) eventLogView.setText(buildEventLog());
    }

    @Override protected void onResume() {
        super.onResume();
        refreshEventLog();
        refreshLiveStatuses();
        refreshTermuxControls();
        uiRefreshHandler.removeCallbacks(uiRefreshRunnable);
        uiRefreshHandler.postDelayed(uiRefreshRunnable, 15000L);
    }

    @Override protected void onPause() {
        uiRefreshHandler.removeCallbacks(uiRefreshRunnable);
        super.onPause();
    }

    @Override protected void onDestroy() {
        uiRefreshHandler.removeCallbacksAndMessages(null);
        if (eventStore != null) eventStore.close();
        super.onDestroy();
    }


    private void updateSnapshotForProbe(TermuxOperation operation, TermuxCommandResult result) {
        switch (operation) {
            case IDENTITY:
                boolean commandAccepted = result.succeeded();
                saveProbeSnapshot("termux", commandAccepted ? ComponentStatus.RUNNING : ComponentStatus.UNAVAILABLE,
                        commandAccepted ? "Termux RUN_COMMAND responded" : "Termux identity command failed");
                saveProbeSnapshot("termux-run-command", commandAccepted ? ComponentStatus.AVAILABLE : ComponentStatus.BLOCKED,
                        commandAccepted ? "Termux accepted external command" : "RUN_COMMAND did not complete; check Android permission, allow-external-apps, and Termux service availability");
                break;
            case TUNNEL_CLIENT_VERSION:
                saveProbeSnapshot("tunnel-client", result.succeeded() ? ComponentStatus.AVAILABLE : ComponentStatus.UNAVAILABLE,
                        result.succeeded() ? result.stdout.trim() : "Tunnel Client binary/version check failed");
                break;
            case CURL_VERSION:
                saveProbeSnapshot("termux-curl", result.succeeded() ? ComponentStatus.AVAILABLE : ComponentStatus.UNAVAILABLE,
                        result.succeeded() ? "curl binary available" : "curl binary unavailable");
                break;
            case BUDDY_TUNNEL_HEALTH:
                String detail = "Health probe failed";
                ComponentStatus pidState = ComponentStatus.ERROR;
                try {
                    JSONObject report = new JSONObject(result.stdout);
                    detail = summarizeHealth(result.stdout);
                    JSONObject process = report.optJSONObject("process");
                    if (result.internalError != -1 || result.exitCode == 127) pidState = ComponentStatus.UNAVAILABLE;
                    else if (process != null && process.optBoolean("running")) pidState = ComponentStatus.PID_PRESENT;
                    else if (process != null) pidState = ComponentStatus.STOPPED;
                } catch (Exception ignored) {
                    if (result.internalError != -1 || result.exitCode == 127) pidState = ComponentStatus.UNAVAILABLE;
                }
                saveProbeSnapshot("tunnel-client", pidState, "PID presence only; executable/config identity not verified");
                ComponentStatus tunnelState = result.internalError != -1 || result.exitCode == 127
                        ? ComponentStatus.UNAVAILABLE : ComponentStatus.NOT_READY;
                saveProbeSnapshot("buddy-tunnel", tunnelState,
                        detail + "; authenticated readiness withheld until process identity verification");
                break;
            case MCP_ENDPOINT_AUDIT:
                int endpointFields = 0;
                int endpointMarker = result.stdout.lastIndexOf("ENDPOINT_FIELDS=");
                if (endpointMarker >= 0) {
                    String countText = result.stdout.substring(endpointMarker + "ENDPOINT_FIELDS=".length()).trim();
                    try { endpointFields = Integer.parseInt(countText.split("\\s+")[0]); }
                    catch (Exception ignored) { endpointFields = 0; }
                }
                ComponentStatus endpointAuditStatus = !result.succeeded() ? ComponentStatus.UNAVAILABLE
                        : endpointFields > 0 ? ComponentStatus.AVAILABLE : ComponentStatus.NOT_READY;
                saveProbeSnapshot("mcp-endpoint-audit", endpointAuditStatus,
                        "Three fixed REA profile YAMLs scanned; " + endpointFields + " non-secret endpoint fields found");
                break;
            case TUNNELS_SCRIPT_SHA256:
                boolean managerHashMatches = result.succeeded()
                        && result.stdout.startsWith(EXPECTED_TUNNELS_SCRIPT_SHA256);
                saveProbeSnapshot("tunnels-manager", !result.succeeded() ? ComponentStatus.UNAVAILABLE
                                : managerHashMatches ? ComponentStatus.AVAILABLE : ComponentStatus.NOT_READY,
                        managerHashMatches ? "Existing tunnel manager matches the reviewed historical SHA-256"
                                : "Existing tunnel manager hash differs from reviewed source or file is unavailable");
                break;
            case BRIDGE_TUNNEL_HEALTH:
                saveInitialProfileHealthSnapshot("rea-bridge", result);
                break;
            case DROIDMCP_TUNNEL_HEALTH:
                saveInitialProfileHealthSnapshot("droidmcp", result);
                break;
            case REA_ANDROID_TUNNEL_HEALTH:
                saveInitialProfileHealthSnapshot("rea-android", result);
                break;
            case BUDDY_MCP_LISTENER_CHALLENGE:
                boolean listener = result.succeeded() && "401".equals(result.stdout.trim());
                ComponentStatus mcpState = listener ? ComponentStatus.RUNNING
                        : (result.internalError != -1 || result.exitCode == 127) ? ComponentStatus.UNAVAILABLE
                        : "200".equals(result.stdout.trim()) ? ComponentStatus.ERROR : ComponentStatus.NOT_READY;
                saveProbeSnapshot("buddy-mcp", mcpState,
                        listener ? "HTTP 401: listener up; authentication not yet tested" : "MCP listener challenge unconfirmed");
                break;
            case PROCESS_LIST:
                // Aggregate inventory is shown in the preflight output only; it is not profile identity evidence.
                break;
            default:
                break;
        }
    }

    private boolean isReaProfileHealthOperation(TermuxOperation operation) {
        return operation == TermuxOperation.BRIDGE_TUNNEL_HEALTH
                || operation == TermuxOperation.DROIDMCP_TUNNEL_HEALTH
                || operation == TermuxOperation.REA_ANDROID_TUNNEL_HEALTH;
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

    private void saveInitialProfileHealthSnapshot(String componentId, TermuxCommandResult result) {
        ComponentStatus status = ComponentStatus.ERROR;
        String detail = "Tunnel profile health report unavailable";
        try {
            JSONObject report = new JSONObject(result.stdout);
            JSONObject process = report.optJSONObject("process");
            detail = summarizeHealth(result.stdout) + "; process identity pending";
            if (process != null && !process.optString("error", "").isEmpty()) status = ComponentStatus.ERROR;
            else if (process != null && !process.optBoolean("running")) status = ComponentStatus.STOPPED;
            else if (process != null && process.optBoolean("running")) status = ComponentStatus.PID_PRESENT;
            else if (result.internalError != -1 || result.exitCode == 127) status = ComponentStatus.UNAVAILABLE;
        } catch (Exception ignored) {
            if (result.internalError != -1 || result.exitCode == 127) status = ComponentStatus.UNAVAILABLE;
        }
        saveProfileSnapshot(componentId, status, 0, detail);
    }

    private void verifyReaProfileIdentity(TermuxOperation operation, TermuxCommandResult healthResult,
                                          Runnable next) {
        if (isFinishing() || isDestroyed()) return;
        String componentId = profileComponentId(operation);
        JSONObject report;
        JSONObject process;
        int pid;
        boolean endpointsReady;
        try {
            report = new JSONObject(healthResult.stdout);
            process = report.optJSONObject("process");
            if (process == null) {
                ComponentStatus status = healthResult.internalError != -1 || healthResult.exitCode == 127
                        ? ComponentStatus.UNAVAILABLE : ComponentStatus.ERROR;
                saveProfileSnapshot(componentId, status, 0, "Tunnel health did not return a process object");
                next.run();
                return;
            }
            if (!process.optString("error", "").isEmpty()) {
                saveProfileSnapshot(componentId, ComponentStatus.ERROR, process.optInt("pid", 0),
                        "Tunnel PID file could not be read or parsed");
                next.run();
                return;
            }
            if (!process.optBoolean("running")) {
                saveProfileSnapshot(componentId, ComponentStatus.STOPPED, process.optInt("pid", 0),
                        "Tunnel Client process is not running");
                next.run();
                return;
            }
            pid = process.optInt("pid", 0);
            if (pid <= 0) {
                saveProfileSnapshot(componentId, ComponentStatus.ERROR, 0,
                        "Tunnel health reports running without a valid PID");
                next.run();
                return;
            }
            endpointsReady = healthResult.succeeded() && "ok".equals(report.optString("result"));
        } catch (Exception e) {
            saveProfileSnapshot(componentId, ComponentStatus.ERROR, 0, "Invalid tunnel health JSON");
            next.run();
            return;
        }

        final int verifiedPid = pid;
        final boolean ready = endpointsReady;
        TermuxCommandClient.InstallState install = TermuxCommandClient.inspect(this);
        final String home = install.dataDir == null ? "" : install.dataDir + "/files/home";
        TermuxCommandClient.runProcessExecutable(this, verifiedPid, executable -> {
            if (isFinishing() || isDestroyed()) return;
            if (!executable.succeeded() || home.isEmpty()
                    || !TunnelProfileIdentity.matchesExecutable(executable.stdout, home)) {
                saveProfileSnapshot(componentId, ComponentStatus.PID_PRESENT, verifiedPid,
                        "PID exists but exact Tunnel Client executable identity does not match");
                next.run();
                return;
            }
            TermuxCommandClient.runProcessCmdline(this, verifiedPid, commandLine -> {
                if (isFinishing() || isDestroyed()) return;
                boolean matches = commandLine.succeeded()
                        && TunnelProfileIdentity.matchesProfile(executable.stdout, commandLine.stdout,
                        home, profileConfigRelativePath(operation), profileConfigFlag(operation));
                ComponentStatus status = !matches ? ComponentStatus.PID_PRESENT
                        : ready ? ComponentStatus.READY : ComponentStatus.NOT_READY;
                saveProfileSnapshot(componentId, status, verifiedPid, matches
                        ? ready ? "Tunnel endpoints and exact profile process identity verified"
                        : "Exact profile process identity verified; health endpoints are not ready"
                        : "Tunnel Client executable matched but exact profile config identity did not");
                if (eventStore != null) {
                    eventStore.append(new DiagnosticEvent(System.currentTimeMillis(), componentId,
                            status == ComponentStatus.READY ? "PROFILE_READY" : "PROFILE_NOT_READY",
                            "health=" + ready + " identity=" + matches));
                    refreshEventLog();
                }
                next.run();
            });
        });
    }

    private void saveProfileSnapshot(String id, ComponentStatus status, int pid, String detail) {
        if (eventStore == null) return;
        try {
            RuntimeSnapshot previous = eventStore.snapshot(id);
            int errors = previous == null ? 0 : previous.errorCount;
            int retries = previous == null ? 0 : previous.retryCount;
            String safeDetail = detail == null ? "" : detail;
            boolean failure = status == ComponentStatus.ERROR || status == ComponentStatus.NOT_READY
                    || status == ComponentStatus.BLOCKED || status == ComponentStatus.UNAVAILABLE;
            if (failure && (previous == null || previous.status != status
                    || !safeDetail.equals(previous.detail))) errors++;
            eventStore.saveSnapshot(new RuntimeSnapshot(id, status, System.currentTimeMillis(),
                    pid, errors, retries, safeDetail));
            refreshLiveStatuses();
        } catch (Exception ignored) { }
    }

    private void saveProbeSnapshot(String id, ComponentStatus status, String detail) {
        if (eventStore == null) return;
        try {
            RuntimeSnapshot previous = eventStore.snapshot(id);
            int errors = previous == null ? 0 : previous.errorCount;
            int retries = previous == null ? 0 : previous.retryCount;
            String safeDetail = detail == null ? "" : detail;
            boolean failure = status == ComponentStatus.ERROR || status == ComponentStatus.NOT_READY
                    || status == ComponentStatus.BLOCKED || status == ComponentStatus.UNAVAILABLE;
            if (failure && (previous == null || previous.status != status
                    || !safeDetail.equals(previous.detail))) errors++;
            eventStore.saveSnapshot(new RuntimeSnapshot(id, status, System.currentTimeMillis(),
                    0, errors, retries, safeDetail));
        } catch (Exception ignored) { }
    }

    private String liveStatusLabel(String componentId) {
        if (eventStore == null) return "LIVE: НЕ ПРОВЕРЕНО";
        RuntimeSnapshot snapshot = eventStore.snapshot(componentId);
        if (snapshot == null) return "LIVE: НЕ ПРОВЕРЕНО";
        long age = Math.max(0, System.currentTimeMillis() - snapshot.checkedAtEpochMillis);
        long staleAfter = ("buddy-tunnel".equals(componentId) || "buddy-mcp".equals(componentId)
                || "tunnel-client".equals(componentId) || "termux".equals(componentId)
                || "termux-run-command".equals(componentId) || "jiti-monitor-service".equals(componentId)
                || "jiti-control-center".equals(componentId) || "rea-bridge".equals(componentId)
                || "droidmcp".equals(componentId) || "rea-android".equals(componentId))
                ? 120000L : ("buddy-mcp-auth".equals(componentId) || "buddy-tools".equals(componentId))
                ? 600000L : 24 * 60 * 60 * 1000L;
        boolean stale = age > staleAfter;
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        String detail = snapshot.detail == null ? "" : snapshot.detail;
        if (detail.length() > 100) detail = detail.substring(0, 100) + "…";
        return "LIVE: " + statusLabel(snapshot.status) + " • " + format.format(new Date(snapshot.checkedAtEpochMillis))
                + (stale ? " • УСТАРЕЛО" : "")
                + (snapshot.errorCount > 0 ? " • errors=" + snapshot.errorCount : "")
                + (snapshot.retryCount > 0 ? " • retries=" + snapshot.retryCount : "")
                + (detail.isEmpty() ? "" : " • " + detail);
    }

    private String statusLabel(ComponentStatus status) {
        switch (status) {
            case CHECKING: return "ПРОВЕРКА";
            case AVAILABLE: return "ДОСТУПЕН";
            case PID_PRESENT: return "PID ЕСТЬ, IDENTITY НЕ ПРОВЕРЕН";
            case RUNNING: return "РАБОТАЕТ";
            case READY: return "ГОТОВ";
            case STARTING: return "ЗАПУСК";
            case STOPPED: return "ОСТАНОВЛЕН";
            case NOT_READY: return "НЕ ГОТОВ";
            case ERROR: return "ОШИБКА";
            case UNAVAILABLE: return "НЕДОСТУПЕН";
            case BLOCKED: return "ЗАБЛОКИРОВАН";
            default: return "НЕ ПРОВЕРЕНО";
        }
    }

    private String termuxPolicyText() {
        return termuxBridgeVerified()
                ? "RUN_COMMAND и allow-external-apps подтверждены последним успешным callback."
                : "RUN_COMMAND permission и allow-external-apps=true должны быть включены отдельно; внешняя политика пока не подтверждена.";
    }

    private boolean termuxBridgeVerified() {
        if (eventStore == null) return false;
        RuntimeSnapshot snapshot = eventStore.snapshot("termux-run-command");
        return snapshot != null && snapshot.status == ComponentStatus.AVAILABLE
                && System.currentTimeMillis() - snapshot.checkedAtEpochMillis <= 120000L;
    }

    private void refreshTermuxControls() {
        if (isFinishing() || isDestroyed()) return;
        TermuxCommandClient.InstallState state = TermuxCommandClient.inspect(this);
        if (termuxStatusView != null) {
            termuxStatusView.setText((state.installed ? "Termux " + state.versionName + " • " : "") + state.message
                    + "\n" + termuxPolicyText()
                    + "\nМониторинг удерживает CPU активным и увеличивает расход батареи; остановка снимает wake lock.");
            termuxStatusView.setTextColor(termuxBridgeVerified() ? GREEN : AMBER);
        }
        if (termuxCheckButton != null) termuxCheckButton.setEnabled(state.canInvoke() && !diagnosticsInFlight
                && !profileOperationCoordinator.isBusy());
        if (monitorStartButton != null) monitorStartButton.setEnabled(state.canInvoke() && !MonitorService.running);
        if (monitorStopButton != null) monitorStopButton.setEnabled(MonitorService.running);
        boolean profileControlsReady = state.canInvoke() && termuxBridgeVerified()
                && !diagnosticsInFlight && !profileOperationCoordinator.isBusy();
        if (globalCheckButton != null) globalCheckButton.setEnabled(state.canInvoke()
                && !diagnosticsInFlight && !profileOperationCoordinator.isBusy());
        if (globalStartButton != null) globalStartButton.setEnabled(profileControlsReady);
        if (globalRestartButton != null) globalRestartButton.setEnabled(profileControlsReady);
        if (globalStopButton != null) globalStopButton.setEnabled(profileControlsReady);
        for (List<Button> buttons : profileActionButtons.values()) {
            for (Button actionButton : buttons) actionButton.setEnabled(profileControlsReady);
        }
        if (globalControlStatusView != null && !profileOperationCoordinator.isBusy()) {
            globalControlStatusView.setText(profileControlsReady
                    ? "Ручное управление REA-туннелями доступно. Buddy не затрагивается."
                    : !state.canInvoke() ? "Сначала устраните блокировку Termux RUN_COMMAND."
                    : !termuxBridgeVerified() ? "Сначала выполните безопасную проверку Termux."
                    : diagnosticsInFlight ? "Выполняется проверка. Управление временно заблокировано."
                    : "Управление временно заблокировано.");
        }
        boolean controlsReady = state.canInvoke() && termuxBridgeVerified() && MonitorService.running;
        boolean buddyBusy = BUDDY_OPERATION_GATE.isInFlight();
        boolean buddyControlsEnabled = controlsReady && !buddyBusy;
        if (buddyStartButton != null) buddyStartButton.setEnabled(buddyControlsEnabled);
        if (buddyStopButton != null) buddyStopButton.setEnabled(buddyControlsEnabled);
        if (buddyRestartButton != null) buddyRestartButton.setEnabled(buddyControlsEnabled);
        if (buddyControlStatusView != null) {
            String controlState = buddyBusy ? "Операция Buddy уже выполняется. Повторные команды временно заблокированы."
                    : !state.canInvoke() ? "Сначала устраните блокировку Termux RUN_COMMAND."
                    : !termuxBridgeVerified() ? "Сначала выполните безопасную проверку и подтвердите allow-external-apps."
                    : !MonitorService.running ? "Сначала запустите фоновый мониторинг."
                    : "Контроль Buddy доступен. Каждый запуск/остановка требует подтверждения; PID проверяется по бинарнику и YAML.";
            buddyControlStatusView.setText(controlState);
            buddyControlStatusView.setTextColor(controlsReady ? GREEN : AMBER);
        }
    }

    private void refreshLiveStatuses() {
        for (Map.Entry<String, TextView> entry : liveStatusViews.entrySet()) {
            entry.getValue().setText(liveStatusLabel(entry.getKey()));
        }
    }

    private String profileIdForComponent(String componentId) {
        if ("rea-bridge".equals(componentId)) return "bridge";
        if ("droidmcp".equals(componentId)) return "droidmcp";
        if ("rea-android".equals(componentId)) return "rea-android";
        return null;
    }

    private String componentIdForProfile(String profileId) {
        if ("bridge".equals(profileId)) return "rea-bridge";
        if ("droidmcp".equals(profileId)) return "droidmcp";
        if ("rea-android".equals(profileId)) return "rea-android";
        return "rea-stack";
    }

    private String profileTitle(String profileId) {
        if ("bridge".equals(profileId)) return "REA-Termux-Bridge 0.1.3";
        if ("droidmcp".equals(profileId)) return "DroidMCP-Filesystem";
        if ("rea-android".equals(profileId)) return "REA-Termux-Android";
        return "REA tunnel stack";
    }

    private JSONObject profileReport(TermuxCommandResult result) {
        try {
            String output = result.stdout == null ? "" : result.stdout.trim();
            int newline = output.lastIndexOf('\n');
            if (newline >= 0) output = output.substring(newline + 1).trim();
            return new JSONObject(output);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ComponentStatus profileComponentStatus(String result) {
        if ("started_ready".equals(result) || "already_running".equals(result)) return ComponentStatus.READY;
        if ("stopped".equals(result) || "already_stopped".equals(result)) return ComponentStatus.STOPPED;
        if ("process_running_not_ready".equals(result) || "started_not_ready".equals(result)) return ComponentStatus.NOT_READY;
        if ("blocked".equals(result) || "restart_aborted".equals(result)) return ComponentStatus.BLOCKED;
        return ComponentStatus.ERROR;
    }

    private String profileReportDetail(JSONObject report, TermuxCommandResult command) {
        if (report == null) return "Profile command returned no structured result; exit=" + command.exitCode;
        StringBuilder detail = new StringBuilder(report.optString("result", "unknown"));
        String reason = report.optString("reason", "");
        if (!reason.isEmpty()) detail.append(" • ").append(reason);
        int pid = report.optInt("pid", report.optInt("spawned_pid", 0));
        if (pid > 0) detail.append(" • PID ").append(pid);
        if (report.optBoolean("stale_pid_file", false)) detail.append(" • stale PID metadata retained/checked");
        return detail.toString();
    }

    private void runProfileStatus(String profileId, TextView statusView) {
        TermuxCommandClient.InstallState install = TermuxCommandClient.inspect(this);
        if (!install.canInvoke() || !termuxBridgeVerified()) {
            statusView.setText("LIVE: проверка недоступна. Сначала выполните безопасную проверку Termux.");
            refreshTermuxControls();
            return;
        }
        OperationCoordinator.Submission submission = profileOperationCoordinator.requestManual(profileId + "-status");
        if (!submission.accepted()) {
            statusView.setText("Другая операция выполняется. Проверка поставлена на паузу.");
            return;
        }
        long requestId = submission.request.id;
        Runnable action = () -> executeProfileStatus(profileId, statusView, requestId);
        if (submission.status == OperationCoordinator.Status.QUEUED_AFTER_AUTOMATIC) {
            queuedManualActions.put(requestId, action);
            statusView.setText("Ручная проверка поставлена в приоритетную очередь.");
            refreshTermuxControls();
            return;
        }
        refreshTermuxControls();
        action.run();
    }

    private void executeProfileStatus(String profileId, TextView statusView, long requestId) {
        statusView.setText("LIVE: проверка процесса, профиля и полной готовности…");
        TermuxCommandClient.runProfileControl(this, profileId, "status", result -> {
            if (isFinishing() || isDestroyed()) {
                completeProfileOperation(requestId);
                return;
            }
            JSONObject report = profileReport(result);
            String state = report == null ? "error" : report.optString("result", "unknown");
            String detail = profileReportDetail(report, result);
            String componentId = componentIdForProfile(profileId);
            saveProbeSnapshot(componentId, profileComponentStatus(state), detail);
            statusView.setText(liveStatusLabel(componentId));
            logControlEvent(componentId, "PROFILE_STATUS", detail);
            completeProfileOperation(requestId);
        });
    }

    private void requestProfileOperation(String profileId, String mode, TextView statusView) {
        TermuxCommandClient.InstallState install = TermuxCommandClient.inspect(this);
        if (!install.canInvoke() || !termuxBridgeVerified()) {
            statusView.setText("Управление недоступно: сначала выполните безопасную проверку Termux.");
            refreshTermuxControls();
            return;
        }
        OperationCoordinator.Submission submission = profileOperationCoordinator.requestManual(profileId);
        if (!submission.accepted()) {
            statusView.setText("Другая операция уже выполняется. Новая команда не отправлена.");
            return;
        }
        long requestId = submission.request.id;
        Runnable action = () -> executeProfileOperation(profileId, mode, statusView, requestId);
        if (submission.status == OperationCoordinator.Status.QUEUED_AFTER_AUTOMATIC) {
            queuedManualActions.put(requestId, action);
            statusView.setText("Ручная команда поставлена в приоритетную очередь. Текущий автоматический шаг завершится первым.");
            refreshTermuxControls();
            return;
        }
        refreshTermuxControls();
        action.run();
    }

    private void executeProfileOperation(String profileId, String mode, TextView statusView, long requestId) {
        String componentId = componentIdForProfile(profileId);
        statusView.setText("ПРЕДПРОВЕРКА: " + profileTitle(profileId) + " • " + mode);
        if (globalControlStatusView != null) globalControlStatusView.setText("Выполняется ручная операция: " + profileTitle(profileId) + " • " + mode);
        TermuxCommandClient.runProfileControl(this, profileId, "preflight", preflight -> {
            if (isFinishing() || isDestroyed()) {
                completeProfileOperation(requestId);
                return;
            }
            JSONObject report = profileReport(preflight);
            String state = report == null ? "unknown" : report.optString("result", "unknown");
            boolean stopped = "ready_to_start".equals(state);
            boolean running = "already_running".equals(state) || "process_running_not_ready".equals(state);
            if (report == null || "blocked".equals(state) || "unknown".equals(state)
                    || ("start".equals(mode) && !(stopped || "already_running".equals(state)))
                    || ("stop".equals(mode) && !(running || stopped))
                    || ("restart".equals(mode) && !(stopped || running))) {
                String detail = profileReportDetail(report, preflight);
                statusView.setText("ОПЕРАЦИЯ НЕ ЗАПУЩЕНА: " + detail);
                saveProbeSnapshot(componentId, profileComponentStatus(state), detail);
                logControlEvent(componentId, "PROFILE_PREFLIGHT_BLOCKED", detail);
                completeProfileOperation(requestId);
                return;
            }
            if ("start".equals(mode) && "already_running".equals(state)) {
                String detail = "Процесс уже запущен и полная готовность подтверждена.";
                statusView.setText(detail);
                saveProbeSnapshot(componentId, ComponentStatus.READY, detail);
                completeProfileOperation(requestId);
                return;
            }
            if ("stop".equals(mode) && stopped) {
                String detail = report.optBoolean("stale_pid_file", false)
                        ? "Процесс не найден; устаревший PID сохранён для безопасной ручной проверки."
                        : "Профиль уже остановлен. Никакой сигнал не отправлялся.";
                statusView.setText(detail);
                saveProbeSnapshot(componentId, ComponentStatus.STOPPED, detail);
                completeProfileOperation(requestId);
                return;
            }
            String actionLabel = "start".equals(mode) ? "запустить" : "stop".equals(mode) ? "остановить" : "перезапустить";
            String message = "Подтверждаете " + actionLabel + " только «" + profileTitle(profileId) + "»?\n\n"
                    + "Другие профили и Buddy не затрагиваются. Перед остановкой повторно проверяется точная идентичность PID, бинарника и YAML. "
                    + "Используется только SIGTERM; SIGKILL не применяется.";
            new AlertDialog.Builder(this)
                    .setTitle("Ручное управление туннелем")
                    .setMessage(message)
                    .setNegativeButton("Отмена", (dialog, which) -> completeProfileOperation(requestId))
                    .setPositiveButton("Подтвердить", (dialog, which) -> {
                        if (!commandGate.mayExecute("profile." + mode, true, true, true)) {
                            statusView.setText("Команда заблокирована политикой безопасности.");
                            completeProfileOperation(requestId);
                            return;
                        }
                        statusView.setText("ВЫПОЛНЯЕТСЯ: " + profileTitle(profileId) + " • " + mode);
                        TermuxCommandClient.runProfileControl(this, profileId, mode, command -> {
                            if (isFinishing() || isDestroyed()) {
                                completeProfileOperation(requestId);
                                return;
                            }
                            JSONObject finalReport = profileReport(command);
                            String finalState = finalReport == null ? "error"
                                    : finalReport.optString("result", "unknown");
                            String detail = profileReportDetail(finalReport, command);
                            saveProbeSnapshot(componentId, profileComponentStatus(finalState), detail);
                            statusView.setText(detail);
                            logControlEvent(componentId, "PROFILE_" + mode.toUpperCase(java.util.Locale.ROOT), detail);
                            completeProfileOperation(requestId);
                            if ("started_ready".equals(finalState) || "stopped".equals(finalState)) {
                                MonitorService.pollNow(this);
                            }
                        });
                    })
                    .setOnCancelListener(dialog -> completeProfileOperation(requestId))
                    .show();
        });
    }

    private void completeProfileOperation(long requestId) {
        OperationCoordinator.Request next = profileOperationCoordinator.complete(requestId);
        refreshTermuxControls();
        if (next != null) {
            Runnable queued = queuedManualActions.remove(next.id);
            if (queued != null) queued.run();
        }
    }

    private void requestProfileStackOperation(String mode) {
        TermuxCommandClient.InstallState install = TermuxCommandClient.inspect(this);
        if (!install.canInvoke() || !termuxBridgeVerified()) {
            if (globalControlStatusView != null) globalControlStatusView.setText("Сначала выполните безопасную проверку Termux.");
            refreshTermuxControls();
            return;
        }
        OperationCoordinator.Submission submission = profileOperationCoordinator.requestManual("rea-stack");
        if (!submission.accepted()) {
            globalControlStatusView.setText("Другая операция уже выполняется. Повторная команда отклонена.");
            return;
        }
        long requestId = submission.request.id;
        Runnable action = () -> confirmAndRunProfileStack(mode, requestId);
        if (submission.status == OperationCoordinator.Status.QUEUED_AFTER_AUTOMATIC) {
            queuedManualActions.put(requestId, action);
            globalControlStatusView.setText("Ручная команда поставлена выше следующих автоматических попыток.");
            refreshTermuxControls();
            return;
        }
        refreshTermuxControls();
        action.run();
    }

    private void confirmAndRunProfileStack(String mode, long requestId) {
        String label = "start".equals(mode) ? "запустить" : "stop".equals(mode) ? "остановить" : "перезапустить";
        String order = "start".equals(mode) ? ProfileStackPlanner.startOrder().toString()
                : "stop".equals(mode) ? ProfileStackPlanner.stopOrder().toString()
                : "обратная остановка, затем запуск в штатном порядке";
        String message = "Подтверждаете " + label + " все три REA-профиля?\n\n"
                + "Порядок: " + order + ".\n"
                + "Buddy исключён из этой операции и останется нетронутым. "
                + "Каждый профиль проходит отдельную проверку процесса и полной готовности. "
                + "При первой ошибке последовательность останавливается; бесконечных повторов нет.";
        new AlertDialog.Builder(this)
                .setTitle("Ручное управление REA-туннелями")
                .setMessage(message)
                .setNegativeButton("Отмена", (dialog, which) -> completeProfileOperation(requestId))
                .setPositiveButton("Подтвердить", (dialog, which) -> {
                    List<ProfileStackPlanner.Step> steps = "start".equals(mode)
                            ? ProfileStackPlanner.startPlan()
                            : "stop".equals(mode) ? ProfileStackPlanner.stopPlan()
                            : ProfileStackPlanner.restartOrder();
                    runProfileStackStep(steps, 0, requestId);
                })
                .setOnCancelListener(dialog -> completeProfileOperation(requestId))
                .show();
    }

    private void runProfileStackStep(List<ProfileStackPlanner.Step> steps, int index, long requestId) {
        if (index >= steps.size()) {
            globalControlStatusView.setText("Готово: все шаги REA-стека завершены. Buddy не затрагивался.");
            logControlEvent("rea-stack", "STACK_OPERATION_COMPLETE", "All requested profile steps completed");
            completeProfileOperation(requestId);
            return;
        }
        ProfileStackPlanner.Step step = steps.get(index);
        String componentId = componentIdForProfile(step.profileId);
        globalControlStatusView.setText("Проверка " + profileTitle(step.profileId) + " • " + step.operation);
        TermuxCommandClient.runProfileControl(this, step.profileId, "preflight", preflight -> {
            if (isFinishing() || isDestroyed()) {
                completeProfileOperation(requestId);
                return;
            }
            JSONObject report = profileReport(preflight);
            String state = report == null ? "unknown" : report.optString("result", "unknown");
            boolean running = "already_running".equals(state) || "process_running_not_ready".equals(state);
            boolean stopped = "ready_to_start".equals(state);
            if (report == null || "blocked".equals(state)
                    || ("start".equals(step.operation) && "process_running_not_ready".equals(state))
                    || ("start".equals(step.operation) && !running && !stopped)
                    || ("stop".equals(step.operation) && !running && !stopped)) {
                String detail = profileReportDetail(report, preflight);
                globalControlStatusView.setText("ОСТАНОВЛЕНО НА ШАГЕ " + profileTitle(step.profileId) + ": " + detail);
                saveProbeSnapshot(componentId, profileComponentStatus(state), detail);
                logControlEvent("rea-stack", "STACK_STEP_BLOCKED", step.profileId + " " + detail);
                completeProfileOperation(requestId);
                return;
            }
            if (("start".equals(step.operation) && "already_running".equals(state))
                    || ("stop".equals(step.operation) && stopped)) {
                globalControlStatusView.setText(profileTitle(step.profileId) + ": шаг уже выполнен, продолжаю…");
                runProfileStackStep(steps, index + 1, requestId);
                return;
            }
            if (!commandGate.mayExecute("profile." + step.operation, true, true, true)) {
                globalControlStatusView.setText("Шаг заблокирован CommandGate: " + profileTitle(step.profileId));
                completeProfileOperation(requestId);
                return;
            }
            globalControlStatusView.setText("ВЫПОЛНЯЕТСЯ " + step.operation + ": " + profileTitle(step.profileId));
            TermuxCommandClient.runProfileControl(this, step.profileId, step.operation, command -> {
                if (isFinishing() || isDestroyed()) {
                    completeProfileOperation(requestId);
                    return;
                }
                JSONObject finalReport = profileReport(command);
                String finalState = finalReport == null ? "error" : finalReport.optString("result", "unknown");
                boolean success = command.succeeded()
                        && (("start".equals(step.operation) && ("started_ready".equals(finalState) || "already_running".equals(finalState)))
                        || ("stop".equals(step.operation) && ("stopped".equals(finalState) || "already_stopped".equals(finalState))));
                String detail = profileReportDetail(finalReport, command);
                saveProbeSnapshot(componentId, profileComponentStatus(finalState), detail);
                logControlEvent("rea-stack", success ? "STACK_STEP_OK" : "STACK_STEP_FAILED",
                        step.profileId + " " + step.operation + " • " + detail);
                if (!success) {
                    globalControlStatusView.setText("ПОСЛЕДОВАТЕЛЬНОСТЬ ОСТАНОВЛЕНА: " + profileTitle(step.profileId) + " • " + detail);
                    completeProfileOperation(requestId);
                    return;
                }
                globalControlStatusView.setText("Подтверждено: " + profileTitle(step.profileId) + " • " + detail);
                runProfileStackStep(steps, index + 1, requestId);
            });
        });
    }

    private void addComponentCard(LinearLayout parent, ComponentSpec item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(12));
        card.setBackgroundColor(CARD);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(item.title, 14, FG, true);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(text(evidenceLabel(item.evidence), 10, evidenceColor(item.evidence), true));
        card.addView(row);

        TextView live = text(liveStatusLabel(item.id), 10, MUTED, false);
        liveStatusViews.put(item.id, live);
        addTop(card, live, 3);
        addTop(card, text(item.detail, 12, MUTED, false), 5);
        if (!item.dependencies.isEmpty()) {
            addTop(card, text("Зависимости: " + String.join(", ", item.dependencies), 10, MUTED, false), 4);
        }
        LinearLayout actions = new LinearLayout(this);
        Button inspect = button("Live-проверка");
        Button restart = button("Перезапустить");
        String profileId = profileIdForComponent(item.id);
        if (profileId != null) {
            Button start = button("Запустить");
            Button stop = button("Остановить");
            Button logs = button("Журналы");
            inspect.setOnClickListener(v -> runProfileStatus(profileId, live));
            start.setOnClickListener(v -> requestProfileOperation(profileId, "start", live));
            stop.setOnClickListener(v -> requestProfileOperation(profileId, "stop", live));
            restart.setOnClickListener(v -> requestProfileOperation(profileId, "restart", live));
            logs.setOnClickListener(v -> runProfileLogs(profileId, live));
            actions.addView(inspect, new LinearLayout.LayoutParams(0, dp(40), 1f));
            actions.addView(restart, new LinearLayout.LayoutParams(0, dp(40), 1f));
            LinearLayout lifecycle = new LinearLayout(this);
            lifecycle.addView(start, new LinearLayout.LayoutParams(0, dp(40), 1f));
            lifecycle.addView(stop, new LinearLayout.LayoutParams(0, dp(40), 1f));
            lifecycle.addView(logs, new LinearLayout.LayoutParams(0, dp(40), 1f));
            addTop(card, actions, 8);
            addTop(card, lifecycle, 4);
            profileActionButtons.put(profileId, Arrays.asList(inspect, start, stop, restart, logs));
        } else {
            inspect.setEnabled(false);
            restart.setEnabled(false);
            actions.addView(inspect, new LinearLayout.LayoutParams(0, dp(40), 1f));
            actions.addView(restart, new LinearLayout.LayoutParams(0, dp(40), 1f));
            addTop(card, actions, 8);
        }
        addCard(parent, card, 7);
    }

    private String evidenceLabel(EvidenceStatus value) {
        switch (value) {
            case CONFIRMED_WORKING: return "РАБОТАЛО 22.11";
            case PRESENT_CONFIGURED: return "НАСТРОЕНО";
            case OBSERVED_STOPPED: return "ОСТАНОВЛЕНО 23.11";
            default: return "НЕ ПРОВЕРЕНО";
        }
    }
    private int evidenceColor(EvidenceStatus value) {
        if (value == EvidenceStatus.CONFIRMED_WORKING) return GREEN;
        if (value == EvidenceStatus.OBSERVED_STOPPED) return AMBER;
        return MUTED;
    }
    private void addSection(LinearLayout parent, String label) { addTop(parent, text(label, 17, FG, true), 18); }
    private void addCard(LinearLayout parent, View child, int top) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(13), dp(12), dp(13), dp(12));
        wrapper.setBackgroundColor(CARD);
        wrapper.addView(child);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(top);
        parent.addView(wrapper, lp);
    }
    private void addTop(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(top);
        parent.addView(child, lp);
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }
    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        return b;
    }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
