package com.termux.app.terminow;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.terminow.linux.TerminowLinuxEnv;
import com.termux.app.TermuxActivity;
import com.termux.shared.shell.ShellUtils;
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
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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


    /** The sign-in pages of Claude Code that a login session may print, nothing else is offered. */
    private static final Pattern LOGIN_URL = Pattern.compile(
        "https://(?:claude\\.ai|claude\\.com|platform\\.claude\\.com|console\\.anthropic\\.com|[a-z0-9-]+\\.anthropic\\.com)/[^\\s\"'<>`]+");

    /** The last sign-in link in the text of a login session, {@code null} if there is none. */
    @Nullable
    static String findLoginUrl(@NonNull String transcript) {
        Matcher matcher = LOGIN_URL.matcher(transcript);
        String url = null;
        while (matcher.find()) url = matcher.group();
        if (url == null) return null;
        // Punctuation that closes a sentence is not part of the link
        while (url.endsWith(".") || url.endsWith(",") || url.endsWith(")")) url = url.substring(0, url.length() - 1);
        return url;
    }

    /** What is known about a tab: set when TermiNow started it, otherwise the tab is unknown. */
    public static class SessionInfo {
        /** The environment of the launcher, {@code null} for a connection to another host. */
        @Nullable public final String environmentKey;
        public final String environmentLabel;
        /** What it runs, like "bash" or "Claude". */
        public final String tool;
        /** The folder it started in, empty if not known. */
        public final String folder;
        /** The tmux session it runs in, empty for none. */
        public final String tmux;
        /** The user in a Linux environment, empty if not known. */
        public String user = "";

        public SessionInfo(@Nullable String environmentKey, @NonNull String environmentLabel, @NonNull String tool,
                           @NonNull String folder, @NonNull String tmux) {
            this.environmentKey = environmentKey;
            this.environmentLabel = environmentLabel;
            this.tool = tool;
            this.folder = folder;
            this.tmux = tmux;
        }
    }


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
    static class Environment {
        final String key;
        final String label;
        @Nullable final TerminowMcpTargets.Container container;
        /** "Local", "Ubuntu", "Debian", from the distribution of the environment. */
        private final String mShortName;

        Environment(String key, String label, @Nullable TerminowMcpTargets.Container container, @NonNull String shortName) {
            this.key = key;
            this.label = label;
            this.container = container;
            mShortName = shortName;
        }

        boolean isLocal() {
            return container == null;
        }

        @NonNull
        String shortName() {
            return mShortName;
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
    private final Map<String, TerminowTmux.State> mTmux = new HashMap<>();
    private final Set<String> mTmuxChecking = new HashSet<>();
    /** What each tab that TermiNow started runs. Tabs that are not in it are unknown. */
    private final Map<TerminalSession, SessionInfo> mInfo = new WeakHashMap<>();
    private final TerminowLaunchProfilesUi mProfiles;
    @Nullable private TerminowLinuxUi mLinux;
    @Nullable private TextView mToolbarEnvironment;
    @Nullable private TextView mToolbarFolder;
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
        mProfiles = new TerminowLaunchProfilesUi(activity, store, this);
    }

    /** The registry of Linux environments, for their names, users, folders and shared folders. */
    public void setLinux(@NonNull TerminowLinuxUi linux) {
        mLinux = linux;
    }

    @Nullable
    private TerminowLinuxEnv linuxOf(@NonNull Environment environment) {
        return mLinux != null && environment.container != null ? mLinux.getRegistry().get(environment.key) : null;
    }

    /** The bindings of the folders that the user shared into the environment. */
    @NonNull
    private List<String> bindsOf(@NonNull Environment environment) {
        TerminowLinuxEnv linux = linuxOf(environment);
        return linux != null ? linux.bindArguments() : new ArrayList<>();
    }

    /** Claude Code and tmux are found out for each environment and user, never shared. */
    @NonNull
    private String stateKey(@NonNull Environment environment) {
        return TerminowTmux.key(environment.key, getUser(environment));
    }

    @NonNull
    TerminowLaunchProfilesUi getProfiles() {
        return mProfiles;
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
        // Keep the end of the sheet above the navigation bar and the taskbar of foldables
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() |
                androidx.core.view.WindowInsetsCompat.Type.tappableElement()).bottom;
            content.setPadding(dp(18), dp(16), dp(18), dp(24) + bottom);
            return insets;
        });
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
        checkTmux(currentEnvironment(), false);
    }

    @NonNull
    List<Environment> environments() {
        List<Environment> list = new ArrayList<>();
        list.add(new Environment(TerminowMcpTargets.TARGET_TERMINOW, mActivity.getString(R.string.terminow_launcher_env_local), null, "Local"));
        for (TerminowMcpTargets.Container container : mTargets.getContainers())
        {
            TerminowLinuxEnv linux = mLinux != null ? mLinux.getRegistry().get(container.name) : null;
            // Only installed environments can be opened, an incomplete one is shown in Browse Linux
            if (linux != null && !linux.isInstalled()) continue;
            String shortName = linux != null ? linux.getShortName() : container.isUbuntu ? "Ubuntu" : container.name;
            list.add(new Environment(container.name, container.label, container, shortName));
        }
        return list;
    }

    @NonNull
    Environment currentEnvironment() {
        Environment environment = environmentFor(mEnvironmentKey);
        return environment != null ? environment : environments().get(0);
    }

    /** The environment with the key, {@code null} if it is not installed (any more). */
    @Nullable
    Environment environmentFor(@NonNull String key) {
        for (Environment environment : environments())
            if (environment.key.equals(key)) return environment;
        return null;
    }

    /** The first Ubuntu container, or else the first container, {@code null} if there is none. */
    @Nullable
    private Environment firstContainer() {
        Environment found = null;
        for (Environment environment : environments()) {
            if (environment.container == null) continue;
            if (environment.container.isUbuntu) return environment;
            if (found == null) found = environment;
        }
        return found;
    }

    private void render() {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();
        Environment environment = currentEnvironment();

        TextView title = text(mActivity.getString(R.string.terminow_tools_title), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        // Environment and folder
        LinearLayout pickers = new LinearLayout(mActivity);
        pickers.setPadding(0, dp(12), 0, dp(4));
        TextView envChip = chip(environment.label + "  ▾");
        envChip.setOnClickListener(v -> pickEnvironment());
        pickers.addView(envChip, chipParams());
        if (environment.container != null) {
            TextView userChip = chip(mActivity.getString(R.string.terminow_tools_user, getUser(environment)) + "  ▾");
            userChip.setOnClickListener(v -> pickUser(environment));
            pickers.addView(userChip, chipParams());
        }
        content.addView(pickers);
        String folder = getFolder(environment);
        TextView folderRow = text(mActivity.getString(R.string.terminow_launcher_folder, folder), 13, R.color.terminow_text_secondary);
        folderRow.setPadding(dp(2), dp(4), 0, dp(4));
        folderRow.setSingleLine(true);
        folderRow.setEllipsize(TextUtils.TruncateAt.START);
        content.addView(folderRow, matchWrap());
        TextView change = link(mActivity.getString(R.string.terminow_launcher_change_folder));
        change.setOnClickListener(v -> pickFolder(environment, folder, path -> {
            putPref("folder:" + environment.key, path);
            render();
        }));
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
                card.addView(status(mActivity.getString(R.string.terminow_tools_unavailable), R.color.terminow_mcp_warn), matchWrap());
                card.addView(command(mActivity.getString(R.string.terminow_launcher_ubuntu_install_command)), matchWrap());
                addButtons(card, button(R.string.terminow_launcher_ubuntu_setup, true, this::confirmUbuntuSetup),
                    button(R.string.terminow_launcher_open_shell_to_install, false,
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
                // Open it right away, without choosing it as the environment first
                Environment first = firstContainer();
                if (first != null)
                    addButtons(card, button(R.string.terminow_launcher_open, true, () -> launchUbuntu(first)));
                content.addView(card, cardParams());
            }
        } else {
            TerminowMcpTargets.Container container = environment.container;
            LinearLayout card = card(R.drawable.ic_terminow_hosts, R.color.terminow_host_mosh, container.label,
                mActivity.getString(R.string.terminow_launcher_ubuntu_summary, container.name, getUser(environment)));
            card.addView(status(mActivity.getString(R.string.terminow_tools_available), R.color.terminow_panel_accent), matchWrap());
            addButtons(card, button(R.string.terminow_launcher_open, true, () -> launchUbuntu(environment)),
                button(R.string.terminow_linux_files, false, () -> {
                    // The Linux files of the environment, at the folder of the launcher
                    TerminowLinuxEnv linux = linuxOf(environment);
                    if (linux == null || mLinux == null) return;
                    dismiss();
                    mLinux.openFiles(linux, getUser(environment), getFolder(environment));
                }),
                button(R.string.terminow_launcher_user, false, () -> pickUser(environment)));
            content.addView(card, cardParams());
        }

        // Claude Code
        content.addView(claudeCard(environment), cardParams());

        // tmux, the user's own commands and the launch profiles
        content.addView(tmuxCard(environment), cardParams());
        content.addView(commandsCard(), cardParams());
        content.addView(profilesCard(), cardParams());

        TextView note = text(mActivity.getString(R.string.terminow_launcher_note), 12, R.color.terminow_text_secondary);
        note.setPadding(dp(2), dp(4), 0, 0);
        content.addView(note, matchWrap());
    }

    @NonNull
    private View claudeCard(@NonNull Environment environment) {
        ClaudeState state = mClaude.get(stateKey(environment));
        boolean checking = mChecking.contains(stateKey(environment));
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

    @NonNull
    private View tmuxCard(@NonNull Environment environment) {
        String user = getUser(environment);
        String key = stateKey(environment);
        LinearLayout card = card(R.drawable.ic_terminow_terminal, R.color.terminow_host_local,
            mActivity.getString(R.string.terminow_tools_tmux), mActivity.getString(R.string.terminow_tools_tmux_summary_user, environment.label, user));
        if (!TerminowAccountUi.isPremium(mStore)) {
            card.addView(status(mActivity.getString(R.string.terminow_tools_tmux_premium), R.color.terminow_text_secondary), matchWrap());
            addButtons(card, button(R.string.terminow_vault_check_premium, false, () -> TerminowPremiumGate.require(mActivity, mStore,
                TerminowPremiumGate.Feature.TMUX, 0, () -> checkTmux(environment, true))));
            return card;
        }
        TerminowTmux.State state = mTmux.get(key);
        if (mTmuxChecking.contains(key) || state == null) {
            card.addView(status(mActivity.getString(R.string.terminow_tools_tmux_checking), R.color.terminow_text_secondary), matchWrap());
            return card;
        }
        if (state.error != null) {
            card.addView(status(mActivity.getString(R.string.terminow_launcher_claude_check_failed, state.error), R.color.terminow_mcp_warn), matchWrap());
            addButtons(card, button(R.string.terminow_launcher_check_again, false, () -> checkTmux(environment, true)));
            return card;
        }
        if (!state.isInstalled()) {
            card.addView(status(mActivity.getString(R.string.terminow_tools_tmux_missing_env, environment.label), R.color.terminow_mcp_warn), matchWrap());
            String install = installTmuxCommand(environment);
            if (!install.isEmpty()) card.addView(command(install), matchWrap());
            List<View> buttons = new ArrayList<>();
            if (!install.isEmpty()) buttons.add(button(R.string.terminow_tools_tmux_install, true, () -> confirmInstallTmux(environment, install)));
            buttons.add(button(R.string.terminow_launcher_check_again, false, () -> checkTmux(environment, true)));
            addButtons(card, buttons.toArray(new View[0]));
            return card;
        }
        // Which tmux and which server, so that sessions of other environments and users are never mixed up
        card.addView(status(mActivity.getString(R.string.terminow_tools_tmux_server, state.binary, state.socket), R.color.terminow_text_secondary), matchWrap());
        if (state.sessions.isEmpty())
            card.addView(status(mActivity.getString(R.string.terminow_tools_tmux_none), R.color.terminow_text_secondary), matchWrap());
        List<String> running = new ArrayList<>();
        for (TerminowTmux.Session session : state.sessions) {
            running.add(session.name);
            LinearLayout row = new LinearLayout(mActivity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            LinearLayout texts = vertical();
            TextView nameView = text(session.name, 15, R.color.terminow_text);
            nameView.setTypeface(Typeface.MONOSPACE);
            texts.addView(nameView);
            texts.addView(text(mActivity.getString(session.attached > 0 ? R.string.terminow_tools_tmux_attached : R.string.terminow_tools_tmux_detached,
                String.valueOf(session.windows)), 12, R.color.terminow_text_secondary));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (TerminowLaunchPlan.isValidTmuxName(session.name)) {
                row.addView(button(R.string.terminow_tools_tmux_attach, false, () -> launchTmux(environment, session.name)),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
                TextView more = text("⋮", 20, R.color.terminow_text);
                more.setPadding(dp(12), 0, dp(4), 0);
                more.setContentDescription(mActivity.getString(R.string.terminow_toolbar_more));
                more.setOnClickListener(v -> tmuxActions(environment, session));
                row.addView(more);
            } else {
                row.addView(text(mActivity.getString(R.string.terminow_tools_tmux_by_hand), 12, R.color.terminow_text_secondary));
            }
            card.addView(row, matchWrap());
        }
        // Sessions opened before that do not run now, which are not the same as running ones
        List<String> recent = new ArrayList<>();
        for (String name : mStore.getTmuxRecent(key)) if (!running.contains(name) && TerminowLaunchPlan.isValidTmuxName(name)) recent.add(name);
        if (!recent.isEmpty()) {
            TextView title = text(mActivity.getString(R.string.terminow_tools_tmux_recent), 12, R.color.terminow_text_secondary);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, dp(12), 0, 0);
            card.addView(title, matchWrap());
            for (String name : recent) {
                LinearLayout row = new LinearLayout(mActivity);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, 0);
                TextView nameView = text(name, 14, R.color.terminow_text_secondary);
                nameView.setTypeface(Typeface.MONOSPACE);
                row.addView(nameView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(button(R.string.terminow_tools_tmux_start_again, false, () -> launchTmux(environment, name)),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
                card.addView(row, matchWrap());
            }
        }
        TextView note = text(mActivity.getString(R.string.terminow_tools_tmux_persistence), 11, R.color.terminow_text_secondary);
        note.setPadding(0, dp(10), 0, 0);
        card.addView(note, matchWrap());
        addButtons(card, button(R.string.terminow_tools_tmux_new, true, () -> newTmuxSession(environment)),
            button(R.string.terminow_launcher_check_again, false, () -> checkTmux(environment, true)));
        return card;
    }

    @NonNull
    private String installTmuxCommand(@NonNull Environment environment) {
        if (environment.isLocal()) return "pkg install -y tmux";
        TerminowLinuxEnv linux = linuxOf(environment);
        return linux != null ? linux.installCommand("tmux") : "";
    }

    /** Install tmux visibly in a new tab, as root of the environment, after it was confirmed. */
    private void confirmInstallTmux(@NonNull Environment environment, @NonNull String command) {
        TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.TMUX, 0, () ->
            new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_tools_tmux_install)
                .setMessage(mActivity.getString(R.string.terminow_tools_tmux_install_message, environment.label, command))
                .setPositiveButton(R.string.terminow_tools_run, (d, w) -> {
                    String user = environment.isLocal() ? "root" : "root";
                    launchPlan("install-tmux|" + environment.key + "|" + System.nanoTime(), environment, user,
                        environment.isLocal() ? TermuxConstants.TERMUX_HOME_DIR_PATH : homeOf(environment, "root"),
                        TerminowLaunchPlan.TOOL_CUSTOM, command + "; echo; echo 'TermiNow: done, check again in Open tools'; exec \"${SHELL:-/bin/sh}\"",
                        TerminowLaunchPlan.TMUX_OFF, "", mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "install tmux"),
                        true, "install tmux");
                    mTmux.remove(stateKey(environment));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private void tmuxActions(@NonNull Environment environment, @NonNull TerminowTmux.Session session) {
        CharSequence[] actions = {mActivity.getString(R.string.terminow_tools_tmux_detach), mActivity.getString(R.string.terminow_tools_tmux_rename),
            mActivity.getString(R.string.terminow_tools_tmux_end)};
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(session.name)
            .setItems(actions, (d, which) -> {
                if (which == 0) tmuxOperation(environment, "detach", session.name, "");
                else if (which == 1) renameTmux(environment, session.name);
                else new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setTitle(mActivity.getString(R.string.terminow_tools_tmux_end_title, session.name))
                    .setMessage(R.string.terminow_tools_tmux_end_message)
                    .setPositiveButton(R.string.terminow_tools_tmux_end, (d2, w2) -> tmuxOperation(environment, "kill", session.name, ""))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            })
            .show();
    }

    private void renameTmux(@NonNull Environment environment, @NonNull String name) {
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setText(name);
        field.setTextColor(color(R.color.terminow_text));
        LinearLayout box = vertical();
        box.setPadding(dp(22), dp(6), dp(22), 0);
        box.addView(field, matchWrap());
        TextView hint = text(mActivity.getString(R.string.terminow_tools_tmux_name_hint), 12, R.color.terminow_text_secondary);
        box.addView(hint, matchWrap());
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_tools_tmux_rename)
            .setView(box)
            .setPositiveButton(R.string.terminow_profiles_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String newName = field.getText().toString().trim();
            if (!TerminowLaunchPlan.isValidTmuxName(newName)) {
                hint.setTextColor(color(R.color.terminow_mcp_warn));
                return;
            }
            dialog.dismiss();
            tmuxOperation(environment, "rename", name, newName);
        }));
        dialog.show();
    }

    /** Run a tmux operation in the environment, as the user, with its own tmux, then list again. */
    private void tmuxOperation(@NonNull Environment environment, @NonNull String op, @NonNull String name, @NonNull String newName) {
        TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.TMUX, 0, () -> {
            String user = getUser(environment);
            String key = stateKey(environment);
            mTmuxChecking.add(key);
            render();
            mExecutor.execute(() -> {
                TerminowClaudeSetup.ProcessResult result = new TerminowClaudeSetup(mActivity, mTargets).run(TerminowClaudeSetup.buildCommand(
                    mTargets.getPrefixDir(), environment.key, user, TerminowTmux.SCRIPT, environment.isLocal() ? "local" : "guest",
                    TerminowLaunchPlan.tmuxSocket(environment.isLocal(), user), op, name, newName), TerminowClaudeSetup.DETECT_TIMEOUT_MS);
                boolean done = result.error == null && TerminowTmux.parse(result.stdout).done;
                String error = result.error != null ? result.error : TerminowClaudeSetup.lastLines(result.stderr, 3);
                mHandler.post(() -> {
                    mTmuxChecking.remove(key);
                    if (!done) mActivity.showToast(mActivity.getString(R.string.terminow_tools_tmux_failed, error), true);
                    if (done && op.equals("rename")) mStore.addTmuxRecent(key, newName);
                    checkTmux(environment, true);
                });
            });
        });
    }

    @NonNull
    private View commandsCard() {
        LinearLayout card = card(R.drawable.ic_terminow_snippets, R.color.terminow_host_mosh,
            mActivity.getString(R.string.terminow_tools_commands), mActivity.getString(R.string.terminow_tools_commands_summary));
        // The commands of the environment that is chosen, which follow it when it changes
        String environmentKey = currentEnvironment().key;
        List<TerminowLaunchProfile> commands = new ArrayList<>();
        for (TerminowLaunchProfile profile : mStore.getLaunchProfiles())
            if (TerminowLaunchPlan.TOOL_CUSTOM.equals(profile.tool) && profile.environment.equals(environmentKey)) commands.add(profile);
        if (commands.isEmpty())
            card.addView(status(mActivity.getString(R.string.terminow_tools_commands_none), R.color.terminow_text_secondary), matchWrap());
        for (TerminowLaunchProfile profile : commands) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            LinearLayout texts = vertical();
            texts.addView(text(profile.icon + "  " + profile.name, 15, R.color.terminow_text));
            TextView commandView = text(profile.command, 12, R.color.terminow_text_secondary);
            commandView.setTypeface(Typeface.MONOSPACE);
            commandView.setSingleLine(true);
            commandView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(commandView, matchWrap());
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(button(R.string.terminow_tools_run, false, () -> launchProfile(profile)),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
            card.addView(row, matchWrap());
        }
        addButtons(card, button(R.string.terminow_tools_commands_add, false, () -> {
            dismiss();
            mProfiles.edit(null, TerminowLaunchPlan.TOOL_CUSTOM);
        }));
        return card;
    }

    @NonNull
    private View profilesCard() {
        int count = mStore.getLaunchProfiles().size();
        LinearLayout card = card(R.drawable.ic_terminow_launch, R.color.terminow_accent,
            mActivity.getString(R.string.terminow_profiles_manage),
            mActivity.getString(R.string.terminow_tools_profiles_summary, count, mProfiles.describeDefault()));
        addButtons(card, button(R.string.terminow_profiles_manage_short, false, () -> {
            dismiss();
            mProfiles.showManager();
        }), button(R.string.terminow_profiles_new, false, () -> {
            dismiss();
            mProfiles.edit(null, TerminowLaunchPlan.TOOL_SHELL);
        }));
        return card;
    }

    /** Show the steps of installing Ubuntu, and run them in a new tab only if the user confirms. */
    private void confirmUbuntuSetup() {
        if (mLinux == null) return;
        dismiss();
        new TerminowUbuntuSetupUi(mActivity, mLinux, this::openEnvironment).show();
    }

    /** Open an installed environment, like its Open button, with its user, folder and the terminal theme. */
    private void openEnvironment(@NonNull String alias) {
        Environment environment = environmentFor(alias);
        if (environment == null || environment.container == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_ubuntu_setup_not_found, alias), true);
            return;
        }
        launchUbuntu(environment);
    }

    @NonNull
    private TextView status(@NonNull String label, int colorRes) {
        TextView view = text(label, 13, colorRes);
        view.setPadding(0, dp(8), 0, 0);
        return view;
    }

    // ---------------------------------------------------------------------------------------
    // Detection
    // ---------------------------------------------------------------------------------------

    private void checkTmux(@NonNull Environment environment, boolean force) {
        if (!TerminowAccountUi.isPremium(mStore)) return;
        String key = stateKey(environment);
        if (mTmuxChecking.contains(key)) return;
        if (!force && mTmux.containsKey(key)) return;
        mTmuxChecking.add(key);
        render();
        String user = getUser(environment);
        mExecutor.execute(() -> {
            TerminowTmux.State state;
            try {
                TerminowClaudeSetup.ProcessResult result = new TerminowClaudeSetup(mActivity, mTargets).run(TerminowClaudeSetup.buildCommand(
                    mTargets.getPrefixDir(), environment.key, user, TerminowTmux.SCRIPT, environment.isLocal() ? "local" : "guest",
                    TerminowLaunchPlan.tmuxSocket(environment.isLocal(), user), "list"), TerminowClaudeSetup.DETECT_TIMEOUT_MS);
                state = result.error != null ? new TerminowTmux.State() : TerminowTmux.parse(result.stdout);
                if (result.error != null) state.error = result.error;
                else if (result.timedOut) state.error = mActivity.getString(R.string.terminow_launcher_claude_timeout);
                else if (!state.checked)
                    state.error = mActivity.getString(R.string.terminow_launcher_env_failed, TerminowClaudeSetup.lastLines(result.stderr, 3));
            } catch (RuntimeException e) {
                state = new TerminowTmux.State();
                state.error = String.valueOf(e.getMessage());
            }
            TerminowTmux.State finalState = state;
            mHandler.post(() -> {
                mTmuxChecking.remove(key);
                mTmux.put(key, finalState);
                render();
            });
        });
    }

    private void checkClaude(@NonNull Environment environment, boolean force) {
        String key = stateKey(environment);
        if (mChecking.contains(key)) return;
        if (!force && mClaude.containsKey(key)) return;
        mChecking.add(key);
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
                mChecking.remove(key);
                mClaude.put(key, state);
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
            folder, mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "bash"), false,
            new SessionInfo(environment.key, environment.shortName(), "bash", folder, ""));
    }

    private void launchUbuntu(@NonNull Environment environment) {
        if (environment.container == null) return;
        String folder = getFolder(environment);
        String[] args = ubuntuLoginArgs(environment.container.name, getUser(environment), folder, bindsOf(environment)).toArray(new String[0]);
        SessionInfo info = new SessionInfo(environment.key, environment.shortName(), "bash", folder, "");
        info.user = getUser(environment);
        launch("ubuntu|" + environment.key + "|" + folder, new File(mTargets.getPrefixDir(), "bin/proot-distro").getPath(), args,
            TermuxConstants.TERMUX_HOME_DIR_PATH, mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "bash"), false,
            info);
    }

    /** The arguments of proot-distro that open a shell of an environment as a user in a folder. */
    @NonNull
    static List<String> ubuntuLoginArgs(@NonNull String alias, @NonNull String user, @NonNull String folder, @NonNull List<String> binds) {
        List<String> login = new ArrayList<>(Arrays.asList("login", alias, "--user", user, "--work-dir", folder));
        login.addAll(binds);
        return login;
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
            args.addAll(Arrays.asList("login", environment.key, "--user", getUser(environment), "--work-dir", folder));
            args.addAll(bindsOf(environment));
            args.addAll(Arrays.asList("--", "/bin/sh", "-c", CLAUDE_SCRIPT, "claude"));
            workingDirectory = TermuxConstants.TERMUX_HOME_DIR_PATH;
        }
        args.addAll(Arrays.asList(claudeArgs));
        String tool = login ? "Claude login" : "Claude";
        TerminalSession session = launch("claude|" + environment.key + "|" + folder + "|" + TextUtils.join(" ", claudeArgs), executable,
            args.toArray(new String[0]), workingDirectory, mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), tool), login,
            new SessionInfo(environment.key, environment.shortName(), tool, folder, ""));
        if (session != null && login) watchLogin(session, environment, null);
    }

    /**
     * Start a session, or switch to the one that runs the same thing already, so repeated taps
     * never start duplicates. A session that ends at once with an error is reported.
     */
    @Nullable
    private TerminalSession launch(@NonNull String key, @NonNull String executable, @NonNull String[] args,
                                   @NonNull String workingDirectory, @NonNull String name, boolean alwaysNew,
                                   @NonNull SessionInfo info) {
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
            mInfo.put(session, info);
            updateToolbar(session);
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

    /**
     * While the login session runs, offer to open the sign-in link that Claude Code printed in it
     * in a browser or to copy it. The link is only read from this session that the launcher
     * started for the login, and it is never logged or kept. When the session ends, the login
     * status of the environment is checked again.
     */
    private void watchLogin(@NonNull TerminalSession session, @NonNull Environment environment, @Nullable String offered) {
        mHandler.postDelayed(() -> {
            if (session.getPid() <= 0 || session.isRunning()) {
                String shown = offered;
                if (session.isRunning()) {
                    String transcript = ShellUtils.getTerminalSessionTranscriptText(session, true, false);
                    String url = transcript != null ? findLoginUrl(transcript) : null;
                    if (url != null && !url.equals(offered)) {
                        offerLoginLink(url);
                        shown = url;
                    }
                }
                watchLogin(session, environment, shown);
                return;
            }
            String key = stateKey(environment);
            mClaude.remove(key);
            mChecking.add(key);
            String user = getUser(environment);
            mExecutor.execute(() -> {
                ClaudeState state = new ClaudeState();
                TerminowClaudeSetup.ProcessResult result = new TerminowClaudeSetup(mActivity, mTargets).run(
                    TerminowClaudeSetup.buildCommand(mTargets.getPrefixDir(), environment.key, user, DETECT_SCRIPT), TerminowClaudeSetup.DETECT_TIMEOUT_MS);
                if (result.error == null && !result.timedOut) parse(state, result.stdout);
                mHandler.post(() -> {
                    mChecking.remove(key);
                    mClaude.put(key, state);
                    int message = state.loggedIn.equals("yes") ? R.string.terminow_launcher_login_done :
                        state.loggedIn.equals("no") ? R.string.terminow_launcher_login_not_done : R.string.terminow_launcher_claude_login_unknown;
                    mActivity.showToast(mActivity.getString(message), true);
                    render();
                });
            });
        }, 1000);
    }

    private void offerLoginLink(@NonNull String url) {
        if (mActivity.isFinishing()) return;
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_launcher_login_link_title)
            .setMessage(R.string.terminow_launcher_login_link_message)
            .setPositiveButton(R.string.terminow_launcher_login_link_open, (d, w) -> {
                try {
                    mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
                } catch (ActivityNotFoundException e) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_launcher_login_link_no_browser), true);
                }
            })
            .setNeutralButton(R.string.terminow_launcher_login_link_copy, (d, w) -> {
                ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard == null) return;
                ClipData clip = ClipData.newPlainText("Claude sign-in", url);
                // Keeps the link out of the clipboard preview of Android 13 and later
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(Build.VERSION.SDK_INT >= 33 ? ClipDescription.EXTRA_IS_SENSITIVE : "android.content.extra.IS_SENSITIVE", true);
                clip.getDescription().setExtras(extras);
                clipboard.setPrimaryClip(clip);
                mActivity.showToast(mActivity.getString(R.string.terminow_launcher_copied), false);
            })
            .setNegativeButton(R.string.terminow_launcher_login_link_later, null)
            .show();
    }

    private void showFailure(@NonNull String name, @NonNull String reason) {
        if (mActivity.isFinishing()) return;
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_launcher_failed_title, name))
            .setMessage(reason)
            .setPositiveButton(R.string.terminow_launcher_open_launcher, (d, w) -> show())
            .setNeutralButton(R.string.terminow_launcher_open_plain_shell, (d, w) -> mHost.openShell(new File(TermuxConstants.TERMUX_HOME_DIR_PATH)))
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Launch profiles, tmux and the default of new Local Terminals
    // ---------------------------------------------------------------------------------------

    private boolean isLocalTmuxInstalled() {
        return new File(mTargets.getPrefixDir(), "bin/tmux").exists();
    }

    /**
     * Start a launch profile in a new tab. A custom command is shown first, the first time and
     * after it changed. With tmux "attach", the tab of the profile that is open already is shown
     * instead of attaching a second time.
     */
    void launchProfile(@NonNull TerminowLaunchProfile profile) {
        String name = profile.icon + " " + profile.name;
        Environment environment = environmentFor(profile.environment);
        if (environment == null) {
            showFailure(name, mActivity.getString(R.string.terminow_profiles_env_missing, profile.environment));
            return;
        }
        String user = environment.container == null ? "root" : profile.user;
        if (environment.container != null && !TerminowMcpTargets.getUsers(environment.container).contains(user)) {
            showFailure(name, mActivity.getString(R.string.terminow_profiles_user_missing, user, environment.label));
            return;
        }
        String folder = profile.folder.isEmpty() ? homeOf(environment, user) : profile.folder;
        if (resolve(environment, folder) == null) {
            showFailure(name, mActivity.getString(R.string.terminow_profiles_folder_missing, folder));
            return;
        }
        if (!profile.needsConfirmation()) {
            runProfile(profile, environment, user, folder);
            return;
        }
        TextView commandView = text(profile.command, 13, R.color.terminow_text);
        commandView.setTypeface(Typeface.MONOSPACE);
        commandView.setTextIsSelectable(true);
        LinearLayout box = vertical();
        box.setPadding(dp(22), dp(6), dp(22), 0);
        box.addView(text(mActivity.getString(R.string.terminow_profiles_confirm_message, environment.label, folder), 13,
            R.color.terminow_text_secondary), matchWrap());
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        box.addView(commandView, params);
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_profiles_confirm_title, profile.name))
            .setView(box)
            .setPositiveButton(R.string.terminow_tools_run, (d, w) -> {
                profile.confirmedCommand = profile.command;
                mProfiles.save(profile);
                runProfile(profile, environment, user, folder);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void runProfile(@NonNull TerminowLaunchProfile profile, @NonNull Environment environment, @NonNull String user,
                            @NonNull String folder) {
        boolean tmux = !TerminowLaunchPlan.TMUX_OFF.equals(profile.tmux);
        String key = tmux ? "tmux|" + environment.key + "|" + profile.getTmuxName() : "profile|" + profile.id;
        launchPlan(key, environment, user, folder, profile.tool, profile.command, profile.tmux, profile.getTmuxName(),
            profile.icon + " " + profile.name, !tmux, toolLabel(profile.tool, profile.name));
    }

    /** Open a tmux session of an environment in a new tab, creating it if it is gone. */
    private void launchTmux(@NonNull Environment environment, @NonNull String name) {
        TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.TMUX, 0, () -> {
            String user = getUser(environment);
            launchPlan("tmux|" + environment.key + "|" + user + "|" + name, environment, user, getFolder(environment),
                TerminowLaunchPlan.TOOL_SHELL, "", TerminowLaunchPlan.TMUX_ATTACH, name,
                mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "tmux " + name), false, "bash");
            mStore.addTmuxRecent(stateKey(environment), name);
            // The list shows it as attached now
            mTmux.remove(stateKey(environment));
        });
    }

    private void newTmuxSession(@NonNull Environment environment) {
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setText(environment.isLocal() ? "main" : "ubuntu");
        field.setTextColor(color(R.color.terminow_text));
        LinearLayout box = vertical();
        box.setPadding(dp(22), dp(6), dp(22), 0);
        box.addView(field, matchWrap());
        TextView error = text(mActivity.getString(R.string.terminow_tools_tmux_name_hint), 12, R.color.terminow_text_secondary);
        box.addView(error, matchWrap());
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_tools_tmux_new)
            .setView(box)
            .setPositiveButton(R.string.terminow_launcher_open, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = field.getText().toString().trim();
            if (!TerminowLaunchPlan.isValidTmuxName(name)) {
                error.setTextColor(color(R.color.terminow_mcp_warn));
                return;
            }
            dialog.dismiss();
            launchTmux(environment, name);
        }));
        dialog.show();
    }

    /** Build what to run with {@link TerminowLaunchPlan} and start it, telling the user if tmux was missing. */
    @Nullable
    private TerminalSession launchPlan(@NonNull String key, @NonNull Environment environment, @NonNull String user,
                                       @NonNull String folder, @NonNull String tool, @NonNull String command,
                                       @NonNull String tmuxMode, @NonNull String tmuxName, @NonNull String tabName,
                                       boolean alwaysNew, @NonNull String toolLabel) {
        TerminowLaunchPlan plan;
        try {
            plan = TerminowLaunchPlan.build(mTargets.getPrefixDir().getPath(), environment.isLocal() ? "" : environment.key, user,
                folder, tool, command, tmuxMode, tmuxName, isLocalTmuxInstalled(), bindsOf(environment));
        } catch (IllegalArgumentException e) {
            showFailure(tabName, String.valueOf(e.getMessage()));
            return null;
        }
        boolean tmux = !TerminowLaunchPlan.TMUX_OFF.equals(tmuxMode) && !plan.tmuxMissing &&
            !TerminowLaunchPlan.TOOL_CLAUDE_LOGIN.equals(tool);
        SessionInfo info = new SessionInfo(environment.key, environment.shortName(), toolLabel, folder, tmux ? tmuxName : "");
        info.user = user;
        TerminalSession session = launch(key, plan.executable, plan.arguments.toArray(new String[0]), plan.workingDirectory, tabName,
            alwaysNew, info);
        if (session != null && plan.tmuxMissing)
            mActivity.showToast(mActivity.getString(R.string.terminow_tools_tmux_missing_started, environment.label), true);
        return session;
    }

    @NonNull
    private String toolLabel(@NonNull String tool, @NonNull String customName) {
        switch (tool) {
            case TerminowLaunchPlan.TOOL_SHELL: return "bash";
            case TerminowLaunchPlan.TOOL_CUSTOM: return customName;
            case TerminowLaunchPlan.TOOL_CLAUDE_LOGIN: return "Claude login";
            default: return "Claude";
        }
    }

    /**
     * Start what the default launch profile of the settings says, for a new Local Terminal.
     * Returns {@code false} to open the TermiNow shell as before: when the default is the local
     * shell, or when what it names is gone, which is told to the user.
     *
     * @param number The number of the new tab, for the name of its tmux session.
     */
    boolean launchDefault(int number) {
        String value = mStore.getDefaultLaunchProfile();
        if (value.equals("local")) return false;
        if (value.startsWith("ubuntu:")) {
            Environment environment = environmentFor(value.substring("ubuntu:".length()));
            if (environment == null || environment.container == null) {
                mActivity.showToast(mActivity.getString(R.string.terminow_profiles_default_missing), true);
                return false;
            }
            String tmuxMode = mStore.shouldStartInTmux() ? TerminowLaunchPlan.TMUX_ATTACH : TerminowLaunchPlan.TMUX_OFF;
            String tmuxName = TerminowLaunchPlan.tmuxNameFor(environment.key) + number;
            if (!TerminowLaunchPlan.isValidTmuxName(tmuxName)) tmuxName = "ubuntu" + number;
            launchPlan("default|" + number + "|" + System.nanoTime(), environment, getUser(environment), getFolder(environment),
                TerminowLaunchPlan.TOOL_SHELL, "", tmuxMode, tmuxName,
                mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "bash"), true, "bash");
            return true;
        }
        for (TerminowLaunchProfile profile : mStore.getLaunchProfiles()) {
            if (profile.id.equals(value)) {
                launchProfile(profile);
                return true;
            }
        }
        mActivity.showToast(mActivity.getString(R.string.terminow_profiles_default_missing), true);
        return false;
    }

    /** Open a shell of Ubuntu from the add menu, or show how to set it up. */
    void openUbuntuTerminal() {
        Environment environment = currentEnvironment().container != null ? currentEnvironment() : firstContainer();
        if (environment == null) {
            show();
            return;
        }
        launchUbuntu(environment);
    }

    /** The environment that Claude Code is started in from the add menu: the chosen one, or else Ubuntu. */
    @NonNull
    private Environment claudeEnvironment() {
        if (currentEnvironment().container != null) return currentEnvironment();
        Environment container = firstContainer();
        return container != null ? container : currentEnvironment();
    }

    /** "Claude — project", for the add menu. */
    @NonNull
    String getClaudeMenuTitle() {
        Environment environment = claudeEnvironment();
        String folder = getFolder(environment);
        String name = folder.equals(homeOf(environment)) ? "~" : new File(folder).getName();
        return mActivity.getString(R.string.terminow_tools_claude_project, name);
    }

    /** Start Claude Code in the chosen project from the add menu, or show the launcher if it is not ready. */
    void openClaudeForProject() {
        Environment environment = claudeEnvironment();
        ClaudeState state = mClaude.get(stateKey(environment));
        if (state != null && state.checked && state.isInstalled() && !state.loggedIn.equals("no")) {
            launchClaude(environment, new String[0], false);
            return;
        }
        // Not checked yet, not installed or not logged in: the launcher shows what to do
        setEnvironment(environment.key);
        show();
    }

    // ---------------------------------------------------------------------------------------
    // What each tab runs, and the terminal toolbar
    // ---------------------------------------------------------------------------------------

    /**
     * Open a new tab of a Linux environment, as the user, in a Linux folder, for the File
     * Manager's "Open in Terminal" and the environment cards. It is started from the TermiNow
     * runtime, never from inside another environment, and nothing is typed into other tabs.
     */
    public void openLinuxTerminal(@NonNull String alias, @NonNull String user, @NonNull String folder) {
        Environment environment = environmentFor(alias);
        if (environment == null || environment.container == null) {
            showFailure(alias, mActivity.getString(R.string.terminow_profiles_env_missing, alias));
            return;
        }
        if (!TerminowMcpTargets.getUsers(environment.container).contains(user)) {
            showFailure(environment.shortName(), mActivity.getString(R.string.terminow_profiles_user_missing, user, environment.label));
            return;
        }
        String path = folder.isEmpty() ? homeOf(environment, user) : folder;
        if (resolve(environment, path) == null) {
            showFailure(environment.shortName(), mActivity.getString(R.string.terminow_profiles_folder_missing, path));
            return;
        }
        launchPlan("linux|" + alias + "|" + user + "|" + System.nanoTime(), environment, user, path, TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_OFF, "", mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "bash"), true, "bash");
    }

    /**
     * The folder that a restored tab of an environment opens in: its own if it is still there,
     * else the home of the user. {@code null} when the environment or the user is gone.
     */
    @Nullable
    String restorableFolder(@NonNull String alias, @NonNull String user, @NonNull String folder) {
        Environment environment = environmentFor(alias);
        if (environment == null || environment.container == null) return null;
        String who = user.isEmpty() ? getUser(environment) : user;
        if (!TerminowMcpTargets.getUsers(environment.container).contains(who)) return null;
        if (!folder.isEmpty() && resolve(environment, folder) != null) return folder;
        return homeOf(environment, who);
    }

    /**
     * Open a restored tab of an environment again: a new shell of the user in the folder, in its
     * tmux session if it had one, which is attached to if it still runs. Nothing else is started.
     */
    @Nullable
    TerminalSession restoreLinuxTerminal(@NonNull String alias, @NonNull String user, @NonNull String folder,
                                         @NonNull String tmuxName, @NonNull String tabName) {
        Environment environment = environmentFor(alias);
        if (environment == null || environment.container == null) return null;
        String who = user.isEmpty() ? getUser(environment) : user;
        boolean tmux = !tmuxName.isEmpty() && TerminowLaunchPlan.isValidTmuxName(tmuxName);
        return launchPlan("restore|" + alias + "|" + System.nanoTime(), environment, who, folder, TerminowLaunchPlan.TOOL_SHELL, "",
            tmux ? TerminowLaunchPlan.TMUX_ATTACH : TerminowLaunchPlan.TMUX_OFF, tmux ? tmuxName : "",
            tabName.isEmpty() ? mActivity.getString(R.string.terminow_launcher_tab, environment.shortName(), "bash") : tabName, true, "bash");
    }

    /** The tabs that TermiNow started in an environment and that still run. */
    @NonNull
    public List<TerminalSession> sessionsOf(@NonNull String alias) {
        List<TerminalSession> sessions = new ArrayList<>();
        for (Map.Entry<TerminalSession, SessionInfo> entry : mInfo.entrySet()) {
            TerminalSession session = entry.getKey();
            if (session != null && session.isRunning() && alias.equals(entry.getValue().environmentKey)) sessions.add(session);
        }
        return sessions;
    }

    /** Remember what a tab runs that TermiNow started some other way, like a Local Terminal. */
    public void track(@NonNull TerminalSession session, @NonNull SessionInfo info) {
        mInfo.put(session, info);
        updateToolbar(session);
    }

    @Nullable
    public SessionInfo getSessionInfo(@NonNull TerminalSession session) {
        return mInfo.get(session);
    }

    /** Use the toolbar under the tabs: environment, folder, tools and more. */
    public void bindToolbar(@NonNull View toolbar) {
        mToolbarEnvironment = toolbar.findViewById(R.id.terminow_toolbar_environment);
        mToolbarFolder = toolbar.findViewById(R.id.terminow_toolbar_folder);
        mToolbarEnvironment.setOnClickListener(v -> switchEnvironment());
        mToolbarFolder.setOnClickListener(v -> changeFolder());
        toolbar.findViewById(R.id.terminow_toolbar_tools).setOnClickListener(v -> show());
        toolbar.findViewById(R.id.terminow_toolbar_more).setOnClickListener(this::showToolbarMenu);
    }

    /** Show what the tab runs in the toolbar, "Unknown" for a tab that TermiNow did not start. */
    public void updateToolbar(@Nullable TerminalSession session) {
        if (mToolbarEnvironment == null || mToolbarFolder == null) return;
        TerminalSession current = mActivity.getCurrentSession();
        if (session != current) session = current;
        SessionInfo info = session != null ? mInfo.get(session) : null;
        String unknown = mActivity.getString(R.string.terminow_toolbar_unknown);
        String environment = info != null ? info.environmentLabel : unknown;
        mToolbarEnvironment.setText(environment + "  ▾");
        mToolbarEnvironment.setContentDescription(mActivity.getString(R.string.terminow_toolbar_environment_description, environment));
        String folder = info != null && !info.folder.isEmpty() ? info.folder : "";
        if (!folder.isEmpty() && info.environmentKey != null) {
            Environment env = environmentFor(info.environmentKey);
            if (env != null && folder.equals(homeOf(env))) folder = "~";
            else if (folder.startsWith(TermuxConstants.TERMUX_HOME_DIR_PATH + "/"))
                folder = "~" + folder.substring(TermuxConstants.TERMUX_HOME_DIR_PATH.length());
        }
        mToolbarFolder.setText(folder.isEmpty() ? unknown : folder);
        mToolbarFolder.setContentDescription(mActivity.getString(R.string.terminow_toolbar_folder_description, folder.isEmpty() ? unknown : folder));
    }

    /**
     * Go to a tab of the chosen environment: a shell tab of it that the launcher knows and that
     * still runs, or else a new one. The tab that is shown is never changed.
     */
    private void switchEnvironment() {
        List<Environment> environments = environments();
        boolean noContainer = environments.size() == 1;
        CharSequence[] labels = new CharSequence[environments.size() + (noContainer ? 1 : 0)];
        for (int i = 0; i < environments.size(); i++) labels[i] = environments.get(i).label;
        if (noContainer) labels[environments.size()] = mActivity.getString(R.string.terminow_launcher_ubuntu_setup) + "…";
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_launcher_environment)
            .setItems(labels, (d, which) -> {
                if (which >= environments.size()) {
                    show();
                    return;
                }
                Environment environment = environments.get(which);
                setEnvironment(environment.key);
                TerminalSession current = mActivity.getCurrentSession();
                for (Map.Entry<TerminalSession, SessionInfo> entry : mInfo.entrySet()) {
                    TerminalSession session = entry.getKey();
                    SessionInfo info = entry.getValue();
                    if (session == null || !session.isRunning() || !environment.key.equals(info.environmentKey) ||
                        !"bash".equals(info.tool)) continue;
                    if (session == current) {
                        mActivity.showToast(mActivity.getString(R.string.terminow_toolbar_already_in, environment.label), false);
                    } else {
                        mHost.showSession(session);
                        mActivity.showToast(mActivity.getString(R.string.terminow_toolbar_switched, session.mSessionName), false);
                    }
                    return;
                }
                if (environment.isLocal()) launchShell(environment);
                else launchUbuntu(environment);
            })
            .show();
    }

    /**
     * Choose the working folder of the environment of the tab. A running shell is never told
     * to change its folder: the folder is used for new tabs, and one can be opened there now.
     */
    private void changeFolder() {
        TerminalSession session = mActivity.getCurrentSession();
        SessionInfo info = session != null ? mInfo.get(session) : null;
        Environment environment = info != null && info.environmentKey != null ? environmentFor(info.environmentKey) : null;
        if (environment == null) environment = currentEnvironment();
        Environment chosen = environment;
        String start = info != null && !info.folder.isEmpty() && resolve(chosen, info.folder) != null ? info.folder : getFolder(chosen);
        pickFolder(chosen, start, path -> {
            setEnvironment(chosen.key);
            putPref("folder:" + chosen.key, path);
            new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_toolbar_folder_set)
                .setMessage(mActivity.getString(R.string.terminow_toolbar_folder_set_message, path, chosen.label))
                .setPositiveButton(R.string.terminow_toolbar_open_tab_here, (d, w) -> {
                    if (chosen.isLocal()) launchShell(chosen);
                    else launchUbuntu(chosen);
                })
                .setNegativeButton(R.string.terminow_launcher_login_link_later, null)
                .show();
        });
    }

    private void showToolbarMenu(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(mActivity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.terminow_toolbar_session_details);
        menu.getMenu().add(0, 2, 1, R.string.terminow_profiles_open_from);
        menu.getMenu().add(0, 3, 2, R.string.terminow_profiles_manage);
        menu.getMenu().add(0, 4, 3, R.string.terminow_toolbar_copy_folder);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showSessionDetails(); return true;
                case 2: mProfiles.pickAndLaunch(); return true;
                case 3: mProfiles.showManager(); return true;
                case 4: copyFolder(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void copyFolder() {
        TerminalSession session = mActivity.getCurrentSession();
        SessionInfo info = session != null ? mInfo.get(session) : null;
        if (info == null || info.folder.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_toolbar_folder_unknown), false);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("folder", info.folder));
        mActivity.showToast(mActivity.getString(R.string.terminow_launcher_copied), false);
    }

    /** What is known about the tab, with "Unknown" for everything that TermiNow can not know. */
    private void showSessionDetails() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        SessionInfo info = mInfo.get(session);
        String unknown = mActivity.getString(R.string.terminow_toolbar_unknown);
        String state = session.isRunning() ? mActivity.getString(R.string.terminow_toolbar_state_running) :
            session.getPid() <= 0 ? mActivity.getString(R.string.terminow_toolbar_state_starting) :
            mActivity.getString(R.string.terminow_toolbar_state_ended, session.getExitStatus());
        String message = mActivity.getString(R.string.terminow_toolbar_details,
            info != null ? info.environmentLabel : unknown,
            info != null ? info.tool : unknown,
            info != null && !info.folder.isEmpty() ? info.folder : unknown,
            info == null ? unknown : info.tmux.isEmpty() ? mActivity.getString(R.string.terminow_toolbar_no_tmux) : info.tmux,
            state);
        if (info == null) message += "\n\n" + mActivity.getString(R.string.terminow_toolbar_untracked);
        else message += "\n\n" + mActivity.getString(R.string.terminow_toolbar_tracked);
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(session.mSessionName != null ? session.mSessionName : mActivity.getString(R.string.terminow_toolbar_session_details))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
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
        CharSequence[] labels = new CharSequence[environments.size() + 1];
        for (int i = 0; i < environments.size(); i++) labels[i] = environments.get(i).label;
        labels[environments.size()] = "+ " + mActivity.getString(R.string.terminow_linux_browse);
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_launcher_environment)
            .setItems(labels, (d, which) -> {
                if (which >= environments.size()) {
                    if (mLinux != null) mLinux.showBrowse(true);
                    return;
                }
                setEnvironment(environments.get(which).key);
                checkTmux(currentEnvironment(), false);
                render();
            })
            .show();
    }

    @NonNull
    String getUser(@NonNull Environment environment) {
        if (environment.container == null) return "root";
        TerminowLinuxEnv linux = linuxOf(environment);
        String fallback = linux != null ? linux.defaultUser : "root";
        String user = getPrefs().optString("user:" + environment.key, fallback);
        return TerminowMcpTargets.getUsers(environment.container).contains(user) ? user : fallback;
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
                render();
                checkClaude(environment, false);
                checkTmux(environment, false);
            })
            .show();
    }

    /** The folder for the environment, its home if none was chosen or the chosen one is gone. */
    @NonNull
    private String getFolder(@NonNull Environment environment) {
        String folder = getPrefs().optString("folder:" + environment.key);
        if (!folder.isEmpty() && resolve(environment, folder) != null) return folder;
        // The default folder of the environment's settings, when it can be opened
        TerminowLinuxEnv linux = linuxOf(environment);
        if (linux != null && !linux.defaultFolder.isEmpty() && resolve(environment, linux.defaultFolder) != null) return linux.defaultFolder;
        return homeOf(environment);
    }

    @NonNull
    private String homeOf(@NonNull Environment environment) {
        return homeOf(environment, getUser(environment));
    }

    @NonNull
    String homeOf(@NonNull Environment environment, @NonNull String user) {
        return environment.container == null ? TermuxConstants.TERMUX_HOME_DIR_PATH :
            TerminowMcpTargets.getGuestHome(environment.container, user);
    }

    /**
     * The folder of the app for a path of the environment, {@code null} if it is not a folder
     * that the environment can start in. Paths of the Storage Access Framework are not paths
     * at all and can not be used.
     */
    @Nullable
    File resolve(@NonNull Environment environment, @NonNull String path) {
        if (!path.startsWith("/") || path.contains("\0") || path.contains("\n")) return null;
        File file;
        if (environment.container != null) {
            // Like the environment resolves it: links inside it, and its bound folders
            TerminowLinuxEnv linux = linuxOf(environment);
            file = linux != null ? mLinux.filesOf(linux).hostFileOf(path) : TerminowMcpTargets.resolveGuestPath(environment.container, path);
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

    interface FolderPicked {
        void onPicked(@NonNull String path);
    }

    /**
     * Choose a folder of the environment by browsing its folders or typing a path. A document
     * of the Storage Access Framework (content://) has no path that a shell can start in, so it
     * is refused with a hint to copy it into TermiNow with the File Manager.
     */
    void pickFolder(@NonNull Environment environment, @NonNull String start, @NonNull FolderPicked picked) {
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
            error.setText(dir != null ? "" : mActivity.getString(path.startsWith("content:") ?
                R.string.terminow_launcher_folder_saf : R.string.terminow_launcher_folder_invalid));
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
                    error.setText(mActivity.getString(path.startsWith("content:") ?
                        R.string.terminow_launcher_folder_saf : R.string.terminow_launcher_folder_invalid));
                    return;
                }
                dialog.dismiss();
                picked.onPicked(path);
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
