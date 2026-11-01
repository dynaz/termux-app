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
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
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
        dismissKeyboard();
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
        sheet.content.addView(tmuxToggleRow(env));

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

        // tmux sessions discovered inside the selected distro, kept separate from the app's terminal
        // tabs above. Discovery and all actions reuse the launcher's existing tmux manager; this only
        // renders them here. The check is Premium-gated and async, so it is a no-op for Free users and
        // refreshes the list for the next time the sheet opens.
        mLauncher.listTmux(env, false);
        TerminowTmux.State tmux = mLauncher.tmuxOf(env);
        boolean hasTmuxSessions = tmux != null && tmux.isInstalled() && !tmux.sessions.isEmpty();
        if (mLauncher.usesTmux(env) || hasTmuxSessions) {
            sheet.section(R.string.terminow_local_linux_tmux_sessions);
            if (hasTmuxSessions) {
                for (TerminowTmux.Session s : tmux.sessions) sheet.content.addView(tmuxSessionRow(sheet, env, s));
            } else if (tmux == null || mLauncher.isTmuxChecking(env)) {
                sheet.note(mActivity.getString(R.string.terminow_tools_tmux_checking));
            } else {
                sheet.note(mActivity.getString(R.string.terminow_tools_tmux_none));
            }
        }

        // Recently closed sessions of this distribution, in a compact collapsible list.
        List<TerminowStore.RecentSession> recent = new java.util.ArrayList<>();
        for (TerminowStore.RecentSession s : mStore.getRecentSessions())
            if (s.environmentKey.equals(env.key)) recent.add(s);
        if (!recent.isEmpty()) recentlyClosed(sheet, env, recent);

        sheet.section(R.string.terminow_local_linux_manage);
        sheet.option(R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_local_linux_install),
            mActivity.getString(R.string.terminow_local_linux_install_summary), mLauncher::browseLinux);
        sheet.show();
    }

    /** A collapsible "Recently closed" list; tapping a row opens a NEW shell (no process is resumed). */
    private void recentlyClosed(@NonNull Sheet sheet, @NonNull TerminowLauncher.Environment env,
                                @NonNull List<TerminowStore.RecentSession> recent) {
        LinearLayout body = new LinearLayout(mActivity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);

        TextView header = text("▸  " + mActivity.getString(R.string.terminow_local_linux_recent), 13, R.color.terminow_text_secondary);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, dp(16), 0, dp(4));
        header.setMinHeight(dp(48));
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription(mActivity.getString(R.string.terminow_local_linux_recent));
        header.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            header.setText((show ? "▾  " : "▸  ") + mActivity.getString(R.string.terminow_local_linux_recent));
        });
        sheet.content.addView(header);

        body.addView(text(mActivity.getString(R.string.terminow_local_linux_recent_note), 12, R.color.terminow_text_secondary));
        for (TerminowStore.RecentSession s : recent) {
            String sub = s.directory.isEmpty() ? "" : s.directory;
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setMinimumHeight(dp(48));
            row.setClickable(true);
            row.setFocusable(true);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(color(R.color.terminow_quick_card));
            shape.setCornerRadius(dp(12));
            shape.setStroke(Math.max(1, dp(1)), color(R.color.terminow_quick_border));
            row.setBackground(shape);
            TextView name = text(s.name.isEmpty() ? env.shortName() : s.name, 15, R.color.terminow_text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(name);
            if (!sub.isEmpty()) row.addView(text(sub, 12, R.color.terminow_text_secondary));
            row.setContentDescription((s.name.isEmpty() ? env.shortName() : s.name) + ". " + mActivity.getString(R.string.terminow_local_linux_recent_note));
            final String dir = s.directory;
            row.setOnClickListener(v -> {
                sheet.dialog.dismiss();
                mLauncher.openNewTerminalAt(env, dir);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            body.addView(row, lp);
        }
        sheet.content.addView(body);
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
        TextView name = text(env.shortName(), 16, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        // Subtitle is the detected installed version only, so the name is not repeated (no
        // "Ubuntu 24.04.5 LTS · Ubuntu 24.04"); fall back to the full label when no version is known.
        String version = mLauncher.environmentVersion(env);
        String subtitle = !version.isEmpty() ? version
            : (env.label.equals(env.shortName()) ? "" : env.label);
        if (!subtitle.isEmpty()) texts.addView(text(subtitle, 13, R.color.terminow_text_secondary));
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

    /**
     * The per-distro "Use tmux" settings row: a terminal icon, the title and subtitle, an info button,
     * and an adaptive toggle bound to the environment's saved {@code tmux:<key>} preference. The toggle
     * affects that distribution's <b>new</b> sessions only; it never touches a running session. Turning
     * it on is gated by Premium (Feature.TMUX), exactly like launching into tmux.
     */
    private View tmuxToggleRow(@NonNull TerminowLauncher.Environment env) {
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
        rowParams.topMargin = dp(10);
        row.setLayoutParams(rowParams);

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_terminow_terminal);
        icon.setColorFilter(color(R.color.terminow_text));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(4), 0);
        TextView title = text(mActivity.getString(R.string.terminow_tmux_use), 15, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        texts.addView(text(mActivity.getString(R.string.terminow_tmux_use_summary), 13, R.color.terminow_text_secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView info = overflowButton(mActivity.getString(R.string.terminow_tmux_info_title), this::showTmuxInfo);
        info.setImageResource(R.drawable.ic_terminow_info);
        info.setColorFilter(color(R.color.terminow_text_secondary));
        row.addView(info, new LinearLayout.LayoutParams(dp(48), dp(48)));

        final Switch toggle = new Switch(mActivity);
        toggle.setChecked(mLauncher.usesTmux(env));
        toggle.setContentDescription(mActivity.getString(R.string.terminow_tmux_use));
        toggle.setMinimumHeight(dp(48));
        final CompoundButton.OnCheckedChangeListener[] listener = new CompoundButton.OnCheckedChangeListener[1];
        listener[0] = (btn, checked) -> {
            if (checked && !TerminowAccountUi.isPremium(mStore)) {
                // Not Premium: revert the visual state without re-entering this listener, then offer upgrade.
                setCheckedSilently(toggle, listener[0], false);
                TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.TMUX, 0, () -> {
                    mLauncher.setTmuxPreference(env, true);
                    setCheckedSilently(toggle, listener[0], true);
                });
                return;
            }
            mLauncher.setTmuxPreference(env, checked);
        };
        toggle.setOnCheckedChangeListener(listener[0]);
        row.addView(toggle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void setCheckedSilently(@NonNull Switch toggle,
                                           @NonNull CompoundButton.OnCheckedChangeListener listener, boolean checked) {
        toggle.setOnCheckedChangeListener(null);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
    }

    /** Hide the soft keyboard before the sheet opens, so it does not cover the sheet or fight its layout. */
    private void dismissKeyboard() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) mActivity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            View focus = mActivity.getCurrentFocus();
            if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        } catch (RuntimeException ignored) {
            // Best-effort; never block opening the sheet on an input-method quirk.
        }
    }

    private void showTmuxInfo() {
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_tmux_info_title)
            .setMessage(R.string.terminow_tmux_info_body)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    /**
     * A discovered tmux-session row (separate from the app-terminal-tab rows above): the session name,
     * its window count and Attached/Detached state, a tap to attach, and an overflow for rename / detach
     * / end. Attaching and the actions reuse the launcher's tmux manager — nothing is reimplemented here.
     */
    @NonNull
    private View tmuxSessionRow(@NonNull Sheet sheet, @NonNull TerminowLauncher.Environment env,
                                @NonNull TerminowTmux.Session session) {
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
        icon.setColorFilter(color(R.color.terminow_panel_accent));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(4), 0);
        TextView nameView = text(session.name, 15, R.color.terminow_text);
        nameView.setTypeface(Typeface.MONOSPACE);
        texts.addView(nameView);
        boolean attached = session.attached > 0;
        String state = mActivity.getString(attached ? R.string.terminow_tools_tmux_attached : R.string.terminow_tools_tmux_detached,
            String.valueOf(session.windows));
        texts.addView(text(state, 13, R.color.terminow_text_secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        boolean manageable = TerminowLaunchPlan.isValidTmuxName(session.name);
        row.setContentDescription(session.name + ". " + state);
        if (manageable) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> attachTmux(sheet, env, session));
            ImageView overflow = overflowButton(mActivity.getString(R.string.terminow_local_linux_session_menu, session.name),
                () -> mLauncher.tmuxActions(env, session));
            row.addView(overflow, new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else {
            texts.addView(text(mActivity.getString(R.string.terminow_tools_tmux_by_hand), 12, R.color.terminow_text_secondary));
        }
        return row;
    }

    /** Attach to a tmux session; if it is already attached elsewhere, explain the shared-view behaviour first. */
    private void attachTmux(@NonNull Sheet sheet, @NonNull TerminowLauncher.Environment env,
                            @NonNull TerminowTmux.Session session) {
        Runnable open = () -> { sheet.dialog.dismiss(); mLauncher.launchTmux(env, session.name); };
        if (session.attached > 0) {
            new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(session.name)
                .setMessage(R.string.terminow_tmux_attach_shared)
                .setPositiveButton(R.string.terminow_tools_tmux_attach, (d, w) -> open.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } else {
            open.run();
        }
    }

    /**
     * The branded "Select OS" picker: one card per installed distribution (logo, short name, detected
     * version, Free/Premium badge, running-session count, selected checkmark), an "Installed" section,
     * and an "Add distribution" action. Selecting a distribution only re-opens the session manager on
     * it — it never launches a terminal — and its saved tmux preference follows the distribution.
     */
    private void pickDistro(@NonNull List<TerminowLauncher.Environment> distros) {
        String selectedKey = mStore.getLastLocalEnvironment();
        Sheet sheet = new Sheet(mActivity.getString(R.string.terminow_select_os_title));

        // Search only when the list is long enough to need it.
        final LinearLayout listHolder = new LinearLayout(mActivity);
        listHolder.setOrientation(LinearLayout.VERTICAL);
        if (distros.size() > 8) {
            final android.widget.EditText search = new android.widget.EditText(mActivity);
            search.setHint(R.string.terminow_select_os_search);
            search.setSingleLine(true);
            search.setMinHeight(dp(48));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.bottomMargin = dp(8);
            sheet.content.addView(search, sp);
            search.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    fillDistroCards(listHolder, distros, selectedKey, sheet, s.toString().trim().toLowerCase(java.util.Locale.ROOT));
                }
            });
        }
        sheet.section(R.string.terminow_select_os_installed);
        sheet.content.addView(listHolder);
        fillDistroCards(listHolder, distros, selectedKey, sheet, "");

        sheet.section(R.string.terminow_select_os_add);
        sheet.option(R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_local_linux_install),
            mActivity.getString(R.string.terminow_local_linux_install_summary), () -> { sheet.dialog.dismiss(); mLauncher.browseLinux(); });
        sheet.show();
    }

    private void fillDistroCards(@NonNull LinearLayout holder, @NonNull List<TerminowLauncher.Environment> distros,
                                 @Nullable String selectedKey, @NonNull Sheet sheet, @NonNull String filter) {
        holder.removeAllViews();
        for (TerminowLauncher.Environment e : distros) {
            if (!filter.isEmpty()
                && !e.shortName().toLowerCase(java.util.Locale.ROOT).contains(filter)
                && !e.label.toLowerCase(java.util.Locale.ROOT).contains(filter)) continue;
            holder.addView(distroCard(sheet, e, e.key.equals(selectedKey)));
        }
    }

    /** One branded, selectable card in the "Select OS" picker. */
    @NonNull
    private View distroCard(@NonNull Sheet sheet, @NonNull TerminowLauncher.Environment env, boolean selected) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(12), dp(12));
        row.setMinimumHeight(dp(56));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color(R.color.terminow_quick_card));
        shape.setCornerRadius(dp(14));
        shape.setStroke(Math.max(1, dp(selected ? 2 : 1)), color(selected ? R.color.terminow_panel_accent : R.color.terminow_quick_border));
        row.setBackground(shape);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(8);
        row.setLayoutParams(rowParams);

        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(distroIcon(mLauncher.environmentDistroId(env)));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        TextView name = text(env.shortName(), 16, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        String version = mLauncher.environmentVersion(env);
        int running = mLauncher.sessionsOf(env).size();
        StringBuilder sub = new StringBuilder(version);
        if (running > 0) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(mActivity.getString(R.string.terminow_select_os_running, running));
        }
        if (sub.length() > 0) texts.addView(text(sub.toString(), 13, R.color.terminow_text_secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        row.addView(tierChip(mLauncher.environmentDistroId(env)));
        if (selected) {
            TextView check = text("✓", 18, R.color.terminow_panel_accent);
            check.setTypeface(Typeface.DEFAULT_BOLD);
            check.setPadding(dp(10), 0, dp(4), 0);
            row.addView(check);
        }

        String desc = env.shortName() + (version.isEmpty() ? "" : " " + version)
            + (selected ? ". " + mActivity.getString(R.string.terminow_select_os_selected) : "");
        row.setContentDescription(desc);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            // Selecting a distribution switches the manager to it; it does NOT launch a terminal.
            mStore.setLastLocalEnvironment(env.key);
            sheet.dialog.dismiss();
            showLocalLinux();
        });
        return row;
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
