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

    /** Page size for a session's task history, so a long history pages instead of rendering all at once. */
    private static final int SESSION_TASKS_PAGE = 25;

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
        mContent.addView(TerminowHelp.header(mActivity, title, "mcp"));
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
            mContent.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_share), true, this::shareChooser));
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
            String subtitle = share.isSshHost()
                ? "SSH · " + share.hostIdentity
                    + (TerminowMcpBridge.MODE_REMOTE_HOST.equals(share.executorMode) ? " · on host" : " · local tools")
                    + (share.folder.isEmpty() ? "" : " · " + share.folder)
                : share.environmentKey + (share.folder.isEmpty() ? "" : " · " + share.folder);
            texts.addView(text(subtitle, 12, R.color.terminow_text_secondary));
            line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_unshare), false, () -> {
                mManager.removeShare(share.id);
                render();
            }));
            card.addView(line, matchWrap());
            addSessionTasksButton(card, share);
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

    /** Choose what to share: a local environment, or a Vault SSH host. */
    private void shareChooser() {
        String[] options = {
            mActivity.getString(R.string.terminow_mcp_bridge_share_local),
            mActivity.getString(R.string.terminow_mcp_bridge_share_ssh),
        };
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_share_type_title)
            .setItems(options, (d, which) -> { if (which == 0) shareDialog(); else shareSshHostDialog(); })
            .show();
    }

    /** Share a managed session that targets a Vault SSH host (Mode A: local Claude via SSH tools). */
    private void shareSshHostDialog() {
        List<TerminowStore.Host> hosts = mStore.getHosts();
        if (hosts.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_share_no_hosts), true);
            return;
        }
        List<TerminowMcpTargets.Container> environments = mManager.environments();
        if (environments.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_no_env), true);
            return;
        }
        final String[] hostLabels = new String[hosts.size()];
        for (int i = 0; i < hosts.size(); i++) hostLabels[i] = hostDisplay(hosts.get(i));
        final List<String> envKeys = new ArrayList<>();
        final List<String> envLabels = new ArrayList<>();
        for (TerminowMcpTargets.Container c : environments) { envKeys.add(c.name); envLabels.add(c.label); }
        final int[] hostSel = {0};
        final int[] envSel = {0};

        EditText label = new EditText(mActivity);
        label.setSingleLine(true);
        label.setText(hosts.get(0).label + " — Claude");
        EditText folder = new EditText(mActivity);
        folder.setSingleLine(true);
        folder.setText(TerminowMcpTargets.getGuestHome(environments.get(0), "root"));

        TextView hostValue = text(hostLabels[0], 14, R.color.terminow_text);
        hostValue.setPadding(0, dp(4), 0, dp(8));
        hostValue.setOnClickListener(v -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_share_host)
            .setItems(hostLabels, (d, which) -> { hostSel[0] = which; hostValue.setText(hostLabels[which]); }).show());

        TextView envValue = text(envLabels.get(0), 14, R.color.terminow_text);
        envValue.setPadding(0, dp(4), 0, dp(8));
        envValue.setOnClickListener(v -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_share_runs_in)
            .setItems(envLabels.toArray(new String[0]), (d, which) -> {
                envSel[0] = which;
                envValue.setText(envLabels.get(which));
                folder.setText(TerminowMcpTargets.getGuestHome(environments.get(which), "root"));
            }).show());

        LinearLayout box = dialogBox();
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_label), 13, R.color.terminow_text_secondary));
        box.addView(label);
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_host), 13, R.color.terminow_text_secondary));
        box.addView(hostValue);
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_runs_in), 13, R.color.terminow_text_secondary));
        box.addView(envValue);
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_folder), 13, R.color.terminow_text_secondary));
        box.addView(folder);
        final int[] modeSel = {0};
        final String[] modeLabels = {
            mActivity.getString(R.string.terminow_mcp_bridge_mode_a),
            mActivity.getString(R.string.terminow_mcp_bridge_mode_b),
        };
        TextView modeValue = text(modeLabels[0], 14, R.color.terminow_text);
        modeValue.setPadding(0, dp(4), 0, dp(8));
        TextView modeHint = text(mActivity.getString(R.string.terminow_mcp_bridge_ssh_hint), 12, R.color.terminow_text_secondary);
        modeValue.setOnClickListener(v -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_share_mode)
            .setItems(modeLabels, (d, which) -> {
                modeSel[0] = which;
                modeValue.setText(modeLabels[which]);
                modeHint.setText(mActivity.getString(which == 0
                    ? R.string.terminow_mcp_bridge_ssh_hint : R.string.terminow_mcp_bridge_ssh_hint_b));
            }).show());
        box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_share_mode), 13, R.color.terminow_text_secondary));
        box.addView(modeValue);
        box.addView(modeHint);

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_share_ssh_title)
            .setView(box)
            .setPositiveButton(R.string.terminow_mcp_bridge_share, (d, w) -> {
                TerminowStore.Host host = hosts.get(hostSel[0]);
                int e = envSel[0];
                String id = "session-" + UUID.randomUUID();
                String lbl = label.getText().toString().trim().isEmpty() ? host.label : label.getText().toString().trim();
                String mode = modeSel[0] == 0 ? TerminowMcpBridge.MODE_LOCAL_TOOLS : TerminowMcpBridge.MODE_REMOTE_HOST;
                TerminowMcpBridge.Share share = TerminowMcpBridgeManager.sshHostShare(id, lbl,
                    envKeys.get(e), "root", folder.getText().toString().trim(),
                    host.uuid, hostIdentity(host), mode);
                try {
                    mManager.putShare(share);
                } catch (IllegalStateException ex) {
                    mActivity.showToast(ex.getMessage(), true);
                }
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @NonNull
    private String hostDisplay(@NonNull TerminowStore.Host h) {
        return h.label.isEmpty() ? hostIdentity(h) : h.label + " · " + hostIdentity(h);
    }

    @NonNull
    private String hostIdentity(@NonNull TerminowStore.Host h) {
        String u = h.username.isEmpty() ? "" : h.username + "@";
        return u + h.address + (h.port == 22 ? "" : ":" + h.port);
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
        if (share.isSshHost())
            box.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_preflight), false, () -> runPreflight(share, false)));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(share.label)
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                List<String> caps = new ArrayList<>();
                if (submit.isChecked()) caps.add(TerminowMcpBridge.CAP_SUBMIT);
                if (read.isChecked()) caps.add(TerminowMcpBridge.CAP_READ);
                if (cancel.isChecked()) caps.add(TerminowMcpBridge.CAP_CANCEL);
                try {
                    // Preserve the SSH-host fields (type, mode, host) when only the capabilities change.
                    mManager.putShare(new TerminowMcpBridge.Share(share.id, share.label, share.managed,
                        share.environmentKey, share.user, share.folder, caps,
                        share.shareType, share.executorMode, share.hostUuid, share.hostIdentity));
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

    /** Run the Mode-B host readiness check off the main thread, then show the result. */
    private void runPreflight(@NonNull TerminowMcpBridge.Share share, boolean trustHostKey) {
        mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_preflight_running), false);
        new Thread(() -> {
            TerminowMcpPreflight.Result result = mManager.remotePreflight(share, trustHostKey);
            mActivity.runOnUiThread(() -> showPreflightResult(share, result));
        }, "mcp-preflight").start();
    }

    private void showPreflightResult(@NonNull TerminowMcpBridge.Share share, @NonNull TerminowMcpPreflight.Result r) {
        LinearLayout box = dialogBox();
        if (!r.error.isEmpty()) {
            box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_preflight_error, r.error), 14, R.color.terminow_mcp_warn));
        } else if (!r.hostKeyTrusted) {
            box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_preflight_hostkey), 14, R.color.terminow_mcp_warn));
            box.addView(text(r.hostKeyInfo, 12, R.color.terminow_text_secondary));
        } else {
            box.addView(preflightRow(R.string.terminow_mcp_bridge_preflight_connected, r.connected));
            box.addView(preflightRow(R.string.terminow_mcp_bridge_preflight_shell, r.shellOk));
            box.addView(preflightRow(R.string.terminow_mcp_bridge_preflight_claude, r.claudeInstalled));
            box.addView(preflightRow(R.string.terminow_mcp_bridge_preflight_folder, r.folderExists));
            if (!r.os.isEmpty()) box.addView(text("OS · " + r.os, 12, R.color.terminow_text_secondary));
            if (!r.claudeInstalled)
                box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_preflight_install), 12, R.color.terminow_text_secondary));
            if (r.ready())
                box.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_preflight_ready), 13, R.color.terminow_quick_ready));
        }
        AlertDialog.Builder b = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_preflight_title)
            .setView(wrapScroll(box))
            .setPositiveButton(android.R.string.ok, null);
        if (!r.hostKeyTrusted && r.error.isEmpty())
            b.setNeutralButton(R.string.terminow_mcp_bridge_preflight_trust, (d, w) -> runPreflight(share, true));
        b.show();
    }

    @NonNull
    private TextView preflightRow(int labelRes, boolean ok) {
        return text((ok ? "✓ " : "✗ ") + mActivity.getString(labelRes), 14,
            ok ? R.color.terminow_quick_ready : R.color.terminow_mcp_warn);
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
            addTaskCard(mContent, task, true);
        }
    }

    /** One task's card, shared by the global Tasks list and a session's own task list. */
    private void addTaskCard(@NonNull LinearLayout parent, @NonNull TerminowMcpBridgeTask task, boolean showSession) {
        LinearLayout card = card(parent);
        TextView state = text(task.state().name().toLowerCase(java.util.Locale.ROOT), 12, stateColor(task.state()));
        state.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(state);
        card.addView(text(oneLine(task.request), 14, R.color.terminow_text));
        if (showSession)
            card.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_task_session, taskTarget(task)),
                12, R.color.terminow_text_secondary));
        String meta = taskMeta(task);
        if (!meta.isEmpty()) card.addView(text(meta, 12, R.color.terminow_text_secondary));
        if (!task.parentId().isEmpty())
            card.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_task_retry_of), 12, R.color.terminow_text_secondary));
        if (task.state() == TerminowMcpBridgeTask.State.WAITING_FOR_USER && !task.pendingAction().isEmpty())
            card.addView(text(mActivity.getString(R.string.terminow_mcp_bridge_task_pending, task.pendingAction()), 12, R.color.terminow_mcp_warn));
        card.setOnClickListener(v -> taskDialog(task));
    }

    /** The tasks of one shared session, newest first (mManager.tasks() is already newest first). */
    @NonNull
    private List<TerminowMcpBridgeTask> sessionTasks(@NonNull String sessionId) {
        List<TerminowMcpBridgeTask> out = new ArrayList<>();
        for (TerminowMcpBridgeTask t : mManager.tasks()) if (t.sessionId.equals(sessionId)) out.add(t);
        return out;
    }

    /** A "Tasks (N)" button plus a running/queued indicator, shown below a shared-session card. */
    private void addSessionTasksButton(@NonNull LinearLayout card, @NonNull TerminowMcpBridge.Share share) {
        List<TerminowMcpBridgeTask> tasks = sessionTasks(share.id);
        LinearLayout line = row();
        line.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_session_tasks, tasks.size()),
            false, () -> sessionTasksDialog(share)));
        int running = 0, queued = 0;
        for (TerminowMcpBridgeTask t : tasks) {
            if (t.state() == TerminowMcpBridgeTask.State.QUEUED) queued++;
            else if (t.state().isActive()) running++;
        }
        if (running > 0 || queued > 0) {
            TextView ind = text(mActivity.getString(R.string.terminow_mcp_bridge_session_tasks_sub, running, queued),
                12, R.color.terminow_panel_accent);
            ind.setPadding(dp(8), 0, 0, 0);
            line.addView(ind);
        }
        card.addView(line, matchWrap());
    }

    /** A readable target from the task's own bound metadata, so it survives the session being unshared. */
    @NonNull
    private String taskTarget(@NonNull TerminowMcpBridgeTask task) {
        String label = sessionLabel(task.sessionId);
        String env = task.environmentKey.isEmpty() ? ""
            : task.environmentKey + (task.folder.isEmpty() ? "" : " · " + task.folder);
        boolean shared = !label.equals(task.sessionId); // sessionLabel falls back to the id when unshared
        if (shared) return env.isEmpty() ? label : label + " (" + env + ")";
        return env.isEmpty() ? label : env; // unshared: show the bound environment/folder, not a bare id
    }

    /** A short meta line: when the task was submitted and, if it ran, how long it took. */
    @NonNull
    private String taskMeta(@NonNull TerminowMcpBridgeTask task) {
        String when = task.createdAt > 0 ? DateUtils.getRelativeTimeSpanString(task.createdAt).toString() : "";
        long dur = task.durationMillis(System.currentTimeMillis());
        if (dur > 0) return (when.isEmpty() ? "" : when + " · ") + formatDuration(dur);
        return when;
    }

    @NonNull
    private String formatDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60; s %= 60;
        if (m < 60) return m + "m " + s + "s";
        long h = m / 60; m %= 60;
        return h + "h " + m + "m";
    }

    private boolean matchesFilter(@NonNull TerminowMcpBridgeTask task, int filter) {
        TerminowMcpBridgeTask.State s = task.state();
        switch (filter) {
            case 1: return s.isActive();
            case 2: return s == TerminowMcpBridgeTask.State.COMPLETED;
            case 3: return s == TerminowMcpBridgeTask.State.FAILED
                || s == TerminowMcpBridgeTask.State.INTERRUPTED || s == TerminowMcpBridgeTask.State.CANCELLED;
            default: return true;
        }
    }

    /** One session's task history: filter by status and page through it, newest first. */
    private void sessionTasksDialog(@NonNull TerminowMcpBridge.Share share) {
        final List<TerminowMcpBridgeTask> all = sessionTasks(share.id);
        final int[] filter = {0};
        final int[] limit = {SESSION_TASKS_PAGE};
        final String[] names = {
            mActivity.getString(R.string.terminow_mcp_bridge_filter_all),
            mActivity.getString(R.string.terminow_mcp_bridge_filter_active),
            mActivity.getString(R.string.terminow_mcp_bridge_filter_completed),
            mActivity.getString(R.string.terminow_mcp_bridge_filter_failed),
        };
        LinearLayout box = dialogBox();
        final TextView filterLabel = text("", 13, R.color.terminow_panel_accent);
        filterLabel.setPadding(0, dp(4), 0, dp(8));
        box.addView(filterLabel);
        final LinearLayout list = column();
        box.addView(list);
        final Runnable[] repopulate = new Runnable[1];
        repopulate[0] = () -> {
            filterLabel.setText(mActivity.getString(R.string.terminow_mcp_bridge_tasks_filter, names[filter[0]]));
            list.removeAllViews();
            int shown = 0, matched = 0;
            for (TerminowMcpBridgeTask t : all) {
                if (!matchesFilter(t, filter[0])) continue;
                matched++;
                if (shown >= limit[0]) continue;
                addTaskCard(list, t, false);
                shown++;
            }
            if (matched == 0) list.addView(note(mActivity.getString(R.string.terminow_mcp_bridge_no_session_tasks)));
            else if (matched > shown)
                list.addView(button(mActivity.getString(R.string.terminow_mcp_bridge_show_more), false, () -> {
                    limit[0] += SESSION_TASKS_PAGE;
                    repopulate[0].run();
                }));
        };
        filterLabel.setOnClickListener(v -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_mcp_bridge_tasks_filter_title)
            .setItems(names, (d, which) -> { filter[0] = which; limit[0] = SESSION_TASKS_PAGE; repopulate[0].run(); })
            .show());
        repopulate[0].run();
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_mcp_bridge_session_tasks_title, share.label))
            .setView(wrapScroll(box))
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void retry(@NonNull TerminowMcpBridgeTask task) {
        try {
            mManager.retryTask(task.id);
            mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_task_retried), false);
            render();
        } catch (TerminowAiError.Exception e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_mcp_bridge_task_retry_failed, e.getMessage()), true);
        }
    }

    /** The shared session a task belongs to, by its id. Falls back to the id if it was unshared. */
    @NonNull
    private String sessionLabel(@NonNull String sessionId) {
        for (TerminowMcpBridge.Share share : mManager.listShares())
            if (share.id.equals(sessionId)) return share.label;
        return sessionId;
    }

    private void taskDialog(@NonNull TerminowMcpBridgeTask task) {
        LinearLayout box = dialogBox();
        addField(box, R.string.terminow_mcp_bridge_task_state, task.state().name().toLowerCase(java.util.Locale.ROOT));
        addField(box, R.string.terminow_mcp_bridge_task_request, task.request);
        addField(box, R.string.terminow_mcp_bridge_task_target, taskTarget(task));
        if (task.startedAt() > 0)
            addField(box, R.string.terminow_mcp_bridge_task_duration, formatDuration(task.durationMillis(System.currentTimeMillis())));
        if (!task.parentId().isEmpty())
            addField(box, R.string.terminow_mcp_bridge_task_lineage, mActivity.getString(R.string.terminow_mcp_bridge_task_retry_of));
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
        else
            builder.setPositiveButton(R.string.terminow_mcp_bridge_task_retry, (d, w) -> retry(task));
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
