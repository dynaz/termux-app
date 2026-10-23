package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What is new after an update: a window on the first start of the home screen after it, the
 * same list in the bell, and a NEW mark on each new feature until it is opened. Nothing after a
 * fresh install. The marks and the window are kept in preferences of their own.
 */
final class TerminowWhatsNew {

    /** Where a feature is opened from the window. */
    enum Action { NONE, QUICK_LAUNCH, FILES, SETTINGS }

    /** A new feature, from the version it is announced in. */
    enum Feature {
        BACKUP(196, R.string.terminow_new_backup, R.string.terminow_new_backup_text, Action.QUICK_LAUNCH),
        UBUNTU_TAB(196, R.string.terminow_new_ubuntu_tab, R.string.terminow_new_ubuntu_tab_text, Action.SETTINGS),
        FILES(196, R.string.terminow_new_files, R.string.terminow_new_files_text, Action.FILES),
        FROZEN_TABS(196, R.string.terminow_new_frozen_tabs, R.string.terminow_new_frozen_tabs_text, Action.NONE),
        DEBUG(196, R.string.terminow_new_debug, R.string.terminow_new_debug_text, Action.SETTINGS),
        SWIPE(196, R.string.terminow_new_swipe, R.string.terminow_new_swipe_text, Action.NONE),
        INSTALLER(196, R.string.terminow_new_installer, R.string.terminow_new_installer_text, Action.NONE);

        final int since;
        final int titleRes;
        final int textRes;
        final Action action;

        Feature(int since, int titleRes, int textRes, Action action) {
            this.since = since;
            this.titleRes = titleRes;
            this.textRes = textRes;
            this.action = action;
        }
    }

    interface Opener {
        void open(@NonNull Action action);
    }

    private static final String PREFS = "terminow_whats_new";
    /** The version the user had before the features that are marked NEW. */
    private static final String KEY_FROM = "from";
    /** The version whose window is still to be shown, 0 for none. */
    private static final String KEY_PENDING_FROM = "pending_from";
    private static final String KEY_OFF = "off";
    private static final String KEY_SEEN = "seen";

    private TerminowWhatsNew() {}

    /** The features that are new for someone who had {@code from}, the newest version first. */
    @NonNull
    static List<Feature> since(int from, int current) {
        List<Feature> features = new ArrayList<>();
        for (Feature feature : Feature.values())
            if (feature.since > from && feature.since <= current) features.add(feature);
        Collections.sort(features, (a, b) -> Integer.compare(b.since, a.since));
        return features;
    }

