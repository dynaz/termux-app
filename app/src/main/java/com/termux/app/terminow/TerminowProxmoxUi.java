package com.termux.app.terminow;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Add/Edit form for a Proxmox VE connection: name, host, port, API token id and secret. Test
 * connection runs {@link TerminowProxmox#testConnection} off the main thread; a self-signed certificate
 * is only accepted after the user confirms its fingerprint, which is then pinned. The token secret is
 * stored encrypted by {@link TerminowStore} and never shown back or logged.
 */
final class TerminowProxmoxUi {

    interface OnSaved {
        void onSaved(@NonNull TerminowProxmox.Connection connection);
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    TerminowProxmoxUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store) {
        mActivity = activity;
        mStore = store;
    }

    void open(@Nullable TerminowProxmox.Connection existing, @Nullable OnSaved onSaved) {
        final TerminowProxmox.Connection conn = new TerminowProxmox.Connection();
        if (existing != null) {
            conn.id = existing.id;
            conn.name = existing.name;
            conn.host = existing.host;
            conn.port = existing.port;
            conn.tokenId = existing.tokenId;
            conn.tokenSecret = existing.tokenSecret;
            conn.fingerprint = existing.fingerprint;
        }

        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);

        EditText name = field(form, R.string.terminow_proxmox_name, InputType.TYPE_CLASS_TEXT, conn.name);
        EditText host = field(form, R.string.terminow_proxmox_host, InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_CLASS_TEXT, conn.host);
        EditText port = field(form, R.string.terminow_proxmox_port, InputType.TYPE_CLASS_NUMBER,
            String.valueOf(conn.port > 0 ? conn.port : TerminowProxmox.DEFAULT_PORT));
        EditText tokenId = field(form, R.string.terminow_proxmox_token_id, InputType.TYPE_CLASS_TEXT, conn.tokenId);
        EditText tokenSecret = field(form, R.string.terminow_proxmox_token_secret,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, conn.tokenSecret);

        TextView status = new TextView(mActivity);
        status.setTextSize(13);
        status.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        status.setPadding(0, dp(10), 0, dp(4));
        form.addView(status);

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(form);

        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_proxmox_title)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_proxmox_save, null)
            .setNeutralButton(R.string.terminow_proxmox_test, null)
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (!collect(conn, name, host, port, tokenId, tokenSecret, status)) return;
                runTest(conn, status);
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (!collect(conn, name, host, port, tokenId, tokenSecret, status)) return;
                mStore.saveProxmoxConnection(conn);
                mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_saved, conn.name), false);
                dialog.dismiss();
                if (onSaved != null) onSaved.onSaved(conn);
                else openView(conn);   // configure -> test -> save -> view the nodes/guests
            });
        });
        dialog.show();
    }

    /** Entry from the Proxmox card: pick a saved connection to view, or add a new one. */
    void openCard() {
        java.util.List<TerminowProxmox.Connection> saved = mStore.getProxmoxConnections();
        if (saved.isEmpty()) {
            open(null, null);
            return;
        }
        CharSequence[] items = new CharSequence[saved.size() + 1];
        for (int i = 0; i < saved.size(); i++) {
            TerminowProxmox.Connection c = saved.get(i);
            items[i] = c.name.isEmpty() ? c.host : c.name;
        }
        items[saved.size()] = mActivity.getString(R.string.terminow_proxmox_add);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_proxmox_title)
            .setItems(items, (d, which) -> {
                if (which < saved.size()) openView(saved.get(which));
                else open(null, null);
            })
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .show();
    }

    /** Show the nodes and their VMs/LXC with status, loaded live from the server — never placeholder data. */
    void openView(@NonNull TerminowProxmox.Connection conn) {
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(8), dp(16), 0);
        TextView status = new TextView(mActivity);
        status.setTextSize(14);
        status.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        status.setPadding(0, dp(6), 0, dp(10));
        root.addView(status);
        LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(conn.name.isEmpty() ? conn.host : conn.name)
            .setView(root)
            .setNeutralButton(R.string.terminow_proxmox_web_ui, (d, w) -> openWeb(conn))
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .show();
        loadView(conn, list, status);
    }

    private void loadView(@NonNull TerminowProxmox.Connection conn, @NonNull LinearLayout list, @NonNull TextView status) {
        status.setVisibility(android.view.View.VISIBLE);
        status.setText(mActivity.getString(R.string.terminow_proxmox_loading));
        list.removeAllViews();
        mExecutor.execute(() -> {
            TerminowProxmox.Result nr = TerminowProxmox.get(conn, "/nodes");
            if (nr.status != TerminowProxmox.Status.OK || nr.body == null) {
                mHandler.post(() -> setStatus(status, loadError(nr), true));
                return;
            }
            java.util.List<TerminowProxmox.PveNode> nodes = TerminowProxmox.parseNodes(nr.body);
            java.util.List<TerminowProxmox.PveGuest> guests = new java.util.ArrayList<>();
            for (TerminowProxmox.PveNode n : nodes) {
                TerminowProxmox.Result q = TerminowProxmox.get(conn, "/nodes/" + n.name + "/qemu");
                if (q.status == TerminowProxmox.Status.OK && q.body != null)
                    guests.addAll(TerminowProxmox.parseGuests(q.body, "qemu", n.name));
                TerminowProxmox.Result l = TerminowProxmox.get(conn, "/nodes/" + n.name + "/lxc");
                if (l.status == TerminowProxmox.Status.OK && l.body != null)
                    guests.addAll(TerminowProxmox.parseGuests(l.body, "lxc", n.name));
            }
            mHandler.post(() -> renderGuests(conn, guests, list, status));
        });
    }

    private void renderGuests(@NonNull TerminowProxmox.Connection conn, @NonNull java.util.List<TerminowProxmox.PveGuest> guests,
                              @NonNull LinearLayout list, @NonNull TextView status) {
        list.removeAllViews();
        if (guests.isEmpty()) {
            setStatus(status, mActivity.getString(R.string.terminow_proxmox_empty), false);
            return;
        }
        status.setVisibility(android.view.View.GONE);
        for (TerminowProxmox.PveGuest g : guests) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(6), dp(12), dp(6), dp(12));
            row.setClickable(true);
            android.util.TypedValue tv = new android.util.TypedValue();
            mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            final TerminowProxmox.PveGuest guest = g;
            row.setOnClickListener(v -> showActions(conn, guest, list, status));
            TextView name = new TextView(mActivity);
            name.setText(g.name);
            name.setTextSize(16);
            name.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            row.addView(name);
            TextView sub = new TextView(mActivity);
            sub.setText(mActivity.getString(R.string.terminow_proxmox_guest_sub, g.vmid,
                g.type.toUpperCase(java.util.Locale.ROOT), g.status));
            sub.setTextSize(12);
            sub.setTextColor(ContextCompat.getColor(mActivity,
                "running".equals(g.status) ? R.color.terminow_quick_ready : R.color.terminow_text_secondary));
            row.addView(sub);
            list.addView(row);
        }
    }

    private void showActions(@NonNull TerminowProxmox.Connection conn, @NonNull TerminowProxmox.PveGuest g,
                             @NonNull LinearLayout list, @NonNull TextView status) {
        boolean running = "running".equals(g.status);
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String> actions = new java.util.ArrayList<>();
        if (!running) {
            labels.add(mActivity.getString(R.string.terminow_proxmox_start)); actions.add("start");
        } else {
            labels.add(mActivity.getString(R.string.terminow_proxmox_shutdown)); actions.add("shutdown");
            labels.add(mActivity.getString(R.string.terminow_proxmox_reboot)); actions.add("reboot");
            labels.add(mActivity.getString(R.string.terminow_proxmox_stop)); actions.add("stop");
        }
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(g.name)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                String action = actions.get(which);
                if (action.equals("start")) {
                    doAction(conn, g, action, list, status);
                } else {
                    // Shutdown / Reboot / Stop change a running guest, so confirm first.
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(labels.get(which))
                        .setMessage(mActivity.getString(R.string.terminow_proxmox_confirm, labels.get(which), g.name))
                        .setPositiveButton(labels.get(which), (d2, w2) -> doAction(conn, g, action, list, status))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                }
            })
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .show();
    }

    private void doAction(@NonNull TerminowProxmox.Connection conn, @NonNull TerminowProxmox.PveGuest g,
                          @NonNull String action, @NonNull LinearLayout list, @NonNull TextView status) {
        mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_working), false);
        mExecutor.execute(() -> {
            TerminowProxmox.Result r = TerminowProxmox.post(conn, TerminowProxmox.guestActionPath(g, action));
            String exit = null;
            if (r.status == TerminowProxmox.Status.OK && r.body != null) {
                String upid = TerminowProxmox.parseUpid(r.body);
                for (int i = 0; i < 30 && !upid.isEmpty(); i++) {
                    TerminowProxmox.Result ts = TerminowProxmox.get(conn, TerminowProxmox.taskStatusPath(g.node, upid));
                    if (ts.status == TerminowProxmox.Status.OK && ts.body != null) {
                        TerminowProxmox.TaskStatus t = TerminowProxmox.parseTaskStatus(ts.body);
                        if (!t.running) { exit = t.exitStatus; break; }
                    }
                    try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }
            final boolean ok = r.status == TerminowProxmox.Status.OK;
            final String fexit = exit;
            mHandler.post(() -> {
                if (ok) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_action_ok,
                        fexit != null && !fexit.isEmpty() ? fexit : "OK"), !"OK".equals(fexit) && fexit != null && !fexit.isEmpty());
                } else {
                    mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_action_failed,
                        r.error != null ? r.error : ""), true);
                }
                loadView(conn, list, status);
            });
        });
    }

    private void openWeb(@NonNull TerminowProxmox.Connection conn) {
        try {
            mActivity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(TerminowProxmox.webUrl(conn))));
        } catch (RuntimeException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_error, "no browser"), true);
        }
    }

    @NonNull
    private String loadError(@NonNull TerminowProxmox.Result r) {
        switch (r.status) {
            case AUTH_FAILED: return mActivity.getString(R.string.terminow_proxmox_auth_failed);
            case UNREACHABLE: return mActivity.getString(R.string.terminow_proxmox_unreachable);
            case CERT_UNTRUSTED:
            case CERT_MISMATCH: return mActivity.getString(R.string.terminow_proxmox_cert_mismatch);
            default: return mActivity.getString(R.string.terminow_proxmox_error, r.error != null ? r.error : "");
        }
    }

    /** Read the fields into the connection; show an error and return false if a required one is missing. */
    private boolean collect(@NonNull TerminowProxmox.Connection conn, EditText name, EditText host, EditText port,
                            EditText tokenId, EditText tokenSecret, @NonNull TextView status) {
        conn.name = name.getText().toString().trim();
        conn.host = host.getText().toString().trim();
        try {
            conn.port = Integer.parseInt(port.getText().toString().trim());
        } catch (NumberFormatException e) {
            conn.port = TerminowProxmox.DEFAULT_PORT;
        }
        conn.tokenId = tokenId.getText().toString().trim();
        conn.tokenSecret = tokenSecret.getText().toString();
        if (conn.name.isEmpty()) conn.name = conn.host;
        if (conn.host.isEmpty() || conn.tokenId.isEmpty() || conn.tokenSecret.isEmpty()) {
            setStatus(status, mActivity.getString(R.string.terminow_proxmox_need_fields), true);
            return false;
        }
        return true;
    }

    private void runTest(@NonNull TerminowProxmox.Connection conn, @NonNull TextView status) {
        setStatus(status, mActivity.getString(R.string.terminow_proxmox_testing), false);
        mExecutor.execute(() -> {
            TerminowProxmox.Result r = TerminowProxmox.testConnection(conn);
            mHandler.post(() -> onTestResult(conn, r, status));
        });
    }

    private void onTestResult(@NonNull TerminowProxmox.Connection conn, @NonNull TerminowProxmox.Result r, @NonNull TextView status) {
        switch (r.status) {
            case OK:
                setStatus(status, mActivity.getString(R.string.terminow_proxmox_ok, r.version != null ? r.version : "?"), false);
                break;
            case AUTH_FAILED:
                setStatus(status, mActivity.getString(R.string.terminow_proxmox_auth_failed), true);
                break;
            case CERT_UNTRUSTED:
                askTrust(conn, r.fingerprint != null ? r.fingerprint : "", status);
                break;
            case CERT_MISMATCH:
                setStatus(status, mActivity.getString(R.string.terminow_proxmox_cert_mismatch), true);
                break;
            case UNREACHABLE:
                setStatus(status, mActivity.getString(R.string.terminow_proxmox_unreachable), true);
                break;
            default:
                setStatus(status, mActivity.getString(R.string.terminow_proxmox_error,
                    r.error != null ? r.error : ""), true);
        }
    }

    /** Show the self-signed certificate's fingerprint and, only on the user's confirmation, pin it and retry. */
    private void askTrust(@NonNull TerminowProxmox.Connection conn, @NonNull String fingerprint, @NonNull TextView status) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_proxmox_cert_title)
            .setMessage(mActivity.getString(R.string.terminow_proxmox_cert_message, fingerprint))
            .setPositiveButton(R.string.terminow_proxmox_cert_trust, (d, w) -> {
                conn.fingerprint = fingerprint;
                runTest(conn, status);
            })
            .setNegativeButton(android.R.string.cancel, (d, w) ->
                setStatus(status, mActivity.getString(R.string.terminow_proxmox_cert_declined), true))
            .show();
    }

    private void setStatus(@NonNull TextView status, @NonNull String text, boolean error) {
        status.setText(text);
        status.setTextColor(ContextCompat.getColor(mActivity, error ? R.color.terminow_mcp_warn : R.color.terminow_quick_ready));
    }

    @NonNull
    private EditText field(@NonNull LinearLayout form, int hintRes, int inputType, @NonNull String value) {
        TextView label = new TextView(mActivity);
        label.setText(hintRes);
        label.setTextSize(13);
        label.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        label.setPadding(0, dp(8), 0, dp(2));
        form.addView(label);
        EditText edit = new EditText(mActivity);
        edit.setInputType(inputType);
        edit.setText(value);
        edit.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
        edit.setGravity(Gravity.CENTER_VERTICAL);
        form.addView(edit, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return edit;
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}
