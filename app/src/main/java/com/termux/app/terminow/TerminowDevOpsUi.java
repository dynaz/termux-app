package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    /** Logcat tag for connect diagnostics. Logs carry a short request id + provider + host only —
     *  never a token, secret, OAuth code, state or URL query. */
    private static final String TAG = "TerminowDevOps";

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
                TerminowDevOps.Status st = mDevOps.status("github");
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

    /** Display name for a provider id. */
    @NonNull
    private String providerName(@NonNull String id) {
        switch (id) {
            case "github":    return "GitHub";
            case "gitlab":    return "GitLab";
            case "bitbucket": return "Bitbucket";
            default:          return id;
        }
    }

    private void renderHome(@NonNull Sheet sheet, @NonNull TerminowDevOps.Status status) {
        sheet.content.removeAllViews();

        // One row per provider: Connected (+ Disconnect), Connect, or — for a provider the backend has no
        // OAuth app configured for — "Coming soon". This is backend-driven: GitLab/Bitbucket show Connect
        // only once their config is on the server AND a live connection works.
        List<TerminowDevOps.Provider> providers = status.providers;
        if (providers.isEmpty()) {
            // Backend older than multi-provider status: show GitHub from the flat fields and the other
            // two as "Coming soon", so the home looks the same before the provider-aware backend ships.
            providers = new ArrayList<>();
            TerminowDevOps.Provider g = new TerminowDevOps.Provider();
            g.id = "github"; g.configured = true; g.connected = status.connected; g.login = status.login;
            providers.add(g);
            for (String id : new String[]{"gitlab", "bitbucket"}) {
                TerminowDevOps.Provider p = new TerminowDevOps.Provider();
                p.id = id; p.configured = false; p.connected = false;
                providers.add(p);
            }
        }
        sheet.section(R.string.terminow_devops_providers);
        boolean anyConnected = false;
        for (TerminowDevOps.Provider p : providers) {
            String name = providerName(p.id);
            if (p.connected && !p.valid) {
                // Token expired/revoked: offer Reconnect (saved projects/host mappings are kept). The
                // final boolean-copies are for the lambdas.
                anyConnected = true;
                final String pidX = p.id;
                TextView expired = sheet.text(mActivity.getString(R.string.terminow_devops_connection_expired, name), 14, R.color.terminow_mcp_warn);
                expired.setTypeface(Typeface.DEFAULT_BOLD);
                sheet.content.addView(expired);
                sheet.action(mActivity.getString(R.string.terminow_devops_reconnect, name), true,
                    () -> connectProvider(sheet, pidX, name));
                sheet.action(mActivity.getString(R.string.terminow_devops_disconnect_provider, name), false,
                    () -> confirmDisconnect(sheet, pidX, name));
            } else if (p.connected) {
                anyConnected = true;
                TextView connected = sheet.text(mActivity.getString(R.string.terminow_devops_connected_as,
                    p.login.isEmpty() ? name : "@" + p.login) + " · " + name, 14, R.color.terminow_quick_ready);
                connected.setTypeface(Typeface.DEFAULT_BOLD);
                sheet.content.addView(connected);
                sheet.action(mActivity.getString(R.string.terminow_devops_disconnect_provider, name), false,
                    () -> confirmDisconnect(sheet, p.id, name));
            } else if (p.configured) {
                final String pid = p.id;
                sheet.action(mActivity.getString(R.string.terminow_devops_connect, name), true,
                    () -> connectProvider(sheet, pid, name));
            } else {
                sheet.action(name + " — " + mActivity.getString(R.string.terminow_devops_coming_soon), false, null);
            }
        }

        if (!anyConnected) {
            sheet.note(mActivity.getString(R.string.terminow_devops_connect_note));
            return;
        }

        final TerminowDevOps.Status fStatus = status;
        sheet.section(R.string.terminow_devops_projects_title);
        List<TerminowStore.DevOpsProject> projects = mStore.getDevOpsProjects();
        if (projects.isEmpty()) sheet.note(mActivity.getString(R.string.terminow_devops_no_projects));
        for (TerminowStore.DevOpsProject p : projects) sheet.content.addView(projectRow(sheet, p));
        sheet.action(mActivity.getString(R.string.terminow_devops_new_project), true, () -> newProject(sheet, fStatus));
    }

    /**
     * Start a provider connection: show a loading dialog immediately (so a tap is never silent), fetch
     * the authorize URL off the main thread, validate it is HTTPS on the provider's expected host, then
     * open the browser. Every failure path shows an error with Retry. Diagnostics carry a short request
     * id + provider + host only — never token/secret/code/state/query.
     */
    private void connectProvider(@NonNull Sheet home, @NonNull String providerId, @NonNull String name) {
        final String reqId = Integer.toHexString((int) (System.nanoTime() & 0xffffffffL));
        Log.i(TAG, "connect start req=" + reqId + " provider=" + providerId);
        ProgressBar spinner = new ProgressBar(mActivity);
        spinner.setPadding(dp(24), dp(24), dp(24), dp(24));
        final android.app.AlertDialog progress = new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_devops_connecting, name))
            .setView(spinner)
            .setCancelable(true)
            .create();
        progress.show();
        new Thread(() -> {
            try {
                String url = mDevOps.connectUrl(providerId, reqId);
                String host = safeHost(url);
                boolean valid = isValidProviderAuthorizeUrl(providerId, url);
                Log.i(TAG, "connect url req=" + reqId + " provider=" + providerId + " host=" + host + " https_host_ok=" + valid);
                mHandler.post(() -> {
                    dismissQuietly(progress);
                    if (!valid) { showConnectError(home, providerId, name, mActivity.getString(R.string.terminow_devops_bad_authorize_url)); return; }
                    mHost.onConnectLaunched();
                    home.dialog.dismiss();
                    boolean opened = openUrl(url);
                    Log.i(TAG, "connect open req=" + reqId + " opened=" + opened);
                    if (opened) mActivity.showToast(mActivity.getString(R.string.terminow_devops_connect_opened), false);
                    else mActivity.showToast(mActivity.getString(R.string.terminow_devops_no_browser_connect), true);
                });
            } catch (TerminowDevOps.DevOpsException e) {
                Log.w(TAG, "connect fail req=" + reqId + " provider=" + providerId + " code=" + e.code + " http=" + e.httpCode);
                mHandler.post(() -> {
                    dismissQuietly(progress);
                    if (e.isPremiumRequired()) { home.dialog.dismiss(); mHost.openUpgrade(); }
                    else showConnectError(home, providerId, name, devOpsMessage(e));
                });
            } catch (Exception e) {
                Log.w(TAG, "connect error req=" + reqId + " provider=" + providerId + " type=" + e.getClass().getSimpleName());
                mHandler.post(() -> {
                    dismissQuietly(progress);
                    showConnectError(home, providerId, name, mActivity.getString(R.string.terminow_devops_error));
                });
            }
        }, "devops-connect").start();
    }

    private void dismissQuietly(@NonNull android.app.AlertDialog d) {
        if (!mActivity.isFinishing() && d.isShowing()) d.dismiss();
    }

    private void showConnectError(@NonNull Sheet home, @NonNull String providerId, @NonNull String name, @NonNull String message) {
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_devops_connect, name))
            .setMessage(message)
            .setPositiveButton(R.string.terminow_install_retry, (d, w) -> connectProvider(home, providerId, name))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** The authorize URL must be HTTPS on the provider's own host, checked before opening a browser. */
    private boolean isValidProviderAuthorizeUrl(@NonNull String providerId, @Nullable String url) {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("https://")) return false;
        String host = safeHost(url);
        switch (providerId) {
            case "github":    return host.equals("github.com");
            case "gitlab":    return host.equals("gitlab.com");
            case "bitbucket": return host.equals("bitbucket.org");
            default:          return false;
        }
    }

    @NonNull
    private static String safeHost(@Nullable String url) {
        try { String h = android.net.Uri.parse(url).getHost(); return h == null ? "" : h.toLowerCase(Locale.ROOT); }
        catch (Exception e) { return ""; }
    }

    @NonNull
    private View projectRow(@NonNull Sheet sheet, @NonNull TerminowStore.DevOpsProject project) {
        LinearLayout card = sheet.card();
        TextView name = sheet.text(providerName(project.provider) + " · " + project.repo, 15, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);
        TerminowStore.Host linked = hostFor(project.hostKey);
        card.addView(sheet.text(linked != null ? mActivity.getString(R.string.terminow_devops_linked_host, linked.getTitle())
            : mActivity.getString(R.string.terminow_devops_not_linked), 12, R.color.terminow_text_secondary));

        LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams ap = sheet.wrap();
        ap.topMargin = sheet.dp(10);
        String repoRef = project.repoId.isEmpty() ? project.repo : project.repoId;
        actions.addView(sheet.smallButton(mActivity.getString(R.string.terminow_devops_pipeline), () -> showRuns(sheet, project.provider, repoRef)));
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

    /** Choose a provider (when more than one is connected), then select a repository. */
    private void newProject(@NonNull Sheet parent, @NonNull TerminowDevOps.Status status) {
        List<String> connected = new java.util.ArrayList<>();
        for (TerminowDevOps.Provider p : status.providers) if (p.connected) connected.add(p.id);
        if (connected.isEmpty() && status.connected) connected.add("github");
        if (connected.size() <= 1) { selectRepo(parent, connected.isEmpty() ? "github" : connected.get(0)); return; }
        Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_devops_select_provider));
        for (String id : connected) {
            LinearLayout card = sheet.card();
            card.setClickable(true);
            TextView n = sheet.text(providerName(id), 15, R.color.terminow_text);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(n);
            card.setOnClickListener(v -> { sheet.dialog.dismiss(); selectRepo(parent, id); });
            sheet.content.addView(card);
        }
        sheet.show();
    }

    /** Select Repo (searchable) → Create Project. The backend returns every page the connection can
     *  read, so filtering here covers the whole list — not just the first page. Filtering is in-memory,
     *  so there is no per-keystroke backend call to debounce or race. */
    private void selectRepo(@NonNull Sheet parent, @NonNull String provider) {
        run(() -> mDevOps.repos(provider), parent, repos -> {
            Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_devops_select_repo));
            // true once a repo is tapped, so a second tap during Create Project can't double-select.
            final boolean[] selecting = {false};
            final TextView count = sheet.text("", 12, R.color.terminow_text_secondary);
            android.widget.EditText search = sheet.searchField(mActivity.getString(R.string.terminow_devops_search_repos));
            sheet.addPinned(count);
            renderRepoList(sheet, parent, repos, "", count, selecting);
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    renderRepoList(sheet, parent, repos, e.toString(), count, selecting);
                }
            });
            sheet.show();
        });
    }

    /** Filter {@code all} by {@code query} (case-insensitive substring of owner/repo) and rebuild the list. */
    private void renderRepoList(@NonNull Sheet sheet, @NonNull Sheet parent, @NonNull List<TerminowDevOps.Repo> all,
                                @NonNull String query, @NonNull TextView count, @NonNull boolean[] selecting) {
        sheet.content.removeAllViews();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<TerminowDevOps.Repo> shown = new ArrayList<>();
        for (TerminowDevOps.Repo r : all)
            if (q.isEmpty() || r.fullName.toLowerCase(Locale.ROOT).contains(q)) shown.add(r);

        count.setText(q.isEmpty()
            ? mActivity.getString(R.string.terminow_devops_repo_count_all, all.size())
            : mActivity.getString(R.string.terminow_devops_repo_count, shown.size(), all.size()));

        if (all.isEmpty()) { sheet.note(mActivity.getString(R.string.terminow_devops_no_repos)); return; }
        if (shown.isEmpty()) { sheet.note(mActivity.getString(R.string.terminow_devops_no_repos_match, query.trim())); return; }

        for (TerminowDevOps.Repo r : shown) {
            LinearLayout card = sheet.card();
            card.setClickable(true);
            TextView n = sheet.text(r.fullName, 15, R.color.terminow_text);
            n.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(n);
            card.addView(sheet.text((r.isPrivate ? "private" : "public") + (r.defaultBranch.isEmpty() ? "" : " · " + r.defaultBranch), 12, R.color.terminow_text_secondary));
            card.setOnClickListener(v -> {
                if (selecting[0]) return;   // guard against a double tap during Create Project
                selecting[0] = true;
                TerminowStore.DevOpsProject p = new TerminowStore.DevOpsProject();
                p.provider = r.provider;
                p.repo = r.fullName;
                p.repoId = r.id;
                mStore.addDevOpsProject(p);
                sheet.dialog.dismiss();
                linkHost(parent, p);   // Create Project → Link Host
            });
            sheet.content.addView(card);
        }
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

    /** View pipeline runs for a repo; tap a run to see its jobs/steps. */
    private void showRuns(@NonNull Sheet parent, @NonNull String provider, @NonNull String repoId) {
        run(() -> mDevOps.runs(provider, repoId), parent, runs -> {
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
                card.setOnClickListener(v -> showLogs(sheet, provider, repoId, r.id));
                sheet.content.addView(card);
            }
            sheet.show();
        });
    }

    private void showLogs(@NonNull Sheet parent, @NonNull String provider, @NonNull String repoId, @NonNull String run) {
        run(() -> mDevOps.logs(provider, repoId, run), parent, jobs -> {
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

    private void confirmDisconnect(@NonNull Sheet parent, @NonNull String provider, @NonNull String name) {
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_devops_disconnect_provider, name))
            .setMessage(R.string.terminow_devops_disconnect_confirm)
            .setPositiveButton(mActivity.getString(R.string.terminow_devops_disconnect_provider, name), (d, w) ->
                run(() -> { mDevOps.disconnect(provider); return Boolean.TRUE; }, parent, ok -> reopen()))
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

    /** Open a single validated web link; returns whether a browser activity actually started. */
    private boolean openUrl(@NonNull String url) {
        if (!TerminowBrowser.isWebLink(url)) return false;
        try { mActivity.startActivity(TerminowBrowser.viewIntent(url, TerminowBrowser.CHROME)); return true; }
        catch (android.content.ActivityNotFoundException | SecurityException ignored) { /* Chrome missing/off */ }
        try { mActivity.startActivity(TerminowBrowser.viewIntent(url, null)); return true; }
        catch (android.content.ActivityNotFoundException | SecurityException ignored) { return false; }
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
        final LinearLayout root = new LinearLayout(mActivity);
        final LinearLayout content = new LinearLayout(mActivity);
        final boolean phone = mActivity.getResources().getConfiguration().screenWidthDp < 600;
        /** Index in {@link #root} where the scrolling list starts; pinned rows insert just before it. */
        private int scrollIndex;

        Sheet(@NonNull String title) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
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
            scrollIndex = root.getChildCount(); // pinned rows go between the head and the scroll
            content.setOrientation(LinearLayout.VERTICAL);
            // Cap the list height so a long list scrolls inside the sheet instead of overflowing the
            // screen (and so the sheet still fits when the soft keyboard is open).
            ScrollView scroll = new ScrollView(mActivity) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int max = Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.55f);
                    super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
                }
            };
            scroll.setFillViewport(true);
            scroll.addView(content);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.topMargin = dp(8);
            root.addView(scroll, sp);
            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                int screen = mActivity.getResources().getDisplayMetrics().widthPixels;
                if (phone) { window.setGravity(Gravity.BOTTOM); window.setLayout(screen, ViewGroup.LayoutParams.WRAP_CONTENT); }
                else window.setLayout(Math.min(screen - dp(32), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        /** Pin a row below the title/close and above the scrolling list (so it never scrolls away). */
        void addPinned(@NonNull View v) {
            LinearLayout.LayoutParams lp = wrap();
            lp.topMargin = dp(10);
            root.addView(v, scrollIndex++, lp);
        }

        /** A pinned search field: leading search icon, single-line input, and a × that clears it
         *  (shown only while there is text). Returns the input so the caller attaches its filter. */
        @NonNull android.widget.EditText searchField(@NonNull String hint) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color(R.color.terminow_field));
            shape.setCornerRadius(dp(12));
            shape.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
            row.setBackground(shape);
            row.setPadding(dp(12), 0, dp(6), 0);

            TextView icon = text("🔍", 14, R.color.terminow_text_secondary); // 🔍
            row.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            final android.widget.EditText input = new android.widget.EditText(mActivity);
            input.setHint(hint);
            input.setSingleLine(true);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            input.setTextColor(color(R.color.terminow_text));
            input.setHintTextColor(color(R.color.terminow_text_secondary));
            input.setBackground(null);
            input.setMinHeight(dp(48));
            input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ilp.leftMargin = dp(8);
            row.addView(input, ilp);

            final TextView clear = text("✕", 15, R.color.terminow_text_secondary); // ✕
            clear.setGravity(Gravity.CENTER);
            clear.setContentDescription(mActivity.getString(R.string.terminow_devops_search_clear));
            clear.setVisibility(View.GONE);
            clear.setOnClickListener(v -> input.setText(""));
            row.addView(clear, new LinearLayout.LayoutParams(dp(40), dp(40)));

            input.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable e) {
                    clear.setVisibility(e.length() > 0 ? View.VISIBLE : View.GONE);
                }
            });
            addPinned(row);
            return input;
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
