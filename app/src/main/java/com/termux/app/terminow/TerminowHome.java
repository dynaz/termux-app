package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.ShellUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.view.KeyboardUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import com.termux.app.terminow.files.TerminowSftpFs;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.linux.TerminowLinuxFs;
import com.termux.app.terminow.linux.TerminowLinuxEnv;
import java.io.OutputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The home screen of {@link TermuxActivity} and the tab bar above it. The home screen is shown over
 * the terminal and lists the saved hosts and snippets, the tab bar lists the running sessions.
 */
public class TerminowHome {

    private static final int PAGE_HOSTS = 0;
    private static final int PAGE_SNIPPETS = 1;
    private static final int PAGE_SETTINGS = 2;
    private static final int PAGE_KEYCHAIN = 3;
    private static final int PAGE_FORWARDING = 4;
    private static final int PAGE_KNOWN_HOSTS = 5;
    private static final int PAGE_LOGS = 6;
    private static final int PAGE_MCP = 7;
    private static final int PAGE_FILES = 8;
    private static final int PAGE_LAUNCH = 9;
    private static final int PAGE_AI = 10;
    private static final int PAGE_MCP_BRIDGE = 11;

    private static final Pattern KEY_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final int KEY_FILE_MAX_SIZE = 64 * 1024;
    /** The request code for the file picker to import a key. */
    private static final int REQUEST_IMPORT_KEY = 2001;
    private static final int REQUEST_IMPORT_CERTIFICATE = 2002;
    private static final int REQUEST_UPLOAD_TMP = 2003;
    private static final int REQUEST_IMPORT_HOSTS = 2004;
    private static final int REQUEST_IMPORT_ROOTFS = 2006;
    private static final int REQUEST_SAVE_DEBUG_LOG = 2009;
    /** A certificate is one line of some kilobytes. */
    private static final int CERTIFICATE_MAX_SIZE = 64 * 1024;

    /** How long to wait for the shell of a new session to start before giving up on sending it a command. */
    private static final int WRITE_RETRY_COUNT = 50;
    private static final int WRITE_RETRY_DELAY_MS = 100;

    /** How long to wait for the password prompt of a host after connecting to it. */
    private static final int PASSWORD_PROMPT_CHECK_COUNT = 200;
    private static final int PASSWORD_PROMPT_CHECK_DELAY_MS = 300;
    /** The line that ssh waits at for the password of a host, like `root@host's password: `. */
    private static final Pattern PASSWORD_PROMPT_PATTERN = Pattern.compile("'s password:\\s*$", Pattern.CASE_INSENSITIVE);
    /** The password prompt of a telnet login, like `Password: `. */
    private static final Pattern TELNET_PASSWORD_PROMPT_PATTERN = Pattern.compile("^\\s*password:\\s*$", Pattern.CASE_INSENSITIVE);

    /** The part of the screen width that the host form takes, like the Termius host panel. */
    private static final float HOST_PANEL_SCREEN_WIDTH_FRACTION = 0.36f;
    private static final int HOST_PANEL_MIN_WIDTH_DP = 340;
    /** The host form takes the whole width of screens narrower than this. */
    private static final int HOST_PANEL_MIN_SCREEN_WIDTH_DP = 600;

    private static final String LOG_TAG = "TerminowHome";

    private final TermuxActivity mActivity;
    private final TerminowFiles mFiles;
    /** The key field of the host form to fill with the key that is added next, if any. */
    @Nullable
    private WeakReference<EditText> mKeyTarget;
    private final TerminowStore mStore;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final View mHomeView;
    private final View mTabBar;
    private final View mTerminalToolbar;
    private final TextView mHomeTitle;
    private final ImageView mVaultSyncIcon;
    private final View mVaultButton;
    private final TerminowVaultsUi mVaultsUi;
    private final TerminowKeyPanel mKeyPanel;
    private final TerminowLinuxUi mLinuxUi;
    private TerminowQuickLaunch mQuickLaunch;
    private TerminowBackupUi mBackupUi;
    private View mRailLaunch;
    private final View mLauncherButton;
    private final View mHomeButton;
    private final HorizontalScrollView mTabsScrollView;
    private final View mNotificationsBadge;
    private final LinearLayout mTabsView;
    private final View mRailHosts;
    private final View mRailSnippets;
    private final View mRailSettings;
    private final View mRailKeychain;
    private final View mRailForwarding;
    private final View mRailKnownHosts;
    private final View mRailLogs;
    private final View mRailMcp;
    private final View mMcpScrollView;
    private final TerminowMcpPage mMcpPage;
    private final View mRailFiles;
    private final View mRailAi;
    private final View mAiScrollView;
    private TerminowAiSettings mAiPage;
    private final View mRailMcpBridge;
    private final View mMcpBridgeScrollView;
    private TerminowMcpBridgeUi mMcpBridgePage;
    private final View mExplorerView;
    private final TerminowExplorer mExplorer;
    private final TerminowLauncher mLauncher;
    /** The saved tabs, to bring them back after the app was closed. */
    private final TerminowWorkspace mWorkspace;
    /** The previous output of the next tab that {@link #startSession} starts, for a restored tab. */
    @Nullable private byte[] mNextPrelude;
    private final View mHeaderView;
    private final View mGridView;
    private final View mFabView;
    private final View mSettingsScrollView;
    private final TerminowSettings mSettings;
    private final View mPremiumButton;
    private final EditText mSearchView;
    /** Sorts the keychain, only shown on it. */
    private final TextView mSortView;
    /** The key that a certificate file that is being picked is for. */
    @Nullable private KeyItem mCertificateKey;
    /** The terminal that files that are being picked are uploaded for. */
    @Nullable private WeakReference<TerminalSession> mUploadSession;
    private final TextView mSectionTitleView;
    private final View mActionsView;
    private final TextView mEmptyView;
    private final ItemsAdapter mAdapter = new ItemsAdapter();

    private int mPage = PAGE_HOSTS;
    /** The group whose hosts are shown on the hosts page, {@code null} for the hosts that are not in a group. */
    @Nullable
    private String mCurrentGroup;
    private List<String> mGroups = new ArrayList<>();
    private List<TerminowStore.Forward> mForwards = new ArrayList<>();
    private List<TerminowStore.Host> mHosts = new ArrayList<>();
    private List<TerminowStore.Snippet> mSnippets = new ArrayList<>();
    /** The items of the current page that match the search, a null item is the local terminal. */
    private final List<Object> mItems = new ArrayList<>();

