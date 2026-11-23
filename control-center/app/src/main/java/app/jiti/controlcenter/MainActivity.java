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

import java.util.Arrays;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF10151D;
    private static final int CARD = 0xFF1B2430;
    private static final int FG = 0xFFF1F5F9;
    private static final int MUTED = 0xFF9AA8B8;
    private static final int AMBER = 0xFFFFC46B;

    private static final class Component {
        final String name;
        final String kind;
        final String detail;

        Component(String name, String kind, String detail) {
            this.name = name;
            this.kind = kind;
            this.detail = detail;
        }
    }

    private final List<Component> components = Arrays.asList(
        new Component("Buddy-MyAI Tunnel", "Tunnel Client profile", "OpenAI tunnel • health URL is dynamic"),
        new Component("REA-Termux-Bridge 0.1.3", "Tunnel Client profile", "Separate Bridge profile"),
        new Component("DroidMCP-Filesystem", "Tunnel Client profile", "Dedicated filesystem profile"),
        new Component("REA-Termux-Android", "Tunnel Client profile", "REA Android profile"),
        new Component("Buddy MCP", "MCP server", "http://127.0.0.1:8766/mcp • Bearer auth"),
        new Component("Buddy tools", "MCP tool catalog", "Historical catalogue: 52; one discovery session: 44"),
        new Component("REA MCP", "MCP server / tools", "Latest saved inventory: 207/207 tools, 6/6 prompts"),
        new Component("Bridge MCP", "MCP bridge", "Termux Bridge 0.1.3"),
        new Component("DroidMCP-Filesystem", "MCP server", "Read-only filesystem runtime"),
        new Component("Buddy-MyAI app", "Android application", "Package/version must be verified on device"),
        new Component("Buddy AccessibilityService", "Android service", "Required for phone interaction"),
        new Component("ADB / Android shell", "Android capability", "Availability and authorization must be checked"),
        new Component("Shizuku", "Android service", "Presence known historically; dependency is unverified"),
        new Component("Termux", "Execution environment", "External-command integration not yet validated"),
        new Component("Tunnel Client v0.0.19", "Runtime executable", "ARM64 binary in Termux home"),
        new Component("Control Plane connectivity", "Network dependency", "Poll result and auth state; never expose secrets"),
        new Component("ChatGPT → Buddy → Android", "End-to-end path", "Read-only read_screen probe"),
        new Component("ChatGPT → REA / Bridge", "End-to-end path", "Discovery and safe health probe")
    );

    @Override
    protected void onCreate(Bundle state) {
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

        TextView title = text("JITI CONTROL CENTER", 22, FG, true);
        page.addView(title);
        TextView subtitle = text("Полная панель Buddy-MyAI и инфраструктуры", 14, MUTED, false);
        addTop(page, subtitle, 5);

        TextView warning = text("ПРОТОТИП • состояния пока не опрашиваются. Все компоненты отмечены «Не проверено», а команды управления отключены до подключения безопасного адаптера.", 13, AMBER, true);
        addCard(page, warning, 12);

        addSection(page, "Управление системой");
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        Button check = button("Проверить всё");
        Button start = button("Запустить всё");
        Button restart = button("Перезапустить всё");
        Button stop = button("Остановить всё");
        for (Button b : Arrays.asList(check, start, restart, stop)) {
            b.setEnabled(false);
            controls.addView(b, matchWrap());
        }
        addCard(page, controls, 8);

        addSection(page, "Полный реестр компонентов");
        String currentGroup = "";
        for (Component c : components) {
            if (!c.kind.equals(currentGroup)) {
                currentGroup = c.kind;
                TextView group = text(currentGroup.toUpperCase(), 12, MUTED, true);
                addTop(page, group, 14);
            }
            addComponentCard(page, c);
        }

        addSection(page, "Последняя диагностика");
        TextView diag = text("Ещё не выполнялась из приложения. Исторические данные 22–23 ноября используются как исходные сведения, но не подменяют текущую проверку телефона.", 13, MUTED, false);
        addCard(page, diag, 8);

        setContentView(scroll);
    }

    private void addComponentCard(LinearLayout parent, Component c) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(CARD);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(c.name, 15, FG, true);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView status = text("НЕ ПРОВЕРЕНО", 10, AMBER, true);
        row.addView(status);
        card.addView(row);

        TextView detail = text(c.detail, 12, MUTED, false);
        addTop(card, detail, 5);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button inspect = button("Проверить");
        Button restart = button("Перезапустить");
        inspect.setEnabled(false);
        restart.setEnabled(false);
        actions.addView(inspect, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(restart, new LinearLayout.LayoutParams(0, dp(42), 1f));
        addTop(card, actions, 9);
        addCard(parent, card, 7);
    }

    private void addSection(LinearLayout parent, String label) {
        addTop(parent, text(label, 17, FG, true), 18);
    }

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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
