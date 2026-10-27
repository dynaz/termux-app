package com.termux.app.terminow;

import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Local AI Bridge entry point, shared by the Terminal toolbar (More sheet) and the Tools Library
 * so both open the same bottom sheet over the same {@link TerminowAiBridgeManager} — no second helper
 * per tab, no duplicated logic. It captures the source environment and session it was opened for,
 * connects and verifies end to end (Connect &amp; Test), diagnoses layer by layer, and tests each
 * authorized host from Claude's view. It never changes what a host allows — authorization lives in AI
 * settings — and never shows a token or credential.
 */
final class TerminowAiBridgeSheet {

    private final TermuxActivity mActivity;
    private final TerminowAiBridgeManager mManager;
    private final TerminowStore mStore;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @Nullable private Dialog mDialog;

    TerminowAiBridgeSheet(@NonNull TermuxActivity activity, @NonNull TerminowAiBridgeManager manager, @NonNull TerminowStore store) {
        mActivity = activity;
        mManager = manager;
        mStore = store;
    }

    /**
     * Open the sheet for a captured source. {@code environmentKey} null means the session is not a
     * local environment (e.g. a remote SSH tab), so only the source is shown, nothing to connect.
     */
    void open(@Nullable String environmentKey, @NonNull String user, @NonNull String environmentLabel, @NonNull String sessionLabel) {
        LinearLayout content = openSheet(mActivity.getString(R.string.terminow_bridge_title));

        section(content, R.string.terminow_bridge_source);
        row(content, mActivity.getString(R.string.terminow_bridge_session, sessionLabel),
            environmentKey != null ? mActivity.getString(R.string.terminow_bridge_env, environmentLabel)
                                   : mActivity.getString(R.string.terminow_bridge_no_env),
            () -> { });

        section(content, R.string.terminow_bridge_client);
        row(content, mActivity.getString(R.string.terminow_bridge_claude), "", () -> { });

        if (environmentKey == null) return;
        final String envKey = environmentKey;
        final String who = user.isEmpty() ? "root" : user;

        section(content, R.string.terminow_bridge_actions);
        row(content, mActivity.getString(R.string.terminow_bridge_connect_test),
            mActivity.getString(R.string.terminow_bridge_connect_test_desc), () -> {
                mActivity.showToast(mActivity.getString(R.string.terminow_bridge_working), false);
                mExecutor.execute(() -> {
                    boolean ok;
                    String message;
                    TerminowAiBridgeManager.SetupResult r = mManager.connectClaude(envKey, who);
                    if (!r.ok) {
                        ok = false;
                        message = r.message;
                    } else {
                        TerminowAiBridgeProbe.Result p = mManager.selfTest(envKey, null);
                        ok = p.ok();
                        message = ok ? mActivity.getString(R.string.terminow_ai_connect_tested, p.hostCount)
                                     : mActivity.getString(R.string.terminow_ai_connect_test_failed, p.error != null ? p.error : "failed");
                    }
                    final boolean fok = ok;
                    final String msg = message;
                    mHandler.post(() -> mActivity.showToast(msg, !fok));
                });
            });
        row(content, mActivity.getString(R.string.terminow_ai_diagnose),
            mActivity.getString(R.string.terminow_bridge_diagnose_desc), () -> {
                mActivity.showToast(mActivity.getString(R.string.terminow_bridge_working), false);
                mExecutor.execute(() -> {
                    TerminowAiBridgeManager.Diagnostics d = mManager.diagnose(envKey, who);
                    final String report = d.toText();
                    mHandler.post(() -> showReport(report));
                });
            });

        section(content, R.string.terminow_ai_hosts_section);
        boolean any = false;
        for (TerminowStore.Host host : mStore.getHosts()) {
            if (host.telnet || !host.isTerminal() || !host.aiAccess.enabled) continue;
            any = true;
            final String hostId = host.uuid;
            final String hostTitle = host.getTitle();
            row(content, hostTitle, mActivity.getString(R.string.terminow_bridge_tap_test), () -> {
                mActivity.showToast(mActivity.getString(R.string.terminow_bridge_working), false);
                mExecutor.execute(() -> {
                    TerminowAiBridgeProbe.Result p = mManager.selfTest(envKey, hostId);
                    final boolean ok = "ok".equals(p.sshTest);
                    final String msg = mActivity.getString(R.string.terminow_bridge_host_result, hostTitle,
                        p.sshTest != null ? p.sshTest : (p.error != null ? p.error : "no result"));
                    mHandler.post(() -> mActivity.showToast(msg, !ok));
                });
            });
        }
        if (!any) row(content, mActivity.getString(R.string.terminow_bridge_no_hosts),
            mActivity.getString(R.string.terminow_bridge_no_hosts_desc), () -> { });
    }

