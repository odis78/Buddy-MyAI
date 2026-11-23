package app.jiti.controlcenter;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.jiti.controlcenter.core.ComponentRegistry;
import app.jiti.controlcenter.core.ComponentSpec;
import app.jiti.controlcenter.core.EvidenceStatus;
import app.jiti.controlcenter.core.ToolCatalog;
import app.jiti.controlcenter.core.ToolSpec;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF10151D;
    private static final int CARD = 0xFF1B2430;
    private static final int FG = 0xFFF1F5F9;
    private static final int MUTED = 0xFF9AA8B8;
    private static final int AMBER = 0xFFFFC46B;
    private static final int GREEN = 0xFF7DD3A7;
    private final ComponentRegistry registry = ComponentRegistry.historicalBaseline();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
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
        addCard(page, text("РЕЖИМ АУДИТА. Сейчас приложение показывает исторические доказательства и реестр зависимостей. Live-проверки и команды управления будут включены только после подтверждения безопасного Termux-адаптера.", 13, AMBER, true), 12);

        addSection(page, "Управление системой");
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        for (String label : new String[]{"Проверить всё", "Запустить всё", "Перезапустить всё", "Остановить всё"}) {
            Button button = button(label);
            button.setEnabled(false);
            controls.addView(button, matchWrap());
        }
        addCard(page, controls, 8);

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
        addCard(page, text("Live-проверка ещё не выполнялась. Историческое состояние не выдаётся за текущее. Следующий технический этап: read-only Termux integration и MCP health probes.", 13, MUTED, false), 8);
        setContentView(scroll);
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
