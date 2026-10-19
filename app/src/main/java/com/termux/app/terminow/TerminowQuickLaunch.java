package com.termux.app.terminow;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Quick Launch: the environments that are installed and the pinned launch profiles, each one tap
 * from a terminal; remote connections; and the sessions that can be resumed, found just now.
 *
 * Showing it never runs a command of the user, and never starts, stops or changes a session:
 * it only lists what is there, with the read-only checks of the launcher (tmux sessions, Claude
 * Code). A profile runs only from its Open button, after the confirmation of what it runs.
 */
public class TerminowQuickLaunch {

    public interface Host {
        void connect(boolean sftp);

        void addHost();

        /** The File Manager at the home of the TermiNow shell. */
        void openLocalFiles();

        @NonNull List<TerminalSession> sessions();

        @Nullable TerminalSession currentSession();

        void showSession(@NonNull TerminalSession session);

        void closeSession(@NonNull TerminalSession session);

        void renameSession(@NonNull TerminalSession session);

        /** The system file picker for a root filesystem archive to import. */
        void pickRootfs();

        /** Connect again to the saved host with the key, like tapping its card. */
        void reconnectHost(@NonNull String hostKey);

        /** Back up a Linux environment to one of the saved hosts. */
        void backupEnvironment(@NonNull String alias, @NonNull String title);

        /** Back up a Linux environment to a file on the phone, in a folder that the user picks. */
        void backupToPhone(@NonNull String alias, @NonNull String title);

        /** Restore a Linux environment from a backup file on the phone that the user picks. */
        void restoreFromPhone();
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final TerminowLauncher mLauncher;
    private final TerminowLinuxUi mLinux;
    private final Host mHost;
    private final ScrollView mScroll;
    private final LinearLayout mContent;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mShown;
    private int mColumns = 1;
    private final Runnable mRenderSoon = this::render;
    private final Runnable mOnChanged = () -> {
        mHandler.removeCallbacks(mRenderSoon);
        mHandler.postDelayed(mRenderSoon, 120);
    };

    /** When each tab was last shown, by the handle of its session: the time that Recent sessions sort by. */
    private static final Map<String, Long> sLastUsed = new HashMap<>();

    public TerminowQuickLaunch(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull TerminowLauncher launcher,
                               @NonNull TerminowLinuxUi linux, @NonNull Host host, @NonNull ScrollView scroll) {
        mActivity = activity;
        mStore = store;
        mLauncher = launcher;
        mLinux = linux;
        mHost = host;
        mScroll = scroll;
        mContent = new LinearLayout(activity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(16), dp(18), dp(16), dp(96));
        scroll.addView(mContent, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The columns follow the width that the page has, also in split screen, not the device
        scroll.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int columns = TerminowQuickModel.columns(Math.round((right - left) / mActivity.getResources().getDisplayMetrics().density));
            if (columns != mColumns && mShown) {
                mColumns = columns;
                mHandler.post(this::render);
            }
        });
    }

    /** A tab was shown, for the order of Recent sessions. */
    public static void noteShown(@Nullable TerminalSession session) {
        if (session == null) return;
        synchronized (sLastUsed) {
            sLastUsed.put(session.mHandle, System.currentTimeMillis());
        }
    }

    /** When a tab was last shown, 0 if not since TermiNow started. */
    public static long lastShown(@NonNull TerminalSession session) {
        synchronized (sLastUsed) {
            Long used = sLastUsed.get(session.mHandle);
            return used != null ? used : 0;
        }
    }

    public void show() {
        mShown = true;
        mScroll.setVisibility(View.VISIBLE);
        mLauncher.setOnStateChanged(mOnChanged);
        mLinux.addProgressListener(mOnChanged);
        int width = mScroll.getWidth();
        if (width > 0) mColumns = TerminowQuickModel.columns(Math.round(width / mActivity.getResources().getDisplayMetrics().density));
        render();
        // Only lists: the tmux sessions and Claude Code of each environment, once until asked again
        for (TerminowLauncher.Environment environment : mLauncher.environments()) {
            mLauncher.listTmux(environment, false);
            if (environment.container != null) mLauncher.detectClaude(environment);
        }
    }

    public void hide() {
        mShown = false;
        mScroll.setVisibility(View.GONE);
        mLauncher.setOnStateChanged(null);
        mLinux.removeProgressListener(mOnChanged);
    }

    public boolean isShown() {
        return mShown;
    }

    /** Draw again, like after an environment was installed or a profile saved. */
    public void refresh() {
        if (mShown) mOnChanged.run();
    }

    // ---------------------------------------------------------------------------------------

