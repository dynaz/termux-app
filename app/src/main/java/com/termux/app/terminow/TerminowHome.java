package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
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
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.view.KeyboardUtils;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The home screen of {@link TermuxActivity} and the tab bar above it. The home screen is shown over
 * the terminal and lists the saved hosts and snippets, the tab bar lists the running sessions.
 */
public class TerminowHome {

    private static final int PAGE_HOSTS = 0;
    private static final int PAGE_SNIPPETS = 1;

    /** How long to wait for the shell of a new session to start before giving up on sending it a command. */
    private static final int WRITE_RETRY_COUNT = 50;
    private static final int WRITE_RETRY_DELAY_MS = 100;

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
    private final EditText mSearchView;
    private final TextView mSectionTitleView;
    private final TextView mEmptyView;
    private final ItemsAdapter mAdapter = new ItemsAdapter();

    private int mPage = PAGE_HOSTS;
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
        View railSettings = activity.findViewById(R.id.terminow_rail_settings);
        railSettings.setOnClickListener(v -> ActivityUtils.startActivity(mActivity, new Intent(mActivity, SettingsActivity.class)));
        setRailItemSelected(railSettings, false);

        GridView gridView = activity.findViewById(R.id.terminow_grid);
        gridView.setAdapter(mAdapter);
        gridView.setOnItemClickListener((parent, view, position, id) -> onItemClick(mItems.get(position)));
        gridView.setOnItemLongClickListener((parent, view, position, id) -> onItemLongClick(mItems.get(position)));

        activity.findViewById(R.id.terminow_fab).setOnClickListener(v -> {
            if (mPage == PAGE_HOSTS)
                showHostDialog(null);
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
            mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
            return;
        }

        new AlertDialog.Builder(mActivity)
            .setMessage(R.string.terminow_close_session)
            .setPositiveButton(android.R.string.ok, (dialog, which) ->
                mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }



    private void setPage(int page) {
        mPage = page;
        setRailItemSelected(mRailHosts, page == PAGE_HOSTS);
        setRailItemSelected(mRailSnippets, page == PAGE_SNIPPETS);
        mSectionTitleView.setText(page == PAGE_HOSTS ? R.string.terminow_hosts : R.string.terminow_snippets);
        updateItems();
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

        mItems.clear();
        if (mPage == PAGE_HOSTS) {
            if (query.isEmpty() || mActivity.getString(R.string.terminow_local_terminal).toLowerCase(Locale.ROOT).contains(query))
                mItems.add(null);
            for (TerminowStore.Host host : mHosts) {
                if (query.isEmpty() || host.getTitle().toLowerCase(Locale.ROOT).contains(query) ||
                    host.address.toLowerCase(Locale.ROOT).contains(query))
                    mItems.add(host);
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
            mEmptyView.setText(query.isEmpty() ? R.string.terminow_no_snippets : R.string.terminow_no_results);
            mEmptyView.setVisibility(View.VISIBLE);
        }
    }

    private void onItemClick(@Nullable Object item) {
        if (item == null)
            openLocalTerminal();
        else if (item instanceof TerminowStore.Host)
            connect((TerminowStore.Host) item);
        else
            runSnippet((TerminowStore.Snippet) item);
    }

    private boolean onItemLongClick(@Nullable Object item) {
        if (item == null) return false;

        CharSequence[] actions = {mActivity.getString(R.string.terminow_edit), mActivity.getString(R.string.terminow_delete)};
        new AlertDialog.Builder(mActivity).setItems(actions, (dialog, which) -> {
            if (item instanceof TerminowStore.Host) {
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
        if (session != null)
            writeWhenStarted(session, host.getCommand() + "\r", 0);
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



    /** Show the full screen form to add or edit a host. */
    private void showHostDialog(@Nullable TerminowStore.Host existingHost) {
        Dialog dialog = new Dialog(mActivity, android.R.style.Theme_Material_NoActionBar);
        dialog.setContentView(R.layout.dialog_terminow_host);
        Window window = dialog.getWindow();
        if (window != null) {
            int backgroundColor = ContextCompat.getColor(mActivity, R.color.terminow_bg);
            window.setStatusBarColor(backgroundColor);
            window.setNavigationBarColor(backgroundColor);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        // Keep the form readable on wide screens instead of stretching the fields across them
        View rootView = dialog.findViewById(R.id.terminow_host_root);
        View columnView = dialog.findViewById(R.id.terminow_host_column);
        rootView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(560));
            ViewGroup.LayoutParams params = columnView.getLayoutParams();
            if (params.width != width) {
                params.width = width;
                columnView.post(() -> columnView.setLayoutParams(params));
            }
        });

        TextView titleView = dialog.findViewById(R.id.terminow_host_title);
        ImageView iconView = dialog.findViewById(R.id.terminow_host_icon);
        EditText labelView = dialog.findViewById(R.id.terminow_host_label);
        EditText addressView = dialog.findViewById(R.id.terminow_host_address);
        EditText portView = dialog.findViewById(R.id.terminow_host_port);
        EditText usernameView = dialog.findViewById(R.id.terminow_host_username);
        Switch moshView = dialog.findViewById(R.id.terminow_host_mosh);

        titleView.setText(existingHost == null ? R.string.terminow_new_host : R.string.terminow_edit_host);
        if (existingHost != null) {
            labelView.setText(existingHost.label);
            addressView.setText(existingHost.address);
            usernameView.setText(existingHost.username);
            moshView.setChecked(existingHost.mosh);
        }
        portView.setText(String.valueOf(existingHost != null ? existingHost.port : 22));

        Runnable updateIcon = () -> iconView.getBackground().mutate().setColorFilter(ContextCompat.getColor(mActivity,
            moshView.isChecked() ? R.color.terminow_host_mosh : R.color.terminow_host_ssh), PorterDuff.Mode.SRC_IN);
        updateIcon.run();
        moshView.setOnCheckedChangeListener((buttonView, isChecked) -> updateIcon.run());

        dialog.findViewById(R.id.terminow_host_close).setOnClickListener(v -> dialog.dismiss());
        dialog.findViewById(R.id.terminow_host_save).setOnClickListener(v -> {
            String address = addressView.getText().toString().trim();
            String username = usernameView.getText().toString().trim();
            int port;
            try {
                port = Integer.parseInt(portView.getText().toString().trim());
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

            TerminowStore.Host host = existingHost != null ? existingHost : new TerminowStore.Host();
            host.label = labelView.getText().toString().trim();
            host.address = address;
            host.port = port;
            host.username = username;
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
            if (item == null) {
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
