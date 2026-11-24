package app.buddy.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/** User-controlled configuration and start/stop controls for Buddy's autonomous tunnel runtime. */
public final class RuntimeSettingsActivity extends Screen {
    private EditText apiKey;
    private EditText tunnelId;
    private Switch autoStart;
    private TextView savedKey;
    private TextView status;
    private TextView bridgeStatus;
    private TextView droidStatus;
    private TextView reaStatus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        android.widget.ImageButton back = iconButton(this, R.drawable.ms_arrow_back,
                getString(R.string.perms_back));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        top.addView(title(this, getString(R.string.runtime_settings_title), 18), weight1());
        root.addView(top, full());

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 28));
        body.addView(text(this, getString(R.string.runtime_settings_info), 14, MUTED), gap(8));

        body.addView(sectionLabel(this, getString(R.string.runtime_api_key_label)), gap(8));
        apiKey = field(getString(R.string.runtime_api_key_hint), true);
        body.addView(apiKey, full());
        savedKey = text(this, "", 12, MUTED);
        body.addView(savedKey, gap(4));

        body.addView(sectionLabel(this, getString(R.string.runtime_tunnel_id_label)), gap(16));
        tunnelId = field(getString(R.string.runtime_tunnel_id_hint), false);
        tunnelId.setText(getSharedPreferences("runtime", MODE_PRIVATE)
                .getString("tunnel_id", RuntimeProcessLauncher.DEFAULT_TUNNEL_ID));
        body.addView(tunnelId, full());

        autoStart = new Switch(this);
        autoStart.setText(getString(R.string.runtime_auto_start));
        autoStart.setTextColor(TEXT);
        autoStart.setTextSize(15);
        autoStart.setChecked(getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("auto_start", false));
        body.addView(autoStart, gap(18));
        body.addView(text(this, getString(R.string.runtime_auto_start_info), 12, MUTED), gap(4));

        Button save = button(this, getString(R.string.runtime_save), true);
        body.addView(save, topGap(18));
        save.setOnClickListener(v -> saveSettings());

        LinearLayout stateCard = column(this);
        stateCard.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        stateCard.setBackground(outlined(this, CARD, 14, OUTLINE, 1));
        stateCard.addView(title(this, getString(R.string.runtime_status), 14));
        status = text(this, "", 13, MUTED);
        stateCard.addView(status, topGap(4));
        stateCard.addView(sectionLabel(this, getString(R.string.runtime_bridge_status_label)), topGap(12));
        bridgeStatus = text(this, "", 13, MUTED);
        stateCard.addView(bridgeStatus, topGap(4));
        stateCard.addView(sectionLabel(this, getString(R.string.runtime_droid_status_label)), topGap(12));
        droidStatus = text(this, "", 13, MUTED);
        stateCard.addView(droidStatus, topGap(4));
        stateCard.addView(sectionLabel(this, getString(R.string.runtime_rea_status_label)), topGap(12));
        reaStatus = text(this, "", 13, MUTED);
        stateCard.addView(reaStatus, topGap(4));
        body.addView(stateCard, topGap(18));

        Button start = button(this, getString(R.string.runtime_start), true);
        body.addView(start, topGap(14));
        start.setOnClickListener(v -> {
            if (!saveSettings()) return;
            BuddyRuntimeService.start(this);
            Toast.makeText(this, R.string.runtime_toast_started, Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        Button stop = button(this, getString(R.string.runtime_stop), false);
        body.addView(stop, topGap(8));
        stop.setOnClickListener(v -> {
            BuddyRuntimeService.stop(this);
            Toast.makeText(this, R.string.runtime_toast_stopped, Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame));
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private EditText field(String hint, boolean secret) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(14);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setHint(hint);
        edit.setPaddingRelative(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        edit.setBackground(outlined(this, CARD, 12, OUTLINE, 1));
        edit.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        edit.setTextIsSelectable(false);
        return edit;
    }

    private boolean saveSettings() {
        String key = apiKey.getText().toString().trim();
        String id = tunnelId.getText().toString().trim();
        if (!id.matches("tunnel_[a-fA-F0-9]{32}")) {
            tunnelId.setError(getString(R.string.runtime_invalid_tunnel_id));
            tunnelId.requestFocus();
            return false;
        }
        if (key.isEmpty() && !Secrets.has(this, RuntimeProcessLauncher.API_KEY_SECRET)) {
            apiKey.setError(getString(R.string.runtime_missing_api_key));
            apiKey.requestFocus();
            return false;
        }
        try {
            if (!key.isEmpty()) Secrets.put(this, RuntimeProcessLauncher.API_KEY_SECRET, key);
            getSharedPreferences("runtime", MODE_PRIVATE).edit()
                    .putString("tunnel_id", id)
                    .putBoolean("auto_start", autoStart.isChecked())
                    .apply();
            apiKey.setText("");
            refreshStatus();
            Toast.makeText(this, R.string.runtime_toast_saved, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void refreshStatus() {
        if (savedKey == null || status == null) return;
        savedKey.setText(Secrets.has(this, RuntimeProcessLauncher.API_KEY_SECRET)
                ? getString(R.string.runtime_api_key_saved) : getString(R.string.runtime_status_not_configured));
        String state = getSharedPreferences("runtime", MODE_PRIVATE).getString("state", "STOPPED");
        String detail = getSharedPreferences("runtime", MODE_PRIVATE).getString("detail", "");
        status.setText(getString(R.string.runtime_status_detail,
                detail.isEmpty() ? state : state + " · " + detail));
        String bridgeState = getSharedPreferences("runtime", MODE_PRIVATE).getString("bridge_state", "NOT_STARTED");
        String bridgeDetail = getSharedPreferences("runtime", MODE_PRIVATE).getString("bridge_detail", "");
        bridgeStatus.setText(getString(R.string.runtime_bridge_status_detail,
                bridgeDetail.isEmpty() ? bridgeState : bridgeState + " · " + bridgeDetail));
        String droidState = getSharedPreferences("runtime", MODE_PRIVATE).getString("droidmcp_state", "NOT_STARTED");
        String droidDetail = getSharedPreferences("runtime", MODE_PRIVATE).getString("droidmcp_detail", "");
        droidStatus.setText(getString(R.string.runtime_droid_status_detail,
                droidDetail.isEmpty() ? droidState : droidState + " · " + droidDetail));
        String reaState = getSharedPreferences("runtime", MODE_PRIVATE).getString("rea_state", "NOT_STARTED");
        String reaDetail = getSharedPreferences("runtime", MODE_PRIVATE).getString("rea_detail", "");
        reaStatus.setText(getString(R.string.runtime_rea_status_detail,
                reaDetail.isEmpty() ? reaState : reaState + " · " + reaDetail));
    }
}
