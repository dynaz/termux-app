package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.linux.TerminowLinuxCatalog;
import com.termux.app.terminow.linux.TerminowLinuxEnv;
import com.termux.app.terminow.linux.TerminowLinuxFileOps;
import com.termux.app.terminow.linux.TerminowLinuxFs;
import com.termux.app.terminow.linux.TerminowLinuxInstaller;
import com.termux.app.terminow.linux.TerminowLinuxPaths;
import com.termux.app.terminow.linux.TerminowLinuxRegistry;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Linux environments in the app: Browse Linux with its catalog and installs, the details and
 * settings of an environment, and uninstalling one. The data comes from
 * {@link TerminowLinuxRegistry}, which only reads what is on disk.
 */
public class TerminowLinuxUi {

    public interface Host {
        void openFiles(@NonNull TerminowLinuxEnv env, @NonNull String user, @NonNull String path);

        void openTerminal(@NonNull TerminowLinuxEnv env, @NonNull String user, @NonNull String path);

        /** The TermiNow tabs that run in an environment now. */
        @NonNull List<TerminalSession> sessionsOf(@NonNull String alias);

        /** The environments changed: installed, uninstalled, settings. */
        void onChanged();
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final Host mHost;
    private final TerminowLinuxFileOps mOps = new TerminowLinuxFileOps.Android();
    private final TerminowLinuxRegistry mRegistry;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    /** Disk usage by alias, computed in the background, -1 while computing. */
    private final Map<String, Long> mUsage = new HashMap<>();
    @Nullable private TerminowLinuxInstaller mInstaller;
    /**
     * The install that runs in this process now, which outlives an activity: an activity that is
     * made again must not take it for one that was killed and clean it up.
     */
    @Nullable private static volatile String sLiveInstall;
    @Nullable private String mInstallingAlias;

