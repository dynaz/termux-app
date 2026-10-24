package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The MCP Bridge page in the left rail (a Premium feature): turn the bridge on, connect it to the
 * relay, pair and revoke AI clients, share managed Claude Code sessions with per-session
 * permissions, and watch, open and cancel the tasks clients submit. Secrets (a pairing token, the
 * device token) are shown once in a copyable dialog and never stored in the page. An emergency stop
 * turns everything off and revokes every client, and is never gated behind Premium.
 */
final class TerminowMcpBridgeUi {

    /** Opens a task's mirrored stream in a terminal tab; the host provides it (it owns sessions). */
    interface OpenInTerminal {
        void open(@NonNull java.io.File logFile, @NonNull String title);
    }

    /** Opens an interactive terminal in a shared session's environment at its folder. */
    interface OpenSessionTerminal {
        void open(@NonNull String environmentKey, @NonNull String user, @NonNull String folder);
    }

    private final TermuxActivity mActivity;
    private final TerminowMcpBridgeManager mManager;
    private final TerminowStore mStore;
    private final LinearLayout mContent;
    private final TerminowAccountUi mAccountUi;
    @NonNull private final OpenInTerminal mOpenInTerminal;
    @NonNull private final OpenSessionTerminal mOpenSessionTerminal;

    TerminowMcpBridgeUi(@NonNull TermuxActivity activity, @NonNull TerminowMcpBridgeManager manager,
                        @NonNull TerminowStore store, @NonNull LinearLayout container,
                        @NonNull OpenInTerminal openInTerminal, @NonNull OpenSessionTerminal openSessionTerminal) {
        mActivity = activity;
        mManager = manager;
        mStore = store;
        mContent = container;
        mOpenInTerminal = openInTerminal;
        mOpenSessionTerminal = openSessionTerminal;
        mAccountUi = new TerminowAccountUi(activity, store, this::render);
    }