    public TerminowHome(@NonNull TermuxActivity activity) {
        mActivity = activity;
        mStore = new TerminowStore(activity);
        mFiles = new TerminowFiles(activity);

        mHomeView = activity.findViewById(R.id.terminow_home);
        mTabBar = activity.findViewById(R.id.terminow_tab_bar);
        mHomeButton = activity.findViewById(R.id.terminow_home_button);
        mTabsScrollView = activity.findViewById(R.id.terminow_tabs_scroll);
        mNotificationsBadge = activity.findViewById(R.id.terminow_notifications_badge);
        activity.findViewById(R.id.terminow_notifications_button).setOnClickListener(v ->
            new TerminowNotifications(mActivity, mStore, this::updateNotificationsBadge).show());
        mTabsView = activity.findViewById(R.id.terminow_tabs);
        mWorkspace = TerminowWorkspace.of(activity);
        mLauncher = new TerminowLauncher(activity, mStore, new TerminowLauncher.Host() {
            @Nullable
            @Override
            public TerminalSession startSession(@NonNull String executable, @NonNull String[] arguments,
                                                @NonNull String workingDirectory, @NonNull String name) {
                return TerminowHome.this.startSession(executable, arguments, workingDirectory, name);
            }

            @Override
            public void showSession(@NonNull TerminalSession session) {
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
                hide();
            }

            @Override
            public void openShell(@NonNull File directory) {
                openTerminalAt(directory);
            }
        });
        mLinuxUi = new TerminowLinuxUi(activity, mStore, new TerminowLinuxUi.Host() {
            @Override
            public void openFiles(@NonNull com.termux.app.terminow.linux.TerminowLinuxEnv env, @NonNull String user, @NonNull String path) {
                show();
                setPage(PAGE_FILES);
                mExplorer.openLinux(env, path);
            }

            @Override
            public void openTerminal(@NonNull com.termux.app.terminow.linux.TerminowLinuxEnv env, @NonNull String user, @NonNull String path) {
                mLauncher.openLinuxTerminal(env.alias, user, path);
            }

            @NonNull
            @Override
            public List<TerminalSession> sessionsOf(@NonNull String alias) {
                return mLauncher.sessionsOf(alias);
            }

            @Override
            public void startUbuntuSetup() {
                mLauncher.confirmUbuntuSetup();
            }

            @Override
            public void onChanged() {
                if (mExplorer != null) mExplorer.refresh();
                if (mQuickLaunch != null) mQuickLaunch.refresh();
            }
        });
        mLauncher.setLinux(mLinuxUi);
        mLauncherButton = activity.findViewById(R.id.terminow_launcher_button);
        mLauncherButton.setOnClickListener(v -> showQuickLaunch());
        mTerminalToolbar = activity.findViewById(R.id.terminow_terminal_toolbar);
        mLauncher.bindToolbar(mTerminalToolbar);
        // The tools of a terminal open Quick Launch over it: its sessions keep running
        mTerminalToolbar.findViewById(R.id.terminow_toolbar_tools).setOnClickListener(v -> showQuickLaunch());
        mBackupUi = new TerminowBackupUi(activity, mStore, mLinuxUi, mLauncher, new TerminowBackupUi.Host() {
            @Override
            public void backupToServer(@NonNull String alias, @NonNull String title) {
                showBackupDialog(alias, title);
            }

            @Override
            public void environmentsChanged() {
                if (mExplorer != null) mExplorer.refresh();
                if (mQuickLaunch != null) mQuickLaunch.refresh();
            }
        });
        mQuickLaunch = new TerminowQuickLaunch(activity, mStore, mLauncher, mLinuxUi, new TerminowQuickLaunch.Host() {
            @Override
            public void connect(boolean sftp) {
                showConnectDialog(sftp);
            }

            @Override
            public void addHost() {
                mHosts = mStore.getHosts();
                mGroups = mStore.getGroups();
                showHostDialog(null);
            }

            @Override
            public void openLocalFiles() {
                setPage(PAGE_FILES);
            }

            @NonNull
            @Override
            public List<TerminalSession> sessions() {
                List<TerminalSession> sessions = new ArrayList<>();
                TermuxService service = mActivity.getTermuxService();
                if (service != null)
                    for (TermuxSession session : service.getTermuxSessions()) sessions.add(session.getTerminalSession());
                return sessions;
            }

            @Nullable
            @Override
            public TerminalSession currentSession() {
                return mActivity.getCurrentSession();
            }

            @Override
            public void showSession(@NonNull TerminalSession session) {
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
                hide();
            }

            @Override
            public void closeSession(@NonNull TerminalSession session) {
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setMessage(mActivity.getString(R.string.terminow_quick_close_tab_confirm,
                        session.mSessionName != null ? session.mSessionName : ""))
                    .setPositiveButton(R.string.terminow_quick_close_tab, (d, w) -> {
                        mActivity.getTermuxTerminalSessionClient().closeSession(session);
                        mHandler.postDelayed(() -> mQuickLaunch.refresh(), 300);
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            }

            @Override
            public void renameSession(@NonNull TerminalSession session) {
                mActivity.getTermuxTerminalSessionClient().renameSession(session);
            }

            @Override
            public void backupEnvironment(@NonNull String alias, @NonNull String title) {
                showBackupDialog(alias, title);
            }

            @Override
            public void backupToPhone(@NonNull String alias, @NonNull String title) {
                mBackupUi.showBackup(alias, title);
            }

            @Override
            public void backupAndRestore(@NonNull String alias, @NonNull String title) {
                TerminowWhatsNew.seen(mActivity, TerminowWhatsNew.Feature.BACKUP);
                mBackupUi.showMenu(alias, title);
            }

            @Override
            public void restoreFromPhone() {
                mBackupUi.pickRestoreFile();
            }

            @Override
            public void reconnectHost(@NonNull String hostKey) {
                mHosts = mStore.getHosts();
                TerminowStore.Host host = TerminowStore.findHost(mHosts, hostKey);
                if (host != null) TerminowHome.this.connect(host);
                else mActivity.showToast(mActivity.getString(R.string.terminow_rdp_host_gone), true);
            }

            @Override
            public void pickRootfs() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                try {
                    mActivity.startActivityForResult(intent, REQUEST_IMPORT_ROOTFS);
                } catch (Exception e) {
                    mActivity.showToast(String.valueOf(e.getMessage()), true);
                }
            }
        }, activity.findViewById(R.id.terminow_quick_scroll));
        mHomeTitle = activity.findViewById(R.id.terminow_home_title);
        mVaultSyncIcon = activity.findViewById(R.id.terminow_vault_sync_icon);
        mVaultButton = activity.findViewById(R.id.terminow_vault_button);
        mVaultsUi = new TerminowVaultsUi(activity, mStore, this::reloadVault);
        mVaultButton.setOnClickListener(v -> mVaultsUi.showSwitcher(v));
        // The members of the team at the end of the actions of the hosts, like Termius
        mVaultsUi.bindTeamChip(activity.findViewById(R.id.terminow_team_chip));
        // The cloud says whether the vault is synced, a tap says why and offers to sync
        mVaultSyncIcon.setOnClickListener(v -> mVaultsUi.showStatus());
        mVaultSyncIcon.setContentDescription(activity.getString(R.string.terminow_vault_status));
        mKeyPanel = new TerminowKeyPanel(activity, mStore, activity.findViewById(R.id.terminow_key_panel));
        activity.findViewById(R.id.terminow_toolbar_keys).setOnClickListener(v -> mKeyPanel.toggle());
        mRailHosts = activity.findViewById(R.id.terminow_rail_hosts);
        mRailSnippets = activity.findViewById(R.id.terminow_rail_snippets);
        mSearchView = activity.findViewById(R.id.terminow_search);
        mSortView = activity.findViewById(R.id.terminow_sort);
        mSortView.setOnClickListener(this::showKeychainSort);
        mSortView.setText("za".equals(mStore.getKeychainSort()) ? "Za" : "Az");
        // Biometric keys sign through the agent of the app, with prompts over this activity
        TerminowBiometricKeys.setActivity(activity);
        TerminowBiometricKeys.startAgent();
        mSectionTitleView = activity.findViewById(R.id.terminow_section_title);
        mEmptyView = activity.findViewById(R.id.terminow_empty);

        mHomeButton.setOnClickListener(v -> show());

        mRailLaunch = activity.findViewById(R.id.terminow_rail_launch);
        mRailLaunch.setOnClickListener(v -> setPage(PAGE_LAUNCH));
        mRailHosts.setOnClickListener(v -> setPage(PAGE_HOSTS));
        mRailSnippets.setOnClickListener(v -> setPage(PAGE_SNIPPETS));
        mRailKeychain = activity.findViewById(R.id.terminow_rail_keychain);
        mRailKeychain.setOnClickListener(v -> setPage(PAGE_KEYCHAIN));
        mRailForwarding = activity.findViewById(R.id.terminow_rail_forwarding);
        mRailForwarding.setOnClickListener(v -> setPage(PAGE_FORWARDING));
        mRailKnownHosts = activity.findViewById(R.id.terminow_rail_known_hosts);
        mRailKnownHosts.setOnClickListener(v -> setPage(PAGE_KNOWN_HOSTS));
        mRailLogs = activity.findViewById(R.id.terminow_rail_logs);
        mRailLogs.setOnClickListener(v -> setPage(PAGE_LOGS));
        mRailMcp = activity.findViewById(R.id.terminow_rail_mcp);
        mRailMcp.setOnClickListener(v -> setPage(PAGE_MCP));
        mMcpScrollView = activity.findViewById(R.id.terminow_mcp_scroll);
        mMcpPage = new TerminowMcpPage(activity, mStore, activity.findViewById(R.id.terminow_mcp));
        mRailFiles = activity.findViewById(R.id.terminow_rail_files);
        mRailFiles.setOnClickListener(v -> setPage(PAGE_FILES));
        mRailAi = activity.findViewById(R.id.terminow_rail_ai);
        mRailAi.setOnClickListener(v -> setPage(PAGE_AI));
        mAiScrollView = activity.findViewById(R.id.terminow_ai_scroll);
        mExplorerView = activity.findViewById(R.id.terminow_explorer);
        mExplorer = new TerminowExplorer(activity, mStore, activity.findViewById(R.id.terminow_explorer), new TerminowExplorer.Host() {
            @Override
            public void openTerminalAt(@NonNull File directory) {
                TerminowHome.this.openTerminalAt(directory);
            }

            @Override
            public void exportFile() {
                mFiles.exportFile();
            }

            @Override
            public void showAccountSettings() {
                setPage(PAGE_SETTINGS);
            }

            @Nullable
            @Override
            public TerminowLinuxUi getLinux() {
                return mLinuxUi;
            }

            @Override
            public void openLinuxTerminal(@NonNull String alias, @NonNull String user, @NonNull String folder) {
                mLauncher.openLinuxTerminal(alias, user, folder);
            }
        });
        mExplorer.setLinuxSessionCounter(alias -> mLauncher.sessionsOf(alias).size());
        mSftpBrowser = new TerminowSftpBrowser(activity, mStore, activity.findViewById(R.id.terminow_sftp_browser));
        // The Local AI Bridge: Claude Code in a Linux environment using allowed hosts over authorized tools
        // Process-scoped singleton: the listener must survive this Activity being backgrounded or
        // recreated while Claude Code keeps using the bridge from a Linux environment.
        mAiBridge = TerminowAiBridgeManager.getInstance(activity.getApplicationContext(), mStore,
            new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), new File(TermuxConstants.TERMUX_HOME_DIR_PATH)));
        mAiBridge.startIfEnabled();
        mAiPage = new TerminowAiSettings(activity, mAiBridge, mStore, activity.findViewById(R.id.terminow_ai));
        // The MCP Bridge: external AI clients reaching shared managed sessions through the relay.
        mMcpBridge = TerminowMcpBridgeManager.getInstance(activity.getApplicationContext(), mStore,
            new TerminowMcpTargets(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), new File(TermuxConstants.TERMUX_HOME_DIR_PATH)));
        mMcpBridge.startIfEnabled();
        mMcpBridgePage = new TerminowMcpBridgeUi(activity, mMcpBridge, mStore, activity.findViewById(R.id.terminow_mcp_bridge), this::openMcpTaskInTerminal, this::openSessionTerminal);
        mRailMcpBridge = activity.findViewById(R.id.terminow_rail_mcp_bridge);
        mRailMcpBridge.setOnClickListener(v -> setPage(PAGE_MCP_BRIDGE));
        mMcpBridgeScrollView = activity.findViewById(R.id.terminow_mcp_bridge_scroll);
        mRailSettings = activity.findViewById(R.id.terminow_rail_settings);
        mRailSettings.setOnClickListener(v -> setPage(PAGE_SETTINGS));
        mHeaderView = activity.findViewById(R.id.terminow_header);
        mGridView = activity.findViewById(R.id.terminow_grid);
        mFabView = activity.findViewById(R.id.terminow_fab);
        mSettingsScrollView = activity.findViewById(R.id.terminow_settings_scroll);
        mSettings = new TerminowSettings(activity, mStore, activity.findViewById(R.id.terminow_settings));
        mSettings.setDebugActions(new TerminowSettings.DebugActions() {
            @Override
            public void showDiagnostics() {
                mLauncher.showLaunchDiagnostics();
            }

            @Override
            public void setRecording(boolean recording) {
                TerminowDebugLog.setRecording(mActivity, recording, TerminowHome.this::allSessions);
            }

            @Override
            public void saveLog() {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TITLE, "terminow-debug-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm",
                    java.util.Locale.ROOT).format(new java.util.Date()) + ".log");
                try {
                    mActivity.startActivityForResult(intent, REQUEST_SAVE_DEBUG_LOG);
                } catch (Exception e) {
                    mActivity.showToast(String.valueOf(e.getMessage()), true);
                }
            }
        });
        // A log that was turned on goes on from the start of the app
        TerminowDebugLog.start(activity, this::allSessions);
        // Account & Premium in the top bar, drawn again when the account or Premium changes
        mPremiumButton = activity.findViewById(R.id.terminow_premium_button);
        mPremiumButton.setOnClickListener(v -> openAccountScreen());
        // Gated pages, like MCP, too: right after a sign in, a purchase or a restore
        mSettings.getAccountUi().addChangeListener(() -> {
            updatePremiumButton();
            if (isVisible()) updateItems();
            // A new Premium can sync the Personal vault: offered once
            if (mVaultsUi != null) mVaultsUi.offerPersonalSync();
        });
        updatePremiumButton();
        mSettings.setVaultSettings(mVaultsUi::showSettings);
        TerminowSettings.applyScreenshotsBlocked(activity, mStore);
        addUpdateNotification();

        GridView gridView = activity.findViewById(R.id.terminow_grid);
        gridView.setAdapter(mAdapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> onItemClick(mItems.get(position)));
        gridView.setOnItemLongClickListener((parent, view, position, id) -> onItemLongClick(mItems.get(position)));

        mActionsView = activity.findViewById(R.id.terminow_actions);
        activity.findViewById(R.id.terminow_action_new_host).setOnClickListener(v -> showHostDialog(null));
        activity.findViewById(R.id.terminow_action_new_group).setOnClickListener(v -> showGroupDialog(null));
        activity.findViewById(R.id.terminow_action_import).setOnClickListener(v -> showImportDialog());
        // The title of a group goes back to all hosts
        mSectionTitleView.setOnClickListener(v -> onBackPressed());

        activity.findViewById(R.id.terminow_fab).setOnClickListener(v -> {
            if (mPage == PAGE_HOSTS)
                showHostDialog(null);
            else if (mPage == PAGE_KEYCHAIN)
                showKeyMenu(v);
            else if (mPage == PAGE_FORWARDING)
                showForwardWizard(null);
            else if (mPage == PAGE_MCP)
                mMcpPage.showProfileDialog(null);
            else
                showSnippetDialog(null);
        });

        mSearchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateItems(); }
        });

        setPage(PAGE_HOSTS);
        mSettings.setWhatsNew(() -> TerminowWhatsNew.showAll(mActivity, BuildConfig.VERSION_CODE, this::openWhatsNew));
        showWhatsNewIfPending();
    }



    public boolean isVisible() {
        return mHomeView.getVisibility() == View.VISIBLE;
    }

    private void updateNotificationsBadge() {
        mNotificationsBadge.setVisibility(mStore.hasUnreadNotifications() ? View.VISIBLE : View.GONE);
    }

    /** Add a notification about an update of the app, on the first start after it. */
    private void addUpdateNotification() {
        int lastVersionCode = mStore.updateLastVersionCode(BuildConfig.VERSION_CODE);
        List<TerminowWhatsNew.Feature> features = TerminowWhatsNew.onStart(mActivity, lastVersionCode, BuildConfig.VERSION_CODE);
        // Not when the app was just installed, or the version was not changed
        if (lastVersionCode == 0 || lastVersionCode == BuildConfig.VERSION_CODE) return;
        mStore.addNotification(mActivity.getString(R.string.terminow_notification_updated,
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"),
            features.isEmpty() ? mActivity.getString(R.string.terminow_notification_updated_text) : TerminowWhatsNew.describe(mActivity, features));
    }

    /** What is new after an update, once, over the home screen and never over a terminal. */
    private void showWhatsNewIfPending() {
        if (!isVisible()) return;
        // After the layout, so that the window does not come before the home screen is drawn
        mHomeView.post(() -> {
            if (isVisible() && !mActivity.isFinishing())
                TerminowWhatsNew.showIfPending(mActivity, BuildConfig.VERSION_CODE, this::openWhatsNew);
        });
    }

    private void openWhatsNew(@NonNull TerminowWhatsNew.Action action) {
        if (action == TerminowWhatsNew.Action.QUICK_LAUNCH) setPage(PAGE_LAUNCH);
        else if (action == TerminowWhatsNew.Action.FILES) setPage(PAGE_FILES);
        else if (action == TerminowWhatsNew.Action.SETTINGS) setPage(PAGE_SETTINGS);
    }

    /** Show the home screen over the terminal. */
    public void show() {
        mHomeView.setVisibility(View.VISIBLE);
        mActivity.getTerminalToolbarViewPager().setVisibility(View.GONE);
        KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
        mHosts = mStore.getHosts();
        mSnippets = mStore.getSnippets();
        mGroups = mStore.getGroups();
        mForwards = mStore.getForwards();
        if (mCurrentGroup != null && !mGroups.contains(mCurrentGroup))
            mCurrentGroup = null;
        if (mPage == PAGE_SETTINGS)
            mSettings.update();
        refresh();
        showWhatsNewIfPending();
    }

    /** Hide the home screen and show the terminal. */
    public void hide() {
        if (!isVisible()) return;
        mHomeView.setVisibility(View.GONE);
        KeyboardUtils.hideSoftKeyboard(mActivity, mSearchView);
        if (mActivity.getPreferences().shouldShowTerminalToolbar())
            mActivity.getTerminalToolbarViewPager().setVisibility(View.VISIBLE);
        mActivity.getTerminalView().requestFocus();
        mActivity.getTermuxTerminalViewClient().setSoftKeyboardState(true, false);
        refresh();
    }

    /** Update the tab bar and the home screen after the sessions or the current session changed. */
    public void refresh() {
        // Tabs were added, closed, renamed or switched
        mWorkspace.changed();
        if (!isVisible()) TerminowQuickLaunch.noteShown(mActivity.getCurrentSession());
        if (mQuickLaunch != null && isVisible()) mQuickLaunch.refresh();
        // With no terminal session the terminal area is empty, so the home screen is shown
        // instead, whatever closed the last session or started the app (the service may have been
        // started by a RUN_COMMAND of another app before the activity was opened)
        TermuxService termuxService = mActivity.getTermuxService();
        if (termuxService != null && termuxService.isTermuxSessionsEmpty() && !isVisible()) {
            show();
            return;
        }

        boolean homeVisible = isVisible();
        mTabBar.setBackgroundColor(ContextCompat.getColor(mActivity,
            homeVisible ? R.color.terminow_bar_home : R.color.terminow_bar_terminal));
        mHomeButton.setBackgroundResource(homeVisible ? R.drawable.terminow_tab : R.drawable.terminow_tab_idle);
        updateNotificationsBadge();
        updateTabs();
        // The toolbar belongs to the terminal, the launcher button to the home screen
        boolean hasSessions = termuxService != null && !termuxService.isTermuxSessionsEmpty();
        // An SFTP tab that shows files has its own bar, and no keys
        boolean files = mSftpBrowser != null && mSftpBrowser.update(mActivity.getCurrentSession(), !homeVisible && hasSessions);
        mTerminalToolbar.setVisibility(!homeVisible && hasSessions && !files ? View.VISIBLE : View.GONE);
        mLauncherButton.setVisibility(homeVisible || !hasSessions ? View.VISIBLE : View.GONE);
        mLauncher.updateToolbar(null);
        mKeyPanel.update(!homeVisible && hasSessions && !files);
        // Like Termius: the vault and its switcher on the home screen, "Vaults" over a terminal
        mHomeTitle.setText(homeVisible ? mVaultsUi.getActiveName() : mActivity.getString(R.string.terminow_vaults));
        mVaultSyncIcon.setImageResource(mVaultsUi.getSyncIcon());
        mVaultSyncIcon.setContentDescription(mVaultsUi.getStatusText());
        mVaultSyncIcon.setVisibility(homeVisible ? View.VISIBLE : View.GONE);
        mVaultButton.setVisibility(homeVisible ? View.VISIBLE : View.GONE);
        mVaultsUi.onListsChanged();
        if (homeVisible)
            updateItems();
    }



    private void updateTabs() {
        mTabsView.removeAllViews();

        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        boolean homeVisible = isVisible();
        TerminalSession currentSession = mActivity.getCurrentSession();
        List<TermuxSession> termuxSessions = service.getTermuxSessions();
        View selectedTab = null;

        for (int i = 0; i < termuxSessions.size(); i++) {
            final TerminalSession session = termuxSessions.get(i).getTerminalSession();
            if (session == null) continue;
            boolean selected = !homeVisible && session == currentSession;

            LinearLayout tab = new LinearLayout(mActivity);
            tab.setOrientation(LinearLayout.HORIZONTAL);
            tab.setGravity(Gravity.CENTER_VERTICAL);
            tab.setBackgroundResource(selected ? R.drawable.terminow_tab : R.drawable.terminow_tab_idle);
            tab.setPadding(dp(12), 0, dp(selected ? 4 : 12), 0);
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            tabParams.setMarginStart(dp(8));
            tab.setLayoutParams(tabParams);

            TextView titleView = new TextView(mActivity);
            titleView.setText(getSessionTitle(session, i));
            titleView.setTextSize(14);
            titleView.setTextColor(ContextCompat.getColor(mActivity, selected ? R.color.terminow_text : R.color.terminow_text_secondary));
            titleView.setMaxLines(1);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setMaxWidth(dp(200));
            tab.addView(titleView);

            if (selected) {
                ImageView closeView = new ImageView(mActivity);
                closeView.setImageResource(R.drawable.ic_terminow_close);
                closeView.setPadding(dp(8), dp(8), dp(8), dp(8));
                closeView.setContentDescription(mActivity.getString(R.string.terminow_close_session));
                closeView.setOnClickListener(v -> closeSession(session));
                tab.addView(closeView, new LinearLayout.LayoutParams(dp(36), dp(36)));
                selectedTab = tab;
            }

            tab.setOnClickListener(v -> {
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
                if (isVisible())
                    hide();
                else
                    refresh();
            });
            tab.setOnLongClickListener(v -> {
                mActivity.getTermuxTerminalSessionClient().renameSession(session);
                return true;
            });

            mTabsView.addView(tab);
        }

        // The button to add a session follows the last tab, like the one of Termius
        ImageView addView = new ImageView(mActivity);
        addView.setImageResource(R.drawable.ic_terminow_add);
        addView.setPadding(dp(8), dp(8), dp(8), dp(8));
        addView.setContentDescription(mActivity.getString(R.string.action_new_session));
        addView.setOnClickListener(this::showNewSessionMenu);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        addParams.setMarginStart(dp(6));
        mTabsView.addView(addView, addParams);

        if (selectedTab != null) {
            final View tab = selectedTab;
            mTabsScrollView.post(() -> mTabsScrollView.requestChildRectangleOnScreen(mTabsView,
                new android.graphics.Rect(tab.getLeft(), 0, tab.getRight(), tab.getHeight()), false));
        }
    }

    @NonNull
    private String getSessionTitle(@NonNull TerminalSession session, int index) {
        if (!TextUtils.isEmpty(session.mSessionName)) return session.mSessionName;
        if (!TextUtils.isEmpty(session.getTitle())) return session.getTitle();
        return mActivity.getString(R.string.terminow_local_terminal) + " " + (index + 1);
    }

    private void closeSession(@NonNull TerminalSession session) {
        if (!session.isRunning()) {
            mActivity.getTermuxTerminalSessionClient().closeSession(session);
            return;
        }

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(R.string.terminow_close_session)
            .setPositiveButton(android.R.string.ok, (dialog, which) ->
                mActivity.getTermuxTerminalSessionClient().closeSession(session))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }



    /** Show Quick Launch over the terminals, which keep running as they are. */
    public void showQuickLaunch() {
        show();
        setPage(PAGE_LAUNCH);
    }

    private void setPage(int page) {
        if (page == PAGE_FILES) TerminowWhatsNew.seen(mActivity, TerminowWhatsNew.Feature.FILES);
        if (mPage == PAGE_FILES && page != PAGE_FILES) mExplorer.onHidden();
        if (mPage == PAGE_LAUNCH && page != PAGE_LAUNCH && mQuickLaunch != null) mQuickLaunch.hide();
        mPage = page;
        setRailItemSelected(mRailLaunch, page == PAGE_LAUNCH);
        if (page == PAGE_LAUNCH) {
            // Quick Launch has its own title, sections and actions over the whole content area
            mHeaderView.setVisibility(View.GONE);
            mSectionTitleView.setVisibility(View.GONE);
            mGridView.setVisibility(View.GONE);
            mFabView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.GONE);
            mSettingsScrollView.setVisibility(View.GONE);
            mMcpScrollView.setVisibility(View.GONE);
            mExplorerView.setVisibility(View.GONE);
            mAiScrollView.setVisibility(View.GONE);
            mMcpBridgeScrollView.setVisibility(View.GONE);
            mActionsView.setVisibility(View.GONE);
            setRailItemSelected(mRailMcpBridge, false);
            setRailItemSelected(mRailHosts, false);
            setRailItemSelected(mRailSnippets, false);
            setRailItemSelected(mRailSettings, false);
            setRailItemSelected(mRailKeychain, false);
            setRailItemSelected(mRailForwarding, false);
            setRailItemSelected(mRailKnownHosts, false);
            setRailItemSelected(mRailLogs, false);
            setRailItemSelected(mRailMcp, false);
            setRailItemSelected(mRailFiles, false);
            setRailItemSelected(mRailAi, false);
            mQuickLaunch.show();
            return;
        }
        setRailItemSelected(mRailHosts, page == PAGE_HOSTS);
        setRailItemSelected(mRailSnippets, page == PAGE_SNIPPETS);
        setRailItemSelected(mRailSettings, page == PAGE_SETTINGS);
        setRailItemSelected(mRailKeychain, page == PAGE_KEYCHAIN);
        setRailItemSelected(mRailForwarding, page == PAGE_FORWARDING);
        setRailItemSelected(mRailKnownHosts, page == PAGE_KNOWN_HOSTS);
        setRailItemSelected(mRailLogs, page == PAGE_LOGS);
        setRailItemSelected(mRailMcp, page == PAGE_MCP);
        setRailItemSelected(mRailFiles, page == PAGE_FILES);
        setRailItemSelected(mRailAi, page == PAGE_AI);
        setRailItemSelected(mRailMcpBridge, page == PAGE_MCP_BRIDGE);
        mActionsView.setVisibility(page == PAGE_HOSTS ? View.VISIBLE : View.GONE);
        mSortView.setVisibility(page == PAGE_KEYCHAIN ? View.VISIBLE : View.GONE);
        mSearchView.setHint(page == PAGE_KEYCHAIN ? R.string.terminow_search_keys_hint :
            page == PAGE_MCP ? R.string.terminow_mcp_search_hint : R.string.terminow_search_hint);

        // The settings are shown instead of the list of hosts or snippets and what belongs to it
        boolean settings = page == PAGE_SETTINGS;
        mHeaderView.setVisibility(settings ? View.GONE : View.VISIBLE);
        mSectionTitleView.setVisibility(settings ? View.GONE : View.VISIBLE);
        mGridView.setVisibility(settings || page == PAGE_MCP ? View.GONE : View.VISIBLE);
        mMcpScrollView.setVisibility(page == PAGE_MCP ? View.VISIBLE : View.GONE);
        boolean ai = page == PAGE_AI;
        mAiScrollView.setVisibility(ai ? View.VISIBLE : View.GONE);
        boolean mcpBridge = page == PAGE_MCP_BRIDGE;
        mMcpBridgeScrollView.setVisibility(mcpBridge ? View.VISIBLE : View.GONE);
        // The explorer has its own toolbar, search and actions over the whole content area
        boolean files = page == PAGE_FILES;
        mExplorerView.setVisibility(files ? View.VISIBLE : View.GONE);
        if (files) {
            mHeaderView.setVisibility(View.GONE);
            mSectionTitleView.setVisibility(View.GONE);
            mGridView.setVisibility(View.GONE);
            mFabView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.GONE);
            mSettingsScrollView.setVisibility(View.GONE);
            mExplorer.show();
            return;
        }
        if (ai) {
            mHeaderView.setVisibility(View.GONE);
            mSectionTitleView.setVisibility(View.GONE);
            mGridView.setVisibility(View.GONE);
            mFabView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.GONE);
            mSettingsScrollView.setVisibility(View.GONE);
            // Built lazily too, so a tap is never ignored even if construction order changes
            if (mAiPage == null)
                mAiPage = new TerminowAiSettings(mActivity, mAiBridge, mStore, mActivity.findViewById(R.id.terminow_ai));
            mAiPage.render();
            return;
        }
        if (mcpBridge) {
            mHeaderView.setVisibility(View.GONE);
            mSectionTitleView.setVisibility(View.GONE);
            mGridView.setVisibility(View.GONE);
            mFabView.setVisibility(View.GONE);
            mEmptyView.setVisibility(View.GONE);
            mSettingsScrollView.setVisibility(View.GONE);
            if (mMcpBridgePage == null)
                mMcpBridgePage = new TerminowMcpBridgeUi(mActivity, mMcpBridge, mStore, mActivity.findViewById(R.id.terminow_mcp_bridge), this::openMcpTaskInTerminal, this::openSessionTerminal);
            mMcpBridgePage.render();
            return;
        }
        // Known hosts and logs are only added by connecting to hosts
        mFabView.setVisibility(settings || page == PAGE_KNOWN_HOSTS || page == PAGE_LOGS ? View.GONE : View.VISIBLE);
        mSettingsScrollView.setVisibility(settings ? View.VISIBLE : View.GONE);
        if (settings)
            mSettings.update();

        updateItems();
    }

    /** Go back to all hosts from a group. Returns {@code false} if there was nothing to go back from. */
    public boolean onBackPressed() {
        if (!isVisible() && mSftpBrowser != null && mSftpBrowser.onBackPressed()) return true;
        if (isVisible() && mPage == PAGE_FILES) return mExplorer.onBackPressed();
        if (!isVisible() || mPage != PAGE_HOSTS || mCurrentGroup == null) return false;
        mCurrentGroup = null;
        updateItems();
        return true;
    }

    private void updateSectionTitle() {
        if (mPage == PAGE_KEYCHAIN)
            mSectionTitleView.setText(mStore.getIdentities().isEmpty() ? R.string.terminow_ssh_keys : R.string.terminow_keychain_title);
        else if (mPage == PAGE_MCP)
            mSectionTitleView.setText(R.string.terminow_mcp);
        else if (mPage == PAGE_FORWARDING)
            mSectionTitleView.setText(R.string.terminow_forwarding);
        else if (mPage == PAGE_KNOWN_HOSTS)
            mSectionTitleView.setText(R.string.terminow_known_hosts);
        else if (mPage == PAGE_LOGS)
            mSectionTitleView.setText(R.string.terminow_logs);
        else if (mPage != PAGE_HOSTS)
            mSectionTitleView.setText(R.string.terminow_snippets);
        else if (mCurrentGroup == null)
            mSectionTitleView.setText(R.string.terminow_hosts);
        else
            mSectionTitleView.setText("‹ " + mActivity.getString(R.string.terminow_hosts) + " / " + mCurrentGroup);
    }

    private boolean isInGroup(@NonNull TerminowStore.Host host, @Nullable String group) {
        // The hosts of a group that does not exist anymore are shown with the hosts without a group
        if (group == null) return host.group.isEmpty() || !mGroups.contains(host.group);
        return group.equals(host.group);
    }

    private int getHostCount(@NonNull String group) {
        int count = 0;
        for (TerminowStore.Host host : mHosts)
            if (group.equals(host.group)) count++;
        return count;
    }

    private void setRailItemSelected(@NonNull View railItem, boolean selected) {
        ViewGroup group = (ViewGroup) railItem;
        int color = ContextCompat.getColor(mActivity, selected ? R.color.terminow_text : R.color.terminow_text_secondary);
        // An icon with a badge, like the crown of a Premium feature, is in a frame
        View iconFrame = group.getChildAt(0);
        ImageView iconView = iconFrame instanceof ImageView ? (ImageView) iconFrame : (ImageView) ((ViewGroup) iconFrame).getChildAt(0);
        iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (selected)
            iconFrame.setBackgroundResource(R.drawable.terminow_rail_selected);
        else
            iconFrame.setBackground(null);
        TextView labelView = (TextView) group.getChildAt(1);
        labelView.setTextColor(color);
        labelView.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void updateItems() {
        String query = mSearchView.getText().toString().trim().toLowerCase(Locale.ROOT);

        if (mPage == PAGE_SETTINGS || mPage == PAGE_FILES || mPage == PAGE_LAUNCH) {
            mEmptyView.setVisibility(View.GONE);
            return;
        }

        updateSectionTitle();
        mItems.clear();
        if (mPage == PAGE_MCP) {
            // The page shows its own cards and empty state
            mAdapter.notifyDataSetChanged();
            mEmptyView.setVisibility(View.GONE);
            mMcpPage.update(query);
            return;
        }
        if (mPage == PAGE_HOSTS && query.isEmpty()) {
            if (mCurrentGroup == null) {
                mItems.add(null);
                for (String group : mGroups)
                    mItems.add(new Group(group));
            }
            for (TerminowStore.Host host : mHosts) {
                if (isInGroup(host, mCurrentGroup))
                    mItems.add(host);
            }
        } else if (mPage == PAGE_HOSTS) {
            // A search is done in the hosts of all groups
            if (mActivity.getString(R.string.terminow_local_terminal).toLowerCase(Locale.ROOT).contains(query))
                mItems.add(null);
            for (TerminowStore.Host host : mHosts) {
                if (host.getTitle().toLowerCase(Locale.ROOT).contains(query) ||
                    host.address.toLowerCase(Locale.ROOT).contains(query) ||
                    TextUtils.join(",", host.tags).toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(host);
            }
        } else if (mPage == PAGE_KEYCHAIN) {
            // Identities first, then the keys, like the keychain of Termius
            List<TerminowStore.Identity> identities = new ArrayList<>();
            for (TerminowStore.Identity identity : mStore.getIdentities()) {
                if (query.isEmpty() || identity.getTitle().toLowerCase(Locale.ROOT).contains(query) ||
                    identity.username.toLowerCase(Locale.ROOT).contains(query))
                    identities.add(identity);
            }
            List<KeyItem> keys = new ArrayList<>();
            for (KeyItem key : getKeys()) {
                if (query.isEmpty() || key.name.toLowerCase(Locale.ROOT).contains(query) ||
                    key.label.toLowerCase(Locale.ROOT).contains(query))
                    keys.add(key);
            }
            sortKeychain(identities, keys, mStore.getKeychainSort());
            mItems.addAll(identities);
            mItems.addAll(keys);
        } else if (mPage == PAGE_FORWARDING) {
            for (TerminowStore.Forward forward : mForwards) {
                if (query.isEmpty() || forward.getTitle().toLowerCase(Locale.ROOT).contains(query) ||
                    forward.host.toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(forward);
            }
        } else if (mPage == PAGE_KNOWN_HOSTS) {
            for (KnownHost knownHost : getKnownHosts()) {
                if (query.isEmpty() || knownHost.hosts.toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(knownHost);
            }
        } else if (mPage == PAGE_LOGS) {
            for (TerminowStore.Log log : mStore.getLogs()) {
                if (query.isEmpty() || log.title.toLowerCase(Locale.ROOT).contains(query) ||
                    log.command.toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(log);
            }
        } else {
            for (TerminowStore.Snippet snippet : mSnippets) {
                if (query.isEmpty() || snippet.getTitle().toLowerCase(Locale.ROOT).contains(query) ||
                    snippet.command.toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(snippet);
            }
        }
        mAdapter.notifyDataSetChanged();

        if (!mItems.isEmpty()) {
            mEmptyView.setVisibility(View.GONE);
        } else {
            boolean forwardingEmpty = mPage == PAGE_FORWARDING && query.isEmpty();
            mEmptyView.setCompoundDrawablesWithIntrinsicBounds(0, forwardingEmpty ? R.drawable.terminow_empty_forwarding : 0, 0, 0);
            mEmptyView.setCompoundDrawablePadding(dp(20));
            if (forwardingEmpty) {
                // A title above the text, like the empty port forwarding page of Termius
                String title = mActivity.getString(R.string.terminow_no_forwards_title);
                SpannableString text = new SpannableString(title + "\n\n" + mActivity.getString(R.string.terminow_no_forwards));
                text.setSpan(new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(mActivity, R.color.terminow_text)), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new RelativeSizeSpan(1.15f), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                mEmptyView.setText(text);
            } else {
                mEmptyView.setText(!query.isEmpty() ? R.string.terminow_no_results :
                    mPage == PAGE_HOSTS ? R.string.terminow_group_empty :
                    mPage == PAGE_KEYCHAIN ? R.string.terminow_no_keys :
                    mPage == PAGE_FORWARDING ? R.string.terminow_no_forwards :
                    mPage == PAGE_KNOWN_HOSTS ? R.string.terminow_no_known_hosts :
                    mPage == PAGE_LOGS ? R.string.terminow_no_logs : R.string.terminow_no_snippets);
            }
            mEmptyView.setVisibility(View.VISIBLE);
        }
    }

    private void onItemClick(@Nullable Object item) {
        if (item == null) {
            openLocalTerminal();
        } else if (item instanceof Group) {
            mCurrentGroup = ((Group) item).name;
            updateItems();
        } else if (item instanceof TerminowStore.Host) {
            connect((TerminowStore.Host) item);
        } else if (item instanceof TerminowStore.Identity) {
            showIdentityDialog((TerminowStore.Identity) item, null);
        } else if (item instanceof KeyItem) {
            showEditKeyPanel((KeyItem) item);
        } else if (item instanceof TerminowStore.Forward) {
            startForward((TerminowStore.Forward) item);
        } else if (item instanceof KnownHost) {
            showRemoveKnownHostDialog((KnownHost) item);
        } else if (item instanceof TerminowStore.Snippet) {
            runSnippet((TerminowStore.Snippet) item);
        }
    }

    private boolean onItemLongClick(@Nullable Object item) {
        if (item == null) return false;

        if (item instanceof TerminowStore.Identity) {
            showIdentityDialog((TerminowStore.Identity) item, null);
            return true;
        }
        if (item instanceof KeyItem) {
            showEditKeyPanel((KeyItem) item);
            return true;
        }
        if (item instanceof KnownHost) {
            showRemoveKnownHostDialog((KnownHost) item);
            return true;
        }
        if (item instanceof TerminowStore.Log) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setMessage(R.string.terminow_logs_clear_confirm)
                .setPositiveButton(R.string.terminow_logs_clear, (dialog, which) -> {
                    mStore.clearLogs();
                    updateItems();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return true;
        }

        // A host can also be duplicated; the other item types keep the plain Edit/Delete menu.
        boolean isHost = item instanceof TerminowStore.Host;
        CharSequence[] actions = isHost
            ? new CharSequence[]{mActivity.getString(R.string.terminow_edit), mActivity.getString(R.string.terminow_duplicate),
                mActivity.getString(R.string.terminow_delete)}
            : new CharSequence[]{mActivity.getString(R.string.terminow_edit), mActivity.getString(R.string.terminow_delete)};
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog).setItems(actions, (dialog, which) -> {
            if (item instanceof Group) {
                String group = ((Group) item).name;
                if (which == 0) {
                    showGroupDialog(group);
                } else {
                    // The hosts of the group are kept and shown without a group
                    for (TerminowStore.Host host : mHosts)
                        if (group.equals(host.group)) host.group = "";
                    mGroups.remove(group);
                    mStore.setGroups(mGroups);
                    mStore.setHosts(mHosts);
                    updateItems();
                }
            } else if (item instanceof TerminowStore.Forward) {
                TerminowStore.Forward forward = (TerminowStore.Forward) item;
                if (which == 0) {
                    showForwardWizard(forward);
                } else {
                    mForwards.remove(forward);
                    mStore.setForwards(mForwards);
                    updateItems();
                }
            } else if (item instanceof TerminowStore.Host) {
                TerminowStore.Host host = (TerminowStore.Host) item;
                if (which == 0) {
                    showHostDialog(host);
                } else if (which == 1) {
                    duplicateHost(host);
                } else {
                    mHosts.remove(host);
                    mStore.setHosts(mHosts);
                    updateItems();
                }
            } else {
                TerminowStore.Snippet snippet = (TerminowStore.Snippet) item;
                if (which == 0) {
                    showSnippetDialog(snippet);
                } else {
                    mSnippets.remove(snippet);
                    mStore.setSnippets(mSnippets);
                    updateItems();
                }
            }
        }).show();
        return true;
    }

    /**
     * Make an independent copy of a host and open it for editing. The copy gets a fresh uuid so it
     * is a distinct host (its authorization, such as local AI access, does not carry the original's
     * grants), and the password is copied over because {@link TerminowStore#hostToJson} leaves it out.
     */
    private void duplicateHost(@NonNull TerminowStore.Host host) {
        TerminowStore.Host copy;
        try {
            copy = TerminowStore.hostFromJson(TerminowStore.hostToJson(host));
        } catch (org.json.JSONException e) {
            mActivity.showToast(String.valueOf(e.getMessage()), true);
            return;
        }
        copy.uuid = TerminowStore.newHostId();
        copy.password = host.password;
        copy.label = duplicateLabel(host.getTitle());
        int index = mHosts.indexOf(host);
        if (index < 0) mHosts.add(copy); else mHosts.add(index + 1, copy);
        mStore.setHosts(mHosts);
        updateItems();
        showHostDialog(copy);
    }

    /** A label for a copied host, like "web copy", then "web copy 2" if that one is taken. */
    @NonNull
    private String duplicateLabel(@NonNull String title) {
        String base = mActivity.getString(R.string.terminow_duplicate_suffix, title);
        String candidate = base;
        int n = 2;
        while (labelTaken(candidate)) candidate = base + " " + n++;
        return candidate;
    }

    private boolean labelTaken(@NonNull String label) {
        for (TerminowStore.Host host : mHosts) if (label.equals(host.label)) return true;
        return false;
    }

    /** Show the menu to start a session under the add button of the tab bar. */
    private void showNewSessionMenu(@NonNull View anchor) {
        LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(R.drawable.terminow_menu);
        menu.setPadding(0, dp(10), 0, dp(10));
        // Scrolls when the profiles do not fit, like on a folded phone held sideways
        android.widget.ScrollView menuScroll = new android.widget.ScrollView(mActivity);
        menuScroll.addView(menu);

        PopupWindow popup = new PopupWindow(menuScroll, dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Compact: environments and profiles are one tap away in Quick Launch, exporting files is in the File Manager
        addMenuItem(menu, popup, R.drawable.ic_terminow_terminal, R.string.terminow_quick_new_terminal, this::openLocalTerminal);
        addMenuItem(menu, popup, R.drawable.ic_terminow_launch, R.string.terminow_quick_title, this::showQuickLaunch);
        addMenuItem(menu, popup, R.drawable.ic_terminow_add, R.string.terminow_quick_add_environment, () -> mQuickLaunch.showAddEnvironment());
        addMenuItem(menu, popup, R.drawable.ic_terminow_hosts, R.string.terminow_quick_add_ssh_host, () -> {
            mHosts = mStore.getHosts();
            mGroups = mStore.getGroups();
            showHostDialog(null);
        });
        addMenuItem(menu, popup, R.drawable.ic_terminow_import, R.string.terminow_quick_import, this::showImportChooser);

        popup.showAsDropDown(anchor, 0, dp(4));
    }

    /** Import: hosts from a file or Termius, or files into the session that is shown. */
    private void showImportChooser() {
        CharSequence[] items = {mActivity.getString(R.string.terminow_quick_import_hosts), mActivity.getString(R.string.terminow_files_import)};
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_quick_import)
            .setItems(items, (d, which) -> {
                if (which == 0) showImportDialog();
                // Shared storage is reached with the system file picker, the app has no storage permission
                else if (!isVisible() && mSftpBrowser.has(mActivity.getCurrentSession())) mSftpBrowser.pickUpload();
                else mFiles.importFiles(getCurrentSftpSession());
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Show the new connection picker, in SSH or SFTP mode. The sessions are kept as they are while it is shown. */
    private void showConnectDialog(boolean sftp) {
        new TerminowConnectDialog(mActivity, mStore, sftp, new TerminowConnectDialog.Actions() {
            @Override
            public void connect(@NonNull TerminowStore.Host host, boolean sftp) {
                if (sftp)
                    connectSftp(host);
                else
                    TerminowHome.this.connect(host);
            }

            @Override
            public void openLocalTerminal() {
                TerminowHome.this.openLocalTerminal();
            }

            @Override
            public void addHostAndConnect(@Nullable TerminowStore.Host host, boolean sftp) {
                // The form saves to the lists of the home screen, which may not be loaded yet
                mHosts = mStore.getHosts();
                mGroups = mStore.getGroups();
                showHostDialog(null, host, savedHost -> connect(savedHost, sftp));
            }
        }).show();
    }

    @NonNull
    public TerminowFiles getFiles() {
        return mFiles;
    }

    /** The sessions that were started for sftp, to upload imported files in. */
    private final java.util.Set<TerminalSession> mSftpSessions =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** The SFTP tabs that show the files of their host, like Termius, instead of the sftp command line. */
    private TerminowSftpBrowser mSftpBrowser;
    private TerminowAiBridgeManager mAiBridge;
    private TerminowMcpBridgeManager mMcpBridge;

    /** The session that is shown if it is an sftp session that is still running, otherwise {@code null}. */
    @Nullable
    private TerminalSession getCurrentSftpSession() {
        TerminalSession session = mActivity.getCurrentSession();
        return session != null && session.isRunning() && mSftpSessions.contains(session) ? session : null;
    }

    // ---------------------------------------------------------------------------------------
    // Windows hosts: the built-in remote desktop, and PowerShell over WinRM

    /**
     * Open the remote desktop of an RDP host: the connection that is still open, if the user
     * wants it, or a new one, which connects to the saved host again.
     */
    private void openRdp(@NonNull TerminowStore.Host host) {
        if (!com.termux.app.terminow.rdp.TerminowRdpNative.isAvailable()) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_rdp_title)
                .setMessage(mActivity.getString(R.string.terminow_rdp_no_engine, String.valueOf(
                    com.termux.app.terminow.rdp.TerminowRdpNative.loadError())))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        for (com.termux.app.terminow.rdp.TerminowRdpSession session : com.termux.app.terminow.rdp.TerminowRdpSession.open()) {
            if (!session.target.hostKey.equals(host.getKey())) continue;
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(host.getTitle())
                .setMessage(R.string.terminow_rdp_open_already)
                .setPositiveButton(R.string.terminow_rdp_resume, (d, w) -> com.termux.app.terminow.rdp.TerminowRdpActivity.show(mActivity, session))
                .setNeutralButton(R.string.terminow_rdp_new_connection, (d, w) -> startRdp(host))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        startRdp(host);
    }

    private void startRdp(@NonNull TerminowStore.Host host) {
        // A desktop of the size of this screen, in its pixels, for a host that takes the size of the screen
        android.util.DisplayMetrics metrics = mActivity.getResources().getDisplayMetrics();
        int width = Math.max(metrics.widthPixels, metrics.heightPixels);
        int height = Math.min(metrics.widthPixels, metrics.heightPixels);
        if (width > 1920) {
            height = Math.round(height * 1920f / width);
            width = 1920;
        }
        com.termux.app.terminow.rdp.TerminowRdpSession.Target target;
        try {
            target = TerminowRdpBridge.rdpTarget(mStore, host, mHosts, width & ~3, height & ~1);
        } catch (TerminowRdpBridge.TargetException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_rdp_host_invalid, e.getMessage()), true);
            return;
        }
        mStore.addRecentConnection(host, false);
        com.termux.app.terminow.rdp.TerminowRdpActivity.show(mActivity, com.termux.app.terminow.rdp.TerminowRdpSession.start(mActivity, target));
    }

    /** The open remote desktops or consoles of a Windows host. */
    private static int windowsSessions(@NonNull TerminowStore.Host host) {
        int count = 0;
        for (com.termux.app.terminow.rdp.TerminowRdpSession session : com.termux.app.terminow.rdp.TerminowRdpSession.open())
            if (session.target.hostKey.equals(host.getKey())) count++;
        for (com.termux.app.terminow.winrm.TerminowWinrmSession session : com.termux.app.terminow.winrm.TerminowWinrmSession.open())
            if (session.target.hostKey.equals(host.getKey())) count++;
        return count;
    }

    /** The PowerShell console of a WinRM host, through the Linux environment. */
    private void openWinrm(@NonNull TerminowStore.Host host) {
        for (com.termux.app.terminow.winrm.TerminowWinrmSession session : com.termux.app.terminow.winrm.TerminowWinrmSession.open()) {
            if (!session.target.hostKey.equals(host.getKey())) continue;
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(host.getTitle())
                .setMessage(R.string.terminow_winrm_open_already)
                .setPositiveButton(R.string.terminow_rdp_resume, (d, w) -> com.termux.app.terminow.winrm.TerminowWinrmActivity.show(mActivity, session))
                .setNeutralButton(R.string.terminow_rdp_new_connection, (d, w) -> startWinrm(host))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        startWinrm(host);
    }

    private void startWinrm(@NonNull TerminowStore.Host host) {
        com.termux.app.terminow.winrm.TerminowWinrmSession.Target target = com.termux.app.terminow.winrm.TerminowWinrmHosts.targetFor(mStore, host);
        if (target == null) {
            // WinRM runs in Ubuntu: without it, what to do instead of a failure
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_winrm_title)
                .setMessage(R.string.terminow_winrm_needs_linux)
                .setPositiveButton(R.string.terminow_quick_add_environment, (d, w) -> {
                    showQuickLaunch();
                    mQuickLaunch.showAddEnvironment();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        mStore.addRecentConnection(host, false);
        com.termux.app.terminow.winrm.TerminowWinrmActivity.show(mActivity, com.termux.app.terminow.winrm.TerminowWinrmSession.start(mActivity, target));
    }

    // ---------------------------------------------------------------------------------------
    // Backing up a Linux environment to a saved host, see TerminowBackup

    /** Where to, what, and how: remembered for the environment, so the next backup is two taps. */
    private void showBackupDialog(@NonNull String alias, @NonNull String title) {
        // Backup & Restore of Ubuntu is Premium, also to a server
        if (!TerminowPhoneBackup.mayBackUp(mStore)) {
            // Only the upgrade of Premium is shown
            TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.BACKUP, 0, () -> {});
            return;
        }
        mHosts = mStore.getHosts();
        List<TerminowStore.Host> servers = new ArrayList<>();
        for (TerminowStore.Host host : mHosts) if (host.isTerminal() && !host.telnet) servers.add(host);
        if (servers.isEmpty()) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(mActivity.getString(R.string.terminow_backup_title, title))
                .setMessage(R.string.terminow_backup_no_hosts)
                .setPositiveButton(R.string.terminow_new_host, (d, w) -> showHostDialog(null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        org.json.JSONObject saved;
        try {
            String stored = mStore.getBackupSettings(alias);
            saved = stored.isEmpty() ? new org.json.JSONObject() : new org.json.JSONObject(stored);
        } catch (org.json.JSONException e) {
            saved = new org.json.JSONObject();
        }
        TerminowStore.Host[] server = {TerminowStore.findHost(servers, saved.optString("host"))};
        if (server[0] == null) server[0] = servers.get(0);

        LinearLayout form = createForm();
        TextView serverLabel = new TextView(form.getContext());
        serverLabel.setText(R.string.terminow_backup_server);
        serverLabel.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        form.addView(serverLabel);
        EditText serverView = addField(form, R.string.terminow_backup_server, InputType.TYPE_NULL);
        serverView.setFocusable(false);
        serverView.setCursorVisible(false);
        Runnable showServer = () -> serverView.setText(server[0].getTitle() + "  ·  " + server[0].username + "@" + server[0].address);
        showServer.run();
        serverView.setOnClickListener(v -> {
            CharSequence[] names = new CharSequence[servers.size()];
            for (int i = 0; i < servers.size(); i++) names[i] = servers.get(i).getTitle() + "  ·  " + servers.get(i).address;
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_backup_server)
                .setItems(names, (d, which) -> {
                    server[0] = servers.get(which);
                    showServer.run();
                })
                .show();
        });
        TextView dirLabel = new TextView(form.getContext());
        dirLabel.setText(R.string.terminow_backup_remote_dir);
        dirLabel.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        dirLabel.setPadding(0, dp(10), 0, 0);
        form.addView(dirLabel);
        EditText dirView = addField(form, R.string.terminow_backup_remote_dir, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        dirView.setText(saved.optString("dir", "terminow-backups/" + alias));

        RadioGroup modes = new RadioGroup(form.getContext());
        RadioButton folders = new RadioButton(form.getContext());
        folders.setId(View.generateViewId());
        folders.setText(R.string.terminow_backup_mode_folders);
        RadioButton system = new RadioButton(form.getContext());
        system.setId(View.generateViewId());
        system.setText(R.string.terminow_backup_mode_system);
        modes.addView(folders);
        modes.addView(system);
        modes.setPadding(0, dp(10), 0, 0);
        form.addView(modes);
        EditText foldersView = addField(form, R.string.terminow_backup_folders, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        foldersView.setText(saved.optString("folders", "/root /home"));
        CheckBox install = new CheckBox(form.getContext());
        install.setText(mActivity.getString(R.string.terminow_backup_install_rsync, title));
        install.setChecked(saved.optBoolean("installRsync"));
        form.addView(install);
        TextView note = new TextView(form.getContext());
        note.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(8), 0, dp(8));
        form.addView(note);
        Runnable showMode = () -> {
            boolean rsync = folders.isChecked();
            foldersView.setVisibility(rsync ? View.VISIBLE : View.GONE);
            install.setVisibility(rsync ? View.VISIBLE : View.GONE);
            note.setText(mActivity.getString(rsync ? R.string.terminow_backup_note_folders : R.string.terminow_backup_note_system, title));
        };
        modes.setOnCheckedChangeListener((group, id) -> showMode.run());
        modes.check(TerminowBackup.MODE_SYSTEM.equals(saved.optString("mode")) ? system.getId() : folders.getId());
        showMode.run();

        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_backup_title, title))
            .setView(scroll)
            .setPositiveButton(R.string.terminow_backup_run, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String dir = dirView.getText().toString().trim();
            if (!TerminowBackup.isValidRemoteDir(dir)) {
                dirView.setError(mActivity.getString(R.string.terminow_backup_invalid_dir));
                return;
            }
            boolean rsync = folders.isChecked();
            List<String> list = TerminowBackup.parseFolders(foldersView.getText().toString());
            if (rsync && list == null) {
                foldersView.setError(mActivity.getString(R.string.terminow_backup_invalid_folders));
                return;
            }
            try {
                mStore.setBackupSettings(alias, new org.json.JSONObject().put("host", server[0].getKey()).put("dir", dir)
                    .put("mode", rsync ? TerminowBackup.MODE_FOLDERS : TerminowBackup.MODE_SYSTEM)
                    .put("folders", foldersView.getText().toString().trim()).put("installRsync", install.isChecked()).toString());
            } catch (org.json.JSONException ignored) {
            }
            dialog.dismiss();
            runBackup(alias, title, server[0], dir, rsync ? list : null, install.isChecked());
        }));
        dialog.show();
    }

    /** In a tab of its own, so its progress, prompts and result are shown; saved passwords and passphrases are typed in. */
    private void runBackup(@NonNull String alias, @NonNull String title, @NonNull TerminowStore.Host host, @NonNull String dir,
                           @Nullable List<String> folders, boolean installRsync) {
        // Checked again where the backup starts, whatever showed the button
        if (!TerminowPhoneBackup.mayBackUp(mStore)) {
            TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.BACKUP, 0, () -> {});
            return;
        }
        Route route = prepareRoute(host);
        if (route == null) return;
        String[] transport = route.login.getTransport(route.jump, route.proxyConnector, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        String command = folders != null ?
            TerminowBackup.foldersCommand(TermuxConstants.TERMUX_PREFIX_DIR_PATH, TermuxConstants.TERMUX_HOME_DIR_PATH, alias, transport, dir, folders, installRsync) :
            TerminowBackup.systemCommand(TermuxConstants.TERMUX_PREFIX_DIR_PATH, alias, transport, dir, TerminowBackup.archiveName(alias, System.currentTimeMillis()));
        String name = mActivity.getString(R.string.terminow_backup_tab, title, host.getTitle());
        TerminalSession session = addSession(name);
        if (session == null) return;
        mStore.addLog(name, command);
        writeWhenStarted(session, command + "\r", 0);
        writePasswordsWhenAsked(session, getLoginPrompts(route.login, route.jump), 0);
    }

    /** Start a session to transfer files to and from a host with sftp. */
    private void connectSftp(@NonNull TerminowStore.Host host) {
        if (!host.isTerminal()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_host_no_sftp_windows), true);
            return;
        }
        if (host.telnet) {
            mActivity.showToast(mActivity.getString(R.string.terminow_host_telnet_no_sftp), true);
            return;
        }
        String title = mActivity.getString(R.string.terminow_sftp_session, host.getTitle());
        if (!startConnecting(host, true)) return;
        TerminalSession session = addSession(title);
        if (session == null) {
            mConnectingHosts.remove(host.getKey() + ":sftp");
            return;
        }

        Route route = prepareRoute(host);
        if (route == null) {
            mConnectingHosts.remove(host.getKey() + ":sftp");
            return;
        }
        mSftpSessions.add(session);
        TerminowWorkspace.Tab tab = hostTab(host, true);
        tab.title = title;
        TerminowWorkspace.register(session, tab);
        mLauncher.track(session, new TerminowLauncher.SessionInfo(null, host.getTitle(), "sftp", "", ""));
        if (isBrowsable(route)) {
            mConnectingHosts.remove(host.getKey() + ":sftp");
            openSftpFiles(session, host, route, tab, title, false);
            return;
        }
        sftpCommandLine(session, host, route, tab, title, false);
    }

    /**
     * Whether an SFTP tab can show the files of its host: it is reached directly, not through a
     * jump host or a proxy, which only the sftp command can.
     */
    private static boolean isBrowsable(@NonNull Route route) {
        return route.jump == null && route.proxyConnector.isEmpty() && !route.login.telnet;
    }

    /** The files of the host in the tab, like Termius; the sftp command line is in its menu. */
    private void openSftpFiles(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, @NonNull Route route,
                               @NonNull TerminowWorkspace.Tab tab, @NonNull String title, boolean again) {
        if (!again) mStore.addLog(title, "sftp://" + route.login.username + "@" + route.login.address + ":" + route.login.port);
        mSftpBrowser.open(session, route.login, () -> mStore.addRecentConnection(host, true),
            () -> sftpCommandLine(session, host, route, tab, title, again));
        refresh();
    }

    /** The sftp command in the tab, which is how a host behind a jump host or a proxy is reached. */
    private void sftpCommandLine(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, @NonNull Route route,
                                 @NonNull TerminowWorkspace.Tab tab, @NonNull String title, boolean again) {
        String command = route.login.getSftpCommand(route.jump, route.proxyConnector, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        if (!again) mStore.addLog(title, command);
        writeWhenStarted(session, command + "\r", 0);
        writePasswordsWhenAsked(session, getLoginPrompts(route.login, route.jump), 0);
        watchConnection(session, host, true, title, 0, tab, again);
        refresh();
    }

    /** The hosts and modes that a connection is being started to, to not start another one at the same time. */
    private final java.util.Set<String> mConnectingHosts = new java.util.HashSet<>();

    /** How long a connection is watched for a failure or for being made. */
    private static final int CONNECTION_CHECK_COUNT = 180;
    private static final int CONNECTION_CHECK_DELAY_MS = 500;
    /** A connection that did not fail for this long and does not wait at a prompt is taken as made. */
    private static final int CONNECTION_MADE_AFTER_CHECKS = 16;
    /** Why a connection failed, in the order they are looked for: the first that matches is the reason. */
    enum FailureKind {
        AUTH("Permission denied \\(|Too many authentication failures|Authentication failed", R.string.terminow_connection_auth_failed),
        KEY("Load key \"[^\"]*\": [^\\n]*|no such identity: [^\\n]*|Identity file [^\\n]* not accessible[^\\n]*", R.string.terminow_connection_key_failed),
        HOST_KEY_CHANGED("REMOTE HOST IDENTIFICATION HAS CHANGED|Host key verification failed", R.string.terminow_connection_host_key_changed),
        TIMEOUT("(?i)Connection timed out|Operation timed out|timed out during banner exchange", R.string.terminow_connection_timeout),
        ADDRESS("Could not resolve hostname[^\\n]*", R.string.terminow_connection_address_failed),
        REFUSED("Connection refused", R.string.terminow_connection_refused),
        NETWORK("No route to host|Network is unreachable", R.string.terminow_connection_network_failed),
        MOSH_SERVER("mosh-server: (command )?not found|Did not find mosh server startup message[^\\n]*", R.string.terminow_connection_mosh_server),
        MOSH_PORTS("(?i)mosh-server[^\\n]*(bad|invalid)[^\\n]*port[^\\n]*|(?i)(bad|invalid) (UDP )?port( range)?[^\\n]*", R.string.terminow_connection_mosh_ports),
        MOSH_UDP("Nothing received from server on UDP port[^\\n]*", R.string.terminow_connection_mosh_udp),
        OTHER("Connection closed by[^\\n]*|Connection reset by[^\\n]*|kex_exchange_identification[^\\n]*|" +
            "Bad configuration option[^\\n]*|Unsupported option[^\\n]*|command-line line [^\\n]*", 0);

        final Pattern pattern;
        final int messageRes;

        FailureKind(@NonNull String pattern, int messageRes) {
            this.pattern = Pattern.compile(pattern);
            this.messageRes = messageRes;
        }
    }

    /** A failure of a connection: why, and the line that ssh or mosh printed about it. */
    static class Failure {
        final FailureKind kind;
        final String line;

        Failure(@NonNull FailureKind kind, @NonNull String line) {
            this.kind = kind;
            this.line = line;
        }
    }

    /** The failure that the output of a connection shows, {@code null} if it shows none. */
    @Nullable
    static Failure findConnectionFailure(@NonNull String transcript) {
        for (FailureKind kind : FailureKind.values()) {
            java.util.regex.Matcher matcher = kind.pattern.matcher(transcript);
            if (!matcher.find()) continue;
            // The whole line it is in, which says what ssh or mosh tried
            int start = transcript.lastIndexOf('\n', matcher.start()) + 1;
            int end = transcript.indexOf('\n', matcher.end());
            String line = transcript.substring(start, end < 0 ? transcript.length() : end).trim();
            if (line.length() > 300) line = line.substring(0, 300) + "…";
            return new Failure(kind, line);
        }
        return null;
    }

    /** What the user reads: why in words, what ssh or mosh said, and what to check in the host. */
    @NonNull
    private String describeFailure(@NonNull Failure failure, @NonNull TerminowStore.Host host) {
        String reason;
        switch (failure.kind) {
            case REFUSED:
                reason = mActivity.getString(R.string.terminow_connection_refused, host.port);
                break;
            case MOSH_UDP:
            case MOSH_PORTS: {
                String[] server = TerminowStore.parseMoshServer(host.moshServer);
                String ports = server != null && !server[1].isEmpty() ? server[1] : "60000:61000";
                reason = mActivity.getString(failure.kind.messageRes, ports);
                break;
            }
            default:
                reason = failure.kind.messageRes != 0 ? mActivity.getString(failure.kind.messageRes) : failure.line;
        }
        return failure.kind.messageRes != 0 && !failure.line.isEmpty() ?
            mActivity.getString(R.string.terminow_connection_reason, reason, failure.line) : reason;
    }
    /** A prompt that a connection waits at for the user, like for a password or to accept the key of a new host. */
    private static final Pattern CONNECTION_PROMPT_PATTERN = Pattern.compile(
        "(password|passphrase|\\(yes/no[^)]*\\)\\?|Verification code)[^\\n]*:?\\s*$", Pattern.CASE_INSENSITIVE);

    private boolean startConnecting(@NonNull TerminowStore.Host host, boolean sftp) {
        // A repeated tap does not start another connection while one is being made
        return mConnectingHosts.add(host.getKey() + (sftp ? ":sftp" : ":ssh"));
    }

    /**
     * Watch the output of a new connection, to show that it is being made in its tab, to show
     * why it failed with an option to retry, and to add it to the recent connections once it is
     * made. The startup snippet of the host is written to the session once it is made, and
     * never while the host asks for a password or to accept its key.
     */
    private void watchConnection(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, boolean sftp,
                                 @NonNull String title, int check, @NonNull TerminowWorkspace.Tab tab, boolean again) {
        String connectingKey = host.getKey() + (sftp ? ":sftp" : ":ssh");
        if (check == 0) {
            session.mSessionName = mActivity.getString(R.string.terminow_connecting_session, title);
            refresh();
        }

        Runnable finish = () -> {
            mConnectingHosts.remove(connectingKey);
            session.mSessionName = title;
            refresh();
        };

        // The tab was closed or the session ended
        if (session.getPid() > 0 && !session.isRunning()) {
            finish.run();
            return;
        }

        String transcript = session.getPid() > 0 ? ShellUtils.getTerminalSessionTranscriptText(session, false, true) : null;
        // Only what came after the previous output of a restored tab, or after "Reconnecting"
        if (transcript != null) transcript = TerminowWorkspace.afterMark(transcript);
        if (transcript != null) {
            Failure found = findConnectionFailure(transcript);
            String failure = found != null ? describeFailure(found, host) : null;
            if (failure != null) {
                finish.run();
                showConnectionFailed(session, host, sftp, failure, tab);
                return;
            }

            String lastLine = transcript.substring(transcript.lastIndexOf('\n') + 1);
            boolean waitingAtPrompt = CONNECTION_PROMPT_PATTERN.matcher(lastLine).find();
            if ((sftp && transcript.contains("sftp>")) || (!waitingAtPrompt && check >= CONNECTION_MADE_AFTER_CHECKS)) {
                mStore.addRecentConnection(host, sftp);
                // Never typed into a tmux session that is attached to again: it may run a program
                TerminowStore.Snippet snippet = sftp || (again && host.remoteTmux) ? null : mStore.getSnippet(host.startupSnippet);
                if (snippet != null && session.isRunning())
                    session.write(snippet.command + "\r");
                finish.run();
                if (!sftp && !host.telnet) watchConnectionLost(session, host, tab);
                return;
            }
        }

        if (check >= CONNECTION_CHECK_COUNT) {
            finish.run();
            return;
        }
        mHandler.postDelayed(() -> watchConnection(session, host, sftp, title, check + 1, tab, again), CONNECTION_CHECK_DELAY_MS);
    }

    private void showConnectionFailed(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, boolean sftp,
                                      @NonNull String reason, @NonNull TerminowWorkspace.Tab tab) {
        // Also in the tab, for a restored tab that is not shown or a dialog that was closed
        markSession(session, mActivity.getString(R.string.terminow_restore_failed_line, reason));
        if (!mActivity.isVisible()) return;
        String message = reason;
        if (!sftp && host.remoteTmux) message += "\n\n" + mActivity.getString(R.string.terminow_restore_failed_tmux);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_connection_failed, host.getTitle()))
            .setMessage(message)
            .setPositiveButton(R.string.terminow_retry, (dialog, which) -> {
                if (!sftp && session.isRunning()) {
                    // In the same tab, at its place, with its tmux session on the server
                    reconnectInTab(session, host, tab);
                    return;
                }
                // The tab of the failed attempt is replaced by the new one
                mActivity.getTermuxTerminalSessionClient().closeSession(session);
                if (sftp)
                    connectSftp(host);
                else
                    connect(host);
            })
            .setNeutralButton(R.string.terminow_connection_edit_host, (dialog, which) -> {
                // Correct what failed, then connect again with the saved host
                TerminowStore.Host saved = TerminowStore.findHost(mHosts, host.getKey());
                if (saved == null) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_rdp_host_gone), true);
                    return;
                }
                showHostDialog(saved, null, edited -> {
                    mActivity.getTermuxTerminalSessionClient().closeSession(session);
                    if (sftp) connectSftp(edited);
                    else connect(edited);
                });
            })
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .show();
    }

    /**
     * Get the folder that a new session is started in, the home directory of the app, which is the
     * HOME of the shell environment. The root folder `/` can not be read by apps, so a shell that
     * starts in it can not list its folder.
     */
    @NonNull
    public String getStartDirectory() {
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        if (!home.isDirectory() && !home.mkdirs())
            Logger.logError(LOG_TAG, "Failed to create the home directory \"" + home + "\"");
        return home.getAbsolutePath();
    }

    /**
     * Open a local terminal in a new tab, which starts in {@link #getStartDirectory()}. The only
     * input that is written to it is the first command of its fresh shell, before it can be used.
     */
    /**
     * Start a program in a new session, for the launcher, with its arguments and folder as they
     * are: nothing is typed into this or any other session. An empty executable is the default
     * login shell.
     */
    @Nullable
    TerminalSession startSession(@NonNull String executable, @NonNull String[] arguments, @NonNull String workingDirectory,
                                 @NonNull String name) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;
        TermuxSession session = executable.isEmpty() ?
            service.createTermuxSession(null, null, null, workingDirectory, false, name) :
            service.createTermuxSession(executable, arguments, null, workingDirectory, false, name);
        byte[] prelude = mNextPrelude;
        mNextPrelude = null;
        if (session == null) return null;
        // Before it is shown, which starts it
        session.getTerminalSession().mPrelude = prelude;
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(session.getTerminalSession());
        hide();
        return session.getTerminalSession();
    }

    /**
     * Open an MCP task's mirrored stream in a terminal tab: it shows the output from the start and
     * follows it live while the task runs. The tab stays open after the task ends.
     */
    private void openMcpTaskInTerminal(@NonNull File logFile, @NonNull String title) {
        String bash = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "bin/bash").getAbsolutePath();
        String command = "clear 2>/dev/null; tail -n +1 -F " + TerminowStore.shellQuote(logFile.getAbsolutePath());
        startSession(bash, new String[]{"-lc", command}, TermuxConstants.TERMUX_HOME_DIR_PATH, "task: " + title);
    }

    /** Open an interactive terminal in a shared session's environment at its folder. */
    private void openSessionTerminal(@NonNull String environmentKey, @NonNull String user, @NonNull String folder) {
        if (environmentKey.isEmpty() || TerminowMcpTargets.TARGET_TERMINOW.equals(environmentKey)) {
            openTerminalAt(new File(folder.isEmpty() ? TermuxConstants.TERMUX_HOME_DIR_PATH : folder));
        } else {
            mLauncher.openLinuxTerminalNewTab(environmentKey, user.isEmpty() ? "root" : user, folder);
        }
    }

    /** The vault was switched or synced: show its hosts, groups, snippets and forwards. */
    public void reloadVault() {
        mHosts = mStore.getHosts();
        mSnippets = mStore.getSnippets();
        mGroups = mStore.getGroups();
        mForwards = mStore.getForwards();
        if (mCurrentGroup != null && !mGroups.contains(mCurrentGroup))
            mCurrentGroup = null;
        if (mVaultsUi != null) mVaultsUi.refreshTeamChip();
        refresh();
        if (isVisible()) updateItems();
    }

    // ---------------------------------------------------------------------------------------
    // Tabs that come back after the app was closed

    /** Start saving the tabs as they change, once the saved ones were restored or there were none. */
    public void startWorkspace() {
        mWorkspace.start(new TerminowWorkspace.Source() {
            @NonNull
            @Override
            public List<TerminalSession> sessions() {
                List<TerminalSession> sessions = new ArrayList<>();
                TermuxService service = mActivity.getTermuxService();
                if (service != null)
                    for (TermuxSession session : service.getTermuxSessions()) sessions.add(session.getTerminalSession());
                return sessions;
            }

            @Nullable
            @Override
            public TerminalSession current() {
                return mActivity.getCurrentSession();
            }

            @NonNull
            @Override
            public TerminowWorkspace.Tab describe(@NonNull TerminalSession session) {
                // A tab that TermiNow did not start itself, like one of the + button, is a local terminal
                TerminowWorkspace.Tab tab = new TerminowWorkspace.Tab();
                tab.id = TerminowWorkspace.newId();
                TerminowLauncher.SessionInfo info = mLauncher.getSessionInfo(session);
                if (info != null && info.environmentKey != null && !TerminowMcpTargets.TARGET_TERMINOW.equals(info.environmentKey)) {
                    tab.kind = TerminowWorkspace.KIND_LINUX;
                    tab.environment = info.environmentKey;
                    tab.user = info.user;
                    tab.folder = info.folder;
                    tab.localTmux = info.tmux;
                } else if (info != null) {
                    tab.folder = info.folder;
                    tab.localTmux = info.tmux;
                }
                return tab;
            }

            @Override
            public boolean paused() {
                // While the app shuts down its tabs end, which is not the user closing them
                return mActivity.getTermuxService() == null || TermuxService.sShuttingDown;
            }
        });
    }

    /** The app goes to the background, where Android may end it: save the tabs and their output now. */
    public void onStop() {
        mWorkspace.saveNow();
    }

    /**
     * Open the tabs that were open when the app was last closed, in their order, with their
     * names and folders, and show the one that was shown. Each starts a new shell, a new shell
     * of its environment, or connects to its host again, showing its previous output as such
     * until then. Nothing that ran before is typed again.
     *
     * @return Whether tabs were restored; else the home screen is shown as before.
     */
    public boolean restoreWorkspace() {
        TerminowWorkspace.State state = mWorkspace.load();
        TermuxService service = mActivity.getTermuxService();
        if (state.tabs.isEmpty() || service == null) return false;
        String when = state.saved > 0 ? android.text.format.DateUtils.formatDateTime(mActivity, state.saved,
            android.text.format.DateUtils.FORMAT_SHOW_TIME | android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_ABBREV_ALL) : "";
        TerminalSession active = null;
        TerminalSession first = null;
        int restored = 0;
        for (TerminowWorkspace.Tab tab : state.tabs) {
            TerminalSession session;
            try {
                session = restoreTab(service, tab, when);
            } catch (RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to restore a tab", e);
                session = null;
            }
            if (session == null) continue;
            restored++;
            if (first == null) first = session;
            if (tab.id.equals(state.activeId)) active = session;
        }
        if (first == null) return false;
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(active != null ? active : first);
        hide();
        mActivity.showToast(mActivity.getResources().getQuantityString(R.plurals.terminow_restore_done, restored, restored), false);
        showWhyClosed(state.saved);
        return true;
    }

    /**
     * Why Android ended TermiNow the last time, if it did so after the tabs were last saved: the
     * programs in the tabs stopped then, which looked like a hang. Only Android 11 and later say why.
     */
    private void showWhyClosed(long saved) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R || saved <= 0) return;
        android.app.ActivityManager manager = mActivity.getSystemService(android.app.ActivityManager.class);
        if (manager == null) return;
        List<android.app.ApplicationExitInfo> exits;
        try {
            exits = manager.getHistoricalProcessExitReasons(null, 0, 1);
        } catch (RuntimeException e) {
            return;
        }
        if (exits.isEmpty()) return;
        android.app.ApplicationExitInfo exit = exits.get(0);
        // An exit long before the last save is from an earlier run
        if (exit.getTimestamp() < saved - 60_000) return;
        int why = whyClosed(exit.getReason(), exit.getStatus());
        if (why == 0) return;
        String when = android.text.format.DateUtils.formatDateTime(mActivity, exit.getTimestamp(),
            android.text.format.DateUtils.FORMAT_SHOW_TIME | android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_ABBREV_ALL);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_closed_title)
            .setMessage(mActivity.getString(R.string.terminow_closed_message, when, mActivity.getString(why)))
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /** What to say about how Android ended the app, 0 for an exit that needs no word, like Exit in TermiNow. */
    static int whyClosed(int reason, int status) {
        switch (reason) {
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return R.string.terminow_closed_low_memory;
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return status == 9 ? R.string.terminow_closed_killed : 0;
            case android.app.ApplicationExitInfo.REASON_CRASH:
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return R.string.terminow_closed_crash;
            case android.app.ApplicationExitInfo.REASON_ANR: return R.string.terminow_closed_not_responding;
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return R.string.terminow_closed_resources;
            case android.app.ApplicationExitInfo.REASON_FREEZER: return R.string.terminow_closed_frozen;
            case android.app.ApplicationExitInfo.REASON_OTHER: return R.string.terminow_closed_other;
            default: return 0;
        }
    }

    @Nullable
    private TerminalSession restoreTab(@NonNull TermuxService service, @NonNull TerminowWorkspace.Tab tab, @NonNull String when) {
        String output = mWorkspace.loadOutput(tab.id);
        String header = mActivity.getString(R.string.terminow_restore_previous, when);
        if (TerminowWorkspace.KIND_HOST.equals(tab.kind)) {
            TerminowStore.Host host = null;
            for (TerminowStore.Host candidate : mStore.getHostsIn(tab.vault))
                if (candidate.getKey().equals(tab.hostKey)) host = candidate;
            if (host == null)
                return restoreLocal(service, tab, output, header, mActivity.getString(R.string.terminow_restore_host_gone, tab.title));
            String footer = mActivity.getString(tab.sftp ? R.string.terminow_restore_sftp_again :
                host.remoteTmux ? R.string.terminow_restore_host_tmux : R.string.terminow_restore_host_again, host.getTitle());
            String title = tab.sftp ? mActivity.getString(R.string.terminow_sftp_session, host.getTitle()) : host.getTitle();
            TermuxSession created = service.createTermuxSession(null, null, null, getStartDirectory(), false, title);
            if (created == null) return null;
            TerminalSession session = created.getTerminalSession();
            session.mPrelude = TerminowWorkspace.prelude(output, header, footer);
            TerminowWorkspace.register(session, tab);
            TerminowStore.Host target = host;
            // It connects when the tab is first shown, not all at once in the background
            session.mOnStarted = () -> {
                if (tab.sftp) {
                    mSftpSessions.add(session);
                    Route route = prepareRoute(target);
                    if (route == null) return;
                    mLauncher.track(session, new TerminowLauncher.SessionInfo(null, target.getTitle(), "sftp", "", ""));
                    if (isBrowsable(route)) openSftpFiles(session, target, route, tab, title, true);
                    else sftpCommandLine(session, target, route, tab, title, true);
                } else {
                    startConnection(session, target, tab, true);
                }
            };
            return session;
        }
        if (TerminowWorkspace.KIND_LINUX.equals(tab.kind)) {
            String folder = mLauncher.restorableFolder(tab.environment, tab.user, tab.folder);
            if (folder == null)
                return restoreLocal(service, tab, output, header, mActivity.getString(R.string.terminow_restore_environment_gone, tab.environment));
            String footer = !tab.folder.isEmpty() && !folder.equals(tab.folder) ?
                mActivity.getString(R.string.terminow_restore_folder_gone, tab.folder) : mActivity.getString(R.string.terminow_restore_new_shell);
            mNextPrelude = TerminowWorkspace.prelude(output, header, footer);
            TerminalSession session = mLauncher.restoreLinuxTerminal(tab.environment, tab.user, folder, tab.localTmux, tab.title);
            mNextPrelude = null;
            if (session == null) return null;
            tab.folder = folder;
            TerminowWorkspace.register(session, tab);
            return session;
        }
        return restoreLocal(service, tab, output, header, null);
    }

    /** A restored local tab, in its folder if it is still there; {@code note} says why it is not what it was. */
    @Nullable
    private TerminalSession restoreLocal(@NonNull TermuxService service, @NonNull TerminowWorkspace.Tab tab, @NonNull String output,
                                         @NonNull String header, @Nullable String note) {
        boolean wasLocal = TerminowWorkspace.KIND_LOCAL.equals(tab.kind);
        String folder = wasLocal ? tab.folder : "";
        String footer = note;
        if (folder.isEmpty() || !new File(folder).isDirectory()) {
            if (footer == null && !folder.isEmpty()) footer = mActivity.getString(R.string.terminow_restore_folder_gone, folder);
            folder = getStartDirectory();
        }
        if (footer == null) footer = mActivity.getString(R.string.terminow_restore_new_shell);
        TermuxSession created = service.createTermuxSession(null, null, null, folder, false, tab.title.isEmpty() ? null : tab.title);
        if (created == null) return null;
        TerminalSession session = created.getTerminalSession();
        session.mPrelude = TerminowWorkspace.prelude(output, header, footer);
        // A tab that can not be what it was is a local terminal from now on
        tab.kind = TerminowWorkspace.KIND_LOCAL;
        tab.folder = folder;
        if (!wasLocal) tab.localTmux = "";
        TerminowWorkspace.register(session, tab);
        mLauncher.track(session, new TerminowLauncher.SessionInfo(TerminowMcpTargets.TARGET_TERMINOW, "Local", "bash", folder, tab.localTmux));
        // Its own tmux session, attached to if it still runs, else made in its folder: the usual start of a tab
        String command = tab.localTmux.isEmpty() ? null : getLocalStartupCommand(tab.localTmux, TerminowStore.shellQuote(folder));
        if (command != null && command.contains("tmux")) writeWhenStartedShown(session, command + "\r");
        return session;
    }

    /** Write to a session once it started, which is when its tab is first shown. */
    private void writeWhenStartedShown(@NonNull TerminalSession session, @NonNull String text) {
        session.mOnStarted = () -> writeWhenStarted(session, text, 0);
    }

    /** The terminal sessions of the service, for the debug log, which reads them on its own thread. */
    @NonNull
    private List<TerminalSession> allSessions() {
        List<TerminalSession> sessions = new ArrayList<>();
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return sessions;
        // A copy first: the list is the service's own, which the main thread changes
        for (com.termux.shared.termux.shell.command.runner.terminal.TermuxSession session : new ArrayList<>(service.getTermuxSessions()))
            sessions.add(session.getTerminalSession());
        return sessions;
    }

    /** The activity was resumed, for example after a screen of Android settings. */
    public void onResume() {
        mExplorer.onResume();
        mVaultsUi.onResume();
        // A purchase whose check failed, or a renewal or expiry that the server found, is picked up
        mSettings.recoverPurchases(() -> {
            updateItems();
            updatePremiumButton();
        });
    }

    /** The activity ends: close what only lives as long as it, like connections to MCP servers. */
    public void onDestroy() {
        mMcpPage.onDestroy();
    }

    public void onStoragePermissionResult() {
        mExplorer.onStoragePermissionResult();
    }

    /**
     * Open a new terminal in a real folder, of HOME or shared storage, for the File Manager. The session is started with
     * the folder as its working directory, nothing is typed into it or into another session, and
     * tmux sessions that run are not touched.
     */
    void openTerminalAt(@NonNull File directory) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        TermuxSession session = service.createTermuxSession(null, null, null, directory.getAbsolutePath(), false, directory.getName());
        if (session == null) return;
        mLauncher.track(session.getTerminalSession(), new TerminowLauncher.SessionInfo(TerminowMcpTargets.TARGET_TERMINOW, "Local", "bash",
            directory.getAbsolutePath(), ""));
        TerminowWorkspace.Tab tab = new TerminowWorkspace.Tab();
        tab.id = TerminowWorkspace.newId();
        tab.folder = directory.getAbsolutePath();
        TerminowWorkspace.register(session.getTerminalSession(), tab);
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(session.getTerminalSession());
        hide();
    }

    /** The Local Terminal that was opened last and when, so that a double tap opens one. */
    @Nullable private WeakReference<TerminalSession> mLastLocalTerminal;
    private long mLastLocalTerminalTap;

    /**
     * Open a new Local Terminal with the default launch profile of the settings. The TermiNow
     * shell gets its startup command once, written to its own new shell before the user types;
     * other profiles start their program directly. Nothing runs again when the tab is shown again
     * or the activity is recreated.
     */
    private void openLocalTerminal() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        // A second tap right after the first shows the tab the first one opened
        long now = android.os.SystemClock.uptimeMillis();
        TerminalSession last = mLastLocalTerminal != null ? mLastLocalTerminal.get() : null;
        if (last != null && last.isRunning() && TerminowLaunchChoice.isRepeatTap(mLastLocalTerminalTap, now)) {
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(last);
            hide();
            return;
        }
        mLastLocalTerminalTap = now;
        if (mLauncher.launchDefault(service.getTermuxSessionsSize() + 1)) {
            TerminalSession started = mActivity.getCurrentSession();
            mLastLocalTerminal = started != null ? new WeakReference<>(started) : null;
            return;
        }

        TerminalSession session = addSession(null);
        if (session == null) return;
        mLastLocalTerminal = new WeakReference<>(session);

        String tmuxName = "local" + (service.getIndexOfSession(session) + 1);
        String command = getLocalStartupCommand(tmuxName);
        if (command != null)
            writeWhenStarted(session, command + "\r", 0);
        mLauncher.track(session, new TerminowLauncher.SessionInfo(TerminowMcpTargets.TARGET_TERMINOW, "Local", "bash",
            TermuxConstants.TERMUX_HOME_DIR_PATH, command != null && command.contains("tmux") ? tmuxName : ""));
        TerminowWorkspace.Tab tab = new TerminowWorkspace.Tab();
        tab.id = TerminowWorkspace.newId();
        tab.folder = TermuxConstants.TERMUX_HOME_DIR_PATH;
        tab.localTmux = command != null && command.contains("tmux") ? tmuxName : "";
        TerminowWorkspace.register(session, tab);
    }

    /**
     * Get the first command of the fresh shell of a new local terminal, {@code null} if there is
     * none. Each local terminal has its own tmux session, which is attached to if it is still
     * running, keeping its folders and programs, or else is created in the home directory. tmux
     * is not started inside another tmux session.
     */
    @Nullable
    private String getLocalStartupCommand(@NonNull String tmuxSessionName) {
        return getLocalStartupCommand(tmuxSessionName, "\"$HOME\"");
    }

    /** Like {@link #getLocalStartupCommand(String)}, a new tmux session starting in {@code folder}, a shell word. */
    @Nullable
    private String getLocalStartupCommand(@NonNull String tmuxSessionName, @NonNull String folder) {
        boolean showFolder = mStore.shouldShowFolderOnStartup();
        // Only if tmux is installed, it is not in the system shell that is started without the packages
        boolean tmux = mStore.shouldStartInTmux() && new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "tmux").exists();
        if (!tmux)
            return showFolder ? "pwd; ls" : null;

        // The session name is made by the app and only has letters and digits, = matches it exactly
        String target = "=" + tmuxSessionName;
        // Sent to the shell of the new session before it is attached, so before the user types
        // A pane target needs the colon after an exact session name
        String showFolderKeys = showFolder ? " \\; send-keys -t " + target + ": 'pwd; ls' Enter" : "";
        return "if [ -z \"$TMUX\" ]; then if tmux has-session -t " + target + " 2>/dev/null;" +
            " then tmux attach-session -t " + target + ";" +
            " else tmux new-session -s " + tmuxSessionName + " -c " + folder + showFolderKeys + "; fi; fi";
    }

    private void connect(@NonNull TerminowStore.Host host) {
        if (host.isRdp()) {
            openRdp(host);
            return;
        }
        if (host.isWinrm()) {
            openWinrm(host);
            return;
        }
        if (!startConnecting(host, false)) return;
        TerminalSession session = addSession(host.getTitle());
        if (session == null) {
            mConnectingHosts.remove(host.getKey() + ":ssh");
            return;
        }
        TerminowWorkspace.Tab tab = hostTab(host, false);
        TerminowWorkspace.register(session, tab);
        if (!startConnection(session, host, tab, false)) mConnectingHosts.remove(host.getKey() + ":ssh");
    }

    /** A new tab of a host, with a new id: its own tmux session on the server. */
    @NonNull
    private TerminowWorkspace.Tab hostTab(@NonNull TerminowStore.Host host, boolean sftp) {
        TerminowWorkspace.Tab tab = new TerminowWorkspace.Tab();
        tab.id = TerminowWorkspace.newId();
        tab.kind = TerminowWorkspace.KIND_HOST;
        tab.hostKey = host.getKey();
        tab.vault = mStore.getActiveVault();
        tab.title = host.getTitle();
        tab.sftp = sftp;
        return tab;
    }

    /**
     * Connect in a session whose shell is ready or about to start: a new tab, a restored one, or
     * one whose connection was lost. Only the login command is typed, never what ran before; a
     * host that keeps its terminals in tmux is attached to the tmux session of the tab again.
     *
     * @param again Whether the tab was connected before, so its startup snippet is not typed
     *              into a tmux session that may run a program.
     */
    private boolean startConnection(@NonNull TerminalSession session, @NonNull TerminowStore.Host host,
                                    @NonNull TerminowWorkspace.Tab tab, boolean again) {
        Route route = prepareRoute(host);
        if (route == null) return false;
        applySessionOptions(session, host);
        mLauncher.track(session, new TerminowLauncher.SessionInfo(null, host.getTitle(), host.telnet ? "telnet" : "ssh", "", ""));
        String command = route.login.getCommand(route.jump, route.proxyConnector, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax(),
            host.remoteTmux ? tab.remoteTmuxName() : "");
        mStore.addLog(host.getTitle(), command);
        writeWhenStarted(session, command + "\r", 0);
        writePasswordsWhenAsked(session, getLoginPrompts(route.login, route.jump), 0);
        watchConnection(session, host, false, host.getTitle(), 0, tab, again);
        if (host.remoteTmux && !host.telnet) watchRemoteTmux(session, host, tab, 0);
        return true;
    }

    /** Write a line of TermiNow into a tab, after which later checks of its output start. */
    private void markSession(@NonNull TerminalSession session, @NonNull String text) {
        if (session.getEmulator() == null) return;
        byte[] line = TerminowWorkspace.markLine(text);
        session.getEmulator().append(line, line.length);
        if (session == mActivity.getCurrentSession()) mActivity.getTerminalView().onScreenUpdated();
    }

    /** Connect again in the same tab, whose shell is back at its prompt after the connection ended. */
    private void reconnectInTab(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, @NonNull TerminowWorkspace.Tab tab) {
        if (!session.isRunning()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_restore_tab_ended), true);
            return;
        }
        markSession(session, mActivity.getString(R.string.terminow_restore_reconnecting, host.getTitle()));
        startConnection(session, host, tab, true);
    }

    private static final Pattern REMOTE_TMUX_MISSING_PATTERN = Pattern.compile("\\[" + TerminowStore.REMOTE_TMUX_MISSING + "\\]");
    /** A connection that was made and then lost, not one that the user ended. */
    private static final Pattern CONNECTION_LOST_PATTERN = Pattern.compile(
        "client_loop: send disconnect|Broken pipe|Connection reset by peer|Timeout, server .* not responding|" +
            "closed by remote host|packet_write_wait|Software caused connection abort");

    /** The server has no tmux: offer to install it, once per connection. */
    private void watchRemoteTmux(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, @NonNull TerminowWorkspace.Tab tab, int check) {
        if (check > 60 || (session.getPid() > 0 && !session.isRunning())) return;
        String transcript = session.getPid() > 0 ? ShellUtils.getTerminalSessionTranscriptText(session, false, true) : null;
        if (transcript != null && REMOTE_TMUX_MISSING_PATTERN.matcher(TerminowWorkspace.afterMark(transcript)).find()) {
            offerRemoteTmux(session, host, tab);
            return;
        }
        mHandler.postDelayed(() -> watchRemoteTmux(session, host, tab, check + 1), 1000);
    }

    private void offerRemoteTmux(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, @NonNull TerminowWorkspace.Tab tab) {
        if (!mActivity.isVisible()) return;
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_remote_tmux_missing_title)
            .setMessage(mActivity.getString(R.string.terminow_remote_tmux_missing_message, host.getTitle()))
            .setPositiveButton(R.string.terminow_remote_tmux_install, (d, w) -> {
                // Typed into the shell of the server only now that the user asked for it
                if (session.isRunning()) session.write(TerminowStore.installRemoteTmuxCommand(tab.remoteTmuxName()) + "\r");
            })
            .setNeutralButton(R.string.terminow_remote_tmux_turn_off, (d, w) -> {
                host.remoteTmux = false;
                mStore.setHosts(mHosts);
                updateItems();
            })
            .setNegativeButton(R.string.terminow_remote_tmux_not_now, null)
            .show();
    }

    /**
     * After a connection was made: if it is lost, like when the network goes, offer to connect
     * again in the same tab, to the same tmux session on the server if the host keeps one.
     */
    private void watchConnectionLost(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, @NonNull TerminowWorkspace.Tab tab) {
        if (session.getPid() > 0 && !session.isRunning()) return;
        if (TerminowWorkspace.tabOf(session) != tab) return;
        String transcript = session.getPid() > 0 ? ShellUtils.getTerminalSessionTranscriptText(session, false, true) : null;
        if (transcript != null && host.mosh) {
            Failure failure = findConnectionFailure(TerminowWorkspace.afterMark(transcript));
            if (failure != null && failure.kind == FailureKind.MOSH_UDP) {
                showConnectionFailed(session, host, false, describeFailure(failure, host), tab);
                return;
            }
        }
        if (transcript != null && CONNECTION_LOST_PATTERN.matcher(TerminowWorkspace.afterMark(transcript)).find()) {
            // Checked again only after the next connection, which writes its own line
            markSession(session, mActivity.getString(R.string.terminow_restore_connection_lost, host.getTitle()));
            showConnectionLost(session, host, tab);
            return;
        }
        mHandler.postDelayed(() -> watchConnectionLost(session, host, tab), 3000);
    }

    private void showConnectionLost(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, @NonNull TerminowWorkspace.Tab tab) {
        if (!mActivity.isVisible()) return;
        String message = mActivity.getString(host.remoteTmux ? R.string.terminow_restore_lost_tmux : R.string.terminow_restore_lost_message);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_restore_lost_title, host.getTitle()))
            .setMessage(message)
            .setPositiveButton(R.string.terminow_restore_reconnect, (d, w) -> reconnectInTab(session, host, tab))
            .setNegativeButton(R.string.terminow_close_dialog, null)
            .show();
    }

    /** How a connection reaches its host: the jump host and the proxy connector, if it uses them. */
    private static class Route {
        /** The host as it logs in: with the username, password and key of its identity. */
        TerminowStore.Host login;
        @Nullable TerminowStore.Host jump;
        String proxyConnector = "";
    }

    /**
     * Find the jump host and install the proxy connector for a host. Returns {@code null} and
     * tells the user why if the connection can not be made.
     */
    @Nullable
    private Route prepareRoute(@NonNull TerminowStore.Host host) {
        Route route = new Route();
        List<TerminowStore.Identity> identities = mStore.getIdentities();
        route.login = TerminowStore.resolve(host, identities);
        if (host.telnet) return route;
        if (!host.jumpHost.isEmpty()) {
            route.jump = TerminowStore.findHost(mHosts, host.jumpHost);
            if (route.jump == null || route.jump.telnet) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_jump_missing), true);
                return null;
            }
            route.jump = TerminowStore.resolve(route.jump, identities);
        }
        if (!host.proxyType.isEmpty()) {
            try {
                route.proxyConnector = TerminowProxy.install(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH)).getPath();
            } catch (IOException e) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_proxy_install_failed, e.getMessage()), true);
                return null;
            }
        }
        return route;
    }

    /** The color theme and the Backspace key of the host, for its session only. */
    private void applySessionOptions(@NonNull TerminalSession session, @NonNull TerminowStore.Host host) {
        session.setBackspaceSendsCtrlH(host.backspaceSendsCtrlH);
        TerminowThemes.Theme theme = TerminowThemes.find(host.colorScheme);
        if (theme != null) session.setColorScheme(TerminowThemes.toScheme(theme));
    }

    /** A password to type in when a prompt that matches is shown, only once. */
    private static class PasswordPrompt {
        final Pattern prompt;
        final String password;
        boolean written;

        PasswordPrompt(@NonNull Pattern prompt, @NonNull String password) {
            this.prompt = prompt;
            this.password = password;
        }
    }

    /**
     * The password prompts of a connection. Through a jump host, ssh asks for the password of
     * each host by its name, like `user@host's password:`, so each one only gets its own.
     */
    @NonNull
    static List<PasswordPrompt> getPasswordPrompts(@NonNull TerminowStore.Host host, @Nullable TerminowStore.Host jump) {
        List<PasswordPrompt> prompts = new ArrayList<>();
        if (host.telnet) {
            if (!host.password.isEmpty()) prompts.add(new PasswordPrompt(TELNET_PASSWORD_PROMPT_PATTERN, host.password));
            return prompts;
        }
        if (jump == null) {
            if (!host.password.isEmpty()) prompts.add(new PasswordPrompt(PASSWORD_PROMPT_PATTERN, host.password));
            return prompts;
        }
        if (!jump.password.isEmpty()) prompts.add(new PasswordPrompt(hostPasswordPrompt(jump), jump.password));
        if (!host.password.isEmpty()) prompts.add(new PasswordPrompt(hostPasswordPrompt(host), host.password));
        return prompts;
    }

    /** The passwords of a connection, and the passphrases that the keychain has for its keys. */
    @NonNull
    private List<PasswordPrompt> getLoginPrompts(@NonNull TerminowStore.Host host, @Nullable TerminowStore.Host jump) {
        List<PasswordPrompt> prompts = getPasswordPrompts(host, jump);
        if (host.telnet) return prompts;
        for (TerminowStore.Host login : jump == null ? java.util.Collections.singletonList(host) : java.util.Arrays.asList(jump, host)) {
            String fileName = keyFileName(login.keyPath);
            if (fileName == null) continue;
            String passphrase = mStore.getKeyPassphrase(fileName);
            if (!passphrase.isEmpty()) prompts.add(new PasswordPrompt(keyPassphrasePrompt(fileName), passphrase));
        }
        return prompts;
    }

    /** The name of a key file of `~/.ssh` that a host logs in with, {@code null} for none or another one. */
    @Nullable
    static String keyFileName(@NonNull String keyPath) {
        if (keyPath.isEmpty() || TerminowBiometricKeys.isAgentKeyPath(keyPath)) return null;
        String prefix = keyPath.startsWith("~/.ssh/") ? "~/.ssh/" : TermuxConstants.TERMUX_HOME_DIR_PATH + "/.ssh/";
        if (!keyPath.startsWith(prefix)) return null;
        String name = keyPath.substring(prefix.length());
        return name.isEmpty() || name.contains("/") ? null : name;
    }

    /** What ssh asks for the passphrase of a key file, like `Enter passphrase for key '/…/.ssh/id_ed25519': `. */
    @NonNull
    static Pattern keyPassphrasePrompt(@NonNull String fileName) {
        return Pattern.compile("Enter passphrase for key '[^']*/" + Pattern.quote(fileName) + "':\\s*$");
    }

    @NonNull
    private static Pattern hostPasswordPrompt(@NonNull TerminowStore.Host host) {
        return Pattern.compile("@" + Pattern.quote(host.address) + "'s password:\\s*$", Pattern.CASE_INSENSITIVE);
    }

    @Nullable
    private TerminowStore.Host getHost(@NonNull String title) {
        for (TerminowStore.Host host : mHosts)
            if (host.getTitle().equals(title)) return host;
        return null;
    }

    /** Start a session that forwards a port through the host of the forwarding until it is closed. */
    private void startForward(@NonNull TerminowStore.Forward forward) {
        TerminowStore.Host host = getHost(forward.host);
        if (host == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_forward_host_missing), true);
            return;
        }

        TerminalSession session = addSession(forward.getTitle());
        if (session == null) return;

        Route route = prepareRoute(host);
        if (route == null || host.telnet) {
            if (host.telnet) mActivity.showToast(mActivity.getString(R.string.terminow_host_telnet_no_forward), true);
            return;
        }
        String command = route.login.getForwardCommand(forward, route.jump, route.proxyConnector, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        mStore.addLog(forward.getTitle(), command);
        writeWhenStarted(session, command + "\r", 0);
        writePasswordsWhenAsked(session, getLoginPrompts(route.login, route.jump), 0);
    }

    /**
     * Type in the passwords of a connection the first time that the session waits at their
     * prompt, like `root@host's password: `. Other prompts, like to confirm the key of a new host,
     * are left to the user. Each password is only written once, so a wrong one ends at the prompt
     * to enter it again instead of being retried.
     */
    private void writePasswordsWhenAsked(@NonNull TerminalSession session, @NonNull List<PasswordPrompt> prompts, int attempt) {
        if (prompts.isEmpty() || attempt >= PASSWORD_PROMPT_CHECK_COUNT) return;

        if (session.getPid() > 0) {
            if (!session.isRunning()) return;

            String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
            if (transcript != null) {
                String lastLine = transcript.substring(transcript.lastIndexOf('\n') + 1);
                for (PasswordPrompt prompt : prompts) {
                    if (!prompt.written && prompt.prompt.matcher(lastLine).find()) {
                        prompt.written = true;
                        session.write(prompt.password + "\r");
                        break;
                    }
                }
                boolean allWritten = true;
                for (PasswordPrompt prompt : prompts) allWritten &= prompt.written;
                if (allWritten) return;
            }
        }

        mHandler.postDelayed(() -> writePasswordsWhenAsked(session, prompts, attempt + 1), PASSWORD_PROMPT_CHECK_DELAY_MS);
    }

    private void runSnippet(@NonNull TerminowStore.Snippet snippet) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;

        TerminalSession session = mActivity.getCurrentSession();
        if (session == null || !session.isRunning() || service.getIndexOfSession(session) < 0)
            session = addSession(null);
        if (session == null) return;

        hide();
        writeWhenStarted(session, snippet.command + "\r", 0);
    }

    /** Add a new session and show it. Returns {@code null} if the session could not be added. */
    @Nullable
    private TerminalSession addSession(@Nullable String sessionName) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return null;

        int sessionCount = service.getTermuxSessionsSize();
        mActivity.getTermuxTerminalSessionClient().addNewSession(false, sessionName);
        if (service.getTermuxSessionsSize() <= sessionCount) return null;

        return mActivity.getCurrentSession();
    }

    /** Write to a new session once its shell has been started, which happens when the terminal is first sized. */
    private void writeWhenStarted(@NonNull TerminalSession session, @NonNull String text, int attempt) {
        if (session.getPid() > 0) {
            session.write(text);
        } else if (attempt < WRITE_RETRY_COUNT) {
            mHandler.postDelayed(() -> writeWhenStarted(session, text, attempt + 1), WRITE_RETRY_DELAY_MS);
        }
    }

    /** Show a dialog to pick a snippet to run in the current session. */
    public void showSnippetsPicker() {
        final List<TerminowStore.Snippet> snippets = mStore.getSnippets();
        if (snippets.isEmpty()) {
            mSnippets = snippets;
            showSnippetDialog(null);
            return;
        }

        CharSequence[] titles = new CharSequence[snippets.size()];
        for (int i = 0; i < titles.length; i++)
            titles[i] = snippets.get(i).getTitle();

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_snippets)
            .setItems(titles, (dialog, which) -> runSnippet(snippets.get(which)))
            .setNeutralButton(R.string.terminow_new_snippet, (dialog, which) -> {
                mSnippets = snippets;
                showSnippetDialog(null);
            })
            .show();
    }



    private interface OnHostSaved {
        void onHostSaved(@NonNull TerminowStore.Host host);
    }

    /** Show the form to add or edit a host in a panel at the side of the screen. */
    private void showHostDialog(@Nullable TerminowStore.Host existingHost) {
        showHostDialog(existingHost, null, null);
    }

    /**
     * Show the form to add or edit a host, for a new host with the fields of a host to start
     * from if it is not {@code null}, and call a listener once the host is saved.
     */
    private void showHostDialog(@Nullable TerminowStore.Host existingHost, @Nullable TerminowStore.Host prefill,
                                @Nullable OnHostSaved onSaved) {
        Dialog dialog = new Dialog(mActivity, R.style.TerminowPanelDialog);
        dialog.setContentView(R.layout.dialog_terminow_host);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // The panel takes the same part of a wide screen as the Termius host panel and all of a
        // narrow screen, where the fields would not fit in a part of it.
        View rootView = dialog.findViewById(R.id.terminow_host_root);
        View columnView = dialog.findViewById(R.id.terminow_host_column);
        rootView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int rootWidth = right - left;
            int width = rootWidth < dp(HOST_PANEL_MIN_SCREEN_WIDTH_DP) ? rootWidth :
                Math.max(dp(HOST_PANEL_MIN_WIDTH_DP), Math.round(rootWidth * HOST_PANEL_SCREEN_WIDTH_FRACTION));
            ViewGroup.LayoutParams params = columnView.getLayoutParams();
            if (params.width != width) {
                params.width = width;
                columnView.post(() -> columnView.setLayoutParams(params));
            }
        });
        // Tapping the dimmed home screen next to the panel closes it
        rootView.setOnClickListener(v -> dialog.dismiss());

        TextView titleView = dialog.findViewById(R.id.terminow_host_title);
        TextView vaultView = dialog.findViewById(R.id.terminow_host_vault);
        EditText labelView = dialog.findViewById(R.id.terminow_host_label);
        EditText addressView = dialog.findViewById(R.id.terminow_host_address);
        EditText groupView = dialog.findViewById(R.id.terminow_host_group);
        EditText tagsView = dialog.findViewById(R.id.terminow_host_tags);
        CheckBox backspaceView = dialog.findViewById(R.id.terminow_host_backspace);
        View sshCard = dialog.findViewById(R.id.terminow_host_ssh_card);
        CheckBox sshView = dialog.findViewById(R.id.terminow_host_ssh);
        CheckBox moshView = dialog.findViewById(R.id.terminow_host_mosh);
        EditText portView = dialog.findViewById(R.id.terminow_host_port);
        EditText usernameView = dialog.findViewById(R.id.terminow_host_username);
        EditText passwordView = dialog.findViewById(R.id.terminow_host_password);
        EditText keyView = dialog.findViewById(R.id.terminow_host_key);
        View keyRemoveView = dialog.findViewById(R.id.terminow_host_key_remove);
        EditText identityView = dialog.findViewById(R.id.terminow_host_identity);
        View identityRemoveView = dialog.findViewById(R.id.terminow_host_identity_remove);
        EditText snippetView = dialog.findViewById(R.id.terminow_host_snippet);
        CheckBox agentView = dialog.findViewById(R.id.terminow_host_agent);
        CheckBox remoteTmuxView = dialog.findViewById(R.id.terminow_host_remote_tmux);
        EditText jumpView = dialog.findViewById(R.id.terminow_host_jump);
        EditText proxyView = dialog.findViewById(R.id.terminow_host_proxy);
        LinearLayout envList = dialog.findViewById(R.id.terminow_host_env);
        TextView schemeView = dialog.findViewById(R.id.terminow_host_scheme);
        ImageView iconPreview = dialog.findViewById(R.id.terminow_host_icon_preview);
        TextView colorValue = dialog.findViewById(R.id.terminow_host_color);
        CheckBox telnetView = dialog.findViewById(R.id.terminow_host_telnet);
        View telnetOptions = dialog.findViewById(R.id.terminow_host_telnet_options);
        EditText telnetPortView = dialog.findViewById(R.id.terminow_host_telnet_port);

        titleView.setText(existingHost == null ? R.string.terminow_new_host : R.string.terminow_edit_host);
        // The fields that are picked, not typed, are kept here until the host is saved
        TerminowStore.Host draft = new TerminowStore.Host();
        TerminowStore.Host source = existingHost != null ? existingHost : prefill;

        // The vault: the one that is shown, and the host can be moved to another one
        List<String[]> vaults = mVaultsUi.getHostVaults();
        String currentScope = mStore.getActiveVault();
        final String[] scope = {currentScope};
        Runnable showVault = () -> {
            String name = vaults.get(0)[1];
            for (String[] vault : vaults) if (vault[0].equals(scope[0])) name = vault[1];
            vaultView.setText(mActivity.getString(R.string.terminow_host_vault_name, name));
        };
        showVault.run();
        vaultView.setOnClickListener(v -> {
            String[] names = new String[vaults.size()];
            for (int i = 0; i < vaults.size(); i++) names[i] = vaults.get(i)[1];
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_host_vault_title)
                .setItems(names, (d, which) -> {
                    scope[0] = vaults.get(which)[0];
                    // A jump host is looked up in the vault of the host
                    if (!scope[0].equals(currentScope)) {
                        draft.jumpHost = "";
                        jumpView.setText("");
                    }
                    showVault.run();
                })
                .show();
        });

        // A new host is added to the group that is being shown
        String group = existingHost != null ? existingHost.group : mCurrentGroup;
        if (group != null && mGroups.contains(group))
            groupView.setText(group);
        groupView.setOnClickListener(v -> showGroupPicker(groupView));
        dialog.findViewById(R.id.terminow_host_group_button).setOnClickListener(v -> showGroupPicker(groupView));

        if (source != null) {
            addressView.setText(source.address);
            usernameView.setText(source.username);
            keyView.setText(source.keyPath);
            if (source.port != 22)
                portView.setText(String.valueOf(source.port));
        }
        if (existingHost != null) {
            labelView.setText(existingHost.label);
            passwordView.setText(existingHost.password);
            draft.identity = existingHost.identity;
            if (mStore.getSnippet(existingHost.startupSnippet) != null)
                snippetView.setText(existingHost.startupSnippet);
            moshView.setChecked(existingHost.mosh);
            draft.tags.addAll(existingHost.tags);
            backspaceView.setChecked(existingHost.backspaceSendsCtrlH);
            telnetView.setChecked(existingHost.telnet);
            sshView.setChecked(!existingHost.telnet);
            if (existingHost.telnetPort != 23)
                telnetPortView.setText(String.valueOf(existingHost.telnetPort));
            agentView.setChecked(existingHost.agentForwarding);
            remoteTmuxView.setChecked(existingHost.remoteTmux);
            draft.jumpHost = existingHost.jumpHost;
            draft.proxyType = existingHost.proxyType;
            draft.proxyHost = existingHost.proxyHost;
            draft.proxyPort = existingHost.proxyPort;
            draft.colorScheme = existingHost.colorScheme;
            draft.icon = existingHost.icon;
            draft.iconColor = existingHost.iconColor;
            for (Map.Entry<String, String> variable : existingHost.env.entrySet())
                addEnvRow(envList, variable.getKey(), variable.getValue());
        }

        // Tags
        Runnable showTags = () -> tagsView.setText(TextUtils.join(", ", draft.tags));
        showTags.run();
        View.OnClickListener editTags = v -> showTagsEditor(draft.tags, showTags);
        tagsView.setOnClickListener(editTags);
        dialog.findViewById(R.id.terminow_host_tags_button).setOnClickListener(editTags);

        // The protocol: SSH (with Mosh and Telnet), the built-in RDP desktop, or WinRM PowerShell
        String[] protocol = {existingHost != null ? existingHost.protocol : TerminowStore.Host.PROTOCOL_SSH};
        EditText domainView = dialog.findViewById(R.id.terminow_host_domain);
        CheckBox rdpNlaView = dialog.findViewById(R.id.terminow_host_rdp_nla);
        CheckBox rdpClipboardView = dialog.findViewById(R.id.terminow_host_rdp_clipboard);
        EditText rdpSizeView = dialog.findViewById(R.id.terminow_host_rdp_size);
        CheckBox winrmHttpsView = dialog.findViewById(R.id.terminow_host_winrm_https);
        CheckBox winrmVerifyView = dialog.findViewById(R.id.terminow_host_winrm_verify);
        int[] rdpSize = {existingHost != null ? existingHost.rdpWidth : 0, existingHost != null ? existingHost.rdpHeight : 0};
        if (existingHost != null) {
            domainView.setText(existingHost.domain);
            rdpNlaView.setChecked(existingHost.rdpNla);
            rdpClipboardView.setChecked(existingHost.rdpClipboard);
            winrmHttpsView.setChecked(existingHost.winrmHttps);
            winrmVerifyView.setChecked(existingHost.winrmVerify);
        }
        Runnable showRdpSize = () -> rdpSizeView.setText(rdpSize[0] == 0 ? mActivity.getString(R.string.terminow_host_rdp_size_fit) :
            rdpSize[0] + " × " + rdpSize[1]);
        showRdpSize.run();
        rdpSizeView.setOnClickListener(v -> {
            int[][] sizes = {{0, 0}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}};
            CharSequence[] labels = new CharSequence[sizes.length];
            for (int i = 0; i < sizes.length; i++)
                labels[i] = sizes[i][0] == 0 ? mActivity.getString(R.string.terminow_host_rdp_size_fit) : sizes[i][0] + " × " + sizes[i][1];
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_host_rdp_size)
                .setItems(labels, (d, which) -> {
                    rdpSize[0] = sizes[which][0];
                    rdpSize[1] = sizes[which][1];
                    showRdpSize.run();
                })
                .show();
        });
        LinearLayout protocolsView = dialog.findViewById(R.id.terminow_host_protocols);
        View windowsCard = dialog.findViewById(R.id.terminow_host_windows_card);
        View rdpOptions = dialog.findViewById(R.id.terminow_host_rdp_options);
        View winrmOptions = dialog.findViewById(R.id.terminow_host_winrm_options);
        View sessionCard = dialog.findViewById(R.id.terminow_host_session_card);
        View telnetCard = dialog.findViewById(R.id.terminow_host_telnet_card);
        View sshRow = dialog.findViewById(R.id.terminow_host_ssh_row);
        View moshRow = dialog.findViewById(R.id.terminow_host_mosh_row);
        View keyRowView = dialog.findViewById(R.id.terminow_host_key_row);
        String[] protocols = {TerminowStore.Host.PROTOCOL_SSH, TerminowStore.Host.PROTOCOL_RDP, TerminowStore.Host.PROTOCOL_WINRM};
        int[] protocolLabels = {R.string.terminow_host_protocol_ssh, R.string.terminow_host_protocol_rdp, R.string.terminow_host_protocol_winrm};
        List<TextView> protocolChips = new ArrayList<>();
        Runnable[] showKind = new Runnable[1];
        for (int i = 0; i < protocols.length; i++) {
            TextView chip = new TextView(mActivity);
            chip.setText(protocolLabels[i]);
            chip.setGravity(Gravity.CENTER);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            String value = protocols[i];
            chip.setTag(value);
            chip.setOnClickListener(v -> {
                protocol[0] = value;
                showKind[0].run();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i > 0) params.setMarginStart(dp(8));
            protocolsView.addView(chip, params);
            protocolChips.add(chip);
        }
        showKind[0] = () -> {
            boolean rdp = TerminowStore.Host.PROTOCOL_RDP.equals(protocol[0]);
            boolean winrm = TerminowStore.Host.PROTOCOL_WINRM.equals(protocol[0]);
            boolean windows = rdp || winrm;
            for (TextView chip : protocolChips) {
                boolean selected = chip.getTag().equals(protocol[0]);
                GradientDrawable shape = new GradientDrawable();
                shape.setCornerRadius(dp(10));
                shape.setColor(ContextCompat.getColor(mActivity, selected ? R.color.terminow_panel_accent : R.color.terminow_panel_button));
                chip.setBackground(shape);
                chip.setTextColor(ContextCompat.getColor(mActivity, selected ? R.color.terminow_bar_home : R.color.terminow_text));
            }
            windowsCard.setVisibility(windows ? View.VISIBLE : View.GONE);
            rdpOptions.setVisibility(rdp ? View.VISIBLE : View.GONE);
            winrmOptions.setVisibility(winrm ? View.VISIBLE : View.GONE);
            sshRow.setVisibility(windows ? View.GONE : View.VISIBLE);
            moshRow.setVisibility(windows ? View.GONE : View.VISIBLE);
            dialog.findViewById(R.id.terminow_host_mosh_options).setVisibility(!windows && moshView.isChecked() ? View.VISIBLE : View.GONE);
            telnetCard.setVisibility(windows ? View.GONE : View.VISIBLE);
            keyRowView.setVisibility(windows ? View.GONE : View.VISIBLE);
            // RDP keeps only the jump host of the session options: its SSH tunnel
            sessionCard.setVisibility(winrm ? View.GONE : View.VISIBLE);
            ViewGroup sessionGroup = (ViewGroup) sessionCard;
            for (int c = 0; c < sessionGroup.getChildCount(); c++) {
                View child = sessionGroup.getChildAt(c);
                boolean jump = child == jumpView || (child instanceof ViewGroup && ((ViewGroup) child).indexOfChild(jumpView) >= 0);
                child.setVisibility(!rdp || jump ? View.VISIBLE : View.GONE);
            }
            sshCard.setAlpha(!windows && telnetView.isChecked() ? 0.5f : 1f);
            portView.setHint(rdp ? "3389" : winrm ? (winrmHttpsView.isChecked() ? "5986" : "5985") :
                mActivity.getString(R.string.terminow_host_port_default));
        };
        winrmHttpsView.setOnCheckedChangeListener((b, checked) -> showKind[0].run());

        // SSH, Mosh and Telnet: Mosh needs SSH to start, Telnet is used instead of both
        Runnable showProtocol = () -> {
            telnetOptions.setVisibility(telnetView.isChecked() ? View.VISIBLE : View.GONE);
            sshCard.setAlpha(telnetView.isChecked() ? 0.5f : 1f);
        };
        sshView.setOnCheckedChangeListener((button, checked) -> {
            if (checked) telnetView.setChecked(false);
            else moshView.setChecked(false);
            showProtocol.run();
        });
        EditText moshServerView = dialog.findViewById(R.id.terminow_host_mosh_server);
        View moshOptions = dialog.findViewById(R.id.terminow_host_mosh_options);
        if (existingHost != null) moshServerView.setText(existingHost.moshServer);
        moshOptions.setVisibility(moshView.isChecked() ? View.VISIBLE : View.GONE);
        moshView.setOnCheckedChangeListener((button, checked) -> {
            if (checked) sshView.setChecked(true);
            moshOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
            // Turning Mosh on starts from the usual ports, which the firewall of the host must let in
            if (checked && moshServerView.getText().toString().trim().isEmpty())
                moshServerView.setText(mActivity.getString(R.string.terminow_host_mosh_server_default_ports));
        });
        telnetView.setOnCheckedChangeListener((button, checked) -> {
            if (checked) sshView.setChecked(false);
            showProtocol.run();
        });
        showProtocol.run();
        showKind[0].run();
        dialog.findViewById(R.id.terminow_host_mosh_learn).setOnClickListener(v -> openUrl("https://mosh.org"));

        // Credentials
        dialog.findViewById(R.id.terminow_host_password_show).setOnClickListener(v -> {
            boolean hidden = (passwordView.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD;
            int selection = passwordView.getSelectionEnd();
            passwordView.setInputType(InputType.TYPE_CLASS_TEXT | (hidden ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordView.setSelection(Math.max(0, selection));
        });
        Runnable showKeyRemove = () -> keyRemoveView.setVisibility(keyView.getText().length() > 0 ? View.VISIBLE : View.GONE);
        keyView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { showKeyRemove.run(); }
        });
        showKeyRemove.run();
        keyView.setOnClickListener(v -> showKeyPicker(keyView));
        dialog.findViewById(R.id.terminow_host_keys).setOnClickListener(v -> showKeyPicker(keyView));
        keyRemoveView.setOnClickListener(v -> keyView.setText(""));

        // An identity: the fields that it has come from it, the others from the host
        View usernameRow = dialog.findViewById(R.id.terminow_host_username_row);
        View passwordRow = dialog.findViewById(R.id.terminow_host_password_row);
        View keyRow = dialog.findViewById(R.id.terminow_host_key_row);
        Runnable showIdentity = () -> {
            TerminowStore.Identity identity = draft.identity.isEmpty() ? null : TerminowStore.findIdentity(mStore.getIdentities(), draft.identity);
            if (identity == null) draft.identity = "";
            identityView.setText(identity == null ? "" : identity.getTitle());
            identityRemoveView.setVisibility(identity == null ? View.GONE : View.VISIBLE);
            boolean username = identity != null && !identity.username.isEmpty();
            boolean password = identity != null && !identity.password.isEmpty();
            boolean key = identity != null && !identity.keyPath.isEmpty();
            usernameRow.setAlpha(username ? 0.4f : 1f);
            usernameView.setEnabled(!username);
            passwordRow.setAlpha(password ? 0.4f : 1f);
            passwordView.setEnabled(!password);
            keyRow.setAlpha(key ? 0.4f : 1f);
            keyView.setEnabled(!key);
            dialog.findViewById(R.id.terminow_host_keys).setEnabled(!key);
        };
        identityView.setOnClickListener(v -> showIdentityPicker(id -> {
            draft.identity = id;
            showIdentity.run();
        }));
        identityRemoveView.setOnClickListener(v -> {
            draft.identity = "";
            showIdentity.run();
        });
        showIdentity.run();

        // Session options
        snippetView.setOnClickListener(v -> showStartupSnippetPicker(snippetView));
        dialog.findViewById(R.id.terminow_host_snippet_button).setOnClickListener(v -> showStartupSnippetPicker(snippetView));

        Runnable showJump = () -> {
            TerminowStore.Host jump = draft.jumpHost.isEmpty() ? null : TerminowStore.findHost(mStore.getHostsIn(scope[0]), draft.jumpHost);
            jumpView.setText(jump == null ? (draft.jumpHost.isEmpty() ? "" : draft.jumpHost) :
                mActivity.getString(R.string.terminow_host_chaining_through, jump.getTitle()));
        };
        showJump.run();
        View.OnClickListener pickJump = v -> showJumpHostPicker(scope[0], existingHost, draft, showJump);
        jumpView.setOnClickListener(pickJump);
        dialog.findViewById(R.id.terminow_host_jump_button).setOnClickListener(pickJump);

        Runnable showProxy = () -> proxyView.setText(draft.proxyType.isEmpty() ? "" :
            mActivity.getString(R.string.terminow_host_proxy_value, draft.proxyType.toUpperCase(Locale.ROOT), draft.proxyHost, draft.proxyPort));
        showProxy.run();
        View.OnClickListener editProxy = v -> showProxyEditor(draft, showProxy);
        proxyView.setOnClickListener(editProxy);
        dialog.findViewById(R.id.terminow_host_proxy_button).setOnClickListener(editProxy);

        dialog.findViewById(R.id.terminow_host_env_add).setOnClickListener(v -> addEnvRow(envList, "", ""));

        Runnable showScheme = () -> schemeView.setText(draft.colorScheme.isEmpty() ?
            mActivity.getString(R.string.terminow_host_color_scheme_default) : draft.colorScheme);
        showScheme.run();
        dialog.findViewById(R.id.terminow_host_scheme_row).setOnClickListener(v -> showSchemePicker(draft, showScheme));

        // Icon and color: a custom look for the host in the list, or the protocol's default
        Runnable showIcon = () -> {
            iconPreview.setImageResource(hostIconDrawable(draft.icon, protocolIconDrawable(protocol[0])));
            Integer custom = parseHostColor(draft.iconColor);
            iconPreview.getBackground().mutate().setColorFilter(
                custom != null ? custom : ContextCompat.getColor(mActivity, R.color.terminow_host_ssh), PorterDuff.Mode.SRC_IN);
        };
        showIcon.run();
        dialog.findViewById(R.id.terminow_host_icon_row).setOnClickListener(v -> showHostIconPicker(draft, showIcon));
        Runnable showColor = () -> colorValue.setText(draft.iconColor.isEmpty() ?
            mActivity.getString(R.string.terminow_host_color_auto) : draft.iconColor);
        showColor.run();
        dialog.findViewById(R.id.terminow_host_color_row).setOnClickListener(v -> showHostColorPicker(draft, () -> {
            showColor.run();
            showIcon.run();
        }));
        dialog.findViewById(R.id.terminow_host_charset_row).setOnClickListener(v ->
            mActivity.showToast(mActivity.getString(R.string.terminow_host_charset_note), true));

        dialog.findViewById(R.id.terminow_host_close).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.terminow_host_save).setOnClickListener(v -> {
            String address = addressView.getText().toString().trim();
            String username = usernameView.getText().toString().trim();
            boolean rdp = TerminowStore.Host.PROTOCOL_RDP.equals(protocol[0]);
            boolean winrm = TerminowStore.Host.PROTOCOL_WINRM.equals(protocol[0]);
            boolean windows = rdp || winrm;
            String keyPath = windows ? "" : keyView.getText().toString().trim();
            int port = parsePort(portView.getText().toString(), rdp ? 3389 : winrm ? (winrmHttpsView.isChecked() ? 5986 : 5985) : 22);
            int telnetPort = parsePort(telnetPortView.getText().toString(), 23);
            String domain = domainView.getText().toString().trim();
            if (windows && !TerminowStore.isValidDomain(domain)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_domain), true);
                domainView.requestFocus();
                return;
            }

            if (!TerminowStore.isValidAddress(address)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_address), true);
                addressView.requestFocus();
                return;
            }
            if (!TerminowStore.isValidPort(port)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_port), true);
                portView.requestFocus();
                return;
            }
            if (!TerminowStore.isValidUsername(username)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_username), true);
                usernameView.requestFocus();
                return;
            }
            if (!TerminowStore.isValidKeyPath(keyPath)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_key), true);
                keyView.requestFocus();
                return;
            }
            if (!windows && !sshView.isChecked() && !telnetView.isChecked()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_no_protocol), true);
                return;
            }

            TerminowStore.Host host = new TerminowStore.Host();
            // An edited host keeps its stable id and AI access, which are not shown in this form
            if (existingHost != null) TerminowStore.copyIdentity(host, existingHost);
            host.label = labelView.getText().toString().trim();
            host.address = address;
            host.port = port;
            host.username = username;
            host.password = passwordView.getText().toString();
            host.keyPath = keyPath;
            host.group = groupView.getText().toString();
            host.startupSnippet = snippetView.getText().toString();
            host.telnet = telnetView.isChecked();
            host.mosh = !host.telnet && moshView.isChecked();
            host.moshServer = host.mosh ? moshServerView.getText().toString().trim().replaceAll("\\s+", " ") : "";
            if (host.mosh && TerminowStore.parseMoshServer(host.moshServer) == null) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_mosh_server), true);
                moshServerView.requestFocus();
                return;
            }
            host.telnetPort = telnetPort;
            host.tags.addAll(draft.tags);
            host.backspaceSendsCtrlH = backspaceView.isChecked();
            host.agentForwarding = !host.telnet && agentView.isChecked();
            host.remoteTmux = !host.telnet && remoteTmuxView.isChecked();
            host.jumpHost = host.telnet ? "" : draft.jumpHost;
            host.proxyType = host.telnet ? "" : draft.proxyType;
            host.proxyHost = host.proxyType.isEmpty() ? "" : draft.proxyHost;
            host.proxyPort = host.proxyType.isEmpty() ? 0 : draft.proxyPort;
            host.colorScheme = draft.colorScheme;
            host.icon = draft.icon;
            host.iconColor = draft.iconColor;
            host.identity = host.telnet ? "" : draft.identity;
            host.protocol = protocol[0];
            host.domain = windows ? domain : "";
            if (windows) {
                // What only SSH has is not kept for a Windows host
                host.telnet = false;
                host.mosh = false;
                host.moshServer = "";
                host.agentForwarding = false;
                host.remoteTmux = false;
                host.startupSnippet = "";
                host.proxyType = "";
                host.proxyHost = "";
                host.proxyPort = 0;
                host.colorScheme = "";
                if (winrm) host.jumpHost = "";
                host.rdpNla = rdpNlaView.isChecked();
                host.rdpClipboard = rdp && rdpClipboardView.isChecked();
                host.rdpWidth = rdp ? rdpSize[0] : 0;
                host.rdpHeight = rdp ? rdpSize[1] : 0;
                host.winrmHttps = winrmHttpsView.isChecked();
                host.winrmVerify = winrmVerifyView.isChecked();
                if (rdp && !host.jumpHost.isEmpty()) {
                    TerminowStore.Host jump = TerminowStore.findHost(mStore.getHostsIn(scope[0]), host.jumpHost);
                    if (jump == null || !jump.isTerminal() || jump.telnet) {
                        mActivity.showToast(mActivity.getString(R.string.terminow_host_rdp_jump_ssh), true);
                        return;
                    }
                }
            }
            if (!windows && !readEnvRows(envList, host.env)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_env), true);
                return;
            }
            if (!TerminowStore.isValidPort(host.telnetPort)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_port), true);
                telnetPortView.requestFocus();
                return;
            }
            Integer problem = host.validateOptions(mStore.getHostsIn(scope[0]));
            if (problem != null) {
                mActivity.showToast(mActivity.getString(problem), true);
                return;
            }

            // Saved in the chosen vault, and moved out of the one it was in. An edited host keeps its place.
            int index = existingHost != null ? mHosts.indexOf(existingHost) : -1;
            if (index >= 0) mHosts.remove(index);
            if (scope[0].equals(currentScope)) {
                mHosts.add(index >= 0 ? index : mHosts.size(), host);
                mStore.setHosts(mHosts);
            } else {
                if (existingHost != null) mStore.setHosts(mHosts);
                List<TerminowStore.Host> target = mStore.getHostsIn(scope[0]);
                target.add(host);
                mStore.setHostsIn(scope[0], target);
                mActivity.showToast(mActivity.getString(R.string.terminow_host_saved_in_vault, vaultView.getText()), false);
            }
            updateItems();
            dialog.dismiss();
            if (onSaved != null)
                onSaved.onHostSaved(host);
        });

        dialog.show();
        if (existingHost == null)
            labelView.requestFocus();
    }

    private static int parsePort(@NonNull String text, int defaultPort) {
        text = text.trim();
        if (text.isEmpty()) return defaultPort;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void openUrl(@NonNull String url) {
        try {
            mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            mActivity.showToast(url, true);
        }
    }

    /** Edit the tags of a host, separated by commas. */
    private void showTagsEditor(@NonNull List<String> tags, @NonNull Runnable onChanged) {
        EditText field = new EditText(mActivity);
        field.setText(TextUtils.join(", ", tags));
        field.setHint(R.string.terminow_host_tags_hint);
        field.setSingleLine(true);
        field.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
        field.setHintTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        LinearLayout form = new LinearLayout(mActivity);
        form.setPadding(dp(22), dp(8), dp(22), 0);
        form.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_tags)
            .setView(form)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> edited = new ArrayList<>();
            for (String part : field.getText().toString().split(",")) {
                String tag = part.trim();
                if (tag.isEmpty() || edited.contains(tag)) continue;
                if (!TerminowStore.isValidTag(tag)) {
                    field.setError(mActivity.getString(R.string.terminow_host_invalid_tag));
                    return;
                }
                edited.add(tag);
            }
            tags.clear();
            tags.addAll(edited);
            onChanged.run();
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** Pick the host to connect through, from the other ssh hosts of the vault. */
    private void showJumpHostPicker(@NonNull String scope, @Nullable TerminowStore.Host editing, @NonNull TerminowStore.Host draft,
                                    @NonNull Runnable onChanged) {
        List<TerminowStore.Host> candidates = new ArrayList<>();
        for (TerminowStore.Host host : mStore.getHostsIn(scope))
            if (!host.telnet && (editing == null || !host.getKey().equals(editing.getKey()))) candidates.add(host);
        List<CharSequence> names = new ArrayList<>();
        names.add(mActivity.getString(R.string.terminow_host_chaining_none));
        for (TerminowStore.Host host : candidates) names.add(host.getTitle() + "  ·  " + host.getSummary(false));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_chaining)
            .setItems(names.toArray(new CharSequence[0]), (d, which) -> {
                draft.jumpHost = which == 0 ? "" : candidates.get(which - 1).getKey();
                onChanged.run();
            })
            .show();
    }

    /** Set a SOCKS5 or HTTP proxy for the connection, or none. */
    private void showProxyEditor(@NonNull TerminowStore.Host draft, @NonNull Runnable onChanged) {
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(22), dp(8), dp(22), 0);
        RadioGroup types = new RadioGroup(mActivity);
        String[] values = {"", TerminowStore.Host.PROXY_SOCKS5, TerminowStore.Host.PROXY_HTTP};
        int[] labels = {R.string.terminow_host_proxy_none, R.string.terminow_host_proxy_socks5, R.string.terminow_host_proxy_http};
        RadioButton[] buttons = new RadioButton[values.length];
        for (int i = 0; i < values.length; i++) {
            buttons[i] = new RadioButton(mActivity);
            buttons[i].setId(View.generateViewId());
            buttons[i].setText(labels[i]);
            buttons[i].setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            types.addView(buttons[i]);
            if (values[i].equals(draft.proxyType)) buttons[i].setChecked(true);
        }
        form.addView(types);
        EditText hostField = new EditText(mActivity);
        hostField.setHint(R.string.terminow_host_proxy_host);
        hostField.setSingleLine(true);
        hostField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        hostField.setText(draft.proxyHost);
        EditText portField = new EditText(mActivity);
        portField.setHint(R.string.terminow_host_port);
        portField.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (draft.proxyPort > 0) portField.setText(String.valueOf(draft.proxyPort));
        for (EditText field : new EditText[]{hostField, portField}) {
            field.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            field.setHintTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            form.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView note = new TextView(mActivity);
        note.setText(R.string.terminow_host_proxy_note);
        note.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setPadding(0, dp(8), 0, 0);
        form.addView(note);
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_proxy)
            .setView(form)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String type = "";
            for (int i = 0; i < values.length; i++) if (buttons[i].isChecked()) type = values[i];
            if (type.isEmpty()) {
                draft.proxyType = "";
                draft.proxyHost = "";
                draft.proxyPort = 0;
            } else {
                String proxyHost = hostField.getText().toString().trim();
                int proxyPort = parsePort(portField.getText().toString(), -1);
                if (!TerminowStore.isValidAddress(proxyHost)) {
                    hostField.setError(mActivity.getString(R.string.terminow_host_invalid_address));
                    return;
                }
                if (!TerminowStore.isValidPort(proxyPort)) {
                    portField.setError(mActivity.getString(R.string.terminow_host_invalid_port));
                    return;
                }
                draft.proxyType = type;
                draft.proxyHost = proxyHost;
                draft.proxyPort = proxyPort;
            }
            onChanged.run();
            dialog.dismiss();
        }));
        dialog.show();
    }

    /** Pick the terminal theme of the sessions of a host, or the one of the settings. */
    private void showSchemePicker(@NonNull TerminowStore.Host draft, @NonNull Runnable onChanged) {
        List<CharSequence> names = new ArrayList<>();
        names.add(mActivity.getString(R.string.terminow_host_color_scheme_default));
        names.add(TerminowThemes.DEFAULT_THEME.name);
        for (TerminowThemes.Theme theme : TerminowThemes.THEMES)
            if (!theme.name.equals(TerminowThemes.DEFAULT_THEME.name)) names.add(theme.name);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_color_scheme)
            .setItems(names.toArray(new CharSequence[0]), (d, which) -> {
                draft.colorScheme = which == 0 ? "" : names.get(which).toString();
                onChanged.run();
            })
            .show();
    }

    // Host icon and color customization --------------------------------------------------------

    /** The icon keys a host may store, in picker order; "" means the protocol's default icon. */
    private static final String[] HOST_ICON_KEYS = {
        "server", "desktop", "terminal", "cloud", "folder", "star", "lock", "key", "person", "phone", "github"};

    /** A preset palette for the host icon color; "" means the protocol's default color. */
    private static final String[] HOST_COLORS = {
        "#E53935", "#FB8C00", "#FDD835", "#43A047", "#00897B", "#1E88E5",
        "#3949AB", "#8E24AA", "#D81B60", "#6D4C41", "#607D8B", "#757575"};

    /** The drawable for a host icon key, or {@code fallback} for an unknown or empty key. */
    static int hostIconDrawable(@NonNull String key, int fallback) {
        switch (key) {
            case "server": return R.drawable.ic_terminow_hosts;
            case "desktop": return R.drawable.ic_terminow_desktop;
            case "terminal": return R.drawable.ic_terminow_terminal;
            case "cloud": return R.drawable.ic_terminow_cloud;
            case "folder": return R.drawable.ic_terminow_folder;
            case "star": return R.drawable.ic_terminow_star;
            case "lock": return R.drawable.ic_terminow_lock;
            case "key": return R.drawable.ic_terminow_key;
            case "person": return R.drawable.ic_terminow_person;
            case "phone": return R.drawable.ic_terminow_phone;
            case "github": return R.drawable.ic_terminow_logo_github;
            default: return fallback;
        }
    }

    /** The default list icon of a protocol, used when a host sets no custom icon. */
    private static int protocolIconDrawable(@NonNull String protocol) {
        if (TerminowStore.Host.PROTOCOL_RDP.equals(protocol)) return R.drawable.ic_terminow_desktop;
        if (TerminowStore.Host.PROTOCOL_WINRM.equals(protocol)) return R.drawable.ic_terminow_terminal;
        return R.drawable.ic_terminow_hosts;
    }

    /** A host's custom icon color as an ARGB int, or {@code null} when it has none or it is invalid. */
    static Integer parseHostColor(@NonNull String value) {
        if (value.isEmpty()) return null;
        try {
            return android.graphics.Color.parseColor(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void showHostIconPicker(@NonNull TerminowStore.Host draft, @NonNull Runnable onChanged) {
        List<String> keys = new ArrayList<>();
        keys.add("");
        keys.addAll(java.util.Arrays.asList(HOST_ICON_KEYS));
        Integer custom = parseHostColor(draft.iconColor);
        int tint = custom != null ? custom : ContextCompat.getColor(mActivity, R.color.terminow_host_ssh);
        android.widget.GridLayout grid = new android.widget.GridLayout(mActivity);
        grid.setColumnCount(5);
        int pad = dp(16);
        grid.setPadding(pad, pad, pad, pad);
        AlertDialog[] ref = new AlertDialog[1];
        for (String key : keys) {
            ImageView tile = new ImageView(mActivity);
            tile.setBackgroundResource(R.drawable.terminow_host_icon);
            tile.getBackground().mutate().setColorFilter(tint, PorterDuff.Mode.SRC_IN);
            tile.setImageResource(hostIconDrawable(key, R.drawable.ic_terminow_hosts));
            int p = dp(9);
            tile.setPadding(p, p, p, p);
            tile.setAlpha(key.equals(draft.icon) ? 1f : 0.55f);
            int size = dp(46), m = dp(6);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(m, m, m, m);
            tile.setLayoutParams(lp);
            tile.setOnClickListener(v -> {
                draft.icon = key;
                onChanged.run();
                if (ref[0] != null) ref[0].dismiss();
            });
            grid.addView(tile);
        }
        ref[0] = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_icon)
            .setView(wrapCenter(grid))
            .setNeutralButton(R.string.terminow_host_icon_default, (d, w) -> { draft.icon = ""; onChanged.run(); })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showHostColorPicker(@NonNull TerminowStore.Host draft, @NonNull Runnable onChanged) {
        List<String> values = new ArrayList<>();
        values.add("");
        values.addAll(java.util.Arrays.asList(HOST_COLORS));
        android.widget.GridLayout grid = new android.widget.GridLayout(mActivity);
        grid.setColumnCount(6);
        int pad = dp(16);
        grid.setPadding(pad, pad, pad, pad);
        AlertDialog[] ref = new AlertDialog[1];
        for (String value : values) {
            ImageView dot = new ImageView(mActivity);
            android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
            shape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            Integer color = parseHostColor(value);
            if (color != null) {
                shape.setColor(color);
            } else {
                shape.setColor(android.graphics.Color.TRANSPARENT);
                shape.setStroke(dp(2), ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            }
            if (value.equals(draft.iconColor))
                shape.setStroke(dp(3), ContextCompat.getColor(mActivity, R.color.terminow_text));
            dot.setBackground(shape);
            int size = dp(44), m = dp(7);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            lp.setMargins(m, m, m, m);
            dot.setLayoutParams(lp);
            dot.setOnClickListener(v -> {
                draft.iconColor = value;
                onChanged.run();
                if (ref[0] != null) ref[0].dismiss();
            });
            grid.addView(dot);
        }
        ref[0] = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_color)
            .setView(wrapCenter(grid))
            .setNeutralButton(R.string.terminow_host_color_auto, (d, w) -> { draft.iconColor = ""; onChanged.run(); })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Center a picker grid inside its dialog. */
    @NonNull
    private View wrapCenter(@NonNull View child) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(mActivity);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        frame.addView(child, lp);
        return frame;
    }

    /** A row of an environment variable: its name, its value and a button to remove it. */
    private void addEnvRow(@NonNull LinearLayout list, @NonNull String name, @NonNull String value) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText nameField = new EditText(mActivity);
        nameField.setHint(R.string.terminow_host_env_name);
        nameField.setText(name);
        EditText valueField = new EditText(mActivity);
        valueField.setHint(R.string.terminow_host_env_value);
        valueField.setText(value);
        for (EditText field : new EditText[]{nameField, valueField}) {
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            field.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            field.setHintTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        }
        row.addView(nameField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        valueParams.setMarginStart(dp(8));
        row.addView(valueField, valueParams);
        ImageButton remove = new ImageButton(mActivity);
        remove.setImageResource(R.drawable.ic_terminow_close);
        remove.setColorFilter(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        remove.setBackground(null);
        remove.setContentDescription(mActivity.getString(R.string.terminow_host_env_remove));
        remove.setOnClickListener(v -> list.removeView(row));
        row.addView(remove, new LinearLayout.LayoutParams(dp(36), dp(36)));
        list.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (name.isEmpty()) nameField.requestFocus();
    }

    /** Read the rows of the environment variables, empty ones are skipped. Returns {@code false} if one is invalid. */
    private boolean readEnvRows(@NonNull LinearLayout list, @NonNull Map<String, String> env) {
        for (int i = 0; i < list.getChildCount(); i++) {
            LinearLayout row = (LinearLayout) list.getChildAt(i);
            String name = ((EditText) row.getChildAt(0)).getText().toString().trim();
            String value = ((EditText) row.getChildAt(1)).getText().toString();
            if (name.isEmpty() && value.isEmpty()) continue;
            if (!TerminowStore.isValidEnvName(name) || !TerminowStore.isValidEnvValue(value) || env.containsKey(name)) {
                ((EditText) row.getChildAt(0)).setError(mActivity.getString(R.string.terminow_host_invalid_env));
                return false;
            }
            env.put(name, value);
        }
        return true;
    }

    /**
     * Show the keys of the keychain to pick the key of a host from, with the options to add a key,
     * which is then picked.
     */
    private void showKeyPicker(@NonNull EditText keyView) {
        List<KeyItem> keys = new ArrayList<>();
        for (KeyItem key : getKeys()) {
            if (TerminowStore.isValidKeyPath(key.getPath()))
                keys.add(key);
        }

        List<CharSequence> names = new ArrayList<>();
        for (KeyItem key : keys) {
            String type = getKeyType(key);
            names.add(key.getTitle() + (type != null ? " (" + type + ")" : ""));
        }
        names.add(mActivity.getString(R.string.terminow_keypanel_new_item));
        names.add(mActivity.getString(R.string.terminow_key_import));
        names.add(mActivity.getString(R.string.terminow_key_generate));

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(keys.isEmpty() ? R.string.terminow_host_no_keys : R.string.terminow_host_keys_title)
            .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which < keys.size()) {
                    keyView.setText(keys.get(which).getPath());
                    return;
                }
                mKeyTarget = new WeakReference<>(keyView);
                int action = which - keys.size();
                if (action == 0) showNewKeyPanel(null, null);
                else if (action == 1) pickKeyFile();
                else showGenerateKeyPanel();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Fill the key field of the host form that asked for a new key, if there is one. */
    private void setKeyTarget(@NonNull String name) {
        EditText keyView = mKeyTarget != null ? mKeyTarget.get() : null;
        mKeyTarget = null;
        if (keyView != null && keyView.isAttachedToWindow())
            keyView.setText("~/.ssh/" + name);
    }

    /** An item of the hosts page for a group of hosts. */
    private static class Group {
        final String name;

        Group(@NonNull String name) {
            this.name = name;
        }
    }

    /** Show a dialog to add a group, or to rename an existing one. */
    private void showGroupDialog(@Nullable String existingGroup) {
        LinearLayout form = createForm();
        EditText nameView = addField(form, R.string.terminow_group_name, InputType.TYPE_CLASS_TEXT);
        if (existingGroup != null)
            nameView.setText(existingGroup);

        showFormDialog(existingGroup == null ? R.string.terminow_new_group : R.string.terminow_rename_group, form, () -> {
            String name = nameView.getText().toString().trim();
            if (name.equals(existingGroup)) return true;
            if (name.isEmpty() || mGroups.contains(name)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_group_invalid), true);
                return false;
            }

            if (existingGroup == null) {
                mGroups.add(name);
            } else {
                mGroups.set(mGroups.indexOf(existingGroup), name);
                for (TerminowStore.Host host : mHosts)
                    if (existingGroup.equals(host.group)) host.group = name;
                mStore.setHosts(mHosts);
            }
            mStore.setGroups(mGroups);
            updateItems();
            return true;
        });
    }

    /** Show the groups to pick the group of a host from in the host form. */
    private void showStartupSnippetPicker(@NonNull EditText snippetView) {
        List<TerminowStore.Snippet> snippets = new ArrayList<>();
        for (TerminowStore.Snippet snippet : mStore.getSnippets()) {
            // A snippet is found again by its name when the host connects
            if (!snippet.name.isEmpty()) snippets.add(snippet);
        }
        if (snippets.isEmpty()) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setMessage(R.string.terminow_host_snippet_none_yet)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        List<CharSequence> names = new ArrayList<>();
        names.add(mActivity.getString(R.string.terminow_host_snippet_none));
        for (TerminowStore.Snippet snippet : snippets)
            names.add(snippet.name + "\n" + snippet.command);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_snippet)
            .setItems(names.toArray(new CharSequence[0]), (dialog, which) ->
                snippetView.setText(which == 0 ? "" : snippets.get(which - 1).name))
            .show();
    }

    private void showGroupPicker(@NonNull EditText groupView) {
        List<String> names = new ArrayList<>();
        names.add(mActivity.getString(R.string.terminow_host_group_none));
        names.addAll(mGroups);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_group)
            .setItems(names.toArray(new CharSequence[0]), (dialog, which) ->
                groupView.setText(which == 0 ? "" : mGroups.get(which - 1)))
            .show();
    }

    /** Ask to import the hosts of the ssh config file of the home directory. */
    private void showImportDialog() {
        final File configFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh/config");
        List<CharSequence> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (configFile.isFile()) {
            items.add(mActivity.getString(R.string.terminow_import_from_config));
            actions.add(() -> {
                try (java.io.Reader in = new java.io.InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                    StringBuilder text = new StringBuilder();
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) text.append(buffer, 0, read);
                    previewImport(text.toString());
                } catch (IOException e) {
                    mActivity.showToast(String.valueOf(e.getMessage()), true);
                }
            });
        }
        items.add(mActivity.getString(R.string.terminow_import_from_file));
        actions.add(() -> pickImportFile(false));
        items.add(mActivity.getString(R.string.terminow_import_termius));
        actions.add(this::showTermiusImport);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_import_title)
            .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> actions.get(which).run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Whether the file that is picked is for the import from Termius, which then asks for the keys. */
    private boolean mImportFromTermius;

    /**
     * Move hosts and keys from Termius, which can not export them: the hosts from a file, then
     * each key pasted from the Keychain of Termius and given to the hosts that name it.
     */
    private void showTermiusImport() {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_import_termius)
            .setMessage(R.string.terminow_termius_message)
            .setPositiveButton(R.string.terminow_termius_hosts, (d, w) -> pickImportFile(true))
            .setNeutralButton(R.string.terminow_termius_keys, (d, w) -> showTermiusKey(null, new ArrayList<>(), new java.util.IdentityHashMap<>()))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Paste one key copied from the Keychain of Termius. With {@code wanted}, the key that
     * imported hosts name; the next ones of {@code rest} are asked for after it.
     */
    private void showTermiusKey(@Nullable String wanted, @NonNull List<String> rest, @NonNull Map<TerminowStore.Host, String> waiting) {
        LinearLayout form = createForm();
        int users = 0;
        if (wanted != null) for (String name : waiting.values()) if (name.equals(wanted)) users++;
        TextView note = new TextView(mActivity);
        note.setText(wanted != null ? mActivity.getResources().getQuantityString(R.plurals.terminow_termius_key_for, users, wanted, users) :
            mActivity.getString(R.string.terminow_termius_key_any));
        note.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        note.setPadding(0, 0, 0, dp(8));
        form.addView(note);
        EditText labelView = addField(form, R.string.terminow_termius_key_label, InputType.TYPE_CLASS_TEXT);
        EditText contentView = addField(form, R.string.terminow_key_content,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        contentView.setMaxLines(6);
        contentView.setTextSize(12);
        if (wanted != null) labelView.setText(wanted);
        TextView paste = new TextView(mActivity);
        paste.setText(R.string.terminow_termius_paste);
        paste.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_panel_accent));
        paste.setMinHeight(dp(48));
        paste.setGravity(Gravity.CENTER_VERTICAL);
        Runnable fill = () -> {
            String text = clipboardKey();
            if (text != null) contentView.setText(text);
            else mActivity.showToast(mActivity.getString(R.string.terminow_termius_no_key_copied), false);
        };
        paste.setOnClickListener(v -> fill.run());
        form.addView(paste);
        String copied = clipboardKey();
        if (copied != null) contentView.setText(copied);

        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_termius_key_title)
            .setView(form)
            .setPositiveButton(R.string.terminow_termius_key_save, null)
            .setNegativeButton(R.string.terminow_termius_done, null);
        if (wanted != null) builder.setNeutralButton(R.string.terminow_termius_skip, null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String label = labelView.getText().toString().trim().replaceAll("\\p{Cntrl}", " ");
                if (label.length() > 100) label = label.substring(0, 100);
                String name = getNewKeyName(label.isEmpty() ? "termius_key" : label);
                if (!isValidNewKeyName(name)) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                    return;
                }
                if (!saveKey(name, contentView.getText().toString())) return;
                if (!label.isEmpty() && !label.equals(name)) mStore.setKeyLabel(name, label);
                // The hosts that named this key log in with it now
                int given = 0;
                for (Map.Entry<TerminowStore.Host, String> entry : waiting.entrySet()) {
                    if (!entry.getValue().equalsIgnoreCase(wanted != null ? wanted : label)) continue;
                    entry.getKey().keyPath = "~/.ssh/" + name;
                    given++;
                }
                if (given > 0) {
                    mStore.setHosts(mHosts);
                    updateItems();
                    mActivity.showToast(mActivity.getResources().getQuantityString(R.plurals.terminow_termius_key_given, given, name, given), true);
                }
                dialog.dismiss();
                nextTermiusKey(wanted, rest, waiting);
            });
            if (wanted != null)
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    dialog.dismiss();
                    nextTermiusKey(wanted, rest, waiting);
                });
        });
        dialog.show();
        // Copying a key in Termius and coming back fills it in, without tapping Paste
        form.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus && contentView.getText().toString().trim().isEmpty()) {
                String text = clipboardKey();
                if (text != null) contentView.setText(text);
            }
        });
    }

    /** The next key that imported hosts wait for, or the end with what is still missing. */
    private void nextTermiusKey(@Nullable String done, @NonNull List<String> rest, @NonNull Map<TerminowStore.Host, String> waiting) {
        if (done != null) {
            List<TerminowStore.Host> given = new ArrayList<>();
            for (Map.Entry<TerminowStore.Host, String> entry : waiting.entrySet())
                if (!entry.getKey().keyPath.isEmpty()) given.add(entry.getKey());
            for (TerminowStore.Host host : given) waiting.remove(host);
        }
        if (!rest.isEmpty()) {
            String next = rest.remove(0);
            showTermiusKey(next, rest, waiting);
            return;
        }
        if (done == null) {
            // Keys without hosts that wait for them: one after the other, until Done
            showTermiusKey(null, rest, waiting);
            return;
        }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>(waiting.values());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_import_termius)
            .setMessage(missing.isEmpty() ? mActivity.getString(R.string.terminow_termius_all_done) :
                mActivity.getString(R.string.terminow_termius_still_missing, TextUtils.join(", ", missing)))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.terminow_termius_more_keys, (d, w) -> showTermiusKey(null, new ArrayList<>(), waiting))
            .show();
    }

    /** A private key on the clipboard, like one copied in Termius, {@code null} if there is none. */
    @Nullable
    private String clipboardKey() {
        ClipboardManager clipboardManager = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboardManager != null ? clipboardManager.getPrimaryClip() : null;
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(mActivity);
        if (text == null) return null;
        String value = text.toString().trim();
        return (value.startsWith("-----BEGIN ") && value.contains("PRIVATE KEY-----")) || TerminowPpk.isPpk(value) ? value : null;
    }

    /** Pick an ssh config or a CSV of hosts anywhere on the phone. */
    private void pickImportFile(boolean termius) {
        mImportFromTermius = termius;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            mActivity.startActivityForResult(intent, REQUEST_IMPORT_HOSTS);
        } catch (Exception e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
        }
    }

    /** Read a picked file of hosts, at most 2 MB. */
    private void readImportFile(@NonNull Uri uri) {
        new Thread(() -> {
            String text = null;
            String error = null;
            try (InputStream in = mActivity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Failed to open the file");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > 2 * 1024 * 1024) throw new IOException("The file is too large to be a list of hosts");
                }
                text = new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            String finalText = text;
            String finalError = error;
            mActivity.runOnUiThread(() -> {
                if (finalText != null) previewImport(finalText);
                else mActivity.showToast(mActivity.getString(R.string.terminow_import_failed, finalError), true);
            });
        }, "TerminowImportHosts").start();
    }

    /** What would be imported, before anything is saved: new hosts, ones that are there, and what was left out. */
    private void previewImport(@NonNull String text) {
        TerminowHostImport.Result result;
        try {
            List<KeyItem> keys = getKeys();
            result = TerminowHostImport.read(text, name -> {
                File key = new File(getSshDir(), name);
                if (key.isFile() && !name.endsWith(".pub")) return "~/.ssh/" + name;
                // A key of Termius is named by its label, like the one given when it was pasted
                for (KeyItem item : keys)
                    if (item.label.equalsIgnoreCase(name) && item.biometricId == null && item.fidoId == null) return item.getPath();
                return null;
            });
        } catch (IOException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_import_failed, e.getMessage()), true);
            return;
        }
        List<TerminowStore.Host> fresh = new ArrayList<>();
        int existing = 0;
        for (TerminowStore.Host imported : result.hosts) {
            boolean exists = false;
            for (TerminowStore.Host host : mHosts) exists |= sameHost(host, imported);
            for (TerminowStore.Host host : fresh) exists |= sameHost(host, imported);
            if (exists) existing++;
            else fresh.add(imported);
        }
        StringBuilder message = new StringBuilder(mActivity.getString(result.csv ? R.string.terminow_import_read_csv : R.string.terminow_import_read_config));
        message.append("\n\n").append(mActivity.getResources().getQuantityString(R.plurals.terminow_import_new, fresh.size(), fresh.size()));
        if (existing > 0) message.append("\n").append(mActivity.getResources().getQuantityString(R.plurals.terminow_import_existing, existing, existing));
        if (!result.skipped.isEmpty()) {
            message.append("\n").append(mActivity.getResources().getQuantityString(R.plurals.terminow_import_skipped, result.skipped.size(), result.skipped.size()));
            for (int i = 0; i < Math.min(5, result.skipped.size()); i++) message.append("\n  · ").append(result.skipped.get(i));
            if (result.skipped.size() > 5) message.append("\n  …");
        }
        boolean termius = mImportFromTermius;
        mImportFromTermius = false;
        if (!result.missingKeys.isEmpty())
            message.append("\n\n").append(termius ? mActivity.getString(R.string.terminow_termius_keys_next, TextUtils.join(", ", result.missingKeys)) :
                mActivity.getString(R.string.terminow_import_missing_keys, TextUtils.join(", ", result.missingKeys)));
        if (fresh.isEmpty()) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_import_title)
                .setMessage(message.toString() + "\n\n" + mActivity.getString(R.string.terminow_import_none_new))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_import_title)
            .setMessage(message.toString())
            .setPositiveButton(mActivity.getResources().getQuantityString(R.plurals.terminow_import_button, fresh.size(), fresh.size()),
                (d, w) -> {
                    importHosts(fresh, result.groups);
                    if (!termius) return;
                    // Then the keys that the new hosts name, pasted from the Keychain of Termius
                    Map<TerminowStore.Host, String> waiting = new java.util.IdentityHashMap<>();
                    for (TerminowStore.Host host : fresh) {
                        String key = result.waitingForKey.get(host);
                        if (key != null) waiting.put(host, key);
                    }
                    List<String> names = new ArrayList<>(new java.util.LinkedHashSet<>(waiting.values()));
                    if (names.isEmpty()) showTermiusKey(null, names, waiting);
                    else showTermiusKey(names.remove(0), names, waiting);
                })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private static boolean sameHost(@NonNull TerminowStore.Host a, @NonNull TerminowStore.Host b) {
        return a.address.equals(b.address) && a.port == b.port && a.username.equals(b.username) && a.telnet == b.telnet;
    }

    /** Save new hosts, with their groups; a host without a group goes to the group that is shown. */
    private void importHosts(@NonNull List<TerminowStore.Host> hosts, @NonNull java.util.Set<String> groups) {
        boolean groupsChanged = false;
        for (String group : groups) {
            if (!mGroups.contains(group)) {
                mGroups.add(group);
                groupsChanged = true;
            }
        }
        if (groupsChanged) mStore.setGroups(mGroups);
        for (TerminowStore.Host host : hosts) {
            if (host.group.isEmpty()) host.group = mCurrentGroup != null ? mCurrentGroup : "";
            mHosts.add(host);
        }
        mStore.setHosts(mHosts);
        updateItems();
        mActivity.showToast(mActivity.getResources().getQuantityString(R.plurals.terminow_import_done, hosts.size(), hosts.size()), true);
    }

    /** An item of the keychain page for a private key file in `~/.ssh`. */
    private static class KeyItem {
        final String name;
        final File file;
        final File publicFile;
        /** The name that the user gave the key, empty if none. */
        final String label;
        /** For a biometric key, its id in the keystore; its file is then its public key. */
        @Nullable final String biometricId;
        /** For a FIDO2 security key, its id in the app; its file is then its public key. */
        @Nullable final String fidoId;

        KeyItem(@NonNull File file, @NonNull String label) {
            this.name = file.getName();
            this.file = file;
            this.publicFile = new File(file.getPath() + ".pub");
            this.label = label;
            this.biometricId = null;
            this.fidoId = null;
        }

        /** A biometric or FIDO2 key, by its public key file. */
        KeyItem(@NonNull File publicFile, @NonNull String label, @Nullable String biometricId, @Nullable String fidoId) {
            this.name = publicFile.getName();
            this.file = publicFile;
            this.publicFile = publicFile;
            this.label = label;
            this.biometricId = biometricId;
            this.fidoId = fidoId;
        }

        /** Where ssh finds the certificate of the key: next to the key, with `-cert.pub`. */
        @NonNull
        File certificateFile() {
            return new File(file.getPath() + "-cert.pub");
        }

        /** The certificate of the key, {@code null} if it has none or it can not be read. */
        @Nullable
        TerminowSshWire.Certificate readCertificate() {
            File certificate = certificateFile();
            if (!certificate.isFile() || certificate.length() > CERTIFICATE_MAX_SIZE) return null;
            try {
                TerminowSshWire.PublicKeyLine line = TerminowSshWire.parseLine(new String(java.nio.file.Files.readAllBytes(certificate.toPath()),
                    StandardCharsets.UTF_8));
                return line != null ? TerminowSshWire.parseCertificate(line.blob) : null;
            } catch (IOException e) {
                return null;
            }
        }

        /** The public key blob, {@code null} if there is no public key file. */
        @Nullable
        byte[] readPublicBlob() {
            if (!publicFile.isFile() || publicFile.length() > CERTIFICATE_MAX_SIZE) return null;
            try {
                TerminowSshWire.PublicKeyLine line = TerminowSshWire.parseLine(new String(java.nio.file.Files.readAllBytes(publicFile.toPath()),
                    StandardCharsets.UTF_8));
                return line != null ? line.blob : null;
            } catch (IOException e) {
                return null;
            }
        }

        @NonNull
        String getTitle() {
            return label.isEmpty() ? name : label;
        }

        /** The path of the key as it is entered for a host. */
        @NonNull
        String getPath() {
            return "~/.ssh/" + name;
        }
    }

    /** An item of the known hosts page for a line of `~/.ssh/known_hosts`. */
    private static class KnownHost {
        final String line;
        final String hosts;
        final String keyType;

        KnownHost(@NonNull String line, @NonNull String hosts, @NonNull String keyType) {
            this.line = line;
            this.hosts = hosts;
            this.keyType = keyType;
        }
    }

    @NonNull
    private static File getSshDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh");
    }

    /** Get the private key files in `~/.ssh`. */
    @NonNull
    private List<KeyItem> getKeys() {
        List<KeyItem> keys = new ArrayList<>();
        File[] files = getSshDir().listFiles();
        if (files == null) return keys;

        for (File file : files) {
            String name = file.getName();
            // Skip public keys and the other files that ssh keeps in the directory
            if (!file.isFile() || name.endsWith(".pub") || name.startsWith("known_hosts") ||
                name.equals("config") || name.equals("authorized_keys") || name.equals("environment"))
                continue;
            keys.add(new KeyItem(file, mStore.getKeyLabel(name)));
        }
        // The keys of the keystore that have their public key file
        for (String id : TerminowBiometricKeys.ids()) {
            File publicFile = TerminowBiometricKeys.publicFile(id);
            if (publicFile.isFile()) keys.add(new KeyItem(publicFile, mStore.getKeyLabel(publicFile.getName()), id, null));
        }
        // The FIDO2 security keys
        for (TerminowFidoKeys.Credential credential : TerminowFidoKeys.load()) {
            File publicFile = TerminowFidoKeys.publicFile(credential.id);
            String label = mStore.getKeyLabel(publicFile.getName());
            if (publicFile.isFile()) keys.add(new KeyItem(publicFile, label.isEmpty() ? credential.label : label, null, credential.id));
        }
        Collections.sort(keys, (a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
        return keys;
    }

    @NonNull
    private static List<String> readLines(@NonNull File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null)
                lines.add(line);
        }
        return lines;
    }

    /** Get the first line of a file, {@code null} if it can not be read. */
    @Nullable
    private static String readFirstLine(@NonNull File file) {
        try {
            List<String> lines = readLines(file);
            return lines.isEmpty() ? null : lines.get(0).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /** The hosts that log in with the key. */
    @NonNull
    private List<TerminowStore.Host> getHostsUsingKey(@NonNull KeyItem key) {
        List<TerminowStore.Host> hosts = new ArrayList<>();
        for (TerminowStore.Host host : mHosts) {
            if (host.keyPath.equals(key.getPath()) || host.keyPath.equals(key.file.getAbsolutePath()))
                hosts.add(host);
        }
        return hosts;
    }

    /** Show the hosts that use a key, tapping one opens it to edit. */
    private void showKeyHosts(@NonNull KeyItem key, @NonNull List<TerminowStore.Host> hosts) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog).setTitle(key.getTitle());
        if (hosts.isEmpty()) {
            builder.setMessage(R.string.terminow_key_no_hosts);
        } else {
            CharSequence[] names = new CharSequence[hosts.size()];
            for (int i = 0; i < hosts.size(); i++)
                names[i] = hosts.get(i).getTitle();
            builder.setItems(names, (dialog, which) -> showHostDialog(hosts.get(which)));
        }
        builder.setPositiveButton(android.R.string.ok, null).show();
    }

    /** Get the type of a key to show, like `ED25519` or `RSA`, {@code null} if it is not known. */
    @Nullable
    private static String getKeyType(@NonNull KeyItem key) {
        // The public key starts with its type, like `ssh-ed25519 AAAA...`
        String publicKey = key.publicFile.isFile() ? readFirstLine(key.publicFile) : null;
        String type = publicKey != null ? getKeyTypeForName(publicKey.split("\\s+")[0]) : null;
        if (type != null) return type;

        try {
            List<String> lines = readLines(key.file);
            if (lines.isEmpty()) return null;
            String header = lines.get(0).trim();
            if (header.contains("BEGIN RSA PRIVATE KEY")) return "RSA";
            if (header.contains("BEGIN EC PRIVATE KEY")) return "ECDSA";
            if (header.contains("BEGIN DSA PRIVATE KEY")) return "DSA";
            // A PuTTY key, like `PuTTY-User-Key-File-3: ssh-ed25519`
            if (header.startsWith("PuTTY-User-Key-File-"))
                return getKeyTypeForName(header.substring(header.indexOf(':') + 1).trim());
            if (header.contains("BEGIN OPENSSH PRIVATE KEY")) {
                // The key file starts with the public key and its type, which are not encrypted
                StringBuilder base64 = new StringBuilder();
                for (int i = 1; i < lines.size() && i <= 8 && !lines.get(i).startsWith("-----"); i++)
                    base64.append(lines.get(i).trim());
                // Decode whole groups of 4 characters, since the key is cut off after some lines
                String data = new String(Base64.decode(base64.substring(0, base64.length() - base64.length() % 4), Base64.DEFAULT), StandardCharsets.ISO_8859_1);
                for (String name : new String[]{"sk-ssh-ed25519", "sk-ecdsa-sha2", "ssh-ed25519", "ecdsa-sha2", "ssh-rsa", "ssh-dss"}) {
                    if (data.contains(name)) return getKeyTypeForName(name);
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // The type is shown as not known
        }
        return null;
    }

    @Nullable
    private static String getKeyTypeForName(@NonNull String name) {
        if (name.startsWith("sk-")) return "FIDO2";
        if (name.startsWith("ssh-ed25519")) return "ED25519";
        if (name.startsWith("ssh-rsa") || name.startsWith("rsa-sha2")) return "RSA";
        if (name.startsWith("ecdsa-sha2")) return "ECDSA";
        if (name.startsWith("ssh-dss")) return "DSA";
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Keychain panels, like the New Key and Edit Key panels of Termius

    /** The private key field of the New Key panel that is open, to fill in a picked file. */
    @Nullable private WeakReference<EditText> mKeyPanelArea;
    @Nullable private WeakReference<EditText> mKeyPanelName;

    /**
     * Add a key: a name, and the private key pasted, picked from a file or typed, which is
     * checked as it changes: its type and fingerprint, whether it has a passphrase, or why it can
     * not be used. A PuTTY key is converted; a public key or certificate pasted by mistake says so.
     */
    private void showNewKeyPanel(@Nullable String content, @Nullable String suggestedName) {
        TerminowPanel panel = new TerminowPanel(mActivity, mActivity.getString(R.string.terminow_keypanel_new));
        LinearLayout card = panel.card();
        EditText nameView = panel.field(card, mActivity.getString(R.string.terminow_keypanel_name),
            mActivity.getString(R.string.terminow_keypanel_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText keyView = panel.keyArea(card, mActivity.getString(R.string.terminow_keypanel_private),
            mActivity.getString(R.string.terminow_keypanel_private_hint));
        TextView status = panel.status(card);
        LinearLayout sources = panel.linkRow(card);
        panel.link(sources, R.drawable.ic_terminow_paste, mActivity.getString(R.string.terminow_keypanel_paste), () -> {
            String copied = clipboardText();
            if (copied == null) mActivity.showToast(mActivity.getString(R.string.terminow_keypanel_clipboard_empty), false);
            else keyView.setText(copied.trim());
        });
        TextView fromFile = panel.link(sources, R.drawable.ic_terminow_import, mActivity.getString(R.string.terminow_keypanel_file), this::pickKeyFile);
        ((LinearLayout.LayoutParams) fromFile.getLayoutParams()).setMarginStart(panel.dp(24));

        // For an encrypted key: a PuTTY key is converted with it, others keep it and it is typed in when ssh asks
        EditText passphraseView = panel.field(card, mActivity.getString(R.string.terminow_key_passphrase), "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        View passphraseLabel = card.getChildAt(card.indexOfChild(passphraseView) - 1);

        // What the key is, once it is one
        LinearLayout summary = panel.content();
        LinearLayout preview = new LinearLayout(mActivity);
        preview.setOrientation(LinearLayout.VERTICAL);
        summary.addView(preview);

        LinearLayout more = panel.card();
        panel.link(more, R.drawable.ic_terminow_key, mActivity.getString(R.string.terminow_keypanel_generate), () -> {
            panel.dismiss();
            showGenerateKeyPanel();
        });
        TextView note = panel.text(mActivity.getString(R.string.terminow_keypanel_note), 13, R.color.terminow_text_secondary);
        more.addView(note);

        final TerminowKeyFormat.Info[] info = {TerminowKeyFormat.analyze("")};
        final KeyItem[] certificateKey = new KeyItem[1];
        Runnable check = () -> {
            info[0] = TerminowKeyFormat.analyze(keyView.getText().toString());
            TerminowKeyFormat.Info i = info[0];
            boolean askPassphrase = i.encrypted && i.isPrivateKey();
            passphraseView.setVisibility(askPassphrase ? View.VISIBLE : View.GONE);
            passphraseLabel.setVisibility(askPassphrase ? View.VISIBLE : View.GONE);
            preview.removeAllViews();
            switch (i.kind) {
                case EMPTY:
                    panel.setStatus(status, "", R.color.terminow_text_secondary);
                    break;
                case PUBLIC_KEY:
                    panel.setStatus(status, mActivity.getString(R.string.terminow_keypanel_is_public), R.color.terminow_host_ssh);
                    break;
                case CERTIFICATE: {
                    // A certificate is added to the key that it certifies, like from Edit Key
                    certificateKey[0] = null;
                    TerminowSshWire.PublicKeyLine line = TerminowSshWire.parseLine(keyView.getText().toString().trim());
                    TerminowSshWire.Certificate certificate = line != null ? TerminowSshWire.parseCertificate(line.blob) : null;
                    if (certificate != null)
                        for (KeyItem item : getKeys())
                            if (Arrays.equals(item.readPublicBlob(), certificate.keyBlob)) certificateKey[0] = item;
                    if (certificateKey[0] != null)
                        panel.setStatus(status, mActivity.getString(R.string.terminow_keypanel_is_certificate, certificateKey[0].getTitle()), R.color.terminow_panel_accent);
                    else
                        panel.setStatus(status, mActivity.getString(R.string.terminow_keypanel_certificate_no_key), R.color.terminow_host_ssh);
                    break;
                }
                case UNKNOWN:
                    panel.setStatus(status, mActivity.getString(R.string.terminow_keypanel_unknown), R.color.terminow_host_ssh);
                    break;
                default: {
                    String type = i.type.isEmpty() ? mActivity.getString(R.string.terminow_keypanel_type_unknown) : i.type;
                    String what = mActivity.getString(i.kind == TerminowKeyFormat.Kind.PPK ? R.string.terminow_keypanel_ok_ppk :
                        R.string.terminow_keypanel_ok, type);
                    if (i.encrypted && i.kind != TerminowKeyFormat.Kind.PPK) what += "\n" + mActivity.getString(R.string.terminow_keypanel_encrypted);
                    panel.setStatus(status, what, R.color.terminow_panel_accent);
                    if (i.publicBlob != null)
                        panel.row(preview, R.drawable.ic_terminow_key, type, TerminowKeyFormat.fingerprint(i.publicBlob), null);
                }
            }
            panel.setSaveEnabled(i.isPrivateKey() || (i.kind == TerminowKeyFormat.Kind.CERTIFICATE && certificateKey[0] != null));
        };
        keyView.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) { check.run(); }
        });

        if (content != null) keyView.setText(content.trim());
        else {
            // A key that was copied already is filled in, like in Termius
            String copied = clipboardKey();
            if (copied != null) keyView.setText(copied);
        }
        if (suggestedName != null) nameView.setText(suggestedName);
        check.run();

        panel.onSave(() -> {
            TerminowKeyFormat.Info i = info[0];
            if (i.kind == TerminowKeyFormat.Kind.CERTIFICATE && certificateKey[0] != null)
                return saveCertificate(certificateKey[0], keyView.getText().toString());
            if (!i.isPrivateKey()) return false;
            String label = nameView.getText().toString().trim().replaceAll("\\p{Cntrl}", " ");
            if (label.length() > 100) label = label.substring(0, 100);
            String base = !label.isEmpty() ? label : "id_" + (i.type.isEmpty() ? "key" : i.type.toLowerCase(Locale.ROOT));
            String name = getNewKeyName(base);
            if (!isValidNewKeyName(name)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                return false;
            }
            if (!label.isEmpty() && !label.equals(name)) mStore.setKeyLabel(name, label);
            String text = keyView.getText().toString();
            if (i.kind == TerminowKeyFormat.Kind.PPK) {
                String passphrase = passphraseView.getText().toString();
                if (i.encrypted && passphrase.isEmpty()) {
                    passphraseView.setError(mActivity.getString(R.string.terminow_key_passphrase_needed));
                    return false;
                }
                convertPpk(name, text.replace("\r\n", "\n").trim() + "\n", i.encrypted ? passphrase : null);
                return true;
            }
            String publicLine = i.publicBlob != null ? TerminowSshWire.publicKeyLine(i.publicBlob, label.isEmpty() ? name : label) + "\n" : null;
            if (!saveKey(name, text, publicLine)) return false;
            String passphrase = i.encrypted ? passphraseView.getText().toString() : "";
            if (!passphrase.isEmpty()) mStore.setKeyPassphrase(name, passphrase);
            // A key whose public part is not in it, like an EC key in PEM: ssh-keygen makes it
            if (publicLine == null && (!i.encrypted || !passphrase.isEmpty())) derivePublicKey(name, passphrase);
            return true;
        });
        panel.getDialog().setOnDismissListener(d -> {
            mKeyPanelArea = null;
            mKeyPanelName = null;
        });
        mKeyPanelArea = new WeakReference<>(keyView);
        mKeyPanelName = new WeakReference<>(nameView);
        panel.show();
    }

    /**
     * A key or certificate from the clipboard, like Paste SSH key in Termius: shown in the New
     * Key panel, which says what it is (ED25519, RSA, ECDSA, PuTTY, a certificate) before it is saved.
     */
    private void pasteKey() {
        String copied = clipboardText();
        if (copied == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_keypanel_clipboard_empty), false);
            return;
        }
        showNewKeyPanel(copied, null);
    }

    /** Any text on the clipboard, {@code null} if there is none. */
    @Nullable
    private String clipboardText() {
        ClipboardManager clipboardManager = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboardManager != null ? clipboardManager.getPrimaryClip() : null;
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(mActivity);
        return text == null || text.toString().trim().isEmpty() ? null : text.toString();
    }

    /**
     * Make the public key file of a key with ssh-keygen, in the background, for a key whose
     * public part is not in the private key file: with the passphrase if it has one.
     */
    private void derivePublicKey(@NonNull String name, @NonNull String passphrase) {
        File key = new File(getSshDir(), name);
        File publicFile = new File(getSshDir(), name + ".pub");
        new Thread(() -> {
            String error = null;
            String line = null;
            try {
                Process process = TerminowClaudeSetup.startInTerminal(mActivity, Arrays.asList("ssh-keygen", "-y", "-P", passphrase, "-f", key.getPath()),
                    Collections.emptyMap(), getSshDir());
                process.getOutputStream().close();
                String out = readAll(process.getInputStream());
                String err = readAll(process.getErrorStream());
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroy();
                    error = "ssh-keygen did not finish";
                } else if (process.exitValue() != 0 || TerminowSshWire.parseLine(out) == null) {
                    error = err.trim().isEmpty() ? "ssh-keygen failed" : err.trim();
                } else {
                    line = out.trim() + "\n";
                }
            } catch (IOException | InterruptedException e) {
                error = String.valueOf(e.getMessage());
            }
            String finalLine = line;
            String finalError = error;
            mActivity.runOnUiThread(() -> {
                if (finalLine != null) {
                    try (FileOutputStream out = new FileOutputStream(publicFile)) {
                        out.write(finalLine.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        mActivity.showToast(String.valueOf(e.getMessage()), true);
                        return;
                    }
                    updateItems();
                } else if (!passphrase.isEmpty()) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_keypanel_public_failed, finalError), true);
                }
            });
        }, "TerminowPublicKey").start();
    }

    @NonNull
    private static String readAll(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > KEY_FILE_MAX_SIZE) break;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Generate a key with ssh-keygen in the background: its type, name and an optional passphrase. */
    private void showGenerateKeyPanel() {
        TerminowPanel panel = new TerminowPanel(mActivity, mActivity.getString(R.string.terminow_keypanel_generate_title));
        LinearLayout card = panel.card();
        EditText nameView = panel.field(card, mActivity.getString(R.string.terminow_keypanel_name),
            mActivity.getString(R.string.terminow_keypanel_name_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        panel.label(card, mActivity.getString(R.string.terminow_keypanel_type));
        android.widget.RadioGroup types = new android.widget.RadioGroup(mActivity);
        String[][] choices = {{"ed25519", "ED25519", mActivity.getString(R.string.terminow_keypanel_type_ed25519)},
            {"rsa", "RSA 4096", mActivity.getString(R.string.terminow_keypanel_type_rsa)},
            {"ecdsa", "ECDSA P-256", mActivity.getString(R.string.terminow_keypanel_type_ecdsa)}};
        for (int i = 0; i < choices.length; i++) {
            android.widget.RadioButton button = new android.widget.RadioButton(mActivity);
            button.setId(View.generateViewId());
            button.setTag(choices[i][0]);
            button.setText(choices[i][1] + "  ·  " + choices[i][2]);
            button.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            button.setButtonTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(mActivity, R.color.terminow_panel_accent)));
            types.addView(button);
            if (i == 0) button.setChecked(true);
        }
        card.addView(types);
        EditText passphraseView = panel.field(card, mActivity.getString(R.string.terminow_keypanel_passphrase_optional), "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText againView = panel.field(card, mActivity.getString(R.string.terminow_keypanel_passphrase_again), "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(panel.text(mActivity.getString(R.string.terminow_keypanel_generate_note), 13, R.color.terminow_text_secondary));
        panel.onSave(() -> {
            String passphrase = passphraseView.getText().toString();
            if (!passphrase.equals(againView.getText().toString())) {
                againView.setError(mActivity.getString(R.string.terminow_password_mismatch));
                return false;
            }
            if (!passphrase.isEmpty() && passphrase.length() < 5) {
                passphraseView.setError(mActivity.getString(R.string.terminow_keypanel_passphrase_short));
                return false;
            }
            View checked = types.findViewById(types.getCheckedRadioButtonId());
            String type = checked != null ? String.valueOf(checked.getTag()) : "ed25519";
            String label = nameView.getText().toString().trim().replaceAll("\\p{Cntrl}", " ");
            String name = getNewKeyName(label.isEmpty() ? "id_" + type : label);
            if (!isValidNewKeyName(name)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                return false;
            }
            generateKey(name, label, type, passphrase);
            return true;
        });
        panel.show();
    }

    private void generateKey(@NonNull String name, @NonNull String label, @NonNull String type, @NonNull String passphrase) {
        File directory = getSshDir();
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Failed to create \"" + directory + "\"");
            Os.chmod(directory.getPath(), 0700);
        } catch (IOException | ErrnoException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
            return;
        }
        List<String> command = new ArrayList<>(Arrays.asList("ssh-keygen", "-q", "-t", type, "-N", passphrase,
            "-C", label.isEmpty() ? name : label, "-f", new File(directory, name).getPath()));
        if (type.equals("rsa")) command.addAll(Arrays.asList("-b", "4096"));
        if (type.equals("ecdsa")) command.addAll(Arrays.asList("-b", "256"));
        mActivity.showToast(mActivity.getString(R.string.terminow_keypanel_generating), false);
        new Thread(() -> {
            String error = null;
            try {
                Process process = TerminowClaudeSetup.startInTerminal(mActivity, command, Collections.emptyMap(), directory);
                process.getOutputStream().close();
                readAll(process.getInputStream());
                String err = readAll(process.getErrorStream());
                if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroy();
                    error = "ssh-keygen did not finish";
                } else if (process.exitValue() != 0) {
                    error = err.trim().isEmpty() ? "ssh-keygen failed" : err.trim();
                }
            } catch (IOException | InterruptedException e) {
                error = String.valueOf(e.getMessage());
            }
            String finalError = error;
            mActivity.runOnUiThread(() -> {
                if (finalError != null) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, finalError), true);
                    return;
                }
                if (!label.isEmpty() && !label.equals(name)) mStore.setKeyLabel(name, label);
                mActivity.showToast(mActivity.getString(R.string.terminow_key_saved, label.isEmpty() ? name : label), false);
                setKeyTarget(name);
                updateItems();
            });
        }, "TerminowKeygen").start();
    }

    /**
     * Export a key as a PuTTY {@code .ppk}, keeping its passphrase, and open the share sheet so the
     * file can be sent or saved. The stored passphrase is tried first; if the key needs one and none
     * is given, it asks for it.
     */
    private void exportKeyToPpk(@NonNull KeyItem key, @Nullable String passphrase) {
        String pass = passphrase == null ? "" : passphrase.trim();
        try {
            String ppk = TerminowPpk.exportKeyFile(key.file, pass.isEmpty() ? null : pass, key.getTitle());
            java.io.File dir = new java.io.File(mActivity.getCacheDir(), "explorer");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String safe = key.getTitle().replaceAll("[^A-Za-z0-9._-]", "_");
            if (safe.isEmpty()) safe = "key";
            java.io.File out = new java.io.File(dir, safe + ".ppk");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(ppk.getBytes(StandardCharsets.UTF_8));
            }
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                mActivity, mActivity.getPackageName() + ".explorer", out);
            Intent share = new Intent(Intent.ACTION_SEND).setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, safe + ".ppk")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            mActivity.startActivity(Intent.createChooser(share, mActivity.getString(R.string.terminow_keypanel_export_ppk)));
        } catch (TerminowPpk.PassphraseException e) {
            LinearLayout form = createForm();
            EditText passphraseView = addField(form, R.string.terminow_key_passphrase,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            showFormDialog(R.string.terminow_keypanel_export_ppk, form, () -> {
                exportKeyToPpk(key, passphraseView.getText().toString());
                return true;
            });
        } catch (TerminowPpk.PpkException | java.io.IOException | RuntimeException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_keypanel_export_ppk_failed, String.valueOf(e.getMessage())), true);
        }
    }

    /**
     * A key: its type and fingerprint, its name, its public key to copy or share, its
     * certificate, and the hosts and identities that log in with it.
     */
    private void showEditKeyPanel(@NonNull KeyItem key) {
        TerminowPanel panel = new TerminowPanel(mActivity, mActivity.getString(R.string.terminow_keypanel_edit));
        byte[] blob = key.readPublicBlob();
        if (blob == null && key.biometricId == null && key.fidoId == null) {
            // The public part of an OpenSSH key is in the private key file, even with a passphrase
            try {
                if (key.file.length() <= KEY_FILE_MAX_SIZE) {
                    TerminowKeyFormat.Info info = TerminowKeyFormat.analyze(new String(java.nio.file.Files.readAllBytes(key.file.toPath()), StandardCharsets.UTF_8));
                    blob = info.publicBlob;
                    if (blob != null && !key.publicFile.exists()) {
                        try (FileOutputStream out = new FileOutputStream(key.publicFile)) {
                            out.write((TerminowSshWire.publicKeyLine(blob, key.getTitle()) + "\n").getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            } catch (IOException e) {
                blob = null;
            }
        }
        String type = getKeyType(key);
        String kind = key.biometricId != null ? mActivity.getString(R.string.terminow_keypanel_kind_biometric) :
            key.fidoId != null ? mActivity.getString(R.string.terminow_keypanel_kind_fido) : null;
        String title = (type != null ? type : mActivity.getString(R.string.terminow_keypanel_type_unknown)) + (kind != null ? "  ·  " + kind : "");
        String fingerprint = blob != null ? TerminowKeyFormat.fingerprint(blob) : mActivity.getString(R.string.terminow_keypanel_no_fingerprint);
        LinearLayout top = panel.card();
        String fingerprintText = fingerprint;
        LinearLayout keyRow = panel.row(top, R.drawable.ic_terminow_key, title, fingerprint, null);
        if (blob != null) panel.rowButton(keyRow, R.drawable.ic_terminow_copy, mActivity.getString(R.string.terminow_keypanel_copy_fingerprint),
            () -> copyText(key.getTitle(), fingerprintText));

        EditText nameView = panel.field(top, mActivity.getString(R.string.terminow_keypanel_name),
            key.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameView.setText(key.label);
        top.addView(panel.text(mActivity.getString(R.string.terminow_keypanel_file_path, key.getPath()), 12, R.color.terminow_text_secondary));

        // Typed in when ssh asks for the passphrase of this key, like the Passphrase of Termius
        EditText storedPassphraseView = null;
        if (key.biometricId == null && key.fidoId == null) {
            storedPassphraseView = panel.field(top, mActivity.getString(R.string.terminow_key_passphrase),
                mActivity.getString(R.string.terminow_keypanel_passphrase_hint), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            storedPassphraseView.setText(mStore.getKeyPassphrase(key.name));
        }
        EditText finalPassphraseView = storedPassphraseView;

        String publicLine = key.publicFile.isFile() ? readFirstLine(key.publicFile) : null;
        panel.label(top, mActivity.getString(R.string.terminow_keypanel_public));
        if (publicLine != null && !publicLine.isEmpty()) {
            LinearLayout publicRow = new LinearLayout(mActivity);
            publicRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView publicView = panel.text(publicLine, 14, R.color.terminow_text);
            publicView.setTypeface(Typeface.MONOSPACE);
            publicView.setSingleLine(true);
            publicView.setEllipsize(TextUtils.TruncateAt.END);
            publicRow.addView(publicView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            panel.rowButton(publicRow, R.drawable.ic_terminow_copy, mActivity.getString(R.string.terminow_key_copy_public),
                () -> copyText(key.publicFile.getName(), publicLine));
            top.addView(publicRow);
            panel.primaryButton(top, mActivity.getString(R.string.terminow_keypanel_share), () -> {
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, publicLine)
                    .putExtra(Intent.EXTRA_SUBJECT, key.getTitle() + ".pub");
                try {
                    mActivity.startActivity(Intent.createChooser(share, mActivity.getString(R.string.terminow_keypanel_share)));
                } catch (Exception e) {
                    copyText(key.publicFile.getName(), publicLine);
                }
            });
        } else {
            top.addView(panel.text(mActivity.getString(R.string.terminow_keypanel_no_public), 13, R.color.terminow_text_secondary));
            if (key.biometricId == null && key.fidoId == null)
                panel.link(top, R.drawable.ic_terminow_key, mActivity.getString(R.string.terminow_keypanel_make_public), () -> {
                    LinearLayout form = createForm();
                    EditText passphraseView = addField(form, R.string.terminow_key_passphrase, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    showFormDialog(R.string.terminow_keypanel_make_public, form, () -> {
                        panel.dismiss();
                        derivePublicKey(key.name, passphraseView.getText().toString());
                        return true;
                    });
                });
        }

        // Export the private key as a PuTTY .ppk, for tools (PuTTY, WinSCP, some servers) that want
        // that format. Uses the stored passphrase, or asks for it, and keeps the same passphrase.
        if (key.biometricId == null && key.fidoId == null && key.file.isFile()) {
            EditText passphraseForExport = finalPassphraseView;
            panel.primaryButton(top, mActivity.getString(R.string.terminow_keypanel_export_ppk), () ->
                exportKeyToPpk(key, passphraseForExport != null ? passphraseForExport.getText().toString() : ""));
        }

        // The certificate of the key, which ssh uses with it
        TerminowSshWire.Certificate certificate = key.readCertificate();
        if (certificate != null) {
            panel.row(top, R.drawable.ic_terminow_certificate, mActivity.getString(R.string.terminow_keypanel_certificate),
                describeCertificateShort(certificate), () -> showCertificate(key, certificate));
        } else if (blob != null || publicLine != null) {
            panel.link(top, R.drawable.ic_terminow_certificate, mActivity.getString(R.string.terminow_cert_add), () -> {
                panel.dismiss();
                showCertificateSource(key);
            });
        }

        // The hosts and identities that log in with the key
        List<TerminowStore.Host> hosts = getHostsUsingKey(key);
        List<TerminowStore.Identity> identities = new ArrayList<>();
        for (TerminowStore.Identity identity : mStore.getIdentities())
            if (identity.keyPath.equals(key.getPath()) || identity.keyPath.equals(key.file.getAbsolutePath())) identities.add(identity);
        LinearLayout attached = panel.expandable(mActivity.getString(R.string.terminow_keypanel_attached, hosts.size() + identities.size()), false);
        if (hosts.isEmpty() && identities.isEmpty())
            attached.addView(panel.text(mActivity.getString(R.string.terminow_key_no_hosts), 13, R.color.terminow_text_secondary));
        for (TerminowStore.Host host : hosts)
            panel.row(attached, R.drawable.ic_terminow_hosts, host.getTitle(), host.username + "@" + host.address, () -> {
                panel.dismiss();
                showHostDialog(host);
            });
        for (TerminowStore.Identity identity : identities)
            panel.row(attached, R.drawable.ic_terminow_person_add, identity.getTitle(), describeIdentity(identity), () -> {
                panel.dismiss();
                showIdentityDialog(identity, null);
            });

        boolean hasPublic = blob != null || publicLine != null;
        panel.onMenu(anchor -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(mActivity, anchor);
            if (publicLine != null) menu.getMenu().add(0, 1, 0, R.string.terminow_key_copy_public);
            if (certificate == null && hasPublic) menu.getMenu().add(0, 2, 0, R.string.terminow_cert_add);
            menu.getMenu().add(0, 3, 0, R.string.terminow_delete);
            menu.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 1 && publicLine != null) copyText(key.publicFile.getName(), publicLine);
                else if (item.getItemId() == 2) {
                    panel.dismiss();
                    showCertificateSource(key);
                } else if (item.getItemId() == 3) confirmDeleteKey(key, panel::dismiss);
                return true;
            });
            menu.show();
        });
        panel.onSave(() -> {
            String label = nameView.getText().toString().trim().replaceAll("\\p{Cntrl}", " ");
            if (!label.equals(key.label)) {
                mStore.setKeyLabel(key.name, label);
                updateItems();
            }
            if (finalPassphraseView != null) mStore.setKeyPassphrase(key.name, finalPassphraseView.getText().toString());
            return true;
        });
        panel.show();
    }

    private void copyText(@NonNull String label, @NonNull String text) {
        ClipboardManager clipboardManager = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboardManager == null) return;
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text));
        mActivity.showToast(mActivity.getString(R.string.terminow_key_copied), false);
    }

    /** Delete a key, after asking, taking it out of the hosts that log in with it. */
    private void confirmDeleteKey(@NonNull KeyItem key, @Nullable Runnable onDeleted) {
        List<TerminowStore.Host> hosts = getHostsUsingKey(key);
        String message = mActivity.getString(R.string.terminow_key_delete_confirm, key.getTitle());
        if (!hosts.isEmpty())
            message += "\n\n" + mActivity.getResources().getQuantityString(R.plurals.terminow_key_delete_hosts, hosts.size(), hosts.size());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(message)
            .setPositiveButton(R.string.terminow_delete, (confirmDialog, confirmWhich) -> {
                if (key.biometricId != null) {
                    TerminowBiometricKeys.delete(key.biometricId);
                } else if (key.fidoId != null) {
                    TerminowFidoKeys.delete(key.fidoId);
                } else {
                    if (!key.file.delete())
                        Logger.logError(LOG_TAG, "Failed to delete \"" + key.file + "\"");
                    if (key.publicFile.isFile() && !key.publicFile.delete())
                        Logger.logError(LOG_TAG, "Failed to delete \"" + key.publicFile + "\"");
                    //noinspection ResultOfMethodCallIgnored
                    key.certificateFile().delete();
                }
                mStore.setKeyLabel(key.name, "");
                mStore.setKeyPassphrase(key.name, "");
                // The hosts would fail to log in with a key that does not exist anymore
                for (TerminowStore.Host host : hosts)
                    host.keyPath = "";
                if (!hosts.isEmpty()) mStore.setHosts(mHosts);
                updateItems();
                if (onDeleted != null) onDeleted.run();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /**
     * Add or edit an identity in a panel: a name, a username, a password and a key, and the
     * hosts that log in with it. {@code onSaved} gets the id of the identity.
     */
    private void showIdentityDialog(@Nullable TerminowStore.Identity existing, @Nullable java.util.function.Consumer<String> onSaved) {
        TerminowPanel panel = new TerminowPanel(mActivity, mActivity.getString(existing != null ? R.string.terminow_identity_edit : R.string.terminow_identity_new));
        LinearLayout card = panel.card();
        EditText labelView = panel.field(card, mActivity.getString(R.string.terminow_identity_label), "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText usernameView = panel.field(card, mActivity.getString(R.string.terminow_host_username), "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText passwordView = panel.field(card, mActivity.getString(R.string.terminow_host_password), "",
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout keyCard = panel.card();
        panel.label(keyCard, mActivity.getString(R.string.terminow_host_key_field));
        EditText keyView = new EditText(mActivity);
        keyView.setVisibility(View.GONE);
        keyCard.addView(keyView);
        LinearLayout keyHolder = new LinearLayout(mActivity);
        keyHolder.setOrientation(LinearLayout.VERTICAL);
        keyCard.addView(keyHolder);
        Runnable showKey = () -> {
            keyHolder.removeAllViews();
            String path = keyView.getText().toString().trim();
            if (path.isEmpty()) {
                panel.link(keyHolder, R.drawable.ic_terminow_key, mActivity.getString(R.string.terminow_keypanel_choose_key), () -> showKeyPicker(keyView));
                return;
            }
            KeyItem chosen = null;
            for (KeyItem item : getKeys()) if (item.getPath().equals(path) || item.file.getAbsolutePath().equals(path)) chosen = item;
            String type = chosen != null ? getKeyType(chosen) : null;
            LinearLayout row = panel.row(keyHolder, R.drawable.ic_terminow_key, chosen != null ? chosen.getTitle() : path,
                type != null ? type : path, () -> showKeyPicker(keyView));
            panel.rowButton(row, R.drawable.ic_terminow_close, mActivity.getString(R.string.terminow_host_key_remove), () -> keyView.setText(""));
        };
        keyView.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable s) { showKey.run(); }
        });
        if (existing != null) {
            labelView.setText(existing.label);
            usernameView.setText(existing.username);
            passwordView.setText(existing.password);
            keyView.setText(existing.keyPath);
        }
        showKey.run();
        keyCard.addView(panel.text(mActivity.getString(R.string.terminow_keypanel_identity_note), 13, R.color.terminow_text_secondary));

        if (existing != null) {
            List<TerminowStore.Host> hosts = getHostsUsingIdentity(existing);
            LinearLayout attached = panel.expandable(mActivity.getString(R.string.terminow_keypanel_attached, hosts.size()), false);
            if (hosts.isEmpty()) attached.addView(panel.text(mActivity.getString(R.string.terminow_key_no_hosts), 13, R.color.terminow_text_secondary));
            for (TerminowStore.Host host : hosts)
                panel.row(attached, R.drawable.ic_terminow_hosts, host.getTitle(), host.username + "@" + host.address, () -> {
                    panel.dismiss();
                    showHostDialog(host);
                });
            panel.onMenu(anchor -> {
                android.widget.PopupMenu menu = new android.widget.PopupMenu(mActivity, anchor);
                menu.getMenu().add(0, 1, 0, R.string.terminow_delete);
                menu.setOnMenuItemClickListener(item -> {
                    confirmDeleteIdentity(existing, panel::dismiss);
                    return true;
                });
                menu.show();
            });
        }

        panel.onSave(() -> {
            TerminowStore.Identity identity = new TerminowStore.Identity();
            identity.id = existing != null ? existing.id : Long.toString(System.currentTimeMillis(), 36) +
                Integer.toString((int) (Math.random() * 1296), 36);
            identity.created = existing != null ? existing.created : System.currentTimeMillis();
            identity.label = labelView.getText().toString().trim();
            identity.username = usernameView.getText().toString().trim();
            identity.password = passwordView.getText().toString();
            identity.keyPath = keyView.getText().toString().trim();
            if (identity.username.isEmpty() && identity.password.isEmpty() && identity.keyPath.isEmpty()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_identity_empty), true);
                return false;
            }
            if (!TerminowStore.isValidUsername(identity.username)) {
                usernameView.setError(mActivity.getString(R.string.terminow_host_invalid_username));
                return false;
            }
            if (!identity.isValid()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_key), true);
                return false;
            }
            List<TerminowStore.Identity> identities = mStore.getIdentities();
            boolean replaced = false;
            for (int i = 0; i < identities.size(); i++) {
                if (identities.get(i).id.equals(identity.id)) {
                    identities.set(i, identity);
                    replaced = true;
                }
            }
            if (!replaced) identities.add(identity);
            mStore.setIdentities(identities);
            updateItems();
            if (onSaved != null) onSaved.accept(identity.id);
            return true;
        });
        panel.show();
    }

    private void confirmDeleteIdentity(@NonNull TerminowStore.Identity identity, @Nullable Runnable onDeleted) {
        List<TerminowStore.Host> hosts = getHostsUsingIdentity(identity);
        String message = mActivity.getString(R.string.terminow_identity_delete_confirm, identity.getTitle());
        if (!hosts.isEmpty())
            message += "\n\n" + mActivity.getResources().getQuantityString(R.plurals.terminow_identity_delete_hosts, hosts.size(), hosts.size());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(message)
            .setPositiveButton(R.string.terminow_delete, (d, w) -> {
                List<TerminowStore.Identity> identities = mStore.getIdentities();
                identities.removeIf(other -> other.id.equals(identity.id));
                mStore.setIdentities(identities);
                // The hosts log in with their own username, password and key again
                for (TerminowStore.Host host : hosts) host.identity = "";
                if (!hosts.isEmpty()) mStore.setHosts(mHosts);
                updateItems();
                if (onDeleted != null) onDeleted.run();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Show the menu to add a key above the add button, like the keychain menu of Termius. */
    private void showKeyMenu(@NonNull View anchor) {
        LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(R.drawable.terminow_menu);
        menu.setPadding(0, dp(10), 0, dp(10));

        PopupWindow popup = new PopupWindow(menu, dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // Like the + of the keychain of Termius: a key first, then an identity, then the keys bound to a device
        addMenuItem(menu, popup, R.drawable.ic_terminow_key, R.string.terminow_keypanel_new_item, () -> showNewKeyPanel(null, null));
        addMenuItem(menu, popup, R.drawable.ic_terminow_paste, R.string.terminow_key_paste, this::pasteKey);
        addMenuItem(menu, popup, R.drawable.ic_terminow_import, R.string.terminow_key_import, this::pickKeyFile);
        addMenuItem(menu, popup, R.drawable.ic_terminow_generate, R.string.terminow_key_generate, this::showGenerateKeyPanel);
        addMenuDivider(menu);
        // A certificate goes to the key it certifies: the New Key panel finds it
        addMenuItem(menu, popup, R.drawable.ic_terminow_paste, R.string.terminow_key_paste_certificate, this::pasteKey);
        addMenuItem(menu, popup, R.drawable.ic_terminow_import, R.string.terminow_key_import_certificate, this::pickKeyFile);
        addMenuDivider(menu);
        addMenuItem(menu, popup, R.drawable.ic_terminow_person_add, R.string.terminow_identity_new, () -> showIdentityDialog(null, null));
        addMenuDivider(menu);
        addMenuItem(menu, popup, R.drawable.ic_terminow_lock, R.string.terminow_key_biometric_new, this::showNewBiometricKeyDialog);
        addMenuItem(menu, popup, R.drawable.ic_terminow_sdcard, R.string.terminow_key_fido_new, this::showNewFidoKeyDialog);

        // Above the button, with its end at the end of the button
        menu.measure(View.MeasureSpec.makeMeasureSpec(dp(300), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        popup.showAsDropDown(anchor, anchor.getWidth() - dp(300), -anchor.getHeight() - menu.getMeasuredHeight() - dp(8));
    }

    private void addMenuItem(@NonNull LinearLayout menu, @NonNull PopupWindow popup, int iconRes, int titleRes, @NonNull Runnable onClick) {
        addMenuItem(menu, popup, iconRes, mActivity.getString(titleRes), onClick);
    }

    private void addMenuItem(@NonNull LinearLayout menu, @NonNull PopupWindow popup, int iconRes, @NonNull CharSequence title, @NonNull Runnable onClick) {
        LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(24), 0, dp(24), 0);
        TypedValue background = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
        item.setBackgroundResource(background.resourceId);

        ImageView iconView = new ImageView(mActivity);
        iconView.setImageResource(iconRes);
        iconView.setColorFilter(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary), PorterDuff.Mode.SRC_IN);
        item.addView(iconView, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView titleView = new TextView(mActivity);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
        titleView.setTextSize(16);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(dp(18));
        item.addView(titleView, titleParams);

        item.setOnClickListener(v -> {
            popup.dismiss();
            onClick.run();
        });
        menu.addView(item, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
    }

    private boolean isValidNewKeyName(@NonNull String name) {
        return KEY_NAME_PATTERN.matcher(name).matches() && !name.endsWith(".pub") && !new File(getSshDir(), name).exists();
    }

    /** Get a name for a new key that is not used yet. */
    @NonNull
    private String getNewKeyName(@NonNull String name) {
        // Make a name that is allowed from a file name, like for an imported file
        name = name.replaceAll("\\.(pem|key|txt|ppk)$", "").replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("^[^A-Za-z0-9_]+", "");
        if (name.isEmpty() || name.endsWith(".pub")) name = "id_key";
        String newName = name;
        for (int i = 2; new File(getSshDir(), newName).exists(); i++)
            newName = name + "_" + i;
        return newName;
    }

    /**
     * Save a private key in `~/.ssh` with the permissions that ssh requires for it. Returns
     * {@code false} and shows why if it is not a private key that ssh can use.
     */
    private boolean saveKey(@NonNull String name, @NonNull String content) {
        return saveKey(name, content, null);
    }

    /** Like {@link #saveKey(String, String)}, with the line of its public key file if it is known. */
    private boolean saveKey(@NonNull String name, @NonNull String content, @Nullable String publicContent) {
        // Windows line endings and a missing line end make ssh fail to load a key
        content = content.replace("\r\n", "\n").replace('\r', '\n').trim() + "\n";
        if (TerminowPpk.isPpk(content)) {
            String ppk = content;
            boolean encrypted;
            try {
                encrypted = TerminowPpk.isEncrypted(ppk);
            } catch (TerminowPpk.PpkException e) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
                return false;
            }
            if (encrypted)
                askPpkPassphrase(name, ppk, null);
            else
                convertPpk(name, ppk, null);
            return true;
        }
        if (!content.startsWith("-----BEGIN ") || !content.contains("PRIVATE KEY-----")) {
            mActivity.showToast(mActivity.getString(R.string.terminow_key_content_invalid), true);
            return false;
        }
        return writeKey(name, content, publicContent);
    }

    /** Ask for the passphrase of an encrypted PuTTY key, which is only kept to convert it. */
    private void askPpkPassphrase(@NonNull String name, @NonNull String ppk, @Nullable String error) {
        LinearLayout form = createForm();
        EditText passphraseView = addField(form, R.string.terminow_key_passphrase,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if (error != null) passphraseView.setError(error);
        showFormDialog(R.string.terminow_key_ppk_title, form, () -> {
            String passphrase = passphraseView.getText().toString();
            if (passphrase.isEmpty()) {
                passphraseView.setError(mActivity.getString(R.string.terminow_key_passphrase_needed));
                return false;
            }
            convertPpk(name, ppk, passphrase);
            return true;
        });
    }

    /**
     * Convert a PuTTY key to an OpenSSH key in the background, since deriving the key from the
     * passphrase takes a while. The converted key is saved without a passphrase, in the private
     * directory of the app, readable only by it.
     */
    private void convertPpk(@NonNull String name, @NonNull String ppk, @Nullable String passphrase) {
        mActivity.showToast(mActivity.getString(R.string.terminow_key_converting), false);
        new Thread(() -> {
            TerminowPpk.Result result = null;
            TerminowPpk.PpkException failure = null;
            try {
                result = TerminowPpk.convert(ppk, passphrase);
            } catch (TerminowPpk.PpkException e) {
                failure = e;
            } catch (RuntimeException | OutOfMemoryError e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to convert PuTTY key", e);
                failure = new TerminowPpk.PpkException(String.valueOf(e.getMessage()));
            }
            TerminowPpk.Result finalResult = result;
            TerminowPpk.PpkException finalFailure = failure;
            mActivity.runOnUiThread(() -> {
                if (finalResult != null) {
                    writeKey(name, finalResult.privateKey, finalResult.publicKey + "\n");
                } else if (finalFailure instanceof TerminowPpk.PassphraseException && passphrase != null) {
                    askPpkPassphrase(name, ppk, finalFailure.getMessage());
                } else if (finalFailure != null) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, finalFailure.getMessage()), true);
                }
            });
        }).start();
    }

    private boolean writeKey(@NonNull String name, @NonNull String content, @Nullable String publicContent) {
        File directory = getSshDir();
        File file = new File(directory, name);
        File publicFile = new File(directory, name + ".pub");
        try {
            if (!directory.isDirectory() && !directory.mkdirs())
                throw new IOException("Failed to create \"" + directory + "\"");
            Os.chmod(directory.getPath(), 0700);
            try (FileOutputStream out = new FileOutputStream(file)) {
                // Before the key is written, so that it is never readable by others
                Os.chmod(file.getPath(), 0600);
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            if (publicContent != null && !publicFile.exists()) {
                try (FileOutputStream out = new FileOutputStream(publicFile)) {
                    out.write(publicContent.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | ErrnoException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save key \"" + file + "\"", e);
            mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
            return false;
        }

        mActivity.showToast(mActivity.getString(R.string.terminow_key_saved, name), false);
        setKeyTarget(name);
        updateItems();
        return true;
    }

    /** Show the file picker of the device to import a private key file. */
    private void pickKeyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            mActivity.startActivityForResult(intent, REQUEST_IMPORT_KEY);
        } catch (Exception e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
        }
    }

    /** Handle the result of an activity that was started for the home screen. Returns {@code true} if it was for it. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (mExplorer.onActivityResult(requestCode, resultCode, data)) return true;
        if (mSftpBrowser.onActivityResult(requestCode, resultCode, data)) return true;
        if (mFiles.onActivityResult(requestCode, resultCode, data)) return true;
        if (requestCode == REQUEST_IMPORT_ROOTFS) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                String fileName = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "";
                try (Cursor cursor = mActivity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) fileName = cursor.getString(0);
                } catch (RuntimeException e) {
                    // The name of the uri
                }
                mLinuxUi.importRootfs(uri, fileName.substring(fileName.lastIndexOf('/') + 1));
            }
            return true;
        }
        if (mBackupUi.onActivityResult(requestCode, resultCode, data)) return true;
        if (requestCode == REQUEST_SAVE_DEBUG_LOG) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri target = data.getData();
                new Thread(() -> {
                    String error = null;
                    try (java.io.OutputStream out = mActivity.getContentResolver().openOutputStream(target, "w")) {
                        if (out == null) throw new java.io.IOException("The file could not be opened");
                        TerminowDebugLog.copyTo(mActivity, out);
                    } catch (Exception e) {
                        error = String.valueOf(e.getMessage());
                    }
                    String finalError = error;
                    mHandler.post(() -> mActivity.showToast(finalError == null ? mActivity.getString(R.string.terminow_debug_saved) :
                        finalError, true));
                }, "terminow-debug-save").start();
            }
            return true;
        }
        if (requestCode == REQUEST_IMPORT_HOSTS) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) readImportFile(data.getData());
            return true;
        }
        if (requestCode == REQUEST_UPLOAD_TMP) {
            TerminalSession session = mUploadSession != null ? mUploadSession.get() : null;
            mUploadSession = null;
            List<Uri> uris = new ArrayList<>();
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null)
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
            }
            if (session != null && !uris.isEmpty()) uploadToTmp(session, uris);
            return true;
        }
        if (requestCode == REQUEST_IMPORT_CERTIFICATE) {
            KeyItem key = mCertificateKey;
            mCertificateKey = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null && key != null) {
                try (InputStream in = mActivity.getContentResolver().openInputStream(data.getData())) {
                    if (in == null) throw new IOException("Failed to open the file");
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        if (out.size() > CERTIFICATE_MAX_SIZE) throw new IOException("The file is too large to be a certificate");
                    }
                    saveCertificate(key, new String(out.toByteArray(), StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException e) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_cert_invalid, e.getMessage()), true);
                }
            }
            return true;
        }
        if (requestCode != REQUEST_IMPORT_KEY) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;

        Uri uri = data.getData();
        try {
            String content;
            try (InputStream in = mActivity.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Failed to open the file");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    // A private key is a few kilobytes at most
                    if (out.size() > KEY_FILE_MAX_SIZE) throw new IOException("The file is too large to be a key");
                }
                content = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }

            String fileName = uri.getLastPathSegment();
            try (Cursor cursor = mActivity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                    fileName = cursor.getString(0);
            }
            if (fileName == null) fileName = "";
            String suggested = fileName.substring(fileName.lastIndexOf('/') + 1).replaceAll("\\.(pem|key|txt|ppk)$", "");
            // Checked and named in the New Key panel before it is saved
            EditText area = mKeyPanelArea != null ? mKeyPanelArea.get() : null;
            EditText nameField = mKeyPanelName != null ? mKeyPanelName.get() : null;
            if (area != null && area.isAttachedToWindow()) {
                area.setText(content.trim());
                if (nameField != null && nameField.getText().toString().trim().isEmpty()) nameField.setText(suggested);
            } else {
                showNewKeyPanel(content, suggested);
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import key from \"" + uri + "\"", e);
            mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
        }
        return true;
    }

    /** Get the hosts whose keys are saved in `~/.ssh/known_hosts`. */
    @NonNull
    private List<KnownHost> getKnownHosts() {
        List<KnownHost> knownHosts = new ArrayList<>();
        File file = new File(getSshDir(), "known_hosts");
        if (!file.isFile()) return knownHosts;

        try {
            for (String line : readLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                // The fields are an optional marker, the hosts, the key type and the key
                String[] fields = trimmed.split("\\s+");
                int hostsIndex = fields[0].startsWith("@") ? 1 : 0;
                if (fields.length < hostsIndex + 2) continue;

                String hosts = fields[hostsIndex];
                // The host names are hashed if HashKnownHosts is set, so they can not be shown
                if (hosts.startsWith("|"))
                    hosts = mActivity.getString(R.string.terminow_known_host_hashed);
                knownHosts.add(new KnownHost(line, hosts, fields[hostsIndex + 1]));
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to read \"" + file + "\"", e);
        }
        return knownHosts;
    }

    private void showRemoveKnownHostDialog(@NonNull KnownHost knownHost) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(mActivity.getString(R.string.terminow_known_host_remove, knownHost.hosts))
            .setPositiveButton(R.string.terminow_delete, (dialog, which) -> {
                File file = new File(getSshDir(), "known_hosts");
                try {
                    // Read the file again, since it may have changed while the page was shown
                    List<String> lines = readLines(file);
                    if (lines.remove(knownHost.line)) {
                        File tmpFile = new File(file.getPath() + ".tmp");
                        try (FileOutputStream out = new FileOutputStream(tmpFile)) {
                            for (String line : lines)
                                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        }
                        if (!tmpFile.renameTo(file))
                            throw new IOException("Failed to replace \"" + file + "\"");
                    }
                } catch (IOException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to remove known host", e);
                    mActivity.showToast(e.getMessage() != null ? e.getMessage() : e.toString(), true);
                }
                updateItems();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Show the panel to set up a new port forwarding, or to edit an existing one. */
    private void showForwardWizard(@Nullable TerminowStore.Forward existingForward) {
        if (mHosts.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_forward_need_host), true);
            return;
        }

        new TerminowForwardWizard(mActivity, mHosts, existingForward, forward -> {
            if (existingForward != null && mForwards.contains(existingForward))
                mForwards.set(mForwards.indexOf(existingForward), forward);
            else
                mForwards.add(forward);
            mStore.setForwards(mForwards);
            updateItems();
        }).show();
    }

    private void showSnippetDialog(@Nullable TerminowStore.Snippet existingSnippet) {
        LinearLayout form = createForm();

        // The vault the snippet is saved in — Personal or a team the phone has the key of. Shown only
        // when there is more than one, so a snippet can be moved into a shared (team) vault like a host.
        List<String[]> vaults = mVaultsUi.getHostVaults();
        String currentScope = mStore.getActiveVault();
        final String[] scope = {currentScope};
        if (vaults.size() > 1) {
            TextView vaultRow = new TextView(form.getContext());
            vaultRow.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
            vaultRow.setTextSize(15);
            vaultRow.setTypeface(Typeface.DEFAULT_BOLD);
            vaultRow.setPadding(0, dp(4), 0, dp(10));
            Runnable showVault = () -> {
                String name = vaults.get(0)[1];
                for (String[] vault : vaults) if (vault[0].equals(scope[0])) name = vault[1];
                vaultRow.setText(mActivity.getString(R.string.terminow_host_vault_name, name));
            };
            showVault.run();
            vaultRow.setOnClickListener(v -> {
                String[] names = new String[vaults.size()];
                for (int i = 0; i < vaults.size(); i++) names[i] = vaults.get(i)[1];
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setTitle(R.string.terminow_host_vault_title)
                    .setItems(names, (d, which) -> {
                        scope[0] = vaults.get(which)[0];
                        showVault.run();
                    })
                    .show();
            });
            form.addView(vaultRow);
        }

        EditText nameView = addField(form, R.string.terminow_snippet_name, InputType.TYPE_CLASS_TEXT);
        EditText commandView = addField(form, R.string.terminow_snippet_command,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        if (existingSnippet != null) {
            nameView.setText(existingSnippet.name);
            commandView.setText(existingSnippet.command);
        }

        showFormDialog(existingSnippet == null ? R.string.terminow_new_snippet : R.string.terminow_edit_snippet, form, () -> {
            String command = commandView.getText().toString().trim();
            if (command.isEmpty()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_snippet_invalid), true);
                return false;
            }

            TerminowStore.Snippet snippet = existingSnippet != null ? existingSnippet : new TerminowStore.Snippet();
            snippet.name = nameView.getText().toString().trim();
            snippet.command = command;
            if (scope[0].equals(currentScope)) {
                if (existingSnippet == null) mSnippets.add(snippet);
                mStore.setSnippets(mSnippets);
            } else {
                // Moved to another vault: take it out of the shown one and add it to the chosen one
                if (existingSnippet != null) {
                    mSnippets.remove(existingSnippet);
                    mStore.setSnippets(mSnippets);
                }
                List<TerminowStore.Snippet> target = mStore.getSnippetsIn(scope[0]);
                target.add(snippet);
                mStore.setSnippetsIn(scope[0], target);
                String name = vaults.get(0)[1];
                for (String[] vault : vaults) if (vault[0].equals(scope[0])) name = vault[1];
                mActivity.showToast(mActivity.getString(R.string.terminow_host_saved_in_vault, name), false);
            }
            updateItems();
            return true;
        });
    }

    private interface FormSaver {
        /** Save the form, returns {@code false} if the form is invalid and the dialog should stay open. */
        boolean save();
    }

    // ---------------------------------------------------------------------------------------
    // Account & Premium
    // ---------------------------------------------------------------------------------------

    /** Open Account & Premium: sign in, the plan, and buying or managing Premium. */
    public void openAccountScreen() {
        new TerminowAccountScreen(mActivity, mSettings.getAccountUi(), mVaultsUi).show();
    }

    /** The crown of the top bar: gold for Premium, gray for everyone else, who can upgrade with it. */
    public void updatePremiumButton() {
        if (mPremiumButton == null) return;
        boolean premium = TerminowAccountUi.isPremium(mStore);
        ImageView icon = mPremiumButton.findViewById(R.id.terminow_premium_icon);
        TextView label = mPremiumButton.findViewById(R.id.terminow_premium_label);
        icon.setColorFilter(premium ? 0xFFFFC107 : ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        label.setText(premium ? R.string.terminow_toolbar_premium : R.string.terminow_toolbar_upgrade);
        label.setTextColor(premium ? 0xFFFFC107 : ContextCompat.getColor(mActivity, R.color.terminow_text));
        // Only the crown, on every screen; what it means is its description and tooltip
        label.setVisibility(View.GONE);
        String description = mActivity.getString(premium ? R.string.terminow_toolbar_premium_active : R.string.terminow_toolbar_premium_upgrade);
        mPremiumButton.setContentDescription(description);
        androidx.appcompat.widget.TooltipCompat.setTooltipText(mPremiumButton, description);
    }

    // ---------------------------------------------------------------------------------------
    // The paperclip of the extra keys: paste text, or upload files to /tmp of the terminal
    // ---------------------------------------------------------------------------------------

    /** The menu of the paperclip key, above it. */
    public void showAttachMenu(@NonNull View anchor, @NonNull Runnable paste) {
        LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(R.drawable.terminow_menu);
        menu.setPadding(0, dp(8), 0, dp(8));
        PopupWindow popup = new PopupWindow(menu, dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        addMenuItem(menu, popup, R.drawable.ic_terminow_paste, R.string.terminow_attach_paste, paste);
        addMenuItem(menu, popup, R.drawable.ic_terminow_attach, R.string.terminow_attach_upload, this::pickFilesForTmp);
        menu.measure(View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        popup.showAsDropDown(anchor, 0, -anchor.getHeight() - menu.getMeasuredHeight() - dp(8));
    }

    /** Attach files: copy the files the user picks to /tmp of the tab and type their paths, with no Enter. */
    public void attachFiles() {
        pickFilesForTmp();
    }

    /** The key panel with more keys: beside the terminal on a wide screen, over it on a narrow one. */
    public void toggleKeyPanel() {
        mKeyPanel.toggle();
    }

    /** The preferences of TermiNow, like the favorite commands of the key bar. */
    @NonNull
    TerminowStore getStore() {
        return mStore;
    }

    @NonNull
    TerminowAiBridgeManager getAiBridge() {
        return mAiBridge;
    }

    /** The launcher of tabs, for the key bar: what a tab runs and Claude Code in a new tab. */
    @NonNull
    TerminowLauncher getLauncher() {
        return mLauncher;
    }

    /** Whether TermiNow started Claude Code as the program of the tab. */
    public boolean isClaudeTab(@NonNull TerminalSession session) {
        return mLauncher.isClaudeTab(session);
    }

    private void pickFilesForTmp() {
        TerminalSession session = mActivity.getCurrentSession();
        if (session == null) return;
        mUploadSession = new WeakReference<>(session);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            mActivity.startActivityForResult(intent, REQUEST_UPLOAD_TMP);
        } catch (Exception e) {
            mUploadSession = null;
            mActivity.showToast(mActivity.getString(R.string.terminow_attach_failed, e.getMessage()), true);
        }
    }

    /** A file name that is safe on any system and in a shell, like "My photo (1).jpg" to "My_photo_1_.jpg". */
    @NonNull
    static String safeUploadName(@Nullable String name) {
        String base = name == null ? "" : name.substring(name.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("_+", "_").replaceAll("^[.-]+", "");
        if (base.length() > 120) base = base.substring(base.length() - 120);
        return base.isEmpty() ? "upload" : base;
    }

    /** The name with "-1", "-2"... before the extension, for one that is taken. */
    @NonNull
    static String numberedName(@NonNull String name, int number) {
        if (number == 0) return name;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) + "-" + number + name.substring(dot) : name + "-" + number;
    }

    /** Where files of a terminal go: its environment's /tmp, its host's /tmp over SFTP, or $PREFIX/tmp. */
    private interface UploadTarget {
        /** Write a new file, never replacing one; returns its path as the terminal sees it. */
        @NonNull String write(@NonNull String name, @NonNull InputStream in) throws IOException;
    }

    @NonNull
    private UploadTarget uploadTargetOf(@NonNull TerminalSession session) throws IOException {
        TerminowLauncher.SessionInfo info = mLauncher.getSessionInfo(session);
        String environment = info != null ? info.environmentKey : null;
        if (environment != null && !TerminowMcpTargets.TARGET_TERMINOW.equals(environment)) {
            TerminowLinuxEnv env = mLinuxUi.getRegistry().get(environment);
            if (env == null || !env.isInstalled()) throw new IOException("The environment " + environment + " is not installed");
            // The guest's own /tmp, resolved like the guest sees it, never through a link out of it
            TerminowLinuxFs fs = mLinuxUi.filesOf(env);
            return (name, in) -> {
                TerminowFs.Node tmp = fs.nodeOf("/tmp");
                for (int i = 0; ; i++) {
                    String candidate = numberedName(name, i);
                    if (fs.find(tmp, candidate) != null) continue;
                    try (OutputStream out = fs.openOutput(tmp, candidate, false)) {
                        copy(in, out);
                    }
                    return "/tmp/" + candidate;
                }
            };
        }
        if (environment == null && info != null && ("ssh".equals(info.tool) || "mosh".equals(info.tool))) {
            TerminowStore.Host host = getHost(info.environmentLabel);
            if (host == null) throw new IOException("The host " + info.environmentLabel + " is not saved");
            TerminowSftpFs fs = mExplorer.sftpFor(host);
            return (name, in) -> {
                fs.connect();
                TerminowFs.Node tmp = fs.getDirectory("/tmp");
                for (int i = 0; ; i++) {
                    String candidate = numberedName(name, i);
                    if (fs.find(tmp, candidate) != null) continue;
                    try (OutputStream out = fs.openOutput(tmp, candidate, false)) {
                        copy(in, out);
                    }
                    return "/tmp/" + candidate;
                }
            };
        }
        File tmp = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "tmp");
        return (name, in) -> {
            if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("Could not create " + tmp);
            for (int i = 0; ; i++) {
                File file = new File(tmp, numberedName(name, i));
                if (file.exists()) continue;
                try (OutputStream out = new java.io.FileOutputStream(file)) {
                    copy(in, out);
                }
                return file.getAbsolutePath();
            }
        };
    }

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    /**
     * Copy picked files to /tmp of the terminal, then type their paths at its prompt, without
     * Enter, for the user to use in a command.
     */
    private void uploadToTmp(@NonNull TerminalSession session, @NonNull List<Uri> uris) {
        mActivity.showToast(mActivity.getResources().getQuantityString(R.plurals.terminow_attach_uploading, uris.size(), uris.size()), false);
        new Thread(() -> {
            List<String> paths = new ArrayList<>();
            String error = null;
            try {
                UploadTarget target = uploadTargetOf(session);
                for (Uri uri : uris) {
                    String name = null;
                    try (Cursor cursor = mActivity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
                    }
                    try (InputStream in = mActivity.getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("Failed to open the file");
                        paths.add(target.write(safeUploadName(name != null ? name : uri.getLastPathSegment()), in));
                    }
                }
            } catch (IOException | RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to upload to /tmp", e);
                error = String.valueOf(e.getMessage());
            }
            String finalError = error;
            mActivity.runOnUiThread(() -> {
                if (!paths.isEmpty() && session.isRunning()) {
                    StringBuilder text = new StringBuilder();
                    for (String path : paths) text.append(path.matches("[A-Za-z0-9._/~+-]+") ? path : TerminowStore.shellQuote(path)).append(' ');
                    session.write(text.toString());
                }
                if (finalError != null)
                    mActivity.showToast(mActivity.getString(R.string.terminow_attach_failed, finalError), true);
                else
                    mActivity.showToast(mActivity.getResources().getQuantityString(R.plurals.terminow_attach_uploaded, paths.size(), paths.size(),
                        paths.isEmpty() ? "" : paths.get(0).substring(0, paths.get(0).lastIndexOf('/') + 1)), false);
            });
        }, "TerminowUploadTmp").start();
    }

    // ---------------------------------------------------------------------------------------
    // Keychain: sorting, identities, certificates, biometric and FIDO2 keys
    // ---------------------------------------------------------------------------------------

    static void sortKeychain(@NonNull List<TerminowStore.Identity> identities, @NonNull List<KeyItem> keys, @NonNull String sort) {
        switch (sort) {
            case "za":
                identities.sort((a, b) -> b.getTitle().compareToIgnoreCase(a.getTitle()));
                keys.sort((a, b) -> b.getTitle().compareToIgnoreCase(a.getTitle()));
                break;
            case "new":
                identities.sort((a, b) -> Long.compare(b.created, a.created));
                keys.sort((a, b) -> Long.compare(b.file.lastModified(), a.file.lastModified()));
                break;
            case "old":
                identities.sort((a, b) -> Long.compare(a.created, b.created));
                keys.sort((a, b) -> Long.compare(a.file.lastModified(), b.file.lastModified()));
                break;
            default:
                identities.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
                keys.sort((a, b) -> a.getTitle().compareToIgnoreCase(b.getTitle()));
        }
    }

    private void showKeychainSort(@NonNull View anchor) {
        String[] values = {"az", "za", "new", "old"};
        CharSequence[] labels = {mActivity.getString(R.string.terminow_sort_az), mActivity.getString(R.string.terminow_sort_za),
            mActivity.getString(R.string.terminow_sort_new), mActivity.getString(R.string.terminow_sort_old)};
        int current = Arrays.asList(values).indexOf(mStore.getKeychainSort());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_keychain_sort)
            .setSingleChoiceItems(labels, Math.max(current, 0), (dialog, which) -> {
                mStore.setKeychainSort(values[which]);
                mSortView.setText(which == 1 ? "Za" : "Az");
                dialog.dismiss();
                updateItems();
            })
            .show();
    }

    private void addMenuDivider(@NonNull LinearLayout menu) {
        View divider = new View(mActivity);
        divider.setBackgroundColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        divider.setAlpha(0.25f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(dp(24), dp(6), dp(24), dp(6));
        menu.addView(divider, params);
    }

    @NonNull
    private String describeIdentity(@NonNull TerminowStore.Identity identity) {
        List<String> parts = new ArrayList<>();
        if (!identity.username.isEmpty()) parts.add(identity.username);
        if (!identity.password.isEmpty()) parts.add(mActivity.getString(R.string.terminow_identity_has_password));
        if (!identity.keyPath.isEmpty()) parts.add(identity.keyPath.substring(identity.keyPath.lastIndexOf('/') + 1));
        int hosts = getHostsUsingIdentity(identity).size();
        if (hosts > 0) parts.add(mActivity.getResources().getQuantityString(R.plurals.terminow_key_hosts, hosts, hosts));
        return parts.isEmpty() ? mActivity.getString(R.string.terminow_identity_empty) : TextUtils.join(" · ", parts);
    }

    @NonNull
    private List<TerminowStore.Host> getHostsUsingIdentity(@NonNull TerminowStore.Identity identity) {
        List<TerminowStore.Host> hosts = new ArrayList<>();
        for (TerminowStore.Host host : mHosts) if (host.identity.equals(identity.id)) hosts.add(host);
        return hosts;
    }

    /** Pick an identity for a host form, or make a new one. */
    private void showIdentityPicker(@NonNull java.util.function.Consumer<String> onPicked) {
        List<TerminowStore.Identity> identities = mStore.getIdentities();
        sortKeychain(identities, new ArrayList<>(), "az");
        List<CharSequence> names = new ArrayList<>();
        for (TerminowStore.Identity identity : identities) names.add(identity.getTitle() + "  ·  " + describeIdentity(identity));
        names.add(mActivity.getString(R.string.terminow_identity_new));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_host_identity_field)
            .setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                if (which < identities.size()) onPicked.accept(identities.get(which).id);
                else showIdentityDialog(null, onPicked);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // Certificates

    @NonNull
    private String describeCertificateShort(@NonNull TerminowSshWire.Certificate certificate) {
        long now = System.currentTimeMillis() / 1000;
        if (!certificate.isValidAt(now))
            return mActivity.getString(Long.compareUnsigned(now, certificate.validAfter) < 0 ? R.string.terminow_cert_not_yet : R.string.terminow_cert_expired);
        if (!certificate.expires()) return mActivity.getString(R.string.terminow_cert_valid_forever);
        return mActivity.getString(R.string.terminow_cert_valid_until,
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(certificate.validBefore * 1000)));
    }

    private void showCertificate(@NonNull KeyItem key, @NonNull TerminowSshWire.Certificate certificate) {
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        String message = mActivity.getString(R.string.terminow_cert_details,
            certificate.keyId,
            certificate.principals.isEmpty() ? mActivity.getString(R.string.terminow_cert_any_principal) : TextUtils.join(", ", certificate.principals),
            certificate.validAfter == 0 ? "—" : format.format(new java.util.Date(certificate.validAfter * 1000)),
            !certificate.expires() ? mActivity.getString(R.string.terminow_cert_valid_forever) : format.format(new java.util.Date(certificate.validBefore * 1000)),
            Long.toUnsignedString(certificate.serial),
            mActivity.getString(certificate.certType == 1 ? R.string.terminow_cert_user : R.string.terminow_cert_host),
            describeCertificateShort(certificate));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_cert_title, key.getTitle()))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.terminow_cert_replace, (d, w) -> showCertificateSource(key))
            .setNegativeButton(R.string.terminow_cert_remove, (d, w) -> {
                //noinspection ResultOfMethodCallIgnored
                key.certificateFile().delete();
                updateItems();
            })
            .show();
    }

    private void showCertificateSource(@NonNull KeyItem key) {
        if (key.readPublicBlob() == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_key_no_public), true);
            return;
        }
        CharSequence[] sources = {mActivity.getString(R.string.terminow_cert_paste), mActivity.getString(R.string.terminow_cert_import)};
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_cert_title, key.getTitle()))
            .setItems(sources, (dialog, which) -> {
                if (which == 0) showPasteCertificateDialog(key);
                else pickCertificateFile(key);
            })
            .show();
    }

    private void showPasteCertificateDialog(@NonNull KeyItem key) {
        LinearLayout form = createForm();
        EditText contentView = addField(form, R.string.terminow_cert_content,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        contentView.setMaxLines(6);
        contentView.setTextSize(12);
        ClipboardManager clipboardManager = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboardManager != null ? clipboardManager.getPrimaryClip() : null;
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(mActivity);
            if (text != null && text.toString().contains(TerminowSshWire.CERT_SUFFIX)) contentView.setText(text.toString().trim());
        }
        showFormDialog(R.string.terminow_cert_paste, form, () -> saveCertificate(key, contentView.getText().toString()));
    }

    private void pickCertificateFile(@NonNull KeyItem key) {
        mCertificateKey = key;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            mActivity.startActivityForResult(intent, REQUEST_IMPORT_CERTIFICATE);
        } catch (Exception e) {
            mCertificateKey = null;
            mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
        }
    }

    /**
     * Keep a certificate where ssh finds it for the key, only if it is a user certificate of
     * exactly this key.
     */
    private boolean saveCertificate(@NonNull KeyItem key, @NonNull String text) {
        TerminowSshWire.PublicKeyLine line = TerminowSshWire.parseLine(text.trim());
        TerminowSshWire.Certificate certificate = line != null ? TerminowSshWire.parseCertificate(line.blob) : null;
        if (certificate == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_cert_invalid, mActivity.getString(R.string.terminow_cert_not_certificate)), true);
            return false;
        }
        byte[] blob = key.readPublicBlob();
        if (blob == null || !Arrays.equals(blob, certificate.keyBlob)) {
            mActivity.showToast(mActivity.getString(R.string.terminow_cert_invalid, mActivity.getString(R.string.terminow_cert_other_key)), true);
            return false;
        }
        if (certificate.certType != 1) {
            mActivity.showToast(mActivity.getString(R.string.terminow_cert_invalid, mActivity.getString(R.string.terminow_cert_host_certificate)), true);
            return false;
        }
        File file = key.certificateFile();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            out.write((TerminowSshWire.publicKeyLine(line.blob, line.comment) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_cert_invalid, e.getMessage()), true);
            return false;
        }
        mActivity.showToast(mActivity.getString(R.string.terminow_cert_saved, certificate.keyId, describeCertificateShort(certificate)), false);
        updateItems();
        return true;
    }

    // Biometric and FIDO2 keys

    private void showNewBiometricKeyDialog() {
        if (!TerminowBiometricKeys.isSupported(mActivity)) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_key_biometric_new)
                .setMessage(R.string.terminow_key_biometric_unsupported)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        LinearLayout form = createForm();
        TextView about = new TextView(form.getContext());
        about.setText(R.string.terminow_key_biometric_about);
        about.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        about.setPadding(0, 0, 0, dp(8));
        form.addView(about);
        EditText nameView = addField(form, R.string.terminow_key_label, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameView.setText(mActivity.getString(R.string.terminow_key_biometric_default_name, android.os.Build.MODEL));
        showFormDialog(R.string.terminow_key_biometric_new, form, () -> {
            String name = nameView.getText().toString().trim().replaceAll("[\\r\\n]", " ");
            if (name.isEmpty() || name.length() > 100) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                return false;
            }
            new Thread(() -> {
                String error = null;
                String id = null;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) id = TerminowBiometricKeys.create(name);
                } catch (Exception e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to create a biometric key", e);
                    error = String.valueOf(e.getMessage());
                }
                String finalError = error;
                String finalId = id;
                mActivity.runOnUiThread(() -> {
                    if (finalId == null) {
                        mActivity.showToast(mActivity.getString(R.string.terminow_key_biometric_failed, String.valueOf(finalError)), true);
                        return;
                    }
                    mStore.setKeyLabel(TerminowBiometricKeys.publicFile(finalId).getName(), name);
                    updateItems();
                    setKeyTarget(TerminowBiometricKeys.publicFile(finalId).getName());
                    mActivity.showToast(mActivity.getString(R.string.terminow_key_biometric_created, name), false);
                });
            }, "TerminowBiometricKeyCreate").start();
            return true;
        });
    }

    private void showNewFidoKeyDialog() {
        LinearLayout form = createForm();
        TextView about = new TextView(form.getContext());
        about.setText(R.string.terminow_key_fido_about);
        about.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        about.setPadding(0, 0, 0, dp(8));
        form.addView(about);
        EditText nameView = addField(form, R.string.terminow_key_label, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        nameView.setText(R.string.terminow_key_fido_default_name);
        showFormDialog(R.string.terminow_key_fido_new, form, () -> {
            String name = nameView.getText().toString().trim().replaceAll("[\\r\\n]", " ");
            if (name.isEmpty() || name.length() > 100) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                return false;
            }
            TerminowFidoKeys.create(mActivity, name, (id, error) -> {
                if (id == null) {
                    if (error != null) mActivity.showToast(mActivity.getString(R.string.terminow_key_fido_failed, error), true);
                    return;
                }
                updateItems();
                setKeyTarget(TerminowFidoKeys.publicFile(id).getName());
                mActivity.showToast(mActivity.getString(R.string.terminow_key_biometric_created, name), false);
            });
            return true;
        });
    }

    private void showFormDialog(int titleRes, @NonNull View form, @NonNull FormSaver saver) {
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(titleRes)
            .setView(form)
            .setPositiveButton(R.string.terminow_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.show();
        // Set after showing so that the dialog is not dismissed if the form is invalid
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (saver.save())
                dialog.dismiss();
        });
    }

    @NonNull
    private LinearLayout createForm() {
        LinearLayout form = new LinearLayout(new ContextThemeWrapper(mActivity, R.style.TerminowAlertDialog));
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        return form;
    }

    @NonNull
    private EditText addField(@NonNull LinearLayout form, int hintRes, int inputType) {
        EditText field = new EditText(form.getContext());
        field.setHint(hintRes);
        field.setInputType(inputType);
        form.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return field;
    }

    private int dp(float dp) {
        return (int) ViewUtils.dpToPx(mActivity, dp);
    }

    /** The number of sessions that were opened for the host. */
    private int getSessionCount(@NonNull TerminowStore.Host host) {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return 0;

        int count = 0;
        for (TermuxSession termuxSession : service.getTermuxSessions()) {
            TerminalSession session = termuxSession.getTerminalSession();
            if (session != null && host.getTitle().equals(session.mSessionName))
                count++;
        }
        return count;
    }



    private class ItemsAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null)
                view = mActivity.getLayoutInflater().inflate(R.layout.item_terminow_host, parent, false);

            ImageView iconView = view.findViewById(R.id.terminow_item_icon);
            TextView titleView = view.findViewById(R.id.terminow_item_title);
            TextView subtitleView = view.findViewById(R.id.terminow_item_subtitle);
            TextView countView = view.findViewById(R.id.terminow_item_count);

            Object item = mItems.get(position);
            int iconRes;
            int iconColorRes;
            int sessionCount = 0;
            if (item instanceof Group) {
                String group = ((Group) item).name;
                int hostCount = getHostCount(group);
                iconRes = R.drawable.ic_terminow_group;
                iconColorRes = R.color.terminow_host_group;
                titleView.setText(group);
                subtitleView.setText(mActivity.getResources().getQuantityString(R.plurals.terminow_group_hosts, hostCount, hostCount));
            } else if (item == null) {
                iconRes = R.drawable.ic_terminow_terminal;
                iconColorRes = R.color.terminow_host_local;
                titleView.setText(R.string.terminow_local_terminal);
                subtitleView.setText(R.string.terminow_local_terminal_subtitle);
            } else if (item instanceof TerminowStore.Host) {
                TerminowStore.Host host = (TerminowStore.Host) item;
                // The badge of the protocol: the icon, its color and the first word
                iconRes = host.isRdp() ? R.drawable.ic_terminow_desktop : host.isWinrm() ? R.drawable.ic_terminow_terminal : R.drawable.ic_terminow_hosts;
                iconColorRes = host.isRdp() ? R.color.terminow_host_rdp : host.isWinrm() ? R.color.terminow_host_winrm :
                    host.mosh ? R.color.terminow_host_mosh : R.color.terminow_host_ssh;
                titleView.setText(host.getTitle());
                sessionCount = host.isTerminal() ? getSessionCount(host) : windowsSessions(host);
                String kind = host.isRdp() ? "rdp" : host.isWinrm() ? "winrm" : host.telnet ? "telnet" : host.mosh ? "mosh" : "ssh";
                if (sessionCount > 0)
                    subtitleView.setText(R.string.terminow_active);
                else
                    subtitleView.setText(kind + (host.username.isEmpty() ? "" : ", " + host.username) +
                        (host.tags.isEmpty() ? "" : ", " + TextUtils.join(", ", host.tags)));
            } else if (item instanceof TerminowStore.Identity) {
                TerminowStore.Identity identity = (TerminowStore.Identity) item;
                iconRes = R.drawable.ic_terminow_person;
                iconColorRes = R.color.terminow_host_ssh;
                titleView.setText(identity.getTitle());
                subtitleView.setText(describeIdentity(identity));
            } else if (item instanceof KeyItem) {
                KeyItem key = (KeyItem) item;
                iconRes = key.biometricId != null ? R.drawable.ic_terminow_lock : key.fidoId != null ? R.drawable.ic_terminow_sdcard : R.drawable.ic_terminow_key;
                iconColorRes = R.color.terminow_host_mosh;
                titleView.setText(key.getTitle());
                String keyType = getKeyType(key);
                String subtitle = mActivity.getString(R.string.terminow_key_type,
                    keyType != null ? keyType : mActivity.getString(R.string.terminow_key_type_unknown));
                if (key.biometricId != null) subtitle += " · " + mActivity.getString(R.string.terminow_key_biometric);
                if (key.fidoId != null) subtitle += " · " + mActivity.getString(R.string.terminow_key_fido);
                TerminowSshWire.Certificate certificate = key.readCertificate();
                if (certificate != null) subtitle += " · " + describeCertificateShort(certificate);
                int hostCount = getHostsUsingKey(key).size();
                if (hostCount > 0)
                    subtitle += " · " + mActivity.getResources().getQuantityString(R.plurals.terminow_key_hosts, hostCount, hostCount);
                subtitleView.setText(subtitle);
            } else if (item instanceof TerminowStore.Forward) {
                TerminowStore.Forward forward = (TerminowStore.Forward) item;
                iconRes = R.drawable.ic_terminow_forward;
                iconColorRes = R.color.terminow_host_mosh;
                titleView.setText(forward.getTitle());
                subtitleView.setText(forward.getOption() + ", " + forward.host);
            } else if (item instanceof KnownHost) {
                KnownHost knownHost = (KnownHost) item;
                iconRes = R.drawable.ic_terminow_known_hosts;
                iconColorRes = R.color.terminow_host_group;
                titleView.setText(knownHost.hosts);
                subtitleView.setText(knownHost.keyType);
            } else if (item instanceof TerminowStore.Log) {
                TerminowStore.Log log = (TerminowStore.Log) item;
                iconRes = R.drawable.ic_terminow_logs;
                iconColorRes = R.color.terminow_host_group;
                titleView.setText(log.title);
                subtitleView.setText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(log.time)) + "  " + log.command);
            } else {
                TerminowStore.Snippet snippet = (TerminowStore.Snippet) item;
                iconRes = R.drawable.ic_terminow_snippets;
                iconColorRes = R.color.terminow_host_mosh;
                titleView.setText(snippet.getTitle());
                subtitleView.setText(snippet.command);
            }

            Integer hostColor = null;
            if (item instanceof TerminowStore.Host) {
                TerminowStore.Host h = (TerminowStore.Host) item;
                if (!h.icon.isEmpty()) iconRes = hostIconDrawable(h.icon, iconRes);
                hostColor = parseHostColor(h.iconColor);
            }
            iconView.setImageResource(iconRes);
            int iconColorValue = hostColor != null ? hostColor : ContextCompat.getColor(mActivity, iconColorRes);
            iconView.getBackground().mutate().setColorFilter(iconColorValue, PorterDuff.Mode.SRC_IN);
            countView.setText(sessionCount > 0 ? String.valueOf(sessionCount) : "");
            return view;
        }

    }

}