    private void render() {
        if (!mShown) return;
        mContent.removeAllViews();
        TextView title = text(mActivity.getString(R.string.terminow_quick_title), 26, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        mContent.addView(title);
        TextView subtitle = text(mActivity.getString(R.string.terminow_quick_subtitle), 15, R.color.terminow_text_secondary);
        subtitle.setPadding(0, dp(2), 0, dp(6));
        mContent.addView(subtitle);

        List<View> cards = new ArrayList<>();
        List<TerminowLauncher.Environment> environments = mLauncher.environments();
        List<TerminowLaunchProfile> profiles = mStore.getLaunchProfiles();
        List<String> envIds = new ArrayList<>();
        Map<String, TerminowLauncher.Environment> byId = new HashMap<>();
        for (TerminowLauncher.Environment environment : environments) {
            String id = TerminowQuickModel.envId(environment.key);
            envIds.add(id);
            byId.put(id, environment);
        }
        List<String> profileIds = new ArrayList<>();
        Map<String, TerminowLaunchProfile> profilesById = new HashMap<>();
        for (TerminowLaunchProfile profile : profiles) {
            String id = TerminowQuickModel.profileId(profile.id);
            profileIds.add(id);
            profilesById.put(id, profile);
        }
        List<String> pins = mStore.getQuickPins();
        String defaultId = TerminowQuickModel.idOfDefault(mStore.getDefaultLaunchProfile());
        for (String id : TerminowQuickModel.cards(envIds, profileIds, pins)) {
            TerminowLauncher.Environment environment = byId.get(id);
            if (environment != null) cards.add(environmentCard(id, environment, pins, defaultId));
            TerminowLaunchProfile profile = profilesById.get(id);
            if (profile != null) cards.add(profileCard(id, profile, pins, defaultId));
        }
        // What is being installed or imported now, and what was left incomplete: never Ready
        TerminowLinuxUi.Progress progress = TerminowLinuxUi.progress();
        if (progress != null && !envIds.contains(TerminowQuickModel.envId(progress.alias))) cards.add(installingCard(progress));
        for (TerminowLinuxEnv env : mLinux.getRegistry().list()) {
            if (env.status == TerminowLinuxEnv.Status.INCOMPLETE && (progress == null || !progress.alias.equals(env.alias)))
                cards.add(incompleteCard(env));
        }
        // Ubuntu when it is not there: the real install, not a pretend environment
        TerminowLinuxCatalog.Entry ubuntu = TerminowLinuxCatalog.get("ubuntu");
        boolean hasUbuntu = false;
        for (TerminowLinuxEnv env : mLinux.getRegistry().list()) hasUbuntu |= env.distroId.equals("ubuntu");
        if (!hasUbuntu && progress == null && ubuntu != null &&
            ubuntu.supportOn(TerminowLinuxUi.deviceArch()) == TerminowLinuxCatalog.Support.AVAILABLE)
            cards.add(installCard(ubuntu));
        cards.add(addEnvironmentCard());
        mContent.addView(grid(cards, mColumns));

        mContent.addView(divider());
        mContent.addView(sectionTitle(mActivity.getString(R.string.terminow_quick_remote), null, null));
        mContent.addView(sectionSubtitle(mActivity.getString(R.string.terminow_quick_remote_subtitle)));
        List<View> remote = new ArrayList<>();
        remote.add(tile(R.drawable.ic_terminow_terminal, mActivity.getString(R.string.terminow_quick_ssh),
            mActivity.getString(R.string.terminow_quick_ssh_summary), false, () -> mHost.connect(false)));
        remote.add(tile(R.drawable.ic_terminow_folder, mActivity.getString(R.string.terminow_quick_sftp),
            mActivity.getString(R.string.terminow_quick_sftp_summary), false, () -> mHost.connect(true)));
        remote.add(tile(R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_quick_add_host),
            mActivity.getString(R.string.terminow_quick_add_host_summary), true, mHost::addHost));
        mContent.addView(grid(remote, mColumns == 1 ? 1 : 3));

        mContent.addView(divider());
        List<TerminowQuickModel.Recent> recent = recentSessions();
        mContent.addView(sectionTitle(mActivity.getString(R.string.terminow_quick_recent),
            TerminowQuickModel.hasMore(recent) ? mActivity.getString(R.string.terminow_quick_view_all, recent.size()) : null,
            () -> showAllSessions(recent)));
        mContent.addView(sectionSubtitle(mActivity.getString(R.string.terminow_quick_recent_subtitle)));
        if (recent.isEmpty()) {
            TextView empty = text(mActivity.getString(R.string.terminow_quick_recent_empty), 14, R.color.terminow_text_secondary);
            empty.setPadding(dp(14), dp(12), dp(14), dp(12));
            empty.setBackground(rounded(color(R.color.terminow_quick_card), 12, color(R.color.terminow_quick_border)));
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(10);
            mContent.addView(empty, params);
        }
        // Two compact rows at most, one under the other on every width; the rest are behind View all
        for (TerminowQuickModel.Recent session : TerminowQuickModel.visible(recent)) mContent.addView(sessionRow(session));

        TextView tools = text(mActivity.getString(R.string.terminow_quick_more_tools), 14, R.color.terminow_quick_primary);
        tools.setTypeface(Typeface.DEFAULT_BOLD);
        tools.setPadding(0, dp(20), 0, dp(8));
        tools.setMinHeight(dp(48));
        tools.setOnClickListener(v -> mLauncher.showTools());
        mContent.addView(tools);
    }

    // ---------------------------------------------------------------------------------------
    // Cards

    @NonNull
    private String nameOf(@NonNull String id, @NonNull String fallback) {
        String name = mStore.getQuickName(id);
        return name.isEmpty() ? fallback : name;
    }

    @Nullable
    private TerminowLinuxEnv linuxOf(@NonNull TerminowLauncher.Environment environment) {
        return environment.container != null ? mLinux.getRegistry().get(environment.key) : null;
    }

    static int iconOf(@NonNull String distroId) {
        switch (distroId) {
            case "ubuntu": return R.drawable.ic_terminow_os_ubuntu;
            case "debian": return R.drawable.ic_terminow_os_debian;
            case "alpine": return R.drawable.ic_terminow_os_alpine;
            case "local": return R.drawable.ic_terminow_os_local;
            default: return R.drawable.ic_terminow_os_linux;
        }
    }

    @NonNull
    private View environmentCard(@NonNull String id, @NonNull TerminowLauncher.Environment environment, @NonNull List<String> pins,
                                 @NonNull String defaultId) {
        TerminowLinuxEnv linux = linuxOf(environment);
        String distro = environment.isLocal() ? "local" : linux != null ? linux.distroId : "";
        String type = environment.isLocal() ? mActivity.getString(R.string.terminow_quick_type_local) :
            linux != null ? typeOf(linux) : mActivity.getString(R.string.terminow_quick_type_custom);
        String fallbackName = environment.isLocal() ? mActivity.getString(R.string.terminow_quick_local_name) :
            linux != null ? linux.getShortName() : environment.label;
        LinearLayout card = card();
        card.addView(cardHeader(iconOf(distro), null, nameOf(id, fallbackName), type,
            mActivity.getString(R.string.terminow_quick_ready), R.color.terminow_quick_ready, id.equals(defaultId),
            anchor -> environmentMenu(anchor, id, environment, pins)));
        card.addView(primaryButton(mActivity.getString(R.string.terminow_quick_open_terminal), () -> mLauncher.openTerminal(environment)));
        LinearLayout secondary = new LinearLayout(mActivity);
        secondary.setOrientation(LinearLayout.HORIZONTAL);
        if (environment.container != null) {
            // Claude Code only where it was found
            TerminowLauncher.ClaudeState claude = mLauncher.claudeOf(environment);
            if (claude != null && claude.isInstalled())
                secondary.addView(secondaryButton(R.drawable.ic_terminow_launch, mActivity.getString(R.string.terminow_quick_claude),
                    () -> mLauncher.openClaude(environment)), weighted());
            secondary.addView(secondaryButton(R.drawable.ic_terminow_folder, mActivity.getString(R.string.terminow_quick_files),
                () -> mLauncher.openFilesOf(environment)), weighted());
            if (linux != null)
                secondary.addView(secondaryButton(R.drawable.ic_terminow_download, mActivity.getString(R.string.terminow_quick_backup),
                    () -> chooseBackup(linux.alias, environment.shortName())), weighted());
        } else {
            secondary.addView(secondaryButton(R.drawable.ic_terminow_folder, mActivity.getString(R.string.terminow_quick_files),
                mHost::openLocalFiles), weighted());
        }
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        card.addView(secondary, params);
        return card;
    }

