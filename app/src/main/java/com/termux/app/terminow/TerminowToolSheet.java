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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Install tools sheet of the terminal toolbar: Claude Code, Cursor CLI, gstack, Git, curl
 * and tmux in the environment of the tab, as its user, with their real state, and plugins of the
 * official Claude Code marketplace, chosen in {@link TerminowPluginPicker}. Installing is done by
 * {@link TerminowToolInstaller}, whose jobs outlive this sheet; the sheet only shows them, and
 * finds out again what is installed when it opens and after each install ends.
 */
final class TerminowToolSheet {

    private static final TerminowToolInstaller.Tool[] TOOLS = {
        TerminowToolInstaller.Tool.CLAUDE, TerminowToolInstaller.Tool.CURSOR, TerminowToolInstaller.Tool.GSTACK,
        TerminowToolInstaller.Tool.GIT, TerminowToolInstaller.Tool.CURL, TerminowToolInstaller.Tool.TMUX, TerminowToolInstaller.Tool.FISH};

    /** The tools and the plugins: the jobs that the sheet shows. */
    private static final TerminowToolInstaller.Tool[] JOBS = {
        TerminowToolInstaller.Tool.CLAUDE, TerminowToolInstaller.Tool.CURSOR, TerminowToolInstaller.Tool.GSTACK,
        TerminowToolInstaller.Tool.GIT, TerminowToolInstaller.Tool.CURL, TerminowToolInstaller.Tool.TMUX,
        TerminowToolInstaller.Tool.FISH, TerminowToolInstaller.Tool.PLUGINS};

    private final TermuxActivity mActivity;
    private final TerminowLauncher mLauncher;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final TerminowToolInstaller.Runner mRunner;

    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    @Nullable private TerminowLauncher.Environment mEnvironment;
    private String mUser = "root";
    /** What the environment has, {@code null} while it is being checked. */
    @Nullable private TerminowToolInstaller.Detection mDetection;
    private boolean mDetecting;
    /** The jobs whose end was seen, so their environment is checked again only once each. */
    private final List<TerminowToolInstaller.Job> mSeenEnded = new ArrayList<>();
    /** The tools whose log is shown in full. */
    private final java.util.Set<TerminowToolInstaller.Tool> mLogOpen = java.util.EnumSet.noneOf(TerminowToolInstaller.Tool.class);

    private final Runnable mOnJobs = () -> mHandler.post(this::onJobsChanged);

