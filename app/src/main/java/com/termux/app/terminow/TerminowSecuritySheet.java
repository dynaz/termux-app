package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security tools screen: a catalogue of individual Kali/Debian security tools the user can
 * install into a Linux environment, one at a time, through the shared background installer
 * ({@link TerminowToolInstaller#installPackage}). It shows what is already present (detected from
 * the environment, not saved UI state), installs only the chosen tool's apt package (never a
 * metapackage), surfaces each install's log and a Cancel, and gates installs behind Premium.
 *
 * <p>Leaving this sheet never stops an install — the shared floating progress card keeps showing
 * running jobs. An information button explains that these tools are for authorised testing only.</p>
 */
public final class TerminowSecuritySheet {

    private static final TerminowSecurityTools.Category[] CATEGORIES = TerminowSecurityTools.Category.values();

    private final TermuxActivity mActivity;
    private final File mPrefixDir;
    private final TerminowToolInstaller.Runner mRunner;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Runnable mOnJobs = () -> mHandler.post(this::render);

    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    private String mAlias = "";
    private String mUser = "root";
    /** Binary name → version, from the last detection; null while it is being checked. */
    @Nullable private Map<String, String> mInstalled;
    private boolean mDetecting;
    /** -1 = All; otherwise an ordinal into {@link #CATEGORIES}. */
    private int mCategory = -1;

