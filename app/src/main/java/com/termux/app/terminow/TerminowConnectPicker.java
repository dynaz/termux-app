package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The unified "Connect to…" picker shown by the terminal toolbar's environment button. It replaces the
 * old environment-only dialog with three tabs — Sessions, Local, Hosts — and a single search box.
 *
 * <p>It never implements a connection itself: it dispatches to the existing flows. Sessions and local
 * environments go through {@link TerminowLauncher} (so running processes, ordering and the New tab / Use
 * current behaviour are preserved and a tap on a running session activates it without a duplicate); hosts
 * go through {@link HostActions}, which TerminowHome implements with its per-protocol connection code
 * (SSH terminal, RDP and WinRM viewers, SFTP file browser) and the host editor. No password, key or token
 * is ever shown. A bottom sheet on phones, a constrained dialog on wider screens; light and dark themed by
 * the shared TermiNow colors.</p>
 */
final class TerminowConnectPicker {

    /** The per-protocol host flows the picker reuses, implemented by TerminowHome. */
    interface HostActions {
        void connect(@NonNull TerminowStore.Host host);
        void connectSftp(@NonNull TerminowStore.Host host);
        void edit(@NonNull TerminowStore.Host host);
        void addHost();
    }

    private enum Tab { SESSIONS, LOCAL, HOSTS }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final TerminowLauncher mLauncher;
    private final HostActions mHosts;

    private Dialog mDialog;
    private LinearLayout mList;
    private String mQuery = "";
    private Tab mTab = Tab.SESSIONS;
    private final TextView[] mTabPills = new TextView[3];

    TerminowConnectPicker(@NonNull TermuxActivity activity, @NonNull TerminowStore store,
                          @NonNull TerminowLauncher launcher, @NonNull HostActions hosts) {
        mActivity = activity;
        mStore = store;
        mLauncher = launcher;
        mHosts = hosts;
    }

    void show() {
        boolean phone = mActivity.getResources().getConfiguration().screenWidthDp < 600;
        mDialog = new Dialog(mActivity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.terminow_bar_home));
        bg.setStroke(dp(1), color(R.color.terminow_quick_border));
        float r = dp(24);
        bg.setCornerRadii(phone ? new float[]{r, r, r, r, 0, 0, 0, 0} : new float[]{r, r, r, r, r, r, r, r});
        root.setBackground(bg);

