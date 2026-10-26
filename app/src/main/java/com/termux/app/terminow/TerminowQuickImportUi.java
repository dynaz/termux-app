package com.termux.app.terminow;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Quick Import flow: parse a chosen source into {@link TerminowImportConnection}s, preview them
 * with their category (valid, duplicate, unsupported, invalid or credentials-required), let the
 * person choose which to import, into which group, and how to handle duplicates, then persist the
 * selection as TermiNow hosts and offer a safe Undo. Parsing and persistence stay apart: the
 * {@link Delegate} owns the host store, and nothing connects automatically.
 */
public final class TerminowQuickImportUi {

    /** The bridge to the Hosts screen: the existing hosts, how to save, and how to undo. */
    public interface Delegate {
        @NonNull List<TerminowStore.Host> existingHosts();
        @NonNull List<String> existingGroups();
        @NonNull String defaultGroup();
        @Nullable String resolveKey(@NonNull String reference);

        /** Persist the import; return a Runnable that removes exactly these created hosts and groups. */
        @NonNull Runnable apply(@NonNull List<TerminowStore.Host> created,
                                @NonNull List<TerminowStore.Host> updatedExisting,
                                @NonNull Set<String> newGroups);

        /** Show the Hosts list filtered to the freshly imported hosts. */
        void viewImported(@NonNull List<TerminowStore.Host> created);
    }

    private final TermuxActivity mActivity;
    private final Delegate mDelegate;

    private List<TerminowImportConnection> mEntries = new ArrayList<>();
    private boolean[] mChecked = new boolean[0];
    private TerminowImportPlan.DuplicatePolicy mPolicy = TerminowImportPlan.DuplicatePolicy.SKIP;
    private boolean mImportPasswords = false;
    private boolean mPreserveFolders = true;
    private String mSearch = "";

    public TerminowQuickImportUi(@NonNull TermuxActivity activity, @NonNull Delegate delegate) {
        this.mActivity = activity;
        this.mDelegate = delegate;
    }