    TerminowToolSheet(@NonNull TermuxActivity activity, @NonNull TerminowLauncher launcher, @NonNull File prefixDir) {
        mActivity = activity;
        mLauncher = launcher;
        TerminowClaudeSetup setup = new TerminowClaudeSetup(activity, new TerminowMcpTargets(prefixDir,
            new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH)));
        mRunner = (alias, user, script, args) -> setup.start(TerminowToolInstaller.command(prefixDir, alias, user, script, args));
    }

    /**
     * Open the sheet for the environment, or the first Linux one when it is TermiNow itself,
     * as the user of the tab, or else the user chosen for the environment.
     */
    void show(@NonNull TerminowLauncher.Environment environment, @Nullable String user) {
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        select(environment);
        if (user != null && mEnvironment == environment) mUser = user;
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
        sheet.addView(title, matchWrap());

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
            int width = Math.min(right - left, dp(640));
            int maxHeight = Math.round((bottom - top) * 0.85f);
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

    private void select(@NonNull TerminowLauncher.Environment environment) {
        TerminowLauncher.Environment chosen = environment;
        if (chosen.isLocal()) {
            // Tools go into a Linux environment: the first one, if there is one
            for (TerminowLauncher.Environment other : mLauncher.environments())
                if (!other.isLocal()) {
                    chosen = other;
                    break;
                }
        }
        mEnvironment = chosen;
        mUser = mLauncher.getUser(chosen);
        mDetection = null;
        mSeenEnded.clear();
        for (TerminowToolInstaller.Tool tool : JOBS) {
            TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(chosen.key, mUser, tool);
            if (job != null && job.getState() != TerminowToolInstaller.State.RUNNING) mSeenEnded.add(job);
        }
    }

    /** Find out what is installed, in the background; never while it is being found out already. */
    private void detect() {
        TerminowLauncher.Environment environment = mEnvironment;
        if (environment == null || environment.isLocal() || unsupportedReason(environment) != null || mDetecting) return;
        mDetecting = true;
        mDetection = null;
        String user = mUser;
        render();
        mExecutor.execute(() -> {
            TerminowToolInstaller.Detection detection = TerminowToolInstaller.detect(mRunner, environment.key, user);
            mHandler.post(() -> {
                mDetecting = false;
                if (environment != mEnvironment || !user.equals(mUser)) {
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
        TerminowLauncher.Environment environment = mEnvironment;
        boolean ended = false;
        if (environment != null) {
            for (TerminowToolInstaller.Tool tool : JOBS) {
                TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(environment.key, mUser, tool);
                if (job != null && job.getState() != TerminowToolInstaller.State.RUNNING && !mSeenEnded.contains(job)) {
                    mSeenEnded.add(job);
                    ended = true;
                    if (job.getState() == TerminowToolInstaller.State.SUCCEEDED) mLauncher.onToolInstalled(environment, tool);
                }
            }
        }
        // What is installed is found out again, never taken from the job alone
        if (ended) detect();
        else render();
    }

    /** Why tools can not be installed in the environment, {@code null} if they can. */
    @Nullable
    private String unsupportedReason(@NonNull TerminowLauncher.Environment environment) {
        if (environment.isLocal()) return null;
        TerminowLinuxEnv linux = mLauncher.linuxEnvOf(environment);
        if (linux == null) return mActivity.getString(R.string.terminow_install_unknown_env, environment.label);
        if (!"apt".equals(linux.packageManager()))
            return mActivity.getString(R.string.terminow_install_no_apt, linux.getTitle());
        return null;
    }

    private void render() {
        LinearLayout content = mContent;
        TerminowLauncher.Environment environment = mEnvironment;
        if (content == null || environment == null) return;
        content.removeAllViews();
        TerminowLinuxEnv linux = environment.isLocal() ? null : mLauncher.linuxEnvOf(environment);

        // The environment, big: tools always go into exactly this one, as this user
        LinearLayout envCard = card(content);
        LinearLayout envRow = new LinearLayout(mActivity);
        envRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout envTexts = new LinearLayout(mActivity);
        envTexts.setOrientation(LinearLayout.VERTICAL);
        TextView envName = text(environment.isLocal() ? mActivity.getString(R.string.terminow_install_no_linux_title) : environment.shortName(),
            22, R.color.terminow_text);
        envName.setTypeface(Typeface.DEFAULT_BOLD);
        envTexts.addView(envName);
        if (!environment.isLocal()) {
            String arch = mDetection != null && !mDetection.arch.isEmpty() ? mDetection.arch : linux != null ? linux.arch : "";
            String version = linux != null ? linux.getTitle() : environment.label;
            envTexts.addView(text(mActivity.getString(R.string.terminow_install_env_details, mUser, arch.isEmpty() ? "?" : arch, version),
                13, R.color.terminow_text_secondary));
        }
        envRow.addView(envTexts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        List<TerminowLauncher.Environment> linuxEnvironments = new ArrayList<>();
        for (TerminowLauncher.Environment other : mLauncher.environments()) if (!other.isLocal()) linuxEnvironments.add(other);
        if (linuxEnvironments.size() > 1) envRow.addView(button(mActivity.getString(R.string.terminow_install_change), false, true, () -> {
            CharSequence[] names = new CharSequence[linuxEnvironments.size()];
            for (int i = 0; i < names.length; i++) names[i] = linuxEnvironments.get(i).label;
            new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_launcher_environment)
                .setItems(names, (d, which) -> {
                    select(linuxEnvironments.get(which));
                    detect();
                    render();
                })
                .show();
        }));
        envCard.addView(envRow, matchWrap());

        if (environment.isLocal()) {
            // No Linux environment at all: tools of Ubuntu are not installed into TermiNow itself
            envCard.addView(note(mActivity.getString(R.string.terminow_install_no_linux)));
            envCard.addView(button(mActivity.getString(R.string.terminow_launcher_ubuntu_setup), true, false, () -> {
                dismiss();
                mLauncher.show();
            }));
            return;
        }
        String unsupported = unsupportedReason(environment);
        if (unsupported != null) {
            envCard.addView(note(unsupported));
            return;
        }
        if (mDetection != null && mDetection.error != null) {
            TextView error = text(mActivity.getString(R.string.terminow_install_check_failed, mDetection.error), 13, R.color.terminow_mcp_warn);
            error.setPadding(0, dp(8), 0, 0);
            envCard.addView(error, matchWrap());
            envCard.addView(button(mActivity.getString(R.string.terminow_install_check_again), false, false, this::detect));
        }

        TerminowToolInstaller.Job running = TerminowToolInstaller.runningIn(environment.key);
        for (TerminowToolInstaller.Tool tool : TOOLS) toolRow(content, environment, tool, running);
        pluginsRow(content, environment, running);

        TextView footer = note(mActivity.getString(mDetection != null && mDetection.isRoot() ? R.string.terminow_install_note_root :
            R.string.terminow_install_note_user, environment.shortName(), mUser));
        footer.setPadding(dp(4), dp(12), dp(4), 0);
        content.addView(footer, matchWrap());
    }

    private void toolRow(@NonNull LinearLayout content, @NonNull TerminowLauncher.Environment environment,
                         @NonNull TerminowToolInstaller.Tool tool, @Nullable TerminowToolInstaller.Job running) {
        LinearLayout card = card(content);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconOf(tool));
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

        TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(environment.key, mUser, tool);
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
            row.addView(button(mActivity.getString(R.string.terminow_install_cancel), false, true, job::cancel));
            return;
        }
        if (mDetection == null) {
            status.setText(R.string.terminow_install_checking);
            ProgressBar spinner = new ProgressBar(mActivity);
            row.addView(spinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
            return;
        }
        boolean failed = job != null && job.getState() == TerminowToolInstaller.State.FAILED && version == null;
        if (version != null) {
            status.setText(mActivity.getString(R.string.terminow_install_installed, version));
            status.setTextColor(color(R.color.terminow_quick_ready));
            // gstack is used from Claude Code, as its skills
            if (tool == TerminowToolInstaller.Tool.FISH) {
                fishInstalled(row, status, environment, version);
                return;
            }
            boolean claude = tool == TerminowToolInstaller.Tool.CLAUDE || tool == TerminowToolInstaller.Tool.GSTACK;
            String open = claude ? mActivity.getString(R.string.terminow_install_open_claude) : mActivity.getString(R.string.terminow_install_open);
            row.addView(button(open, tool == TerminowToolInstaller.Tool.CLAUDE, true, () -> {
                dismiss();
                if (claude) mLauncher.openClaudeWithSignInHelp(environment, mUser);
                else mLauncher.openTerminal(environment);
            }));
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
            status.setText(mActivity.getString(R.string.terminow_install_failed, stageName(job.getStage()), failureText(job)));
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
        } else {
            status.setText(R.string.terminow_install_not_installed);
        }
        // Fish is installed and made the shell of new tabs in one go
        int label = failed ? R.string.terminow_install_retry :
            tool == TerminowToolInstaller.Tool.FISH ? R.string.terminow_install_fish_enable : R.string.terminow_install_install;
        View action = button(mActivity.getString(label), true, true, () -> install(environment, tool));
        if (running != null) {
            // apt runs once at a time in an environment
            action.setEnabled(false);
            action.setAlpha(0.4f);
            status.setText(mActivity.getString(R.string.terminow_install_waiting, nameOf(running.tool)));
        }
        row.addView(action);
    }

    /** The plugins of the official marketplace: chosen in the picker, installed as one job. */
    private void pluginsRow(@NonNull LinearLayout content, @NonNull TerminowLauncher.Environment environment,
                            @Nullable TerminowToolInstaller.Job running) {
        TerminowToolInstaller.Tool tool = TerminowToolInstaller.Tool.PLUGINS;
        LinearLayout card = card(content);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconOf(tool));
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

        TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf(environment.key, mUser, tool);
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
            row.addView(button(mActivity.getString(R.string.terminow_install_cancel), false, true, job::cancel));
            return;
        }
        if (mDetection == null) {
            status.setText(R.string.terminow_install_checking);
            return;
        }
        boolean hasClaude = mDetection.versions.get(TerminowToolInstaller.Tool.CLAUDE) != null;
        if (job != null && job.getState() == TerminowToolInstaller.State.FAILED) {
            status.setText(mActivity.getString(R.string.terminow_install_failed, stageName(job.getStage()), failureText(job)));
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
            new TerminowPluginPicker(mActivity, mRunner, environment.key, mUser, names -> {
                TerminowToolInstaller.Job started = TerminowToolInstaller.install(mRunner, environment.key, mUser, tool, names);
                if (started == null) {
                    TerminowToolInstaller.Job other = TerminowToolInstaller.runningIn(environment.key);
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

    private void install(@NonNull TerminowLauncher.Environment environment, @NonNull TerminowToolInstaller.Tool tool) {
        TerminowToolInstaller.Job job = TerminowToolInstaller.install(mRunner, environment.key, mUser, tool);
        if (job == null) {
            TerminowToolInstaller.Job running = TerminowToolInstaller.runningIn(environment.key);
            mActivity.showToast(mActivity.getString(R.string.terminow_install_waiting, running != null ? nameOf(running.tool) : ""), false);
        }
        mLogOpen.remove(tool);
        render();
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
     * Fish that is installed: whether new Ubuntu tabs open with it, and Enable when they do not.
     * Tabs that are open keep the shell they have.
     */
    private void fishInstalled(@NonNull LinearLayout row, @NonNull TextView status, @NonNull TerminowLauncher.Environment environment,
                               @NonNull String version) {
        boolean ubuntu = mLauncher.isUbuntuEnvironment(environment);
        boolean enabled = ubuntu && TerminowStore.UBUNTU_SHELL_FISH.equals(mLauncher.getStore().getUbuntuShell());
        status.setText(mActivity.getString(enabled ? R.string.terminow_install_fish_enabled : R.string.terminow_install_installed, version));
        if (!ubuntu) return;
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