    private void showReport(@NonNull String report) {
        LinearLayout content = openSheet(mActivity.getString(R.string.terminow_ai_diagnose));
        TextView body = new TextView(mActivity);
        body.setText(report);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        body.setTextColor(color(R.color.terminow_text));
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        int pad = dp(4);
        body.setPadding(pad, pad, pad, pad);
        content.addView(body);
        row(content, mActivity.getString(R.string.terminow_ai_copy_diag), "", () -> {
            android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) mActivity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (clipboard != null)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("TermiNow diagnostics", report));
            mActivity.showToast(mActivity.getString(R.string.terminow_ai_diag_copied), false);
        });
    }

    // ---- bottom-sheet scaffolding (mirrors the toolbar sheet so the two look the same) ----

    @NonNull
    private LinearLayout openSheet(@NonNull String titleText) {
        if (mDialog != null && mDialog.isShowing()) mDialog.dismiss();
        Dialog dialog = new Dialog(mActivity, R.style.TerminowPanelDialog);
        mDialog = dialog;
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(color(R.color.terminow_panel_scrim));
        root.setOnClickListener(v -> dialog.dismiss());

        LinearLayout sheet = new LinearLayout(mActivity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_panel));
        float radius = dp(20);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);
        sheet.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView title = new TextView(mActivity);
        title.setText(titleText);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTextColor(color(R.color.terminow_text));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        ViewCompat.setAccessibilityHeading(title, true);
        sheet.addView(title);

        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sheet, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(560));
            int scrollMax = Math.round((bottom - top) * 0.8f) - dp(60);
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            ViewGroup.LayoutParams scrollParams = scroll.getLayoutParams();
            int scrollHeight = content.getHeight() > scrollMax ? scrollMax : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params.width != width || scrollParams.height != scrollHeight) {
                params.width = width;
                scrollParams.height = scrollHeight;
                sheet.post(() -> {
                    sheet.setLayoutParams(params);
                    scroll.setLayoutParams(scrollParams);
                });
            }
        });
        dialog.setContentView(root);
        if (dialog.getWindow() != null)
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.setOnDismissListener(d -> {
            if (mDialog == dialog) mDialog = null;
        });
        dialog.show();
        return content;
    }

    private void section(@NonNull LinearLayout content, @StringRes int label) {
        TextView view = new TextView(mActivity);
        view.setText(mActivity.getString(label));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setTextColor(color(R.color.terminow_panel_accent));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setAllCaps(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        params.bottomMargin = dp(4);
        view.setLayoutParams(params);
        content.addView(view);
    }

    private void row(@NonNull LinearLayout content, @NonNull String name, @NonNull String description, @NonNull Runnable action) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(4), dp(10), dp(4), dp(10));
        row.setClickable(true);
        row.setFocusable(true);
        TypedValue ripple = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> action.run());
        TextView title = new TextView(mActivity);
        title.setText(name);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTextColor(color(R.color.terminow_text));
        row.addView(title);
        if (!description.isEmpty()) {
            TextView desc = new TextView(mActivity);
            desc.setText(description);
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            desc.setTextColor(color(R.color.terminow_text_secondary));
            row.addView(desc);
        }
        content.addView(row);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }
}
