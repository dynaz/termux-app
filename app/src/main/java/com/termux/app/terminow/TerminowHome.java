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

    private static final Pattern KEY_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final int KEY_FILE_MAX_SIZE = 64 * 1024;
    /** The request code for the file picker to import a key. */
    private static final int REQUEST_IMPORT_KEY = 2001;

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
    private final View mExplorerView;
    private final TerminowExplorer mExplorer;
    private final TerminowLauncher mLauncher;
    private final View mHeaderView;
    private final View mGridView;
    private final View mFabView;
    private final View mSettingsScrollView;
    private final TerminowSettings mSettings;
    private final EditText mSearchView;
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
            public void onChanged() {
                if (mExplorer != null) mExplorer.refresh();
            }
        });
        mLauncher.setLinux(mLinuxUi);
        mLauncherButton = activity.findViewById(R.id.terminow_launcher_button);
        mLauncherButton.setOnClickListener(v -> mLauncher.show());
        mTerminalToolbar = activity.findViewById(R.id.terminow_terminal_toolbar);
        mLauncher.bindToolbar(mTerminalToolbar);
        mHomeTitle = activity.findViewById(R.id.terminow_home_title);
        mVaultSyncIcon = activity.findViewById(R.id.terminow_vault_sync_icon);
        mVaultButton = activity.findViewById(R.id.terminow_vault_button);
        mVaultsUi = new TerminowVaultsUi(activity, mStore, this::reloadVault);
        mVaultButton.setOnClickListener(v -> mVaultsUi.showSwitcher(v));
        // The cloud says whether the vault is synced, a tap says why and offers to sync
        mVaultSyncIcon.setOnClickListener(v -> mVaultsUi.showStatus());
        mVaultSyncIcon.setContentDescription(activity.getString(R.string.terminow_vault_status));
        mKeyPanel = new TerminowKeyPanel(activity, mStore, activity.findViewById(R.id.terminow_key_panel));
        activity.findViewById(R.id.terminow_toolbar_keys).setOnClickListener(v -> mKeyPanel.toggle());
        mRailHosts = activity.findViewById(R.id.terminow_rail_hosts);
        mRailSnippets = activity.findViewById(R.id.terminow_rail_snippets);
        mSearchView = activity.findViewById(R.id.terminow_search);
        mSectionTitleView = activity.findViewById(R.id.terminow_section_title);
        mEmptyView = activity.findViewById(R.id.terminow_empty);

        mHomeButton.setOnClickListener(v -> show());

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
        mRailSettings = activity.findViewById(R.id.terminow_rail_settings);
        mRailSettings.setOnClickListener(v -> setPage(PAGE_SETTINGS));
        mHeaderView = activity.findViewById(R.id.terminow_header);
        mGridView = activity.findViewById(R.id.terminow_grid);
        mFabView = activity.findViewById(R.id.terminow_fab);
        mSettingsScrollView = activity.findViewById(R.id.terminow_settings_scroll);
        mSettings = new TerminowSettings(activity, mStore, activity.findViewById(R.id.terminow_settings));
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
        // Not when the app was just installed, or the version was not changed
        if (lastVersionCode == 0 || lastVersionCode == BuildConfig.VERSION_CODE) return;
        mStore.addNotification(mActivity.getString(R.string.terminow_notification_updated,
            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"),
            mActivity.getString(R.string.terminow_notification_updated_text));
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
        mTerminalToolbar.setVisibility(!homeVisible && hasSessions ? View.VISIBLE : View.GONE);
        mLauncherButton.setVisibility(homeVisible || !hasSessions ? View.VISIBLE : View.GONE);
        mLauncher.updateToolbar(null);
        mKeyPanel.update(!homeVisible && hasSessions);
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



    private void setPage(int page) {
        if (mPage == PAGE_FILES && page != PAGE_FILES) mExplorer.onHidden();
        mPage = page;
        setRailItemSelected(mRailHosts, page == PAGE_HOSTS);
        setRailItemSelected(mRailSnippets, page == PAGE_SNIPPETS);
        setRailItemSelected(mRailSettings, page == PAGE_SETTINGS);
        setRailItemSelected(mRailKeychain, page == PAGE_KEYCHAIN);
        setRailItemSelected(mRailForwarding, page == PAGE_FORWARDING);
        setRailItemSelected(mRailKnownHosts, page == PAGE_KNOWN_HOSTS);
        setRailItemSelected(mRailLogs, page == PAGE_LOGS);
        setRailItemSelected(mRailMcp, page == PAGE_MCP);
        setRailItemSelected(mRailFiles, page == PAGE_FILES);
        mActionsView.setVisibility(page == PAGE_HOSTS ? View.VISIBLE : View.GONE);
        mSearchView.setHint(page == PAGE_KEYCHAIN ? R.string.terminow_search_keys_hint :
            page == PAGE_MCP ? R.string.terminow_mcp_search_hint : R.string.terminow_search_hint);

        // The settings are shown instead of the list of hosts or snippets and what belongs to it
        boolean settings = page == PAGE_SETTINGS;
        mHeaderView.setVisibility(settings ? View.GONE : View.VISIBLE);
        mSectionTitleView.setVisibility(settings ? View.GONE : View.VISIBLE);
        mGridView.setVisibility(settings || page == PAGE_MCP ? View.GONE : View.VISIBLE);
        mMcpScrollView.setVisibility(page == PAGE_MCP ? View.VISIBLE : View.GONE);
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
        // Known hosts and logs are only added by connecting to hosts
        mFabView.setVisibility(settings || page == PAGE_KNOWN_HOSTS || page == PAGE_LOGS ? View.GONE : View.VISIBLE);
        mSettingsScrollView.setVisibility(settings ? View.VISIBLE : View.GONE);
        if (settings)
            mSettings.update();

        updateItems();
    }

    /** Go back to all hosts from a group. Returns {@code false} if there was nothing to go back from. */
    public boolean onBackPressed() {
        if (isVisible() && mPage == PAGE_FILES) return mExplorer.onBackPressed();
        if (!isVisible() || mPage != PAGE_HOSTS || mCurrentGroup == null) return false;
        mCurrentGroup = null;
        updateItems();
        return true;
    }

    private void updateSectionTitle() {
        if (mPage == PAGE_KEYCHAIN)
            mSectionTitleView.setText(R.string.terminow_ssh_keys);
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

        if (mPage == PAGE_SETTINGS || mPage == PAGE_FILES) {
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
            for (KeyItem key : getKeys()) {
                if (query.isEmpty() || key.name.toLowerCase(Locale.ROOT).contains(query) ||
                    key.label.toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(key);
            }
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
        } else if (item instanceof KeyItem) {
            showKeyActions((KeyItem) item);
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

        if (item instanceof KeyItem) {
            showKeyActions((KeyItem) item);
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

        CharSequence[] actions = {mActivity.getString(R.string.terminow_edit), mActivity.getString(R.string.terminow_delete)};
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

        addMenuItem(menu, popup, R.drawable.ic_terminow_hosts, R.string.terminow_connect, () -> showConnectDialog(false));
        addMenuItem(menu, popup, R.drawable.ic_terminow_group, R.string.terminow_connect_sftp, () -> showConnectDialog(true));
        addMenuItem(menu, popup, R.drawable.ic_terminow_terminal, R.string.terminow_local_terminal, this::openLocalTerminal);
        // Launch profiles: Ubuntu, Claude Code in the chosen project, saved profiles and a new one
        addMenuItem(menu, popup, R.drawable.ic_terminow_hosts, mActivity.getString(R.string.terminow_tools_ubuntu_terminal), mLauncher::openUbuntuTerminal);
        addMenuItem(menu, popup, R.drawable.ic_terminow_launch, mLauncher.getClaudeMenuTitle(), mLauncher::openClaudeForProject);
        List<TerminowLaunchProfile> profiles = mStore.getLaunchProfiles();
        for (int i = 0; i < Math.min(4, profiles.size()); i++) {
            TerminowLaunchProfile profile = profiles.get(i);
            addMenuItem(menu, popup, R.drawable.ic_terminow_snippets, profile.icon + "  " + profile.name, () -> mLauncher.launchProfile(profile));
        }
        addMenuItem(menu, popup, R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_profiles_custom),
            () -> mLauncher.getProfiles().edit(null, TerminowLaunchPlan.TOOL_CUSTOM));
        if (!profiles.isEmpty())
            addMenuItem(menu, popup, R.drawable.ic_terminow_more, mActivity.getString(R.string.terminow_profiles_manage), () -> mLauncher.getProfiles().showManager());
        // Shared storage is reached with the system file picker, the app has no storage permission
        addMenuItem(menu, popup, R.drawable.ic_terminow_import, R.string.terminow_files_import, () -> mFiles.importFiles(getCurrentSftpSession()));
        addMenuItem(menu, popup, R.drawable.ic_terminow_group, R.string.terminow_files_export, mFiles::exportFile);

        popup.showAsDropDown(anchor, 0, dp(4));
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

    /** The session that is shown if it is an sftp session that is still running, otherwise {@code null}. */
    @Nullable
    private TerminalSession getCurrentSftpSession() {
        TerminalSession session = mActivity.getCurrentSession();
        return session != null && session.isRunning() && mSftpSessions.contains(session) ? session : null;
    }

    /** Start a session to transfer files to and from a host with sftp. */
    private void connectSftp(@NonNull TerminowStore.Host host) {
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
        mLauncher.track(session, new TerminowLauncher.SessionInfo(null, host.getTitle(), "sftp", "", ""));
        String command = host.getSftpCommand(route.jump, route.proxyConnector, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        mStore.addLog(title, command);
        writeWhenStarted(session, command + "\r", 0);
        writePasswordsWhenAsked(session, getPasswordPrompts(host, route.jump), 0);
        watchConnection(session, host, true, title, 0);
    }

    /** The hosts and modes that a connection is being started to, to not start another one at the same time. */
    private final java.util.Set<String> mConnectingHosts = new java.util.HashSet<>();

    /** How long a connection is watched for a failure or for being made. */
    private static final int CONNECTION_CHECK_COUNT = 180;
    private static final int CONNECTION_CHECK_DELAY_MS = 500;
    /** A connection that did not fail for this long and does not wait at a prompt is taken as made. */
    private static final int CONNECTION_MADE_AFTER_CHECKS = 16;
    private static final Pattern CONNECTION_AUTH_FAILED_PATTERN = Pattern.compile(
        "Permission denied \\(|Too many authentication failures|Authentication failed");
    private static final Pattern CONNECTION_TIMEOUT_PATTERN = Pattern.compile(
        "Connection timed out|Operation timed out|timed out during banner exchange", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTION_FAILED_PATTERN = Pattern.compile(
        "Could not resolve hostname|Connection refused|No route to host|Network is unreachable|" +
            "Host key verification failed|Connection closed by|Connection reset by|kex_exchange_identification");
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
                                 @NonNull String title, int check) {
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
        if (transcript != null) {
            String failure = null;
            java.util.regex.Matcher matcher;
            if ((matcher = CONNECTION_AUTH_FAILED_PATTERN.matcher(transcript)).find())
                failure = mActivity.getString(R.string.terminow_connection_auth_failed);
            else if ((matcher = CONNECTION_TIMEOUT_PATTERN.matcher(transcript)).find())
                failure = mActivity.getString(R.string.terminow_connection_timeout);
            else if ((matcher = CONNECTION_FAILED_PATTERN.matcher(transcript)).find())
                failure = matcher.group();
            if (failure != null) {
                finish.run();
                showConnectionFailed(session, host, sftp, failure);
                return;
            }

            String lastLine = transcript.substring(transcript.lastIndexOf('\n') + 1);
            boolean waitingAtPrompt = CONNECTION_PROMPT_PATTERN.matcher(lastLine).find();
            if ((sftp && transcript.contains("sftp>")) || (!waitingAtPrompt && check >= CONNECTION_MADE_AFTER_CHECKS)) {
                mStore.addRecentConnection(host, sftp);
                TerminowStore.Snippet snippet = sftp ? null : mStore.getSnippet(host.startupSnippet);
                if (snippet != null && session.isRunning())
                    session.write(snippet.command + "\r");
                finish.run();
                return;
            }
        }

        if (check >= CONNECTION_CHECK_COUNT) {
            finish.run();
            return;
        }
        mHandler.postDelayed(() -> watchConnection(session, host, sftp, title, check + 1), CONNECTION_CHECK_DELAY_MS);
    }

    private void showConnectionFailed(@NonNull TerminalSession session, @NonNull TerminowStore.Host host, boolean sftp,
                                      @NonNull String reason) {
        if (!mActivity.isVisible()) return;
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_connection_failed, host.getTitle()))
            .setMessage(reason)
            .setPositiveButton(R.string.terminow_retry, (dialog, which) -> {
                // The tab of the failed attempt is replaced by the new one
                mActivity.getTermuxTerminalSessionClient().closeSession(session);
                if (sftp)
                    connectSftp(host);
                else
                    connect(host);
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
        if (session == null) return null;
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(session.getTerminalSession());
        hide();
        return session.getTerminalSession();
    }

    /** The vault was switched or synced: show its hosts, groups, snippets and forwards. */
    public void reloadVault() {
        mHosts = mStore.getHosts();
        mSnippets = mStore.getSnippets();
        mGroups = mStore.getGroups();
        mForwards = mStore.getForwards();
        if (mCurrentGroup != null && !mGroups.contains(mCurrentGroup))
            mCurrentGroup = null;
        refresh();
        if (isVisible()) updateItems();
    }

    /** The activity was resumed, for example after a screen of Android settings. */
    public void onResume() {
        mExplorer.onResume();
        mVaultsUi.onResume();
        // A purchase whose check failed, or a renewal or expiry that the server found, is picked up
        mSettings.recoverPurchases(this::updateItems);
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
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(session.getTerminalSession());
        hide();
    }

    /**
     * Open a new Local Terminal with the default launch profile of the settings. The TermiNow
     * shell gets its startup command once, written to its own new shell before the user types;
     * other profiles start their program directly. Nothing runs again when the tab is shown again
     * or the activity is recreated.
     */
    private void openLocalTerminal() {
        TermuxService service = mActivity.getTermuxService();
        if (service == null) return;
        if (mLauncher.launchDefault(service.getTermuxSessionsSize() + 1)) return;

        TerminalSession session = addSession(null);
        if (session == null) return;

        String tmuxName = "local" + (service.getIndexOfSession(session) + 1);
        String command = getLocalStartupCommand(tmuxName);
        if (command != null)
            writeWhenStarted(session, command + "\r", 0);
        mLauncher.track(session, new TerminowLauncher.SessionInfo(TerminowMcpTargets.TARGET_TERMINOW, "Local", "bash",
            TermuxConstants.TERMUX_HOME_DIR_PATH, command != null && command.contains("tmux") ? tmuxName : ""));
    }

    /**
     * Get the first command of the fresh shell of a new local terminal, {@code null} if there is
     * none. Each local terminal has its own tmux session, which is attached to if it is still
     * running, keeping its folders and programs, or else is created in the home directory. tmux
     * is not started inside another tmux session.
     */
    @Nullable
    private String getLocalStartupCommand(@NonNull String tmuxSessionName) {
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
            " else tmux new-session -s " + tmuxSessionName + " -c \"$HOME\"" + showFolderKeys + "; fi; fi";
    }

    private void connect(@NonNull TerminowStore.Host host) {
        if (!startConnecting(host, false)) return;
        TerminalSession session = addSession(host.getTitle());
        if (session == null) {
            mConnectingHosts.remove(host.getKey() + ":ssh");
            return;
        }

        Route route = prepareRoute(host);
        if (route == null) {
            mConnectingHosts.remove(host.getKey() + ":ssh");
            return;
        }
        applySessionOptions(session, host);
        mLauncher.track(session, new TerminowLauncher.SessionInfo(null, host.getTitle(), host.telnet ? "telnet" : "ssh", "", ""));
        String command = host.getCommand(route.jump, route.proxyConnector, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        mStore.addLog(host.getTitle(), command);
        writeWhenStarted(session, command + "\r", 0);
        writePasswordsWhenAsked(session, getPasswordPrompts(host, route.jump), 0);
        watchConnection(session, host, false, host.getTitle(), 0);
    }

    /** How a connection reaches its host: the jump host and the proxy connector, if it uses them. */
    private static class Route {
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
        if (host.telnet) return route;
        if (!host.jumpHost.isEmpty()) {
            route.jump = TerminowStore.findHost(mHosts, host.jumpHost);
            if (route.jump == null || route.jump.telnet) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_jump_missing), true);
                return null;
            }
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
        String command = host.getForwardCommand(forward, route.jump, route.proxyConnector, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        mStore.addLog(forward.getTitle(), command);
        writeWhenStarted(session, command + "\r", 0);
        writePasswordsWhenAsked(session, getPasswordPrompts(host, route.jump), 0);
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
        EditText snippetView = dialog.findViewById(R.id.terminow_host_snippet);
        CheckBox agentView = dialog.findViewById(R.id.terminow_host_agent);
        EditText jumpView = dialog.findViewById(R.id.terminow_host_jump);
        EditText proxyView = dialog.findViewById(R.id.terminow_host_proxy);
        LinearLayout envList = dialog.findViewById(R.id.terminow_host_env);
        TextView schemeView = dialog.findViewById(R.id.terminow_host_scheme);
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
            draft.jumpHost = existingHost.jumpHost;
            draft.proxyType = existingHost.proxyType;
            draft.proxyHost = existingHost.proxyHost;
            draft.proxyPort = existingHost.proxyPort;
            draft.colorScheme = existingHost.colorScheme;
            for (Map.Entry<String, String> variable : existingHost.env.entrySet())
                addEnvRow(envList, variable.getKey(), variable.getValue());
        }

        // Tags
        Runnable showTags = () -> tagsView.setText(TextUtils.join(", ", draft.tags));
        showTags.run();
        View.OnClickListener editTags = v -> showTagsEditor(draft.tags, showTags);
        tagsView.setOnClickListener(editTags);
        dialog.findViewById(R.id.terminow_host_tags_button).setOnClickListener(editTags);

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
        moshView.setOnCheckedChangeListener((button, checked) -> {
            if (checked) sshView.setChecked(true);
        });
        telnetView.setOnCheckedChangeListener((button, checked) -> {
            if (checked) sshView.setChecked(false);
            showProtocol.run();
        });
        showProtocol.run();
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
        dialog.findViewById(R.id.terminow_host_charset_row).setOnClickListener(v ->
            mActivity.showToast(mActivity.getString(R.string.terminow_host_charset_note), true));

        dialog.findViewById(R.id.terminow_host_close).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.terminow_host_save).setOnClickListener(v -> {
            String address = addressView.getText().toString().trim();
            String username = usernameView.getText().toString().trim();
            String keyPath = keyView.getText().toString().trim();
            int port = parsePort(portView.getText().toString(), 22);
            int telnetPort = parsePort(telnetPortView.getText().toString(), 23);

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
            if (!sshView.isChecked() && !telnetView.isChecked()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_no_protocol), true);
                return;
            }

            TerminowStore.Host host = new TerminowStore.Host();
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
            host.telnetPort = telnetPort;
            host.tags.addAll(draft.tags);
            host.backspaceSendsCtrlH = backspaceView.isChecked();
            host.agentForwarding = !host.telnet && agentView.isChecked();
            host.jumpHost = host.telnet ? "" : draft.jumpHost;
            host.proxyType = host.telnet ? "" : draft.proxyType;
            host.proxyHost = host.proxyType.isEmpty() ? "" : draft.proxyHost;
            host.proxyPort = host.proxyType.isEmpty() ? 0 : draft.proxyPort;
            host.colorScheme = draft.colorScheme;
            if (!readEnvRows(envList, host.env)) {
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
        names.add(mActivity.getString(R.string.terminow_key_import));
        names.add(mActivity.getString(R.string.terminow_key_paste));
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
                if (action == 0) pickKeyFile();
                else if (action == 1) showPasteKeyDialog();
                else showNewKeyDialog();
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
        if (!configFile.isFile()) {
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_import_title)
                .setMessage(R.string.terminow_import_no_file)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_import_title)
            .setMessage(R.string.terminow_import_message)
            .setPositiveButton(R.string.terminow_import_confirm, (dialog, which) -> importHosts(configFile))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void importHosts(@NonNull File configFile) {
        List<TerminowStore.Host> importedHosts;
        try {
            importedHosts = TerminowStore.readSshConfigHosts(configFile);
        } catch (IOException e) {
            mActivity.showToast(e.getMessage() != null ? e.getMessage() : e.toString(), true);
            return;
        }

        int count = 0;
        for (TerminowStore.Host importedHost : importedHosts) {
            boolean exists = false;
            for (TerminowStore.Host host : mHosts) {
                if (host.address.equals(importedHost.address) && host.port == importedHost.port &&
                    host.username.equals(importedHost.username)) {
                    exists = true;
                    break;
                }
            }
            if (exists) continue;

            // Import into the group that is being shown
            importedHost.group = mCurrentGroup != null ? mCurrentGroup : "";
            mHosts.add(importedHost);
            count++;
        }

        if (count > 0) {
            mStore.setHosts(mHosts);
            updateItems();
            mActivity.showToast(mActivity.getResources().getQuantityString(R.plurals.terminow_import_done, count, count), true);
        } else {
            mActivity.showToast(mActivity.getString(R.string.terminow_import_none), true);
        }
    }

    /** An item of the keychain page for a private key file in `~/.ssh`. */
    private static class KeyItem {
        final String name;
        final File file;
        final File publicFile;
        /** The name that the user gave the key, empty if none. */
        final String label;

        KeyItem(@NonNull File file, @NonNull String label) {
            this.name = file.getName();
            this.file = file;
            this.publicFile = new File(file.getPath() + ".pub");
            this.label = label;
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

    private void showKeyActions(@NonNull KeyItem key) {
        List<TerminowStore.Host> hosts = getHostsUsingKey(key);
        CharSequence[] actions = {
            mActivity.getString(R.string.terminow_key_rename),
            mActivity.getString(R.string.terminow_key_copy_public),
            mActivity.getResources().getQuantityString(R.plurals.terminow_key_used_by, hosts.size(), hosts.size()),
            mActivity.getString(R.string.terminow_delete)};
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog).setTitle(key.getTitle()).setItems(actions, (dialog, which) -> {
            if (which == 0) {
                LinearLayout form = createForm();
                EditText labelView = addField(form, R.string.terminow_key_label, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                labelView.setText(key.label);
                showFormDialog(R.string.terminow_key_rename, form, () -> {
                    mStore.setKeyLabel(key.name, labelView.getText().toString().trim());
                    updateItems();
                    return true;
                });
            } else if (which == 1) {
                String publicKey = key.publicFile.isFile() ? readFirstLine(key.publicFile) : null;
                if (publicKey == null || publicKey.isEmpty()) {
                    mActivity.showToast(mActivity.getString(R.string.terminow_key_no_public), true);
                    return;
                }
                ClipboardManager clipboardManager = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboardManager != null) {
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(key.publicFile.getName(), publicKey));
                    mActivity.showToast(mActivity.getString(R.string.terminow_key_copied), false);
                }
            } else if (which == 2) {
                showKeyHosts(key, hosts);
            } else {
                String message = mActivity.getString(R.string.terminow_key_delete_confirm, key.getTitle());
                if (!hosts.isEmpty())
                    message += "\n\n" + mActivity.getResources().getQuantityString(R.plurals.terminow_key_delete_hosts, hosts.size(), hosts.size());
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setMessage(message)
                    .setPositiveButton(R.string.terminow_delete, (confirmDialog, confirmWhich) -> {
                        if (!key.file.delete())
                            Logger.logError(LOG_TAG, "Failed to delete \"" + key.file + "\"");
                        if (key.publicFile.isFile() && !key.publicFile.delete())
                            Logger.logError(LOG_TAG, "Failed to delete \"" + key.publicFile + "\"");
                        mStore.setKeyLabel(key.name, "");
                        // The hosts would fail to log in with a key that does not exist anymore
                        for (TerminowStore.Host host : hosts)
                            host.keyPath = "";
                        if (!hosts.isEmpty()) mStore.setHosts(mHosts);
                        updateItems();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            }
        }).show();
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

    /** Show the menu to add a key above the add button, like the keychain menu of Termius. */
    private void showKeyMenu(@NonNull View anchor) {
        LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(R.drawable.terminow_menu);
        menu.setPadding(0, dp(10), 0, dp(10));

        PopupWindow popup = new PopupWindow(menu, dp(300), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        addMenuItem(menu, popup, R.drawable.ic_terminow_paste, R.string.terminow_key_paste, this::showPasteKeyDialog);
        addMenuItem(menu, popup, R.drawable.ic_terminow_import, R.string.terminow_key_import, this::pickKeyFile);
        addMenuItem(menu, popup, R.drawable.ic_terminow_key, R.string.terminow_key_generate, this::showNewKeyDialog);

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
        return writeKey(name, content, null);
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

    /** Show a dialog to save a private key that is pasted from the clipboard. */
    private void showPasteKeyDialog() {
        LinearLayout form = createForm();
        EditText nameView = addField(form, R.string.terminow_key_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText contentView = addField(form, R.string.terminow_key_content,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        contentView.setMaxLines(6);
        contentView.setTextSize(12);
        nameView.setText(getNewKeyName("id_key"));

        // Fill in the key if it was copied already
        ClipboardManager clipboardManager = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboardManager != null ? clipboardManager.getPrimaryClip() : null;
        if (clip != null && clip.getItemCount() > 0) {
            CharSequence text = clip.getItemAt(0).coerceToText(mActivity);
            if (text != null && ((text.toString().trim().startsWith("-----BEGIN ") && text.toString().contains("PRIVATE KEY-----")) ||
                TerminowPpk.isPpk(text.toString().trim())))
                contentView.setText(text.toString().trim());
        }

        showFormDialog(R.string.terminow_key_paste, form, () -> {
            String name = nameView.getText().toString().trim();
            if (!isValidNewKeyName(name)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                return false;
            }
            return saveKey(name, contentView.getText().toString());
        });
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
        if (mFiles.onActivityResult(requestCode, resultCode, data)) return true;
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
            saveKey(getNewKeyName(fileName.substring(fileName.lastIndexOf('/') + 1)), content);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to import key from \"" + uri + "\"", e);
            mActivity.showToast(mActivity.getString(R.string.terminow_key_import_failed, e.getMessage()), true);
        }
        return true;
    }

    /** Ask for the file name of a new key and generate it with `ssh-keygen` in a local terminal. */
    private void showNewKeyDialog() {
        LinearLayout form = createForm();
        EditText nameView = addField(form, R.string.terminow_key_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (!new File(getSshDir(), "id_ed25519").exists())
            nameView.setText("id_ed25519");

        showFormDialog(R.string.terminow_key_new, form, () -> {
            String name = nameView.getText().toString().trim();
            if (!isValidNewKeyName(name)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                return false;
            }

            // ssh-keygen asks for the passphrase of the key in the terminal
            TerminalSession session = addSession(null);
            if (session != null)
                writeWhenStarted(session, "mkdir -p ~/.ssh && chmod 700 ~/.ssh && ssh-keygen -t ed25519 -f ~/.ssh/" + name + "\r", 0);
            // The key is created in the terminal, with the path that the host will use
            setKeyTarget(name);
            return true;
        });
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
            if (existingSnippet == null)
                mSnippets.add(snippet);
            mStore.setSnippets(mSnippets);
            updateItems();
            return true;
        });
    }

    private interface FormSaver {
        /** Save the form, returns {@code false} if the form is invalid and the dialog should stay open. */
        boolean save();
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
                iconRes = R.drawable.ic_terminow_hosts;
                iconColorRes = host.mosh ? R.color.terminow_host_mosh : R.color.terminow_host_ssh;
                titleView.setText(host.getTitle());
                sessionCount = getSessionCount(host);
                if (sessionCount > 0)
                    subtitleView.setText(R.string.terminow_active);
                else
                    subtitleView.setText((host.telnet ? "telnet" : host.mosh ? "mosh" : "ssh") + (host.username.isEmpty() ? "" : ", " + host.username) +
                        (host.tags.isEmpty() ? "" : ", " + TextUtils.join(", ", host.tags)));
            } else if (item instanceof KeyItem) {
                KeyItem key = (KeyItem) item;
                iconRes = R.drawable.ic_terminow_key;
                iconColorRes = R.color.terminow_host_mosh;
                titleView.setText(key.getTitle());
                String keyType = getKeyType(key);
                String subtitle = mActivity.getString(R.string.terminow_key_type,
                    keyType != null ? keyType : mActivity.getString(R.string.terminow_key_type_unknown));
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

            iconView.setImageResource(iconRes);
            iconView.getBackground().mutate().setColorFilter(ContextCompat.getColor(mActivity, iconColorRes), PorterDuff.Mode.SRC_IN);
            countView.setText(sessionCount > 0 ? String.valueOf(sessionCount) : "");
            return view;
        }

    }

}
