package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.Collections;
import java.util.List;

/**
 * The DevOps MVP flow (a Premium feature): Connect GitHub → Select Repo → Create Project → Link an
 * existing SSH host → View pipeline/logs → Open the host's terminal. Each step is a sheet
 * (bottom on phones, centered on tablets). Entitlement is enforced at the entry ({@link
 * TerminowHome#showDevOps()}) and again by the backend on every call — a {@code premium_required} from
 * the server reopens the upgrade flow, so an expired user is locked out of sync while their saved
 * projects stay and Disconnect still works. GitLab/Bitbucket are shown as "coming soon", and
 * Deploy/Rollback are intentionally not offered until the workflow and backend are ready.
 *
 * <p>The provider connection, repositories and runs come from the TermiNow DevOps backend; this class
 * never sees a provider token. When the backend is not deployed/configured yet, the calls fail and an
 * honest message is shown — nothing is simulated.</p>
 */
public final class TerminowDevOpsUi {

    /** What the DevOps UI needs from the app: the SSH hosts to link, and opening a host's terminal. */
    public interface Host {
        @NonNull List<TerminowStore.Host> sshHosts();
        void openHost(@NonNull TerminowStore.Host host);
        /** Open the Premium upgrade flow (used when the backend reports the entitlement lapsed). */
        void openUpgrade();
        /** Connect was just launched into the browser: refresh the DevOps status when the app returns. */
        void onConnectLaunched();
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final TerminowDevOps mDevOps;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public TerminowDevOpsUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store,
                            @NonNull TerminowDevOps devOps, @NonNull Host host) {
        mActivity = activity;
        mStore = store;
        mDevOps = devOps;
        mHost = host;
    }

