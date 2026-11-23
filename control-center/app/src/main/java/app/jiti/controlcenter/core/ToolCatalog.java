package app.jiti.controlcenter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ToolCatalog {
    private static final List<ToolSpec> TOOLS;
    static {
        List<ToolSpec> t = new ArrayList<>();
        add(t, "read_screen", "Экран", false, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "tap", "Экран", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "long_press", "Экран", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "type_text", "Экран", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "scroll", "Экран", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "swipe", "Экран", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "press", "Экран", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "open_app", "Приложения", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "list_apps", "Приложения", false, ToolSpec.Risk.READ_ONLY);
        add(t, "open_url", "Навигация", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "screenshot", "Экран", false, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "wait", "Автоматизация", false, ToolSpec.Risk.READ_ONLY);
        add(t, "wait_for", "Автоматизация", false, ToolSpec.Risk.READ_ONLY);
        add(t, "do_steps", "Автоматизация", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "collect", "Автоматизация", false, ToolSpec.Risk.READ_ONLY);
        add(t, "find", "Автоматизация", false, ToolSpec.Risk.READ_ONLY);
        add(t, "confirm", "Защита", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "ask", "Защита", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "point_out", "Защита", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "notify", "Уведомления", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "reply_notification", "Уведомления", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "delegate", "Агенты", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "subagents", "Агенты", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "schedule_message", "Планирование", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "add_notification_rule", "Планирование", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "list_scheduled", "Планирование", false, ToolSpec.Risk.READ_ONLY);
        add(t, "cancel_scheduled", "Планирование", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "phone_shell", "Системные команды", false, ToolSpec.Risk.PRIVILEGED);
        add(t, "install_apk", "Установка", false, ToolSpec.Risk.PRIVILEGED);
        add(t, "update_buddy", "Установка", false, ToolSpec.Risk.PRIVILEGED);
        add(t, "wait_for_install", "Установка", false, ToolSpec.Risk.READ_ONLY);

        add(t, "clipboard_read", "Буфер обмена", false, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "clipboard_write", "Буфер обмена", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "set_alarm", "Система", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "set_timer", "Система", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "share", "Обмен", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "save_to_downloads", "Файлы", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "device_status", "Система", false, ToolSpec.Risk.READ_ONLY);
        add(t, "take_photo", "Камера", false, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "notifications", "Уведомления", false, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "media", "Медиа", false, ToolSpec.Risk.INTERACTIVE);
        add(t, "calendar_events", "Календарь", false, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "add_calendar_event", "Календарь", false, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "location", "Местоположение", false, ToolSpec.Risk.SENSITIVE_READ);

        add(t, "browser_open", "Фоновый браузер", true, ToolSpec.Risk.INTERACTIVE);
        add(t, "browser_read", "Фоновый браузер", true, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "browser_click", "Фоновый браузер", true, ToolSpec.Risk.INTERACTIVE);
        add(t, "browser_type", "Фоновый браузер", true, ToolSpec.Risk.WRITE_OR_EXTERNAL);
        add(t, "browser_scroll", "Фоновый браузер", true, ToolSpec.Risk.INTERACTIVE);
        add(t, "browser_screenshot", "Фоновый браузер", true, ToolSpec.Risk.SENSITIVE_READ);
        add(t, "browser_back", "Фоновый браузер", true, ToolSpec.Risk.INTERACTIVE);
        add(t, "browser_tabs", "Фоновый браузер", true, ToolSpec.Risk.READ_ONLY);
        TOOLS = Collections.unmodifiableList(t);
    }

    private ToolCatalog() {}
    private static void add(List<ToolSpec> out, String name, String group, boolean optional, ToolSpec.Risk risk) {
        out.add(new ToolSpec(name, group, optional, risk));
    }
    public static List<ToolSpec> all() { return TOOLS; }
    public static int baseCount() {
        int n = 0; for (ToolSpec t : TOOLS) if (!t.optionalFeature) n++; return n;
    }
    public static int optionalCount() {
        int n = 0; for (ToolSpec t : TOOLS) if (t.optionalFeature) n++; return n;
    }
}