    @NonNull
    private static SharedPreferences prefs(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Note the start of a version, with the one before it, 0 after a fresh install. Returns the
     * features to announce, empty when there are none.
     */
    @NonNull
    static List<Feature> onStart(@NonNull Context context, int lastVersionCode, int versionCode) {
        return onStart(prefs(context), lastVersionCode, versionCode);
    }

    @NonNull
    static List<Feature> onStart(@NonNull SharedPreferences prefs, int lastVersionCode, int versionCode) {
        SharedPreferences.Editor editor = prefs.edit();
        // A fresh install has nothing new; after updates the oldest version counts, so that a
        // mark stays until it is opened however many updates come
        if (!prefs.contains(KEY_FROM)) editor.putInt(KEY_FROM, lastVersionCode == 0 ? versionCode : lastVersionCode);
        List<Feature> features = lastVersionCode == 0 || lastVersionCode == versionCode ? Collections.emptyList() :
            since(lastVersionCode, versionCode);
        if (!features.isEmpty() && !prefs.getBoolean(KEY_OFF, false)) {
            int pending = prefs.getInt(KEY_PENDING_FROM, 0);
            editor.putInt(KEY_PENDING_FROM, pending == 0 ? lastVersionCode : Math.min(pending, lastVersionCode));
        }
        editor.apply();
        return features;
    }

    /** The text of the bell notification of an update: the new features, one a line. */
    @NonNull
    static String describe(@NonNull Context context, @NonNull List<Feature> features) {
        StringBuilder text = new StringBuilder();
        for (Feature feature : features) {
            if (text.length() > 0) text.append('\n');
            text.append("• ").append(context.getString(feature.titleRes));
        }
        return text.toString();
    }

    /** Whether to mark a feature NEW: new since the version the user had, and not opened yet. */
    static boolean isNew(@NonNull Context context, @NonNull Feature feature) {
        return isNew(prefs(context), feature);
    }

    static boolean isNew(@NonNull SharedPreferences prefs, @NonNull Feature feature) {
        return feature.since > prefs.getInt(KEY_FROM, Integer.MAX_VALUE) && !prefs.getStringSet(KEY_SEEN, Collections.emptySet()).contains(feature.name());
    }

    /** The feature was opened: no NEW mark anymore. */
    static void seen(@NonNull Context context, @NonNull Feature feature) {
        seen(prefs(context), feature);
    }

    static void seen(@NonNull SharedPreferences prefs, @NonNull Feature feature) {
        Set<String> seen = new HashSet<>(prefs.getStringSet(KEY_SEEN, Collections.emptySet()));
        if (seen.add(feature.name())) prefs.edit().putStringSet(KEY_SEEN, seen).apply();
    }

    /** A label with a NEW mark after it, when the feature is new. */
    @NonNull
    static CharSequence label(@NonNull Context context, @NonNull CharSequence label, @NonNull Feature feature) {
        if (!isNew(context, feature)) return label;
        SpannableStringBuilder text = new SpannableStringBuilder(label).append("  ");
        int start = text.length();
        text.append(context.getString(R.string.terminow_new_badge));
        text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(context, R.color.terminow_accent)), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new RelativeSizeSpan(0.75f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /** Show the window of an update once, if one is waiting. Returns whether it was shown. */
    static boolean showIfPending(@NonNull Activity activity, int versionCode, @NonNull Opener opener) {
        SharedPreferences prefs = prefs(activity);
        int from = prefs.getInt(KEY_PENDING_FROM, 0);
        if (from == 0) return false;
        prefs.edit().remove(KEY_PENDING_FROM).apply();
        if (prefs.getBoolean(KEY_OFF, false)) return false;
        List<Feature> features = since(from, versionCode);
        if (features.isEmpty()) return false;
        show(activity, features, true, opener);
        return true;
    }

    /** Show the features of this version, like from Settings. */
    static void showAll(@NonNull Activity activity, int versionCode, @NonNull Opener opener) {
        List<Feature> features = new ArrayList<>(Arrays.asList(Feature.values()));
        features.removeIf(feature -> feature.since > versionCode);
        show(activity, features, false, opener);
    }

    private static void show(@NonNull Activity activity, @NonNull List<Feature> features, boolean afterUpdate, @NonNull Opener opener) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * density);
        list.setPadding(pad, (int) (8 * density), pad, 0);
        AlertDialog[] dialog = new AlertDialog[1];
        for (Feature feature : features) {
            TextView title = new TextView(activity);
            title.setText(activity.getString(feature.titleRes));
            title.setTextColor(ContextCompat.getColor(activity, R.color.terminow_text));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            title.setTypeface(null, Typeface.BOLD);
            title.setPadding(0, (int) (12 * density), 0, 0);
            list.addView(title);
            TextView text = new TextView(activity);
            text.setText(activity.getString(feature.textRes));
            text.setTextColor(ContextCompat.getColor(activity, R.color.terminow_text_secondary));
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            list.addView(text);
            if (feature.action != Action.NONE) {
                TextView open = new TextView(activity);
                open.setText(R.string.terminow_new_open);
                open.setTextColor(ContextCompat.getColor(activity, R.color.terminow_accent));
                open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                open.setTypeface(null, Typeface.BOLD);
                open.setGravity(Gravity.CENTER_VERTICAL);
                open.setMinHeight((int) (40 * density));
                open.setOnClickListener(v -> {
                    if (dialog[0] != null) dialog[0].dismiss();
                    opener.open(feature.action);
                });
                list.addView(open);
            }
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
            .setTitle(activity.getString(R.string.terminow_new_title, com.termux.BuildConfig.VERSION_NAME))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null);
        if (afterUpdate)
            builder.setNeutralButton(R.string.terminow_new_off, (d, w) -> prefs(activity).edit().putBoolean(KEY_OFF, true).apply());
        dialog[0] = builder.show();
    }
}
