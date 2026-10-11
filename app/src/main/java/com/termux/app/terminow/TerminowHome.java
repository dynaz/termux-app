package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PorterDuff;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
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

    private static final Pattern KEY_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");

    /** How long to wait for the shell of a new session to start before giving up on sending it a command. */
    private static final int WRITE_RETRY_COUNT = 50;
    private static final int WRITE_RETRY_DELAY_MS = 100;

    /** How long to wait for the password prompt of a host after connecting to it. */
    private static final int PASSWORD_PROMPT_CHECK_COUNT = 200;
    private static final int PASSWORD_PROMPT_CHECK_DELAY_MS = 300;
    /** The line that ssh waits at for the password of a host, like `root@host's password: `. */
    private static final Pattern PASSWORD_PROMPT_PATTERN = Pattern.compile("'s password:\\s*$", Pattern.CASE_INSENSITIVE);

    /** The part of the screen width that the host form takes, like the Termius host panel. */
    private static final float HOST_PANEL_SCREEN_WIDTH_FRACTION = 0.36f;
    private static final int HOST_PANEL_MIN_WIDTH_DP = 340;
    /** The host form takes the whole width of screens narrower than this. */
    private static final int HOST_PANEL_MIN_SCREEN_WIDTH_DP = 600;

    private static final String LOG_TAG = "TerminowHome";

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final View mHomeView;
    private final View mTabBar;
    private final View mHomeButton;
    private final HorizontalScrollView mTabsScrollView;
    private final LinearLayout mTabsView;
    private final View mRailHosts;
    private final View mRailSnippets;
    private final View mRailSettings;
    private final View mRailKeychain;
    private final View mRailForwarding;
    private final View mRailKnownHosts;
    private final View mRailLogs;
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

        mHomeView = activity.findViewById(R.id.terminow_home);
        mTabBar = activity.findViewById(R.id.terminow_tab_bar);
        mHomeButton = activity.findViewById(R.id.terminow_home_button);
        mTabsScrollView = activity.findViewById(R.id.terminow_tabs_scroll);
        mTabsView = activity.findViewById(R.id.terminow_tabs);
        mRailHosts = activity.findViewById(R.id.terminow_rail_hosts);
        mRailSnippets = activity.findViewById(R.id.terminow_rail_snippets);
        mSearchView = activity.findViewById(R.id.terminow_search);
        mSectionTitleView = activity.findViewById(R.id.terminow_section_title);
        mEmptyView = activity.findViewById(R.id.terminow_empty);

        mHomeButton.setOnClickListener(v -> show());
        activity.findViewById(R.id.terminow_new_tab).setOnClickListener(v -> openLocalTerminal());

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
        mRailSettings = activity.findViewById(R.id.terminow_rail_settings);
        mRailSettings.setOnClickListener(v -> setPage(PAGE_SETTINGS));
        mHeaderView = activity.findViewById(R.id.terminow_header);
        mGridView = activity.findViewById(R.id.terminow_grid);
        mFabView = activity.findViewById(R.id.terminow_fab);
        mSettingsScrollView = activity.findViewById(R.id.terminow_settings_scroll);
        mSettings = new TerminowSettings(activity, mStore, activity.findViewById(R.id.terminow_settings));
        TerminowSettings.applyScreenshotsBlocked(activity, mStore);

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
                showNewKeyDialog();
            else if (mPage == PAGE_FORWARDING)
                showNewForwardDialog();
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
        boolean homeVisible = isVisible();
        mTabBar.setBackgroundColor(ContextCompat.getColor(mActivity,
            homeVisible ? R.color.terminow_bar_home : R.color.terminow_bar_terminal));
        mHomeButton.setBackgroundResource(homeVisible ? R.drawable.terminow_tab : R.drawable.terminow_tab_idle);
        updateTabs();
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

        new AlertDialog.Builder(mActivity)
            .setMessage(R.string.terminow_close_session)
            .setPositiveButton(android.R.string.ok, (dialog, which) ->
                mActivity.getTermuxTerminalSessionClient().closeSession(session))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }



    private void setPage(int page) {
        mPage = page;
        setRailItemSelected(mRailHosts, page == PAGE_HOSTS);
        setRailItemSelected(mRailSnippets, page == PAGE_SNIPPETS);
        setRailItemSelected(mRailSettings, page == PAGE_SETTINGS);
        setRailItemSelected(mRailKeychain, page == PAGE_KEYCHAIN);
        setRailItemSelected(mRailForwarding, page == PAGE_FORWARDING);
        setRailItemSelected(mRailKnownHosts, page == PAGE_KNOWN_HOSTS);
        setRailItemSelected(mRailLogs, page == PAGE_LOGS);
        mActionsView.setVisibility(page == PAGE_HOSTS ? View.VISIBLE : View.GONE);

        // The settings are shown instead of the list of hosts or snippets and what belongs to it
        boolean settings = page == PAGE_SETTINGS;
        mHeaderView.setVisibility(settings ? View.GONE : View.VISIBLE);
        mSectionTitleView.setVisibility(settings ? View.GONE : View.VISIBLE);
        mGridView.setVisibility(settings ? View.GONE : View.VISIBLE);
        // Known hosts and logs are only added by connecting to hosts
        mFabView.setVisibility(settings || page == PAGE_KNOWN_HOSTS || page == PAGE_LOGS ? View.GONE : View.VISIBLE);
        mSettingsScrollView.setVisibility(settings ? View.VISIBLE : View.GONE);
        if (settings)
            mSettings.update();

        updateItems();
    }

    /** Go back to all hosts from a group. Returns {@code false} if there was nothing to go back from. */
    public boolean onBackPressed() {
        if (!isVisible() || mPage != PAGE_HOSTS || mCurrentGroup == null) return false;
        mCurrentGroup = null;
        updateItems();
        return true;
    }

    private void updateSectionTitle() {
        if (mPage == PAGE_KEYCHAIN)
            mSectionTitleView.setText(R.string.terminow_keychain);
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
        ImageView iconView = (ImageView) group.getChildAt(0);
        iconView.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (selected)
            iconView.setBackgroundResource(R.drawable.terminow_rail_selected);
        else
            iconView.setBackground(null);
        ((TextView) group.getChildAt(1)).setTextColor(color);
    }

    private void updateItems() {
        String query = mSearchView.getText().toString().trim().toLowerCase(Locale.ROOT);

        if (mPage == PAGE_SETTINGS) {
            mEmptyView.setVisibility(View.GONE);
            return;
        }

        updateSectionTitle();
        mItems.clear();
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
                    host.address.toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(host);
            }
        } else if (mPage == PAGE_KEYCHAIN) {
            for (KeyItem key : getKeys()) {
                if (query.isEmpty() || key.name.toLowerCase(Locale.ROOT).contains(query))
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
            mEmptyView.setText(!query.isEmpty() ? R.string.terminow_no_results :
                mPage == PAGE_HOSTS ? R.string.terminow_group_empty :
                mPage == PAGE_KEYCHAIN ? R.string.terminow_no_keys :
                mPage == PAGE_FORWARDING ? R.string.terminow_no_forwards :
                mPage == PAGE_KNOWN_HOSTS ? R.string.terminow_no_known_hosts :
                mPage == PAGE_LOGS ? R.string.terminow_no_logs : R.string.terminow_no_snippets);
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
            new AlertDialog.Builder(mActivity)
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
        new AlertDialog.Builder(mActivity).setItems(actions, (dialog, which) -> {
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
                    showForwardDialog(forward, forward.type, forward.host);
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



    private void openLocalTerminal() {
        addSession(null);
    }

    private void connect(@NonNull TerminowStore.Host host) {
        TerminalSession session = addSession(host.getTitle());
        if (session == null) return;

        String command = host.getCommand(mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        mStore.addLog(host.getTitle(), command);
        writeWhenStarted(session, command + "\r", 0);
        if (!host.password.isEmpty())
            writePasswordWhenAsked(session, host.password, 0);
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

        String command = host.getForwardCommand(forward, mStore.getKeepAliveInterval(), mStore.getKeepAliveCountMax());
        mStore.addLog(forward.getTitle(), command);
        writeWhenStarted(session, command + "\r", 0);
        if (!host.password.isEmpty())
            writePasswordWhenAsked(session, host.password, 0);
    }

    /**
     * Type in the password of a host the first time that the session waits at a password prompt
     * after connecting, like `root@host's password: `. Other prompts before it, like to confirm
     * the key of a new host, are left to the user. The password is only written once, so a wrong
     * password ends at the prompt of ssh to enter it again instead of being retried.
     */
    private void writePasswordWhenAsked(@NonNull TerminalSession session, @NonNull String password, int attempt) {
        if (attempt >= PASSWORD_PROMPT_CHECK_COUNT) return;

        if (session.getPid() > 0) {
            if (!session.isRunning()) return;

            String transcript = ShellUtils.getTerminalSessionTranscriptText(session, false, true);
            if (transcript != null) {
                String lastLine = transcript.substring(transcript.lastIndexOf('\n') + 1);
                if (PASSWORD_PROMPT_PATTERN.matcher(lastLine).find()) {
                    session.write(password + "\r");
                    return;
                }
            }
        }

        mHandler.postDelayed(() -> writePasswordWhenAsked(session, password, attempt + 1), PASSWORD_PROMPT_CHECK_DELAY_MS);
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

        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.terminow_snippets)
            .setItems(titles, (dialog, which) -> runSnippet(snippets.get(which)))
            .setNeutralButton(R.string.terminow_new_snippet, (dialog, which) -> {
                mSnippets = snippets;
                showSnippetDialog(null);
            })
            .show();
    }



    /** Show the form to add or edit a host in a panel at the side of the screen. */
    private void showHostDialog(@Nullable TerminowStore.Host existingHost) {
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
        EditText labelView = dialog.findViewById(R.id.terminow_host_label);
        EditText addressView = dialog.findViewById(R.id.terminow_host_address);
        EditText portView = dialog.findViewById(R.id.terminow_host_port);
        EditText usernameView = dialog.findViewById(R.id.terminow_host_username);
        EditText passwordView = dialog.findViewById(R.id.terminow_host_password);
        EditText keyView = dialog.findViewById(R.id.terminow_host_key);
        EditText groupView = dialog.findViewById(R.id.terminow_host_group);
        Switch moshView = dialog.findViewById(R.id.terminow_host_mosh);

        titleView.setText(existingHost == null ? R.string.terminow_new_host : R.string.terminow_edit_host);
        // A new host is added to the group that is being shown
        String group = existingHost != null ? existingHost.group : mCurrentGroup;
        if (group != null && mGroups.contains(group))
            groupView.setText(group);
        groupView.setOnClickListener(v -> showGroupPicker(groupView));
        if (existingHost != null) {
            labelView.setText(existingHost.label);
            addressView.setText(existingHost.address);
            usernameView.setText(existingHost.username);
            passwordView.setText(existingHost.password);
            keyView.setText(existingHost.keyPath);
            moshView.setChecked(existingHost.mosh);
            // The default port is shown as the hint of the empty field
            if (existingHost.port != 22)
                portView.setText(String.valueOf(existingHost.port));
        }

        dialog.findViewById(R.id.terminow_host_keys).setOnClickListener(v -> showKeyPicker(keyView));
        dialog.findViewById(R.id.terminow_host_key_remove).setOnClickListener(v -> keyView.setText(""));

        dialog.findViewById(R.id.terminow_host_close).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.terminow_host_save).setOnClickListener(v -> {
            String address = addressView.getText().toString().trim();
            String username = usernameView.getText().toString().trim();
            String keyPath = keyView.getText().toString().trim();
            String portText = portView.getText().toString().trim();
            int port;
            try {
                port = portText.isEmpty() ? 22 : Integer.parseInt(portText);
            } catch (NumberFormatException e) {
                port = -1;
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

            TerminowStore.Host host = existingHost != null ? existingHost : new TerminowStore.Host();
            host.label = labelView.getText().toString().trim();
            host.address = address;
            host.port = port;
            host.username = username;
            host.password = passwordView.getText().toString();
            host.keyPath = keyPath;
            host.group = groupView.getText().toString();
            host.mosh = moshView.isChecked();
            if (existingHost == null)
                mHosts.add(host);
            mStore.setHosts(mHosts);
            updateItems();
            dialog.dismiss();
        });

        dialog.show();
        if (existingHost == null)
            addressView.requestFocus();
    }

    /** Show the private keys in `~/.ssh` to pick the key of a host from. */
    private void showKeyPicker(@NonNull EditText keyView) {
        List<String> keys = new ArrayList<>();
        File[] files = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh").listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                // Skip public keys and the other files that ssh keeps in the directory
                if (!file.isFile() || name.endsWith(".pub") || name.startsWith("known_hosts") ||
                    name.equals("config") || name.equals("authorized_keys") || name.equals("environment"))
                    continue;
                if (TerminowStore.isValidKeyPath("~/.ssh/" + name))
                    keys.add("~/.ssh/" + name);
            }
        }
        Collections.sort(keys);

        if (keys.isEmpty()) {
            new AlertDialog.Builder(mActivity)
                .setMessage(R.string.terminow_host_no_keys)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }

        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.terminow_host_keys_title)
            .setItems(keys.toArray(new CharSequence[0]), (dialog, which) -> keyView.setText(keys.get(which)))
            .show();
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
    private void showGroupPicker(@NonNull EditText groupView) {
        List<String> names = new ArrayList<>();
        names.add(mActivity.getString(R.string.terminow_host_group_none));
        names.addAll(mGroups);
        new AlertDialog.Builder(mActivity)
            .setTitle(R.string.terminow_host_group)
            .setItems(names.toArray(new CharSequence[0]), (dialog, which) ->
                groupView.setText(which == 0 ? "" : mGroups.get(which - 1)))
            .show();
    }

    /** Ask to import the hosts of the ssh config file of the home directory. */
    private void showImportDialog() {
        final File configFile = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh/config");
        if (!configFile.isFile()) {
            new AlertDialog.Builder(mActivity)
                .setTitle(R.string.terminow_import_title)
                .setMessage(R.string.terminow_import_no_file)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }

        new AlertDialog.Builder(mActivity)
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

        KeyItem(@NonNull File file) {
            this.name = file.getName();
            this.file = file;
            this.publicFile = new File(file.getPath() + ".pub");
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
            keys.add(new KeyItem(file));
        }
        Collections.sort(keys, (a, b) -> a.name.compareTo(b.name));
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

    private void showKeyActions(@NonNull KeyItem key) {
        CharSequence[] actions = {mActivity.getString(R.string.terminow_key_copy_public), mActivity.getString(R.string.terminow_delete)};
        new AlertDialog.Builder(mActivity).setTitle(key.name).setItems(actions, (dialog, which) -> {
            if (which == 0) {
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
            } else {
                new AlertDialog.Builder(mActivity)
                    .setMessage(mActivity.getString(R.string.terminow_key_delete_confirm, key.name))
                    .setPositiveButton(R.string.terminow_delete, (confirmDialog, confirmWhich) -> {
                        if (!key.file.delete())
                            Logger.logError(LOG_TAG, "Failed to delete \"" + key.file + "\"");
                        if (key.publicFile.isFile() && !key.publicFile.delete())
                            Logger.logError(LOG_TAG, "Failed to delete \"" + key.publicFile + "\"");
                        updateItems();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            }
        }).show();
    }

    /** Ask for the file name of a new key and generate it with `ssh-keygen` in a local terminal. */
    private void showNewKeyDialog() {
        LinearLayout form = createForm();
        EditText nameView = addField(form, R.string.terminow_key_name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        if (!new File(getSshDir(), "id_ed25519").exists())
            nameView.setText("id_ed25519");

        showFormDialog(R.string.terminow_key_new, form, () -> {
            String name = nameView.getText().toString().trim();
            if (!KEY_NAME_PATTERN.matcher(name).matches() || name.endsWith(".pub") || new File(getSshDir(), name).exists()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_key_invalid), true);
                return false;
            }

            // ssh-keygen asks for the passphrase of the key in the terminal
            TerminalSession session = addSession(null);
            if (session != null)
                writeWhenStarted(session, "mkdir -p ~/.ssh && chmod 700 ~/.ssh && ssh-keygen -t ed25519 -f ~/.ssh/" + name + "\r", 0);
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
        new AlertDialog.Builder(mActivity)
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

    /** Ask for the type and the host of a new port forwarding and then show its form. */
    private void showNewForwardDialog() {
        if (mHosts.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_forward_need_host), true);
            return;
        }

        final String[] types = {TerminowStore.Forward.TYPE_LOCAL, TerminowStore.Forward.TYPE_REMOTE, TerminowStore.Forward.TYPE_DYNAMIC};
        CharSequence[] typeLabels = {mActivity.getString(R.string.terminow_forward_local),
            mActivity.getString(R.string.terminow_forward_remote), mActivity.getString(R.string.terminow_forward_dynamic)};
        new AlertDialog.Builder(mActivity).setTitle(R.string.terminow_forward_type).setItems(typeLabels, (typeDialog, typeWhich) -> {
            CharSequence[] hostTitles = new CharSequence[mHosts.size()];
            for (int i = 0; i < hostTitles.length; i++)
                hostTitles[i] = mHosts.get(i).getTitle();
            new AlertDialog.Builder(mActivity).setTitle(R.string.terminow_forward_host).setItems(hostTitles, (hostDialog, hostWhich) ->
                showForwardDialog(null, types[typeWhich], hostTitles[hostWhich].toString())).show();
        }).show();
    }

    private static int parsePort(@NonNull EditText view) {
        try {
            return Integer.parseInt(view.getText().toString().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void showForwardDialog(@Nullable TerminowStore.Forward existingForward, @NonNull String type, @NonNull String hostTitle) {
        boolean dynamic = TerminowStore.Forward.TYPE_DYNAMIC.equals(type);
        boolean remote = TerminowStore.Forward.TYPE_REMOTE.equals(type);

        LinearLayout form = createForm();
        EditText labelView = addField(form, R.string.terminow_forward_label, InputType.TYPE_CLASS_TEXT);
        EditText portView = addField(form, remote ? R.string.terminow_forward_port_remote : R.string.terminow_forward_port_local, InputType.TYPE_CLASS_NUMBER);
        EditText destinationView = dynamic ? null : addField(form, R.string.terminow_forward_destination, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText destinationPortView = dynamic ? null : addField(form, R.string.terminow_forward_destination_port, InputType.TYPE_CLASS_NUMBER);

        if (existingForward != null) {
            labelView.setText(existingForward.label);
            portView.setText(String.valueOf(existingForward.port));
            if (!dynamic) {
                destinationView.setText(existingForward.destinationAddress);
                destinationPortView.setText(String.valueOf(existingForward.destinationPort));
            }
        } else if (!dynamic) {
            destinationView.setText("localhost");
        }

        showFormDialog(existingForward == null ? R.string.terminow_forward_new : R.string.terminow_forward_edit, form, () -> {
            TerminowStore.Forward forward = new TerminowStore.Forward();
            forward.label = labelView.getText().toString().trim();
            forward.type = type;
            forward.host = hostTitle;
            forward.port = parsePort(portView);
            if (!dynamic) {
                forward.destinationAddress = destinationView.getText().toString().trim();
                forward.destinationPort = parsePort(destinationPortView);
            }

            if (!TerminowStore.isValidPort(forward.port) || (!dynamic && !TerminowStore.isValidPort(forward.destinationPort))) {
                mActivity.showToast(mActivity.getString(R.string.terminow_forward_invalid_port), true);
                return false;
            }
            if (!TerminowStore.isValidForward(forward)) {
                mActivity.showToast(mActivity.getString(R.string.terminow_host_invalid_address), true);
                return false;
            }

            if (existingForward != null)
                mForwards.set(mForwards.indexOf(existingForward), forward);
            else
                mForwards.add(forward);
            mStore.setForwards(mForwards);
            updateItems();
            return true;
        });
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
        AlertDialog dialog = new AlertDialog.Builder(mActivity)
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
        LinearLayout form = new LinearLayout(mActivity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        return form;
    }

    @NonNull
    private EditText addField(@NonNull LinearLayout form, int hintRes, int inputType) {
        EditText field = new EditText(mActivity);
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
                    subtitleView.setText((host.mosh ? "mosh" : "ssh") + (host.username.isEmpty() ? "" : ", " + host.username));
            } else if (item instanceof KeyItem) {
                KeyItem key = (KeyItem) item;
                iconRes = R.drawable.ic_terminow_key;
                iconColorRes = R.color.terminow_host_local;
                titleView.setText(key.name);
                // The public key starts with its type, like `ssh-ed25519`
                String publicKey = key.publicFile.isFile() ? readFirstLine(key.publicFile) : null;
                subtitleView.setText(publicKey == null || publicKey.isEmpty() ? mActivity.getString(R.string.terminow_key_private) : publicKey.split("\\s+")[0]);
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
