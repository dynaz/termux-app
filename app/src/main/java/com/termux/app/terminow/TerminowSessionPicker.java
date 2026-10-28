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
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.linux.TerminowLinuxCatalog;
import com.termux.terminal.TerminalSession;

import java.util.List;

/**
 * What Open Ubuntu and Open Claude ask: go back to a tab that runs, open a new tab, pick one of
 * the tabs that run, and for Claude Code also the last or an earlier conversation, each in a new
 * tab. A bottom sheet on a phone, a dialog in the middle of a wider screen. The choice can be
 * remembered, apart for Ubuntu and for Claude Code; Settings goes back to asking.
 */
class TerminowSessionPicker {

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final TerminowLauncher mLauncher;

    TerminowSessionPicker(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull TerminowLauncher launcher) {
        mActivity = activity;
        mStore = store;
        mLauncher = launcher;
    }

    /** The picker for Open Ubuntu, or another Linux environment. */
    void showUbuntu(@NonNull TerminowLauncher.Environment environment) {
        List<TerminalSession> running = mLauncher.runningOf(environment, false);
        Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_picker_ubuntu_title, environment.shortName()));
        CheckBox remember = sheet.remember();
        if (!running.isEmpty()) {
            TerminalSession last = running.get(0);
            sheet.option(R.drawable.ic_terminow_terminal, mActivity.getString(R.string.terminow_picker_resume),
                describe(last), () -> {
                    if (remember.isChecked()) mStore.setUbuntuLaunchBehavior(TerminowStore.UBUNTU_LAUNCH_RESUME);
                    mLauncher.showRunning(last);
                });
        }
        sheet.option(R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_picker_new_tab),
            mActivity.getString(R.string.terminow_picker_new_tab_summary, environment.shortName()), () -> {
                if (remember.isChecked()) mStore.setUbuntuLaunchBehavior(TerminowStore.UBUNTU_LAUNCH_NEW);
                mLauncher.openInNewTab(environment);
            });
        sheet.option(R.drawable.ic_terminow_os_linux, mActivity.getString(R.string.terminow_launch_use_current),
            mActivity.getString(R.string.terminow_picker_current_summary), () -> {
                if (remember.isChecked()) mStore.setUbuntuLaunchBehavior(TerminowStore.UBUNTU_LAUNCH_CURRENT);
                mLauncher.openInCurrentTab(environment);
            });
        if (running.size() > 1) {
            sheet.section(R.string.terminow_picker_open_tabs);
            for (TerminalSession session : running)
                sheet.option(R.drawable.ic_terminow_terminal, nameOf(session), describe(session), () -> mLauncher.showRunning(session));
        }
        sheet.addRemember(remember);
        sheet.show();
    }

    /**
     * The Local Linux picker: choose an installed distribution, see its live sessions (focus one without
     * starting a process), and start a new session. Defaults to the last-used distribution; the choice of
     * distribution is remembered, but the sheet never remembers an action, so existing and new sessions
     * stay one tap apart. When nothing is installed it offers to install a distribution.
     */
    void showLocalLinux() {
        List<TerminowLauncher.Environment> distros = mLauncher.installedDistros();
        if (distros.isEmpty()) {
            Sheet empty = new Sheet(mActivity.getString(R.string.terminow_local_linux_title));
            empty.note(mActivity.getString(R.string.terminow_local_linux_none));
            empty.option(R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_local_linux_install),
                mActivity.getString(R.string.terminow_local_linux_install_summary), mLauncher::browseLinux);
            empty.show();
            return;
        }
        TerminowLauncher.Environment selected = null;
        String last = mStore.getLastLocalEnvironment();
        for (TerminowLauncher.Environment e : distros) if (e.key.equals(last)) selected = e;
        if (selected == null) selected = distros.get(0);
        mStore.setLastLocalEnvironment(selected.key);
        final TerminowLauncher.Environment env = selected;

        Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_local_linux_title));
        sheet.content.addView(distroSelector(sheet, env, distros));

        // The default action is a new session; Open in current tab is tucked into an overflow beside it.
        LinearLayout actions = new LinearLayout(mActivity);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(12);
        TextView newSession = primaryButton(mActivity.getString(R.string.terminow_local_linux_new_session), () -> {
            sheet.dialog.dismiss();
            mLauncher.openInNewTab(env);
        });
        actions.addView(newSession, new LinearLayout.LayoutParams(0, dp(48), 1f));
        ImageView more = overflowButton(mActivity.getString(R.string.terminow_local_linux_more), () -> {
            new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setItems(new CharSequence[]{mActivity.getString(R.string.terminow_launch_use_current)}, (d, w) -> {
                    sheet.dialog.dismiss();
                    mLauncher.openInCurrentTab(env);
                })
                .show();
        });
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        moreParams.leftMargin = dp(6);
        actions.addView(more, moreParams);
        sheet.content.addView(actions, actionsParams);

        // Live sessions of this distribution, the current one first, then most recently used.
        List<TerminalSession> sessions = mLauncher.sessionsOf(env);
        TerminalSession current = mActivity.getCurrentSession();
        sessions.sort((a, b) -> {
            if (a == current) return -1;
            if (b == current) return 1;
            return 0;
        });
        if (sessions.isEmpty()) {
            sheet.note(mActivity.getString(R.string.terminow_local_linux_no_sessions));
        } else {
            sheet.section(R.string.terminow_local_linux_sessions);
            for (TerminalSession session : sessions)
                sheet.content.addView(localSessionRow(sheet, session, session == current));
        }

        sheet.section(R.string.terminow_local_linux_manage);
        sheet.option(R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_local_linux_install),
            mActivity.getString(R.string.terminow_local_linux_install_summary), mLauncher::browseLinux);
        sheet.show();
    }

    /** The distribution selector: its icon, name and version, and a chevron to pick another installed one. */
    @NonNull
    private View distroSelector(@NonNull Sheet sheet, @NonNull TerminowLauncher.Environment env,
                                @NonNull List<TerminowLauncher.Environment> distros) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(56));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_quick_card));
        shape.setCornerRadius(dp(14));
        shape.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
        row.setBackground(shape);

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(distroIcon(mLauncher.environmentDistroId(env)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(env.label, 16, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        String version = mLauncher.environmentVersion(env);
        texts.addView(text(version.isEmpty() ? env.shortName() : env.shortName() + " " + version, 13, R.color.terminow_text_secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(tierChip(mLauncher.environmentDistroId(env)));

        // Only a chevron when there is more than one distribution to choose between.
        if (distros.size() > 1) {
            TextView chevron = text("▾", 16, R.color.terminow_text_secondary);
            chevron.setPadding(dp(8), 0, dp(8), 0);
            row.addView(chevron);
            row.setContentDescription(mActivity.getString(R.string.terminow_local_linux_choose_distro, env.label));
            row.setOnClickListener(v -> {
                sheet.dialog.dismiss();
                pickDistro(distros);
            });
            row.setMinimumHeight(dp(48));
        } else {
            row.setContentDescription(env.label);
        }
        return row;
    }

    /** Pick another installed distribution, then re-open the picker on it. */
    private void pickDistro(@NonNull List<TerminowLauncher.Environment> distros) {
        CharSequence[] labels = new CharSequence[distros.size()];
        for (int i = 0; i < distros.size(); i++) {
            TerminowLauncher.Environment e = distros.get(i);
            String version = mLauncher.environmentVersion(e);
            labels[i] = version.isEmpty() ? e.label : e.label + " · " + e.shortName() + " " + version;
        }
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_local_linux_title)
            .setItems(labels, (d, w) -> {
                mStore.setLastLocalEnvironment(distros.get(w).key);
                showLocalLinux();
            })
            .show();
    }

    /** A live-session row: its name, folder and state; a tap focuses it; an overflow renames it. */
    @NonNull
    private View localSessionRow(@NonNull Sheet sheet, @NonNull TerminalSession session, boolean current) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(10), dp(6), dp(10));
        row.setMinimumHeight(dp(56));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_quick_card));
        shape.setCornerRadius(dp(12));
        shape.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
        row.setBackground(shape);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_terminal);
        icon.setColorFilter(color(R.color.terminow_text));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(4), 0);
        String name = nameOf(session);
        TextView nameView = text(name, 15, R.color.terminow_text);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(nameView);
        TerminowLauncher.SessionInfo info = mLauncher.infoOf(session);
        String folder = info != null && !info.folder.isEmpty() ? info.folder : "";
        String state = mActivity.getString(current ? R.string.terminow_local_linux_current
            : mLauncher.isStarting(session) ? R.string.terminow_picker_state_starting : R.string.terminow_picker_state_running);
        String sub = folder.isEmpty() ? state : folder + " · " + state;
        texts.addView(text(sub, 13, current ? R.color.terminow_quick_ready : R.color.terminow_text_secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.setContentDescription(name + ". " + sub);
        row.setOnClickListener(v -> {
            sheet.dialog.dismiss();
            mLauncher.showRunning(session);
        });

        ImageView overflow = overflowButton(mActivity.getString(R.string.terminow_local_linux_session_menu, name), () ->
            new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setItems(new CharSequence[]{mActivity.getString(R.string.terminow_quick_rename_tab)}, (d, w) -> {
                    sheet.dialog.dismiss();
                    mActivity.getTermuxTerminalSessionClient().renameSession(session);
                })
                .show());
        row.addView(overflow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    /** A filled primary button, at least 48dp tall, no uppercase. */
    @NonNull
    private TextView primaryButton(@NonNull String label, @NonNull Runnable action) {
        TextView button = text(label, 15, R.color.terminow_bar_home);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_panel_accent));
        shape.setCornerRadius(dp(12));
        button.setBackground(shape);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** A ⋮ overflow icon with a 48dp touch target and an accessible label. */
    @NonNull
    private ImageView overflowButton(@NonNull String description, @NonNull Runnable action) {
        ImageView view = new ImageView(mActivity);
        view.setImageResource(R.drawable.ic_terminow_more);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setColorFilter(color(R.color.terminow_text_secondary));
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setContentDescription(description);
        TypedValue ripple = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        if (ripple.resourceId != 0) view.setBackgroundResource(ripple.resourceId);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private int distroIcon(@NonNull String distroId) {
        switch (distroId) {
            case "ubuntu": return R.drawable.ic_terminow_os_ubuntu;
            case "debian": return R.drawable.ic_terminow_os_debian;
            case "alpine": return R.drawable.ic_terminow_os_alpine;
            case "kali": return R.drawable.ic_terminow_os_kali;
            case "local": return R.drawable.ic_terminow_os_local;
            default: return R.drawable.ic_terminow_os_linux;
        }
    }

    /** A "Free" or crown "Premium" chip for a distribution, by its stable id. */
    @NonNull
    private TextView tierChip(@NonNull String distroId) {
        boolean premium = TerminowLinuxCatalog.requiresPremium(distroId);
        String label = mActivity.getString(premium ? R.string.terminow_distro_badge_premium : R.string.terminow_distro_badge_free);
        TextView chip = text(premium ? "👑 " + label : label, 11, premium ? R.color.terminow_panel_accent : R.color.terminow_text_secondary);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(color(R.color.terminow_field));
        chip.setBackground(bg);
        chip.setContentDescription(label);
        return chip;
    }

    /**
     * The picker for Open Claude. Going back to a tab only shows it; a conversation is Claude
     * Code started in a new tab with the flag that its --help lists, never typed into a tab.
     */
    void showClaude(@NonNull TerminowLauncher.Environment environment, @Nullable TerminowLauncher.ClaudeState state) {
        boolean supportsContinue = state != null && state.supportsContinue;
        boolean supportsResume = state != null && state.supportsResume;
        List<TerminalSession> running = mLauncher.runningOf(environment, true);
        Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_picker_claude_title, environment.shortName()));
        CheckBox remember = sheet.remember();
        if (!running.isEmpty()) {
            TerminalSession last = running.get(0);
            sheet.option(R.drawable.ic_terminow_tool_claude, mActivity.getString(R.string.terminow_picker_claude_resume),
                describe(last), () -> {
                    if (remember.isChecked()) mStore.setClaudeLaunchBehavior(TerminowStore.CLAUDE_LAUNCH_RESUME);
                    mLauncher.showRunning(last);
                });
        }
        sheet.option(R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_picker_claude_new),
            mActivity.getString(R.string.terminow_picker_claude_new_summary), () -> {
                if (remember.isChecked()) mStore.setClaudeLaunchBehavior(TerminowStore.CLAUDE_LAUNCH_NEW);
                mLauncher.launchClaudeInNewTab(environment, new String[0]);
            });
        sheet.section(R.string.terminow_picker_conversations);
        if (supportsContinue) {
            sheet.option(R.drawable.ic_terminow_tool_claude, mActivity.getString(R.string.terminow_picker_claude_continue),
                mActivity.getString(R.string.terminow_picker_claude_continue_summary), () -> {
                    if (remember.isChecked()) mStore.setClaudeLaunchBehavior(TerminowStore.CLAUDE_LAUNCH_CONTINUE);
                    mLauncher.launchClaudeInNewTab(environment, new String[]{"--continue"});
                });
        }
        if (supportsResume) {
            sheet.option(R.drawable.ic_terminow_list, mActivity.getString(R.string.terminow_picker_claude_pick),
                mActivity.getString(R.string.terminow_picker_claude_pick_summary),
                () -> mLauncher.launchClaudeInNewTab(environment, new String[]{"--resume"}));
        }
        if (!supportsContinue && !supportsResume)
            sheet.note(mActivity.getString(state == null || state.error != null ? R.string.terminow_picker_claude_flags_unknown :
                R.string.terminow_picker_claude_flags_missing));
        sheet.note(mActivity.getString(R.string.terminow_picker_claude_note));
        if (running.size() > 1) {
            sheet.section(R.string.terminow_picker_open_claude_tabs);
            for (TerminalSession session : running)
                sheet.option(R.drawable.ic_terminow_tool_claude, nameOf(session), describe(session), () -> mLauncher.showRunning(session));
        }
        sheet.addRemember(remember);
        sheet.show();
    }

    @NonNull
    private String nameOf(@NonNull TerminalSession session) {
        return session.mSessionName != null && !session.mSessionName.isEmpty() ? session.mSessionName : mActivity.getString(R.string.terminow_toolbar_unknown);
    }

    /** The environment, folder and state of a tab, as TermiNow knows them. */
    @NonNull
    private String describe(@NonNull TerminalSession session) {
        TerminowLauncher.SessionInfo info = mLauncher.infoOf(session);
        String state = mActivity.getString(mLauncher.isStarting(session) ? R.string.terminow_picker_state_starting : R.string.terminow_picker_state_running);
        String environment = info != null ? info.environmentLabel : "";
        String folder = info != null && !info.folder.isEmpty() ? info.folder : "";
        return mActivity.getString(R.string.terminow_picker_session_line, nameOf(session), environment, folder.isEmpty() ? "—" : folder, state);
    }

    /** A sheet: at the bottom of a phone, in the middle of a wider screen. */
    private final class Sheet {
        final Dialog dialog = new Dialog(mActivity);
        final LinearLayout content = new LinearLayout(mActivity);
        final boolean phone = mActivity.getResources().getConfiguration().screenWidthDp < 600;

        Sheet(@NonNull String title) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root = new LinearLayout(mActivity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(20), dp(16), dp(20), dp(20));
            GradientDrawable background = new GradientDrawable();
            background.setColor(color(R.color.terminow_bar_home));
            float radius = dp(20);
            background.setCornerRadii(phone ? new float[]{radius, radius, radius, radius, 0, 0, 0, 0} :
                new float[]{radius, radius, radius, radius, radius, radius, radius, radius});
            root.setBackground(background);
            LinearLayout head = new LinearLayout(mActivity);
            head.setGravity(Gravity.CENTER_VERTICAL);
            TextView titleView = text(title, 19, R.color.terminow_text);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            ViewCompat.setAccessibilityHeading(titleView, true);
            head.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageView close = new ImageView(mActivity);
            close.setImageResource(R.drawable.ic_terminow_close);
            close.setColorFilter(color(R.color.terminow_text));
            close.setScaleType(ImageView.ScaleType.CENTER);
            close.setContentDescription(mActivity.getString(R.string.terminow_profiles_close));
            close.setOnClickListener(v -> dialog.dismiss());
            head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
            root.addView(head);
            ScrollView scroll = new ScrollView(mActivity);
            content.setOrientation(LinearLayout.VERTICAL);
            scroll.addView(content);
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Above the navigation bar and the taskbar of foldables
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() |
                    androidx.core.view.WindowInsetsCompat.Type.tappableElement()).bottom;
                root.setPadding(dp(20), dp(16), dp(20), dp(20) + (phone ? bottom : 0));
                return insets;
            });
            dialog.setContentView(root);
        }

        @NonNull
        CheckBox remember() {
            CheckBox box = new CheckBox(mActivity);
            box.setText(R.string.terminow_launch_remember);
            box.setTextColor(color(R.color.terminow_text));
            box.setMinHeight(dp(48));
            return box;
        }

        void addRemember(@NonNull CheckBox box) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(8);
            content.addView(box, params);
        }

        void section(int label) {
            TextView view = text(mActivity.getString(label), 13, R.color.terminow_text_secondary);
            view.setTypeface(Typeface.DEFAULT_BOLD);
            view.setPadding(0, dp(16), 0, 0);
            ViewCompat.setAccessibilityHeading(view, true);
            content.addView(view);
        }

        void note(@NonNull String value) {
            TextView view = text(value, 12, R.color.terminow_text_secondary);
            view.setPadding(0, dp(8), 0, 0);
            content.addView(view);
        }

        void option(@DrawableRes int icon, @NonNull String title, @NonNull String summary, @NonNull Runnable action) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(10), dp(10), dp(10));
            row.setMinimumHeight(dp(56));
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color(R.color.terminow_quick_card));
            shape.setCornerRadius(dp(12));
            shape.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
            row.setBackground(shape);
            ImageView iconView = new ImageView(mActivity);
            iconView.setImageResource(icon);
            if (icon != R.drawable.ic_terminow_tool_claude && icon != R.drawable.ic_terminow_os_linux)
                iconView.setColorFilter(color(R.color.terminow_text));
            iconView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(iconView, new LinearLayout.LayoutParams(dp(28), dp(28)));
            LinearLayout texts = new LinearLayout(mActivity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(14), 0, dp(4), 0);
            TextView titleView = text(title, 15, R.color.terminow_text);
            titleView.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(titleView);
            if (!summary.isEmpty()) texts.addView(text(summary, 13, R.color.terminow_text_secondary));
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.setContentDescription(summary.isEmpty() ? title : title + ". " + summary);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                action.run();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(10);
            content.addView(row, params);
        }

        void show() {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int screen = mActivity.getResources().getDisplayMetrics().widthPixels;
                if (phone) {
                    window.setGravity(Gravity.BOTTOM);
                    window.setLayout(screen, ViewGroup.LayoutParams.WRAP_CONTENT);
                } else {
                    window.setLayout(Math.min(screen - dp(32), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            }
            dialog.show();
        }
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private int color(int id) {
        return ContextCompat.getColor(mActivity, id);
    }

    private int dp(float value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
