package com.termux.app.terminow;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
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
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher of the terminal screen: a TermiNow shell, Ubuntu, and Claude Code, in the
 * environment and folder that the user picked.
 *
 * Every launch is a new session that runs its program directly, with the folder and the
 * arguments as separate arguments: nothing is typed into a terminal, so a terminal that runs
 * something else is never disturbed. Tapping again switches to the session that is open already
 * instead of starting a second one. What Claude Code supports and whether it is logged in is
 * found out by running it in a separate process, in the environment that it will run in.
 */
public class TerminowLauncher {

    public interface Host {
        /**
         * Start a program in a new session and show it, {@code null} if it failed. An empty
         * executable starts the default login shell of the app.
         */
        @Nullable TerminalSession startSession(@NonNull String executable, @NonNull String[] arguments,
                                               @NonNull String workingDirectory, @NonNull String name);

        void showSession(@NonNull TerminalSession session);

        /** Open a TermiNow shell in the folder, for installing what is missing. */
        void openShell(@NonNull File directory);
    }

    /**
     * Prints what the Claude Code of an environment supports, as `TERMINOW_*=` lines. Whether
     * it is logged in is only taken from `claude auth status`, never from the file existing.
     * Nothing of the account, like the email, is printed.
     */
    static final String DETECT_SCRIPT =
        "PATH=\"$HOME/.local/bin:$PATH\"; export PATH\n" +
        "echo \"TERMINOW_HOME=$HOME\"\n" +
        "c=$(command -v claude 2>/dev/null) || { echo TERMINOW_CLAUDE=; exit 0; }\n" +
        "echo \"TERMINOW_CLAUDE=$c\"\n" +
        "echo \"TERMINOW_VERSION=$(claude --version 2>&1 | head -n 1)\"\n" +
        "h=$(claude --help 2>&1)\n" +
        "case \"$h\" in *--continue*) echo TERMINOW_CONTINUE=yes;; esac\n" +
        "case \"$h\" in *--resume*) echo TERMINOW_RESUME=yes;; esac\n" +
        "a=$(claude auth --help 2>&1)\n" +
        "case \"$a\" in *login*) echo TERMINOW_AUTH_LOGIN=yes;; esac\n" +
        "case \"$a\" in *status*)\n" +
        "  s=$(claude auth status --json 2>/dev/null | tr -d ' \\n')\n" +
        "  case \"$s\" in *'\"loggedIn\":true'*) echo TERMINOW_LOGGED_IN=yes;; *'\"loggedIn\":false'*) echo TERMINOW_LOGGED_IN=no;; *) echo TERMINOW_LOGGED_IN=unknown;; esac;;\n" +
        "esac\n";

    /** Starts Claude Code with the arguments after `$0`, from the folder the session starts in. */
    static final String CLAUDE_SCRIPT = "PATH=\"$HOME/.local/bin:$PATH\"; export PATH; exec claude \"$@\"";

    /** What was found out about Claude Code in an environment. */
    static class ClaudeState {
        boolean checked;
        @Nullable String error;
        String path = "";
        String version = "";
        boolean supportsContinue;
        boolean supportsResume;
        boolean supportsLogin;
        /** "yes", "no", or "unknown" when the CLI could not tell. */
        String loggedIn = "unknown";

        boolean isInstalled() {
            return !path.isEmpty();
        }
    }