    public TerminowSecuritySheet(@NonNull TermuxActivity activity, @NonNull File prefixDir) {
        mActivity = activity;
        mPrefixDir = prefixDir;
        TerminowClaudeSetup setup = new TerminowClaudeSetup(activity, new TerminowMcpTargets(prefixDir,
            new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH)));
        mRunner = (alias, user, script, args) -> setup.start(TerminowToolInstaller.command(prefixDir, alias, user, script, args));
    }

    /** Open for an environment as a user (root unless the environment names another). */
    public void show(@NonNull String alias, @NonNull String user) {
        mAlias = alias;
        mUser = user.isEmpty() ? "root" : user;
        open();
    }

    private void open() {
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
        float radius = dp(24);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(background);
        sheet.setPadding(dp(16), dp(8), dp(16), dp(16));

        View handle = new View(mActivity);
        GradientDrawable handleShape = new GradientDrawable();
        handleShape.setColor(color(R.color.terminow_text_secondary));
        handleShape.setCornerRadius(dp(2));
        handle.setBackground(handleShape);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
        handleParams.gravity = Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp(10);
        sheet.addView(handle, handleParams);

        LinearLayout titleRow = new LinearLayout(mActivity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_sec_title), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView info = text("ⓘ", 20, R.color.terminow_accent);
        info.setPadding(dp(10), dp(4), dp(10), dp(4));
        info.setContentDescription(mActivity.getString(R.string.terminow_sec_info_title));
        info.setOnClickListener(v -> showInfo());
        titleRow.addView(info);
        sheet.addView(titleRow, matchWrap());

        TextView subtitle = text(mActivity.getString(R.string.terminow_sec_subtitle), 13, R.color.terminow_text_secondary);
        subtitle.setPadding(0, dp(2), 0, dp(8));
        sheet.addView(subtitle, matchWrap());

        sheet.addView(categoryChips(), matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(8), 0, 0);
        scroll.addView(content);
        mContent = content;
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sheet, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int cap = mActivity.getResources().getConfiguration().screenWidthDp >= 720 ? dp(900) : dp(640);
            int width = Math.min(r - l, cap);
            int maxHeight = Math.round((b - t) * 0.90f);
            ViewGroup.LayoutParams params = sheet.getLayoutParams();
            ViewGroup.LayoutParams scrollParams = scroll.getLayoutParams();
            int scrollMax = maxHeight - dp(120);
            boolean tooTall = content.getHeight() > scrollMax;
            int scrollHeight = tooTall ? scrollMax : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (params.width != width || scrollParams.height != scrollHeight) {
                params.width = width;
                scrollParams.height = scrollHeight;
                sheet.post(() -> { sheet.setLayoutParams(params); scroll.setLayoutParams(scrollParams); });
            }
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(d -> {
            TerminowToolInstaller.removeListener(mOnJobs);
            if (mDialog == dialog) { mDialog = null; mContent = null; }
        });
        TerminowToolInstaller.addListener(mOnJobs);
        render();
        dialog.show();
        detect();
    }

    private HorizontalScrollView categoryChips() {
        HorizontalScrollView scroll = new HorizontalScrollView(mActivity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(chip(mActivity.getString(R.string.terminow_sec_cat_all), -1));
        for (TerminowSecurityTools.Category c : CATEGORIES) row.addView(chip(categoryLabel(c), c.ordinal()));
        scroll.addView(row);
        return scroll;
    }

    private TextView chip(@NonNull String label, int category) {
        boolean selected = mCategory == category;
        TextView chip = text(label, 13, selected ? R.color.terminow_bar_home : R.color.terminow_text);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(color(selected ? R.color.terminow_panel_accent : R.color.terminow_panel_button));
        chip.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> { mCategory = category; if (mDialog != null) open(); });
        return chip;
    }

    // ---------------------------------------------------------------------------------------

    private void detect() {
        if (mDetecting) return;
        mDetecting = true;
        render();
        String alias = mAlias, user = mUser;
        mExecutor.execute(() -> {
            Map<String, String> found = TerminowSecurityTools.detect(mRunner, alias, user);
            mHandler.post(() -> {
                mDetecting = false;
                if (!alias.equals(mAlias)) return;
                mInstalled = found;
                render();
            });
        });
    }

    private void render() {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();
        if (mDetecting && mInstalled == null) {
            content.addView(text(mActivity.getString(R.string.terminow_sec_checking), 14, R.color.terminow_text_secondary), matchWrap());
        }
        int shown = 0;
        for (TerminowSecurityTools.Tool tool : TerminowSecurityTools.catalog()) {
            if (mCategory >= 0 && tool.category.ordinal() != mCategory) continue;
            content.addView(toolCard(tool), matchWrap());
            shown++;
        }
        if (shown == 0) content.addView(text(mActivity.getString(R.string.terminow_sec_none), 14, R.color.terminow_text_secondary), matchWrap());
    }

    private View toolCard(@NonNull TerminowSecurityTools.Tool tool) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color(R.color.terminow_panel_card));
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(10);
        card.setLayoutParams(cardLp);

        LinearLayout top = new LinearLayout(mActivity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(tool.label, 16, R.color.terminow_text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (tool.gui) {
            TextView gui = text(mActivity.getString(R.string.terminow_sec_gui_badge), 11, R.color.terminow_mcp_warn);
            top.addView(gui);
        }
        card.addView(top, matchWrap());

        TextView desc = text(tool.description, 13, R.color.terminow_text_secondary);
        desc.setPadding(0, dp(2), 0, dp(8));
        card.addView(desc, matchWrap());

        card.addView(actionRow(tool), matchWrap());
        return card;
    }

    private View actionRow(@NonNull TerminowSecurityTools.Tool tool) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String version = mInstalled == null ? null : mInstalled.get(tool.binName);
        TerminowToolInstaller.Job job = TerminowToolInstaller.packageJobOf(mAlias, mUser, tool.id);
        boolean running = job != null && (job.getState() == TerminowToolInstaller.State.RUNNING || job.getState() == TerminowToolInstaller.State.QUEUED);

        TextView status = text("", 13, R.color.terminow_text_secondary);
        status.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(status);

        if (running) {
            status.setText(mActivity.getString(job.getState() == TerminowToolInstaller.State.QUEUED
                ? R.string.terminow_sec_queued : R.string.terminow_sec_installing, stageLabel(job)));
            row.addView(textButton(mActivity.getString(R.string.terminow_sec_logs), false, () -> showLog(tool)));
            row.addView(textButton(mActivity.getString(android.R.string.cancel), false, () -> { job.cancel(); render(); }));
            return row;
        }
        if (version != null) {
            status.setTextColor(color(R.color.terminow_quick_ready));
            status.setText(mActivity.getString(R.string.terminow_sec_installed, version));
            if (job != null && !job.getLog().trim().isEmpty())
                row.addView(textButton(mActivity.getString(R.string.terminow_sec_logs), false, () -> showLog(tool)));
            return row;
        }
        if (job != null && job.getState() == TerminowToolInstaller.State.FAILED) {
            status.setTextColor(color(R.color.terminow_host_ssh));
            status.setText(mActivity.getString(R.string.terminow_sec_failed));
            row.addView(textButton(mActivity.getString(R.string.terminow_sec_logs), false, () -> showLog(tool)));
            row.addView(textButton(mActivity.getString(R.string.terminow_sec_retry), true, () -> install(tool)));
            return row;
        }
        boolean locked = !TerminowAccountUi.isPremium(new TerminowStore(mActivity));
        row.addView(textButton(mActivity.getString(locked ? R.string.terminow_sec_install_premium : R.string.terminow_sec_install), true, () -> install(tool)));
        return row;
    }

    private void install(@NonNull TerminowSecurityTools.Tool tool) {
        if (!TerminowSecurityTools.isValid(tool)) {
            mActivity.showToast(mActivity.getString(R.string.terminow_sec_bad_entry), true);
            return;
        }
        TerminowPremiumGate.require(mActivity, new TerminowStore(mActivity), TerminowPremiumGate.Feature.SECURITY_TOOLS, 0, () -> {
            TerminowToolInstaller.Job job = TerminowToolInstaller.installPackage(
                mRunner, mAlias, mUser, tool.id, tool.label, tool.binName, tool.aptPackages);
            if (job == null) {
                TerminowToolInstaller.Job busy = TerminowToolInstaller.runningIn(mAlias);
                mActivity.showToast(mActivity.getString(R.string.terminow_sec_waiting,
                    busy != null ? TerminowToolInstaller.jobLabel(busy) : ""), false);
            }
            render();
        });
    }

    private void showLog(@NonNull TerminowSecurityTools.Tool tool) {
        TerminowToolInstaller.Job job = TerminowToolInstaller.packageJobOf(mAlias, mUser, tool.id);
        String log = job == null ? "" : job.getLog().trim();
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(tool.label)
            .setMessage(log.isEmpty() ? mActivity.getString(R.string.terminow_sec_no_log) : log)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void showInfo() {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_sec_info_title)
            .setMessage(R.string.terminow_sec_info_message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @NonNull
    private String stageLabel(@NonNull TerminowToolInstaller.Job job) {
        switch (job.getStage()) {
            case DEPENDENCIES: return mActivity.getString(R.string.terminow_sec_stage_deps);
            case INSTALLING: return mActivity.getString(R.string.terminow_sec_stage_installing);
            case VERIFYING: return mActivity.getString(R.string.terminow_sec_stage_verifying);
            default: return mActivity.getString(R.string.terminow_sec_stage_checking);
        }
    }

    @NonNull
    private String categoryLabel(@NonNull TerminowSecurityTools.Category c) {
        switch (c) {
            case RECON: return mActivity.getString(R.string.terminow_sec_cat_recon);
            case WEB: return mActivity.getString(R.string.terminow_sec_cat_web);
            case PASSWORD: return mActivity.getString(R.string.terminow_sec_cat_password);
            case WIRELESS: return mActivity.getString(R.string.terminow_sec_cat_wireless);
            case CAPTURE: return mActivity.getString(R.string.terminow_sec_cat_capture);
            default: return c.name();
        }
    }

    // ---------------------------------------------------------------------------------------
    // View helpers

    private TextView textButton(@NonNull String label, boolean primary, @NonNull Runnable onClick) {
        TextView button = text(label, 13, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(40));
        button.setMinWidth(dp(44));
        button.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(10));
        shape.setColor(color(primary ? R.color.terminow_panel_accent : R.color.terminow_panel_button));
        button.setBackground(shape);
        button.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(dp(8));
        button.setLayoutParams(lp);
        return button;
    }

    private TextView text(@NonNull String value, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }
}
