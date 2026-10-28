package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.linux.TerminowLinuxEnv;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Install tools sheet of the terminal toolbar: Claude Code, Cursor CLI, gstack, Git, curl
 * and tmux, and plugins of the official Claude Code marketplace, in the environment of the tab,
 * as its user, with their real state. The tab decides the target: a local Linux environment, or
 * a saved SSH host, whose server the tools go onto over its own connection, never the local one.
 * Installing is done by {@link TerminowToolInstaller}, whose jobs outlive this sheet; the sheet
 * only shows them, and finds out again what is installed when it opens and after each install.
 */
final class TerminowToolSheet {

    private static final TerminowToolInstaller.Tool[] TOOLS = {
        TerminowToolInstaller.Tool.CLAUDE, TerminowToolInstaller.Tool.CURSOR,
        TerminowToolInstaller.Tool.CODEX, TerminowToolInstaller.Tool.GEMINI, TerminowToolInstaller.Tool.GROK,
        TerminowToolInstaller.Tool.OPENCODE, TerminowToolInstaller.Tool.AIDER, TerminowToolInstaller.Tool.HERMES,
        TerminowToolInstaller.Tool.ANTIGRAVITY,
        TerminowToolInstaller.Tool.GSTACK,
        TerminowToolInstaller.Tool.GIT, TerminowToolInstaller.Tool.CURL, TerminowToolInstaller.Tool.TMUX, TerminowToolInstaller.Tool.FISH};

    /** The tools and the plugins: the jobs that the sheet shows. */
    private static final TerminowToolInstaller.Tool[] JOBS = {
        TerminowToolInstaller.Tool.CLAUDE, TerminowToolInstaller.Tool.CURSOR,
        TerminowToolInstaller.Tool.CODEX, TerminowToolInstaller.Tool.GEMINI, TerminowToolInstaller.Tool.GROK,
        TerminowToolInstaller.Tool.OPENCODE, TerminowToolInstaller.Tool.AIDER, TerminowToolInstaller.Tool.HERMES,
        TerminowToolInstaller.Tool.ANTIGRAVITY,
        TerminowToolInstaller.Tool.GSTACK,
        TerminowToolInstaller.Tool.GIT, TerminowToolInstaller.Tool.CURL, TerminowToolInstaller.Tool.TMUX,
        TerminowToolInstaller.Tool.FISH, TerminowToolInstaller.Tool.PLUGINS};

    /**
     * Where tools are installed: a local Linux environment, or a saved SSH host over its own
     * connection. It keeps the runner, the key that scopes the jobs and the user, so a job stays
     * bound to it even if the tab changes.
     */
    static final class Target {
        final boolean ssh;
        final String key;
        final String user;
        final TerminowToolInstaller.Runner runner;
        /** The local environment, {@code null} for an SSH host. */
        @Nullable final TerminowLauncher.Environment environment;
        /** The SSH host, {@code null} for a local environment. */
        @Nullable final TerminowStore.Host host;
        /** The SSH tab the tools belong to, for Open and Start Fish; {@code null} for local. */
        @Nullable final TerminalSession session;

        private Target(boolean ssh, @NonNull String key, @NonNull String user, @NonNull TerminowToolInstaller.Runner runner,
                       @Nullable TerminowLauncher.Environment environment, @Nullable TerminowStore.Host host,
                       @Nullable TerminalSession session) {
            this.ssh = ssh;
            this.key = key;
            this.user = user;
            this.runner = runner;
            this.environment = environment;
            this.host = host;
            this.session = session;
        }

        @NonNull
        static Target local(@NonNull TerminowLauncher.Environment environment, @NonNull String user,
                            @NonNull TerminowToolInstaller.Runner runner) {
            return new Target(false, environment.key, user, runner, environment, null, null);
        }

        @NonNull
        static Target ssh(@NonNull TerminowStore.Host host, @NonNull TerminalSession session,
                          @NonNull TerminowToolInstaller.Runner runner) {
            return new Target(true, TerminowRemoteTools.aliasOf(host), host.username.isEmpty() ? "root" : host.username, runner,
                null, host, session);
        }
    }

    private final TermuxActivity mActivity;
    private final TerminowLauncher mLauncher;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    /** Runs the scripts in a local environment with proot-distro. */
    private final TerminowToolInstaller.Runner mLocalRunner;

    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    @Nullable private Target mTarget;
    /** Set while the target is a host, to close its connection when the sheet is closed. */
    @Nullable private TerminowRemoteTools mRemoteTools;
    /** What the target has, {@code null} while it is being checked. */
    @Nullable private TerminowToolInstaller.Detection mDetection;
    private boolean mDetecting;
    /** The jobs whose end was seen, so their target is checked again only once each. */
    private final List<TerminowToolInstaller.Job> mSeenEnded = new ArrayList<>();
    /** The tools whose log is shown in full. */
    private final java.util.Set<TerminowToolInstaller.Tool> mLogOpen = java.util.EnumSet.noneOf(TerminowToolInstaller.Tool.class);

    // Tools Library filters: the search text, the chosen category (0=All) and the installed-only toggle.
    private String mSearch = "";
    private int mCategory = 0;
    private boolean mInstalledOnly;
    /** The category chips, in order. CAT_ALL is index 0. */
    private static final int CAT_ALL = 0, CAT_AI = 1, CAT_TERMINAL = 2, CAT_DEVELOPER = 3, CAT_PLUGINS = 4;

    /** Which category a tool belongs to, for the filter chips. */
    private static int categoryOf(@NonNull TerminowToolInstaller.Tool tool) {
        switch (tool) {
            case CLAUDE: case CURSOR: case CODEX: case GEMINI: case GROK: case OPENCODE: case AIDER: case HERMES: case ANTIGRAVITY: return CAT_AI;
            case TMUX: case FISH: return CAT_TERMINAL;
            case GIT: case CURL: return CAT_DEVELOPER;
            case GSTACK: case PLUGINS: return CAT_PLUGINS;
            default: return CAT_ALL;
        }
    }

    /** The scrolling list of tool rows; re-populated on a filter change so the search keeps focus. */
    @Nullable private LinearLayout mList;

