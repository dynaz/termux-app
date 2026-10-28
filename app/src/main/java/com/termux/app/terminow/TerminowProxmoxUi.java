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
            });
        });
        dialog.show();
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
