package com.termux.app.terminow;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.files.TerminowSftpFs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Docker management over a saved SSH host: it runs docker commands on the host's own SSH connection
 * (reusing its credentials and host-key verification via {@link TerminowRemoteTools}), lists the
 * containers with their real status, and starts/stops/restarts them and shows their logs. Nothing is
 * faked: the list is always the live {@code docker ps}. It never opens a Docker TCP socket or changes
 * the host. An interactive shell ({@code docker exec}) needs a PTY, so it opens the host's SSH terminal.
 */
final class TerminowDockerUi {

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    /** Opens an SSH terminal to the host (for docker exec, which needs a PTY). */
    @Nullable private final Consumer<TerminowStore.Host> mOpenTerminal;
    private final TerminowRemoteTools mRemote;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    TerminowDockerUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store,
                     @Nullable Consumer<TerminowStore.Host> openTerminal) {
        mActivity = activity;
        mStore = store;
        mOpenTerminal = openTerminal;
        mRemote = new TerminowRemoteTools(activity, store);
    }

    /** Entry from the Docker card: pick an SSH host to manage Docker on, or add one. */
    void openCard(@NonNull Runnable onAddHost) {
        List<TerminowStore.Host> hosts = sshHosts();
        if (hosts.isEmpty()) {
            onAddHost.run();
            return;
        }
        CharSequence[] items = new CharSequence[hosts.size() + 1];
        for (int i = 0; i < hosts.size(); i++) items[i] = hosts.get(i).getTitle();
        items[hosts.size()] = mActivity.getString(R.string.terminow_docker_add);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_lab_docker)
            .setItems(items, (d, which) -> {
                if (which < hosts.size()) openView(hosts.get(which));
                else onAddHost.run();
            })
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .show();
    }

    @NonNull
    private List<TerminowStore.Host> sshHosts() {
        List<TerminowStore.Host> out = new java.util.ArrayList<>();
        for (TerminowStore.Host h : mStore.getHosts()) if (h.isTerminal() && !h.telnet) out.add(h);
        return out;
    }

    void openView(@NonNull TerminowStore.Host host) {
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

        AlertDialog.Builder b = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(host.getTitle())
            .setView(root)
            .setNegativeButton(R.string.terminow_close_dialog, null);
        if (mOpenTerminal != null) b.setNeutralButton(R.string.terminow_docker_terminal, (d, w) -> mOpenTerminal.accept(host));
        b.show();
        loadView(host, list, status);
    }

    private void loadView(@NonNull TerminowStore.Host host, @NonNull LinearLayout list, @NonNull TextView status) {
        status.setVisibility(android.view.View.VISIBLE);
        status.setText(mActivity.getString(R.string.terminow_docker_connecting));
        list.removeAllViews();
        mExecutor.execute(() -> {
            String error = null;
            List<TerminowDocker.Container> containers = null;
            String compose = "";
            try {
                TerminowSftpFs fs = mRemote.connectionFor(host);
                int[] exit = new int[1];
                String version = run(fs, TerminowDocker.versionCommand(), exit).trim();
                if (exit[0] != 0 || version.isEmpty()) {
                    error = mActivity.getString(R.string.terminow_docker_no_access);
                } else {
                    containers = TerminowDocker.parsePs(run(fs, TerminowDocker.psCommand(true), null));
                    compose = run(fs, TerminowDocker.composeLsCommand(), null).trim();
                }
            } catch (IOException e) {
                error = mActivity.getString(R.string.terminow_docker_unreachable);
            }
            final String ferror = error;
            final List<TerminowDocker.Container> fc = containers;
            final boolean hasCompose = !compose.isEmpty() && !compose.toLowerCase(java.util.Locale.ROOT).startsWith("name");
            mHandler.post(() -> {
                if (ferror != null) {
                    status.setText(ferror);
                    status.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_mcp_warn));
                    return;
                }
                renderContainers(host, fc, hasCompose, list, status);
            });
        });
    }

    private void renderContainers(@NonNull TerminowStore.Host host, @Nullable List<TerminowDocker.Container> containers,
                                  boolean hasCompose, @NonNull LinearLayout list, @NonNull TextView status) {
        list.removeAllViews();
        if (containers == null || containers.isEmpty()) {
            status.setText(mActivity.getString(R.string.terminow_docker_empty));
            status.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            return;
        }
        status.setVisibility(android.view.View.GONE);
        if (hasCompose) {
            TextView c = new TextView(mActivity);
            c.setText(mActivity.getString(R.string.terminow_docker_has_compose));
            c.setTextSize(12);
            c.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            c.setPadding(dp(6), dp(2), dp(6), dp(8));
            list.addView(c);
        }
        for (TerminowDocker.Container ct : containers) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(6), dp(12), dp(6), dp(12));
            row.setClickable(true);
            android.util.TypedValue tv = new android.util.TypedValue();
            mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            row.setBackgroundResource(tv.resourceId);
            final TerminowDocker.Container container = ct;
            row.setOnClickListener(v -> showActions(host, container, list, status));
            TextView name = new TextView(mActivity);
            name.setText(ct.name);
            name.setTextSize(16);
            name.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name);
            TextView sub = new TextView(mActivity);
            sub.setText(ct.image + "  ·  " + ct.status);
            sub.setTextSize(12);
            sub.setTextColor(ContextCompat.getColor(mActivity,
                ct.running() ? R.color.terminow_quick_ready : R.color.terminow_text_secondary));
            row.addView(sub);
            list.addView(row);
        }
    }

    private void showActions(@NonNull TerminowStore.Host host, @NonNull TerminowDocker.Container ct,
                             @NonNull LinearLayout list, @NonNull TextView status) {
        boolean running = ct.running();
        java.util.List<String> labels = new java.util.ArrayList<>();
        java.util.List<String> actions = new java.util.ArrayList<>();
        if (!running) {
            labels.add(mActivity.getString(R.string.terminow_proxmox_start)); actions.add("start");
        } else {
            labels.add(mActivity.getString(R.string.terminow_docker_restart)); actions.add("restart");
            labels.add(mActivity.getString(R.string.terminow_docker_stop)); actions.add("stop");
        }
        labels.add(mActivity.getString(R.string.terminow_docker_logs)); actions.add("logs");
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(ct.name)
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> {
                String action = actions.get(which);
                if (action.equals("logs")) {
                    showLogs(host, ct);
                } else if (action.equals("start")) {
                    doAction(host, ct, action, list, status);
                } else {
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(labels.get(which))
                        .setMessage(mActivity.getString(R.string.terminow_proxmox_confirm, labels.get(which), ct.name))
                        .setPositiveButton(labels.get(which), (d2, w2) -> doAction(host, ct, action, list, status))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                }
            })
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .show();
    }

    private void doAction(@NonNull TerminowStore.Host host, @NonNull TerminowDocker.Container ct,
                          @NonNull String action, @NonNull LinearLayout list, @NonNull TextView status) {
        mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_working), false);
        String ref = ct.id.isEmpty() ? ct.name : ct.id;
        mExecutor.execute(() -> {
            String err = null;
            try {
                int[] exit = new int[1];
                String out = run(mRemote.connectionFor(host), TerminowDocker.actionCommand(action, ref), exit);
                if (exit[0] != 0) err = out.trim();
            } catch (IOException e) {
                err = mActivity.getString(R.string.terminow_docker_unreachable);
            }
            final String ferr = err;
            mHandler.post(() -> {
                if (ferr != null && !ferr.isEmpty())
                    mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_action_failed, ferr), true);
                loadView(host, list, status);
            });
        });
    }

    private void showLogs(@NonNull TerminowStore.Host host, @NonNull TerminowDocker.Container ct) {
        String ref = ct.id.isEmpty() ? ct.name : ct.id;
        mActivity.showToast(mActivity.getString(R.string.terminow_proxmox_working), false);
        mExecutor.execute(() -> {
            String logs;
            try {
                logs = run(mRemote.connectionFor(host), TerminowDocker.logsCommand(ref, 400), null);
            } catch (IOException e) {
                logs = mActivity.getString(R.string.terminow_docker_unreachable);
            }
            final String flogs = logs.isEmpty() ? "—" : logs;
            mHandler.post(() -> {
                TextView body = new TextView(mActivity);
                body.setText(flogs);
                body.setTypeface(Typeface.MONOSPACE);
                body.setTextSize(12);
                body.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
                body.setPadding(dp(14), dp(10), dp(14), dp(10));
                body.setTextIsSelectable(true);
                ScrollView s = new ScrollView(mActivity);
                s.addView(body);
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setTitle(ct.name)
                    .setView(s)
                    .setNegativeButton(R.string.terminow_close_dialog, null)
                    .show();
            });
        });
    }

    /** Run a command on the host over SSH and return its stdout (or stderr if stdout is empty). */
    @NonNull
    private String run(@NonNull TerminowSftpFs fs, @NonNull String command, @Nullable int[] exitOut) throws IOException {
        Process p = fs.exec(command);
        String out = readAll(p.getInputStream());
        String err = readAll(p.getErrorStream());
        try {
            int e = p.waitFor();
            if (exitOut != null) exitOut[0] = e;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        return !out.isEmpty() ? out : err;
    }

    @NonNull
    private static String readAll(@NonNull InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}
