package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.List;

/**
 * Settings → AI Integration: turn the Local AI Bridge on, connect Claude Code of an environment,
 * see the status, allow or stop hosts for the AI, and revoke everything. No secret is shown or
 * entered here; a host is allowed by its stable id, and its credentials stay in TermiNow.
 */
final class TerminowAiSettings {

    private final TermuxActivity mActivity;
    private final TerminowAiBridgeManager mManager;
    private final TerminowStore mStore;
    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    private List<TerminowStore.Host> mHosts;

    TerminowAiSettings(@NonNull TermuxActivity activity, @NonNull TerminowAiBridgeManager manager, @NonNull TerminowStore store) {
        mActivity = activity;
        mManager = manager;
        mStore = store;
    }

    void show() {
        mHosts = mStore.getHosts();
        Dialog dialog = new Dialog(mActivity, R.style.TerminowPanelDialog);
        mDialog = dialog;
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.terminow_panel));
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mContent = content;
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        render();
        dialog.show();
    }

    private void render() {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();

        TextView title = text(mActivity.getString(R.string.terminow_ai_integration), 22, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        boolean enabled = mManager.isEnabled();
        // Enable switch
        LinearLayout enableCard = card(content);
        LinearLayout enableRow = new LinearLayout(mActivity);
        enableRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout enableTexts = new LinearLayout(mActivity);
        enableTexts.setOrientation(LinearLayout.VERTICAL);
        TextView enableTitle = text(mActivity.getString(R.string.terminow_ai_enable), 16, R.color.terminow_text);
        enableTitle.setTypeface(Typeface.DEFAULT_BOLD);
        enableTexts.addView(enableTitle);
        enableTexts.addView(text(mActivity.getString(R.string.terminow_ai_enable_desc), 12, R.color.terminow_text_secondary));
        enableRow.addView(enableTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(mActivity);
        toggle.setChecked(enabled);
        toggle.setOnClickListener(v -> {
            TerminowAiBridgeManager.SetupResult result = mManager.setEnabled(toggle.isChecked());
            if (!result.ok) {
                toggle.setChecked(false);
                mActivity.showToast(result.message, true);
            }
            render();
        });
        enableRow.addView(toggle);
        enableCard.addView(enableRow, matchWrap());
        if (enabled) {
            TerminowAiBridgeManager.Status status = mManager.status();
            enableCard.addView(text(mActivity.getString(R.string.terminow_ai_status_on,
                status.connectedEnvironments, status.openConnections, status.activeGrants), 12, R.color.terminow_text_secondary));
        }

        if (!enabled) {
            content.addView(note(mActivity.getString(R.string.terminow_ai_bridge_off_note)));
            return;
        }

        // Connect Claude Code per environment
        content.addView(section(mActivity.getString(R.string.terminow_ai_connect_section)));
        List<TerminowMcpTargets.Container> environments = mManager.environments();
        if (environments.isEmpty()) {
            content.addView(note(mActivity.getString(R.string.terminow_ai_no_env)));
        } else {
            for (TerminowMcpTargets.Container container : environments) {
                LinearLayout row = card(content);
                LinearLayout line = new LinearLayout(mActivity);
                line.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout texts = new LinearLayout(mActivity);
                texts.setOrientation(LinearLayout.VERTICAL);
                TextView name = text(container.label, 16, R.color.terminow_text);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                texts.addView(name);
                boolean connected = mManager.hasToken(container.name);
                texts.addView(text(mActivity.getString(connected ? R.string.terminow_ai_connected : R.string.terminow_ai_not_connected),
                    12, connected ? R.color.terminow_quick_ready : R.color.terminow_text_secondary));
                line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                String label = mActivity.getString(connected ? R.string.terminow_ai_disconnect : R.string.terminow_ai_connect);
                line.addView(button(label, !connected, () -> {
                    TerminowAiBridgeManager.SetupResult result = connected
                        ? mManager.disconnectClaude(container.name, "root")
                        : mManager.connectClaude(container.name, "root");
                    mActivity.showToast(result.message, !result.ok);
                    render();
                }));
                row.addView(line, matchWrap());
            }
        }

        // Hosts and their AI access
        content.addView(section(mActivity.getString(R.string.terminow_ai_hosts_section)));
        boolean anyHost = false;
        for (TerminowStore.Host host : mHosts) {
            if (host.telnet || !host.isTerminal()) continue;
            anyHost = true;
            LinearLayout row = card(content);
            LinearLayout line = new LinearLayout(mActivity);
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(mActivity);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(host.getTitle(), 16, R.color.terminow_text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(name);
            String state = host.aiAccess.enabled
                ? mActivity.getString(R.string.terminow_ai_host_allowed, capabilitiesText(host))
                : mActivity.getString(R.string.terminow_ai_host_off);
            texts.addView(text(state, 12, host.aiAccess.enabled ? R.color.terminow_quick_ready : R.color.terminow_text_secondary));
            line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(line, matchWrap());
            row.setOnClickListener(v -> editHostAccess(host));
        }
        if (!anyHost) content.addView(note(mActivity.getString(R.string.terminow_ai_no_hosts)));

        // Revoke all
        View revoke = button(mActivity.getString(R.string.terminow_ai_revoke_all), false, () -> {
            mManager.revokeAll();
            mActivity.showToast(mActivity.getString(R.string.terminow_ai_revoked), false);
            render();
        });
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) revoke.getLayoutParams();
        p.topMargin = dp(16);
        content.addView(revoke);
    }

    @NonNull
    private String capabilitiesText(@NonNull TerminowStore.Host host) {
        List<String> names = new java.util.ArrayList<>();
        if (host.aiAccess.capabilities.contains(TerminowStore.AiAccess.CAP_TEST_CONNECTION)) names.add("ssh_test");
        if (host.aiAccess.capabilities.contains(TerminowStore.AiAccess.CAP_RUN_COMMAND)) names.add("ssh_exec");
        return names.isEmpty() ? "hosts_list" : android.text.TextUtils.join(", ", names);
    }

    /** The per-host AI access editor: allow, capabilities, approval. Saves into the host and persists. */
    private void editHostAccess(@NonNull TerminowStore.Host host) {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        CheckBox allow = new CheckBox(mActivity);
        allow.setText(R.string.terminow_ai_allow_host);
        allow.setChecked(host.aiAccess.enabled);
        box.addView(allow);

        CheckBox test = new CheckBox(mActivity);
        test.setText(R.string.terminow_ai_cap_test);
        test.setChecked(host.aiAccess.capabilities.contains(TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        box.addView(test);

        CheckBox exec = new CheckBox(mActivity);
        exec.setText(R.string.terminow_ai_cap_exec);
        exec.setChecked(host.aiAccess.capabilities.contains(TerminowStore.AiAccess.CAP_RUN_COMMAND));
        box.addView(exec);

        TextView warn = text(mActivity.getString(R.string.terminow_ai_exec_warning), 12, R.color.terminow_mcp_warn);
        warn.setPadding(0, dp(4), 0, dp(8));
        box.addView(warn);

        box.addView(text(mActivity.getString(R.string.terminow_ai_policy), 13, R.color.terminow_text_secondary));
        RadioGroup policy = new RadioGroup(mActivity);
        RadioButton rules = radio(R.string.terminow_ai_policy_rules);
        RadioButton session = radio(R.string.terminow_ai_policy_session);
        RadioButton ask = radio(R.string.terminow_ai_policy_ask);
        policy.addView(rules);
        policy.addView(session);
        policy.addView(ask);
        switch (host.aiAccess.approvalPolicy) {
            case "session": session.setChecked(true); break;
            case "ask": ask.setChecked(true); break;
            default: rules.setChecked(true); break;
        }
        box.addView(policy);

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_ai_access_title, host.getTitle()))
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                host.aiAccess.enabled = allow.isChecked();
                host.aiAccess.capabilities.clear();
                if (test.isChecked()) host.aiAccess.capabilities.add(TerminowStore.AiAccess.CAP_TEST_CONNECTION);
                if (exec.isChecked()) host.aiAccess.capabilities.add(TerminowStore.AiAccess.CAP_RUN_COMMAND);
                host.aiAccess.approvalPolicy = session.isChecked() ? "session" : ask.isChecked() ? "ask" : "rules";
                // Bless the current endpoint and credentials, so a later change asks again
                host.aiAccess.fingerprint = TerminowStore.aiFingerprint(host);
                mStore.setHosts(mHosts);
                mManager.onHostSaved(host);
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @NonNull
    private RadioButton radio(int labelRes) {
        RadioButton button = new RadioButton(mActivity);
        button.setText(labelRes);
        button.setTextColor(color(R.color.terminow_text));
        return button;
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private LinearLayout card(@NonNull LinearLayout parent) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.terminow_panel_card);
        card.setPadding(dp(14), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        parent.addView(card, params);
        return card;
    }

    @NonNull
    private TextView section(@NonNull String title) {
        TextView view = text(title, 13, R.color.terminow_panel_accent);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setAllCaps(true);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(16);
        view.setLayoutParams(params);
        return view;
    }

    @NonNull
    private TextView note(@NonNull String value) {
        TextView view = text(value, 13, R.color.terminow_text_secondary);
        view.setPadding(0, dp(10), 0, 0);
        return view;
    }

    @NonNull
    private View button(@NonNull String label, boolean primary, @NonNull Runnable onClick) {
        TextView button = text(label, 14, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(16), 0, dp(16), 0);
        button.setMinHeight(dp(40));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_field));
        shape.setCornerRadius(dp(20));
        button.setBackground(shape);
        button.setOnClickListener(v -> onClick.run());
        button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        return button;
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }
}
