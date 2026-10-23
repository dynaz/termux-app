package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.view.KeyboardUtils;
import com.termux.shared.view.ViewUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The "New connection" picker of the add button of the tab bar, in SSH or SFTP mode. It has a
 * search and quick connect field, quick actions, and the recent connections, groups and hosts
 * of the saved hosts. Closing it does not change any session.
 */
public class TerminowConnectDialog {

    /** What the picker does, implemented by {@link TerminowHome}. */
    public interface Actions {
        /** Connect to a host in a new tab, with ssh or with sftp. */
        void connect(@NonNull TerminowStore.Host host, boolean sftp);

        /** Open a local terminal in a new tab. */
        void openLocalTerminal();

        /**
         * Show the host form to add a host, with the fields of a host if it is not {@code null},
         * and connect to the host with ssh or sftp once it is saved.
         */
        void addHostAndConnect(@Nullable TerminowStore.Host host, boolean sftp);
    }

    /** Screens narrower than this show the picker over the whole screen instead of as a dialog. */
    private static final int FULL_SCREEN_MAX_WIDTH_DP = 600;
    private static final int DIALOG_MAX_WIDTH_DP = 860;
    /** The lists have two columns if they are at least this wide. */
    private static final int TWO_COLUMNS_MIN_WIDTH_DP = 540;
    /** The number of recent connections that are shown. */
    private static final int RECENT_CONNECTIONS_SHOWN = 6;

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Actions mActions;
    private final boolean mSftp;

    private final Dialog mDialog;
    private final LinearLayout mCardView;
    private final EditText mSearchView;
    private final TextView mConnectView;
    private final TextView mErrorView;
    private final LinearLayout mContentView;

    private final List<TerminowStore.Host> mHosts;
    private final List<String> mGroups;
    /** The time of the last connection to each host, by {@link TerminowStore.Host#getKey()}. */
    private final Map<String, Long> mLastUsed = new HashMap<>();
    private final List<TerminowStore.RecentConnection> mRecents;

    private boolean mSortByRecent;
    /** The group whose hosts are shown, {@code null} for all. */
    @Nullable
    private String mGroup;
    private int mColumns = 1;
    /** Set once a connection is started, so that repeated taps do not start more. */
    private boolean mConnecting;