    static void parse(@NonNull ClaudeState state, @NonNull String stdout) {
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.startsWith("TERMINOW_HOME=")) state.checked = true;
            else if (line.startsWith("TERMINOW_CLAUDE=")) state.path = line.substring("TERMINOW_CLAUDE=".length());
            else if (line.startsWith("TERMINOW_VERSION=")) state.version = line.substring("TERMINOW_VERSION=".length());
            else if (line.equals("TERMINOW_CONTINUE=yes")) state.supportsContinue = true;
            else if (line.equals("TERMINOW_RESUME=yes")) state.supportsResume = true;
            else if (line.equals("TERMINOW_AUTH_LOGIN=yes")) state.supportsLogin = true;
            else if (line.startsWith("TERMINOW_LOGGED_IN=")) state.loggedIn = line.substring("TERMINOW_LOGGED_IN=".length());
        }
    }

    /** The environments: TermiNow itself, and each installed proot-distro container. */
    private static class Environment {
        final String key;
        final String label;
        @Nullable final TerminowMcpTargets.Container container;

        Environment(String key, String label, @Nullable TerminowMcpTargets.Container container) {
            this.key = key;
            this.label = label;
            this.container = container;
        }

        boolean isLocal() {
            return container == null;
        }

        @NonNull
        String shortName() {
            return container == null ? "Local" : container.isUbuntu ? "Ubuntu" : container.name;
        }
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Host mHost;
    private final TerminowMcpTargets mTargets;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Map<String, ClaudeState> mClaude = new HashMap<>();
    private final Set<String> mChecking = new HashSet<>();
    /** The sessions that the launcher started, by what they run, to switch to them instead of starting more. */
    private final Map<String, WeakReference<TerminalSession>> mSessions = new HashMap<>();
    private boolean mLaunching;
    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    private String mEnvironmentKey;

    public TerminowLauncher(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Host host) {
        mActivity = activity;
        mStore = store;
        mHost = host;
        mTargets = new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        mEnvironmentKey = getPrefs().optString("environment", TerminowMcpTargets.TARGET_TERMINOW);
    }

    // ---------------------------------------------------------------------------------------
    // The sheet
    // ---------------------------------------------------------------------------------------

    public void show() {
        if (mDialog != null && mDialog.isShowing()) return;
        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout content = vertical();
        content.setPadding(dp(18), dp(16), dp(18), dp(24));
        scroll.addView(content);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_bar_home));
        float radius = dp(22);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        scroll.setBackground(background);
        dialog.setContentView(scroll);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            int width = Math.min(mActivity.getResources().getDisplayMetrics().widthPixels, dp(720));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(d -> {
            mDialog = null;
            mContent = null;
        });
        mDialog = dialog;
        mContent = content;
        render();
        dialog.show();
        checkClaude(currentEnvironment(), false);
    }

    @NonNull
    private List<Environment> environments() {
        List<Environment> list = new ArrayList<>();
        list.add(new Environment(TerminowMcpTargets.TARGET_TERMINOW, mActivity.getString(R.string.terminow_launcher_env_local), null));
        for (TerminowMcpTargets.Container container : mTargets.getContainers())
            list.add(new Environment(container.name, container.label, container));
        return list;
    }

    @NonNull
    private Environment currentEnvironment() {
        for (Environment environment : environments())
            if (environment.key.equals(mEnvironmentKey)) return environment;
        return environments().get(0);
    }

    private void render() {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();
        Environment environment = currentEnvironment();

        TextView title = text(mActivity.getString(R.string.terminow_launcher_title), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        // Environment and folder
        LinearLayout pickers = new LinearLayout(mActivity);
        pickers.setPadding(0, dp(12), 0, dp(4));
        TextView envChip = chip(environment.label + "  ▾");
        envChip.setOnClickListener(v -> pickEnvironment());
        pickers.addView(envChip, chipParams());
        content.addView(pickers);
        String folder = getFolder(environment);
        TextView folderRow = text(mActivity.getString(R.string.terminow_launcher_folder, folder), 13, R.color.terminow_text_secondary);
        folderRow.setPadding(dp(2), dp(4), 0, dp(4));
        folderRow.setSingleLine(true);
        folderRow.setEllipsize(TextUtils.TruncateAt.START);
        content.addView(folderRow, matchWrap());
        TextView change = link(mActivity.getString(R.string.terminow_launcher_change_folder));
        change.setOnClickListener(v -> pickFolder(environment, folder));
        content.addView(change);

        // TermiNow shell
        if (environment.isLocal()) {
            LinearLayout card = card(R.drawable.ic_terminow_terminal, R.color.terminow_host_local,
                mActivity.getString(R.string.terminow_launcher_shell), mActivity.getString(R.string.terminow_launcher_shell_summary));
            addButtons(card, button(R.string.terminow_launcher_open, true, () -> launchShell(environment)));
            content.addView(card, cardParams());
        }

        // Ubuntu
        List<TerminowMcpTargets.Container> containers = mTargets.getContainers();
        if (environment.isLocal()) {
            if (containers.isEmpty()) {
                LinearLayout card = card(R.drawable.ic_terminow_hosts, R.color.terminow_text_secondary,
                    mActivity.getString(R.string.terminow_launcher_ubuntu), mActivity.getString(R.string.terminow_launcher_ubuntu_missing));
                card.addView(command(mActivity.getString(R.string.terminow_launcher_ubuntu_install_command)), matchWrap());
                addButtons(card, button(R.string.terminow_launcher_open_shell_to_install, false,
                    () -> mHost.openShell(new File(TermuxConstants.TERMUX_HOME_DIR_PATH))));
                content.addView(card, cardParams());
            } else {
                LinearLayout card = card(R.drawable.ic_terminow_hosts, R.color.terminow_host_mosh,
                    mActivity.getString(R.string.terminow_launcher_ubuntu), mActivity.getString(R.string.terminow_launcher_ubuntu_choose));
                for (TerminowMcpTargets.Container container : containers) {
                    TextView choose = chip(container.label);
                    choose.setOnClickListener(v -> {
                        setEnvironment(container.name);
                        render();
                    });
                    LinearLayout.LayoutParams params = chipParams();
                    params.topMargin = dp(8);
                    card.addView(choose, params);
                }
                content.addView(card, cardParams());
            }
        } else {
            TerminowMcpTargets.Container container = environment.container;
            LinearLayout card = card(R.drawable.ic_terminow_hosts, R.color.terminow_host_mosh, container.label,
                mActivity.getString(R.string.terminow_launcher_ubuntu_summary, container.name, getUser(environment)));
            addButtons(card, button(R.string.terminow_launcher_open, true, () -> launchUbuntu(environment)),
                button(R.string.terminow_launcher_user, false, () -> pickUser(environment)));
            content.addView(card, cardParams());
        }

        // Claude Code
        content.addView(claudeCard(environment), cardParams());

        TextView note = text(mActivity.getString(R.string.terminow_launcher_note), 12, R.color.terminow_text_secondary);
        note.setPadding(dp(2), dp(4), 0, 0);
        content.addView(note, matchWrap());
    }

    @NonNull
    private View claudeCard(@NonNull Environment environment) {
        ClaudeState state = mClaude.get(environment.key);
        boolean checking = mChecking.contains(environment.key);
        String status;
        int statusColor = R.color.terminow_text_secondary;
        if (checking || state == null) status = mActivity.getString(R.string.terminow_launcher_claude_checking);
        else if (state.error != null) status = mActivity.getString(R.string.terminow_launcher_claude_check_failed, state.error);
        else if (!state.isInstalled()) status = mActivity.getString(R.string.terminow_launcher_claude_missing, environment.label);
        else {
            String login;
            if (state.loggedIn.equals("yes")) {
                login = mActivity.getString(R.string.terminow_launcher_claude_logged_in);
                statusColor = R.color.terminow_panel_accent;
            } else if (state.loggedIn.equals("no")) {
                login = mActivity.getString(R.string.terminow_launcher_claude_login_required);
                statusColor = R.color.terminow_mcp_warn;
            } else {
                login = mActivity.getString(R.string.terminow_launcher_claude_login_unknown);
            }
            status = state.version + " · " + login;
        }
        LinearLayout card = card(R.drawable.ic_terminow_launch, R.color.terminow_accent, mActivity.getString(R.string.terminow_launcher_claude),
            mActivity.getString(R.string.terminow_launcher_claude_summary, environment.label));
        TextView statusView = text(status, 13, statusColor);
        statusView.setPadding(0, dp(8), 0, 0);
        if (checking) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ProgressBar spinner = new ProgressBar(mActivity);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
            row.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
            statusView.setPadding(dp(8), dp(8), 0, 0);
            row.addView(statusView);
            card.addView(row);
        } else {
            card.addView(statusView, matchWrap());
        }
        if (checking || state == null) return card;

        if (state.error != null) {
            addButtons(card, button(R.string.terminow_launcher_check_again, false, () -> checkClaude(environment, true)));
            return card;
        }
        if (!state.isInstalled()) {
            card.addView(command(mActivity.getString(environment.isLocal() ? R.string.terminow_launcher_claude_install_local :
                R.string.terminow_launcher_claude_install_ubuntu)), matchWrap());
            List<View> buttons = new ArrayList<>();
            if (environment.isLocal())
                buttons.add(button(R.string.terminow_launcher_open_shell_to_install, false,
                    () -> mHost.openShell(new File(TermuxConstants.TERMUX_HOME_DIR_PATH))));
            else
                buttons.add(button(R.string.terminow_launcher_open_ubuntu_to_install, false, () -> launchUbuntu(environment)));
            buttons.add(button(R.string.terminow_launcher_check_again, false, () -> checkClaude(environment, true)));
            addButtons(card, buttons.toArray(new View[0]));
            return card;
        }

        boolean loggedOut = state.loggedIn.equals("no");
        List<View> buttons = new ArrayList<>();
        buttons.add(button(R.string.terminow_launcher_claude_new, !loggedOut, () -> launchClaude(environment, new String[0], false)));
        if (state.supportsContinue)
            buttons.add(button(R.string.terminow_launcher_claude_continue, false, () -> launchClaude(environment, new String[]{"--continue"}, false)));
        if (state.supportsResume)
            buttons.add(button(R.string.terminow_launcher_claude_resume, false, () -> launchClaude(environment, new String[]{"--resume"}, false)));
        if (state.supportsLogin)
            buttons.add(button(R.string.terminow_launcher_claude_login, loggedOut, () -> launchClaude(environment, new String[]{"auth", "login"}, true)));
        buttons.add(button(R.string.terminow_launcher_check_again, false, () -> checkClaude(environment, true)));
        addButtons(card, buttons.toArray(new View[0]));
        if (!state.supportsLogin) {
            TextView noLogin = text(mActivity.getString(R.string.terminow_launcher_claude_no_login_command), 12, R.color.terminow_text_secondary);
            noLogin.setPadding(0, dp(8), 0, 0);
            card.addView(noLogin, matchWrap());
        }
        if (!state.loggedIn.equals("yes")) {
            TextView separate = text(mActivity.getString(R.string.terminow_launcher_claude_separate), 12, R.color.terminow_text_secondary);
            separate.setPadding(0, dp(8), 0, 0);
            card.addView(separate, matchWrap());
        }
        return card;
    }

    // ---------------------------------------------------------------------------------------
    // Detection
    // ---------------------------------------------------------------------------------------

    private void checkClaude(@NonNull Environment environment, boolean force) {
        if (mChecking.contains(environment.key)) return;
        if (!force && mClaude.containsKey(environment.key)) return;
        mChecking.add(environment.key);
        render();
        String user = getUser(environment);
        mExecutor.execute(() -> {
            ClaudeState state = new ClaudeState();
            try {
                TerminowClaudeSetup setup = new TerminowClaudeSetup(mActivity, mTargets);
                TerminowClaudeSetup.ProcessResult result = setup.run(TerminowClaudeSetup.buildCommand(mTargets.getPrefixDir(),
                    environment.key, user, DETECT_SCRIPT), TerminowClaudeSetup.DETECT_TIMEOUT_MS);
                if (result.error != null) state.error = result.error;
                else if (result.timedOut) state.error = mActivity.getString(R.string.terminow_launcher_claude_timeout);
                else {
                    parse(state, result.stdout);
                    if (!state.checked)
                        state.error = mActivity.getString(R.string.terminow_launcher_env_failed, TerminowClaudeSetup.lastLines(result.stderr, 3));
                }
            } catch (RuntimeException e) {
                state.error = String.valueOf(e.getMessage());
            }
            mHandler.post(() -> {
                mChecking.remove(environment.key);
                mClaude.put(environment.key, state);
                render();
            });
        });
    }

    // ---------------------------------------------------------------------------------------
    // Launching
    // ---------------------------------------------------------------------------------------

    private void launchShell(@NonNull Environment environment) {
        String folder = getFolder(environment);
        // An empty executable is the default login shell of the app, like a Local Terminal
        launch("shell|" + environment.key + "|" + folder, "", new String[0],
            folder, mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "bash"), false);
    }

    private void launchUbuntu(@NonNull Environment environment) {
        if (environment.container == null) return;
        String folder = getFolder(environment);
        String[] args = {"login", environment.container.name, "--user", getUser(environment), "--work-dir", folder};
        launch("ubuntu|" + environment.key + "|" + folder, new File(mTargets.getPrefixDir(), "bin/proot-distro").getPath(), args,
            TermuxConstants.TERMUX_HOME_DIR_PATH, mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "bash"), false);
    }

    /**
     * Start Claude Code with arguments that its `--help` listed, in the folder. A login is a
     * session of its own, after which the status is checked again.
     */
    private void launchClaude(@NonNull Environment environment, @NonNull String[] claudeArgs, boolean login) {
        String folder = getFolder(environment);
        String executable;
        List<String> args = new ArrayList<>();
        String workingDirectory;
        if (environment.isLocal()) {
            executable = new File(mTargets.getPrefixDir(), "bin/bash").getPath();
            args.addAll(Arrays.asList("-c", CLAUDE_SCRIPT, "claude"));
            workingDirectory = folder;
        } else {
            executable = new File(mTargets.getPrefixDir(), "bin/proot-distro").getPath();
            args.addAll(Arrays.asList("login", environment.key, "--user", getUser(environment), "--work-dir", folder,
                "--", "/bin/sh", "-c", CLAUDE_SCRIPT, "claude"));
            workingDirectory = TermuxConstants.TERMUX_HOME_DIR_PATH;
        }
        args.addAll(Arrays.asList(claudeArgs));
        String tool = login ? "Claude login" : "Claude";
        TerminalSession session = launch("claude|" + environment.key + "|" + folder + "|" + TextUtils.join(" ", claudeArgs), executable,
            args.toArray(new String[0]), workingDirectory, mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), tool), login);
        if (session != null && login) watchLogin(session, environment);
    }

    /**
     * Start a session, or switch to the one that runs the same thing already, so repeated taps
     * never start duplicates. A session that ends at once with an error is reported.
     */
    @Nullable
    private TerminalSession launch(@NonNull String key, @NonNull String executable, @NonNull String[] args,
                                   @NonNull String workingDirectory, @NonNull String name, boolean alwaysNew) {
        if (mLaunching) return null;
        WeakReference<TerminalSession> existing = mSessions.get(key);
        TerminalSession running = existing != null ? existing.get() : null;
        if (running != null && running.isRunning() && !alwaysNew) {
            dismiss();
            mHost.showSession(running);
            mActivity.showToast(mActivity.getString(R.string.terminow_launcher_already_open), false);
            return running;
        }
        mLaunching = true;
        try {
            TerminalSession session = mHost.startSession(executable, args, workingDirectory, name);
            if (session == null) {
                showFailure(name, mActivity.getString(R.string.terminow_launcher_start_failed));
                return null;
            }
            mSessions.put(key, new WeakReference<>(session));
            dismiss();
            watchStart(session, name, 0);
            return session;
        } finally {
            mLaunching = false;
        }
    }

    /** Report a session that ended with an error right after it started, like a missing program. */
    private void watchStart(@NonNull TerminalSession session, @NonNull String name, int check) {
        if (check > 16) return;
        mHandler.postDelayed(() -> {
            if (session.getPid() > 0 && !session.isRunning()) {
                int exit = session.getExitStatus();
                if (exit != 0) showFailure(name, mActivity.getString(R.string.terminow_launcher_exited, exit));
                return;
            }
            watchStart(session, name, check + 1);
        }, 500);
    }

    /** When the login session ends, check the login status of the environment again. */
    private void watchLogin(@NonNull TerminalSession session, @NonNull Environment environment) {
        mHandler.postDelayed(() -> {
            if (session.getPid() <= 0 || session.isRunning()) {
                watchLogin(session, environment);
                return;
            }
            mClaude.remove(environment.key);
            mChecking.add(environment.key);
            String user = getUser(environment);
            mExecutor.execute(() -> {
                ClaudeState state = new ClaudeState();
                TerminowClaudeSetup.ProcessResult result = new TerminowClaudeSetup(mActivity, mTargets).run(
                    TerminowClaudeSetup.buildCommand(mTargets.getPrefixDir(), environment.key, user, DETECT_SCRIPT), TerminowClaudeSetup.DETECT_TIMEOUT_MS);
                if (result.error == null && !result.timedOut) parse(state, result.stdout);
                mHandler.post(() -> {
                    mChecking.remove(environment.key);
                    mClaude.put(environment.key, state);
                    int message = state.loggedIn.equals("yes") ? R.string.terminow_launcher_login_done :
                        state.loggedIn.equals("no") ? R.string.terminow_launcher_login_not_done : R.string.terminow_launcher_claude_login_unknown;
                    mActivity.showToast(mActivity.getString(message), true);
                    render();
                });
            });
        }, 2000);
    }

    private void showFailure(@NonNull String name, @NonNull String reason) {
        if (mActivity.isFinishing()) return;
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_launcher_failed_title, name))
            .setMessage(reason)
            .setPositiveButton(R.string.terminow_launcher_open_launcher, (d, w) -> show())
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    private void dismiss() {
        if (mDialog != null) mDialog.dismiss();
    }

    // ---------------------------------------------------------------------------------------
    // Environment, user and folder, remembered per environment
    // ---------------------------------------------------------------------------------------

    @NonNull
    private JSONObject getPrefs() {
        return mStore.getLauncher();
    }

    private void putPref(@NonNull String key, @NonNull String value) {
        JSONObject prefs = getPrefs();
        try {
            prefs.put(key, value);
        } catch (JSONException e) {
            return;
        }
        mStore.setLauncher(prefs);
    }

    private void setEnvironment(@NonNull String key) {
        mEnvironmentKey = key;
        putPref("environment", key);
        checkClaude(currentEnvironment(), false);
    }

    private void pickEnvironment() {
        List<Environment> environments = environments();
        CharSequence[] labels = new CharSequence[environments.size()];
        for (int i = 0; i < environments.size(); i++) labels[i] = environments.get(i).label;
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_launcher_environment)
            .setItems(labels, (d, which) -> {
                setEnvironment(environments.get(which).key);
                render();
            })
            .show();
    }

    @NonNull
    private String getUser(@NonNull Environment environment) {
        if (environment.container == null) return "root";
        String user = getPrefs().optString("user:" + environment.key, "root");
        return TerminowMcpTargets.getUsers(environment.container).contains(user) ? user : "root";
    }

    private void pickUser(@NonNull Environment environment) {
        if (environment.container == null) return;
        List<String> users = TerminowMcpTargets.getUsers(environment.container);
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_launcher_user)
            .setItems(users.toArray(new CharSequence[0]), (d, which) -> {
                putPref("user:" + environment.key, users.get(which));
                // Another user has another home, folder and Claude login
                putPref("folder:" + environment.key, "");
                mClaude.remove(environment.key);
                render();
                checkClaude(environment, true);
            })
            .show();
    }

    /** The folder for the environment, its home if none was chosen or the chosen one is gone. */
    @NonNull
    private String getFolder(@NonNull Environment environment) {
        String folder = getPrefs().optString("folder:" + environment.key);
        if (!folder.isEmpty() && resolve(environment, folder) != null) return folder;
        return homeOf(environment);
    }

    @NonNull
    private String homeOf(@NonNull Environment environment) {
        return environment.container == null ? TermuxConstants.TERMUX_HOME_DIR_PATH :
            TerminowMcpTargets.getGuestHome(environment.container, getUser(environment));
    }

    /**
     * The folder of the app for a path of the environment, {@code null} if it is not a folder
     * that the environment can start in. Paths of the Storage Access Framework are not paths
     * at all and can not be used.
     */
    @Nullable
    private File resolve(@NonNull Environment environment, @NonNull String path) {
        if (!path.startsWith("/") || path.contains("\0") || path.contains("\n")) return null;
        File file;
        if (environment.container != null) {
            file = TerminowMcpTargets.resolveGuestPath(environment.container, path);
        } else {
            file = mTargets.resolveTerminowPath(path);
            // Shared storage, when the build may use it by path and access is on
            if (file == null && TerminowStorageAccess.hasAccess(mActivity)) {
                for (TerminowStorageAccess.Volume volume : TerminowStorageAccess.getVolumes(mActivity)) {
                    if (volume.directory == null || !volume.isReadable()) continue;
                    String root = volume.directory.getAbsolutePath();
                    if ((path.equals(root) || path.startsWith(root + "/")) && !path.contains("/../")) file = new File(path);
                }
            }
        }
        return file != null && file.isDirectory() ? file : null;
    }

    /** Choose a folder of the environment by browsing its folders or typing a path. */
    private void pickFolder(@NonNull Environment environment, @NonNull String start) {
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(6), dp(20), 0);
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setText(start);
        field.setTextColor(color(R.color.terminow_text));
        box.addView(field, matchWrap());
        LinearLayout list = vertical();
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(list);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(260)));
        TextView error = text("", 12, R.color.terminow_mcp_warn);
        box.addView(error, matchWrap());
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            String path = field.getText().toString().trim();
            File dir = resolve(environment, path);
            error.setText(dir == null ? mActivity.getString(R.string.terminow_launcher_folder_invalid) : "");
            if (dir == null) return;
            if (!path.equals("/")) {
                TextView up = text("‹ ..", 15, R.color.terminow_text);
                up.setPadding(0, dp(10), 0, dp(10));
                up.setOnClickListener(v -> {
                    String parent = path.substring(0, Math.max(1, path.lastIndexOf('/')));
                    field.setText(parent);
                    fill[0].run();
                });
                list.addView(up, matchWrap());
            }
            File[] children = dir.listFiles(File::isDirectory);
            if (children == null) return;
            Arrays.sort(children, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File child : children) {
                if (child.getName().startsWith(".")) continue;
                TextView row = text("📁  " + child.getName(), 15, R.color.terminow_text);
                row.setPadding(0, dp(10), 0, dp(10));
                row.setOnClickListener(v -> {
                    field.setText(path.equals("/") ? "/" + child.getName() : path + "/" + child.getName());
                    fill[0].run();
                });
                list.addView(row, matchWrap());
            }
        };
        fill[0].run();
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_launcher_folder_title, environment.label))
            .setView(box)
            .setPositiveButton(R.string.terminow_launcher_use_folder, null)
            .setNeutralButton(R.string.terminow_launcher_folder_go, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> fill[0].run());
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String path = field.getText().toString().trim();
                while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
                if (resolve(environment, path) == null) {
                    error.setText(mActivity.getString(R.string.terminow_launcher_folder_invalid));
                    return;
                }
                putPref("folder:" + environment.key, path);
                dialog.dismiss();
                render();
            });
        });
        dialog.show();
    }

    // ---------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------

    @NonNull
    private LinearLayout card(int iconRes, int colorRes, @NonNull String title, @NonNull String summary) {
        LinearLayout card = vertical();
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(16));
        background.setColor(color(R.color.terminow_surface));
        card.setBackground(background);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color(colorRes));
        icon.setBackground(circle);
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        head.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(12), 0, 0, 0);
        TextView titleView = text(title, 16, R.color.terminow_text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(titleView);
        texts.addView(text(summary, 12, R.color.terminow_text_secondary), matchWrap());
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(head, matchWrap());
        return card;
    }

    private void addButtons(@NonNull LinearLayout card, @NonNull View... buttons) {
        // Wraps on narrow screens: rows of up to three
        LinearLayout row = null;
        for (int i = 0; i < buttons.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(mActivity);
                row.setPadding(0, dp(10), 0, 0);
                card.addView(row, matchWrap());
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
            params.setMarginEnd(dp(8));
            row.addView(buttons[i], params);
        }
    }

    @NonNull
    private TextView button(int labelRes, boolean primary, @NonNull Runnable action) {
        TextView button = text(mActivity.getString(labelRes), 13, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(21));
        shape.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_field));
        button.setBackground(shape);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    @NonNull
    private TextView chip(@NonNull String label) {
        TextView chip = text(label, 14, R.color.terminow_text);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), 0, dp(14), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(18));
        shape.setColor(color(R.color.terminow_field));
        chip.setBackground(shape);
        return chip;
    }

    @NonNull
    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.setMarginEnd(dp(8));
        return params;
    }

    /** A command to run by hand, which can be copied. It is never run by the launcher. */
    @NonNull
    private View command(@NonNull String command) {
        TextView view = text(command, 12, R.color.terminow_text);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(color(R.color.terminow_field));
        view.setBackground(shape);
        view.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("command", command));
                mActivity.showToast(mActivity.getString(R.string.terminow_launcher_copied), false);
            }
            return true;
        });
        LinearLayout wrapper = vertical();
        wrapper.setPadding(0, dp(8), 0, 0);
        wrapper.addView(view, matchWrap());
        return wrapper;
    }

    @NonNull
    private TextView link(@NonNull String label) {
        TextView link = text(label, 14, R.color.terminow_panel_accent);
        link.setTypeface(Typeface.DEFAULT_BOLD);
        link.setPadding(dp(2), dp(4), dp(8), dp(12));
        return link;
    }

    @NonNull
    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        return params;
    }

    @NonNull
    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(mActivity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    @NonNull
    private TextView text(@NonNull String text, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(text);
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

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics()));
    }

}