    @NonNull
    private String typeOf(@NonNull TerminowLinuxEnv linux) {
        switch (linux.distroId) {
            case "ubuntu": return "Ubuntu" + (linux.version.isEmpty() ? "" : " " + linux.version);
            case "debian": return "Debian" + (linux.version.isEmpty() ? "" : " " + linux.version);
            case "alpine": return "Alpine" + (linux.version.isEmpty() ? "" : " " + linux.version);
            default: return mActivity.getString(R.string.terminow_quick_type_custom_linux, linux.getTitle());
        }
    }

    @NonNull
    private View profileCard(@NonNull String id, @NonNull TerminowLaunchProfile profile, @NonNull List<String> pins, @NonNull String defaultId) {
        TerminowLauncher.Environment environment = mLauncher.environmentFor(profile.environment);
        String type = mActivity.getString(R.string.terminow_quick_type_profile,
            environment != null ? environment.shortName() : profile.environment);
        boolean ready = environment != null;
        LinearLayout card = card();
        card.addView(cardHeader(0, profile, nameOf(id, profile.name), type,
            mActivity.getString(ready ? R.string.terminow_quick_ready : R.string.terminow_quick_needs_environment, profile.environment),
            ready ? R.color.terminow_quick_ready : R.color.terminow_mcp_warn, id.equals(defaultId),
            anchor -> profileMenu(anchor, id, profile, pins)));
        TextView open = primaryButton(mActivity.getString(R.string.terminow_quick_open), () -> mLauncher.launchProfile(profile));
        open.setEnabled(ready);
        open.setAlpha(ready ? 1f : 0.45f);
        card.addView(open);
        return card;
    }

    @NonNull
    private View installCard(@NonNull TerminowLinuxCatalog.Entry entry) {
        LinearLayout card = card();
        card.addView(cardHeader(iconOf(entry.distroId), null, entry.name, entry.name + " " + entry.version,
            mActivity.getString(R.string.terminow_quick_not_installed), R.color.terminow_quick_idle, false, null));
        card.addView(primaryButton(mActivity.getString(R.string.terminow_quick_install, android.text.format.Formatter.formatShortFileSize(mActivity,
            entry.downloadBytes)), () -> mLinux.installEntry(entry)));
        return card;
    }