    /** Parse the picked file and show the preview. Passwords are decoded but only persisted on opt-in. */
    public void preview(@NonNull TerminowImportParsers.Source source, @NonNull String text, @NonNull String fileName) {
        List<TerminowImportConnection> entries;
        try {
            // Parse with passwords decoded into memory; whether they are saved is gated at persist time.
            entries = TerminowImportParsers.parse(source, text, fileName, true, mDelegate::resolveKey);
        } catch (IOException e) {
            mActivity.showToast(mActivity.getString(R.string.terminow_import_failed, e.getMessage()), true);
            return;
        }
        if (entries.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_qimport_none), true);
            return;
        }
        showEntries(source.label, entries);
    }

    /** Preview connections already fetched (used by cloud imports, which authenticate instead of parse). */
    public void previewConnections(@NonNull String sourceLabel, @NonNull List<TerminowImportConnection> entries) {
        if (entries.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_qimport_none), true);
            return;
        }
        showEntries(sourceLabel, entries);
    }

    private void showEntries(@NonNull String sourceLabel, @NonNull List<TerminowImportConnection> entries) {
        mEntries = entries;
        mChecked = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) mChecked[i] = defaultChecked(entries.get(i));
        showPreview(sourceLabel);
    }

    private boolean defaultChecked(@NonNull TerminowImportConnection c) {
        TerminowImportPlan.Category cat = TerminowImportPlan.categorize(c, isDuplicate(c));
        return TerminowImportPlan.importable(cat, mPolicy);
    }

    private boolean isDuplicate(@NonNull TerminowImportConnection c) {
        return TerminowImportPlan.isDuplicate(c, mDelegate.existingHosts());
    }

    private void showPreview(@NonNull String sourceLabel) {
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, dp(8), pad, 0);

        TextView counts = new TextView(mActivity);
        counts.setTextColor(color(R.color.terminow_text_secondary));
        counts.setPadding(0, 0, 0, dp(8));
        content.addView(counts);

        EditText search = new EditText(mActivity);
        search.setHint(R.string.terminow_qimport_search);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setSingleLine(true);
        content.addView(search);

        EditText group = new EditText(mActivity);
        group.setHint(R.string.terminow_qimport_group);
        group.setInputType(InputType.TYPE_CLASS_TEXT);
        group.setSingleLine(true);
        group.setText(mDelegate.defaultGroup());
        content.addView(group);

        CheckBox preserve = checkbox(content, R.string.terminow_qimport_preserve_folders, mPreserveFolders);
        CheckBox passwords = checkbox(content, R.string.terminow_qimport_passwords, mImportPasswords);
        CheckBox selectAll = checkbox(content, R.string.terminow_qimport_select_all, false);

        TextView policyLabel = new TextView(mActivity);
        policyLabel.setText(R.string.terminow_qimport_duplicates);
        policyLabel.setTextColor(color(R.color.terminow_text_secondary));
        policyLabel.setPadding(0, dp(8), 0, dp(2));
        content.addView(policyLabel);
        TextView policy = new TextView(mActivity);
        policy.setTextColor(color(R.color.terminow_panel_accent));
        policy.setMinHeight(dp(44));
        policy.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(policy);

        LinearLayout rows = new LinearLayout(mActivity);
        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView rowScroll = new ScrollView(mActivity);
        rowScroll.addView(rows);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(260));
        rowLp.topMargin = dp(8);
        content.addView(rowScroll, rowLp);

        ScrollView outer = new ScrollView(mActivity);
        outer.addView(content);

        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> {
            counts.setText(countsText());
            policy.setText(policyText());
            rows.removeAllViews();
            for (int i = 0; i < mEntries.size(); i++) {
                TerminowImportConnection c = mEntries.get(i);
                if (!matchesSearch(c)) continue;
                rows.addView(entryRow(i, c));
            }
        };

        search.addTextChangedListener(new SimpleWatcher(s -> {
            mSearch = s;
            rebuild[0].run();
        }));
        preserve.setOnCheckedChangeListener((b, v) -> mPreserveFolders = v);
        passwords.setOnCheckedChangeListener((b, v) -> mImportPasswords = v);
        selectAll.setOnCheckedChangeListener((b, v) -> {
            for (int i = 0; i < mEntries.size(); i++)
                if (v) mChecked[i] = TerminowImportPlan.importable(categoryOf(mEntries.get(i)), mPolicy);
                else mChecked[i] = false;
            rebuild[0].run();
        });
        policy.setOnClickListener(v -> {
            TerminowImportPlan.DuplicatePolicy[] all = TerminowImportPlan.DuplicatePolicy.values();
            mPolicy = all[(mPolicy.ordinal() + 1) % all.length];
            rebuild[0].run();
        });

        rebuild[0].run();

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_qimport_preview_title, sourceLabel))
            .setView(outer)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_qimport_import, (d, w) -> doImport(group.getText().toString().trim()))
            .show();
    }

    @NonNull
    private View entryRow(int index, @NonNull TerminowImportConnection c) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));

        TerminowImportPlan.Category category = categoryOf(c);
        boolean selectable = TerminowImportPlan.importable(category, mPolicy);

        CheckBox box = new CheckBox(mActivity);
        box.setChecked(mChecked[index] && selectable);
        box.setEnabled(selectable);
        box.setOnCheckedChangeListener((b, v) -> mChecked[index] = v);
        row.addView(box);

        TextView label = new TextView(mActivity);
        label.setTextColor(color(selectable ? R.color.terminow_text : R.color.terminow_text_secondary));
        String line1 = c.name.isEmpty() ? c.host : c.name;
        String where = (c.username.isEmpty() ? "" : c.username + "@") + c.host + ":" + c.effectivePort();
        String tag = categoryLabel(category);
        StringBuilder sb = new StringBuilder(line1).append("\n").append(where)
            .append("  ·  ").append(c.protocol.label()).append("  ·  ").append(tag);
        if (!c.note.isEmpty()) sb.append("\n").append(c.note);
        label.setText(sb.toString());
        label.setTextSize(13);
        label.setPadding(dp(8), 0, 0, 0);
        row.addView(label);
        return row;
    }

    private void doImport(@NonNull String destGroup) {
        String group = destGroup.isEmpty() ? mDelegate.defaultGroup() : destGroup;
        List<TerminowStore.Host> created = new ArrayList<>();
        List<TerminowStore.Host> updated = new ArrayList<>();
        Set<String> newGroups = new LinkedHashSet<>();
        Set<String> existingGroups = new LinkedHashSet<>(mDelegate.existingGroups());
        int skipped = 0;

        for (int i = 0; i < mEntries.size(); i++) {
            TerminowImportConnection c = mEntries.get(i);
            TerminowImportPlan.Category category = categoryOf(c);
            if (!mChecked[i] || !TerminowImportPlan.importable(category, mPolicy)) {
                if (mChecked[i]) skipped++;
                continue;
            }
            if (!mPreserveFolders) c.folders.clear();

            if (category == TerminowImportPlan.Category.DUPLICATE && mPolicy == TerminowImportPlan.DuplicatePolicy.UPDATE) {
                TerminowStore.Host match = matchExisting(c);
                if (match != null) {
                    TerminowImportPersist.updateExisting(match, c, mImportPasswords, mDelegate::resolveKey);
                    updated.add(match);
                    continue;
                }
            }
            TerminowStore.Host host = TerminowImportPersist.toHost(c, mImportPasswords, mDelegate::resolveKey, group);
            if (!host.group.isEmpty() && !existingGroups.contains(host.group)) newGroups.add(host.group);
            created.add(host);
        }

        if (created.isEmpty() && updated.isEmpty()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_qimport_nothing_selected), true);
            return;
        }

        Runnable undo = mDelegate.apply(created, updated, newGroups);
        showResults(created, updated.size(), skipped, undo);
    }

    private void showResults(@NonNull List<TerminowStore.Host> created, int updated, int skipped, @NonNull Runnable undo) {
        String message = mActivity.getString(R.string.terminow_qimport_result, created.size(), updated, skipped);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_qimport_done_title)
            .setMessage(message)
            .setPositiveButton(R.string.terminow_qimport_view, (d, w) -> mDelegate.viewImported(created))
            .setNeutralButton(R.string.terminow_qimport_undo, (d, w) -> {
                undo.run();
                mActivity.showToast(mActivity.getString(R.string.terminow_qimport_undone), false);
            })
            .setNegativeButton(android.R.string.ok, null)
            .show();
    }

    // --- helpers ------------------------------------------------------------------------------

    @NonNull
    private TerminowImportPlan.Category categoryOf(@NonNull TerminowImportConnection c) {
        return TerminowImportPlan.categorize(c, isDuplicate(c));
    }

    private boolean matchesSearch(@NonNull TerminowImportConnection c) {
        if (mSearch.isEmpty()) return true;
        String q = mSearch.toLowerCase(Locale.ROOT);
        return c.name.toLowerCase(Locale.ROOT).contains(q) || c.host.toLowerCase(Locale.ROOT).contains(q)
            || c.username.toLowerCase(Locale.ROOT).contains(q) || c.groupPath().toLowerCase(Locale.ROOT).contains(q);
    }

    @Nullable
    private TerminowStore.Host matchExisting(@NonNull TerminowImportConnection c) {
        for (TerminowStore.Host h : mDelegate.existingHosts()) if (TerminowImportPlan.sameIdentity(c, h)) return h;
        return null;
    }

    @NonNull
    private String countsText() {
        int valid = 0, dup = 0, invalid = 0, unsupported = 0, creds = 0;
        for (TerminowImportConnection c : mEntries) {
            switch (categoryOf(c)) {
                case VALID: valid++; break;
                case DUPLICATE: dup++; break;
                case INVALID: invalid++; break;
                case UNSUPPORTED: unsupported++; break;
                case CREDENTIALS_REQUIRED: creds++; break;
            }
        }
        return mActivity.getString(R.string.terminow_qimport_counts, valid, dup, creds, unsupported, invalid);
    }

    @NonNull
    private String policyText() {
        int res;
        switch (mPolicy) {
            case KEEP_BOTH: res = R.string.terminow_qimport_dup_keep; break;
            case UPDATE: res = R.string.terminow_qimport_dup_update; break;
            default: res = R.string.terminow_qimport_dup_skip;
        }
        return mActivity.getString(res);
    }

    @NonNull
    private String categoryLabel(@NonNull TerminowImportPlan.Category category) {
        int res;
        switch (category) {
            case DUPLICATE: res = R.string.terminow_qimport_cat_duplicate; break;
            case INVALID: res = R.string.terminow_qimport_cat_invalid; break;
            case UNSUPPORTED: res = R.string.terminow_qimport_cat_unsupported; break;
            case CREDENTIALS_REQUIRED: res = R.string.terminow_qimport_cat_creds; break;
            default: res = R.string.terminow_qimport_cat_valid;
        }
        return mActivity.getString(res);
    }

    @NonNull
    private CheckBox checkbox(@NonNull LinearLayout parent, int labelRes, boolean checked) {
        CheckBox box = new CheckBox(mActivity);
        box.setText(labelRes);
        box.setChecked(checked);
        box.setTextColor(color(R.color.terminow_text));
        parent.addView(box);
        return box;
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    /** A tiny TextWatcher that reports the current text. */
    private static final class SimpleWatcher implements TextWatcher {
        private final java.util.function.Consumer<String> onChanged;

        SimpleWatcher(java.util.function.Consumer<String> onChanged) {
            this.onChanged = onChanged;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        @Override public void afterTextChanged(Editable s) { onChanged.accept(s.toString()); }
    }
}
