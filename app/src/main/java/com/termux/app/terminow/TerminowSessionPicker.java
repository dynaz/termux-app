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