        // Header: title + close
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_connpick_title), 19, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        androidx.core.view.ViewCompat.setAccessibilityHeading(title, true);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 18, R.color.terminow_text);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription(mActivity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> mDialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(head, matchWrap());

        // Search
        final EditText search = new EditText(mActivity);
        search.setHint(mActivity.getString(R.string.terminow_connpick_search));
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setTextColor(color(R.color.terminow_text));
        search.setHintTextColor(color(R.color.terminow_text_secondary));
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(color(R.color.terminow_field));
        sbg.setCornerRadius(dp(12));
        sbg.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
        search.setBackground(sbg);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams slp = matchWrap();
        slp.topMargin = dp(10);
        root.addView(search, slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable e) { mQuery = e.toString().trim().toLowerCase(Locale.ROOT); rebuild(); }
        });

        // Tabs
        LinearLayout tabs = new LinearLayout(mActivity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tlp = matchWrap();
        tlp.topMargin = dp(12);
        int[] labels = {R.string.terminow_connpick_tab_sessions, R.string.terminow_connpick_tab_local, R.string.terminow_connpick_tab_hosts};
        Tab[] order = {Tab.SESSIONS, Tab.LOCAL, Tab.HOSTS};
        for (int i = 0; i < 3; i++) {
            final Tab t = order[i];
            TextView pill = text(mActivity.getString(labels[i]), 14, R.color.terminow_text);
            pill.setTypeface(Typeface.DEFAULT_BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setMinHeight(dp(44));
            pill.setClickable(true);
            pill.setOnClickListener(v -> { mTab = t; styleTabs(); rebuild(); });
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) plp.leftMargin = dp(6);
            pill.setLayoutParams(plp);
            mTabPills[i] = pill;
            tabs.addView(pill);
        }
        root.addView(tabs, tlp);

        // Scrollable list
        mList = new LinearLayout(mActivity);
        mList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(mActivity) {
            @Override protected void onMeasure(int w, int h) {
                int max = Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.55f);
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(true);
        scroll.addView(mList);
        LinearLayout.LayoutParams sclp = matchWrap();
        sclp.topMargin = dp(8);
        root.addView(scroll, sclp);

        mDialog.setContentView(root);
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Keep the terminal keyboard hidden until the user taps search.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int screen = mActivity.getResources().getDisplayMetrics().widthPixels;
            if (phone) { window.setGravity(Gravity.BOTTOM); window.setLayout(screen, ViewGroup.LayoutParams.WRAP_CONTENT); }
            else window.setLayout(Math.min(screen - dp(32), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        // Default to Sessions when any exist, otherwise Local (the button's historic role).
        if (mLauncher.openSessions().isEmpty()) mTab = Tab.LOCAL;
        styleTabs();
        rebuild();
        mDialog.show();
    }

    private void styleTabs() {
        Tab[] order = {Tab.SESSIONS, Tab.LOCAL, Tab.HOSTS};
        for (int i = 0; i < 3; i++) {
            boolean sel = mTab == order[i];
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color(sel ? R.color.terminow_panel_accent : R.color.terminow_field));
            shape.setCornerRadius(dp(10));
            mTabPills[i].setBackground(shape);
            mTabPills[i].setTextColor(color(sel ? R.color.terminow_bar_home : R.color.terminow_text));
        }
    }

    private void rebuild() {
        mList.removeAllViews();
        switch (mTab) {
            case SESSIONS: buildSessions(); break;
            case LOCAL: buildLocal(); break;
            case HOSTS: buildHosts(); break;
        }
    }

    // ---- Sessions -------------------------------------------------------------------------------

    private void buildSessions() {
        List<TerminalSession> sessions = mLauncher.openSessions();
        TerminalSession current = mActivity.getCurrentSession();
        int shown = 0;
        for (TerminalSession session : sessions) {
            TerminowLauncher.TabIdentity id = mLauncher.tabIdentity(session);
            TerminowLauncher.SessionInfo info = mLauncher.infoOf(session);
            String name = sessionName(session, info);
            String type = sessionTypeLabel(id, info);
            String sub = type + (id.hostName.isEmpty() ? "" : " · " + id.hostName);
            if (!matches(name, sub)) continue;
            boolean active = session == current;
            addRow(id.iconRes, id.tintIcon, name, active ? sub + " · " + mActivity.getString(R.string.terminow_connpick_active) : sub,
                active ? R.color.terminow_quick_ready : R.color.terminow_text_secondary, () -> {
                    mDialog.dismiss();
                    mLauncher.activateSession(session);   // activate, never duplicate
                });
            shown++;
        }
        if (shown == 0) note(mActivity.getString(R.string.terminow_connpick_no_sessions));
    }

    @NonNull
    private String sessionName(@NonNull TerminalSession s, @Nullable TerminowLauncher.SessionInfo info) {
        if (s.mSessionName != null && !s.mSessionName.isEmpty()) return s.mSessionName;
        if (info != null && !info.environmentLabel.isEmpty()) return info.environmentLabel;
        return mActivity.getString(R.string.terminow_connpick_type_session);
    }

    @NonNull
    private String sessionTypeLabel(@NonNull TerminowLauncher.TabIdentity id, @Nullable TerminowLauncher.SessionInfo info) {
        switch (id.kind) {
            case "local": return mActivity.getString(R.string.terminow_connpick_type_local);
            case "distro": return info != null ? info.environmentLabel : mActivity.getString(R.string.terminow_connpick_type_distro);
            case "sftp": return "SFTP";
            case "rdp": return "RDP";
            case "winrm": return "WinRM";
            case "telnet": return "Telnet";
            case "ssh": return "SSH";
            default: return mActivity.getString(R.string.terminow_connpick_type_session);
        }
    }

    // ---- Local ----------------------------------------------------------------------------------

    private void buildLocal() {
        int shown = 0;
        for (TerminowLauncher.Environment env : mLauncher.environments()) {
            String distroId = mLauncher.environmentDistroId(env);
            String sub = env.isLocal() ? mActivity.getString(R.string.terminow_connpick_type_local) : distroId;
            if (!matches(env.label, sub)) continue;
            int icon = TerminowQuickLaunch.iconOf(distroId.isEmpty() ? "local" : distroId);
            boolean tint = env.isLocal() || distroId.isEmpty();
            addRow(icon, tint, env.label, sub, R.color.terminow_text_secondary, () -> {
                mDialog.dismiss();
                mLauncher.selectEnvironment(env);   // reuses New tab / Use current / Resume, default New tab
            });
            shown++;
        }
        if (shown == 0) note(mActivity.getString(R.string.terminow_connpick_no_local));
    }

    // ---- Hosts ----------------------------------------------------------------------------------

    private void buildHosts() {
        List<TerminowStore.Host> hosts = mStore.getHosts();
        // Group by connection type; empty groups are hidden. SFTP/VNC are not saved-host protocols in
        // TermiNow (SFTP is a mode of an SSH host; VNC is not supported), so those groups never appear.
        List<TerminowStore.Host> ssh = new ArrayList<>(), rdp = new ArrayList<>(), winrm = new ArrayList<>();
        for (TerminowStore.Host h : hosts) {
            if (!matchesHost(h)) continue;
            if (h.isRdp()) rdp.add(h);
            else if (h.isWinrm()) winrm.add(h);
            else ssh.add(h);
        }
        boolean any = !ssh.isEmpty() || !rdp.isEmpty() || !winrm.isEmpty();
        if (hosts.isEmpty()) {
            note(mActivity.getString(R.string.terminow_connpick_no_hosts));
            addAction(mActivity.getString(R.string.terminow_connpick_add_host), () -> { mDialog.dismiss(); mHosts.addHost(); });
            return;
        }
        if (!any) { note(mActivity.getString(R.string.terminow_connpick_no_hosts_match)); }
        group(R.string.terminow_connpick_group_ssh, ssh);
        group(R.string.terminow_connpick_group_rdp, rdp);
        group(R.string.terminow_connpick_group_winrm, winrm);
        addAction(mActivity.getString(R.string.terminow_connpick_add_host), () -> { mDialog.dismiss(); mHosts.addHost(); });
    }

    private void group(int labelRes, @NonNull List<TerminowStore.Host> hosts) {
        if (hosts.isEmpty()) return;   // hide empty groups
        section(mActivity.getString(labelRes));
        for (TerminowStore.Host h : hosts) {
            int icon = h.isRdp() || h.isWinrm() ? R.drawable.ic_terminow_desktop : R.drawable.ic_terminow_hosts;
            // Never show secrets: only address, username and port.
            String sub = (h.username.isEmpty() ? "" : h.username + "@") + h.address + (h.port > 0 ? ":" + h.port : "");
            addRow(icon, true, h.getTitle(), sub, R.color.terminow_text_secondary, () -> {
                mDialog.dismiss();
                mHosts.connect(h);   // existing per-protocol flow
            }, () -> { mDialog.dismiss(); mHosts.edit(h); });
        }
    }

    // ---- Row + helpers --------------------------------------------------------------------------

    private boolean matches(@NonNull String a, @NonNull String b) {
        return mQuery.isEmpty() || a.toLowerCase(Locale.ROOT).contains(mQuery) || b.toLowerCase(Locale.ROOT).contains(mQuery);
    }

    private boolean matchesHost(@NonNull TerminowStore.Host h) {
        if (mQuery.isEmpty()) return true;
        String hay = (h.getTitle() + " " + h.address + " " + h.username).toLowerCase(Locale.ROOT);
        return hay.contains(mQuery);
    }

    private void addRow(int iconRes, boolean tint, @NonNull String title, @NonNull String subtitle, int subColor, @NonNull Runnable onClick) {
        addRow(iconRes, tint, title, subtitle, subColor, onClick, null);
    }

    private void addRow(int iconRes, boolean tint, @NonNull String title, @NonNull String subtitle, int subColor,
                        @NonNull Runnable onClick, @Nullable Runnable onLongClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable cbg = new GradientDrawable();
        cbg.setColor(color(R.color.terminow_quick_card));
        cbg.setCornerRadius(dp(12));
        cbg.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
        row.setBackground(cbg);
        row.setClickable(true);
        row.setOnClickListener(v -> onClick.run());
        if (onLongClick != null) row.setOnLongClickListener(v -> { onLongClick.run(); return true; });

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (tint) icon.setColorFilter(color(R.color.terminow_text));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
        ilp.rightMargin = dp(12);
        row.addView(icon, ilp);

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 15, R.color.terminow_text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t);
        if (!subtitle.isEmpty()) {
            TextView s = text(subtitle, 12, subColor);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(s);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setContentDescription(title + (subtitle.isEmpty() ? "" : ", " + subtitle));
        LinearLayout.LayoutParams rlp = matchWrap();
        rlp.topMargin = dp(8);
        mList.addView(row, rlp);
    }

    private void section(@NonNull String label) {
        TextView v = text(label, 12, R.color.terminow_text_secondary);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(dp(2), dp(14), 0, dp(2));
        v.setAllCaps(true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(v, true);
        mList.addView(v);
    }

    private void note(@NonNull String s) {
        TextView v = text(s, 13, R.color.terminow_text_secondary);
        v.setPadding(dp(2), dp(16), dp(2), dp(8));
        mList.addView(v, matchWrap());
    }

    private void addAction(@NonNull String label, @NonNull Runnable onClick) {
        TextView b = text(label, 15, R.color.terminow_bar_home);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_panel_accent));
        shape.setCornerRadius(dp(12));
        b.setBackground(shape);
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(12);
        mList.addView(b, lp);
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView v = new TextView(mActivity);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color(colorRes));
        return v;
    }

    @NonNull
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) { return ContextCompat.getColor(mActivity, res); }
    private int dp(float v) { return Math.round(v * mActivity.getResources().getDisplayMetrics().density); }
}
