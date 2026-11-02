package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security category of the Tools Library: a catalogue of individual Kali/Debian security tools
 * the user can install into a Linux environment, one at a time, through the shared background
 * installer ({@link TerminowToolInstaller#installPackage}). It shows what is already present
 * (detected from the environment, never from saved UI state), the environment's real capabilities
 * (distro, CPU, apt, PRoot root), installs only the chosen tool's apt package (never a metapackage),
 * surfaces each install's log, Update/Uninstall for tools TermiNow installed, an "Open" that launches
 * the tool in the environment's terminal (with a command composer for Nmap and TShark, never an
 * automatic scan), a details sheet with the honest capability limitations and the official docs link,
 * and gates installs behind Premium.
 *
 * <p>Leaving this sheet never stops an install — the shared floating progress card keeps showing
 * running jobs. An information button explains that these tools are for authorised testing only.</p>
 */
public final class TerminowSecuritySheet {

    private static final TerminowSecurityTools.Category[] CATEGORIES = TerminowSecurityTools.Category.values();

    private final TermuxActivity mActivity;
    @Nullable private final TerminowLauncher mLauncher;
    private final TerminowToolInstaller.Runner mRunner;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Runnable mOnJobs = () -> mHandler.post(this::render);

    @Nullable private Dialog mDialog;
    @Nullable private LinearLayout mContent;
    @Nullable private TerminowLauncher.Environment mEnv;
    private String mAlias = "";
    private String mUser = "root";
    /** Binary name → version, from the last detection; null while it is being checked. */
    @Nullable private Map<String, String> mInstalled;
    /** The environment's real capabilities (distro/CPU/apt/root); null while being checked. */
    @Nullable private TerminowSecurityTools.Capabilities mCaps;
    private boolean mDetecting;
    /** -1 = All; otherwise an ordinal into {@link #CATEGORIES}. */
    private int mCategory = -1;

    public TerminowSecuritySheet(@NonNull TermuxActivity activity, @NonNull File prefixDir) {
        this(activity, prefixDir, null);
    }

    public TerminowSecuritySheet(@NonNull TermuxActivity activity, @NonNull File prefixDir,
                                 @Nullable TerminowLauncher launcher) {
        mActivity = activity;
        mLauncher = launcher;
        TerminowClaudeSetup setup = new TerminowClaudeSetup(activity, new TerminowMcpTargets(prefixDir,
            new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH)));
        mRunner = (alias, user, script, args) -> setup.start(TerminowToolInstaller.command(prefixDir, alias, user, script, args));
    }

    /** Open for an environment as a user (root unless the environment names another). */
    public void show(@NonNull String alias, @NonNull String user) {
        mEnv = null;
        mAlias = alias;
        mUser = user.isEmpty() ? "root" : user;
        open();
    }

    /** Open for a local Linux environment; keeping the environment lets "Open" launch a terminal in it. */
    public void show(@NonNull TerminowLauncher.Environment environment, @NonNull String user) {
        mEnv = environment;
        mAlias = environment.key;
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
            // Real capabilities (distro, CPU, apt, uid) from the shared detect script, then the
            // per-tool version check. A local Linux environment always runs under PRoot.
            TerminowToolInstaller.Detection detection = TerminowToolInstaller.detect(mRunner, alias, user);
            TerminowSecurityTools.Capabilities caps = detection != null && detection.ran
                ? TerminowSecurityTools.capabilitiesOf(detection, true) : null;
            Map<String, String> found = TerminowSecurityTools.detect(mRunner, alias, user);
            mHandler.post(() -> {
                mDetecting = false;
                if (!alias.equals(mAlias)) return;
                mCaps = caps;
                mInstalled = found;
                render();
            });
        });
    }

    private void render() {
        LinearLayout content = mContent;
        if (content == null) return;
        content.removeAllViews();
        content.addView(capabilityBanner(), matchWrap());
        if (mDetecting && mInstalled == null) {
            content.addView(text(mActivity.getString(R.string.terminow_sec_checking), 14, R.color.terminow_text_secondary), matchWrap());
        }
        int shown = 0;
        for (TerminowSecurityTools.Tool tool : TerminowSecurityTools.catalog()) {
            if (mCategory >= 0 && tool.category.ordinal() != mCategory) continue;
            recordIfTermiNowInstalled(tool);
            content.addView(toolCard(tool), matchWrap());
            shown++;
        }
        if (shown == 0) content.addView(text(mActivity.getString(R.string.terminow_sec_none), 14, R.color.terminow_text_secondary), matchWrap());
    }

    /** A one-line banner with the detected environment: distro, CPU and whether apt is present. */
    private View capabilityBanner() {
        String value;
        if (mCaps == null) {
            value = mActivity.getString(R.string.terminow_sec_checking);
        } else {
            String os = mCaps.os.isEmpty() ? mActivity.getString(R.string.terminow_sec_unknown) : mCaps.os;
            String arch = mCaps.arch.isEmpty() ? mActivity.getString(R.string.terminow_sec_unknown) : mCaps.arch;
            String apt = mActivity.getString(mCaps.hasApt ? R.string.terminow_sec_apt_yes : R.string.terminow_sec_apt_no);
            value = mActivity.getString(R.string.terminow_sec_caps, os, arch, apt);
        }
        TextView banner = text(value, 12, R.color.terminow_text_secondary);
        banner.setPadding(dp(2), 0, dp(2), dp(8));
        return banner;
    }

    private View toolCard(@NonNull TerminowSecurityTools.Tool tool) {
        TerminowSecurityTools.Status status = mCaps == null ? null : TerminowSecurityTools.support(tool, mCaps);

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
        TextView details = text("ⓘ", 18, R.color.terminow_accent);
        details.setPadding(dp(10), dp(2), dp(4), dp(2));
        details.setContentDescription(mActivity.getString(R.string.terminow_sec_details));
        details.setOnClickListener(v -> showDetails(tool, status));
        top.addView(details);
        card.addView(top, matchWrap());

        card.addView(badgeRow(tool, status), matchWrap());

        TextView desc = text(tool.description, 13, R.color.terminow_text_secondary);
        desc.setPadding(0, dp(4), 0, dp(8));
        card.addView(desc, matchWrap());

        card.addView(actionRow(tool, status), matchWrap());
        return card;
    }

    /** CLI/GUI, ARM64, Requires-privileges and Limited-support badges, as relevant. */
    private View badgeRow(@NonNull TerminowSecurityTools.Tool tool, @Nullable TerminowSecurityTools.Status status) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(2), 0, 0);
        row.addView(badge(mActivity.getString(tool.gui ? R.string.terminow_sec_badge_gui : R.string.terminow_sec_badge_cli),
            R.color.terminow_panel_button, R.color.terminow_text_secondary));
        if (mCaps != null && mCaps.arm64())
            row.addView(badge(mActivity.getString(R.string.terminow_sec_badge_arm64), R.color.terminow_panel_button, R.color.terminow_text_secondary));
        if (tool.privileged)
            row.addView(badge(mActivity.getString(R.string.terminow_sec_badge_priv), R.color.terminow_panel_button, R.color.terminow_mcp_warn));
        if (status != null && status.support == TerminowSecurityTools.Support.LIMITED)
            row.addView(badge(mActivity.getString(R.string.terminow_sec_badge_limited), R.color.terminow_panel_button, R.color.terminow_mcp_warn));
        return row;
    }

    private TextView badge(@NonNull String label, int bgColor, int textColor) {
        TextView badge = text(label, 11, textColor);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setPadding(dp(8), dp(3), dp(8), dp(3));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(color(bgColor));
        badge.setBackground(shape);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        badge.setLayoutParams(lp);
        return badge;
    }

    private View actionRow(@NonNull TerminowSecurityTools.Tool tool, @Nullable TerminowSecurityTools.Status status) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String version = mInstalled == null ? null : mInstalled.get(tool.binName);
        TerminowToolInstaller.Job install = TerminowToolInstaller.packageJobOf(mAlias, mUser, tool.id);
        TerminowToolInstaller.Job update = TerminowToolInstaller.packageActionJobOf(mAlias, mUser, tool.id, TerminowToolInstaller.PkgAction.UPDATE);
        TerminowToolInstaller.Job remove = TerminowToolInstaller.packageActionJobOf(mAlias, mUser, tool.id, TerminowToolInstaller.PkgAction.REMOVE);
        TerminowToolInstaller.Job active = running(install) ? install : running(update) ? update : running(remove) ? remove : null;

        TextView status0 = text("", 13, R.color.terminow_text_secondary);
        status0.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(status0);

        if (active != null) {
            status0.setText(mActivity.getString(active.getState() == TerminowToolInstaller.State.QUEUED
                ? R.string.terminow_sec_queued : R.string.terminow_sec_installing, stageLabel(active)));
            row.addView(textButton(mActivity.getString(R.string.terminow_sec_logs), false, () -> showLog(tool)));
            row.addView(textButton(mActivity.getString(android.R.string.cancel), false, () -> { active.cancel(); render(); }));
            return row;
        }
        // Unsupported for installation: no Install, an actionable reason, details still reachable.
        if (status != null && status.support == TerminowSecurityTools.Support.UNSUPPORTED) {
            status0.setTextColor(color(R.color.terminow_host_ssh));
            status0.setText(mActivity.getString(R.string.terminow_sec_unsupported) + " · " + reasonText(status.reason));
            return row;
        }
        if (version != null) {
            status0.setTextColor(color(R.color.terminow_quick_ready));
            status0.setText(mActivity.getString(R.string.terminow_sec_installed, version));
            if (canOpen(tool))
                row.addView(textButton(mActivity.getString(R.string.terminow_sec_open), true, () -> openTool(tool)));
            row.addView(textButton(mActivity.getString(R.string.terminow_sec_menu), false, () -> showActionMenu(tool)));
            return row;
        }
        if ((install != null && install.getState() == TerminowToolInstaller.State.FAILED)
            || (update != null && update.getState() == TerminowToolInstaller.State.FAILED)
            || (remove != null && remove.getState() == TerminowToolInstaller.State.FAILED)) {
            status0.setTextColor(color(R.color.terminow_host_ssh));
            status0.setText(mActivity.getString(R.string.terminow_sec_failed));
            row.addView(textButton(mActivity.getString(R.string.terminow_sec_logs), false, () -> showLog(tool)));
            row.addView(textButton(mActivity.getString(R.string.terminow_sec_retry), true, () -> install(tool)));
            return row;
        }
        if (status != null && status.support == TerminowSecurityTools.Support.LIMITED) {
            status0.setText(reasonText(status.reason));
        }
        boolean locked = !TerminowAccountUi.isPremium(new TerminowStore(mActivity));
        row.addView(textButton(mActivity.getString(locked ? R.string.terminow_sec_install_premium : R.string.terminow_sec_install), true, () -> install(tool)));
        return row;
    }

    private static boolean running(@Nullable TerminowToolInstaller.Job job) {
        return job != null && (job.getState() == TerminowToolInstaller.State.RUNNING || job.getState() == TerminowToolInstaller.State.QUEUED);
    }

    /** "Open" is offered for installed CLI tools in a local environment (not the GUI-only Wireshark). */
    private boolean canOpen(@NonNull TerminowSecurityTools.Tool tool) {
        return mEnv != null && mLauncher != null && !tool.gui && tool.composer != TerminowSecurityTools.Composer.NONE;
    }

    // ---------------------------------------------------------------------------------------
    // Install / update / uninstall

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

    /** The overflow menu on an installed tool: Update (always), Uninstall (only if TermiNow installed it). */
    private void showActionMenu(@NonNull TerminowSecurityTools.Tool tool) {
        List<String> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        items.add(mActivity.getString(R.string.terminow_sec_update));
        actions.add(() -> updateTool(tool));
        items.add(mActivity.getString(R.string.terminow_sec_logs));
        actions.add(() -> showLog(tool));
        if (isTracked(tool.id)) {
            items.add(mActivity.getString(R.string.terminow_sec_uninstall));
            actions.add(() -> confirmUninstall(tool));
        }
        final Runnable[] arr = actions.toArray(new Runnable[0]);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(tool.label)
            .setItems(items.toArray(new CharSequence[0]), (d, which) -> arr[which].run())
            .show();
    }

    private void updateTool(@NonNull TerminowSecurityTools.Tool tool) {
        TerminowPremiumGate.require(mActivity, new TerminowStore(mActivity), TerminowPremiumGate.Feature.SECURITY_TOOLS, 0, () -> {
            TerminowToolInstaller.Job job = TerminowToolInstaller.packageAction(
                mRunner, mAlias, mUser, tool.id, tool.label, tool.binName, tool.aptPackages, TerminowToolInstaller.PkgAction.UPDATE);
            if (job == null) {
                TerminowToolInstaller.Job busy = TerminowToolInstaller.runningIn(mAlias);
                mActivity.showToast(mActivity.getString(R.string.terminow_sec_waiting,
                    busy != null ? TerminowToolInstaller.jobLabel(busy) : ""), false);
            }
            render();
        });
    }

    private void confirmUninstall(@NonNull TerminowSecurityTools.Tool tool) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(tool.label)
            .setMessage(mActivity.getString(R.string.terminow_sec_confirm_uninstall, tool.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_sec_uninstall, (d, w) -> {
                TerminowToolInstaller.Job job = TerminowToolInstaller.packageAction(
                    mRunner, mAlias, mUser, tool.id, tool.label, tool.binName, tool.aptPackages, TerminowToolInstaller.PkgAction.REMOVE);
                if (job == null) {
                    TerminowToolInstaller.Job busy = TerminowToolInstaller.runningIn(mAlias);
                    mActivity.showToast(mActivity.getString(R.string.terminow_sec_waiting,
                        busy != null ? TerminowToolInstaller.jobLabel(busy) : ""), false);
                } else {
                    untrack(tool.id);
                }
                render();
            })
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Open — launch the tool in the environment's terminal, never an automatic scan

    private void openTool(@NonNull TerminowSecurityTools.Tool tool) {
        switch (tool.composer) {
            case NMAP_TCP: nmapComposer(tool); break;
            case TSHARK_PCAP: tsharkComposer(tool); break;
            case HELP: launch(tool, java.util.Collections.singletonList("--help")); break;
            default: break;
        }
    }

    private void nmapComposer(@NonNull TerminowSecurityTools.Tool tool) {
        LinearLayout box = composerBox();
        EditText target = field(mActivity.getString(R.string.terminow_sec_composer_target), InputType.TYPE_TEXT_VARIATION_URI);
        EditText ports = field(mActivity.getString(R.string.terminow_sec_composer_ports), InputType.TYPE_CLASS_TEXT);
        box.addView(target, matchWrap());
        box.addView(ports, matchWrap());
        box.addView(authorizedNote(), matchWrap());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(tool.label + " — " + mActivity.getString(R.string.terminow_sec_nmap_tcp))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_sec_composer_run, (d, w) -> {
                List<String> args = TerminowSecurityTools.nmapTcpArgs(
                    target.getText().toString().trim(), ports.getText().toString().trim());
                if (args == null) { mActivity.showToast(mActivity.getString(R.string.terminow_sec_composer_bad), true); return; }
                launch(tool, args);
            })
            .show();
    }

    private void tsharkComposer(@NonNull TerminowSecurityTools.Tool tool) {
        LinearLayout box = composerBox();
        EditText file = field(mActivity.getString(R.string.terminow_sec_composer_pcap), InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(file, matchWrap());
        box.addView(note(mActivity.getString(R.string.terminow_sec_pcap_note)), matchWrap());
        box.addView(authorizedNote(), matchWrap());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(tool.label + " — " + mActivity.getString(R.string.terminow_sec_tshark_read))
            .setView(box)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.terminow_sec_composer_run, (d, w) -> {
                List<String> args = TerminowSecurityTools.tsharkReadArgs(file.getText().toString().trim());
                if (args == null) { mActivity.showToast(mActivity.getString(R.string.terminow_sec_composer_bad_pcap), true); return; }
                launch(tool, args);
            })
            .show();
    }

    /** Build a safely-quoted command and open a terminal in the environment running it. */
    private void launch(@NonNull TerminowSecurityTools.Tool tool, @NonNull List<String> args) {
        if (mEnv == null || mLauncher == null) return;
        String command = TerminowSecurityTools.launchCommand(tool.binName, args);
        if (mDialog != null) mDialog.dismiss();
        mLauncher.openTerminalRunning(mEnv, command);
    }

    // ---------------------------------------------------------------------------------------
    // Details, info, logs

    private void showDetails(@NonNull TerminowSecurityTools.Tool tool, @Nullable TerminowSecurityTools.Status status) {
        StringBuilder sb = new StringBuilder();
        sb.append(tool.description).append("\n\n");
        if (status != null && status.reason != TerminowSecurityTools.Reason.NONE)
            sb.append("• ").append(reasonText(status.reason)).append("\n");
        if (tool.composer == TerminowSecurityTools.Composer.NMAP_TCP)
            sb.append("• ").append(mActivity.getString(R.string.terminow_sec_lim_nmap)).append("\n");
        if (tool.category == TerminowSecurityTools.Category.CAPTURE)
            sb.append("• ").append(mActivity.getString(R.string.terminow_sec_lim_capture)).append("\n");
        if (tool.category == TerminowSecurityTools.Category.WIRELESS)
            sb.append("• ").append(mActivity.getString(R.string.terminow_sec_lim_wireless)).append("\n");
        if (tool.privileged)
            sb.append("• ").append(mActivity.getString(R.string.terminow_sec_lim_proot)).append("\n");
        sb.append("• ").append(mActivity.getString(R.string.terminow_sec_lim_kali)).append("\n");
        sb.append("• ").append(mActivity.getString(R.string.terminow_sec_lim_remote));

        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(tool.label)
            .setMessage(sb.toString())
            .setNeutralButton(R.string.terminow_sec_doc, (d, w) -> openUrl(tool.documentationUrl))
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void openUrl(@NonNull String url) {
        try {
            mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            mActivity.showToast(url, false);
        }
    }

    private void showLog(@NonNull TerminowSecurityTools.Tool tool) {
        TerminowToolInstaller.Job job = TerminowToolInstaller.packageJobOf(mAlias, mUser, tool.id);
        if (job == null || job.getLog().trim().isEmpty())
            job = TerminowToolInstaller.packageActionJobOf(mAlias, mUser, tool.id, TerminowToolInstaller.PkgAction.UPDATE);
        if (job == null || job.getLog().trim().isEmpty())
            job = TerminowToolInstaller.packageActionJobOf(mAlias, mUser, tool.id, TerminowToolInstaller.PkgAction.REMOVE);
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

    // ---------------------------------------------------------------------------------------
    // TermiNow-installed tracking (so Uninstall never touches a pre-existing user tool)

    private SharedPreferences trackPrefs() {
        return mActivity.getSharedPreferences("terminow_sec_tools", Context.MODE_PRIVATE);
    }

    private String trackKey() {
        return mAlias + "|" + mUser;
    }

    private boolean isTracked(@NonNull String pkgId) {
        Set<String> set = trackPrefs().getStringSet(trackKey(), null);
        return set != null && set.contains(pkgId);
    }

    private void track(@NonNull String pkgId) {
        Set<String> set = new HashSet<>(trackPrefs().getStringSet(trackKey(), new HashSet<>()));
        if (set.add(pkgId)) trackPrefs().edit().putStringSet(trackKey(), set).apply();
    }

    private void untrack(@NonNull String pkgId) {
        Set<String> cur = trackPrefs().getStringSet(trackKey(), null);
        if (cur == null || !cur.contains(pkgId)) return;
        Set<String> set = new HashSet<>(cur);
        set.remove(pkgId);
        trackPrefs().edit().putStringSet(trackKey(), set).apply();
    }

    /** When an install TermiNow ran succeeded (and the tool was not already there), remember it. */
    private void recordIfTermiNowInstalled(@NonNull TerminowSecurityTools.Tool tool) {
        TerminowToolInstaller.Job job = TerminowToolInstaller.packageJobOf(mAlias, mUser, tool.id);
        if (job != null && job.getState() == TerminowToolInstaller.State.SUCCEEDED && !job.wasAlreadyInstalled())
            track(tool.id);
    }

    // ---------------------------------------------------------------------------------------
    // Reason / label mapping

    @NonNull
    private String reasonText(@NonNull TerminowSecurityTools.Reason reason) {
        switch (reason) {
            case NO_APT: return mActivity.getString(R.string.terminow_sec_reason_no_apt);
            case UNSUPPORTED_ARCH: return mActivity.getString(R.string.terminow_sec_reason_arch,
                mCaps == null ? "" : mCaps.arch);
            case GUI_DISPLAY: return mActivity.getString(R.string.terminow_sec_reason_gui);
            case WIRELESS_MONITOR: return mActivity.getString(R.string.terminow_sec_reason_wireless);
            case RAW_SOCKET: return mActivity.getString(R.string.terminow_sec_reason_raw);
            default: return "";
        }
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

    private LinearLayout composerBox() {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        return box;
    }

    private EditText field(@NonNull String hint, int inputType) {
        EditText edit = new EditText(mActivity);
        edit.setHint(hint);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | inputType);
        edit.setTextColor(color(R.color.terminow_text));
        edit.setHintTextColor(color(R.color.terminow_text_secondary));
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        return edit;
    }

    private TextView authorizedNote() {
        return note(mActivity.getString(R.string.terminow_sec_authorized));
    }

    private TextView note(@NonNull String value) {
        TextView view = text(value, 12, R.color.terminow_text_secondary);
        view.setPadding(0, dp(6), 0, 0);
        return view;
    }

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
