package com.termux.app.terminow;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cloud side of Quick Import: authenticate to AWS EC2 or DigitalOcean with the person's own
 * credentials, list instances (with pagination, cancel and clear permission errors), and feed the
 * chosen ones into the same preview/import/Undo pipeline as the file sources. Credentials are used
 * for the one fetch and kept only in memory; nothing is started, stopped or changed in the cloud,
 * and no host is connected to automatically.
 */
public final class TerminowCloudImportUi {

    public enum Provider {AWS, DIGITALOCEAN}

    private final TermuxActivity mActivity;
    private final TerminowQuickImportUi.Delegate mDelegate;

    public TerminowCloudImportUi(@NonNull TermuxActivity activity, @NonNull TerminowQuickImportUi.Delegate delegate) {
        this.mActivity = activity;
        this.mDelegate = delegate;
    }

    public void start(@NonNull Provider provider) {
        if (provider == Provider.AWS) showAwsForm();
        else showDigitalOceanForm();
    }

    // --- DigitalOcean --------------------------------------------------------------------------

    private void showDigitalOceanForm() {
        LinearLayout form = form();
        EditText token = field(form, R.string.terminow_cloud_do_token, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CloudOptions options = optionFields(form);

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_cloud_do_title)
            .setView(scroll(form))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_cloud_fetch, (d, w) -> {
                String tokenText = token.getText().toString().trim();
                if (tokenText.isEmpty()) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_cloud_need_token), true);
                    return;
                }
                fetch("DigitalOcean", options.read(), cancel ->
                    TerminowDigitalOceanClient.fetchDroplets(tokenText, cancel));
            })
            .show();
    }

    // --- AWS -----------------------------------------------------------------------------------

    private void showAwsForm() {
        LinearLayout form = form();
        TextView help = new TextView(mActivity);
        help.setText(R.string.terminow_cloud_aws_help);
        help.setTextSize(12);
        help.setPadding(0, 0, 0, dp(8));
        form.addView(help);

        EditText accessKey = field(form, R.string.terminow_cloud_aws_access_key, InputType.TYPE_CLASS_TEXT);
        EditText secretKey = field(form, R.string.terminow_cloud_aws_secret_key, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText sessionToken = field(form, R.string.terminow_cloud_aws_session_token, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText regions = field(form, R.string.terminow_cloud_aws_regions, InputType.TYPE_CLASS_TEXT);
        regions.setText("us-east-1");
        CloudOptions options = optionFields(form);

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_cloud_aws_title)
            .setView(scroll(form))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_cloud_fetch, (d, w) -> {
                String access = accessKey.getText().toString().trim();
                String secret = secretKey.getText().toString().trim();
                String session = sessionToken.getText().toString().trim();
                List<String> regionList = new ArrayList<>();
                for (String r : regions.getText().toString().split(",")) if (!r.trim().isEmpty()) regionList.add(r.trim());
                if (access.isEmpty() || secret.isEmpty() || regionList.isEmpty()) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_cloud_need_aws), true);
                    return;
                }
                fetch("AWS", options.read(), cancel -> {
                    List<TerminowCloudInstance> all = new ArrayList<>();
                    for (String region : regionList) {
                        if (cancel.get()) break;
                        all.addAll(TerminowEc2Client.describeInstances(access, secret, session, region, cancel));
                    }
                    return all;
                });
            })
            .show();
    }

    // --- Fetch + map ---------------------------------------------------------------------------

    /** A provider call that lists instances, given a cancel flag. */
    private interface Fetcher {
        @NonNull List<TerminowCloudInstance> fetch(@NonNull AtomicBoolean cancel) throws Exception;
    }

    private void fetch(@NonNull String sourceLabel, @NonNull CloudOptions.Values options, @NonNull Fetcher fetcher) {
        AtomicBoolean cancel = new AtomicBoolean(false);

        LinearLayout progressView = new LinearLayout(mActivity);
        progressView.setOrientation(LinearLayout.HORIZONTAL);
        progressView.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(20);
        progressView.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(mActivity);
        progressView.addView(bar);
        TextView label = new TextView(mActivity);
        label.setText(R.string.terminow_cloud_fetching);
        label.setPadding(dp(16), 0, 0, 0);
        progressView.addView(label);

        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setView(progressView)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, (d, w) -> cancel.set(true))
            .create();
        progress.show();

        new Thread(() -> {
            List<TerminowImportConnection> connections = null;
            String error = null;
            try {
                List<TerminowCloudInstance> instances = fetcher.fetch(cancel);
                connections = map(sourceLabel, instances, options);
            } catch (Exception e) {
                error = String.valueOf(e.getMessage());
            }
            List<TerminowImportConnection> finalConnections = connections;
            String finalError = error;
            mActivity.runOnUiThread(() -> {
                progress.dismiss();
                if (cancel.get()) return;
                if (finalConnections != null)
                    new TerminowQuickImportUi(mActivity, mDelegate).previewConnections(sourceLabel, finalConnections);
                else mActivity.showToast(mActivity.getString(R.string.terminow_import_failed, finalError), true);
            });
        }, "TerminowCloudImport").start();
    }

    @NonNull
    private List<TerminowImportConnection> map(@NonNull String source, @NonNull List<TerminowCloudInstance> instances,
                                               @NonNull CloudOptions.Values options) {
        List<TerminowImportConnection> result = new ArrayList<>();
        for (TerminowCloudInstance instance : instances) {
            TerminowImportConnection c = new TerminowImportConnection(source);
            c.name = instance.name.isEmpty() ? instance.id : instance.name;
            c.host = instance.address(options.preferPublic);
            c.username = options.username;
            c.port = options.port;
            c.protocol = TerminowImportConnection.Protocol.SSH;
            c.resourceId = instance.id;
            c.region = instance.region;
            if (!options.keyName.isEmpty()) {
                c.credential = TerminowImportConnection.Credential.KEY_REFERENCE;
                c.keyReference = options.keyName;
            }
            StringBuilder note = new StringBuilder();
            if (!instance.region.isEmpty()) note.append(instance.region);
            if (!instance.state.isEmpty()) note.append(note.length() > 0 ? " · " : "").append(instance.state);
            if (!instance.tags.isEmpty()) note.append(note.length() > 0 ? " · " : "").append(android.text.TextUtils.join(",", instance.tags));
            c.note = note.toString();
            result.add(c);
        }
        return result;
    }

    // --- Shared option fields ------------------------------------------------------------------

    private static final class CloudOptions {
        final CheckBox publicIp;
        final EditText username;
        final EditText port;
        final EditText keyName;

        CloudOptions(CheckBox publicIp, EditText username, EditText port, EditText keyName) {
            this.publicIp = publicIp;
            this.username = username;
            this.port = port;
            this.keyName = keyName;
        }

        Values read() {
            Values v = new Values();
            v.preferPublic = publicIp.isChecked();
            v.username = username.getText().toString().trim();
            v.keyName = keyName.getText().toString().trim();
            try {
                int p = Integer.parseInt(port.getText().toString().trim());
                if (p >= 1 && p <= 65535) v.port = p;
            } catch (NumberFormatException ignored) {
                // Keep the default SSH port.
            }
            return v;
        }

        static final class Values {
            boolean preferPublic = true;
            String username = "";
            int port = 22;
            String keyName = "";
        }
    }

    @NonNull
    private CloudOptions optionFields(@NonNull LinearLayout form) {
        CheckBox publicIp = new CheckBox(mActivity);
        publicIp.setText(R.string.terminow_cloud_use_public);
        publicIp.setChecked(true);
        form.addView(publicIp);
        EditText username = field(form, R.string.terminow_cloud_username, InputType.TYPE_CLASS_TEXT);
        EditText port = field(form, R.string.terminow_cloud_port, InputType.TYPE_CLASS_NUMBER);
        port.setText("22");
        EditText keyName = field(form, R.string.terminow_cloud_key, InputType.TYPE_CLASS_TEXT);
        return new CloudOptions(publicIp, username, port, keyName);
    }

    // --- View helpers --------------------------------------------------------------------------

    @NonNull
    private LinearLayout form() {
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);
        return form;
    }

    @NonNull
    private android.widget.ScrollView scroll(@NonNull LinearLayout form) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(mActivity);
        scroll.addView(form);
        return scroll;
    }

    @NonNull
    private EditText field(@NonNull LinearLayout form, int hintRes, int inputType) {
        EditText edit = new EditText(mActivity);
        edit.setHint(hintRes);
        edit.setInputType(inputType);
        edit.setSingleLine(true);
        form.addView(edit);
        return edit;
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
