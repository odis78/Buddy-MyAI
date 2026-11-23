package app.jiti.controlcenter;

import android.app.Activity;
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

import app.jiti.controlcenter.core.ComponentRegistry;
import app.jiti.controlcenter.core.ComponentSpec;
import app.jiti.controlcenter.core.EvidenceStatus;
import app.jiti.controlcenter.core.ToolCatalog;
import app.jiti.controlcenter.core.ToolSpec;
import app.jiti.controlcenter.termux.TermuxCommandClient;
import app.jiti.controlcenter.termux.TermuxCommandResult;
import app.jiti.controlcenter.termux.TermuxOperation;
import app.jiti.controlcenter.service.MonitorService;
import app.jiti.controlcenter.storage.EventStore;
import app.jiti.controlcenter.core.DiagnosticEvent;
import app.jiti.controlcenter.core.ComponentStatus;
import app.jiti.controlcenter.core.RuntimeSnapshot;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF10151D;
    private static final int CARD = 0xFF1B2430;
    private static final int FG = 0xFFF1F5F9;
    private static final int MUTED = 0xFF9AA8B8;
    private static final int AMBER = 0xFFFFC46B;
    private static final int GREEN = 0xFF7DD3A7;
    private final ComponentRegistry registry = ComponentRegistry.historicalBaseline();
    private EventStore eventStore;
    private TextView eventLogView;
    private final Map<String, TextView> liveStatusViews = new HashMap<>();
    private static final int REQUEST_NOTIFICATIONS = 4201;

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
        addCard(page, text("РЕЖИМ АУДИТА. Доступны фиксированные read-only проверки через Termux RUN_COMMAND после явной настройки разрешения. Запуск, остановка и перезапуск инфраструктуры пока заблокированы до проверки контрактов и тестов на устройстве.", 13, AMBER, true), 12);

        addSection(page, "Управление системой");
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        for (String label : new String[]{"Проверить всё", "Запустить всё", "Перезапустить всё", "Остановить всё"}) {
            Button button = button(label);
            button.setEnabled(false);
            controls.addView(button, matchWrap());
        }
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
        String status = state.installed
                ? "Termux " + state.versionName + " • " + state.message
                : state.message;
        addCard(page, text(status + "\nДля запуска требуется RUN_COMMAND permission и allow-external-apps=true в Termux. Мониторинг удерживает CPU активным между проверками и расходует больше батареи; остановка снимает wake lock. Настройки Termux JITI не меняет автоматически.", 12,
                state.canInvoke() ? GREEN : AMBER, true), 8);

        TextView output = text("Проверка ещё не запускалась.", 11, MUTED, false);
        Button check = button("Безопасная проверка Termux");
        check.setEnabled(state.canInvoke());
        check.setOnClickListener(v -> runTermuxDiagnostics(check, output));

        Button settings = button("Настройки JITI");
        settings.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(check, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row1.addView(settings, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button startMonitor = button("Запустить мониторинг");
        startMonitor.setEnabled(state.canInvoke() && !MonitorService.running);
        startMonitor.setOnClickListener(v -> requestNotificationAndStart());
        Button stopMonitor = button("Остановить мониторинг");
        stopMonitor.setEnabled(MonitorService.running);
        stopMonitor.setOnClickListener(v -> {
            MonitorService.stop(this);
            stopMonitor.setEnabled(false);
            startMonitor.setEnabled(true);
        });

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(startMonitor, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row2.addView(stopMonitor, new LinearLayout.LayoutParams(0, dp(44), 1f));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.addView(row1);
        addTop(card, row2, 6);
        addTop(card, output, 8);
        addCard(page, card, 8);
    }

    private void runTermuxDiagnostics(Button button, TextView output) {
        button.setEnabled(false);
        output.setText("Read-only preflight запущен…");
        runTermuxStep(button, output, 0);
    }

    private void runTermuxStep(Button button, TextView output, int index) {
        TermuxOperation[] operations = new TermuxOperation[]{
                TermuxOperation.IDENTITY,
                TermuxOperation.TUNNEL_CLIENT_VERSION,
                TermuxOperation.CURL_VERSION,
                TermuxOperation.BUDDY_TUNNEL_HEALTH,
                TermuxOperation.BUDDY_MCP_LISTENER_CHALLENGE,
                TermuxOperation.PROCESS_LIST
        };
        if (index >= operations.length) {
            output.append("\n\nRead-only preflight завершён. MCP 401 подтверждает только доступность listener и требование авторизации, но не успешную аутентификацию.");
            button.setEnabled(true);
            return;
        }
        TermuxOperation operation = operations[index];
        output.append("\n\n[" + operationLabel(operation) + "] running…");
        TermuxCommandClient.run(this, operation, result -> {
            if (isFinishing() || isDestroyed()) return;
            appendCommandResult(output, operation, result);
            runTermuxStep(button, output, index + 1);
        });
    }

    private String operationLabel(TermuxOperation operation) {
        switch (operation) {
            case IDENTITY: return "TERMUX IDENTITY";
            case TUNNEL_CLIENT_VERSION: return "TUNNEL CLIENT VERSION";
            case CURL_VERSION: return "CURL VERSION";
            case BUDDY_TUNNEL_HEALTH: return "BUDDY TUNNEL HEALTH";
            case BUDDY_MCP_LISTENER_CHALLENGE: return "BUDDY MCP AUTH CHALLENGE";
            case PROCESS_LIST: return "PROCESS INVENTORY";
            default: return operation.name();
        }
    }

    private void appendCommandResult(TextView output, TermuxOperation operation, TermuxCommandResult result) {
        output.append("\nexit=" + result.exitCode + " termuxErr=" + result.internalError);
        if (!result.internalErrorMessage.isEmpty()) output.append("\n" + result.internalErrorMessage);
        String body = result.stdout;
        if (operation == TermuxOperation.PROCESS_LIST) body = relevantProcesses(body);
        if (operation == TermuxOperation.BUDDY_TUNNEL_HEALTH) body = summarizeHealth(body);
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
            eventStore.append(new DiagnosticEvent(System.currentTimeMillis(), "termux",
                    result.succeeded() ? "PROBE_OK" : "PROBE_FAILED", message));
            refreshEventLog();
            refreshLiveStatuses();
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
    }

    @Override protected void onDestroy() {
        if (eventStore != null) eventStore.close();
        super.onDestroy();
    }


    private void updateSnapshotForProbe(TermuxOperation operation, TermuxCommandResult result) {
        switch (operation) {
            case IDENTITY:
                saveProbeSnapshot("termux", result.succeeded() ? ComponentStatus.RUNNING : ComponentStatus.UNAVAILABLE,
                        result.succeeded() ? "Termux RUN_COMMAND responded" : "Termux identity command failed");
                break;
            case TUNNEL_CLIENT_VERSION:
                saveProbeSnapshot("tunnel-client", result.succeeded() ? ComponentStatus.AVAILABLE : ComponentStatus.UNAVAILABLE,
                        result.succeeded() ? result.stdout.trim() : "Tunnel Client binary/version check failed");
                break;
            case BUDDY_TUNNEL_HEALTH:
                boolean ready = false;
                String detail = "Health probe failed";
                try {
                    JSONObject report = new JSONObject(result.stdout);
                    ready = result.succeeded() && "ok".equals(report.optString("result"));
                    detail = "healthz=" + report.optJSONObject("healthz").optBoolean("ok")
                            + " readyz=" + report.optJSONObject("readyz").optBoolean("ok");
                } catch (Exception ignored) { }
                saveProbeSnapshot("buddy-tunnel", ready ? ComponentStatus.READY : ComponentStatus.NOT_READY, detail);
                break;
            case BUDDY_MCP_LISTENER_CHALLENGE:
                boolean listener = result.succeeded() && "401".equals(result.stdout.trim());
                saveProbeSnapshot("buddy-mcp", listener ? ComponentStatus.RUNNING : ComponentStatus.NOT_READY,
                        listener ? "HTTP 401: listener up; authentication not yet tested" : "MCP listener challenge unconfirmed");
                break;
            case PROCESS_LIST:
                boolean tunnel = result.stdout.toLowerCase(java.util.Locale.ROOT).contains("tunnel-client");
                saveProbeSnapshot("tunnel-client", tunnel ? ComponentStatus.RUNNING : ComponentStatus.STOPPED,
                        "Process-name check only; PID identity not verified");
                break;
            default:
                break;
        }
    }

    private void saveProbeSnapshot(String id, ComponentStatus status, String detail) {
        if (eventStore == null) return;
        try {
            RuntimeSnapshot previous = eventStore.snapshot(id);
            int errors = previous == null ? 0 : previous.errorCount;
            int retries = previous == null ? 0 : previous.retryCount;
            if (status == ComponentStatus.ERROR || status == ComponentStatus.NOT_READY
                    || status == ComponentStatus.BLOCKED || status == ComponentStatus.UNAVAILABLE) errors++;
            eventStore.saveSnapshot(new RuntimeSnapshot(id, status, System.currentTimeMillis(),
                    0, errors, retries, detail));
        } catch (Exception ignored) { }
    }

    private String liveStatusLabel(String componentId) {
        if (eventStore == null) return "LIVE: НЕ ПРОВЕРЕНО";
        RuntimeSnapshot snapshot = eventStore.snapshot(componentId);
        if (snapshot == null) return "LIVE: НЕ ПРОВЕРЕНО";
        long age = Math.max(0, System.currentTimeMillis() - snapshot.checkedAtEpochMillis);
        boolean stale = age > 120000L;
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

    private void refreshLiveStatuses() {
        for (Map.Entry<String, TextView> entry : liveStatusViews.entrySet()) {
            entry.getValue().setText(liveStatusLabel(entry.getKey()));
        }
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

        addTop(card, text(item.detail, 12, MUTED, false), 5);
        if (!item.dependencies.isEmpty()) {
            addTop(card, text("Зависимости: " + String.join(", ", item.dependencies), 10, MUTED, false), 4);
        }
        LinearLayout actions = new LinearLayout(this);
        Button inspect = button("Live-проверка");
        Button restart = button("Перезапустить");
        inspect.setEnabled(false);
        restart.setEnabled(false);
        actions.addView(inspect, new LinearLayout.LayoutParams(0, dp(40), 1f));
        actions.addView(restart, new LinearLayout.LayoutParams(0, dp(40), 1f));
        addTop(card, actions, 8);
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