    public TerminowConnectDialog(@NonNull TermuxActivity activity, @NonNull TerminowStore store, boolean sftp, @NonNull Actions actions) {
        mActivity = activity;
        mStore = store;
        mActions = actions;
        mSftp = sftp;

        mHosts = store.getHosts();
        mGroups = store.getGroups();
        mRecents = store.getRecentConnections();
        for (TerminowStore.RecentConnection recent : mRecents) {
            Long time = mLastUsed.get(recent.hostKey);
            if (time == null || recent.time > time) mLastUsed.put(recent.hostKey, recent.time);
        }

        mDialog = new Dialog(activity, R.style.TerminowPanelDialog);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(color(R.color.terminow_panel_scrim));
        root.setFitsSystemWindows(true);
        // Tapping next to the dialog closes it, like its close button
        root.setOnClickListener(v -> mDialog.dismiss());

        mCardView = new LinearLayout(activity);
        mCardView.setOrientation(LinearLayout.VERTICAL);
        mCardView.setClickable(true);
        root.addView(mCardView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        // Header: close, title and sort
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(8), dp(12), 0);
        ImageView closeView = new ImageView(activity);
        closeView.setImageResource(R.drawable.ic_terminow_close);
        closeView.setPadding(dp(10), dp(10), dp(10), dp(10));
        closeView.setContentDescription(activity.getString(android.R.string.cancel));
        closeView.setOnClickListener(v -> mDialog.dismiss());
        header.addView(closeView, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView titleView = new TextView(activity);
        titleView.setText(sftp ? R.string.terminow_new_sftp_connection : R.string.terminow_new_connection);
        titleView.setTextColor(color(R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView sortView = new TextView(activity);
        sortView.setText("Az");
        sortView.setTextColor(color(R.color.terminow_bg));
        sortView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sortView.setTypeface(null, Typeface.BOLD);
        sortView.setGravity(Gravity.CENTER);
        sortView.setBackground(rounded(color(R.color.terminow_text), 6));
        sortView.setContentDescription(activity.getString(R.string.terminow_sort));
        sortView.setOnClickListener(v -> showSortOptions());
        LinearLayout.LayoutParams sortParams = new LinearLayout.LayoutParams(dp(40), dp(36));
        sortParams.setMargins(dp(4), 0, dp(4), 0);
        header.addView(sortView, sortParams);
        mCardView.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

        // Search and quick connect
        LinearLayout searchRow = new LinearLayout(activity);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(rounded(color(R.color.terminow_field), 28));
        searchRow.setPadding(dp(20), 0, dp(6), 0);
        mSearchView = new EditText(activity);
        mSearchView.setHint(sftp ? R.string.terminow_connect_search_hint_sftp : R.string.terminow_connect_search_hint);
        mSearchView.setBackground(null);
        mSearchView.setTextColor(color(R.color.terminow_text));
        mSearchView.setHintTextColor(color(R.color.terminow_text_secondary));
        mSearchView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mSearchView.setMaxLines(1);
        mSearchView.setSingleLine(true);
        mSearchView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        mSearchView.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        mSearchView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        mSearchView.setPadding(0, 0, 0, 0);
        searchRow.addView(mSearchView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        mConnectView = new TextView(activity);
        mConnectView.setText(R.string.terminow_connect);
        mConnectView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mConnectView.setTypeface(null, Typeface.BOLD);
        mConnectView.setGravity(Gravity.CENTER);
        mConnectView.setPadding(dp(14), 0, dp(14), 0);
        mConnectView.setOnClickListener(v -> quickConnect());
        searchRow.addView(mConnectView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        searchParams.setMargins(dp(16), dp(8), dp(16), 0);
        mCardView.addView(searchRow, searchParams);

        mErrorView = new TextView(activity);
        mErrorView.setTextColor(color(R.color.terminow_host_ssh));
        mErrorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mErrorView.setVisibility(View.GONE);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        errorParams.setMargins(dp(36), dp(6), dp(16), 0);
        mCardView.addView(mErrorView, errorParams);

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFillViewport(true);
        mContentView = new LinearLayout(activity);
        mContentView.setOrientation(LinearLayout.VERTICAL);
        mContentView.setPadding(dp(16), dp(8), dp(16), dp(24));
        scrollView.addView(mContentView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mCardView.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        mSearchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { update(); }
        });
        mSearchView.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                quickConnect();
                return true;
            }
            return false;
        });

        // A full screen sheet on a narrow screen, a centered dialog on a wide one, with one column
        // or two depending on the width
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            boolean fullScreen = width < dp(FULL_SCREEN_MAX_WIDTH_DP);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mCardView.getLayoutParams();
            int cardWidth = fullScreen ? ViewGroup.LayoutParams.MATCH_PARENT : Math.min(width - dp(48), dp(DIALOG_MAX_WIDTH_DP));
            int cardHeight = fullScreen ? ViewGroup.LayoutParams.MATCH_PARENT : Math.round((bottom - top) * 0.9f);
            int columns = (fullScreen ? width : cardWidth) - dp(32) >= dp(TWO_COLUMNS_MIN_WIDTH_DP) ? 2 : 1;
            if (params.width != cardWidth || params.height != cardHeight || columns != mColumns) {
                params.width = cardWidth;
                params.height = cardHeight;
                mColumns = columns;
                mCardView.post(() -> {
                    mCardView.setBackground(fullScreen ? rounded(color(R.color.terminow_bar_home), 0) :
                        rounded(color(R.color.terminow_bar_home), 24));
                    mCardView.setLayoutParams(params);
                    update();
                });
            }
        });