    void render() {
        if (mContent == null) return;
        mContent.removeAllViews();
        mContent.setPadding(dp(18), dp(16), dp(18), dp(24));

        TextView title = text(mActivity.getString(R.string.terminow_mcp_bridge_title), 22, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        mContent.addView(title);
        mContent.addView(note(mActivity.getString(R.string.terminow_mcp_bridge_desc)));

        if (!gateEntitlement()) return;

        boolean enabled = mManager.isEnabled();
        TerminowMcpBridgeManager.Status status = mManager.status();

        // Enable
        LinearLayout enableCard = card(mContent);
        LinearLayout enableRow = row();
        LinearLayout enableTexts = column();
        TextView enableTitle = text(mActivity.getString(R.string.terminow_mcp_bridge_enable), 16, R.color.terminow_text);
        enableTitle.setTypeface(Typeface.DEFAULT_BOLD);
        enableTexts.addView(enableTitle);
        enableRow.addView(enableTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        android.widget.Switch toggle = new android.widget.Switch(mActivity);
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
        if (enabled && status.listening) {
            enableCard.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_status_on,
                status.port, status.pairedClients, status.sharedSessions, status.activeTasks), 12, R.color.terminow_text_secondary));
        } else if (enabled) {
            enableCard.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_status_error,
                status.error != null ? status.error : "unknown"), 12, R.color.terminow_mcp_warn));
        }

        if (!enabled) {
            mContent.addView(note(mActivity.getString(R.string.terminow_mcp_bridge_off_note)));
            addEmergencyStop();
            return;
        }

        renderRelay();
        renderClients();
        renderSessions();
        renderTasks();
        addEmergencyStop();
    }

    // ---------------------------------------------------------------------------------------
    // Relay
    // ---------------------------------------------------------------------------------------

    private void renderRelay() {
        mContent.addView(section(mActivity.getString(R.string.terminow_mcp_bridge_relay_section)));
        LinearLayout relayCard = card(mContent);
        String url = mManager.relayUrl();
        boolean connected = !url.isEmpty();
        if (connected) {
            TextView endpoint = text(mManager.publicMcpEndpoint(url), 14, R.color.terminow_text);
            endpoint.setTypeface(Typeface.MONOSPACE);
            relayCard.addView(endpoint);
            relayCard.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_relay_state,
                mManager.relayState().name()), 12, R.color.terminow_text_secondary));
            relayCard.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_relay_disconnect), false, () -> {
                mManager.clearRelay();
                render();
            }));
        } else {
            relayCard.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_relay_off), 13, R.color.terminow_text_secondary));
            relayCard.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_relay_connect), true, this::connectRelayDialog));
        }
    }

    private void connectRelayDialog() {
        EditText field = new EditText(mActivity);
        field.setText(mManager.relayUrl().isEmpty() ? TerminowMcpBridgeManager.DEFAULT_RELAY_URL : mManager.relayUrl());
        field.setSingleLine(true);
        LinearLayout box = dialogBox();
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_relay_endpoint), 13, R.color.terminow_text_secondary));
        box.addView(field);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_relay_section)
            .setView(box)
            .setPositiveButton(R.string.terminow_mcp_bridge_relay_connect, (d, w) -> {
                try {
                    String deviceToken = mManager.configureRelay(field.getText().toString());
                    showSecret(mActivity.getString(R.string.terminow_mcp_bridge_relay_token_title),
                        mActivity.getString(R.string.terminow_mcp_bridge_relay_token_message), deviceToken);
                } catch (IllegalStateException e) {
                    mActivity.showToast(e.getMessage(), true);
                }
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Clients
    // ---------------------------------------------------------------------------------------

    private void renderClients() {
        mContent.addView(section(mActivity.getString(R.string.terminow_mcp_bridge_clients_section)));
        mContent.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_pair), true, this::pairDialog));
        List<JSONObject> clients = mManager.pairedClients();
        if (clients.isEmpty()) {
            mContent.addView(note(mActivity.getString(R.string.terminow_mcp_bridge_no_clients)));
            return;
        }
        for (JSONObject client : clients) {
            String id = client.optString("id");
            LinearLayout card = card(mContent);
            LinearLayout line = row();
            LinearLayout texts = column();
            TextView name = text(client.optString("label", id), 16, R.color.terminow_text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(name);
            long created = client.optLong("createdAt");
            if (created > 0) texts.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_paired_on,
                DateUtils.getRelativeTimeSpanString(created).toString()), 12, R.color.terminow_text_secondary));
            line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_revoke), false, () -> {
                mManager.revokeClient(id);
                mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_revoked), false);
                render();
            }));
            card.addView(line, matchWrap());
        }
    }

    private void pairDialog() {
        EditText field = new EditText(mActivity);
        field.setHint(R.string.terminow_mcp_bridge_pair_hint);
        field.setSingleLine(true);
        LinearLayout box = dialogBox();
        box.addView(field);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_pair_title)
            .setView(box)
            .setPositiveButton(R.string.terminow_mcp_bridge_pair, (d, w) -> {
                try {
                    TerminowMcpBridgeManager.PairResult result = mManager.pairClient(field.getText().toString());
                    showSecret(mActivity.getString(R.string.terminow_mcp_bridge_pair_token_title),
                        mActivity.getString(R.string.terminow_mcp_bridge_pair_token_message), result.token);
                } catch (IllegalStateException e) {
                    mActivity.showToast(e.getMessage(), true);
                }
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Shared sessions
    // ---------------------------------------------------------------------------------------

    private void renderSessions() {
        mContent.addView(section(mActivity.getString(R.string.terminow_mcp_bridge_sessions_section)));
        if (mManager.environments().isEmpty()) {
            mContent.addView(note(mActivity.getString(R.string.terminow_mcp_bridge_no_env)));
        } else {
            mContent.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_share), true, this::shareDialog));
        }
        List<TerminowMcpBridge.Share> shares = mManager.listShares();
        if (shares.isEmpty()) {
            mContent.addView(note(mActivity.getString(R.string.terminow_mcp_bridge_no_sessions)));
            return;
        }
        for (TerminowMcpBridge.Share share : shares) {
            LinearLayout card = card(mContent);
            LinearLayout line = row();
            LinearLayout texts = column();
            TextView name = text(share.label, 16, R.color.terminow_text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(name);
            texts.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_session_shared,
                capabilitiesText(share)), 12, R.color.terminow_quick_ready));
            texts.addView(text(share.environmentKey + (share.folder.isEmpty() ? "" : " · " + share.folder),
                12, R.color.terminow_text_secondary));
            line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_unshare), false, () -> {
                mManager.removeShare(share.id);
                render();
            }));
            card.addView(line, matchWrap());
            card.setOnClickListener(v -> editShareDialog(share));
        }
    }

    private void shareDialog() {
        List<TerminowMcpTargets.Container> environments = mManager.environments();
        List<String> envKeys = new ArrayList<>();
        List<String> envLabels = new ArrayList<>();
        for (TerminowMcpTargets.Container container : environments) {
            envKeys.add(container.name);
            envLabels.add(container.label);
        }
        if (envKeys.isEmpty()) return;

        EditText label = new EditText(mActivity);
        label.setSingleLine(true);
        label.setText(envLabels.get(0) + " — Claude");
        EditText folder = new EditText(mActivity);
        folder.setSingleLine(true);
        folder.setText(TerminowMcpTargets.getGuestHome(environments.get(0), "root"));

        final int[] selected = {0};
        TextView envValue = text(envLabels.get(0), 14, R.color.terminow_text);
        envValue.setPadding(0, dp(4), 0, dp(8));
        envValue.setOnClickListener(v -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_share_env)
            .setItems(envLabels.toArray(new String[0]), (d, which) -> {
                selected[0] = which;
                envValue.setText(envLabels.get(which));
                folder.setText(TerminowMcpTargets.getGuestHome(environments.get(which), "root"));
            }).show());

        LinearLayout box = dialogBox();
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_label), 13, R.color.terminow_text_secondary));
        box.addView(label);
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_env), 13, R.color.terminow_text_secondary));
        box.addView(envValue);
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_folder), 13, R.color.terminow_text_secondary));
        box.addView(folder);
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_managed), 12, R.color.terminow_text_secondary));

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_share_title)
            .setView(box)
            .setPositiveButton(R.string.terminow_mcp_bridge_share, (d, w) -> {
                int i = selected[0];
                String id = "session-" + UUID.randomUUID();
                TerminowMcpBridge.Share share = TerminowMcpBridgeManager.managedShare(id,
                    label.getText().toString().trim().isEmpty() ? envLabels.get(i) : label.getText().toString().trim(),
                    envKeys.get(i), "root", folder.getText().toString().trim());
                try {
                    mManager.putShare(share);
                } catch (IllegalStateException e) {
                    mActivity.showToast(e.getMessage(), true);
                }
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Edit a share's client permissions. */
    private void editShareDialog(@NonNull TerminowMcpBridge.Share share) {
        CheckBox submit = checkbox(R.string.terminow_mcp_bridge_cap_submit, share.capabilities.contains(TerminowMcpBridge.CAP_SUBMIT));
        CheckBox read = checkbox(R.string.terminow_mcp_bridge_cap_read, share.capabilities.contains(TerminowMcpBridge.CAP_READ));
        CheckBox cancel = checkbox(R.string.terminow_mcp_bridge_cap_cancel, share.capabilities.contains(TerminowMcpBridge.CAP_CANCEL));
        LinearLayout box = dialogBox();
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_caps_title), 13, R.color.terminow_text_secondary));
        box.addView(submit);
        box.addView(read);
        box.addView(cancel);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(share.label)
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                List<String> caps = new ArrayList<>();
                if (submit.isChecked()) caps.add(TerminowMcpBridge.CAP_SUBMIT);
                if (read.isChecked()) caps.add(TerminowMcpBridge.CAP_READ);
                if (cancel.isChecked()) caps.add(TerminowMcpBridge.CAP_CANCEL);
                try {
                    mManager.putShare(new TerminowMcpBridge.Share(share.id, share.label, share.managed,
                        share.environmentKey, share.user, share.folder, caps));
                } catch (IllegalStateException e) {
                    mActivity.showToast(e.getMessage(), true);
                }
                render();
            })
            .setNeutralButton(R.string.terminow_mcp_bridge_open_terminal, (d, w) ->
                mOpenSessionTerminal.open(share.environmentKey, share.user, share.folder))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Tasks
    // ---------------------------------------------------------------------------------------

    private void renderTasks() {
        mContent.addView(section(mActivity.getString(R.string.terminow_mcp_bridge_tasks_section)));
        List<TerminowMcpBridgeTask> tasks = mManager.tasks();
        if (tasks.isEmpty()) {
            mContent.addView(note(mActivity.getString(R.string.terminow_mcp_bridge_no_tasks)));
            return;
        }
        int shown = 0;
        for (TerminowMcpBridgeTask task : tasks) {
            if (shown++ >= 40) break;
            LinearLayout card = card(mContent);
            TextView state = text(task.state().name().toLowerCase(java.util.Locale.ROOT), 12, stateColor(task.state()));
            state.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(state);
            card.addView(text(oneLine(task.request), 14, R.color.terminow_text));
            if (task.state() == TerminowMcpBridgeTask.State.WAITING_FOR_USER && !task.pendingAction().isEmpty())
                card.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_task_pending, task.pendingAction()), 12, R.color.terminow_mcp_warn));
            card.setOnClickListener(v -> taskDialog(task));
        }
    }

    private void taskDialog(@NonNull TerminowMcpBridgeTask task) {
        LinearLayout box = dialogBox();
        addField(box, R.string.terminow_mcp_bridge_task_state, task.state().name().toLowerCase(java.util.Locale.ROOT));
        addField(box, R.string.terminow_mcp_bridge_task_request, task.request);
        addField(box, R.string.terminow_mcp_bridge_task_target, task.sessionId);
        if (!task.pendingAction().isEmpty()) addField(box, R.string.terminow_mcp_bridge_task_pending, task.pendingAction());
        if (!task.result().isEmpty()) addField(box, R.string.terminow_mcp_bridge_task_result, task.result());
        if (!task.failureReason().isEmpty()) addField(box, R.string.terminow_mcp_bridge_task_failure, task.failureReason());
        TerminowMcpBridgeTask.Page page = task.outputFrom(Math.max(0, task.outputLength() - 4000), 4000);
        if (!page.text.isEmpty()) {
            box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_task_output), 12, R.color.terminow_text_secondary));
            TextView output = text(page.text, 12, R.color.terminow_text);
            output.setTypeface(Typeface.MONOSPACE);
            box.addView(output);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_task_title)
            .setView(wrapScroll(box))
            .setNegativeButton(android.R.string.ok, null);
        if (task.state().isActive())
            builder.setPositiveButton(R.string.terminow_mcp_bridge_cancel_task, (d, w) -> {
                mManager.cancelTask(task.id);
                render();
            });
        java.io.File log = mManager.taskLogFile(task.id);
        if (log != null && log.isFile())
            builder.setNeutralButton(R.string.terminow_mcp_bridge_open_terminal, (d, w) ->
                mOpenInTerminal.open(log, oneLine(task.request)));
        builder.show();
    }

    private int stateColor(@NonNull TerminowMcpBridgeTask.State state) {
        switch (state) {
            case COMPLETED: return R.color.terminow_quick_ready;
            case FAILED: case INTERRUPTED: case CANCELLED: return R.color.terminow_mcp_warn;
            default: return R.color.terminow_panel_accent;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Emergency stop
    // ---------------------------------------------------------------------------------------

    private void addEmergencyStop() {
        View stop = button(mActivity.getString(R.string.terminow_mcp_bridge_emergency_stop), false, () ->
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_mcp_bridge_emergency_stop)
                .setMessage(R.string.terminow_mcp_bridge_emergency_desc)
                .setPositiveButton(R.string.terminow_mcp_bridge_emergency_stop, (d, w) -> {
                    mManager.emergencyStop();
                    mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_stopped), false);
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) stop.getLayoutParams();
        p.topMargin = dp(20);
        mContent.addView(stop);
    }

    // ---------------------------------------------------------------------------------------
    // Premium gate (same behaviour as the AI page)
    // ---------------------------------------------------------------------------------------

    private boolean gateEntitlement() {
        TerminowEntitlement.State state = TerminowEntitlement.get(mStore);
        if (state == TerminowEntitlement.State.ACTIVE) return true;
        if (mAccountUi.isCheckingPremium()) {
            renderChecking();
            return false;
        }
        if (state == TerminowEntitlement.State.UNKNOWN || state == TerminowEntitlement.State.NEEDS_CHECK) {
            mAccountUi.checkPremiumNow();
            renderChecking();
            return false;
        }
        renderUpgrade(state);
        return false;
    }

    private void renderChecking() {
        LinearLayout card = card(mContent);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ProgressBar spinner = new ProgressBar(mActivity);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
        card.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView checking = text(mActivity.getString(R.string.terminow_account_premium_checking), 14, R.color.terminow_text_secondary);
        checking.setGravity(Gravity.CENTER);
        checking.setPadding(0, dp(12), 0, 0);
        card.addView(checking, matchWrap());
    }

    private void renderUpgrade(@NonNull TerminowEntitlement.State state) {
        LinearLayout card = card(mContent);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView crown = new ImageView(mActivity);
        crown.setImageResource(R.drawable.ic_terminow_crown);
        card.addView(crown, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView heading = text(mActivity.getString(R.string.terminow_mcp_bridge_title), 18, R.color.terminow_text);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setPadding(0, dp(10), 0, dp(6));
        card.addView(heading, matchWrap());
        TextView desc = text(mActivity.getString(R.string.terminow_mcp_bridge_premium), 14, R.color.terminow_text_secondary);
        desc.setGravity(Gravity.CENTER);
        card.addView(desc, matchWrap());

        String error = mAccountUi.getPremiumError();
        boolean signedOut = state == TerminowEntitlement.State.SIGNED_OUT;
        if (error != null) {
            TextView errorView = text(error, 13, R.color.terminow_mcp_warn);
            errorView.setGravity(Gravity.CENTER);
            errorView.setPadding(0, dp(10), 0, 0);
            card.addView(errorView, matchWrap());
        }
        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams buttonsParams = matchWrap();
        buttonsParams.topMargin = dp(16);
        buttons.addView(button(mActivity.getString(R.string.terminow_install_check_again), false, () -> {
            mAccountUi.checkPremiumNow();
            render();
        }));
        if (!signedOut) {
            View upgrade = button(mActivity.getString(R.string.terminow_premium_get), true, mAccountUi::buyPremium);
            ((LinearLayout.LayoutParams) upgrade.getLayoutParams()).setMarginStart(dp(10));
            buttons.addView(upgrade);
        }
        card.addView(buttons, buttonsParams);
    }

    // ---------------------------------------------------------------------------------------
    // Small view helpers (mirrors the other rail pages)
    // ---------------------------------------------------------------------------------------

    @NonNull
    private String capabilitiesText(@NonNull TerminowMcpBridge.Share share) {
        List<String> names = new ArrayList<>();
        if (share.capabilities.contains(TerminowMcpBridge.CAP_SUBMIT)) names.add(mActivity.getString(R.string.terminow_mcp_bridge_cap_submit));
        if (share.capabilities.contains(TerminowMcpBridge.CAP_READ)) names.add(mActivity.getString(R.string.terminow_mcp_bridge_cap_read));
        if (share.capabilities.contains(TerminowMcpBridge.CAP_CANCEL)) names.add(mActivity.getString(R.string.terminow_mcp_bridge_cap_cancel));
        return names.isEmpty() ? "—" : android.text.TextUtils.join(", ", names);
    }

    private void showSecret(@NonNull String title, @NonNull String message, @NonNull String secret) {
        LinearLayout box = dialogBox();
        box.addView(text(message, 13, R.color.terminow_text_secondary));
        TextView value = text(secret, 13, R.color.terminow_text);
        value.setTypeface(Typeface.MONOSPACE);
        value.setPadding(0, dp(10), 0, 0);
        value.setTextIsSelectable(true);
        box.addView(value);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(R.string.terminow_mcp_bridge_copy, (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("TermiNow", secret));
                mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_copied), false);
            })
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private void addField(@NonNull LinearLayout box, int labelRes, @NonNull String value) {
        box.addView(text(mActivity.getString(labelRes), 12, R.color.terminow_text_secondary));
        TextView view = text(value, 14, R.color.terminow_text);
        view.setPadding(0, dp(2), 0, dp(8));
        box.addView(view);
    }

    @NonNull
    private CheckBox checkbox(int labelRes, boolean checked) {
        CheckBox box = new CheckBox(mActivity);
        box.setText(labelRes);
        box.setChecked(checked);
        box.setTextColor(color(R.color.terminow_text));
        return box;
    }

    @NonNull
    private String oneLine(@NonNull String value) {
        String single = value.replaceAll("\\s+", " ").trim();
        return single.length() > 120 ? single.substring(0, 120) + "…" : single;
    }

    @NonNull
    private android.widget.ScrollView wrapScroll(@NonNull View child) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(mActivity);
        scroll.addView(child);
        return scroll;
    }

    @NonNull
    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        return box;
    }

    @NonNull
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    @NonNull
    private LinearLayout column() {
        LinearLayout column = new LinearLayout(mActivity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.topMargin = dp(10);
        button.setLayoutParams(params);
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