    @NonNull
    private View installingCard(@NonNull TerminowLinuxUi.Progress progress) {
        LinearLayout card = card();
        card.addView(cardHeader(iconOf(progress.distroId), null, progress.name, progress.alias,
            progress.phase, R.color.terminow_quick_primary, false, null));
        ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(progress.permille < 0);
        if (progress.permille >= 0) bar.setProgress(progress.permille);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(color(R.color.terminow_quick_primary)));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(16);
        card.addView(bar, params);
        return card;
    }

    @NonNull
    private View incompleteCard(@NonNull TerminowLinuxEnv env) {
        LinearLayout card = card();
        card.addView(cardHeader(iconOf(env.distroId), null, env.getShortName(), env.alias,
            mActivity.getString(R.string.terminow_quick_incomplete), R.color.terminow_mcp_warn, false, null));
        TextView manage = secondaryButton(0, mActivity.getString(R.string.terminow_quick_manage), () -> mLinux.showDetails(env));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        card.addView(manage, params);
        return card;
    }

    @NonNull
    private View addEnvironmentCard() {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(16), dp(20), dp(16), dp(20));
        card.setMinimumHeight(dp(150));
        card.setBackground(dashed());
        ImageView plus = new ImageView(mActivity);
        plus.setImageResource(R.drawable.ic_terminow_add);
        plus.setColorFilter(color(R.color.terminow_text));
        plus.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(color(R.color.terminow_quick_border));
        plus.setBackground(circle);
        card.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text(mActivity.getString(R.string.terminow_quick_add_environment), 17, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, 0);
        card.addView(title);
        TextView summary = text(mActivity.getString(R.string.terminow_quick_add_environment_summary), 13, R.color.terminow_text_secondary);
        summary.setGravity(Gravity.CENTER);
        card.addView(summary);
        card.setOnClickListener(v -> showAddEnvironment());
        card.setContentDescription(mActivity.getString(R.string.terminow_quick_add_environment));
        return card;
    }

    /** The icon, the name, its type, its state, and the ⋮ of a card. */
    @NonNull
    private View cardHeader(int iconRes, @Nullable TerminowLaunchProfile profile, @NonNull String name, @NonNull String type,
                            @NonNull String state, int stateColor, boolean isDefault, @Nullable java.util.function.Consumer<View> onMenu) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View icon;
        if (profile != null) {
            TextView emoji = text(profile.icon, 26, R.color.terminow_text);
            emoji.setGravity(Gravity.CENTER);
            GradientDrawable tile = new GradientDrawable();
            tile.setCornerRadius(dp(14));
            tile.setColor(parseColor(profile.color));
            emoji.setBackground(tile);
            icon = emoji;
        } else {
            ImageView image = new ImageView(mActivity);
            image.setImageResource(iconRes);
            icon = image;
        }
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(4), 0);
        TextView title = text(name, 17, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView typeView = text(type + (isDefault ? "  ·  " + mActivity.getString(R.string.terminow_quick_default) : ""), 13,
            R.color.terminow_text_secondary);
        typeView.setSingleLine(true);
        typeView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(typeView);
        texts.addView(statusLine(state, stateColor));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onMenu != null) {
            ImageView more = new ImageView(mActivity);
            more.setImageResource(R.drawable.ic_terminow_more);
            more.setColorFilter(color(R.color.terminow_text_secondary));
            more.setPadding(dp(10), dp(10), dp(10), dp(10));
            more.setContentDescription(mActivity.getString(R.string.terminow_more_options));
            more.setOnClickListener(onMenu::accept);
            row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
        return row;
    }

    @NonNull
    private View statusLine(@NonNull String state, int colorRes) {
        LinearLayout line = new LinearLayout(mActivity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(3), 0, 0);
        View dot = new View(mActivity);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color(colorRes));
        dot.setBackground(shape);
        line.addView(dot, new LinearLayout.LayoutParams(dp(9), dp(9)));
        TextView label = text(state, 13, colorRes);
        label.setPadding(dp(7), 0, 0, 0);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(label);
        return line;
    }

    // ---------------------------------------------------------------------------------------
    // Menus of cards

    private void environmentMenu(@NonNull View anchor, @NonNull String id, @NonNull TerminowLauncher.Environment environment,
                                 @NonNull List<String> pins) {
        PopupMenu menu = new PopupMenu(mActivity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.terminow_quick_rename);
        menu.getMenu().add(0, 2, 0, TerminowQuickModel.isPinned(pins, id) ? R.string.terminow_quick_unpin : R.string.terminow_quick_pin);
        menu.getMenu().add(0, 3, 0, R.string.terminow_quick_set_default);
        menu.getMenu().add(0, 4, 0, R.string.terminow_quick_launch_settings);
        menu.getMenu().add(0, 5, 0, R.string.terminow_quick_new_profile_here);
        menu.getMenu().add(0, 6, 0, R.string.terminow_quick_files);
        menu.getMenu().add(0, 9, 0, R.string.terminow_quick_new_tab);
        TerminowLinuxEnv linux = linuxOf(environment);
        if (linux != null) menu.getMenu().add(0, 7, 0, R.string.terminow_quick_manage);
        if (linux != null) menu.getMenu().add(0, 10, 0, R.string.terminow_phone_backup_menu);
        if (linux != null) menu.getMenu().add(0, 8, 0, R.string.terminow_backup_menu);
        if (linux != null) menu.getMenu().add(0, 11, 0, R.string.terminow_phone_restore_menu);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: rename(id, nameOf(id, environment.isLocal() ? mActivity.getString(R.string.terminow_quick_local_name) : environment.shortName())); break;
                case 2: mStore.setQuickPins(TerminowQuickModel.togglePin(mStore.getQuickPins(), id)); render(); break;
                case 3: setDefault(id); break;
                case 4: showLaunchSettings(environment); break;
                case 5: mLauncher.getProfiles().edit(null, TerminowLaunchPlan.TOOL_SHELL, environment, this::refresh); break;
                case 6:
                    if (environment.container != null) mLauncher.openFilesOf(environment);
                    else mHost.openLocalFiles();
                    break;
                case 7: if (linux != null) mLinux.showDetails(linux); break;
                case 8: if (linux != null) mHost.backupEnvironment(linux.alias, environment.shortName()); break;
                case 9: mLauncher.openNewTerminal(environment); break;
                case 10: if (linux != null) mHost.backupToPhone(linux.alias, environment.shortName()); break;
                case 11: mHost.restoreFromPhone(); break;
                default: break;
            }
            return true;
        });
        menu.show();
    }

    /** Where to back up an environment to: a file on the phone, or a server; or restore one from the phone. */
    private void chooseBackup(@NonNull String alias, @NonNull String title) {
        CharSequence[] places = {mActivity.getString(R.string.terminow_phone_backup_menu), mActivity.getString(R.string.terminow_backup_menu),
            mActivity.getString(R.string.terminow_phone_restore_menu)};
        new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_backup_title, title))
            .setItems(places, (d, which) -> {
                if (which == 0) mHost.backupToPhone(alias, title);
                else if (which == 1) mHost.backupEnvironment(alias, title);
                else mHost.restoreFromPhone();
            })
            .show();
    }

    private void profileMenu(@NonNull View anchor, @NonNull String id, @NonNull TerminowLaunchProfile profile, @NonNull List<String> pins) {
        PopupMenu menu = new PopupMenu(mActivity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.terminow_quick_rename);
        menu.getMenu().add(0, 2, 0, TerminowQuickModel.isPinned(pins, id) ? R.string.terminow_quick_unpin : R.string.terminow_quick_pin);
        menu.getMenu().add(0, 3, 0, R.string.terminow_quick_set_default);
        menu.getMenu().add(0, 4, 0, R.string.terminow_quick_edit_profile);
        menu.getMenu().add(0, 5, 0, R.string.terminow_quick_remove_profile);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: rename(id, nameOf(id, profile.name)); break;
                case 2: mStore.setQuickPins(TerminowQuickModel.togglePin(mStore.getQuickPins(), id)); render(); break;
                case 3: setDefault(id); break;
                case 4: mLauncher.getProfiles().edit(profile, profile.tool, null, this::refresh); break;
                case 5:
                    // Only the profile: its environment and files stay as they are
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(mActivity.getString(R.string.terminow_quick_remove_profile_title, profile.name))
                        .setMessage(R.string.terminow_quick_remove_profile_message)
                        .setPositiveButton(R.string.terminow_quick_remove_profile, (d, w) -> {
                            mLauncher.getProfiles().remove(profile);
                            render();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                    break;
                default: break;
            }
            return true;
        });
        menu.show();
    }

    private void rename(@NonNull String id, @NonNull String current) {
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setText(current);
        field.setSelection(field.length());
        field.setTextColor(color(R.color.terminow_text));
        LinearLayout box = new LinearLayout(mActivity);
        box.setPadding(dp(22), dp(6), dp(22), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_quick_rename)
            .setView(box)
            .setPositiveButton(R.string.terminow_profiles_save, (d, w) -> {
                mStore.setQuickName(id, field.getText().toString());
                render();
            })
            .setNeutralButton(R.string.terminow_quick_rename_reset, (d, w) -> {
                mStore.setQuickName(id, "");
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void setDefault(@NonNull String id) {
        mStore.setDefaultLaunchProfile(TerminowQuickModel.defaultOfId(id));
        mActivity.showToast(mActivity.getString(R.string.terminow_quick_default_set), false);
        render();
    }

    /** The user, folder and tmux of Open terminal of an environment. */
    private void showLaunchSettings(@NonNull TerminowLauncher.Environment environment) {
        String[] user = {mLauncher.userOf(environment)};
        String[] folder = {mLauncher.folderOf(environment)};
        boolean[] tmux = {TerminowLaunchPlan.TMUX_ATTACH.equals(mLauncher.tmuxPreferenceOf(environment))};
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);
        TextView userView = choice();
        TextView folderView = choice();
        android.widget.CheckBox tmuxView = new android.widget.CheckBox(mActivity);
        tmuxView.setText(mActivity.getString(R.string.terminow_quick_settings_tmux, mLauncher.quickTmuxName(environment)));
        tmuxView.setTextColor(color(R.color.terminow_text));
        tmuxView.setChecked(tmux[0]);
        tmuxView.setOnCheckedChangeListener((b, checked) -> tmux[0] = checked);
        Runnable update = () -> {
            userView.setText(mActivity.getString(R.string.terminow_tools_user, user[0]) + "  ▾");
            folderView.setText(folder[0] + "  ▾");
        };
        update.run();
        if (environment.container != null) {
            box.addView(label(R.string.terminow_launcher_user));
            box.addView(userView, matchWrap());
            userView.setOnClickListener(v -> {
                List<String> users = mLauncher.usersOf(environment);
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setTitle(R.string.terminow_launcher_user)
                    .setItems(users.toArray(new CharSequence[0]), (d, which) -> {
                        user[0] = users.get(which);
                        update.run();
                    })
                    .show();
            });
        }
        box.addView(label(R.string.terminow_profiles_folder));
        box.addView(folderView, matchWrap());
        folderView.setOnClickListener(v -> mLauncher.pickFolder(environment, folder[0], path -> {
            folder[0] = path;
            update.run();
        }));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(12);
        box.addView(tmuxView, params);
        TextView note = text(mActivity.getString(R.string.terminow_quick_settings_note), 12, R.color.terminow_text_secondary);
        note.setPadding(0, dp(6), 0, 0);
        box.addView(note);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_quick_launch_settings)
            .setView(box)
            .setPositiveButton(R.string.terminow_profiles_save, (d, w) -> {
                mLauncher.setLaunchSettings(environment, user[0], folder[0],
                    tmux[0] ? TerminowLaunchPlan.TMUX_ATTACH : TerminowLaunchPlan.TMUX_OFF);
                render();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Add environment

    /** A bottom sheet on a phone, a dialog of its own size on a wider screen. */
    public void showAddEnvironment() {
        Dialog dialog = sheet();
        LinearLayout content = sheetContent(dialog, mActivity.getString(R.string.terminow_quick_add_environment));
        content.addView(optionRow(R.drawable.ic_terminow_import, mActivity.getString(R.string.terminow_quick_option_install),
            mActivity.getString(R.string.terminow_quick_option_install_summary), () -> {
                dialog.dismiss();
                showDistributions();
            }));
        content.addView(optionRow(R.drawable.ic_terminow_archive, mActivity.getString(R.string.terminow_quick_option_import),
            mActivity.getString(R.string.terminow_quick_option_import_summary), () -> {
                dialog.dismiss();
                showImportInfo();
            }));
        content.addView(optionRow(R.drawable.ic_terminow_download, mActivity.getString(R.string.terminow_phone_restore_menu),
            mActivity.getString(R.string.terminow_phone_restore_summary), () -> {
                dialog.dismiss();
                mHost.restoreFromPhone();
            }));
        content.addView(optionRow(R.drawable.ic_terminow_snippets, mActivity.getString(R.string.terminow_quick_option_custom),
            mActivity.getString(R.string.terminow_quick_option_custom_summary), () -> {
                dialog.dismiss();
                mLauncher.getProfiles().edit(null, TerminowLaunchPlan.TOOL_PROGRAM, null, this::refresh);
            }));
        dialog.show();
    }

    /** The distributions that the installer can really install here; the others say why not. */
    private void showDistributions() {
        Dialog dialog = sheet();
        LinearLayout content = sheetContent(dialog, mActivity.getString(R.string.terminow_quick_option_install));
        String arch = TerminowLinuxUi.deviceArch();
        List<TerminowLinuxEnv> installed = mLinux.getRegistry().list();
        TerminowLinuxUi.Progress progress = TerminowLinuxUi.progress();
        for (TerminowLinuxCatalog.Entry entry : TerminowLinuxCatalog.ENTRIES) {
            TerminowLinuxCatalog.Support support = entry.supportOn(arch);
            boolean present = false;
            for (TerminowLinuxEnv env : installed) present |= env.distroId.equals(entry.distroId) && env.isInstalled();
            boolean installing = progress != null && entry.distroId.equals(progress.distroId);
            String state;
            boolean can = false;
            if (installing) state = progress.phase;
            else if (present) state = mActivity.getString(R.string.terminow_quick_distro_installed);
            else if (support == TerminowLinuxCatalog.Support.AVAILABLE) {
                state = mActivity.getString(R.string.terminow_quick_distro_download,
                    android.text.format.Formatter.formatShortFileSize(mActivity, entry.downloadBytes));
                can = !TerminowLinuxUi.isBusy();
            } else if (support == TerminowLinuxCatalog.Support.UNSUPPORTED_ARCH) {
                state = mActivity.getString(R.string.terminow_linux_compat_arch, arch);
            } else {
                state = mActivity.getString(R.string.terminow_quick_distro_not_yet);
            }
            boolean enabled = can;
            View row = optionRow(iconOf(entry.distroId), entry.name + " " + entry.version, state, enabled ? () -> {
                dialog.dismiss();
                mLinux.installEntry(entry);
            } : null);
            row.setAlpha(enabled || present || installing ? 1f : 0.5f);
            content.addView(row);
        }
        TextView note = text(mActivity.getString(R.string.terminow_quick_distro_note), 12, R.color.terminow_text_secondary);
        note.setPadding(dp(4), dp(10), dp(4), 0);
        content.addView(note);
        dialog.show();
    }

    private void showImportInfo() {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_quick_import_title)
            .setMessage(mActivity.getString(R.string.terminow_quick_import_info, TerminowLinuxUi.deviceArch()))
            .setPositiveButton(R.string.terminow_quick_import_choose, (d, w) -> {
                if (TerminowLinuxUi.isBusy()) mActivity.showToast(mActivity.getString(R.string.terminow_linux_install_busy), true);
                else mHost.pickRootfs();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Recent sessions

    /**
     * What can be resumed now: the tabs of TermiNow, and the tmux sessions that the last list of
     * each environment found. Each comes from what runs, never from a saved history alone.
     */
    @NonNull
    private List<TerminowQuickModel.Recent> recentSessions() {
        List<TerminowQuickModel.Recent> tabs = new ArrayList<>();
        List<String> tabTmux = new ArrayList<>();
        for (TerminalSession session : mHost.sessions()) {
            TerminowLauncher.SessionInfo info = mLauncher.getSessionInfo(session);
            String environmentKey = info != null && info.environmentKey != null ? info.environmentKey : "";
            String label = info != null ? info.environmentLabel : mActivity.getString(R.string.terminow_quick_local_name);
            if (info != null && !info.tmux.isEmpty()) tabTmux.add(environmentKey + "|" + info.tmux);
            Long used;
            synchronized (sLastUsed) {
                used = sLastUsed.get(session.mHandle);
            }
            String name = session.mSessionName != null && !session.mSessionName.isEmpty() ? session.mSessionName : label;
            boolean ended = session.getPid() > 0 && !session.isRunning();
            tabs.add(new TerminowQuickModel.Recent("tab:" + session.mHandle, name, environmentKey, label,
                ended ? TerminowQuickModel.State.ENDED : TerminowQuickModel.State.OPEN, used != null ? used : 0, ""));
        }
        List<TerminowQuickModel.Recent> tmux = new ArrayList<>();
        for (TerminowLauncher.Environment environment : mLauncher.environments()) {
            TerminowTmux.State state = mLauncher.tmuxOf(environment);
            if (state == null || state.error != null) continue;
            String label = environment.isLocal() ? mActivity.getString(R.string.terminow_quick_local_name) : environment.shortName();
            for (TerminowTmux.Session session : state.sessions)
                tmux.add(new TerminowQuickModel.Recent("tmux:" + environment.key + "|" + mLauncher.userOf(environment) + "|" + session.name,
                    session.name, environment.key, label, session.attached > 0 ? TerminowQuickModel.State.ATTACHED : TerminowQuickModel.State.DETACHED,
                    session.created * 1000, session.name));
        }
        // Remote desktops and WinRM consoles: the ones that run are resumed, the ones that ended connect again
        for (com.termux.app.terminow.rdp.TerminowRdpSession rdp : com.termux.app.terminow.rdp.TerminowRdpSession.open())
            tabs.add(new TerminowQuickModel.Recent("rdp:" + rdp.id, rdp.target.title, "@rdp", mActivity.getString(R.string.terminow_quick_rdp),
                TerminowQuickModel.State.OPEN, rdp.getLastUsed(), ""));
        for (com.termux.app.terminow.rdp.TerminowRdpSession rdp : com.termux.app.terminow.rdp.TerminowRdpSession.ended())
            tabs.add(new TerminowQuickModel.Recent("rdphost:" + rdp.target.hostKey, rdp.target.title, "@rdp",
                mActivity.getString(R.string.terminow_quick_rdp), TerminowQuickModel.State.SAVED, rdp.getLastUsed(), ""));
        for (com.termux.app.terminow.winrm.TerminowWinrmSession winrm : com.termux.app.terminow.winrm.TerminowWinrmSession.open())
            tabs.add(new TerminowQuickModel.Recent("winrm:" + winrm.id, winrm.target.title, "@winrm", mActivity.getString(R.string.terminow_quick_winrm),
                TerminowQuickModel.State.OPEN, winrm.getLastUsed(), ""));
        for (com.termux.app.terminow.winrm.TerminowWinrmSession winrm : com.termux.app.terminow.winrm.TerminowWinrmSession.ended())
            tabs.add(new TerminowQuickModel.Recent("winrmhost:" + winrm.target.hostKey, winrm.target.title, "@winrm",
                mActivity.getString(R.string.terminow_quick_winrm), TerminowQuickModel.State.SAVED, winrm.getLastUsed(), ""));
        return TerminowQuickModel.merge(tabs, tabTmux, tmux);
    }

    @NonNull
    private View sessionRow(@NonNull TerminowQuickModel.Recent session) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(4), dp(8));
        row.setMinimumHeight(dp(64));
        row.setBackground(rounded(color(R.color.terminow_quick_card), 12, color(R.color.terminow_quick_border)));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconForEnvironment(session.environmentKey));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView title = text(session.environmentLabel + "  ·  " + session.name, 15, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        String detail = stateText(session.state) + (session.lastUsed > 0 ? "  ·  " + mActivity.getString(R.string.terminow_quick_last_active,
            DateUtils.getRelativeTimeSpanString(session.lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)) : "");
        texts.addView(statusLine(detail, stateColor(session.state)));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView resume = text(mActivity.getString(session.state == TerminowQuickModel.State.SAVED ? R.string.terminow_quick_reconnect :
            R.string.terminow_quick_resume), 14, R.color.terminow_text);
        resume.setTypeface(Typeface.DEFAULT_BOLD);
        resume.setGravity(Gravity.CENTER);
        resume.setPadding(dp(16), 0, dp(16), 0);
        resume.setBackground(rounded(color(R.color.terminow_quick_primary), 10, 0));
        resume.setOnClickListener(v -> resume(session));
        row.addView(resume, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        ImageView more = new ImageView(mActivity);
        more.setImageResource(R.drawable.ic_terminow_more);
        more.setColorFilter(color(R.color.terminow_text_secondary));
        more.setPadding(dp(10), dp(10), dp(10), dp(10));
        more.setContentDescription(mActivity.getString(R.string.terminow_more_options));
        more.setOnClickListener(v -> sessionMenu(v, session));
        row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        row.setLayoutParams(params);
        return row;
    }

    private int iconForEnvironment(@NonNull String environmentKey) {
        if (environmentKey.isEmpty()) return R.drawable.ic_terminow_os_linux;
        if (environmentKey.equals("@rdp")) return R.drawable.ic_terminow_os_windows;
        if (environmentKey.equals("@winrm")) return R.drawable.ic_terminow_os_powershell;
        if (TerminowMcpTargets.TARGET_TERMINOW.equals(environmentKey)) return R.drawable.ic_terminow_os_local;
        TerminowLinuxEnv env = mLinux.getRegistry().get(environmentKey);
        return iconOf(env != null ? env.distroId : "");
    }

    @NonNull
    private String stateText(@NonNull TerminowQuickModel.State state) {
        switch (state) {
            case OPEN: return mActivity.getString(R.string.terminow_quick_state_open);
            case ENDED: return mActivity.getString(R.string.terminow_quick_state_ended);
            case ATTACHED: return mActivity.getString(R.string.terminow_quick_state_attached);
            case SAVED: return mActivity.getString(R.string.terminow_quick_state_saved);
            default: return mActivity.getString(R.string.terminow_quick_state_detached);
        }
    }

    private int stateColor(@NonNull TerminowQuickModel.State state) {
        switch (state) {
            case OPEN:
            case ATTACHED: return R.color.terminow_quick_ready;
            case ENDED: return R.color.terminow_mcp_warn;
            default: return R.color.terminow_quick_idle;
        }
    }

    @Nullable
    private TerminalSession tabOf(@NonNull TerminowQuickModel.Recent session) {
        if (!session.key.startsWith("tab:")) return null;
        String handle = session.key.substring("tab:".length());
        for (TerminalSession tab : mHost.sessions()) if (tab.mHandle.equals(handle)) return tab;
        return null;
    }

    /** Resume what is still there, checked now; else say so, and offer a new session in its environment. */
    private void resume(@NonNull TerminowQuickModel.Recent session) {
        if (session.key.startsWith("rdp:") || session.key.startsWith("winrm:")) {
            // A connection that runs: shown again as it is
            String id = session.key.substring(session.key.indexOf(':') + 1);
            com.termux.app.terminow.rdp.TerminowRdpSession rdp = com.termux.app.terminow.rdp.TerminowRdpSession.get(id);
            com.termux.app.terminow.winrm.TerminowWinrmSession winrm = com.termux.app.terminow.winrm.TerminowWinrmSession.get(id);
            if (rdp != null && rdp.isOpen()) com.termux.app.terminow.rdp.TerminowRdpActivity.show(mActivity, rdp);
            else if (winrm != null && winrm.isOpen()) com.termux.app.terminow.winrm.TerminowWinrmActivity.show(mActivity, winrm);
            else offerNew(session, null, R.string.terminow_quick_gone_connection);
            return;
        }
        if (session.key.startsWith("rdphost:") || session.key.startsWith("winrmhost:")) {
            // It ended: a new connection to the saved host, not the old one resumed
            mHost.reconnectHost(session.key.substring(session.key.indexOf(':') + 1));
            return;
        }
        // A tab of a remote host has no environment to open a new session in
        TerminowLauncher.Environment environment = session.environmentKey.isEmpty() ? null : mLauncher.environmentFor(session.environmentKey);
        if (session.key.startsWith("tab:")) {
            TerminalSession tab = tabOf(session);
            if (tab == null) {
                offerNew(session, environment, R.string.terminow_quick_gone_tab);
                return;
            }
            if (tab.getPid() > 0 && !tab.isRunning()) {
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                    .setTitle(session.name)
                    .setMessage(R.string.terminow_quick_ended_message)
                    .setPositiveButton(R.string.terminow_quick_show_tab, (d, w) -> mHost.showSession(tab))
                    .setNeutralButton(R.string.terminow_quick_new_session, environment != null ? (d, w) -> mLauncher.openNewTerminal(environment) : null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return;
            }
            mHost.showSession(tab);
            return;
        }
        if (environment == null) {
            offerNew(session, null, R.string.terminow_quick_gone_environment);
            return;
        }
        mLauncher.resumeTmux(environment, session.tmuxName, () -> offerNew(session, environment, R.string.terminow_quick_gone_tmux));
    }

    private void offerNew(@NonNull TerminowQuickModel.Recent session, @Nullable TerminowLauncher.Environment environment, int messageRes) {
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(session.name)
            .setMessage(mActivity.getString(messageRes, session.environmentLabel))
            .setNegativeButton(android.R.string.cancel, null);
        if (environment != null)
            builder.setPositiveButton(R.string.terminow_quick_new_session, (d, w) -> mLauncher.openNewTerminal(environment));
        builder.show();
        render();
    }

    private void sessionMenu(@NonNull View anchor, @NonNull TerminowQuickModel.Recent session) {
        PopupMenu menu = new PopupMenu(mActivity, anchor);
        TerminowLauncher.Environment environment = session.environmentKey.isEmpty() ? null : mLauncher.environmentFor(session.environmentKey);
        if (session.key.startsWith("rdp:") || session.key.startsWith("winrm:")) {
            menu.getMenu().add(0, 7, 0, R.string.terminow_rdp_disconnect);
        } else if (session.key.startsWith("rdphost:") || session.key.startsWith("winrmhost:")) {
            menu.getMenu().add(0, 8, 0, R.string.terminow_quick_reconnect);
        } else if (session.key.startsWith("tab:")) {
            menu.getMenu().add(0, 1, 0, R.string.terminow_quick_rename_tab);
            menu.getMenu().add(0, 2, 0, R.string.terminow_quick_close_tab);
        } else {
            menu.getMenu().add(0, 3, 0, R.string.terminow_tools_tmux_rename);
            if (session.state == TerminowQuickModel.State.ATTACHED) menu.getMenu().add(0, 4, 0, R.string.terminow_quick_detach);
            menu.getMenu().add(0, 5, 0, R.string.terminow_quick_end_tmux);
        }
        if (environment != null) menu.getMenu().add(0, 6, 0, R.string.terminow_quick_new_session);
        menu.setOnMenuItemClickListener(item -> {
            TerminalSession tab = tabOf(session);
            switch (item.getItemId()) {
                case 1: if (tab != null) mHost.renameSession(tab); break;
                case 2: if (tab != null) mHost.closeSession(tab); break;
                case 3: if (environment != null) mLauncher.renameTmuxSession(environment, session.tmuxName); break;
                case 4: if (environment != null) mLauncher.tmuxSessionOperation(environment, "detach", session.tmuxName); break;
                case 5:
                    if (environment == null) break;
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setTitle(mActivity.getString(R.string.terminow_quick_end_tmux_title, session.name))
                        .setMessage(R.string.terminow_quick_end_tmux_message)
                        .setPositiveButton(R.string.terminow_quick_end_tmux, (d, w) -> mLauncher.tmuxSessionOperation(environment, "kill", session.tmuxName))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                    break;
                case 6: if (environment != null) mLauncher.openNewTerminal(environment); break;
                case 7: {
                    String id = session.key.substring(session.key.indexOf(':') + 1);
                    com.termux.app.terminow.rdp.TerminowRdpSession rdp = com.termux.app.terminow.rdp.TerminowRdpSession.get(id);
                    com.termux.app.terminow.winrm.TerminowWinrmSession winrm = com.termux.app.terminow.winrm.TerminowWinrmSession.get(id);
                    if (rdp != null) rdp.disconnect();
                    if (winrm != null) winrm.disconnect();
                    mHandler.postDelayed(this::render, 300);
                    break;
                }
                case 8: mHost.reconnectHost(session.key.substring(session.key.indexOf(':') + 1)); break;
                default: break;
            }
            return true;
        });
        menu.show();
    }

    private void showAllSessions(@NonNull List<TerminowQuickModel.Recent> sessions) {
        Dialog dialog = sheet();
        LinearLayout content = sheetContent(dialog, mActivity.getString(R.string.terminow_quick_recent));
        for (TerminowQuickModel.Recent session : sessions) {
            View row = sessionRow(session);
            // Leaving the list for what was chosen
            View resume = ((ViewGroup) row).getChildAt(2);
            resume.setOnClickListener(v -> {
                dialog.dismiss();
                resume(session);
            });
            content.addView(row);
        }
        dialog.show();
    }

    // ---------------------------------------------------------------------------------------
    // Views

    @NonNull
    private View grid(@NonNull List<View> items, int columns) {
        LinearLayout grid = new LinearLayout(mActivity);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(mActivity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = matchWrap();
                rowParams.topMargin = dp(12);
                grid.addView(row, rowParams);
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i % columns != 0) params.setMarginStart(dp(12));
            row.addView(items.get(i), params);
        }
        // The last row keeps the width of a column for each card
        if (row != null) {
            for (int i = items.size() % columns; i != 0 && i < columns; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 1, 1);
                params.setMarginStart(dp(12));
                row.addView(new View(mActivity), params);
            }
        }
        return grid;
    }

    @NonNull
    private LinearLayout card() {
        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(10), dp(16));
        card.setBackground(rounded(color(R.color.terminow_quick_card), 16, color(R.color.terminow_quick_border)));
        return card;
    }

    @NonNull
    private TextView primaryButton(@NonNull String label, @NonNull Runnable onClick) {
        TextView button = text("›_  " + label, 15, R.color.terminow_text);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setBackground(rounded(color(R.color.terminow_quick_primary), 10, 0));
        button.setOnClickListener(v -> onClick.run());
        button.setMinHeight(dp(48));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        params.topMargin = dp(16);
        params.setMarginEnd(dp(6));
        button.setLayoutParams(params);
        return button;
    }

    @NonNull
    private TextView secondaryButton(int iconRes, @NonNull String label, @NonNull Runnable onClick) {
        TextView button = text(label, 14, R.color.terminow_text);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setMinHeight(dp(44));
        button.setPadding(dp(10), 0, dp(10), 0);
        if (iconRes != 0) {
            button.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
            button.setCompoundDrawablePadding(dp(8));
            button.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(color(R.color.terminow_text)));
        }
        button.setBackground(rounded(Color.TRANSPARENT, 10, color(R.color.terminow_quick_border)));
        button.setOnClickListener(v -> onClick.run());
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(44), 1);
        params.setMarginEnd(dp(6));
        return params;
    }

    @NonNull
    private View tile(int iconRes, @NonNull String title, @NonNull String summary, boolean dashed, @NonNull Runnable onClick) {
        LinearLayout tile = new LinearLayout(mActivity);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(14), dp(12), dp(10), dp(12));
        tile.setMinimumHeight(dp(68));
        tile.setBackground(dashed ? dashed() : rounded(color(R.color.terminow_quick_card), 12, color(R.color.terminow_quick_border)));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(dashed ? R.color.terminow_text : R.color.terminow_bar_home));
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        if (!dashed) icon.setBackground(rounded(color(R.color.terminow_accent), 8, 0));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(12), 0, 0, 0);
        TextView titleView = text(title, 15, R.color.terminow_text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(titleView);
        TextView summaryView = text(summary, 12, R.color.terminow_text_secondary);
        summaryView.setMaxLines(2);
        summaryView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(summaryView);
        tile.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tile.setOnClickListener(v -> onClick.run());
        return tile;
    }

    @NonNull
    private View sectionTitle(@NonNull String title, @Nullable String action, @Nullable Runnable onAction) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = text(title, 20, R.color.terminow_text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (action != null && onAction != null) {
            TextView link = text(action, 14, R.color.terminow_quick_primary);
            link.setTypeface(Typeface.DEFAULT_BOLD);
            link.setGravity(Gravity.CENTER_VERTICAL);
            link.setMinHeight(dp(44));
            link.setPadding(dp(12), 0, dp(4), 0);
            link.setOnClickListener(v -> onAction.run());
            row.addView(link);
        }
        return row;
    }

    @NonNull
    private TextView sectionSubtitle(@NonNull String text) {
        TextView view = text(text, 13, R.color.terminow_text_secondary);
        view.setPadding(0, dp(2), 0, 0);
        return view;
    }

    @NonNull
    private View divider() {
        View line = new View(mActivity);
        line.setBackgroundColor(color(R.color.terminow_quick_border));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.topMargin = dp(22);
        params.bottomMargin = dp(18);
        line.setLayoutParams(params);
        return line;
    }

    @NonNull
    private View optionRow(int iconRes, @NonNull String title, @NonNull String summary, @Nullable Runnable onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(10), dp(12));
        row.setMinimumHeight(dp(64));
        row.setBackground(rounded(color(R.color.terminow_quick_card), 12, color(R.color.terminow_quick_border)));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        if (iconRes == R.drawable.ic_terminow_import || iconRes == R.drawable.ic_terminow_archive || iconRes == R.drawable.ic_terminow_snippets)
            icon.setColorFilter(color(R.color.terminow_text));
        row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout texts = new LinearLayout(mActivity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, dp(8), 0);
        TextView titleView = text(title, 15, R.color.terminow_text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(titleView);
        texts.addView(text(summary, 13, R.color.terminow_text_secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (onClick != null) {
            TextView chevron = text("›", 22, R.color.terminow_text_secondary);
            row.addView(chevron);
            row.setOnClickListener(v -> onClick.run());
        }
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        row.setLayoutParams(params);
        return row;
    }

    @NonNull
    private Dialog sheet() {
        Dialog dialog = new Dialog(mActivity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return dialog;
    }

    /** The scrolled content of a sheet with its title and close: at the bottom of a phone, in the middle of a wider screen. */
    @NonNull
    private LinearLayout sheetContent(@NonNull Dialog dialog, @NonNull String title) {
        boolean phone = mActivity.getResources().getConfiguration().screenWidthDp < 600;
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
        head.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView close = new ImageView(mActivity);
        close.setImageResource(R.drawable.ic_terminow_close);
        close.setColorFilter(color(R.color.terminow_text));
        close.setPadding(dp(10), dp(10), dp(10), dp(10));
        close.setContentDescription(mActivity.getString(R.string.terminow_profiles_close));
        close.setOnClickListener(v -> dialog.dismiss());
        head.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(head);
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(8));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Above the navigation bar and the taskbar of foldables
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() |
                androidx.core.view.WindowInsetsCompat.Type.tappableElement()).bottom;
            root.setPadding(dp(20), dp(16), dp(20), dp(20) + (phone ? bottom : 0));
            return insets;
        });
        dialog.setContentView(root);
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
        return content;
    }

    @NonNull
    private TextView choice() {
        TextView view = text("", 15, R.color.terminow_text);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setMinHeight(dp(44));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setBackground(rounded(color(R.color.terminow_field), 8, 0));
        return view;
    }

    @NonNull
    private TextView label(int res) {
        TextView view = text(mActivity.getString(res), 12, R.color.terminow_text_secondary);
        view.setPadding(0, dp(12), 0, dp(4));
        return view;
    }

    @NonNull
    private GradientDrawable rounded(int fill, float radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(Math.max(1, dp(1)), stroke);
        return shape;
    }

    @NonNull
    private GradientDrawable dashed() {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.TRANSPARENT);
        shape.setCornerRadius(dp(16));
        shape.setStroke(Math.max(1, dp(1.5f)), color(R.color.terminow_text_secondary), dp(6), dp(5));
        return shape;
    }

    private static int parseColor(@NonNull String value) {
        try {
            return Color.parseColor(value);
        } catch (IllegalArgumentException e) {
            return Color.parseColor(TerminowLaunchProfile.COLORS[0]);
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

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }
}