        mDialog.setContentView(root);
        mDialog.setOnKeyListener((dialog, keyCode, event) -> {
            // Back leaves a group before it closes the picker
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && mGroup != null) {
                mGroup = null;
                update();
                return true;
            }
            return false;
        });
        mDialog.setOnDismissListener(dialog -> KeyboardUtils.hideSoftKeyboard(mActivity, mSearchView));
    }

    public void show() {
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            // The lists stay usable above the keyboard
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        update();
        mDialog.show();
    }



    private void showSortOptions() {
        CharSequence[] options = {mActivity.getString(R.string.terminow_sort_name), mActivity.getString(R.string.terminow_sort_recent)};
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_sort)
            .setSingleChoiceItems(options, mSortByRecent ? 1 : 0, (dialog, which) -> {
                mSortByRecent = which == 1;
                dialog.dismiss();
                update();
            })
            .show();
    }

    /** Connect to the host of the quick connect field, or show the host form for what it is missing. */
    private void quickConnect() {
        TerminowQuickConnect.Result result = TerminowQuickConnect.parse(mSearchView.getText().toString());
        if (result.host == null) {
            mErrorView.setText(result.error);
            mErrorView.setVisibility(View.VISIBLE);
            return;
        }
        if (result.isComplete()) {
            connect(result.host);
        } else {
            startOnce(() -> mActions.addHostAndConnect(result.host, mSftp));
        }
    }

    private void connect(@NonNull TerminowStore.Host host) {
        startOnce(() -> mActions.connect(host, mSftp));
    }

    /** Close the picker and run an action, only once however often it is tapped. */
    private void startOnce(@NonNull Runnable action) {
        if (mConnecting) return;
        mConnecting = true;
        KeyboardUtils.hideSoftKeyboard(mActivity, mSearchView);
        mDialog.dismiss();
        action.run();
    }

    private void update() {
        String text = mSearchView.getText().toString().trim();
        TerminowQuickConnect.Result result = TerminowQuickConnect.parse(text);
        boolean canConnect = result.host != null;
        mConnectView.setEnabled(canConnect);
        mConnectView.setTextColor(color(canConnect ? R.color.terminow_panel_accent : R.color.terminow_text_secondary));
        // Only explain why the text is not valid if it looks like it is meant as a connection,
        // not while searching
        boolean looksLikeConnection = text.startsWith("ssh ") || text.startsWith("sftp ") || text.equals("ssh") ||
            text.equals("sftp") || text.contains("@") || text.contains("://") || text.contains(" -");
        if (!canConnect && looksLikeConnection) {
            mErrorView.setText(result.error);
            mErrorView.setVisibility(View.VISIBLE);
        } else {
            mErrorView.setVisibility(View.GONE);
        }

        mContentView.removeAllViews();
        String query = looksLikeConnection ? "" : text.toLowerCase(Locale.ROOT);

        if (query.isEmpty() && mGroup == null) {
            List<View> actions = new ArrayList<>();
            actions.add(createCard(R.drawable.ic_terminow_terminal, R.color.terminow_host_local,
                mActivity.getString(R.string.terminow_quick_local), null, () -> startOnce(mActions::openLocalTerminal), null));
            actions.add(createCard(R.drawable.ic_terminow_hosts, R.color.terminow_host_ssh,
                mActivity.getString(R.string.terminow_quick_new_ssh), null, () -> startOnce(() -> mActions.addHostAndConnect(null, false)), null));
            actions.add(createCard(R.drawable.ic_terminow_group, R.color.terminow_host_mosh,
                mActivity.getString(R.string.terminow_quick_new_sftp), null, () -> startOnce(() -> mActions.addHostAndConnect(null, true)), null));
            addGrid(actions);

            List<View> recents = new ArrayList<>();
            for (TerminowStore.RecentConnection recent : mRecents) {
                if (recents.size() >= RECENT_CONNECTIONS_SHOWN) break;
                // The recent connections of the mode of the picker
                if (recent.sftp != mSftp) continue;
                TerminowStore.Host host = findHost(recent.hostKey);
                if (host != null) recents.add(createHostCard(host));
            }
            if (!recents.isEmpty()) {
                addSection(R.string.terminow_recent_connections);
                addGrid(recents);
            }

            if (!mGroups.isEmpty()) {
                addSection(R.string.terminow_groups);
                List<View> groups = new ArrayList<>();
                for (String group : sortGroups()) {
                    int count = 0;
                    for (TerminowStore.Host host : mHosts)
                        if (group.equals(host.group)) count++;
                    groups.add(createCard(R.drawable.ic_terminow_group, R.color.terminow_host_group, group,
                        mActivity.getResources().getQuantityString(R.plurals.terminow_group_hosts, count, count), () -> {
                            mGroup = group;
                            update();
                        }, null));
                }
                addGrid(groups);
            }
        }

        List<TerminowStore.Host> hosts = new ArrayList<>();
        for (TerminowStore.Host host : mHosts) {
            if (mGroup != null && !mGroup.equals(host.group)) continue;
            if (!query.isEmpty() && !matches(host, query)) continue;
            hosts.add(host);
        }
        sortHosts(hosts);

        if (mGroup != null) {
            TextView backView = addSection(R.string.terminow_hosts);
            backView.setText("‹ " + mActivity.getString(R.string.terminow_hosts) + " / " + mGroup);
            backView.setOnClickListener(v -> {
                mGroup = null;
                update();
            });
        } else {
            addSection(R.string.terminow_hosts);
        }

        if (hosts.isEmpty()) {
            TextView emptyView = new TextView(mActivity);
            emptyView.setText(!query.isEmpty() ? R.string.terminow_no_results :
                mHosts.isEmpty() ? R.string.terminow_connect_no_hosts : R.string.terminow_group_empty);
            emptyView.setTextColor(color(R.color.terminow_text_secondary));
            emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            emptyView.setPadding(dp(4), dp(4), dp(4), dp(12));
            mContentView.addView(emptyView);
            TextView addView = new TextView(mActivity);
            addView.setText(R.string.terminow_action_new_host);
            addView.setTextColor(color(R.color.terminow_text));
            addView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            addView.setTypeface(null, Typeface.BOLD);
            addView.setGravity(Gravity.CENTER);
            addView.setBackground(rounded(color(R.color.terminow_surface), 18));
            addView.setPadding(dp(18), 0, dp(18), 0);
            addView.setOnClickListener(v -> startOnce(() -> mActions.addHostAndConnect(null, mSftp)));
            mContentView.addView(addView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        } else {
            List<View> cards = new ArrayList<>();
            for (TerminowStore.Host host : hosts)
                cards.add(createHostCard(host));
            addGrid(cards);
        }
    }

    private static boolean matches(@NonNull TerminowStore.Host host, @NonNull String query) {
        return host.getTitle().toLowerCase(Locale.ROOT).contains(query) ||
            host.address.toLowerCase(Locale.ROOT).contains(query) ||
            host.username.toLowerCase(Locale.ROOT).contains(query) ||
            host.group.toLowerCase(Locale.ROOT).contains(query);
    }

    @Nullable
    private TerminowStore.Host findHost(@NonNull String hostKey) {
        for (TerminowStore.Host host : mHosts)
            if (host.getKey().equals(hostKey)) return host;
        return null;
    }

    private long getLastUsed(@NonNull TerminowStore.Host host) {
        Long time = mLastUsed.get(host.getKey());
        return time != null ? time : 0;
    }

    private void sortHosts(@NonNull List<TerminowStore.Host> hosts) {
        Collections.sort(hosts, (a, b) -> {
            if (mSortByRecent) {
                int byTime = Long.compare(getLastUsed(b), getLastUsed(a));
                if (byTime != 0) return byTime;
            }
            return a.getTitle().compareToIgnoreCase(b.getTitle());
        });
    }

    @NonNull
    private List<String> sortGroups() {
        List<String> groups = new ArrayList<>(mGroups);
        Collections.sort(groups, (a, b) -> {
            if (mSortByRecent) {
                long timeA = 0, timeB = 0;
                for (TerminowStore.Host host : mHosts) {
                    if (a.equals(host.group)) timeA = Math.max(timeA, getLastUsed(host));
                    if (b.equals(host.group)) timeB = Math.max(timeB, getLastUsed(host));
                }
                int byTime = Long.compare(timeB, timeA);
                if (byTime != 0) return byTime;
            }
            return a.compareToIgnoreCase(b);
        });
        return groups;
    }



    @NonNull
    private TextView addSection(int titleRes) {
        TextView titleView = new TextView(mActivity);
        titleView.setText(titleRes);
        titleView.setTextColor(color(R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(18);
        params.bottomMargin = dp(10);
        mContentView.addView(titleView, params);
        return titleView;
    }

    /** Add views in rows of {@link #mColumns}. */
    private void addGrid(@NonNull List<View> views) {
        for (int i = 0; i < views.size(); i += mColumns) {
            LinearLayout row = new LinearLayout(mActivity);
            for (int column = 0; column < mColumns; column++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(68), 1);
                if (column > 0) params.setMarginStart(dp(12));
                if (i + column < views.size()) {
                    row.addView(views.get(i + column), params);
                } else {
                    // Keeps the cards of the last row as wide as the others
                    row.addView(new View(mActivity), params);
                }
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(12);
            mContentView.addView(row, rowParams);
        }
    }

    @NonNull
    private View createHostCard(@NonNull TerminowStore.Host host) {
        // The platform of a host is not known, so it has the icon of a server, in the color of
        // its connection type. The subtitle never has secrets, only the user, host and port.
        String subtitle = host.getSummary(mSftp) + (host.mosh && !mSftp ? " · mosh" : "");
        return createCard(R.drawable.ic_terminow_hosts, host.mosh && !mSftp ? R.color.terminow_host_mosh : R.color.terminow_host_ssh,
            host.getTitle(), subtitle, () -> connect(host), host.getTitle());
    }

    @NonNull
    private View createCard(int iconRes, int iconColorRes, @NonNull String title, @Nullable String subtitle,
                            @NonNull Runnable onClick, @Nullable String contentDescription) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.terminow_card);
        card.setPadding(dp(10), 0, dp(12), 0);

        ImageView iconView = new ImageView(mActivity);
        iconView.setImageResource(iconRes);
        iconView.setColorFilter(color(R.color.terminow_text), PorterDuff.Mode.SRC_IN);
        iconView.setBackground(rounded(color(iconColorRes), 10));
        iconView.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(iconView, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(mActivity);
        titleView.setText(title);
        titleView.setTextColor(color(R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView);
        if (subtitle != null) {
            TextView subtitleView = new TextView(mActivity);
            subtitleView.setText(subtitle);
            subtitleView.setTextColor(color(R.color.terminow_text_secondary));
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subtitleView.setMaxLines(1);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView);
        }
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        textsParams.setMarginStart(dp(14));
        card.addView(texts, textsParams);

        card.setContentDescription(contentDescription != null ? contentDescription : title);
        card.setOnClickListener(v -> onClick.run());
        return card;
    }

    @NonNull
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private int color(int colorRes) {
        return ContextCompat.getColor(mActivity, colorRes);
    }

    private int dp(float dp) {
        return (int) ViewUtils.dpToPx(mActivity, dp);
    }

}