    public TerminowLinuxUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull Host host) {
        mActivity = activity;
        mStore = store;
        mHost = host;
        mRegistry = new TerminowLinuxRegistry(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH), new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
            new TerminowLinuxRegistry.Settings() {
                @NonNull
                @Override
                public JSONObject load() {
                    return mStore.getLinuxSettings();
                }

                @Override
                public void save(@NonNull JSONObject settings) {
                    mStore.setLinuxSettings(settings);
                }
            }, mOps, () -> mInstallingAlias);
        // An install that was killed with the app is cleaned up, its folder is not an environment
        String marked = mStore.getLinuxInstalling();
        if (marked != null && !marked.equals(sLiveInstall)) mExecutor.execute(() -> {
            // Not if an install started meanwhile
            if (sLiveInstall != null) return;
            String alias = newInstaller().cleanUpInterrupted();
            if (alias != null) mHandler.post(() -> {
                mActivity.showToast(mActivity.getString(R.string.terminow_linux_cleaned_interrupted, alias), true);
                mHost.onChanged();
            });
        });
    }

    @NonNull
    public TerminowLinuxRegistry getRegistry() {
        return mRegistry;
    }

    @NonNull
    public TerminowLinuxFileOps getOps() {
        return mOps;
    }

    @NonNull
    public TerminowLinuxFs filesOf(@NonNull TerminowLinuxEnv env) {
        return new TerminowLinuxFs(env, mRegistry.mountsOf(env), mOps);
    }

    @NonNull
    /** The alias of the Linux install that runs in this process now, {@code null} if none. */
    @Nullable
    static String liveInstall() {
        return sLiveInstall;
    }

    TerminowLinuxInstaller newInstaller() {
        return new TerminowLinuxInstaller(mRegistry, new File(mActivity.getCacheDir(), "linux-install"), new TerminowLinuxInstaller.Runner() {
            @NonNull
            @Override
            public Process start(@NonNull List<String> command) throws IOException {
                return new TerminowClaudeSetup(mActivity, new TerminowMcpTargets(mRegistry.getPrefix(),
                    new File(TermuxConstants.TERMUX_HOME_DIR_PATH))).start(command);
            }

            @Override
            public void interrupt(int pid) {
                android.os.Process.sendSignal(pid, 2);
            }
        }, new TerminowLinuxInstaller.Marker() {
            @Nullable
            @Override
            public String getAlias() {
                return mStore.getLinuxInstalling();
            }

            @Override
            public void set(@Nullable String alias) {
                sLiveInstall = alias;
                mStore.setLinuxInstalling(alias);
            }
        }, mOps);
    }

    @NonNull
    static String deviceArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        return TerminowLinuxCatalog.archOfAbi(abis.length > 0 ? abis[0] : "");
    }

    // ---------------------------------------------------------------------------------------
    // Disk usage
    // ---------------------------------------------------------------------------------------

    /** The disk usage of an environment, {@code null} while it is computed, which is started. */
    @Nullable
    public Long getUsage(@NonNull TerminowLinuxEnv env) {
        Long usage = mUsage.get(env.alias);
        if (usage == null) {
            mUsage.put(env.alias, -1L);
            mExecutor.execute(() -> {
                long bytes;
                try {
                    bytes = TerminowLinuxFs.diskUsage(env, mRegistry.mountsOf(env), mOps, new TerminowFs.Cancel());
                } catch (IOException e) {
                    bytes = -2;
                }
                long result = bytes;
                mHandler.post(() -> {
                    mUsage.put(env.alias, result);
                    mHost.onChanged();
                });
            });
            return null;
        }
        return usage == -1 ? null : usage;
    }

    @NonNull
    public String usageText(@NonNull TerminowLinuxEnv env) {
        Long usage = getUsage(env);
        if (usage == null) return mActivity.getString(R.string.terminow_linux_usage_calculating);
        if (usage < 0) return mActivity.getString(R.string.terminow_linux_usage_unknown);
        return mActivity.getString(R.string.terminow_linux_usage, android.text.format.Formatter.formatShortFileSize(mActivity, usage));
    }

    @NonNull
    public String statusText(@NonNull TerminowLinuxEnv env) {
        switch (env.status) {
            case INSTALLED: return mActivity.getString(R.string.terminow_linux_status_installed);
            case INSTALLING: return mActivity.getString(R.string.terminow_linux_status_installing);
            default: return mActivity.getString(R.string.terminow_linux_status_incomplete);
        }
    }

    /** "Ubuntu" gets its orange, Debian red, Alpine blue, others the accent. */
    public static int colorOf(@NonNull String distroId) {
        switch (distroId) {
            case "ubuntu": return 0xFFE95420;
            case "debian": return 0xFFD70A53;
            case "alpine": return 0xFF0D597F;
            case "kali": return 0xFF367BF0;
            case "fedora": return 0xFF51A2DA;
            case "archlinux": case "arch": return 0xFF1793D1;
            default: return 0xFF3C4258;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Browse Linux
    // ---------------------------------------------------------------------------------------

    private boolean mShowAvailable;

    /** The Browse Linux screen, on the Available tab or the Installed one. */
    public void showBrowse(boolean available) {
        mShowAvailable = available;
        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = vertical();
        root.setBackground(rounded(color(R.color.terminow_bar_home), 20));
        root.setPadding(dp(18), dp(14), dp(18), dp(18));
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(mActivity.getString(R.string.terminow_linux_browse), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = text("✕", 20, R.color.terminow_text_secondary);
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setContentDescription(mActivity.getString(R.string.terminow_profiles_close));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close);
        root.addView(head, matchWrap());

        LinearLayout tabs = new LinearLayout(mActivity);
        tabs.setPadding(0, dp(10), 0, dp(10));
        TextView installedTab = pill(mActivity.getString(R.string.terminow_linux_tab_installed), !mShowAvailable);
        TextView availableTab = pill(mActivity.getString(R.string.terminow_linux_tab_available), mShowAvailable);
        tabs.addView(installedTab, pillParams());
        tabs.addView(availableTab, pillParams());
        root.addView(tabs, matchWrap());

        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout list = vertical();
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            installedTab.setBackground(rounded(color(!mShowAvailable ? R.color.terminow_panel_accent : R.color.terminow_field), 18));
            installedTab.setTextColor(color(!mShowAvailable ? R.color.terminow_bar_home : R.color.terminow_text));
            availableTab.setBackground(rounded(color(mShowAvailable ? R.color.terminow_panel_accent : R.color.terminow_field), 18));
            availableTab.setTextColor(color(mShowAvailable ? R.color.terminow_bar_home : R.color.terminow_text));
            if (mShowAvailable) fillAvailable(list, dialog);
            else fillInstalled(list, dialog);
        };
        installedTab.setOnClickListener(v -> {
            mShowAvailable = false;
            fill[0].run();
        });
        availableTab.setOnClickListener(v -> {
            mShowAvailable = true;
            fill[0].run();
        });
        fill[0].run();
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(mActivity.getResources().getDisplayMetrics().widthPixels - dp(16), dp(760));
            window.setLayout(width, Math.round(mActivity.getResources().getDisplayMetrics().heightPixels * 0.85f));
        }
        dialog.show();
    }

    private void fillInstalled(@NonNull LinearLayout list, @NonNull Dialog dialog) {
        List<TerminowLinuxEnv> envs = mRegistry.list();
        if (envs.isEmpty()) {
            list.addView(text(mActivity.getString(R.string.terminow_linux_none_installed), 14, R.color.terminow_text_secondary), matchWrap());
            return;
        }
        for (TerminowLinuxEnv env : envs) {
            LinearLayout card = card(env.distroId, env.getTitle(), statusText(env) + " · " + (env.arch.isEmpty() ? "?" : env.arch) +
                " · " + mActivity.getString(R.string.terminow_linux_alias, env.alias));
            if (env.isInstalled()) {
                card.addView(text(usageText(env), 12, R.color.terminow_text_secondary), matchWrap());
                addButtons(card, button(R.string.terminow_linux_files, false, () -> {
                    dialog.dismiss();
                    openFiles(env, env.defaultUser, env.startFolder(env.defaultUser));
                }), button(R.string.terminow_linux_terminal, true, () -> {
                    dialog.dismiss();
                    mHost.openTerminal(env, env.defaultUser, env.startFolder(env.defaultUser));
                }), button(R.string.terminow_linux_details, false, () -> showDetails(env)));
            } else {
                card.addView(text(mActivity.getString(R.string.terminow_linux_incomplete_message), 12, R.color.terminow_mcp_warn), matchWrap());
                addButtons(card, button(R.string.terminow_linux_details, false, () -> showDetails(env)));
            }
            list.addView(card, cardParams());
        }
    }

    private void fillAvailable(@NonNull LinearLayout list, @NonNull Dialog dialog) {
        String arch = deviceArch();
        List<TerminowLinuxEnv> installed = mRegistry.list();
        for (TerminowLinuxCatalog.Entry entry : TerminowLinuxCatalog.ENTRIES) {
            TerminowLinuxCatalog.Support support = entry.supportOn(arch);
            String size = entry.downloadBytes > 0 ? mActivity.getString(R.string.terminow_linux_download_size,
                android.text.format.Formatter.formatShortFileSize(mActivity, entry.downloadBytes)) : "";
            LinearLayout card = card(entry.distroId, entry.name + " " + entry.version, entry.arch + (size.isEmpty() ? "" : " · " + size));
            card.addView(text(entry.description, 13, R.color.terminow_text_secondary), matchWrap());
            String state;
            int stateColor = R.color.terminow_text_secondary;
            boolean already = false;
            for (TerminowLinuxEnv env : installed) if (env.distroId.equals(entry.distroId) && env.isInstalled()) already = true;
            switch (support) {
                case AVAILABLE:
                    state = mActivity.getString(already ? R.string.terminow_linux_compat_installed : R.string.terminow_linux_compat_ok);
                    stateColor = R.color.terminow_panel_accent;
                    break;
                case UNSUPPORTED_ARCH:
                    state = mActivity.getString(R.string.terminow_linux_compat_arch, arch);
                    stateColor = R.color.terminow_mcp_warn;
                    break;
                default:
                    state = mActivity.getString(R.string.terminow_linux_compat_experimental);
                    stateColor = R.color.terminow_mcp_warn;
                    break;
            }
            TextView stateView = text(state, 12, stateColor);
            stateView.setPadding(0, dp(6), 0, 0);
            card.addView(stateView, matchWrap());
            TextView install = button(R.string.terminow_linux_install, support == TerminowLinuxCatalog.Support.AVAILABLE,
                () -> confirmInstall(entry, dialog));
            boolean enabled = support == TerminowLinuxCatalog.Support.AVAILABLE && mInstaller == null;
            install.setEnabled(enabled);
            install.setAlpha(enabled ? 1f : 0.4f);
            addButtons(card, install);
            list.addView(card, cardParams());
        }
        TextView note = text(mActivity.getString(R.string.terminow_linux_catalog_note), 12, R.color.terminow_text_secondary);
        note.setPadding(dp(2), dp(4), dp(2), dp(8));
        list.addView(note, matchWrap());
    }

    private int installedCount() {
        return mRegistry.installed().size();
    }

    private void confirmInstall(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull Dialog browse) {
        // One Linux install at a time, also with the Ubuntu setup
        if (TerminowUbuntuSetupUi.isSetupRunning() || sLiveInstall != null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_linux_install_busy), true);
            return;
        }
        TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.MULTIPLE_ENVIRONMENTS, installedCount(), () -> {
            TerminowLinuxInstaller probe = newInstaller();
            String alias = probe.freeAlias(entry.distroId);
            long usable = usableBytes();
            boolean space = TerminowLinuxInstaller.hasSpace(usable, entry);
            String message = mActivity.getString(R.string.terminow_linux_install_message, entry.name + " " + entry.version,
                android.text.format.Formatter.formatShortFileSize(mActivity, entry.downloadBytes),
                android.text.format.Formatter.formatShortFileSize(mActivity, entry.installedBytes), alias,
                android.text.format.Formatter.formatShortFileSize(mActivity, usable), entry.url, entry.sha256);
            AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(mActivity.getString(R.string.terminow_linux_install_title, entry.name))
                .setMessage(space ? message : message + "\n\n" + mActivity.getString(R.string.terminow_linux_no_space))
                .setNegativeButton(android.R.string.cancel, null);
            if (space) builder.setPositiveButton(R.string.terminow_linux_install, (d, w) -> {
                browse.dismiss();
                install(entry, alias, usable);
            });
            builder.show();
        });
    }

    private long usableBytes() {
        try {
            File dir = mRegistry.getRuntimeDir();
            while (dir != null && !dir.exists()) dir = dir.getParentFile();
            if (dir == null) return 0;
            return new StatFs(dir.getPath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    /** Install with a dialog of the real phases and bytes, and a cancel that cleans up. */
    private void install(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull String alias, long usable) {
        TerminowLinuxInstaller installer = newInstaller();
        mInstaller = installer;
        mInstallingAlias = alias;
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(10), dp(24), dp(4));
        TextView phase = text(mActivity.getString(R.string.terminow_linux_phase_checking), 15, R.color.terminow_text);
        box.addView(phase, matchWrap());
        ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
        LinearLayout.LayoutParams barParams = matchWrap();
        barParams.topMargin = dp(12);
        box.addView(bar, barParams);
        TextView detail = text("", 12, R.color.terminow_text_secondary);
        detail.setPadding(0, dp(8), 0, 0);
        box.addView(detail, matchWrap());
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_linux_installing_title, entry.name))
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        progress.setOnShowListener(d -> progress.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
            installer.cancel();
            phase.setText(R.string.terminow_linux_phase_cancelling);
            v.setEnabled(false);
        }));
        progress.show();
        mHost.onChanged();
        mExecutor.execute(() -> {
            String error = null;
            TerminowLinuxEnv env = null;
            try {
                env = installer.install(entry, alias, deviceArch(), usable, new TerminowLinuxInstaller.Listener() {
                    @Override
                    public void onPhase(@NonNull TerminowLinuxInstaller.Phase p, @NonNull String text) {
                        mHandler.post(() -> {
                            if (installer.isCancelled()) return;
                            phase.setText(phaseText(p));
                            bar.setIndeterminate(p != TerminowLinuxInstaller.Phase.DOWNLOADING);
                            if (p != TerminowLinuxInstaller.Phase.DOWNLOADING) detail.setText(text.startsWith("http") ? "" : text);
                        });
                    }

                    @Override
                    public void onProgress(long done, long total) {
                        mHandler.post(() -> {
                            if (total > 0) {
                                bar.setIndeterminate(false);
                                bar.setProgress((int) (done * 1000 / total));
                            }
                            detail.setText(mActivity.getString(R.string.terminow_linux_downloaded,
                                android.text.format.Formatter.formatShortFileSize(mActivity, done),
                                total > 0 ? android.text.format.Formatter.formatShortFileSize(mActivity, total) : "?"));
                        });
                    }
                });
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            String finalError = error;
            TerminowLinuxEnv finalEnv = env;
            mHandler.post(() -> {
                mInstaller = null;
                mInstallingAlias = null;
                if (!mActivity.isFinishing()) progress.dismiss();
                mHost.onChanged();
                if (finalEnv != null) {
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(mActivity.getString(R.string.terminow_linux_installed_title, finalEnv.getTitle()))
                        .setMessage(R.string.terminow_linux_installed_message)
                        .setPositiveButton(R.string.terminow_linux_terminal, (d, w) -> mHost.openTerminal(finalEnv, "root", finalEnv.homeOf("root")))
                        .setNegativeButton(android.R.string.ok, null)
                        .show();
                } else {
                    boolean cancelled = installer.isCancelled();
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(cancelled ? R.string.terminow_linux_cancelled_title : R.string.terminow_linux_failed_title)
                        .setMessage(mActivity.getString(cancelled ? R.string.terminow_linux_cancelled_message : R.string.terminow_linux_failed_message, finalError))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
            });
        });
    }

    @NonNull
    private String phaseText(@NonNull TerminowLinuxInstaller.Phase phase) {
        switch (phase) {
            case CHECKING: return mActivity.getString(R.string.terminow_linux_phase_checking);
            case DOWNLOADING: return mActivity.getString(R.string.terminow_linux_phase_downloading);
            case VERIFYING: return mActivity.getString(R.string.terminow_linux_phase_verifying);
            case EXTRACTING: return mActivity.getString(R.string.terminow_linux_phase_extracting);
            case SETTING_UP: return mActivity.getString(R.string.terminow_linux_phase_setup);
            case VALIDATING: return mActivity.getString(R.string.terminow_linux_phase_validating);
            default: return mActivity.getString(R.string.terminow_linux_phase_done);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Details, settings and uninstall
    // ---------------------------------------------------------------------------------------

    public void openFiles(@NonNull TerminowLinuxEnv env, @NonNull String user, @NonNull String path) {
        TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.LINUX_FILES, installedCount(),
            () -> mHost.openFiles(env, user, path));
    }

    public void showDetails(@NonNull TerminowLinuxEnv given) {
        TerminowLinuxEnv env = mRegistry.get(given.alias);
        if (env == null) {
            mActivity.showToast(mActivity.getString(R.string.terminow_linux_gone, given.alias), true);
            return;
        }
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(6), dp(24), dp(6));
        int active = mHost.sessionsOf(env.alias).size();
        String info = mActivity.getString(R.string.terminow_linux_details_info, env.prettyName.isEmpty() ? env.getTitle() : env.prettyName,
            env.arch.isEmpty() ? "?" : env.arch, env.alias, statusText(env), usageText(env),
            mActivity.getResources().getQuantityString(R.plurals.terminow_linux_terminals, active, active));
        box.addView(text(info, 13, R.color.terminow_text_secondary), matchWrap());

        AlertDialog[] dialog = new AlertDialog[1];
        box.addView(row(mActivity.getString(R.string.terminow_linux_default_user), env.defaultUser, () -> pickDefaultUser(env, dialog[0])));
        box.addView(row(mActivity.getString(R.string.terminow_linux_default_folder),
            env.defaultFolder.isEmpty() ? mActivity.getString(R.string.terminow_linux_home_of, env.homeOf(env.defaultUser)) : env.defaultFolder,
            () -> pickDefaultFolder(env, dialog[0])));
        StringBuilder shared = new StringBuilder();
        for (TerminowLinuxEnv.SharedFolder folder : env.sharedFolders)
            shared.append(folder.guestPath).append("  ←  ").append(folder.hostPath).append('\n');
        box.addView(row(mActivity.getString(R.string.terminow_linux_shared_folders),
            shared.length() == 0 ? mActivity.getString(R.string.terminow_linux_shared_none) : shared.toString().trim(),
            () -> editSharedFolders(env, dialog[0])));
        TextView note = text(mActivity.getString(R.string.terminow_linux_shared_default_note), 12, R.color.terminow_text_secondary);
        note.setPadding(0, dp(4), 0, 0);
        box.addView(note, matchWrap());
        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setPadding(0, dp(12), 0, dp(4));
        if (env.isInstalled()) {
            buttons.addView(button(R.string.terminow_linux_files, false, () -> {
                dialog[0].dismiss();
                openFiles(env, env.defaultUser, env.startFolder(env.defaultUser));
            }), buttonParams());
            buttons.addView(button(R.string.terminow_linux_terminal, true, () -> {
                dialog[0].dismiss();
                mHost.openTerminal(env, env.defaultUser, env.startFolder(env.defaultUser));
            }), buttonParams());
        }
        box.addView(buttons, matchWrap());
        // Destructive, away from the ordinary buttons
        TextView uninstall = text(mActivity.getString(env.isInstalled() ? R.string.terminow_linux_uninstall : R.string.terminow_linux_remove_incomplete),
            14, R.color.terminow_mcp_warn);
        uninstall.setTypeface(Typeface.DEFAULT_BOLD);
        uninstall.setPadding(0, dp(18), 0, dp(6));
        uninstall.setOnClickListener(v -> {
            dialog[0].dismiss();
            confirmUninstall(env);
        });
        box.addView(uninstall, matchWrap());
        ScrollView scroll = new ScrollView(mActivity);
        scroll.addView(box);
        dialog[0] = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(env.getTitle())
            .setView(scroll)
            .setNegativeButton(R.string.terminow_profiles_close, null)
            .show();
    }

    private void pickDefaultUser(@NonNull TerminowLinuxEnv env, @Nullable AlertDialog parent) {
        List<String> names = new ArrayList<>();
        for (TerminowLinuxEnv.User user : env.users) names.add(user.name);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_linux_default_user)
            .setItems(names.toArray(new CharSequence[0]), (d, which) -> {
                mRegistry.setDefaultUser(env.alias, names.get(which));
                if (parent != null) parent.dismiss();
                mHost.onChanged();
                showDetails(env);
            })
            .show();
    }

    private void pickDefaultFolder(@NonNull TerminowLinuxEnv env, @Nullable AlertDialog parent) {
        EditText field = field(env.defaultFolder.isEmpty() ? env.homeOf(env.defaultUser) : env.defaultFolder);
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(6), dp(24), 0);
        box.addView(text(mActivity.getString(R.string.terminow_linux_default_folder_hint), 12, R.color.terminow_text_secondary), matchWrap());
        box.addView(field, matchWrap());
        TextView error = text("", 12, R.color.terminow_mcp_warn);
        box.addView(error, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_linux_default_folder)
            .setView(box)
            .setPositiveButton(R.string.terminow_profiles_save, null)
            .setNeutralButton(R.string.terminow_linux_use_home, (d, w) -> {
                mRegistry.setDefaultFolder(env.alias, "");
                if (parent != null) parent.dismiss();
                showDetails(env);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String path = TerminowLinuxPaths.normalize(field.getText().toString().trim());
            if (!isFolderOf(env, path)) {
                error.setText(R.string.terminow_launcher_folder_invalid);
                return;
            }
            mRegistry.setDefaultFolder(env.alias, path);
            dialog.dismiss();
            if (parent != null) parent.dismiss();
            mHost.onChanged();
            showDetails(env);
        }));
        dialog.show();
    }

    /** Whether a Linux path is a folder of the environment that a shell can start in. */
    public boolean isFolderOf(@NonNull TerminowLinuxEnv env, @NonNull String path) {
        if (!path.startsWith("/")) return false;
        File file = filesOf(env).hostFileOf(path);
        return file != null && file.isDirectory();
    }

    private void editSharedFolders(@NonNull TerminowLinuxEnv env, @Nullable AlertDialog parent) {
        List<TerminowLinuxEnv.SharedFolder> folders = new ArrayList<>(env.sharedFolders);
        List<CharSequence> items = new ArrayList<>();
        for (TerminowLinuxEnv.SharedFolder folder : folders)
            items.add(mActivity.getString(R.string.terminow_linux_shared_remove_item, folder.guestPath, folder.hostPath));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_linux_shared_folders)
            .setItems(items.toArray(new CharSequence[0]), (d, which) -> {
                TerminowLinuxEnv.SharedFolder removed = folders.remove(which);
                mRegistry.setSharedFolders(env.alias, folders);
                // Only the binding goes, the host folder and its files stay
                mActivity.showToast(mActivity.getString(R.string.terminow_linux_shared_removed, removed.guestPath), false);
                if (parent != null) parent.dismiss();
                showDetails(env);
            })
            .setPositiveButton(R.string.terminow_linux_shared_add, (d, w) -> addSharedFolder(env, folders, parent))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void addSharedFolder(@NonNull TerminowLinuxEnv env, @NonNull List<TerminowLinuxEnv.SharedFolder> folders,
                                 @Nullable AlertDialog parent) {
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(6), dp(24), 0);
        box.addView(text(mActivity.getString(R.string.terminow_linux_shared_host), 12, R.color.terminow_text_secondary), matchWrap());
        EditText host = field(TermuxConstants.TERMUX_HOME_DIR_PATH + "/");
        box.addView(host, matchWrap());
        box.addView(text(mActivity.getString(R.string.terminow_linux_shared_guest), 12, R.color.terminow_text_secondary), matchWrap());
        EditText guest = field("/mnt/");
        box.addView(guest, matchWrap());
        TextView error = text("", 12, R.color.terminow_mcp_warn);
        box.addView(error, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_linux_shared_add)
            .setView(box)
            .setPositiveButton(R.string.terminow_profiles_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String hostPath = host.getText().toString().trim();
            String guestPath = TerminowLinuxPaths.normalize(guest.getText().toString().trim());
            File hostDir = new File(hostPath);
            if (!TerminowLinuxRegistry.isValidShare(hostPath, guestPath) || !hostDir.isDirectory()) {
                error.setText(R.string.terminow_linux_shared_invalid);
                return;
            }
            folders.add(new TerminowLinuxEnv.SharedFolder(hostDir.getAbsolutePath(), guestPath));
            mRegistry.setSharedFolders(env.alias, folders);
            dialog.dismiss();
            if (parent != null) parent.dismiss();
            mActivity.showToast(mActivity.getString(R.string.terminow_linux_shared_added), true);
            showDetails(env);
        }));
        dialog.show();
    }

    /**
     * Uninstall: the tabs that run in it are named, it is said that its files go, and it has to be
     * confirmed. Shared folders are bindings, the host folders stay.
     */
    private void confirmUninstall(@NonNull TerminowLinuxEnv env) {
        List<TerminalSession> sessions = mHost.sessionsOf(env.alias);
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(6), dp(24), 0);
        StringBuilder message = new StringBuilder(mActivity.getString(R.string.terminow_linux_uninstall_message, env.getTitle(), env.alias));
        if (!sessions.isEmpty()) {
            message.append("\n\n").append(mActivity.getString(R.string.terminow_linux_uninstall_sessions, sessions.size()));
            for (TerminalSession session : sessions) message.append("\n• ").append(session.mSessionName != null ? session.mSessionName : "?");
        }
        box.addView(text(message.toString(), 13, R.color.terminow_text), matchWrap());
        CheckBox understood = new CheckBox(mActivity);
        understood.setText(mActivity.getString(R.string.terminow_linux_uninstall_confirm_check, env.alias));
        understood.setTextColor(color(R.color.terminow_text));
        box.addView(understood, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_linux_uninstall_title, env.getTitle()))
            .setView(box)
            .setPositiveButton(sessions.isEmpty() ? R.string.terminow_linux_uninstall : R.string.terminow_linux_uninstall_close_tabs, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> {
            View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setEnabled(false);
            understood.setOnCheckedChangeListener((b, checked) -> positive.setEnabled(checked));
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                for (TerminalSession session : sessions) session.finishIfRunning();
                uninstall(env);
            });
        });
        dialog.show();
    }

    private void uninstall(@NonNull TerminowLinuxEnv env) {
        AlertDialog progress = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_linux_uninstalling, env.getTitle()))
            .setView(new ProgressBar(mActivity))
            .setCancelable(false)
            .show();
        mExecutor.execute(() -> {
            String error = null;
            try {
                // The tabs need a moment to end before proot-distro sees no sessions
                Thread.sleep(800);
                Process process = new TerminowClaudeSetup(mActivity, new TerminowMcpTargets(mRegistry.getPrefix(),
                    new File(TermuxConstants.TERMUX_HOME_DIR_PATH))).start(Arrays.asList(
                    new File(mRegistry.getPrefix(), "bin/proot-distro").getPath(), "remove", env.alias));
                process.getOutputStream().close();
                StringBuilder log = new StringBuilder();
                Thread err = new Thread(() -> readAll(process.getErrorStream(), log));
                err.start();
                readAll(process.getInputStream(), log);
                err.join(30000);
                int exit = process.waitFor();
                if (exit != 0 || mRegistry.get(env.alias) != null) {
                    String text = log.toString().replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").trim();
                    error = text.isEmpty() ? "exit " + exit : text;
                }
            } catch (IOException | InterruptedException e) {
                error = String.valueOf(e.getMessage());
            }
            String finalError = error;
            mHandler.post(() -> {
                if (!mActivity.isFinishing()) progress.dismiss();
                if (finalError == null) {
                    mRegistry.forget(env.alias);
                    mUsage.remove(env.alias);
                    mActivity.showToast(mActivity.getString(R.string.terminow_linux_uninstalled, env.getTitle()), true);
                } else {
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(R.string.terminow_linux_uninstall_failed)
                        .setMessage(finalError)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                }
                mHost.onChanged();
            });
        });
    }

    private static void readAll(@NonNull java.io.InputStream stream, @NonNull StringBuilder log) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (log) {
                    log.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            // Ended
        }
    }

    // ---------------------------------------------------------------------------------------
    // Views, in the style of the launcher
    // ---------------------------------------------------------------------------------------

    @NonNull
    private LinearLayout card(@NonNull String distroId, @NonNull String title, @NonNull String subtitle) {
        LinearLayout card = vertical();
        card.setBackground(rounded(color(R.color.terminow_surface), 16));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(badge(distroId), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(12), 0, 0, 0);
        TextView titleView = text(title, 16, R.color.terminow_text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(titleView);
        texts.addView(text(subtitle, 12, R.color.terminow_text_secondary), matchWrap());
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(head, matchWrap());
        return card;
    }

    /** The first letter of the distribution on its color, as its icon. */
    @NonNull
    public TextView badge(@NonNull String distroId) {
        String letter = distroId.isEmpty() ? "L" : distroId.substring(0, 1).toUpperCase(Locale.ROOT);
        TextView badge = new TextView(mActivity);
        badge.setText(letter);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(colorOf(distroId));
        badge.setBackground(circle);
        return badge;
    }

    @NonNull
    private View row(@NonNull String label, @NonNull String value, @NonNull Runnable onClick) {
        LinearLayout row = vertical();
        row.setPadding(0, dp(12), 0, dp(4));
        TextView labelView = text(label, 12, R.color.terminow_text_secondary);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(labelView, matchWrap());
        TextView valueView = text(value + "  ›", 15, R.color.terminow_text);
        row.addView(valueView, matchWrap());
        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private void addButtons(@NonNull LinearLayout card, @NonNull View... buttons) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setPadding(0, dp(10), 0, 0);
        for (View button : buttons) row.addView(button, buttonParams());
        card.addView(row, matchWrap());
    }

    @NonNull
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMarginEnd(dp(8));
        return params;
    }

    @NonNull
    private TextView button(int labelRes, boolean primary, @NonNull Runnable action) {
        TextView button = text(mActivity.getString(labelRes), 13, primary ? R.color.terminow_bar_home : R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(rounded(color(primary ? R.color.terminow_panel_accent : R.color.terminow_field), 21));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    @NonNull
    private TextView pill(@NonNull String label, boolean selected) {
        TextView pill = text(label, 14, selected ? R.color.terminow_bar_home : R.color.terminow_text);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(16), 0, dp(16), 0);
        return pill;
    }

    @NonNull
    private LinearLayout.LayoutParams pillParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        params.setMarginEnd(dp(8));
        return params;
    }

    @NonNull
    private EditText field(@NonNull String value) {
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setText(value);
        field.setSelection(value.length());
        field.setTextColor(color(R.color.terminow_text));
        field.setTypeface(Typeface.MONOSPACE);
        return field;
    }

    @NonNull
    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        return params;
    }

    @NonNull
    private static GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radiusDp * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        shape.setColor(color);
        return shape;
    }

    @NonNull
    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(mActivity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    @NonNull
    private TextView text(@NonNull String text, int sp, int colorRes) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color(colorRes));
        return view;
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics()));
    }

}
