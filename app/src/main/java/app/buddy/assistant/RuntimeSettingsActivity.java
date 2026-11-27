package app.buddy.assistant;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
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
    private Switch monitorBridge;
    private Switch monitorDroidMcp;
    private Switch reaEnabled;
    private TextView savedKey;
    private TextView status;
    private TextView bridgeStatus;
    private TextView droidStatus;
    private TextView reaStatus;
    private Button grantStorageAccess;

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
        body.addView(text(this, getString(R.string.runtime_settings_info, McpServer.PORT), 14, MUTED), topGap(8));
        body.addView(text(this, getString(R.string.runtime_termux_monitor_info), 12, MUTED), topGap(6));

        body.addView(sectionLabel(this, getString(R.string.runtime_api_key_label)), topGap(8));
        apiKey = field(getString(R.string.runtime_api_key_hint), true);
        body.addView(apiKey, full());
        savedKey = text(this, "", 12, MUTED);
        body.addView(savedKey, topGap(4));

        body.addView(sectionLabel(this, getString(R.string.runtime_tunnel_id_label)), topGap(16));
        tunnelId = field(getString(R.string.runtime_tunnel_id_hint), false);
        tunnelId.setText(getSharedPreferences("runtime", MODE_PRIVATE)
                .getString("tunnel_id", RuntimeProcessLauncher.DEFAULT_TUNNEL_ID));
        body.addView(tunnelId, full());

        autoStart = new Switch(this);
        autoStart.setText(getString(R.string.runtime_auto_start));
        autoStart.setTextColor(TEXT);
        autoStart.setTextSize(15);
        autoStart.setChecked(getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("auto_start", false));
        body.addView(autoStart, topGap(18));
        body.addView(text(this, getString(R.string.runtime_auto_start_info), 12, MUTED), topGap(4));

        monitorBridge = new Switch(this);
        monitorBridge.setText(getString(R.string.runtime_monitor_bridge));
        monitorBridge.setTextColor(TEXT);
        monitorBridge.setTextSize(15);
        monitorBridge.setChecked(getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("monitor_bridge", true));
        body.addView(monitorBridge, topGap(16));

        monitorDroidMcp = new Switch(this);
        monitorDroidMcp.setText(getString(R.string.runtime_monitor_droidmcp));
        monitorDroidMcp.setTextColor(TEXT);
        monitorDroidMcp.setTextSize(15);
        monitorDroidMcp.setChecked(getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("monitor_droidmcp", true));
        body.addView(monitorDroidMcp, topGap(10));

        reaEnabled = new Switch(this);
        reaEnabled.setText(getString(R.string.runtime_enable_local_rea));
        reaEnabled.setTextColor(TEXT);
        reaEnabled.setTextSize(15);
        reaEnabled.setChecked(getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("rea_enabled", true));
        body.addView(reaEnabled, topGap(10));
        body.addView(text(this, getString(R.string.runtime_helper_switch_info), 12, MUTED), topGap(4));

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

        grantStorageAccess = button(this, getString(R.string.runtime_grant_file_access), true);
        body.addView(grantStorageAccess, topGap(12));
        grantStorageAccess.setOnClickListener(v -> openAllFilesAccessSettings());

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

    private LinearLayout.LayoutParams topGap(int marginDp) {
        LinearLayout.LayoutParams params = full();
        params.topMargin = dp(this, marginDp);
        return params;
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

    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e) {
                Toast.makeText(this, R.string.runtime_file_access_settings_unavailable, Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean saveSettings() {
        String key = apiKey.getText().toString().trim();
        String id = tunnelId.getText().toString().trim();
        if (RuntimeProcessLauncher.RESERVED_TEST220_TUNNEL_ID.equalsIgnoreCase(id)) {
            tunnelId.setError(getString(R.string.runtime_test220_tunnel_id_reserved));
            tunnelId.requestFocus();
            return false;
        }
        if (!id.isEmpty() && !id.matches("tunnel_[a-fA-F0-9]{32}")) {
            tunnelId.setError(getString(R.string.runtime_invalid_tunnel_id));
            tunnelId.requestFocus();
            return false;
        }
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("runtime", MODE_PRIVATE);
            String currentState = prefs.getString("state", "STOPPED");
            boolean runtimeWasActive = !"STOPPED".equals(currentState);
            if (!key.isEmpty()) Secrets.put(this, RuntimeProcessLauncher.API_KEY_SECRET, key);
            prefs.edit()
                    .putString("tunnel_id", id)
                    .putBoolean("auto_start", autoStart.isChecked())
                    .putBoolean("monitor_bridge", monitorBridge.isChecked())
                    .putBoolean("monitor_droidmcp", monitorDroidMcp.isChecked())
                    .putBoolean("rea_enabled", reaEnabled.isChecked())
                    .apply();
            apiKey.setText("");
            refreshStatus();
            Toast.makeText(this, R.string.runtime_toast_saved, Toast.LENGTH_SHORT).show();
            if (runtimeWasActive) BuddyRuntimeService.start(this);
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
        if (grantStorageAccess != null) {
            boolean needsAccess = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager();
            grantStorageAccess.setVisibility(needsAccess ? View.VISIBLE : View.GONE);
        }
    }
}
