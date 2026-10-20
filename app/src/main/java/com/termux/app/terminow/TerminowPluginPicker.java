package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Choose plugins of the official Claude Code marketplace to install: the list comes from Claude
 * Code in the environment, as its user, with the ones installed already marked, and a search.
 * Nothing is chosen at first; the chosen plugins are given to {@link Listener#install}.
 */
final class TerminowPluginPicker {

    interface Listener {
        void install(@NonNull String[] names);
    }

    private final TermuxActivity mActivity;
    private final TerminowToolInstaller.Runner mRunner;
    private final String mAlias;
    private final String mUser;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final List<TerminowToolInstaller.Plugin> mShown = new ArrayList<>();
    private final Set<String> mChosen = new LinkedHashSet<>();
    private TerminowToolInstaller.PluginCatalog mCatalog;
    private String mQuery = "";

    private AlertDialog mDialog;
    private LinearLayout mBody;
    private TextView mCount;
    private BaseAdapter mAdapter;

    TerminowPluginPicker(@NonNull TermuxActivity activity, @NonNull TerminowToolInstaller.Runner runner,
                         @NonNull String alias, @NonNull String user, @NonNull Listener listener) {
        mActivity = activity;
        mRunner = runner;
        mAlias = alias;
        mUser = user;
        mListener = listener;
    }

    void show() {
        mBody = new LinearLayout(mActivity);
        mBody.setOrientation(LinearLayout.VERTICAL);
        mBody.setPadding(dp(20), dp(8), dp(20), 0);
        showLoading();
        mDialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_plugins_title)
            .setView(mBody)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(mActivity.getString(R.string.terminow_plugins_install, 0), (d, which) -> {
                if (!mChosen.isEmpty()) mListener.install(mChosen.toArray(new String[0]));
            })
            .create();
        mDialog.setOnShowListener(d -> updateCount());
        mDialog.show();

        new Thread(() -> {
            TerminowToolInstaller.PluginCatalog catalog = TerminowToolInstaller.listPlugins(mRunner, mAlias, mUser);
            mHandler.post(() -> {
                if (mDialog == null || !mDialog.isShowing()) return;
                mCatalog = catalog;
                showCatalog();
            });
        }, "TermiNow plugins").start();
    }

    private void showLoading() {
        mBody.removeAllViews();
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(16));
        row.addView(new ProgressBar(mActivity), new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView text = text(mActivity.getString(R.string.terminow_plugins_loading), 14, R.color.terminow_text);
        text.setPadding(dp(12), 0, 0, 0);
        row.addView(text);
        mBody.addView(row);
    }

    private void showCatalog() {
        mBody.removeAllViews();
        TerminowToolInstaller.PluginCatalog catalog = mCatalog;
        if (catalog.noClaude || catalog.error != null || catalog.plugins.isEmpty()) {
            String reason = catalog.noClaude ? mActivity.getString(R.string.terminow_install_plugins_needs_claude) :
                catalog.error != null ? catalog.error : "0";
            TextView error = text(mActivity.getString(R.string.terminow_plugins_error, reason), 14, R.color.terminow_mcp_warn);
            error.setPadding(0, dp(12), 0, dp(12));
            mBody.addView(error);
            return;
        }

        mBody.addView(text(mActivity.getString(R.string.terminow_plugins_note), 12, R.color.terminow_text_secondary));
        EditText search = new EditText(mActivity);
        search.setHint(R.string.terminow_plugins_search);
        search.setSingleLine(true);
        search.setTextColor(color(R.color.terminow_text));
        search.setHintTextColor(color(R.color.terminow_text_secondary));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }
        });
        mBody.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mCount = text("", 12, R.color.terminow_text_secondary);
        mCount.setPadding(0, dp(4), 0, dp(4));
        mBody.addView(mCount);

        ListView list = new ListView(mActivity);
        list.setDivider(null);
        mAdapter = new BaseAdapter() {
            @Override public int getCount() { return mShown.size(); }
            @Override public Object getItem(int position) { return mShown.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                return row(mShown.get(position), convertView);
            }
        };
        list.setAdapter(mAdapter);
        // Tall enough to choose from, while the buttons stay on the screen
        int height = Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.5f);
        mBody.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        filter();
    }

    private void filter() {
        mShown.clear();
        for (TerminowToolInstaller.Plugin plugin : mCatalog.plugins) {
            if (mQuery.isEmpty() || plugin.name.toLowerCase(Locale.ROOT).contains(mQuery) ||
                plugin.description.toLowerCase(Locale.ROOT).contains(mQuery)) mShown.add(plugin);
        }
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
        updateCount();
    }

    private void updateCount() {
        if (mDialog == null) return;
        Button install = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (install != null) {
            install.setText(mActivity.getString(R.string.terminow_plugins_install, mChosen.size()));
            install.setEnabled(!mChosen.isEmpty());
        }
        if (mCount != null && mCatalog != null)
            mCount.setText(mActivity.getString(R.string.terminow_plugins_count, mCatalog.plugins.size(), mCatalog.installed.size(), mChosen.size()));
    }

    @NonNull
    private View row(@NonNull TerminowToolInstaller.Plugin plugin, View convertView) {
        LinearLayout row;
        CheckBox check;
        TextView name;
        TextView description;
        if (convertView instanceof LinearLayout) {
            row = (LinearLayout) convertView;
            check = (CheckBox) row.getChildAt(0);
            LinearLayout texts = (LinearLayout) row.getChildAt(1);
            name = (TextView) texts.getChildAt(0);
            description = (TextView) texts.getChildAt(1);
        } else {
            row = new LinearLayout(mActivity);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            check = new CheckBox(mActivity);
            check.setClickable(false);
            check.setFocusable(false);
            row.addView(check);
            LinearLayout texts = new LinearLayout(mActivity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(4), 0, 0, 0);
            name = text("", 15, R.color.terminow_text);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            texts.addView(name);
            description = text("", 12, R.color.terminow_text_secondary);
            description.setMaxLines(2);
            description.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(description);
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        boolean installed = mCatalog.installed.contains(plugin.name);
        name.setText(installed ? plugin.name + " · " + mActivity.getString(R.string.terminow_plugins_installed) : plugin.name);
        description.setText(plugin.description);
        check.setChecked(installed || mChosen.contains(plugin.name));
        check.setEnabled(!installed);
        row.setAlpha(installed ? 0.6f : 1f);
        row.setOnClickListener(installed ? null : v -> {
            if (!mChosen.remove(plugin.name)) mChosen.add(plugin.name);
            check.setChecked(mChosen.contains(plugin.name));
            updateCount();
        });
        return row;
    }

    @NonNull
    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }
}
