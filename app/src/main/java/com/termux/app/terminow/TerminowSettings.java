package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.settings.properties.SharedProperties;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.view.ViewUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The settings page of the {@link TerminowHome} screen. It shows the settings that are most used,
 * the rest are in {@link SettingsActivity} and the `termux.properties` file.
 */
public class TerminowSettings {

    private static final int[] TRANSCRIPT_ROWS = {1000, 2000, 5000, 10000, 20000, 50000};
    private static final int[] CURSOR_BLINK_RATES = {0, 1000, 600, 300};
    private static final int[] BELL_BEHAVIOURS = {
        TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_VIBRATE,
        TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_BEEP,
        TermuxPropertyConstants.IVALUE_BELL_BEHAVIOUR_IGNORE};
    private static final String[] BELL_BEHAVIOUR_VALUES = {
        TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_VIBRATE,
        TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_BEEP,
        TermuxPropertyConstants.VALUE_BELL_BEHAVIOUR_IGNORE};
    private static final int[] KEEP_ALIVE_INTERVALS = {0, 30, 60, 120, 300, 600};
    private static final int[] KEEP_ALIVE_COUNTS = {3, 5, 10, 20};

    private static final String LOG_TAG = "TerminowSettings";

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final LinearLayout mContainer;

    private final TerminowAccountUi mAccountUi;
    @Nullable private Runnable mVaultSettings;

    public TerminowSettings(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull LinearLayout container) {
        mActivity = activity;
        mStore = store;
        mContainer = container;
        mAccountUi = new TerminowAccountUi(activity, store, this::update);
    }

    /** What the Debug section does, which the home screen provides. */
    public interface DebugActions {
        void showDiagnostics();

        void setRecording(boolean recording);

        void saveLog();
    }

    @Nullable private DebugActions mDebug;

    public void setDebugActions(@NonNull DebugActions actions) {
        mDebug = actions;
    }

    /** The row of the vault password and syncing, which the vault switcher provides. */
    @Nullable private Runnable mWhatsNew;

    /** The window of what is new in this version, opened from the About rows. */
    public void setWhatsNew(@NonNull Runnable showWhatsNew) {
        mWhatsNew = showWhatsNew;
    }

    public void setVaultSettings(@NonNull Runnable showVaultSettings) {
        mVaultSettings = showVaultSettings;
    }