    public void open() {
        if (!TerminowAccountUi.isPremium(mStore)) { mHost.openUpgrade(); return; }
        Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_devops_title));
        sheet.subtitle(mActivity.getString(R.string.terminow_devops_subtitle));
        sheet.show();
        loadHome(sheet);
    }

    /** Always re-query the connection from the backend with the current Firebase token (no cached state). */
    private void loadHome(@NonNull Sheet sheet) {
        sheet.content.removeAllViews();
        sheet.content.addView(new ProgressBar(mActivity));
        new Thread(() -> {
            try {
                TerminowDevOps.Status st = mDevOps.status();
                mHandler.post(() -> renderHome(sheet, st));
            } catch (TerminowDevOps.DevOpsException e) {
                mHandler.post(() -> {
                    if (e.isPremiumRequired()) { sheet.dialog.dismiss(); mHost.openUpgrade(); }
                    else renderError(sheet, devOpsMessage(e));
                });
            } catch (Exception e) {
                mHandler.post(() -> renderError(sheet, mActivity.getString(R.string.terminow_devops_error)));
            }
        }, "devops-status").start();
    }

    /** A real error state with a Retry that re-queries — never a silent stale "disconnected". */
    private void renderError(@NonNull Sheet sheet, @NonNull String message) {
        sheet.content.removeAllViews();
        sheet.note(message);
        sheet.action(mActivity.getString(R.string.terminow_install_retry), true, () -> loadHome(sheet));
    }

    private void renderHome(@NonNull Sheet sheet, @NonNull TerminowDevOps.Status status) {
        sheet.content.removeAllViews();
        if (!status.connected) {
            sheet.section(R.string.terminow_devops_providers);
            sheet.action(mActivity.getString(R.string.terminow_devops_connect, "GitHub"), true, () ->
                run(mDevOps::connectUrl, sheet, url -> {
                    // Mark pending so the app re-checks on return, and dismiss this (now stale) sheet so the
                    // next open re-queries the backend instead of showing a cached "disconnected" state.
                    mHost.onConnectLaunched();
                    sheet.dialog.dismiss();
                    openUrl(url);
                    mActivity.showToast(mActivity.getString(R.string.terminow_devops_connect_opened), false);
                }));
            sheet.note(mActivity.getString(R.string.terminow_devops_connect_note));
            sheet.action("GitLab — " + mActivity.getString(R.string.terminow_devops_coming_soon), false, null);
            sheet.action("Bitbucket — " + mActivity.getString(R.string.terminow_devops_coming_soon), false, null);
            return;
        }

        TextView connected = sheet.text(mActivity.getString(R.string.terminow_devops_connected_as, status.login.isEmpty() ? "GitHub" : "@" + status.login), 14, R.color.terminow_quick_ready);
        connected.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.content.addView(connected);

        sheet.section(R.string.terminow_devops_projects_title);
        List<TerminowStore.DevOpsProject> projects = mStore.getDevOpsProjects();
        if (projects.isEmpty()) sheet.note(mActivity.getString(R.string.terminow_devops_no_projects));
        for (TerminowStore.DevOpsProject p : projects) sheet.content.addView(projectRow(sheet, p));

        sheet.action(mActivity.getString(R.string.terminow_devops_new_project), true, () -> newProject(sheet));
        sheet.action(mActivity.getString(R.string.terminow_devops_disconnect), false, () -> confirmDisconnect(sheet));
    }

    @NonNull
    private View projectRow(@NonNull Sheet sheet, @NonNull TerminowStore.DevOpsProject project) {
        LinearLayout card = sheet.card();
        TextView name = sheet.text(project.repo, 15, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);
        TerminowStore.Host linked = hostFor(project.hostKey);
        card.addView(sheet.text(linked != null ? mActivity.getString(R.string.terminow_devops_linked_host, linked.getTitle())
            : mActivity.getString(R.string.terminow_devops_not_linked), 12, R.color.terminow_text_secondary));

        LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = sheet.wrap();
        ap.topMargin = sheet.dp(10);
        actions.addView(sheet.smallButton(mActivity.getString(R.string.terminow_devops_pipeline), () -> showRuns(sheet, project.repo)));
        if (linked != null) actions.addView(sheet.smallButton(mActivity.getString(R.string.terminow_install_open),
            () -> { sheet.dialog.dismiss(); mHost.openHost(linked); }));
        else actions.addView(sheet.smallButton(mActivity.getString(R.string.terminow_devops_link_host), () -> linkHost(sheet, project)));
        actions.addView(sheet.smallButton(mActivity.getString(R.string.terminow_tools_github_remove), () -> {
            mStore.removeDevOpsProject(project.provider, project.repo);
            reopen();
        }));
        card.addView(actions, ap);
        return card;
    }

    /** Select Repo → Create Project. */
    private void newProject(@NonNull Sheet parent) {
        run(mDevOps::repos, parent, repos -> {
            Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_devops_select_repo));
            if (repos.isEmpty()) sheet.note(mActivity.getString(R.string.terminow_devops_no_repos));
            for (TerminowDevOps.Repo r : repos) {
                LinearLayout card = sheet.card();
                card.setClickable(true);
                TextView n = sheet.text(r.fullName, 15, R.color.terminow_text);
                n.setTypeface(Typeface.DEFAULT_BOLD);
                card.addView(n);
                card.addView(sheet.text((r.isPrivate ? "private" : "public") + (r.defaultBranch.isEmpty() ? "" : " · " + r.defaultBranch), 12, R.color.terminow_text_secondary));
                card.setOnClickListener(v -> {
                    TerminowStore.DevOpsProject p = new TerminowStore.DevOpsProject();
                    p.provider = "github";
                    p.repo = r.fullName;
                    mStore.addDevOpsProject(p);
                    sheet.dialog.dismiss();
                    linkHost(parent, p);   // Create Project → Link Host
                });
                sheet.content.addView(card);
            }
            sheet.show();
        });
    }

    /** Link an existing SSH host to a project. */
    private void linkHost(@NonNull Sheet parent, @NonNull TerminowStore.DevOpsProject project) {
        List<TerminowStore.Host> hosts = mHost.sshHosts();
        Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_devops_link_host));
        if (hosts.isEmpty()) sheet.note(mActivity.getString(R.string.terminow_connect_empty));
        for (TerminowStore.Host h : hosts) {
            if (!h.isTerminal()) continue;
            LinearLayout card = sheet.card();
            card.setClickable(true);
            TextView n = sheet.text(h.getTitle(), 15, R.color.terminow_text);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(n);
            card.setOnClickListener(v -> {
                project.hostKey = h.getKey();
                mStore.addDevOpsProject(project);
                sheet.dialog.dismiss();
                reopen();
            });
            sheet.content.addView(card);
        }
        sheet.show();
    }

    /** View pipeline (Actions runs) for a repo; tap a run to see its jobs/steps. */
    private void showRuns(@NonNull Sheet parent, @NonNull String repo) {
        run(() -> mDevOps.runs(repo), parent, runs -> {
            Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_devops_pipeline));
            if (runs.isEmpty()) sheet.note(mActivity.getString(R.string.terminow_devops_no_runs));
            for (TerminowDevOps.Run r : runs) {
                LinearLayout card = sheet.card();
                card.setClickable(true);
                TextView n = sheet.text(r.name.isEmpty() ? ("#" + r.id) : r.name, 15, R.color.terminow_text);
                n.setTypeface(Typeface.DEFAULT_BOLD);
                card.addView(n);
                String state = (r.conclusion.isEmpty() ? r.status : r.conclusion) + (r.branch.isEmpty() ? "" : " · " + r.branch);
                card.addView(sheet.text(state, 12, R.color.terminow_text_secondary));
                card.setOnClickListener(v -> showLogs(sheet, repo, r.id));
                sheet.content.addView(card);
            }
            sheet.show();
        });
    }

    private void showLogs(@NonNull Sheet parent, @NonNull String repo, long run) {
        run(() -> mDevOps.logs(repo, run), parent, jobs -> {
            Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_devops_logs));
            if (jobs.isEmpty()) sheet.note(mActivity.getString(R.string.terminow_devops_no_runs));
            for (TerminowDevOps.Job j : jobs) {
                sheet.section2(j.name + (j.conclusion.isEmpty() ? "" : " · " + j.conclusion));
                for (TerminowDevOps.Step s : j.steps)
                    sheet.content.addView(sheet.text("• " + s.name + "  " + (s.conclusion.isEmpty() ? s.status : s.conclusion), 12, R.color.terminow_text_secondary));
            }
            sheet.show();
        });
    }

    private void confirmDisconnect(@NonNull Sheet parent) {
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_devops_disconnect)
            .setMessage(R.string.terminow_devops_disconnect_confirm)
            .setPositiveButton(R.string.terminow_devops_disconnect, (d, w) ->
                run(() -> { mDevOps.disconnect(); return Boolean.TRUE; }, parent, ok -> reopen()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Nullable
    private TerminowStore.Host hostFor(@NonNull String key) {
        if (key.isEmpty()) return null;
        for (TerminowStore.Host h : mHost.sshHosts()) if (h.getKey().equals(key)) return h;
        return null;
    }

    private void reopen() { open(); }

    private void openUrl(@NonNull String url) {
        TerminowBrowser.open(mActivity, Collections.singletonList(url));
    }

    // ---- Async helper ---------------------------------------------------------------------------

    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void run(@NonNull T result); }

    /** Run {@code work} off the main thread; on success call {@code done}; map errors to the right UI. */
    private <T> void run(@NonNull Work<T> work, @NonNull Sheet busySheet, @NonNull Done<T> done) {
        new Thread(() -> {
            try {
                T result = work.run();
                mHandler.post(() -> done.run(result));
            } catch (TerminowDevOps.DevOpsException e) {
                mHandler.post(() -> {
                    if (e.isPremiumRequired()) { busySheet.dialog.dismiss(); mHost.openUpgrade(); }
                    else mActivity.showToast(devOpsMessage(e), true);
                });
            } catch (Exception e) {
                mHandler.post(() -> mActivity.showToast(mActivity.getString(R.string.terminow_devops_error), true));
            }
        }, "devops").start();
    }

    @NonNull
    private String devOpsMessage(@NonNull TerminowDevOps.DevOpsException e) {
        if (e.isNotConnected()) return mActivity.getString(R.string.terminow_devops_not_connected_msg);
        if ("no_access".equals(e.code)) return mActivity.getString(R.string.terminow_devops_no_access);
        // 503 / service not configured yet, or other provider error — honest, not simulated.
        return mActivity.getString(R.string.terminow_devops_backend_required);
    }

    // ---- Sheet (bottom on phones, centered on tablets; charcoal, 24dp) --------------------------

    private final class Sheet {
        final Dialog dialog = new Dialog(mActivity);
        final LinearLayout content = new LinearLayout(mActivity);
        final boolean phone = mActivity.getResources().getConfiguration().screenWidthDp < 600;

        Sheet(@NonNull String title) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root = new LinearLayout(mActivity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(20), dp(16), dp(20), dp(20));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color(R.color.terminow_bar_home));
            bg.setStroke(dp(1), color(R.color.terminow_quick_border));
            float r = dp(24);
            bg.setCornerRadii(phone ? new float[]{r, r, r, r, 0, 0, 0, 0} : new float[]{r, r, r, r, r, r, r, r});
            root.setBackground(bg);
            LinearLayout head = new LinearLayout(mActivity);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = text(title, 19, R.color.terminow_text);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            androidx.core.view.ViewCompat.setAccessibilityHeading(t, true);
            head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = text("✕", 18, R.color.terminow_text);
            close.setGravity(Gravity.CENTER);
            close.setContentDescription(mActivity.getString(android.R.string.cancel));
            close.setOnClickListener(v -> dialog.dismiss());
            head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            root.addView(head, wrap());
            content.setOrientation(LinearLayout.VERTICAL);
            ScrollView scroll = new ScrollView(mActivity);
            scroll.addView(content);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            sp.topMargin = dp(8);
            root.addView(scroll, sp);
            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int screen = mActivity.getResources().getDisplayMetrics().widthPixels;
                if (phone) { window.setGravity(Gravity.BOTTOM); window.setLayout(screen, ViewGroup.LayoutParams.WRAP_CONTENT); }
                else window.setLayout(Math.min(screen - dp(32), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        void show() { dialog.show(); }

        void subtitle(@NonNull String s) { content.addView(text(s, 13, R.color.terminow_text_secondary), wrap()); }

        void section(int label) { section2(mActivity.getString(label)); }
        void section2(@NonNull String label) {
            TextView v = text(label, 13, R.color.terminow_text_secondary);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setPadding(0, dp(16), 0, dp(4));
            androidx.core.view.ViewCompat.setAccessibilityHeading(v, true);
            content.addView(v);
        }

        void note(@NonNull String s) { content.addView(text(s, 12, R.color.terminow_text_secondary), wrap()); }

        void action(@NonNull String label, boolean primary, @Nullable Runnable onClick) {
            TextView b = text(label, 15, primary ? R.color.terminow_bar_home : R.color.terminow_text);
            b.setAllCaps(false);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(48));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_field));
            shape.setCornerRadius(dp(12));
            b.setBackground(shape);
            b.setEnabled(onClick != null);
            b.setAlpha(onClick != null ? 1f : 0.5f);
            if (onClick != null) { b.setClickable(true); b.setOnClickListener(v -> onClick.run()); }
            LinearLayout.LayoutParams lp = wrap();
            lp.topMargin = dp(10);
            content.addView(b, lp);
        }

        @NonNull View smallButton(@NonNull String label, @NonNull Runnable onClick) {
            TextView b = text(label, 13, R.color.terminow_text);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setMinHeight(dp(48));
            b.setPadding(dp(14), 0, dp(14), 0);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color(R.color.terminow_field));
            shape.setCornerRadius(dp(10));
            b.setBackground(shape);
            b.setClickable(true);
            b.setOnClickListener(v -> onClick.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6);
            b.setLayoutParams(lp);
            return b;
        }

        @NonNull LinearLayout card() {
            LinearLayout c = new LinearLayout(mActivity);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setPadding(dp(14), dp(12), dp(14), dp(12));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color(R.color.terminow_quick_card));
            shape.setCornerRadius(dp(12));
            shape.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
            c.setBackground(shape);
            LinearLayout.LayoutParams lp = wrap();
            lp.topMargin = dp(8);
            c.setLayoutParams(lp);
            // Caller adds it to the container exactly once (returning it already-parented double-adds → crash).
            return c;
        }

        @NonNull TextView text(@NonNull String value, int sp, int colorRes) {
            TextView v = new TextView(mActivity);
            v.setText(value);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            v.setTextColor(color(colorRes));
            return v;
        }

        @NonNull LinearLayout.LayoutParams wrap() {
            return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        int dp(float v) { return TerminowDevOpsUi.this.dp(v); }
    }

    private int color(int res) { return ContextCompat.getColor(mActivity, res); }
    private int dp(float v) { return Math.round(v * mActivity.getResources().getDisplayMetrics().density); }
}