    private boolean matchesSearch(@NonNull String name, @NonNull String description) {
        if (mSearch.isEmpty()) return true;
        String q = mSearch.toLowerCase(java.util.Locale.ROOT);
        return name.toLowerCase(java.util.Locale.ROOT).contains(q)
            || description.toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    private boolean matchesFilters(@NonNull TerminowToolInstaller.Tool tool) {
        if (mCategory != CAT_ALL && categoryOf(tool) != mCategory) return false;
        if (mInstalledOnly && (mDetection == null || mDetection.versions.get(tool) == null)) return false;
        return matchesSearch(nameOf(tool), descriptionOf(tool));
    }

    private final Runnable mOnJobs = () -> mHandler.post(this::onJobsChanged);

    TerminowToolSheet(@NonNull TermuxActivity activity, @NonNull TerminowLauncher launcher, @NonNull File prefixDir) {
        mActivity = activity;
        mLauncher = launcher;
        TerminowClaudeSetup setup = new TerminowClaudeSetup(activity, new TerminowMcpTargets(prefixDir,
            new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH)));
        mLocalRunner = (alias, user, script, args) -> setup.start(TerminowToolInstaller.command(prefixDir, alias, user, script, args));
    }

    /**
     * Open the sheet for the environment, or the first Linux one when it is TermiNow itself,
     * as the user of the tab, or else the user chosen for the environment.
     */
    void show(@NonNull TerminowLauncher.Environment environment, @Nullable String user) {
        TerminowLauncher.Environment chosen = environment;
        if (chosen.isLocal()) {
            for (TerminowLauncher.Environment other : mLauncher.environments())
                if (!other.isLocal()) {
                    chosen = other;
                    break;
                }
        }
        String chosenUser = user != null && chosen == environment ? user : mLauncher.getUser(chosen);
        mRemoteTools = null;
        open(Target.local(chosen, chosenUser, mLocalRunner));
    }

    /** Open the Tools Library for an environment scrolled to the AI Agents category. */
    void showAi(@NonNull TerminowLauncher.Environment environment, @Nullable String user) {
        mCategory = CAT_AI;
        mSearch = "";
        mInstalledOnly = false;
        show(environment, user);
    }

    /** Open the sheet for a saved SSH host: tools go onto that server over its own connection. */
    void showSsh(@NonNull TerminowStore.Host host, @NonNull TerminalSession session, @NonNull TerminowRemoteTools remoteTools) {
        mRemoteTools = remoteTools;
        open(Target.ssh(host, session, remoteTools.runnerFor(host)));
    }