    /** Apply the setting to block screenshots and screen recording of the app. */
    public static void applyScreenshotsBlocked(@NonNull TermuxActivity activity, @NonNull TerminowStore store) {
        if (store.areScreenshotsBlocked())
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    /** Show the settings with their current values. */
    public void update() {
        mContainer.removeAllViews();
        TermuxAppSharedPreferences preferences = mActivity.getPreferences();
        if (preferences == null) return;


        addSection(R.string.terminow_account, true);
        mAccountUi.addRows(this::addRow);
        if (mVaultSettings != null)
            addRow(R.string.terminow_vault_settings, mActivity.getString(R.string.terminow_vault_settings_summary), mVaultSettings);
        if (mActivity.getTerminowHome() != null)
            addRow(R.string.terminow_ai_integration, mActivity.getString(R.string.terminow_ai_integration_summary), () -> {
                TerminowHome home = mActivity.getTerminowHome();
                if (home != null) new TerminowAiSettings(mActivity, home.getAiBridge(), mStore).show();
            });

        addSection(R.string.terminow_settings_terminal, false);

        String theme = mStore.getTerminalTheme();
        String font = mStore.getTerminalFont();
        addRow(R.string.terminow_settings_font_and_colours,
            (theme.isEmpty() ? TerminowThemes.DEFAULT_THEME_NAME : theme) + " / " + preferences.getFontSize() + " / " +
                (font.isEmpty() ? mActivity.getString(R.string.terminow_theme_font_default) : font),
            () -> new TerminowThemeDialog(mActivity, mStore, this::update).show());

        addSwitchRow(R.string.terminow_settings_show_folder, R.string.terminow_settings_show_folder_summary,
            mStore.shouldShowFolderOnStartup(), mStore::setShowFolderOnStartup);

        addRow(R.string.terminow_settings_default_profile, TerminowLaunchProfilesUi.describeDefault(mActivity, mStore),
            () -> TerminowLaunchProfilesUi.pickDefault(mActivity, mStore, this::update));

        final String[] launchValues = {TerminowStore.UBUNTU_LAUNCH_ASK, TerminowStore.UBUNTU_LAUNCH_RESUME, TerminowStore.UBUNTU_LAUNCH_CURRENT,
            TerminowStore.UBUNTU_LAUNCH_NEW};
        final String[] launchLabels = {mActivity.getString(R.string.terminow_launch_behavior_ask),
            mActivity.getString(R.string.terminow_picker_resume), mActivity.getString(R.string.terminow_launch_use_current),
            mActivity.getString(R.string.terminow_launch_open_new)};
        addRow(TerminowWhatsNew.label(mActivity, mActivity.getText(R.string.terminow_launch_behavior), TerminowWhatsNew.Feature.UBUNTU_TAB),
            launchLabels[Math.max(0, indexOf(launchValues, mStore.getUbuntuLaunchBehavior()))], () -> {
                TerminowWhatsNew.seen(mActivity, TerminowWhatsNew.Feature.UBUNTU_TAB);
                update();
                pick(R.string.terminow_launch_behavior, launchLabels, which -> {
                    mStore.setUbuntuLaunchBehavior(launchValues[which]);
                    update();
                });
            });

        // Open Claude is remembered apart from Open Ubuntu; Ask every time forgets it
        final String[] claudeValues = {TerminowStore.CLAUDE_LAUNCH_ASK, TerminowStore.CLAUDE_LAUNCH_RESUME, TerminowStore.CLAUDE_LAUNCH_NEW,
            TerminowStore.CLAUDE_LAUNCH_CONTINUE};
        final String[] claudeLabels = {mActivity.getString(R.string.terminow_launch_behavior_ask),
            mActivity.getString(R.string.terminow_picker_claude_resume), mActivity.getString(R.string.terminow_picker_claude_new),
            mActivity.getString(R.string.terminow_picker_claude_continue)};
        addRow(R.string.terminow_claude_launch_behavior, claudeLabels[Math.max(0, indexOf(claudeValues, mStore.getClaudeLaunchBehavior()))], () ->
            pick(R.string.terminow_claude_launch_behavior, claudeLabels, which -> {
                mStore.setClaudeLaunchBehavior(claudeValues[which]);
                update();
            }));

        // The shell of new tabs of local Ubuntu; not chosen yet shows Bash and keeps the shell Ubuntu gives
        final String[] shellValues = {TerminowStore.UBUNTU_SHELL_FISH, TerminowStore.UBUNTU_SHELL_BASH};
        final String[] shellLabels = {mActivity.getString(R.string.terminow_settings_ubuntu_shell_fish),
            mActivity.getString(R.string.terminow_settings_ubuntu_shell_bash)};
        String shell = mStore.getUbuntuShell();
        addRow(R.string.terminow_settings_ubuntu_shell,
            (TerminowStore.UBUNTU_SHELL_FISH.equals(shell) ? shellLabels[0] : shellLabels[1]) + " · " +
                mActivity.getString(R.string.terminow_settings_ubuntu_shell_summary), () ->
            pick(R.string.terminow_settings_ubuntu_shell, shellLabels, which -> {
                mStore.setUbuntuShell(shellValues[which]);
                mActivity.showToast(mActivity.getString(R.string.terminow_settings_ubuntu_shell_changed, shellLabels[which]), true);
                update();
            }));

        int[] timeouts = TerminowStore.UBUNTU_START_TIMEOUTS;
        String[] timeoutLabels = new String[timeouts.length];
        for (int i = 0; i < timeouts.length; i++) timeoutLabels[i] = mActivity.getString(R.string.terminow_launch_timeout_seconds, timeouts[i]);
        addRow(R.string.terminow_launch_timeout, mActivity.getString(R.string.terminow_launch_timeout_seconds, mStore.getUbuntuStartTimeoutSeconds()), () ->
            pick(R.string.terminow_launch_timeout, timeoutLabels, which -> {
                mStore.setUbuntuStartTimeoutSeconds(timeouts[which]);
                update();
            }));

        addSwitchRow(R.string.terminow_settings_start_in_tmux, R.string.terminow_settings_start_in_tmux_summary,
            mStore.shouldStartInTmux(), mStore::setStartInTmux);

        addSwitchRow(R.string.terminow_settings_extra_keys, R.string.terminow_settings_extra_keys_summary,
            preferences.shouldShowTerminalToolbar(), preferences::setShowTerminalToolbar);

        int transcriptRows = mActivity.getProperties().getTerminalTranscriptRows();
        addRow(R.string.terminow_settings_transcript_rows, mActivity.getString(R.string.terminow_settings_lines, transcriptRows), () ->
            pick(R.string.terminow_settings_transcript_rows, getLabels(TRANSCRIPT_ROWS, R.string.terminow_settings_lines, 0),
                which -> setProperty(TermuxPropertyConstants.KEY_TERMINAL_TRANSCRIPT_ROWS, String.valueOf(TRANSCRIPT_ROWS[which]))));

        final String[] bellLabels = {mActivity.getString(R.string.terminow_settings_bell_vibrate),
            mActivity.getString(R.string.terminow_settings_bell_beep), mActivity.getString(R.string.terminow_settings_bell_ignore)};
        addRow(R.string.terminow_settings_bell, bellLabels[Math.max(0, indexOf(BELL_BEHAVIOURS, mActivity.getProperties().getBellBehaviour()))], () ->
            pick(R.string.terminow_settings_bell, bellLabels,
                which -> setProperty(TermuxPropertyConstants.KEY_BELL_BEHAVIOUR, BELL_BEHAVIOUR_VALUES[which])));

        final String[] blinkLabels = {mActivity.getString(R.string.terminow_settings_disabled), mActivity.getString(R.string.terminow_settings_cursor_slow),
            mActivity.getString(R.string.terminow_settings_cursor_normal), mActivity.getString(R.string.terminow_settings_cursor_fast)};
        int blinkRate = mActivity.getProperties().getTerminalCursorBlinkRate();
        int blinkIndex = indexOf(CURSOR_BLINK_RATES, blinkRate);
        addRow(R.string.terminow_settings_cursor_blink, blinkIndex >= 0 ? blinkLabels[blinkIndex] : mActivity.getString(R.string.terminow_settings_milliseconds, blinkRate), () ->
            pick(R.string.terminow_settings_cursor_blink, blinkLabels,
                which -> setProperty(TermuxPropertyConstants.KEY_TERMINAL_CURSOR_BLINK_RATE, String.valueOf(CURSOR_BLINK_RATES[which]))));

        addSwitchRow(R.string.terminow_settings_keep_screen_on, R.string.terminow_settings_keep_screen_on_summary,
            preferences.shouldKeepScreenOn(), value -> {
                preferences.setKeepScreenOn(value);
                mActivity.getTerminalView().setKeepScreenOn(value);
            });


        addSection(R.string.terminow_settings_session, false);

        int keepAliveInterval = mStore.getKeepAliveInterval();
        addRow(R.string.terminow_settings_keep_alive_interval, getKeepAliveIntervalLabel(keepAliveInterval), () ->
            pick(R.string.terminow_settings_keep_alive_interval, getLabels(KEEP_ALIVE_INTERVALS, R.string.terminow_settings_seconds, R.string.terminow_settings_disabled),
                which -> {
                    mStore.setKeepAliveInterval(KEEP_ALIVE_INTERVALS[which]);
                    update();
                }));

        addRow(R.string.terminow_settings_keep_alive_count, String.valueOf(mStore.getKeepAliveCountMax()), () ->
            pick(R.string.terminow_settings_keep_alive_count, getLabels(KEEP_ALIVE_COUNTS, 0, 0),
                which -> {
                    mStore.setKeepAliveCountMax(KEEP_ALIVE_COUNTS[which]);
                    update();
                }));


        addSection(R.string.terminow_settings_notification, false);

        addRow(R.string.terminow_settings_notifications, mActivity.getString(R.string.terminow_settings_notifications_summary), () -> {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, mActivity.getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + mActivity.getPackageName()));
            }
            ActivityUtils.startActivity(mActivity, intent);
        });


        addSection(R.string.terminow_settings_security, false);

        addSwitchRow(R.string.terminow_settings_block_screenshots, R.string.terminow_settings_block_screenshots_summary,
            mStore.areScreenshotsBlocked(), value -> {
                mStore.setScreenshotsBlocked(value);
                applyScreenshotsBlocked(mActivity, mStore);
            });


        addSection(R.string.terminow_settings_volume_keys, false);

        addSwitchRow(R.string.terminow_settings_volume_keys_virtual, R.string.terminow_settings_volume_keys_virtual_summary,
            !mActivity.getProperties().areVirtualVolumeKeysDisabled(), value ->
                setProperty(TermuxPropertyConstants.KEY_VOLUME_KEYS_BEHAVIOUR, value ?
                    TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VIRTUAL : TermuxPropertyConstants.IVALUE_VOLUME_KEY_BEHAVIOUR_VOLUME));


        addSection(R.string.terminow_debug_section, false);
        DebugActions debug = mDebug;
        addRow(TerminowWhatsNew.label(mActivity, mActivity.getText(R.string.terminow_launch_diagnostics_title), TerminowWhatsNew.Feature.DEBUG),
            mActivity.getString(R.string.terminow_debug_diagnostics_summary), debug == null ? null : () -> {
                TerminowWhatsNew.seen(mActivity, TerminowWhatsNew.Feature.DEBUG);
                update();
                debug.showDiagnostics();
            });
        addSwitchRow(R.string.terminow_debug_record, R.string.terminow_debug_record_summary, TerminowDebugLog.isRecording(mActivity), value -> {
            if (debug != null) debug.setRecording(value);
            update();
        });
        java.io.File log = TerminowDebugLog.file(mActivity);
        addRow(R.string.terminow_debug_save, log.isFile() ? android.text.format.Formatter.formatShortFileSize(mActivity, log.length()) :
            mActivity.getString(R.string.terminow_debug_empty), debug != null ? debug::saveLog : null);
        addRow(R.string.terminow_debug_clear, null, () -> {
            TerminowDebugLog.clear(mActivity);
            mActivity.showToast(mActivity.getString(R.string.terminow_debug_cleared), false);
            update();
        });

        addSection(R.string.terminow_settings_about, false);

        addRow(R.string.terminow_settings_advanced, mActivity.getString(R.string.terminow_settings_advanced_summary),
            () -> ActivityUtils.startActivity(mActivity, new Intent(mActivity, SettingsActivity.class)));

        addRow(R.string.terminow_settings_help, null,
            () -> ActivityUtils.startActivity(mActivity, new Intent(mActivity, HelpActivity.class)));

        if (mWhatsNew != null) addRow(R.string.terminow_new_settings_row, null, mWhatsNew);
        addRow(R.string.terminow_settings_version, BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")", null);
        // Identifies the exact build that is installed, like for reporting a problem
        addRow(R.string.terminow_settings_build, BuildConfig.TERMINOW_BOOTSTRAP_RELEASE + " / " + BuildConfig.TERMINOW_COMMIT, null);
        String repairResult = com.termux.app.TerminowPrefixRepair.getLastResult(mActivity);
        addRow(R.string.terminow_settings_runtime_repair, repairResult != null ? repairResult : mActivity.getString(R.string.terminow_settings_runtime_repair_none), null);
    }



    @NonNull
    private String getKeepAliveIntervalLabel(int seconds) {
        return seconds <= 0 ? mActivity.getString(R.string.terminow_settings_disabled) : mActivity.getString(R.string.terminow_settings_seconds, seconds);
    }

    /** Get labels for values, with the format string resource if it is set and the zero label for a 0 value. */
    @NonNull
    private String[] getLabels(@NonNull int[] values, int formatRes, int zeroRes) {
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] == 0 && zeroRes != 0)
                labels[i] = mActivity.getString(zeroRes);
            else
                labels[i] = formatRes != 0 ? mActivity.getString(formatRes, values[i]) : String.valueOf(values[i]);
        }
        return labels;
    }

    private static int indexOf(@NonNull int[] values, int value) {
        for (int i = 0; i < values.length; i++)
            if (values[i] == value) return i;
        return -1;
    }

    private static int indexOf(@NonNull String[] values, @NonNull String value) {
        for (int i = 0; i < values.length; i++)
            if (values[i].equals(value)) return i;
        return -1;
    }

    private interface OnPicked {
        void onPicked(int which);
    }

    private void pick(int titleRes, @NonNull CharSequence[] labels, @NonNull OnPicked onPicked) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(titleRes)
            .setItems(labels, (dialog, which) -> onPicked.onPicked(which))
            .show();
    }



    /**
     * Set a property in the `termux.properties` file and reload the properties, like the user
     * editing the file and running `termux-reload-settings`.
     */
    private void setProperty(@NonNull String key, @NonNull String value) {
        File file = SharedProperties.getPropertiesFileFromList(TermuxConstants.TERMUX_PROPERTIES_FILE_PATHS_LIST, LOG_TAG);
        if (file == null)
            file = TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE;

        try {
            List<String> lines = new ArrayList<>();
            if (file.isFile()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null)
                        lines.add(line);
                }
            }

            // Replace the first line that sets the property and remove the others, since the
            // last one would be the one that is used
            Pattern pattern = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*[=:].*$");
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (!pattern.matcher(lines.get(i)).matches()) continue;
                if (replaced) {
                    lines.remove(i--);
                } else {
                    lines.set(i, key + " = " + value);
                    replaced = true;
                }
            }
            if (!replaced)
                lines.add(key + " = " + value);

            File directory = file.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs())
                throw new IOException("Failed to create \"" + directory + "\"");
            try (FileOutputStream out = new FileOutputStream(file)) {
                for (String line : lines)
                    out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set \"" + key + "\" in \"" + file + "\"", e);
            mActivity.showToast(e.getMessage() != null ? e.getMessage() : e.toString(), true);
            return;
        }

        mActivity.getProperties().loadTermuxPropertiesFromDisk();
        TermuxActivity.updateTermuxActivityStyling(mActivity, false);
        update();
    }



    private void addSection(int titleRes, boolean first) {
        if (!first) {
            View divider = new View(mActivity);
            divider.setBackgroundColor(ContextCompat.getColor(mActivity, R.color.terminow_surface));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            params.topMargin = dp(10);
            mContainer.addView(divider, params);
        }

        TextView titleView = new TextView(mActivity);
        titleView.setText(titleRes);
        titleView.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(first ? 18 : 22);
        params.bottomMargin = dp(8);
        mContainer.addView(titleView, params);
    }

    /** Add a row with a title and an optional summary under it. Returns the row to add a view at its end. */
    @NonNull
    private LinearLayout addRow(int titleRes, @Nullable String summary, @Nullable Runnable onClick) {
        return addRow(mActivity.getText(titleRes), summary, onClick);
    }

    @NonNull
    private LinearLayout addRow(@NonNull CharSequence title, @Nullable String summary, @Nullable Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58));
        row.setPadding(0, dp(8), dp(4), dp(8));

        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(mActivity);
        titleView.setText(title);
        titleView.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texts.addView(titleView);

        if (summary != null) {
            TextView summaryView = new TextView(mActivity);
            summaryView.setText(summary);
            summaryView.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            summaryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            summaryView.setPadding(0, dp(2), 0, 0);
            texts.addView(summaryView);
        }

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (onClick != null) {
            TypedValue background = new TypedValue();
            mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true);
            row.setBackgroundResource(background.resourceId);
            row.setOnClickListener(v -> onClick.run());
        }

        mContainer.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private interface OnSwitched {
        void onSwitched(boolean value);
    }

    private void addSwitchRow(int titleRes, int summaryRes, boolean checked, @NonNull OnSwitched onSwitched) {
        Switch switchView = new Switch(mActivity);
        switchView.setChecked(checked);
        int[][] states = {{android.R.attr.state_checked}, {}};
        switchView.setThumbTintList(new ColorStateList(states, new int[]{
            ContextCompat.getColor(mActivity, R.color.terminow_text), ContextCompat.getColor(mActivity, R.color.terminow_text)}));
        switchView.setTrackTintList(new ColorStateList(states, new int[]{
            ContextCompat.getColor(mActivity, R.color.terminow_accent), ContextCompat.getColor(mActivity, R.color.terminow_text_secondary)}));
        // The row toggles the switch, so that the whole row can be tapped
        switchView.setClickable(false);

        LinearLayout row = addRow(titleRes, mActivity.getString(summaryRes), () -> {
            switchView.setChecked(!switchView.isChecked());
            onSwitched.onSwitched(switchView.isChecked());
        });
        row.addView(switchView);
    }

    private int dp(float dp) {
        return (int) ViewUtils.dpToPx(mActivity, dp);
    }

    /** Check the Google Play purchases of this device, see {@link TerminowAccountUi#recoverPurchases()}. */
    @NonNull
    public TerminowAccountUi getAccountUi() {
        return mAccountUi;
    }

    public void recoverPurchases(@Nullable Runnable onDone) {
        mAccountUi.recoverPurchases(onDone);
    }

}