    private void open(@NonNull Target target) {
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        select(target);
        Dialog dialog = new Dialog(mActivity, R.style.TerminowPanelDialog);
        mDialog = dialog;
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(color(R.color.terminow_panel_scrim));
        root.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(mActivity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_panel));
        float radius = dp(24);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);
        sheet.setPadding(dp(16), dp(8), dp(16), dp(16));

        View handle = new View(mActivity);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(color(R.color.terminow_text_secondary));
        handleShape.setCornerRadius(dp(2));
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        sheet.addView(handle, handleParams);
        TextView title = text(mActivity.getString(R.string.terminow_install_title), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        sheet.addView(TerminowHelp.header(mActivity, title, "support/tools"), matchWrap());
        TextView subtitle = text(mActivity.getString(R.string.terminow_install_subtitle), 13, R.color.terminow_text_secondary);
        subtitle.setPadding(0, dp(2), 0, 0);
        sheet.addView(subtitle, matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content);
        mContent = content;
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // As wide as the screen on a phone, a column in the middle on a tablet or a foldable
        LinearLayout.LayoutParams sheetParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(sheet, sheetParams);
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int cap = mActivity.getResources().getConfiguration().screenWidthDp >= 720 ? dp(900) : dp(640);
            int width = Math.min(right - left, cap);
            int maxHeight = Math.round((bottom - top) * 0.90f);
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            ViewGroup.LayoutParams scrollParams = scroll.getLayoutParams();
            int scrollMax = maxHeight - dp(70);
            boolean tooTall = content.getHeight() > scrollMax;
            int scrollHeight = tooTall ? scrollMax : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params.width != width || scrollParams.height != scrollHeight) {
                params.width = width;
                scrollParams.height = scrollHeight;
                sheet.post(() -> {
                    sheet.setLayoutParams(params);
                    scroll.setLayoutParams(scrollParams);
                });
            }
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(d -> {
            TerminowToolInstaller.removeListener(mOnJobs);
            // The SSH connection is kept only while an install is still running
            if (mRemoteTools != null) mRemoteTools.closeAllIdle();
            if (mDialog == dialog) {
                mDialog = null;
                mContent = null;
            }
        });
        TerminowToolInstaller.addListener(mOnJobs);
        render();
        dialog.show();
        detect();
    }

    private void select(@NonNull Target target) {
        mTarget = target;
        mDetection = null;
        mSeenEnded.clear();
        for (TerminowToolInstaller.Tool tool : JOBS) {
            TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(target.key, target.user, tool);
            if (job != null && job.getState() != TerminowToolInstaller.State.RUNNING) mSeenEnded.add(job);
        }
    }

    /** Find out what is installed, in the background; never while it is being found out already. */
    private void detect() {
        Target target = mTarget;
        // A local target with no Linux environment has nothing to check
        if (target == null || (!target.ssh && target.environment != null && target.environment.isLocal()) ||
            (!target.ssh && unsupportedReason(target) != null) || mDetecting) return;
        mDetecting = true;
        mDetection = null;
        render();
        mExecutor.execute(() -> {
            TerminowToolInstaller.Detection detection = TerminowToolInstaller.detect(target.runner, target.key, target.user);
            mHandler.post(() -> {
                mDetecting = false;
                if (target != mTarget) {
                    detect();
                    return;
                }
                mDetection = detection;
                render();
            });
        });
    }

    private void onJobsChanged() {
        if (mDialog == null || !mDialog.isShowing()) return;
        Target target = mTarget;
        boolean ended = false;
        if (target != null) {
            for (TerminowToolInstaller.Tool tool : JOBS) {
                TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(target.key, target.user, tool);
                if (job != null && job.getState() != TerminowToolInstaller.State.RUNNING && !mSeenEnded.contains(job)) {
                    mSeenEnded.add(job);
                    ended = true;
                    if (job.getState() == TerminowToolInstaller.State.SUCCEEDED && !target.ssh && target.environment != null)
                        mLauncher.onToolInstalled(target.environment, tool);
                }
            }
        }
        // What is installed is found out again, never taken from the job alone
        if (ended) detect();
        else render();
    }

    /** Why tools can not be installed in a local environment, {@code null} if they can. */
    @Nullable
    private String unsupportedReason(@NonNull Target target) {
        if (target.ssh || target.environment == null) return null;
        TerminowLauncher.Environment environment = target.environment;
        if (environment.isLocal()) return null;
        TerminowLinuxEnv linux = mLauncher.linuxEnvOf(environment);
        if (linux == null) return mActivity.getString(R.string.terminow_install_unknown_env, environment.label);
        if (!"apt".equals(linux.packageManager()))
            return mActivity.getString(R.string.terminow_install_no_apt, linux.getTitle());
        return null;
    }

    private void render() {
        LinearLayout content = mContent;
        Target target = mTarget;
        if (content == null || target == null) return;
        content.removeAllViews();
        boolean local = !target.ssh;
        boolean noLinux = local && target.environment != null && target.environment.isLocal();
        TerminowLinuxEnv linux = local && !noLinux && target.environment != null ? mLauncher.linuxEnvOf(target.environment) : null;

        // The target, big: tools always go into exactly this one, as this user
        LinearLayout envCard = card(content);
        LinearLayout envRow = new LinearLayout(mActivity);
        envRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout envTexts = new LinearLayout(mActivity);
        envTexts.setOrientation(LinearLayout.VERTICAL);
        TextView envName = text(targetName(target, noLinux), 15, R.color.terminow_text);
        envName.setTypeface(Typeface.DEFAULT_BOLD);
        envTexts.addView(envName);
        if (!noLinux) {
            String detail = targetDetail(target, linux);
            if (!detail.isEmpty()) envTexts.addView(text(detail, 13, R.color.terminow_text_secondary));
        }
        envRow.addView(envTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // Changing the environment is only among the local Linux ones; an SSH tab is fixed to its host
        if (local) {
            List<TerminowLauncher.Environment> linuxEnvironments = new ArrayList<>();
            for (TerminowLauncher.Environment other : mLauncher.environments()) if (!other.isLocal()) linuxEnvironments.add(other);
            if (linuxEnvironments.size() > 1) envRow.addView(button(mActivity.getString(R.string.terminow_install_change), false, true, () -> {
                CharSequence[] names = new CharSequence[linuxEnvironments.size()];
                for (int i = 0; i < names.length; i++) names[i] = linuxEnvironments.get(i).label;
                new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setTitle(R.string.terminow_launcher_environment)
                    .setItems(names, (d, which) -> {
                        TerminowLauncher.Environment picked = linuxEnvironments.get(which);
                        select(Target.local(picked, mLauncher.getUser(picked), mLocalRunner));
                        detect();
                        render();
                    })
                    .show();
            }));
        }
        envCard.addView(envRow, matchWrap());

        if (noLinux) {
            // No Linux environment at all: tools of Ubuntu are not installed into TermiNow itself
            envCard.addView(note(mActivity.getString(R.string.terminow_install_no_linux)));
            envCard.addView(button(mActivity.getString(R.string.terminow_launcher_ubuntu_setup), true, false, () -> {
                dismiss();
                mLauncher.show();
            }));
            return;
        }
        String unsupported = unsupportedReason(target);
        if (unsupported != null) {
            envCard.addView(note(unsupported));
            return;
        }
        if (mDetection != null && mDetection.error != null) {
            TextView error = text(mActivity.getString(R.string.terminow_install_check_failed, mDetection.error), 13, R.color.terminow_mcp_warn);
            error.setPadding(0, dp(8), 0, 0);
            envCard.addView(error, matchWrap());
            envCard.addView(button(mActivity.getString(R.string.terminow_install_check_again), false, false, this::detect));
            // A host that can not be reached never installs anything locally instead
            if (target.ssh) return;
        }

        addFiltersBar(content, target);
        mList = new LinearLayout(mActivity);
        mList.setOrientation(LinearLayout.VERTICAL);
        content.addView(mList, matchWrap());
        populateList(target);

        TextView footer = note(mActivity.getString(mDetection != null && mDetection.isRoot() ? R.string.terminow_install_note_root :
            R.string.terminow_install_note_user, targetName(target, false), target.user));
        footer.setPadding(dp(4), dp(12), dp(4), 0);
        content.addView(footer, matchWrap());
    }

    /** Sticky search, category chips and the installed-only toggle; chips/toggle re-render, search re-lists. */
    private void addFiltersBar(@NonNull LinearLayout content, @NonNull Target target) {
        EditText search = new EditText(mActivity);
        search.setSingleLine(true);
        search.setHint(R.string.terminow_install_search_hint);
        search.setText(mSearch);
        search.setSelection(mSearch.length());
        search.setTextSize(14);
        search.setTextColor(color(R.color.terminow_text));
        search.setHintTextColor(color(R.color.terminow_text_secondary));
        search.setBackgroundResource(R.drawable.terminow_field);
        search.setPadding(dp(12), dp(10), dp(12), dp(10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { mSearch = s.toString(); populateList(target); }
        });
        LinearLayout.LayoutParams searchParams = matchWrap();
        searchParams.topMargin = dp(12);
        content.addView(search, searchParams);

        HorizontalScrollView chipScroll = new HorizontalScrollView(mActivity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(mActivity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        int[] cats = {CAT_ALL, CAT_AI, CAT_TERMINAL, CAT_DEVELOPER, CAT_PLUGINS};
        int[] labels = {R.string.terminow_install_cat_all, R.string.terminow_install_cat_ai,
            R.string.terminow_install_cat_terminal, R.string.terminow_install_cat_developer, R.string.terminow_install_cat_plugins};
        for (int i = 0; i < cats.length; i++) {
            int cat = cats[i];
            chips.addView(chip(mActivity.getString(labels[i]), mCategory == cat, () -> { mCategory = cat; render(); }));
        }
        chipScroll.addView(chips);
        LinearLayout.LayoutParams chipParams = matchWrap();
        chipParams.topMargin = dp(10);
        content.addView(chipScroll, chipParams);

        CheckBox installedOnly = new CheckBox(mActivity);
        installedOnly.setText(R.string.terminow_install_installed_only);
        installedOnly.setTextSize(13);
        installedOnly.setTextColor(color(R.color.terminow_text_secondary));
        installedOnly.setChecked(mInstalledOnly);
        installedOnly.setOnCheckedChangeListener((b, checked) -> { mInstalledOnly = checked; render(); });
        LinearLayout.LayoutParams toggleParams = matchWrap();
        toggleParams.topMargin = dp(4);
        content.addView(installedOnly, toggleParams);
    }

    /** Fill the list with the tools that pass the filters; a useful empty state when none do. */
    private void populateList(@NonNull Target target) {
        LinearLayout list = mList;
        if (list == null) return;
        list.removeAllViews();
        // Two columns when the window is wide (a tablet or an unfolded foldable), one on a phone.
        boolean two = mActivity.getResources().getConfiguration().screenWidthDp >= 720;
        mPairRow = null;
        mPairCol = 0;
        TerminowToolInstaller.Job running = TerminowToolInstaller.runningIn(target.key);
        int shown = 0;
        // The Local AI Bridge for this environment: the same sheet the toolbar opens, reusing the same
        // services (no second helper per tab). Only for a local environment, under All / AI.
        if (!target.ssh && target.environment != null && (mCategory == CAT_ALL || mCategory == CAT_AI) && !mInstalledOnly
                && matchesSearch(mActivity.getString(R.string.terminow_bridge_tool_name),
                    mActivity.getString(R.string.terminow_bridge_tool_desc))) {
            bridgeRow(nextColumn(list, two), target);
            shown++;
        }
        for (TerminowToolInstaller.Tool tool : TOOLS) {
            if (!matchesFilters(tool)) continue;
            toolRow(nextColumn(list, two), target, tool, running);
            shown++;
        }
        if ((mCategory == CAT_ALL || mCategory == CAT_PLUGINS) && !mInstalledOnly
                && matchesSearch(mActivity.getString(R.string.terminow_install_plugins),
                    mActivity.getString(R.string.terminow_install_plugins_description))) {
            pluginsRow(nextColumn(list, two), target, running);
            shown++;
        }
        if (shown == 0) addEmptyState(list);
    }

    /** The "TermiNow Bridge" entry: opens the shared bridge sheet for this local environment. */
    private void bridgeRow(@NonNull LinearLayout parent, @NonNull Target target) {
        if (target.ssh || target.environment == null) return;
        final String envKey = target.environment.key;
        final String envLabel = target.environment.label;
        final String user = target.user;
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(color(R.color.terminow_field));
        card.setBackground(bg);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> {
            TerminowHome home = mActivity.getTerminowHome();
            if (home == null) return;
            new TerminowAiBridgeSheet(mActivity, home.getAiBridge(), home.getStore())
                .open(envKey, user, envLabel, mActivity.getString(R.string.terminow_install_title), "", "");
        });
        TextView name = text(mActivity.getString(R.string.terminow_bridge_tool_name), 15, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(name);
        card.addView(text(mActivity.getString(R.string.terminow_bridge_tool_desc), 13, R.color.terminow_text_secondary));
        LinearLayout.LayoutParams lp = matchWrap();
        lp.bottomMargin = dp(8);
        parent.addView(card, lp);
    }

    @Nullable private LinearLayout mPairRow;
    private int mPairCol;

    /** The parent to add the next card to: the list itself in one column, or a cell of a pair in two. */
    @NonNull
    private LinearLayout nextColumn(@NonNull LinearLayout list, boolean two) {
        if (!two) return list;
        if (mPairCol == 0) {
            mPairRow = new LinearLayout(mActivity);
            mPairRow.setOrientation(LinearLayout.HORIZONTAL);
            list.addView(mPairRow, matchWrap());
        }
        LinearLayout cell = new LinearLayout(mActivity);
        cell.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (mPairCol == 0) lp.rightMargin = dp(5); else lp.leftMargin = dp(5);
        mPairRow.addView(cell, lp);
        mPairCol = (mPairCol + 1) % 2;
        return cell;
    }

    @NonNull
    private TextView chip(@NonNull String label, boolean selected, @NonNull Runnable onClick) {
        TextView chip = text(label, 13, selected ? R.color.terminow_text : R.color.terminow_text_secondary);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setMinHeight(dp(40));
        chip.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(color(selected ? R.color.terminow_panel_accent : R.color.terminow_field));
        chip.setBackground(bg);
        chip.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = dp(8);
        chip.setLayoutParams(p);
        return chip;
    }

    private void addEmptyState(@NonNull LinearLayout list) {
        TextView empty = text(mActivity.getString(R.string.terminow_install_empty), 14, R.color.terminow_text_secondary);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(28), 0, dp(10));
        list.addView(empty, matchWrap());
        LinearLayout wrap = new LinearLayout(mActivity);
        wrap.setGravity(Gravity.CENTER);
        wrap.addView(button(mActivity.getString(R.string.terminow_install_clear_filters), false, true, () -> {
            mSearch = "";
            mCategory = CAT_ALL;
            mInstalledOnly = false;
            render();
        }));
        list.addView(wrap, matchWrap());
    }

    @NonNull
    private String targetName(@NonNull Target target, boolean noLinux) {
        if (target.ssh && target.host != null) return target.host.getTitle();
        if (noLinux) return mActivity.getString(R.string.terminow_install_no_linux_title);
        return target.environment != null ? target.environment.shortName() : "";
    }

    /** The line under the name: "SSH · root@host · aarch64 · Ubuntu" for a host, user/arch/OS for a local one. */
    @NonNull
    private String targetDetail(@NonNull Target target, @Nullable TerminowLinuxEnv linux) {
        String arch = mDetection != null && !mDetection.arch.isEmpty() ? mDetection.arch :
            linux != null ? linux.arch : "";
        String os = mDetection != null && !mDetection.os.isEmpty() ? mDetection.os :
            linux != null ? linux.getTitle() : "";
        if (target.ssh && target.host != null) {
            StringBuilder detail = new StringBuilder(mActivity.getString(R.string.terminow_install_ssh_label, hostTarget(target.host)));
            if (!arch.isEmpty()) detail.append(" · ").append(arch);
            if (!os.isEmpty()) detail.append(" · ").append(os);
            return detail.toString();
        }
        return mActivity.getString(R.string.terminow_install_env_details, target.user, arch.isEmpty() ? "?" : arch,
            os.isEmpty() ? (target.environment != null ? target.environment.label : "") : os);
    }

    @NonNull
    private static String hostTarget(@NonNull TerminowStore.Host host) {
        return (host.username.isEmpty() ? "" : host.username + "@") + host.address;
    }

    private void toolRow(@NonNull LinearLayout content, @NonNull Target target,
                         @NonNull TerminowToolInstaller.Tool tool, @Nullable TerminowToolInstaller.Job running) {
        LinearLayout card = card(content);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconOf(tool));
        icon.setContentDescription(nameOf(tool));
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(color(R.color.terminow_field));
        tile.setCornerRadius(dp(12));
        icon.setBackground(tile);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(nameOf(tool), 16, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        TextView description = text(descriptionOf(tool), 12, R.color.terminow_text_secondary);
        texts.addView(description);
        TextView status = text("", 13, R.color.terminow_text_secondary);
        status.setPadding(0, dp(4), 0, 0);
        texts.addView(status);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, matchWrap());
        // Tapping the row opens the tool's details; the trailing button keeps its own action.
        card.setOnClickListener(v -> toolDetailsDialog(target, tool));

        TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(target.key, target.user, tool);
        String version = mDetection != null ? mDetection.versions.get(tool) : null;
        String arch = mDetection != null ? mDetection.arch : "";
        if (job != null && job.getState() == TerminowToolInstaller.State.RUNNING) {
            // Its stage, as the script says it: no percentage that nobody knows
            int step = job.getStage().ordinal() + 1;
            status.setText(mActivity.getString(R.string.terminow_install_running, step, TerminowToolInstaller.Stage.values().length, stageName(job.getStage())));
            status.setTextColor(color(R.color.terminow_panel_accent));
            ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
            LinearLayout.LayoutParams barParams = matchWrap();
            barParams.topMargin = dp(8);
            card.addView(bar, barParams);
            String last = job.getLogTail(1);
            if (!last.isEmpty()) {
                TextView line = mono(last);
                line.setSingleLine(true);
                line.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(line, matchWrap());
            }
            // Hide closes the sheet while the install keeps running; Cancel stops it.
            row.addView(button(mActivity.getString(R.string.terminow_install_hide), false, true, this::dismiss));
            row.addView(button(mActivity.getString(R.string.terminow_install_cancel), false, true, job::cancel));
            return;
        }
        if (mDetection == null) {
            status.setText(R.string.terminow_install_checking);
            ProgressBar spinner = new ProgressBar(mActivity);
            row.addView(spinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
            return;
        }
        boolean failed = job != null && (job.getState() == TerminowToolInstaller.State.FAILED
            || job.getState() == TerminowToolInstaller.State.UNKNOWN) && version == null;
        if (version != null) {
            status.setText(mActivity.getString(R.string.terminow_install_installed, version));
            status.setTextColor(color(R.color.terminow_quick_ready));
            if (tool == TerminowToolInstaller.Tool.FISH) {
                fishInstalled(row, status, target, version);
                return;
            }
            if (tool == TerminowToolInstaller.Tool.GIT || tool == TerminowToolInstaller.Tool.CURL) {
                // Git and curl are libraries, not apps to open: show their details instead of Open.
                row.addView(button(mActivity.getString(R.string.terminow_install_details), false, true,
                    () -> toolDetailsDialog(target, tool)));
                return;
            }
            boolean claude = tool == TerminowToolInstaller.Tool.CLAUDE || tool == TerminowToolInstaller.Tool.GSTACK;
            String open = claude ? mActivity.getString(R.string.terminow_install_open_claude) : mActivity.getString(R.string.terminow_install_open);
            row.addView(button(open, tool == TerminowToolInstaller.Tool.CLAUDE, true, () -> openTool(target, tool)));
            return;
        }
        if (tool != TerminowToolInstaller.Tool.GIT && tool != TerminowToolInstaller.Tool.CURL && tool != TerminowToolInstaller.Tool.TMUX &&
            tool != TerminowToolInstaller.Tool.FISH &&
            !arch.isEmpty() && !TerminowToolInstaller.claudeSupports(arch)) {
            status.setText(mActivity.getString(R.string.terminow_install_unsupported_arch, nameOf(tool), arch));
            status.setTextColor(color(R.color.terminow_mcp_warn));
            return;
        }
        if (failed) {
            boolean unknown = job.getState() == TerminowToolInstaller.State.UNKNOWN;
            status.setText(unknown ? mActivity.getString(R.string.terminow_install_unknown)
                : mActivity.getString(R.string.terminow_install_failed, stageName(job.getStage()), failureText(job)));
            status.setTextColor(color(R.color.terminow_mcp_warn));
            boolean open = mLogOpen.contains(tool);
            String log = open ? job.getLog().trim() : job.getLogTail(8);
            if (!log.isEmpty()) {
                TextView logView = mono(log);
                logView.setTextIsSelectable(true);
                card.addView(logView, matchWrap());
                TextView toggle = text(mActivity.getString(open ? R.string.terminow_install_log_less : R.string.terminow_install_log_all), 13,
                    R.color.terminow_panel_accent);
                toggle.setPadding(0, dp(6), 0, 0);
                toggle.setOnClickListener(v -> {
                    if (!mLogOpen.remove(tool)) mLogOpen.add(tool);
                    render();
                });
                card.addView(toggle);
            }
        } else if (TerminowToolInstaller.experimental(tool)) {
            status.setText(R.string.terminow_install_experimental);
            status.setTextColor(color(R.color.terminow_panel_accent));
        } else {
            status.setText(R.string.terminow_install_not_installed);
        }
        // Fish of local Ubuntu is installed and made the shell of new tabs in one go; on a host it is only installed
        int label = failed ? R.string.terminow_install_retry :
            tool == TerminowToolInstaller.Tool.FISH && !target.ssh ? R.string.terminow_install_fish_enable : R.string.terminow_install_install;
        View action = button(mActivity.getString(label), true, true, () -> install(target, tool));
        if (running != null) {
            // apt runs once at a time in a target
            action.setEnabled(false);
            action.setAlpha(0.4f);
            status.setText(mActivity.getString(R.string.terminow_install_waiting, nameOf(running.tool)));
        }
        row.addView(action);
    }

    /** The plugins of the official marketplace: chosen in the picker, installed as one job. */
    private void pluginsRow(@NonNull LinearLayout content, @NonNull Target target,
                            @Nullable TerminowToolInstaller.Job running) {
        TerminowToolInstaller.Tool tool = TerminowToolInstaller.Tool.PLUGINS;
        LinearLayout card = card(content);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconOf(tool));
        icon.setContentDescription(nameOf(tool));
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(color(R.color.terminow_field));
        tile.setCornerRadius(dp(12));
        icon.setBackground(tile);
        row.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(nameOf(tool), 16, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        texts.addView(text(descriptionOf(tool), 12, R.color.terminow_text_secondary));
        TextView status = text("", 13, R.color.terminow_text_secondary);
        status.setPadding(0, dp(4), 0, 0);
        texts.addView(status);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, matchWrap());

        TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(target.key, target.user, tool);
        if (job != null && job.getState() == TerminowToolInstaller.State.RUNNING) {
            status.setText(mActivity.getString(R.string.terminow_install_running, job.getStage().ordinal() + 1,
                TerminowToolInstaller.Stage.values().length, stageName(job.getStage())));
            status.setTextColor(color(R.color.terminow_panel_accent));
            ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
            LinearLayout.LayoutParams barParams = matchWrap();
            barParams.topMargin = dp(8);
            card.addView(bar, barParams);
            String last = job.getLogTail(1);
            if (!last.isEmpty()) {
                TextView line = mono(last);
                line.setSingleLine(true);
                line.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(line, matchWrap());
            }
            // Hide closes the sheet while the install keeps running; Cancel stops it.
            row.addView(button(mActivity.getString(R.string.terminow_install_hide), false, true, this::dismiss));
            row.addView(button(mActivity.getString(R.string.terminow_install_cancel), false, true, job::cancel));
            return;
        }
        if (mDetection == null) {
            status.setText(R.string.terminow_install_checking);
            return;
        }
        boolean hasClaude = mDetection.versions.get(TerminowToolInstaller.Tool.CLAUDE) != null;
        if (job != null && (job.getState() == TerminowToolInstaller.State.FAILED || job.getState() == TerminowToolInstaller.State.UNKNOWN)) {
            status.setText(job.getState() == TerminowToolInstaller.State.UNKNOWN ? mActivity.getString(R.string.terminow_install_unknown)
                : mActivity.getString(R.string.terminow_install_failed, stageName(job.getStage()), failureText(job)));
            status.setTextColor(color(R.color.terminow_mcp_warn));
            boolean open = mLogOpen.contains(tool);
            String log = open ? job.getLog().trim() : job.getLogTail(8);
            if (!log.isEmpty()) {
                TextView logView = mono(log);
                logView.setTextIsSelectable(true);
                card.addView(logView, matchWrap());
                TextView toggle = text(mActivity.getString(open ? R.string.terminow_install_log_less : R.string.terminow_install_log_all), 13,
                    R.color.terminow_panel_accent);
                toggle.setPadding(0, dp(6), 0, 0);
                toggle.setOnClickListener(v -> {
                    if (!mLogOpen.remove(tool)) mLogOpen.add(tool);
                    render();
                });
                card.addView(toggle);
            }
        } else if (job != null && job.getState() == TerminowToolInstaller.State.SUCCEEDED) {
            status.setText(mActivity.getString(R.string.terminow_install_plugins_done, job.getVersion()));
            status.setTextColor(color(R.color.terminow_quick_ready));
        } else if (!hasClaude) {
            status.setText(R.string.terminow_install_plugins_needs_claude);
        }
        View action = button(mActivity.getString(R.string.terminow_install_plugins_choose), hasClaude, true, () ->
            new TerminowPluginPicker(mActivity, target.runner, target.key, target.user, names -> {
                TerminowToolInstaller.Job started = TerminowToolInstaller.install(target.runner, target.key, target.user, tool, names);
                if (started == null) {
                    TerminowToolInstaller.Job other = TerminowToolInstaller.runningIn(target.key);
                    mActivity.showToast(mActivity.getString(R.string.terminow_install_waiting, other != null ? nameOf(other.tool) : ""), false);
                }
                mLogOpen.remove(tool);
                render();
            }).show());
        if (!hasClaude || running != null) {
            action.setEnabled(false);
            action.setAlpha(0.4f);
            if (running != null && hasClaude) status.setText(mActivity.getString(R.string.terminow_install_waiting, nameOf(running.tool)));
        }
        row.addView(action);
    }

    private void install(@NonNull Target target, @NonNull TerminowToolInstaller.Tool tool) {
        TerminowToolInstaller.Job job = TerminowToolInstaller.install(target.runner, target.key, target.user, tool);
        if (job == null) {
            TerminowToolInstaller.Job running = TerminowToolInstaller.runningIn(target.key);
            mActivity.showToast(mActivity.getString(R.string.terminow_install_waiting, running != null ? nameOf(running.tool) : ""), false);
        }
        mLogOpen.remove(tool);
        render();
    }

    /** Open a tool: a local one in a new tab, a tool of a host in the tab the host is in. */
    private void openTool(@NonNull Target target, @NonNull TerminowToolInstaller.Tool tool) {
        dismiss();
        boolean claude = tool == TerminowToolInstaller.Tool.CLAUDE || tool == TerminowToolInstaller.Tool.GSTACK;
        // A CLI with its own launch command typed at the prompt (Antigravity's agy); null just opens a shell.
        String launch = tool == TerminowToolInstaller.Tool.ANTIGRAVITY ? "agy" : null;
        if (target.ssh) {
            openInSshSession(claude ? "claude" : launch);
            return;
        }
        if (target.environment == null) return;
        if (claude) mLauncher.openClaudeWithSignInHelp(target.environment, target.user);
        else if (launch != null) mLauncher.openTerminalRunning(target.environment, launch);
        else mLauncher.openTerminal(target.environment);
    }

    /** Switch to the SSH tab and, for a tool, type its command at its prompt. */
    private void openInSshSession(@Nullable String command) {
        TerminalSession session = mTarget != null ? mTarget.session : null;
        if (session == null || !session.isRunning()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_install_ssh_tab_gone), true);
            return;
        }
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
        if (command != null) session.write(command + "\r");
    }

    /**
     * The full details of a tool: its logo and description, its installation and authentication state
     * (shown apart), compatibility, dependencies, documentation, the target, and its primary action
     * with the installation log. Opened by tapping a row.
     */
    private void toolDetailsDialog(@NonNull Target target, @NonNull TerminowToolInstaller.Tool tool) {
        String version = mDetection != null ? mDetection.versions.get(tool) : null;
        String arch = mDetection != null ? mDetection.arch : "";
        String os = mDetection != null ? mDetection.os : "";

        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(8));

        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconOf(tool));
        icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable tile = new GradientDrawable();
        tile.setColor(color(R.color.terminow_field));
        tile.setCornerRadius(dp(12));
        icon.setBackground(tile);
        head.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView name = text(nameOf(tool), 18, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setPadding(dp(12), 0, 0, 0);
        head.addView(name);
        box.addView(head, matchWrap());
        TextView desc = text(descriptionOf(tool), 13, R.color.terminow_text_secondary);
        desc.setPadding(0, dp(8), 0, 0);
        box.addView(desc);

        detailSection(box, R.string.terminow_install_detail_installation,
            version != null ? mActivity.getString(R.string.terminow_install_installed, version)
                : TerminowToolInstaller.experimental(tool) ? mActivity.getString(R.string.terminow_install_experimental)
                : mActivity.getString(R.string.terminow_install_not_installed));
        if (categoryOf(tool) == CAT_AI)
            detailSection(box, R.string.terminow_install_detail_auth, mActivity.getString(R.string.terminow_install_detail_auth_note));
        if (!arch.isEmpty())
            detailSection(box, R.string.terminow_install_detail_compat, arch + " · " + mActivity.getString(
                TerminowToolInstaller.claudeSupports(arch) ? R.string.terminow_install_detail_supported
                    : R.string.terminow_install_detail_unsupported));
        String deps = depsOf(tool);
        if (!deps.isEmpty()) detailSection(box, R.string.terminow_install_detail_deps, deps);
        detailSection(box, R.string.terminow_install_detail_target, mActivity.getString(
            R.string.terminow_install_env_details, target.user, arch.isEmpty() ? "?" : arch, os.isEmpty() ? "?" : os));

        String docs = docsOf(tool);
        if (!docs.isEmpty()) {
            TextView link = text(mActivity.getString(R.string.terminow_install_detail_docs), 14, R.color.terminow_panel_accent);
            link.setPadding(0, dp(14), 0, 0);
            link.setOnClickListener(v -> openUrl(docs));
            box.addView(link);
        }
        TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(target.key, target.user, tool);
        if (job != null && !job.getLog().trim().isEmpty()) {
            TextView log = text(mActivity.getString(R.string.terminow_install_detail_log), 14, R.color.terminow_panel_accent);
            log.setPadding(0, dp(10), 0, 0);
            log.setOnClickListener(v -> new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(nameOf(tool)).setMessage(job.getLog().trim()).setPositiveButton(android.R.string.ok, null).show());
            box.addView(log);
        }

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(box);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setView(scroll)
            .setNegativeButton(android.R.string.ok, null);
        boolean canInstall = tool != TerminowToolInstaller.Tool.PLUGINS
            && (arch.isEmpty() || TerminowToolInstaller.claudeSupports(arch) || categoryOf(tool) == CAT_TERMINAL || categoryOf(tool) == CAT_DEVELOPER);
        if (version != null && categoryOf(tool) == CAT_AI) {
            b.setPositiveButton(R.string.terminow_install_open, (d, w) -> openTool(target, tool));
        } else if (version == null && canInstall) {
            b.setPositiveButton(R.string.terminow_install_install, (d, w) -> install(target, tool));
        }
        b.show();
    }

    private void detailSection(@NonNull LinearLayout box, int labelRes, @NonNull String value) {
        TextView label = text(mActivity.getString(labelRes), 12, R.color.terminow_text_secondary);
        label.setPadding(0, dp(12), 0, dp(2));
        box.addView(label);
        box.addView(text(value, 14, R.color.terminow_text));
    }

    private void openUrl(@NonNull String url) {
        try {
            mActivity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (RuntimeException ignored) {
            mActivity.showToast(url, false);
        }
    }

    @NonNull
    private static String docsOf(@NonNull TerminowToolInstaller.Tool tool) {
        switch (tool) {
            case CLAUDE: return "https://docs.claude.com/en/docs/claude-code/overview";
            case CURSOR: return "https://docs.cursor.com/en/cli/overview";
            case CODEX: return "https://github.com/openai/codex";
            case GEMINI: return "https://github.com/google-gemini/gemini-cli";
            case GROK: return "https://github.com/superagent-ai/grok-cli";
            case OPENCODE: return "https://opencode.ai/docs";
            case AIDER: return "https://aider.chat/docs/install.html";
            case HERMES: return "https://hermes-agent.nousresearch.com/docs";
            case ANTIGRAVITY: return "https://antigravity.google/docs/getting-started";
            case GSTACK: return "https://github.com/garrytan/gstack";
            case GIT: return "https://git-scm.com/doc";
            case CURL: return "https://curl.se/docs/";
            case TMUX: return "https://github.com/tmux/tmux/wiki";
            case FISH: return "https://fishshell.com/docs/current/";
            default: return "";
        }
    }

    @NonNull
    private String depsOf(@NonNull TerminowToolInstaller.Tool tool) {
        switch (tool) {
            case CODEX: case GEMINI: case GROK: return "Node.js, npm";
            case OPENCODE: return "curl";
            case AIDER: return "Python 3, pip";
            case GSTACK: return "Claude Code, Bun, git";
            default: return "";
        }
    }

    void dismiss() {
        if (mDialog != null) mDialog.dismiss();
    }

    @NonNull
    private String failureText(@NonNull TerminowToolInstaller.Job job) {
        TerminowToolInstaller.Failure failure = job.getFailure();
        if (failure == null) failure = TerminowToolInstaller.Failure.EXIT;
        switch (failure) {
            case NO_APT: return mActivity.getString(R.string.terminow_install_fail_no_apt);
            case NO_SUDO: return mActivity.getString(R.string.terminow_install_fail_no_sudo, job.user);
            case SUDO_PASSWORD: return mActivity.getString(R.string.terminow_install_fail_sudo_password, job.user);
            case UNSUPPORTED_ARCH: return mActivity.getString(R.string.terminow_install_fail_arch);
            case NEEDS_CLAUDE: return mActivity.getString(R.string.terminow_install_fail_needs_claude);
            case APT_UPDATE: return mActivity.getString(R.string.terminow_install_fail_apt_update);
            case APT_INSTALL: return mActivity.getString(R.string.terminow_install_fail_apt_install);
            case NETWORK: return mActivity.getString(R.string.terminow_install_fail_network);
            case INSTALLER: return mActivity.getString(R.string.terminow_install_fail_installer, job.getExitCode());
            case PATH: return mActivity.getString(R.string.terminow_install_fail_path);
            case VERIFY: return mActivity.getString(R.string.terminow_install_fail_verify);
            case CANCELLED: return mActivity.getString(R.string.terminow_install_fail_cancelled);
            case START: return mActivity.getString(R.string.terminow_install_fail_start);
            default: return mActivity.getString(R.string.terminow_install_fail_exit, job.getExitCode());
        }
    }

    @NonNull
    private String stageName(@NonNull TerminowToolInstaller.Stage stage) {
        switch (stage) {
            case DEPENDENCIES: return mActivity.getString(R.string.terminow_install_stage_dependencies);
            case INSTALLING: return mActivity.getString(R.string.terminow_install_stage_installing);
            case VERIFYING: return mActivity.getString(R.string.terminow_install_stage_verifying);
            default: return mActivity.getString(R.string.terminow_install_stage_checking);
        }
    }

    /**
     * Fish that is installed. In local Ubuntu it can become the shell of new tabs; on a host, or
     * another local environment, it is started in the tab it belongs to. Tabs that are open keep
     * the shell they have.
     */
    private void fishInstalled(@NonNull LinearLayout row, @NonNull TextView status, @NonNull Target target,
                               @NonNull String version) {
        if (target.ssh) {
            // Start Fish only in the SSH tab; the login shell of the server is a separate choice of the user
            row.addView(button(mActivity.getString(R.string.terminow_install_ssh_start_fish), true, true, () -> {
                dismiss();
                openInSshSession("fish");
            }));
            return;
        }
        TerminowLauncher.Environment environment = target.environment;
        boolean ubuntu = environment != null && mLauncher.isUbuntuEnvironment(environment);
        boolean enabled = ubuntu && TerminowStore.UBUNTU_SHELL_FISH.equals(mLauncher.getStore().getUbuntuShell());
        status.setText(mActivity.getString(enabled ? R.string.terminow_install_fish_enabled : R.string.terminow_install_installed, version));
        if (!ubuntu || environment == null) return;
        if (enabled) {
            row.addView(button(mActivity.getString(R.string.terminow_install_open), true, true, () -> {
                dismiss();
                mLauncher.openNewTerminal(environment);
            }));
        } else {
            row.addView(button(mActivity.getString(R.string.terminow_install_fish_enable_only), true, true, () -> {
                mLauncher.enableFish();
                render();
            }));
        }
    }

    @NonNull
    private String nameOf(@NonNull TerminowToolInstaller.Tool tool) {
        switch (tool) {
            case CLAUDE: return mActivity.getString(R.string.terminow_install_claude);
            case CURSOR: return mActivity.getString(R.string.terminow_install_cursor);
            case CODEX: return "Codex CLI";
            case GEMINI: return "Gemini CLI";
            case GROK: return "Grok CLI";
            case OPENCODE: return "OpenCode";
            case AIDER: return "Aider";
            case HERMES: return "Hermes Agent";
            case ANTIGRAVITY: return "Antigravity CLI";
            case GSTACK: return mActivity.getString(R.string.terminow_install_gstack);
            case PLUGINS: return mActivity.getString(R.string.terminow_install_plugins);
            case GIT: return "Git";
            case CURL: return "curl";
            case FISH: return mActivity.getString(R.string.terminow_install_fish);
            default: return "tmux";
        }
    }

    @NonNull
    private String descriptionOf(@NonNull TerminowToolInstaller.Tool tool) {
        switch (tool) {
            case CLAUDE: return mActivity.getString(R.string.terminow_install_claude_description);
            case CURSOR: return mActivity.getString(R.string.terminow_install_cursor_description);
            case CODEX: return mActivity.getString(R.string.terminow_install_codex_description);
            case GEMINI: return mActivity.getString(R.string.terminow_install_gemini_description);
            case GROK: return mActivity.getString(R.string.terminow_install_grok_description);
            case OPENCODE: return mActivity.getString(R.string.terminow_install_opencode_description);
            case AIDER: return mActivity.getString(R.string.terminow_install_aider_description);
            case HERMES: return mActivity.getString(R.string.terminow_install_hermes_description);
            case ANTIGRAVITY: return mActivity.getString(R.string.terminow_install_antigravity_description);
            case GSTACK: return mActivity.getString(R.string.terminow_install_gstack_description);
            case PLUGINS: return mActivity.getString(R.string.terminow_install_plugins_description);
            case GIT: return mActivity.getString(R.string.terminow_install_git_description);
            case CURL: return mActivity.getString(R.string.terminow_install_curl_description);
            case FISH: return mActivity.getString(R.string.terminow_install_fish_description);
            default: return mActivity.getString(R.string.terminow_install_tmux_description);
        }
    }

    private static int iconOf(@NonNull TerminowToolInstaller.Tool tool) {
        switch (tool) {
            case CLAUDE: return R.drawable.ic_terminow_tool_claude;
            case CURSOR: return R.drawable.ic_terminow_tool_cursor;
            case CODEX: return R.drawable.ic_terminow_tool_codex;
            case GEMINI: return R.drawable.ic_terminow_tool_gemini;
            case GROK: return R.drawable.ic_terminow_tool_grok;
            case OPENCODE: return R.drawable.ic_terminow_tool_opencode;
            case AIDER: return R.drawable.ic_terminow_tool_aider;
            case HERMES: return R.drawable.ic_terminow_tool_hermes;
            case ANTIGRAVITY: return R.drawable.ic_terminow_tool_antigravity;
            case GSTACK: return R.drawable.ic_terminow_tool_gstack;
            case PLUGINS: return R.drawable.ic_terminow_tool_plugins;
            case GIT: return R.drawable.ic_terminow_tool_git;
            case CURL: return R.drawable.ic_terminow_tool_curl;
            case FISH: return R.drawable.ic_terminow_tool_fish;
            default: return R.drawable.ic_terminow_tool_tmux;
        }
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
    private View button(@NonNull String label, boolean primary, boolean inRow, @NonNull Runnable onClick) {
        TextView button = text(label, 14, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setMinHeight(dp(40));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_field));
        shape.setCornerRadius(dp(20));
        button.setBackground(shape);
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        if (!inRow) params.topMargin = dp(10);
        button.setLayoutParams(params);
        return button;
    }

    @NonNull
    private TextView note(@NonNull String value) {
        TextView view = text(value, 13, R.color.terminow_text_secondary);
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }

    @NonNull
    private TextView mono(@NonNull String value) {
        TextView view = text(value, 11, R.color.terminow_text_secondary);
        view.setTypeface(Typeface.MONOSPACE);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_field));
        shape.setCornerRadius(dp(8));
        view.setBackground(shape);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        view.setLayoutParams(params);
        return view;
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
