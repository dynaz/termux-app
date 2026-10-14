package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.files.TerminowDeviceFs;
import com.termux.app.terminow.files.TerminowFileScan;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowLocalFs;
import com.termux.app.terminow.files.TerminowSafFs;
import com.termux.app.terminow.files.TerminowSftpFs;
import com.termux.app.terminow.files.TerminowTransfer;
import com.termux.app.terminow.files.TerminowTransferQueue;
import com.termux.app.terminow.linux.TerminowLinuxEnv;
import com.termux.app.terminow.linux.TerminowLinuxFs;
import com.termux.app.terminow.linux.TerminowLinuxPaths;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The File Manager page, a Premium feature: TermiNow HOME, folders of shared storage that the
 * user picked with the Storage Access Framework, and saved SFTP hosts.
 *
 * Listing, searching, analyzing and transfers run on worker threads. Nothing outside HOME is
 * browsable on the device: no device "/", no other app's files, no Ubuntu rootfs. Deleting is
 * permanent, there is no recycle bin. Only features that work are shown.
 */
public class TerminowExplorer {

    public static final int REQUEST_ADD_FOLDER = 2030;
    public static final int REQUEST_REPICK_FOLDER = 2031;

    private static final String IMPORTS_DIR = "Imports";

    public interface Host {
        void openTerminalAt(@NonNull File directory);

        void exportFile();

        void showAccountSettings();

        /** The Linux environments of the app, {@code null} before they are set up. */
        @Nullable TerminowLinuxUi getLinux();

        /** A new tab of a Linux environment, as the user, in the Linux folder. */
        void openLinuxTerminal(@NonNull String alias, @NonNull String user, @NonNull String folder);
    }

    private enum Screen { DASHBOARD, BROWSER, CATEGORY, SEARCH, ANALYZER, QUEUE, RECENTS, BOOKMARKS }

    /** The colors of the light or dark theme of the File Manager. */
    private static class Palette {
        boolean dark;
        int background, card, field, text, secondary, accent, selected, divider, danger, warning;
        int dialogTheme;
    }

    /** One browser pane. Large screens can show two, to transfer between them. */
    private static class Pane {
        @Nullable TerminowFs fs;
        final List<TerminowFs.Node> crumbs = new ArrayList<>();
        List<TerminowFs.Node> entries = new ArrayList<>();
        final Set<String> selection = new LinkedHashSet<>();
        boolean loading;
        @Nullable String error;
        String query = "";
        boolean searching;
        int generation;
        @Nullable TerminowFs.Cancel cancel;

        @Nullable
        TerminowFs.Node current() {
            return crumbs.isEmpty() ? null : crumbs.get(crumbs.size() - 1);
        }
    }

    /** Items that were copied or cut, to paste in another folder. */
    private static class Clip {
        final TerminowFs fs;
        final TerminowFs.Node parent;
        final List<TerminowFs.Node> nodes;
        final boolean move;
        final String from;

        Clip(TerminowFs fs, TerminowFs.Node parent, List<TerminowFs.Node> nodes, boolean move, String from) {
            this.fs = fs;
            this.parent = parent;
            this.nodes = nodes;
            this.move = move;
            this.from = from;
        }
    }

    /** A search over the accessible locations, for a category or a name. */
    private static class Scan {
        @Nullable TerminowFileScan.Category category;
        String query = "";
        final List<TerminowFileScan.Match> results = new ArrayList<>();
        boolean running;
        String status = "";
        @Nullable TerminowFs.Cancel cancel;
        @Nullable TerminowFileScan.Filter filter;
        int generation;
    }

    /** The space of one location, for the analyzer. */
    private static class LocationSpace {
        final TerminowFs fs;
        @Nullable TerminowFileScan.Space space;
        @Nullable String error;

        LocationSpace(TerminowFs fs) {
            this.fs = fs;
        }
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final FrameLayout mContainer;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final TerminowAccountUi mAccountUi;
    private final Map<String, TerminowFs> mFileSystems = new HashMap<>();
    private final Pane[] mPanes = {new Pane(), new Pane()};
    private final TerminowTransferQueue mQueue;
    private final Scan mScan = new Scan();
    private final List<LocationSpace> mSpaces = new ArrayList<>();
    private final LruCache<String, Bitmap> mThumbnails = new LruCache<>(80);
    private final Set<String> mThumbnailsLoading = new HashSet<>();
    private final Set<String> mCollapsed = new HashSet<>();
    private Palette mPalette = new Palette();
    private Screen mScreen = Screen.DASHBOARD;
    private int mActivePane;
    private boolean mTwoPanes;
    private boolean mDrawerOpen;
    private boolean mSidebarCollapsed;
    private boolean mAnalyzing;
    private String mAnalyzeStatus = "";
    @Nullable private TerminowFs.Cancel mAnalyzeCancel;
    @Nullable private Clip mClip;
    private int mRepickIndex = -1;
    private boolean mVisible;
    private long mHomeBytes = -1;
    private long mHomeBytesTime;
    private boolean mHomeBytesRunning;
    @Nullable private BaseAdapter mListAdapter;
    private boolean mRenderPosted;
    /** Whether shared storage could be browsed by path the last time it was checked. */
    private boolean mHadStorageAccess;

    public TerminowExplorer(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull FrameLayout container,
                            @NonNull Host host) {
        mActivity = activity;
        mStore = store;
        mContainer = container;
        mHost = host;
        mAccountUi = new TerminowAccountUi(activity, store, () -> {
            if (mVisible) render();
        });
        mQueue = new TerminowTransferQueue(job -> mHandler.post(() -> onJobChanged(job)));
    }

    // ---------------------------------------------------------------------------------------
    // Page life
    // ---------------------------------------------------------------------------------------

    public void show() {
        mVisible = true;
        mHadStorageAccess = TerminowStorageAccess.hasAccess(mActivity);
        TerminowEntitlement.State state = TerminowEntitlement.get(mStore);
        if ((state == TerminowEntitlement.State.NEEDS_CHECK || state == TerminowEntitlement.State.EXPIRED) &&
            !mAccountUi.isCheckingPremium())
            mAccountUi.checkPremiumNow();
        render();
    }

    /** Draw again after something outside changed, like an environment was installed. */
    public void refresh() {
        if (mVisible) render();
    }

    /**
     * Called when the app comes back, for example from the All files access screen of Android.
     * A change of the access is applied at once, also to search results.
     */
    public void onResume() {
        boolean access = TerminowStorageAccess.hasAccess(mActivity);
        if (access == mHadStorageAccess) {
            if (mVisible) render();
            return;
        }
        mHadStorageAccess = access;
        // Folders that were listed before access was given or after it was taken away are stale
        for (Pane pane : mPanes) if (pane.fs instanceof TerminowDeviceFs && mVisible && mScreen == Screen.BROWSER) load(pane);
        if (mVisible && (mScreen == Screen.CATEGORY || mScreen == Screen.SEARCH) && mScan.filter != null) runScan(mScan.filter);
        else if (mVisible) render();
    }

    /** Leaving the page closes the SFTP connections of the File Manager when nothing uses them. */
    public void onHidden() {
        mVisible = false;
        for (Pane pane : mPanes) if (pane.cancel != null) pane.cancel.cancel();
        if (mScan.cancel != null) mScan.cancel.cancel();
        if (mAnalyzeCancel != null) mAnalyzeCancel.cancel();
        // A transfer that is still running keeps its connection
        if (mQueue.getActiveCount() > 0) return;
        for (TerminowFs fs : mFileSystems.values())
            if (fs instanceof TerminowSftpFs) mExecutor.execute(fs::close);
    }

    public boolean onBackPressed() {
        if (!mVisible) return false;
        if (mDrawerOpen) {
            mDrawerOpen = false;
            render();
            return true;
        }
        Pane pane = mPanes[mActivePane];
        if (mScreen == Screen.BROWSER) {
            if (!pane.selection.isEmpty()) {
                pane.selection.clear();
                render();
                return true;
            }
            if (pane.searching) {
                pane.searching = false;
                pane.query = "";
                render();
                return true;
            }
            if (pane.crumbs.size() > 1) {
                pane.crumbs.remove(pane.crumbs.size() - 1);
                load(pane);
                return true;
            }
        }
        if (mScreen == Screen.DASHBOARD) return false;
        if (mScan.cancel != null && (mScreen == Screen.CATEGORY || mScreen == Screen.SEARCH)) mScan.cancel.cancel();
        mScreen = Screen.DASHBOARD;
        render();
        return true;
    }

    private int widthDp() {
        return mActivity.getResources().getConfiguration().screenWidthDp;
    }

    /** A secondary sidebar instead of a drawer, when the screen is wide enough. */
    private boolean isLarge() {
        return widthDp() >= 720;
    }

    private void postRender() {
        if (mRenderPosted) return;
        mRenderPosted = true;
        mHandler.post(() -> {
            mRenderPosted = false;
            render();
        });
    }

    private void render() {
        if (!mVisible) return;
        mPalette = palette();
        mContainer.removeAllViews();
        mListAdapter = null;
        if (!mPalette.dark) mContainer.setBackgroundColor(mPalette.background);
        else mContainer.setBackground(null);

        LinearLayout root = vertical();
        TerminowEntitlement.State state = TerminowEntitlement.get(mStore);
        if (state != TerminowEntitlement.State.ACTIVE) {
            root.addView(toolbar(false), matchWrap());
            root.addView(locked(state), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            mContainer.addView(root, matchMatch());
            return;
        }

        root.addView(toolbar(true), matchWrap());
        View content = content();
        if (isLarge() && !mSidebarCollapsed) {
            LinearLayout row = new LinearLayout(mActivity);
            ScrollView sidebar = new ScrollView(mActivity);
            LinearLayout drawer = vertical();
            drawer.setPadding(0, dp(4), dp(10), dp(24));
            sidebar.addView(drawer);
            drawerContent(drawer);
            row.addView(sidebar, new LinearLayout.LayoutParams(dp(264), ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            root.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        View strip = transferStrip();
        if (strip != null) root.addView(strip, matchWrap());
        mContainer.addView(root, matchMatch());
        if (mDrawerOpen && !isLarge()) addDrawerOverlay();
    }

    @NonNull
    private View content() {
        switch (mScreen) {
            case BROWSER: return browsers();
            case CATEGORY:
            case SEARCH: return scanScreen();
            case ANALYZER: return analyzerScreen();
            case QUEUE: return queueScreen();
            case RECENTS: return recentsScreen();
            case BOOKMARKS: return bookmarksScreen();
            default: return dashboard();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Theme
    // ---------------------------------------------------------------------------------------

    @NonNull
    private Palette palette() {
        String theme = mStore.getExplorer().optString("theme", "system");
        boolean dark = theme.equals("dark") || (theme.equals("system") &&
            (mActivity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        Palette p = new Palette();
        p.dark = dark;
        p.accent = color(R.color.terminow_panel_accent);
        p.danger = color(R.color.terminow_host_ssh);
        p.warning = 0xFFF5A623;
        if (dark) {
            p.background = color(R.color.terminow_bar_home);
            p.card = color(R.color.terminow_surface);
            p.field = color(R.color.terminow_field);
            p.text = color(R.color.terminow_text);
            p.secondary = color(R.color.terminow_text_secondary);
            p.selected = color(R.color.terminow_rail_selected);
            p.divider = 0x22FFFFFF;
            p.dialogTheme = R.style.TerminowAlertDialog;
        } else {
            p.background = 0xFFF1F3F7;
            p.card = 0xFFFFFFFF;
            p.field = 0xFFE6E9F0;
            p.text = 0xFF1B1E2B;
            p.secondary = 0xFF667085;
            p.selected = 0xFFCDEFE0;
            p.divider = 0x14000000;
            p.dialogTheme = android.R.style.Theme_Material_Light_Dialog_Alert;
        }
        return p;
    }

    @NonNull
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(mActivity, mPalette.dialogTheme);
    }

    // ---------------------------------------------------------------------------------------
    // Toolbar, drawer and transfer strip
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View toolbar(boolean premium) {
        LinearLayout bar = new LinearLayout(mActivity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(8), 0, dp(4));
        if (premium) {
            ImageView menu = iconButton(R.drawable.ic_terminow_menu, R.string.terminow_explorer_drawer);
            menu.setOnClickListener(v -> {
                if (isLarge()) mSidebarCollapsed = !mSidebarCollapsed;
                else mDrawerOpen = true;
                render();
            });
            bar.addView(menu);
            if (mScreen != Screen.DASHBOARD) {
                ImageView back = iconButton(R.drawable.ic_terminow_back, R.string.terminow_explorer_up);
                back.setOnClickListener(v -> onBackPressed());
                bar.addView(back);
            }
        }
        LinearLayout titles = vertical();
        titles.setPadding(dp(premium ? 4 : 8), 0, 0, 0);
        TextView title = text(screenTitle(), 19, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title, matchWrap());
        String subtitle = screenSubtitle();
        if (subtitle != null) {
            TextView sub = text(subtitle, 12, mPalette.secondary);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.START);
            titles.addView(sub, matchWrap());
        }
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView crown = new ImageView(mActivity);
        crown.setImageResource(R.drawable.ic_terminow_crown);
        crown.setContentDescription(mActivity.getString(R.string.terminow_account_premium));
        crown.setPadding(dp(12), dp(12), dp(12), dp(12));
        bar.addView(crown, new LinearLayout.LayoutParams(dp(48), dp(48)));
        if (!premium) return bar;

        ImageView search = iconButton(R.drawable.ic_terminow_search, R.string.terminow_explorer_search);
        search.setOnClickListener(v -> {
            if (mScreen == Screen.BROWSER) {
                Pane pane = mPanes[mActivePane];
                pane.searching = !pane.searching;
                if (!pane.searching) pane.query = "";
                render();
            } else {
                startNameSearch();
            }
        });
        bar.addView(search);
        ImageView more = iconButton(R.drawable.ic_terminow_more, R.string.terminow_explorer_more);
        more.setOnClickListener(this::showOverflow);
        bar.addView(more);
        return bar;
    }

    @NonNull
    private String screenTitle() {
        switch (mScreen) {
            case BROWSER: {
                Pane pane = mPanes[mActivePane];
                TerminowFs.Node current = pane.current();
                if (pane.fs == null || current == null) return mActivity.getString(R.string.terminow_explorer_title);
                return pane.crumbs.size() > 1 ? current.name : pane.fs.getTitle();
            }
            case CATEGORY: return categoryLabel(mScan.category);
            case SEARCH: return mActivity.getString(R.string.terminow_explorer_search);
            case ANALYZER: return mActivity.getString(R.string.terminow_explorer_analyzer);
            case QUEUE: return mActivity.getString(R.string.terminow_explorer_queue);
            case RECENTS: return mActivity.getString(R.string.terminow_explorer_recent);
            case BOOKMARKS: return mActivity.getString(R.string.terminow_explorer_bookmarks);
            default: return mActivity.getString(R.string.terminow_explorer_title);
        }
    }

    @Nullable
    private String screenSubtitle() {
        if (mScreen == Screen.BROWSER) {
            Pane pane = mPanes[mActivePane];
            return pane.fs != null ? pane.fs.getDisplayPath(pane.crumbs) : null;
        }
        if (mScreen == Screen.CATEGORY || mScreen == Screen.SEARCH || mScreen == Screen.ANALYZER)
            return mActivity.getString(R.string.terminow_explorer_scope, scopeLabel());
        return null;
    }

    private void showOverflow(@NonNull View anchor) {
        PopupMenu menu = new PopupMenu(mActivity, anchor);
        List<Runnable> actions = new ArrayList<>();
        Pane pane = mPanes[mActivePane];
        if (mScreen == Screen.BROWSER && pane.fs != null) {
            addMenu(menu, actions, R.string.terminow_explorer_new, () -> createNew(pane));
            addMenu(menu, actions, R.string.terminow_explorer_refresh, () -> load(pane));
            addMenu(menu, actions, R.string.terminow_explorer_view, () -> showViewPanel(pane, anchor));
            if (widthDp() >= 600)
                addMenu(menu, actions, mTwoPanes ? R.string.terminow_explorer_one_pane : R.string.terminow_explorer_two_panes, this::toggleTwoPanes);
            addMenu(menu, actions, R.string.terminow_explorer_analyze, this::startAnalyzer);
            addMenu(menu, actions, R.string.terminow_explorer_history, () -> go(Screen.RECENTS));
            addMenu(menu, actions, R.string.terminow_explorer_bookmark, () -> bookmark(pane));
            addMenu(menu, actions, R.string.terminow_explorer_open_terminal, () -> openInTerminal(pane.fs, pane.current()));
            if (!(pane.fs instanceof TerminowLocalFs))
                addMenu(menu, actions, R.string.terminow_explorer_folder_to_home, () -> copyToHome(pane.fs,
                    pane.crumbs.size() > 1 ? pane.crumbs.get(pane.crumbs.size() - 2) : pane.current(), Collections.singletonList(pane.current())));
            addMenu(menu, actions, R.string.terminow_explorer_properties, () -> showProperties(pane.fs, pane.current(), pane.fs.getDisplayPath(pane.crumbs)));
            if (pane.fs instanceof TerminowSftpFs) addMenu(menu, actions, R.string.terminow_explorer_disconnect, () -> disconnect(pane.fs));
        } else {
            addMenu(menu, actions, R.string.terminow_explorer_refresh, () -> {
                mHomeBytes = -1;
                render();
            });
        }
        addMenu(menu, actions, R.string.terminow_explorer_queue, () -> go(Screen.QUEUE));
        if (mScreen != Screen.BROWSER)
            addMenu(menu, actions, R.string.terminow_explorer_view, () -> showViewPanel(null, anchor));
        addMenu(menu, actions, R.string.terminow_explorer_theme, this::showThemeDialog);
        if (mScreen != Screen.DASHBOARD) addMenu(menu, actions, R.string.terminow_explorer_dashboard, () -> go(Screen.DASHBOARD));
        menu.setOnMenuItemClickListener(item -> {
            actions.get(item.getItemId()).run();
            return true;
        });
        menu.show();
    }

    private void addMenu(@NonNull PopupMenu menu, @NonNull List<Runnable> actions, int titleRes, @NonNull Runnable action) {
        menu.getMenu().add(0, actions.size(), actions.size(), titleRes);
        actions.add(action);
    }

    private void go(@NonNull Screen screen) {
        mScreen = screen;
        mDrawerOpen = false;
        render();
    }

    private void addDrawerOverlay() {
        FrameLayout scrim = new FrameLayout(mActivity);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> {
            mDrawerOpen = false;
            render();
        });
        ScrollView panel = new ScrollView(mActivity);
        panel.setBackgroundColor(mPalette.background);
        panel.setClickable(true);
        LinearLayout drawer = vertical();
        drawer.setPadding(dp(10), dp(12), dp(10), dp(24));
        panel.addView(drawer);
        drawerContent(drawer);
        int width = mContainer.getWidth() > 0 ? Math.min(dp(300), (int) (mContainer.getWidth() * 0.86f)) : dp(300);
        scrim.addView(panel, new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT));
        mContainer.addView(scrim, matchMatch());
    }

    /** The sections of the File Manager drawer, the same in the overlay and the sidebar. */
    private void drawerContent(@NonNull LinearLayout drawer) {
        LinearLayout header = new LinearLayout(mActivity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(6), 0, dp(10));
        ImageView crown = new ImageView(mActivity);
        crown.setImageResource(R.drawable.ic_terminow_crown);
        header.addView(crown, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout titles = vertical();
        titles.setPadding(dp(10), 0, 0, 0);
        TextView name = text(mActivity.getString(R.string.terminow_explorer_title), 16, mPalette.text);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(name);
        titles.addView(text(mActivity.getString(R.string.terminow_explorer_premium_active), 12, mPalette.accent));
        header.addView(titles);
        drawer.addView(header, matchWrap());

        if (drawerSection(drawer, "storage", R.string.terminow_explorer_section_storage, R.drawable.ic_terminow_folder)) {
            for (TerminowStorageAccess.Volume volume : TerminowStorageAccess.getVolumes(mActivity))
                drawerItem(drawer, volume.removable ? R.drawable.ic_terminow_sdcard : R.drawable.ic_terminow_phone, volume.title,
                    volumeStatus(volume), () -> openVolume(volume));
            drawerItem(drawer, R.drawable.ic_terminow_folder, mActivity.getString(R.string.terminow_explorer_home), null, () -> openHome(null));
            JSONArray folders = mStore.getExplorer().optJSONArray("folders");
            for (int i = 0; folders != null && i < folders.length(); i++) {
                JSONObject folder = folders.optJSONObject(i);
                if (folder == null) continue;
                int index = i;
                Uri uri = Uri.parse(folder.optString("uri"));
                boolean access = new TerminowSafFs(mActivity, uri, folder.optString("name")).hasAccess();
                drawerItem(drawer, R.drawable.ic_terminow_folder, folder.optString("name"),
                    access ? null : mActivity.getString(R.string.terminow_explorer_access_lost_short), () -> {
                        if (access) openSaf(uri, folder.optString("name"), null);
                        else repickFolder(index);
                    });
            }
            drawerItem(drawer, R.drawable.ic_terminow_add, mActivity.getString(R.string.terminow_explorer_add_folder), null, this::addFolder);
        }
        if (drawerSection(drawer, "categories", R.string.terminow_explorer_section_categories, R.drawable.ic_terminow_image)) {
            for (TerminowFileScan.Category category : TerminowFileScan.Category.values())
                drawerItem(drawer, categoryIcon(category), categoryLabel(category), null, () -> startCategory(category));
        }
        if (drawerSection(drawer, "sftp", R.string.terminow_explorer_section_sftp, R.drawable.ic_terminow_hosts)) {
            List<TerminowStore.Host> hosts = mStore.getHosts();
            if (hosts.isEmpty()) drawerNote(drawer, R.string.terminow_explorer_no_hosts);
            for (TerminowStore.Host host : hosts)
                drawerItem(drawer, R.drawable.ic_terminow_hosts, host.getTitle(), isConnected(host) ?
                    mActivity.getString(R.string.terminow_explorer_connected) : null, () -> openSftp(host, null));
        }
        if (drawerSection(drawer, "bookmarks", R.string.terminow_explorer_bookmarks, R.drawable.ic_terminow_bookmark)) {
            JSONArray bookmarks = mStore.getExplorer().optJSONArray("bookmarks");
            if (bookmarks == null || bookmarks.length() == 0) drawerNote(drawer, R.string.terminow_explorer_no_bookmarks);
            for (int i = 0; bookmarks != null && i < bookmarks.length(); i++) {
                JSONObject bookmark = bookmarks.optJSONObject(i);
                if (bookmark != null)
                    drawerItem(drawer, R.drawable.ic_terminow_bookmark, bookmark.optString("name"), bookmark.optString("fsTitle"), () -> openBookmark(bookmark));
            }
        }
        if (drawerSection(drawer, "tools", R.string.terminow_explorer_section_tools, R.drawable.ic_terminow_analyzer)) {
            drawerItem(drawer, R.drawable.ic_terminow_logs, mActivity.getString(R.string.terminow_explorer_recent), null, () -> go(Screen.RECENTS));
            int active = mQueue.getActiveCount();
            drawerItem(drawer, R.drawable.ic_terminow_queue, mActivity.getString(R.string.terminow_explorer_queue),
                active > 0 ? mActivity.getString(R.string.terminow_explorer_queue_active, active) : null, () -> go(Screen.QUEUE));
            drawerItem(drawer, R.drawable.ic_terminow_analyzer, mActivity.getString(R.string.terminow_explorer_analyzer), null, this::startAnalyzer);
            drawerItem(drawer, R.drawable.ic_terminow_terminal, mActivity.getString(R.string.terminow_explorer_open_terminal_home), null,
                () -> mHost.openTerminalAt(new File(TermuxConstants.TERMUX_HOME_DIR_PATH)));
        }

        if (TerminowStorageAccess.isSupported()) {
            boolean access = TerminowStorageAccess.hasAccess(mActivity);
            drawerItem(drawer, R.drawable.ic_terminow_phone, mActivity.getString(R.string.terminow_explorer_access_title),
                mActivity.getString(access ? R.string.terminow_explorer_access_on : R.string.terminow_explorer_access_off), () -> {
                    if (access) TerminowStorageAccess.request(mActivity);
                    else explainAccess();
                });
        }

        View divider = new View(mActivity);
        divider.setBackgroundColor(mPalette.divider);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(dp(8), dp(10), dp(8), dp(6));
        drawer.addView(divider, dividerParams);

        LinearLayout hidden = new LinearLayout(mActivity);
        hidden.setGravity(Gravity.CENTER_VERTICAL);
        hidden.setMinimumHeight(dp(52));
        hidden.setPadding(dp(12), 0, dp(4), 0);
        hidden.addView(text(mActivity.getString(R.string.terminow_explorer_hidden), 15, mPalette.text),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch toggle = new Switch(mActivity);
        toggle.setChecked(mStore.getExplorer().optBoolean("showHidden"));
        toggle.setThumbTintList(ContextCompat.getColorStateList(mActivity, R.color.terminow_panel_switch_thumb));
        toggle.setTrackTintList(ContextCompat.getColorStateList(mActivity, R.color.terminow_panel_switch_track));
        toggle.setOnCheckedChangeListener((b, checked) -> setView("showHidden", checked));
        hidden.addView(toggle);
        drawer.addView(hidden, matchWrap());
        boolean grid = mStore.getExplorer().optBoolean("grid");
        drawerItem(drawer, grid ? R.drawable.ic_terminow_grid : R.drawable.ic_terminow_list, mActivity.getString(R.string.terminow_explorer_view),
            mActivity.getString(grid ? R.string.terminow_explorer_view_grid : R.string.terminow_explorer_view_list), () -> setView("grid", !grid));
        drawerItem(drawer, R.drawable.ic_terminow_theme, mActivity.getString(R.string.terminow_explorer_theme), themeLabel(), this::showThemeDialog);
    }

    /** A collapsible section header. Returns whether its items are shown. */
    private boolean drawerSection(@NonNull LinearLayout drawer, @NonNull String key, int titleRes, int iconRes) {
        boolean expanded = !mCollapsed.contains(key);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(8), dp(4), dp(8), dp(4));
        row.setBackground(ripple());
        row.addView(round(iconRes, mPalette.accent, 30), new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView title = text(mActivity.getString(titleRes), 15, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text(expanded ? "⌃" : "⌄", 16, mPalette.secondary));
        row.setContentDescription(mActivity.getString(titleRes));
        row.setOnClickListener(v -> {
            if (!mCollapsed.remove(key)) mCollapsed.add(key);
            render();
        });
        drawer.addView(row, matchWrap());
        return expanded;
    }

    private void drawerItem(@NonNull LinearLayout drawer, int iconRes, @NonNull String label, @Nullable String detail, @NonNull Runnable action) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(50), 0, dp(8), 0);
        row.setBackground(ripple());
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(mPalette.secondary));
        row.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(12), dp(4), 0, dp(4));
        TextView labelView = text(label, 14, mPalette.text);
        labelView.setSingleLine(true);
        labelView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(labelView, matchWrap());
        if (detail != null) texts.addView(text(detail, 11, mPalette.secondary), matchWrap());
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setOnClickListener(v -> {
            mDrawerOpen = false;
            action.run();
        });
        drawer.addView(row, matchWrap());
    }

    private void drawerNote(@NonNull LinearLayout drawer, int textRes) {
        TextView note = text(mActivity.getString(textRes), 12, mPalette.secondary);
        note.setPadding(dp(50), dp(4), dp(8), dp(8));
        drawer.addView(note, matchWrap());
    }

    @NonNull
    private String themeLabel() {
        String theme = mStore.getExplorer().optString("theme", "system");
        return mActivity.getString(theme.equals("light") ? R.string.terminow_explorer_theme_light :
            theme.equals("dark") ? R.string.terminow_explorer_theme_dark : R.string.terminow_explorer_theme_system);
    }

    private void showThemeDialog() {
        String[] values = {"system", "light", "dark"};
        CharSequence[] labels = {mActivity.getString(R.string.terminow_explorer_theme_system),
            mActivity.getString(R.string.terminow_explorer_theme_light), mActivity.getString(R.string.terminow_explorer_theme_dark)};
        String current = mStore.getExplorer().optString("theme", "system");
        int checked = current.equals("light") ? 1 : current.equals("dark") ? 2 : 0;
        dialog().setTitle(R.string.terminow_explorer_theme)
            .setSingleChoiceItems(labels, checked, (d, which) -> {
                d.dismiss();
                setView("theme", values[which]);
            }).show();
    }

    /** The progress of the running transfer, at the bottom of every screen. */
    @Nullable
    private View transferStrip() {
        TerminowTransferQueue.Job running = null;
        int active = 0;
        for (TerminowTransferQueue.Job job : mQueue.getJobs()) {
            if (job.isFinished()) continue;
            active++;
            if (job.status == TerminowTransferQueue.Status.RUNNING) running = job;
        }
        if (active == 0) return null;
        LinearLayout strip = new LinearLayout(mActivity);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(14), dp(6), dp(6), dp(6));
        strip.setBackground(rounded(mPalette.card, 14));
        strip.setOnClickListener(v -> go(Screen.QUEUE));
        LinearLayout texts = vertical();
        String title = running != null ? jobTitle(running) : mActivity.getString(R.string.terminow_explorer_queue_waiting);
        TextView titleView = text(title + (active > 1 ? "  (+" + (active - 1) + ")" : ""), 13, mPalette.text);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        texts.addView(titleView, matchWrap());
        texts.addView(progressBar(running), matchWrap());
        strip.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (running != null) {
            TerminowTransferQueue.Job job = running;
            TextView cancel = chip(mActivity.getString(android.R.string.cancel), false);
            cancel.setOnClickListener(v -> mQueue.cancel(job));
            strip.addView(cancel, chipParams());
        }
        LinearLayout wrapper = vertical();
        wrapper.setPadding(0, dp(6), dp(4), dp(8));
        wrapper.addView(strip, matchWrap());
        return wrapper;
    }

    @NonNull
    private ProgressBar progressBar(@Nullable TerminowTransferQueue.Job job) {
        ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setProgressTintList(ColorStateList.valueOf(mPalette.accent));
        bar.setIndeterminateTintList(ColorStateList.valueOf(mPalette.accent));
        if (job == null || job.bytesTotal <= 0) {
            bar.setIndeterminate(job != null && job.status == TerminowTransferQueue.Status.RUNNING);
            bar.setMax(1);
            bar.setProgress(job != null && job.status == TerminowTransferQueue.Status.DONE ? 1 : 0);
        } else {
            bar.setMax(1000);
            bar.setProgress((int) Math.min(1000, job.bytesDone * 1000 / job.bytesTotal));
        }
        return bar;
    }

    @NonNull
    private String jobTitle(@NonNull TerminowTransferQueue.Job job) {
        return mActivity.getString(job.move ? R.string.terminow_explorer_job_move : R.string.terminow_explorer_job_copy,
            mActivity.getResources().getQuantityString(R.plurals.terminow_explorer_items, job.itemCount, job.itemCount),
            job.sourceTitle, job.destinationTitle);
    }

    private void onJobChanged(@NonNull TerminowTransferQueue.Job job) {
        if (job.isFinished()) {
            TerminowTransfer.Result result = job.result;
            if (result != null && mVisible) {
                String summary = TerminowTransfer.describe(result);
                if (job.status == TerminowTransferQueue.Status.DONE && result.skippedLinks.isEmpty()) toast(summary);
                else showMessage(jobTitle(job), summary);
            }
            reloadShowing(job.target);
            reloadShowing(job.source);
            rescanIfShown();
        }
        if (mVisible) postRender();
    }

    // ---------------------------------------------------------------------------------------
    // Free users and Premium states
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View locked(@NonNull TerminowEntitlement.State state) {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(10), dp(4), dp(24));
        scroll.addView(page);
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView crown = new ImageView(mActivity);
        crown.setImageResource(R.drawable.ic_terminow_crown);
        card.addView(crown, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text(mActivity.getString(R.string.terminow_explorer_premium_title), 18, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(10));
        card.addView(title, matchWrap());
        card.addView(text(mActivity.getString(R.string.terminow_explorer_premium_features), 14, mPalette.text), matchWrap());

        String status;
        boolean hadPremium = state == TerminowEntitlement.State.EXPIRED || state == TerminowEntitlement.State.NEEDS_CHECK;
        if (mAccountUi.isCheckingPremium()) status = mActivity.getString(R.string.terminow_account_premium_checking);
        else if (state == TerminowEntitlement.State.SIGNED_OUT) status = mActivity.getString(R.string.terminow_explorer_state_signed_out);
        else if (state == TerminowEntitlement.State.UNKNOWN) status = mActivity.getString(R.string.terminow_explorer_state_unknown);
        else if (state == TerminowEntitlement.State.NEEDS_CHECK) status = mActivity.getString(R.string.terminow_explorer_state_needs_check, TerminowEntitlement.OFFLINE_DAYS);
        else if (state == TerminowEntitlement.State.EXPIRED) status = mActivity.getString(R.string.terminow_explorer_state_expired);
        else status = mActivity.getString(R.string.terminow_explorer_state_free);
        if (mAccountUi.getPremiumError() != null) status += "\n" + mAccountUi.getPremiumError();
        TextView statusView = text(status, 14, mPalette.secondary);
        statusView.setPadding(0, dp(14), 0, 0);
        statusView.setGravity(Gravity.CENTER);
        card.addView(statusView, matchWrap());

        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(16), 0, 0);
        // The Play build sells Premium with Google Play, once the account is known
        boolean canBuy = TerminowBilling.isSupported() && (state == TerminowEntitlement.State.FREE || state == TerminowEntitlement.State.EXPIRED);
        if (canBuy) {
            TextView get = button(mActivity.getString(R.string.terminow_premium_get), true);
            get.setOnClickListener(v -> mAccountUi.buyPremium());
            buttons.addView(get, buttonParams());
        }
        TextView check = button(mActivity.getString(state == TerminowEntitlement.State.SIGNED_OUT ?
            R.string.terminow_account_sign_in : R.string.terminow_explorer_check_premium), !canBuy);
        check.setEnabled(!mAccountUi.isCheckingPremium());
        check.setOnClickListener(v -> mAccountUi.checkPremiumNow());
        buttons.addView(check, buttonParams());
        if (!canBuy) {
            TextView account = button(mActivity.getString(R.string.terminow_explorer_account), false);
            account.setOnClickListener(v -> mHost.showAccountSettings());
            buttons.addView(account, buttonParams());
        }
        card.addView(buttons);

        if (hadPremium || state == TerminowEntitlement.State.FREE) {
            TextView keep = text(mActivity.getString(R.string.terminow_explorer_data_kept), 13, mPalette.secondary);
            keep.setPadding(0, dp(16), 0, dp(6));
            card.addView(keep, matchWrap());
            TextView export = button(mActivity.getString(R.string.terminow_explorer_export), false);
            export.setOnClickListener(v -> mHost.exportFile());
            card.addView(export, buttonParams());
        }
        page.addView(card, matchWrap());
        return scroll;
    }

    // ---------------------------------------------------------------------------------------
    // Dashboard
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View dashboard() {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(6), dp(4), dp(24));
        scroll.addView(page);
        int width = isLarge() && !mSidebarCollapsed ? widthDp() - 264 - 96 : widthDp() - 96;

        // Storage: each volume with its own capacity, TermiNow Home with its own size, the analyzer
        List<View> storageCards = new ArrayList<>();
        for (TerminowStorageAccess.Volume volume : TerminowStorageAccess.getVolumes(mActivity)) storageCards.add(volumeCard(volume));
        storageCards.add(homeCard());
        storageCards.add(analyzerCard());
        int storageColumns = width >= 520 ? 2 : 1;
        for (int start = 0; start < storageCards.size(); start += storageColumns) {
            LinearLayout row = new LinearLayout(mActivity);
            for (int i = start; i < start + storageColumns; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (i < start + storageColumns - 1) params.setMarginEnd(dp(10));
                row.addView(i < storageCards.size() ? storageCards.get(i) : new View(mActivity), params);
            }
            page.addView(row, cardParams());
        }

        // Linux environments, installed ones and Browse Linux
        View linux = linuxSection(storageColumns);
        if (linux != null) page.addView(linux, cardParams());

        // Categories
        LinearLayout categories = card();
        categories.addView(sectionTitle(R.string.terminow_explorer_section_categories), matchWrap());
        int columns = width >= 820 ? 6 : 3;
        TerminowFileScan.Category[] all = TerminowFileScan.Category.values();
        for (int start = 0; start < all.length; start += columns) {
            LinearLayout row = new LinearLayout(mActivity);
            for (int i = start; i < start + columns; i++) {
                if (i < all.length) row.addView(categoryTile(all[i]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                else row.addView(new View(mActivity), new LinearLayout.LayoutParams(0, 1, 1));
            }
            categories.addView(row, matchWrap());
        }
        page.addView(categories, cardParams());

        // Device folders
        LinearLayout folders = card();
        folders.addView(sectionTitle(R.string.terminow_explorer_section_device), matchWrap());
        JSONArray saved = mStore.getExplorer().optJSONArray("folders");
        for (int i = 0; saved != null && i < saved.length(); i++) {
            JSONObject folder = saved.optJSONObject(i);
            if (folder == null) continue;
            int index = i;
            Uri uri = Uri.parse(folder.optString("uri"));
            boolean access = new TerminowSafFs(mActivity, uri, folder.optString("name")).hasAccess();
            View row = listRow(R.drawable.ic_terminow_folder, access ? 0xFF3D8BFD : mPalette.secondary, folder.optString("name"),
                mActivity.getString(access ? R.string.terminow_explorer_device_folder : R.string.terminow_explorer_access_lost), null,
                v -> {
                    if (access) openSaf(uri, folder.optString("name"), null);
                    else repickFolder(index);
                });
            row.setOnLongClickListener(v -> {
                removeFolder(index);
                return true;
            });
            folders.addView(row, matchWrap());
        }
        folders.addView(listRow(R.drawable.ic_terminow_add, mPalette.secondary, mActivity.getString(R.string.terminow_explorer_add_folder),
            mActivity.getString(R.string.terminow_explorer_add_folder_summary), null, v -> addFolder()), matchWrap());
        page.addView(folders, cardParams());

        // SFTP
        LinearLayout sftp = card();
        sftp.addView(sectionTitle(R.string.terminow_explorer_section_sftp), matchWrap());
        List<TerminowStore.Host> hosts = mStore.getHosts();
        if (hosts.isEmpty()) sftp.addView(text(mActivity.getString(R.string.terminow_explorer_no_hosts), 13, mPalette.secondary), matchWrap());
        for (TerminowStore.Host host : hosts) {
            boolean connected = isConnected(host);
            TextView connect = button(mActivity.getString(connected ? R.string.terminow_explorer_open : R.string.terminow_explorer_connect), !connected);
            connect.setOnClickListener(v -> openSftp(host, null));
            String detail = (host.username.isEmpty() ? "" : host.username + "@") + host.address + ":" + host.port + "  ·  " +
                mActivity.getString(connected ? R.string.terminow_explorer_connected : R.string.terminow_explorer_not_connected);
            sftp.addView(listRow(R.drawable.ic_terminow_hosts, connected ? mPalette.accent : mPalette.danger, host.getTitle(), detail,
                connect, v -> openSftp(host, null)), matchWrap());
        }
        page.addView(sftp, cardParams());

        // Tools
        LinearLayout tools = card();
        tools.addView(sectionTitle(R.string.terminow_explorer_section_tools), matchWrap());
        LinearLayout toolRow = new LinearLayout(mActivity);
        toolRow.addView(tile(R.drawable.ic_terminow_logs, 0xFF5B8DEF, mActivity.getString(R.string.terminow_explorer_recent), () -> go(Screen.RECENTS)),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        toolRow.addView(tile(R.drawable.ic_terminow_bookmark, 0xFFB57BEE, mActivity.getString(R.string.terminow_explorer_bookmarks), () -> go(Screen.BOOKMARKS)),
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        int active = mQueue.getActiveCount();
        toolRow.addView(tile(R.drawable.ic_terminow_queue, 0xFF2BB5A6, mActivity.getString(R.string.terminow_explorer_queue) +
            (active > 0 ? " (" + active + ")" : ""), () -> go(Screen.QUEUE)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        toolRow.addView(tile(R.drawable.ic_terminow_terminal, 0xFF3C4258, mActivity.getString(R.string.terminow_explorer_open_terminal),
            () -> mHost.openTerminalAt(new File(TermuxConstants.TERMUX_HOME_DIR_PATH))), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        tools.addView(toolRow, matchWrap());
        page.addView(tools, cardParams());
        return scroll;
    }

    // ---------------------------------------------------------------------------------------
    // Linux environments
    // ---------------------------------------------------------------------------------------

    @Nullable
    private View linuxSection(int columns) {
        TerminowLinuxUi linux = mHost.getLinux();
        if (linux == null) return null;
        LinearLayout section = vertical();
        section.addView(sectionTitle(R.string.terminow_linux_section), matchWrap());
        List<View> cards = new ArrayList<>();
        for (TerminowLinuxEnv env : linux.getRegistry().list()) cards.add(linuxCard(linux, env));
        cards.add(browseLinuxCard(linux));
        for (int start = 0; start < cards.size(); start += columns) {
            LinearLayout row = new LinearLayout(mActivity);
            for (int i = start; i < start + columns; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (i < start + columns - 1) params.setMarginEnd(dp(10));
                row.addView(i < cards.size() ? cards.get(i) : new View(mActivity), params);
            }
            LinearLayout.LayoutParams rowParams = matchWrap();
            rowParams.bottomMargin = dp(10);
            section.addView(row, rowParams);
        }
        return section;
    }

    /** An installed environment: its version, status, size, open tabs, and Files, Terminal and more. */
    @NonNull
    private View linuxCard(@NonNull TerminowLinuxUi linux, @NonNull TerminowLinuxEnv env) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(linux.badge(env.distroId), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(14), 0, 0, 0);
        TextView title = text(env.getShortName(), 16, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        texts.addView(text(env.version.isEmpty() ? mActivity.getString(R.string.terminow_linux_version_unknown) : env.version, 12, mPalette.secondary));
        boolean installed = env.isInstalled();
        texts.addView(text(linux.statusText(env), 12, installed ? mPalette.accent : mPalette.danger));
        if (installed) {
            texts.addView(text(linux.usageText(env), 12, mPalette.secondary));
            int open = mHost.getLinux() != null ? linuxSessions(env) : 0;
            texts.addView(text(mActivity.getResources().getQuantityString(R.plurals.terminow_linux_terminals, open, open), 12, mPalette.secondary));
        }
        head.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView more = iconButton(R.drawable.ic_terminow_more, R.string.terminow_toolbar_more);
        more.setOnClickListener(v -> linux.showDetails(env));
        head.addView(more);
        card.addView(head, matchWrap());
        if (installed) {
            LinearLayout buttons = new LinearLayout(mActivity);
            buttons.setPadding(0, dp(10), 0, 0);
            TextView files = button(mActivity.getString(R.string.terminow_linux_files), false);
            files.setOnClickListener(v -> linux.openFiles(env, env.defaultUser, env.startFolder(env.defaultUser)));
            buttons.addView(files, buttonParams());
            TextView terminal = button(mActivity.getString(R.string.terminow_linux_terminal), true);
            terminal.setOnClickListener(v -> mHost.openLinuxTerminal(env.alias, env.defaultUser, env.startFolder(env.defaultUser)));
            buttons.addView(terminal, buttonParams());
            card.addView(buttons, matchWrap());
        } else {
            card.setOnClickListener(v -> linux.showDetails(env));
        }
        return card;
    }

    private int linuxSessions(@NonNull TerminowLinuxEnv env) {
        return mLinuxSessionCounter != null ? mLinuxSessionCounter.count(env.alias) : 0;
    }

    /** Counts the open tabs of an environment, set by the home screen. */
    public interface SessionCounter {
        int count(@NonNull String alias);
    }

    @Nullable private SessionCounter mLinuxSessionCounter;

    public void setLinuxSessionCounter(@NonNull SessionCounter counter) {
        mLinuxSessionCounter = counter;
    }

    @NonNull
    private View browseLinuxCard(@NonNull TerminowLinuxUi linux) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(round(R.drawable.ic_terminow_add, 0xFF3C4258, 48), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(14), 0, 0, 0);
        TextView title = text(mActivity.getString(R.string.terminow_linux_browse) + "  ›", 16, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        texts.addView(text(mActivity.getString(R.string.terminow_linux_browse_summary), 12, mPalette.secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, matchWrap());
        card.setOnClickListener(v -> linux.showBrowse(true));
        return card;
    }

    /** Open the files of an environment at a Linux path, for the Files buttons. */
    public void openLinux(@NonNull TerminowLinuxEnv env, @NonNull String path) {
        TerminowLinuxUi linux = mHost.getLinux();
        if (linux == null) return;
        TerminowLinuxFs fs = linux.filesOf(env);
        mFileSystems.put(fs.getId(), fs);
        background(() -> {
            try {
                return fs.crumbsOf(path);
            } catch (IOException e) {
                // The folder is gone: the root of the environment
                return Collections.singletonList(fs.getRoot());
            }
        }, crumbs -> open(fs, crumbs));
    }

    /** Home, Filesystem and the shared folders of the environment that is browsed. */
    private void addLinuxShortcuts(@NonNull LinearLayout tools, @NonNull TerminowLinuxFs fs) {
        TerminowLinuxEnv env = fs.getEnv();
        String user = env.defaultUser;
        TextView home = chip("⌂ " + mActivity.getString(R.string.terminow_linux_home), false);
        home.setOnClickListener(v -> openLinux(env, env.homeOf(user)));
        tools.addView(home, chipParams());
        TextView root = chip("/ " + mActivity.getString(R.string.terminow_linux_filesystem), false);
        root.setOnClickListener(v -> openLinux(env, "/"));
        tools.addView(root, chipParams());
        List<TerminowLinuxPaths.Mount> shared = new ArrayList<>();
        for (TerminowLinuxPaths.Mount mount : fs.getMounts()) if (mount.userShared || mount.guest.equals("/sdcard")) shared.add(mount);
        if (!shared.isEmpty()) {
            TextView sharedChip = chip("⇄ " + mActivity.getString(R.string.terminow_linux_shared), false);
            sharedChip.setOnClickListener(v -> {
                CharSequence[] labels = new CharSequence[shared.size()];
                for (int i = 0; i < shared.size(); i++) labels[i] = shared.get(i).guest;
                dialog().setTitle(R.string.terminow_linux_shared_folders)
                    .setItems(labels, (d, which) -> openLinux(env, shared.get(which).guest))
                    .show();
            });
            tools.addView(sharedChip, chipParams());
        }
    }

    /** A volume of shared storage, with its own used, free and total space. */
    @NonNull
    private View volumeCard(@NonNull TerminowStorageAccess.Volume volume) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        long[] space = volume.getSpace();
        float used = space != null && space[2] > 0 ? (float) space[0] / space[2] : 0f;
        row.addView(new UsageRing(mActivity, used, mPalette), new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(14), 0, 0, 0);
        TextView title = text(volume.title + "  ›", 16, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        if (space != null)
            texts.addView(text(mActivity.getString(R.string.terminow_explorer_capacity, size(space[0]), size(space[1]), size(space[2])), 13, mPalette.text));
        TextView status = text(volumeStatus(volume), 12, TerminowStorageAccess.hasAccess(mActivity) && volume.isReadable() ? mPalette.accent : mPalette.secondary);
        texts.addView(status);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, matchWrap());
        card.setOnClickListener(v -> openVolume(volume));
        return card;
    }

    @NonNull
    private String volumeStatus(@NonNull TerminowStorageAccess.Volume volume) {
        if (!TerminowStorageAccess.isSupported()) return mActivity.getString(R.string.terminow_explorer_volume_play);
        if (!TerminowStorageAccess.hasAccess(mActivity)) return mActivity.getString(R.string.terminow_explorer_volume_no_access);
        if (!volume.isReadable()) return mActivity.getString(R.string.terminow_explorer_volume_unreadable);
        return mActivity.getString(Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.state) ?
            R.string.terminow_explorer_volume_read_only : R.string.terminow_explorer_volume_ok);
    }

    /** TermiNow HOME: the size of the folder itself, never the capacity of the device. */
    @NonNull
    private View homeCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(round(R.drawable.ic_terminow_folder, mPalette.accent, 52), new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(14), 0, 0, 0);
        TextView title = text(mActivity.getString(R.string.terminow_explorer_home) + "  ›", 16, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        texts.addView(text(mActivity.getString(R.string.terminow_explorer_home_label), 12, mPalette.secondary));
        if (mHomeBytes < 0 || System.currentTimeMillis() - mHomeBytesTime > 10 * 60 * 1000) computeHomeSize();
        texts.addView(text(mHomeBytes >= 0 ? mActivity.getString(R.string.terminow_explorer_home_size, size(mHomeBytes)) :
            mActivity.getString(R.string.terminow_explorer_home_size_calculating), 12, mPalette.accent));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, matchWrap());
        card.setOnClickListener(v -> openHome(null));
        return card;
    }

    /** Open a volume by path, asking for access first when the build has the broad mode. */
    private void openVolume(@NonNull TerminowStorageAccess.Volume volume) {
        mDrawerOpen = false;
        if (!TerminowStorageAccess.isSupported()) {
            dialog().setTitle(volume.title)
                .setMessage(R.string.terminow_explorer_play_storage_message)
                .setPositiveButton(R.string.terminow_explorer_add_folder, (d, w) -> addFolder())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            render();
            return;
        }
        if (!TerminowStorageAccess.hasAccess(mActivity)) {
            explainAccess();
            render();
            return;
        }
        if (!volume.isReadable() || volume.directory == null) {
            dialog().setTitle(volume.title)
                .setMessage(R.string.terminow_explorer_volume_unreadable_message)
                .setPositiveButton(R.string.terminow_explorer_add_folder, (d, w) -> addFolder())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            render();
            return;
        }
        try {
            TerminowDeviceFs fs = deviceFs(volume);
            open(fs, Collections.singletonList(fs.getRoot()));
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    /** Explain what All files access gives, then open the screen of Android to turn it on. */
    private void explainAccess() {
        dialog().setTitle(R.string.terminow_explorer_access_title)
            .setMessage(R.string.terminow_explorer_access_message)
            .setPositiveButton(R.string.terminow_explorer_access_open, (d, w) -> TerminowStorageAccess.request(mActivity))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** The result of the storage permission dialog of Android 6 to 10. */
    public void onStoragePermissionResult() {
        onResume();
    }

    @NonNull
    private TerminowDeviceFs deviceFs(@NonNull TerminowStorageAccess.Volume volume) throws IOException {
        TerminowFs existing = mFileSystems.get(volume.getFsId());
        if (existing instanceof TerminowDeviceFs) return (TerminowDeviceFs) existing;
        if (volume.directory == null) throw new IOException(volume.title + " has no path");
        TerminowDeviceFs fs = new TerminowDeviceFs(volume.directory, volume.getFsId(), volume.title);
        mFileSystems.put(fs.getId(), fs);
        return fs;
    }

    private void computeHomeSize() {
        if (mHomeBytesRunning) return;
        mHomeBytesRunning = true;
        mExecutor.execute(() -> {
            long bytes = -1;
            try {
                TerminowLocalFs home = getHomeFs();
                bytes = TerminowFileScan.analyze(home, home.getRoot(), new TerminowFs.Cancel(), null).totalBytes;
            } catch (IOException | RuntimeException e) {
                // Stays unknown
            }
            long result = bytes;
            mHandler.post(() -> {
                mHomeBytesRunning = false;
                mHomeBytes = result;
                mHomeBytesTime = System.currentTimeMillis();
                if (mVisible && mScreen == Screen.DASHBOARD) render();
            });
        });
    }

    @NonNull
    private View analyzerCard() {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(round(R.drawable.ic_terminow_analyzer, 0xFF3D8BFD, 52), new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(14), 0, 0, 0);
        TextView title = text(mActivity.getString(R.string.terminow_explorer_analyzer) + "  ›", 16, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(title);
        String summary;
        if (mAnalyzing) summary = mActivity.getString(R.string.terminow_explorer_analyzing);
        else if (!mSpaces.isEmpty()) {
            long total = 0;
            for (LocationSpace space : mSpaces) if (space.space != null) total += space.space.totalBytes;
            summary = mActivity.getString(R.string.terminow_explorer_analyzer_result, size(total), mSpaces.size());
        } else summary = mActivity.getString(R.string.terminow_explorer_analyzer_summary);
        texts.addView(text(summary, 12, mPalette.secondary));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(row, matchWrap());
        card.setOnClickListener(v -> startAnalyzer());
        return card;
    }

    @NonNull
    private View categoryTile(@NonNull TerminowFileScan.Category category) {
        return tile(categoryIcon(category), categoryColor(category), categoryLabel(category), () -> startCategory(category));
    }

    @NonNull
    private View tile(int iconRes, int circleColor, @NonNull String label, @NonNull Runnable action) {
        LinearLayout tile = vertical();
        tile.setGravity(Gravity.CENTER_HORIZONTAL);
        tile.setPadding(dp(4), dp(10), dp(4), dp(10));
        tile.setBackground(ripple());
        tile.addView(round(iconRes, circleColor, 52), new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView labelView = text(label, 13, mPalette.text);
        labelView.setGravity(Gravity.CENTER);
        labelView.setMaxLines(2);
        labelView.setPadding(0, dp(6), 0, 0);
        tile.addView(labelView, matchWrap());
        tile.setContentDescription(label);
        tile.setOnClickListener(v -> action.run());
        return tile;
    }

    @NonNull
    private String categoryLabel(@Nullable TerminowFileScan.Category category) {
        if (category == null) return "";
        switch (category) {
            case IMAGES: return mActivity.getString(R.string.terminow_explorer_cat_images);
            case VIDEOS: return mActivity.getString(R.string.terminow_explorer_cat_videos);
            case AUDIO: return mActivity.getString(R.string.terminow_explorer_cat_audio);
            case DOCUMENTS: return mActivity.getString(R.string.terminow_explorer_cat_documents);
            case APK: return mActivity.getString(R.string.terminow_explorer_cat_apk);
            default: return mActivity.getString(R.string.terminow_explorer_cat_archives);
        }
    }

    private static int categoryIcon(@NonNull TerminowFileScan.Category category) {
        switch (category) {
            case IMAGES: return R.drawable.ic_terminow_image;
            case VIDEOS: return R.drawable.ic_terminow_video;
            case AUDIO: return R.drawable.ic_terminow_audio;
            case DOCUMENTS: return R.drawable.ic_terminow_document;
            case APK: return R.drawable.ic_terminow_apk;
            default: return R.drawable.ic_terminow_archive;
        }
    }

    private static int categoryColor(@NonNull TerminowFileScan.Category category) {
        switch (category) {
            case IMAGES: return 0xFFFF9A3D;
            case VIDEOS: return 0xFF1BC9C0;
            case AUDIO: return 0xFF8E5BEA;
            case DOCUMENTS: return 0xFFF4B400;
            case APK: return 0xFF21C17A;
            default: return 0xFF3D8BFD;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Categories, search and the space analyzer
    // ---------------------------------------------------------------------------------------

    /**
     * The locations that the app can read: HOME, the volumes of shared storage when All files
     * access is on, and the folders picked with the Storage Access Framework that are not inside
     * one of those volumes already, so that nothing is found twice.
     */
    @NonNull
    private List<TerminowFs> accessibleLocations() {
        List<TerminowFs> locations = new ArrayList<>();
        try {
            locations.add(getHomeFs());
        } catch (IOException e) {
            // Without HOME
        }
        List<String> coveredVolumes = new ArrayList<>();
        if (TerminowStorageAccess.hasAccess(mActivity)) {
            for (TerminowStorageAccess.Volume volume : TerminowStorageAccess.getVolumes(mActivity)) {
                if (!volume.isReadable()) continue;
                try {
                    locations.add(deviceFs(volume));
                    coveredVolumes.add(volume.uuid);
                } catch (IOException e) {
                    // Skipped
                }
            }
        }
        // Linux environments, without their bound folders, which are shared storage or HOME
        TerminowLinuxUi linux = mHost.getLinux();
        if (linux != null && TerminowAccountUi.isPremium(mStore)) {
            for (TerminowLinuxEnv env : linux.getRegistry().installed()) locations.add(linux.filesOf(env).forScan());
        }
        JSONArray folders = mStore.getExplorer().optJSONArray("folders");
        for (int i = 0; folders != null && i < folders.length(); i++) {
            JSONObject folder = folders.optJSONObject(i);
            if (folder == null) continue;
            Uri uri = Uri.parse(folder.optString("uri"));
            if (isInsideVolume(uri, coveredVolumes)) continue;
            TerminowFs fs = findFs(TerminowSafFs.idFor(uri), folder.optString("name"));
            if (fs instanceof TerminowSafFs && ((TerminowSafFs) fs).hasAccess()) locations.add(fs);
        }
        return locations;
    }

    private static boolean isInsideVolume(@NonNull Uri treeUri, @NonNull List<String> volumeUuids) {
        if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) return false;
        String treeId;
        try {
            treeId = android.provider.DocumentsContract.getTreeDocumentId(treeUri);
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (String uuid : volumeUuids)
            if (TerminowDeviceFs.treeDocumentPath(treeId, uuid) != null) return true;
        return false;
    }

    @NonNull
    private String scopeLabel() {
        List<String> titles = new ArrayList<>();
        for (TerminowFs fs : accessibleLocations()) titles.add(fs.getTitle());
        return TextUtils.join(", ", titles);
    }

    private void startCategory(@NonNull TerminowFileScan.Category category) {
        mScan.category = category;
        mScan.query = "";
        mScreen = Screen.CATEGORY;
        mDrawerOpen = false;
        runScan(node -> category.matches(node.name));
    }

    private void startNameSearch() {
        if (mScan.cancel != null) mScan.cancel.cancel();
        mScan.category = null;
        mScan.query = "";
        mScan.results.clear();
        mScan.running = false;
        mScan.status = "";
        mScreen = Screen.SEARCH;
        mDrawerOpen = false;
        render();
    }

    private void runScan(@NonNull TerminowFileScan.Filter filter) {
        if (mScan.cancel != null) mScan.cancel.cancel();
        mScan.filter = filter;
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        mScan.cancel = cancel;
        mScan.results.clear();
        mScan.running = true;
        mScan.status = mActivity.getString(R.string.terminow_explorer_searching, 0);
        int generation = ++mScan.generation;
        boolean hidden = mStore.getExplorer().optBoolean("showHidden");
        List<TerminowFs> locations = accessibleLocations();
        render();
        mExecutor.execute(() -> {
            List<TerminowFileScan.Match> results = Collections.synchronizedList(new ArrayList<>());
            for (TerminowFs fs : locations) {
                try {
                    TerminowFileScan.find(fs, Collections.singletonList(fs.getRoot()), filter, hidden, results, cancel, (name, found) -> {
                        String status = mActivity.getString(R.string.terminow_explorer_searching, found);
                        mHandler.post(() -> {
                            if (mScan.generation == generation) mScan.status = status;
                        });
                    });
                } catch (TerminowFs.CancelledException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    // A location that can not be read is skipped
                }
            }
            mHandler.post(() -> {
                if (mScan.generation != generation) return;
                mScan.running = false;
                mScan.results.clear();
                synchronized (results) {
                    mScan.results.addAll(results);
                }
                Collections.sort(mScan.results, (a, b) -> Long.compare(b.node.modified, a.node.modified));
                mScan.status = cancel.isCancelled() ? mActivity.getString(R.string.terminow_explorer_search_cancelled) :
                    mActivity.getResources().getQuantityString(R.plurals.terminow_explorer_found, mScan.results.size(), mScan.results.size());
                if (mScan.results.size() >= TerminowFileScan.MAX_RESULTS)
                    mScan.status += " · " + mActivity.getString(R.string.terminow_explorer_search_limit, TerminowFileScan.MAX_RESULTS);
                render();
            });
        });
    }

    @NonNull
    private View scanScreen() {
        LinearLayout page = vertical();
        page.setPadding(0, dp(6), dp(4), 0);
        if (mScreen == Screen.SEARCH) {
            EditText field = new EditText(mActivity);
            field.setHint(R.string.terminow_explorer_search_all_hint);
            field.setSingleLine(true);
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            field.setTextColor(mPalette.text);
            field.setHintTextColor(mPalette.secondary);
            field.setText(mScan.query);
            field.setSelection(mScan.query.length());
            field.setBackground(rounded(mPalette.field, 22));
            field.setPadding(dp(18), dp(10), dp(18), dp(10));
            field.setOnEditorActionListener((v, actionId, event) -> {
                String query = field.getText().toString().trim();
                if (query.length() < 2) {
                    field.setError(mActivity.getString(R.string.terminow_explorer_search_short));
                    return true;
                }
                mScan.query = query;
                String lower = query.toLowerCase(Locale.ROOT);
                runScan(node -> node.name.toLowerCase(Locale.ROOT).contains(lower));
                return true;
            });
            page.addView(field, matchWrap());
            if (mScan.query.isEmpty()) field.requestFocus();
        }
        LinearLayout status = new LinearLayout(mActivity);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(4), dp(10), 0, dp(6));
        if (mScan.running) {
            ProgressBar spinner = new ProgressBar(mActivity);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(mPalette.accent));
            status.addView(spinner, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        TextView statusText = text(mScan.status, 13, mPalette.secondary);
        statusText.setPadding(dp(8), 0, 0, 0);
        status.addView(statusText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (mScan.running) {
            TextView cancel = chip(mActivity.getString(android.R.string.cancel), false);
            cancel.setOnClickListener(v -> {
                if (mScan.cancel != null) mScan.cancel.cancel();
            });
            status.addView(cancel, chipParams());
        }
        page.addView(status, matchWrap());

        if (!mScan.running && mScan.results.isEmpty() && (mScreen == Screen.CATEGORY || !mScan.query.isEmpty())) {
            page.addView(emptyText(R.string.terminow_explorer_nothing_found), matchWrap());
            return page;
        }
        List<TerminowFileScan.Match> matches = new ArrayList<>(mScan.results);
        ViewPrefs resultsView = viewOf(null);
        GridView list = gridView(resultsView);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return matches.size(); }
            @Override public Object getItem(int position) { return matches.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TerminowFileScan.Match match = matches.get(position);
                return entryView(match.fs, match.node, false, resultsView, match.fs.getTitle() + " · " + match.fs.getDisplayPath(match.parents));
            }
        };
        mListAdapter = adapter;
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> {
            TerminowFileScan.Match match = matches.get(position);
            openFile(match.fs, match.node, match.fs.getDisplayPath(match.parents));
        });
        list.setOnItemLongClickListener((parent, v, position, id) -> {
            showMatchActions(matches.get(position));
            return true;
        });
        page.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private void showMatchActions(@NonNull TerminowFileScan.Match match) {
        CharSequence[] labels = {mActivity.getString(R.string.terminow_explorer_open_folder), mActivity.getString(R.string.terminow_explorer_share),
            mActivity.getString(R.string.terminow_explorer_copy), mActivity.getString(R.string.terminow_explorer_properties)};
        String path = match.fs.getDisplayPath(match.parents);
        dialog().setTitle(match.node.name).setItems(labels, (d, which) -> {
            TerminowFs.Node parent = match.parents.get(match.parents.size() - 1);
            if (which == 0) open(match.fs, match.parents);
            else if (which == 1) share(match.fs, Collections.singletonList(match.node), path);
            else if (which == 2) {
                mClip = new Clip(match.fs, parent, Collections.singletonList(match.node), false, match.fs.getTitle());
                toast(mActivity.getString(R.string.terminow_explorer_copied_hint));
            } else showProperties(match.fs, match.node, path);
        }).show();
    }

    private void startAnalyzer() {
        mScreen = Screen.ANALYZER;
        mDrawerOpen = false;
        if (mAnalyzing) {
            render();
            return;
        }
        if (mAnalyzeCancel != null) mAnalyzeCancel.cancel();
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        mAnalyzeCancel = cancel;
        mAnalyzing = true;
        mAnalyzeStatus = mActivity.getString(R.string.terminow_explorer_analyzing);
        mSpaces.clear();
        for (TerminowFs fs : accessibleLocations()) mSpaces.add(new LocationSpace(fs));
        List<LocationSpace> spaces = new ArrayList<>(mSpaces);
        render();
        mExecutor.execute(() -> {
            for (LocationSpace location : spaces) {
                try {
                    TerminowFileScan.Space space = TerminowFileScan.analyze(location.fs, location.fs.getRoot(), cancel, (name, files) -> {
                        String status = mActivity.getString(R.string.terminow_explorer_analyzing_in, location.fs.getTitle(), files);
                        mHandler.post(() -> mAnalyzeStatus = status);
                    });
                    mHandler.post(() -> {
                        location.space = space;
                        if (mVisible && mScreen == Screen.ANALYZER) render();
                    });
                } catch (TerminowFs.CancelledException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    String error = String.valueOf(e.getMessage());
                    mHandler.post(() -> location.error = error);
                }
            }
            mHandler.post(() -> {
                mAnalyzing = false;
                if (cancel.isCancelled()) mAnalyzeStatus = mActivity.getString(R.string.terminow_explorer_search_cancelled);
                render();
            });
        });
    }

    @NonNull
    private View analyzerScreen() {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(6), dp(4), dp(24));
        scroll.addView(page);
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), 0, 0, dp(8));
        TextView status = text(mAnalyzing ? mAnalyzeStatus : mActivity.getString(R.string.terminow_explorer_analyzer_note, scopeLabel()), 13, mPalette.secondary);
        head.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView action = chip(mActivity.getString(mAnalyzing ? android.R.string.cancel : R.string.terminow_explorer_analyze_again), false);
        action.setOnClickListener(v -> {
            if (mAnalyzing && mAnalyzeCancel != null) mAnalyzeCancel.cancel();
            else startAnalyzer();
        });
        head.addView(action, chipParams());
        page.addView(head, matchWrap());

        for (LocationSpace location : mSpaces) {
            LinearLayout card = card();
            TextView title = text(location.fs.getTitle(), 16, mPalette.text);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(title);
            if (location.error != null) {
                card.addView(text(location.error, 13, mPalette.danger));
            } else if (location.space == null) {
                card.addView(text(mActivity.getString(mAnalyzing ? R.string.terminow_explorer_analyzing : R.string.terminow_explorer_search_cancelled),
                    13, mPalette.secondary));
            } else {
                TerminowFileScan.Space space = location.space;
                card.addView(text(mActivity.getString(R.string.terminow_explorer_space_total, size(space.totalBytes), space.files, space.folders) +
                    (space.incomplete ? " · " + mActivity.getString(R.string.terminow_explorer_space_incomplete) : ""), 13, mPalette.secondary));
                if (!space.unreadable.isEmpty()) {
                    TextView unreadable = text(mActivity.getString(R.string.terminow_explorer_space_unreadable,
                        TextUtils.join(", ", space.unreadable.subList(0, Math.min(5, space.unreadable.size()))) +
                            (space.unreadable.size() > 5 ? " …" : "")), 12, mPalette.warning);
                    unreadable.setPadding(0, dp(4), 0, 0);
                    card.addView(unreadable, matchWrap());
                }
                long max = space.top.isEmpty() ? 1 : Math.max(1, space.top.get(0).bytes);
                for (int i = 0; i < Math.min(10, space.top.size()); i++) {
                    TerminowFileScan.Entry entry = space.top.get(i);
                    card.addView(spaceRow(entry.node.name, entry.bytes, max, entry.node.directory, v -> {
                        try {
                            TerminowFs.Node root = location.fs.getRoot();
                            if (entry.node.directory) {
                                List<TerminowFs.Node> crumbs = new ArrayList<>();
                                crumbs.add(root);
                                crumbs.add(entry.node);
                                open(location.fs, crumbs);
                            } else {
                                openFile(location.fs, entry.node, location.fs.getTitle());
                            }
                        } catch (IOException e) {
                            toast(e.getMessage());
                        }
                    }), matchWrap());
                }
                if (!space.largest.isEmpty()) {
                    TextView largest = text(mActivity.getString(R.string.terminow_explorer_largest_files), 13, mPalette.text);
                    largest.setTypeface(Typeface.DEFAULT_BOLD);
                    largest.setPadding(0, dp(12), 0, dp(2));
                    card.addView(largest);
                    for (int i = 0; i < Math.min(5, space.largest.size()); i++) {
                        TerminowFileScan.Match match = space.largest.get(i);
                        card.addView(spaceRow(match.node.name + "  ·  " + match.fs.getDisplayPath(match.parents), Math.max(0, match.node.size),
                            Math.max(1, space.largest.get(0).node.size), false, v -> showMatchActions(match)), matchWrap());
                    }
                }
            }
            page.addView(card, cardParams());
        }
        return scroll;
    }

    @NonNull
    private View spaceRow(@NonNull String name, long bytes, long max, boolean directory, @NonNull View.OnClickListener onClick) {
        LinearLayout row = vertical();
        row.setPadding(0, dp(8), 0, dp(4));
        row.setMinimumHeight(dp(48));
        row.setBackground(ripple());
        LinearLayout line = new LinearLayout(mActivity);
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(directory ? R.drawable.ic_terminow_folder : R.drawable.ic_terminow_file);
        icon.setImageTintList(ColorStateList.valueOf(directory ? mPalette.accent : mPalette.secondary));
        line.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView label = text(name, 13, mPalette.text);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        label.setPadding(dp(8), 0, dp(8), 0);
        line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        line.addView(text(size(bytes), 13, mPalette.secondary));
        row.addView(line, matchWrap());
        ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgress((int) Math.min(1000, bytes * 1000 / Math.max(1, max)));
        bar.setProgressTintList(ColorStateList.valueOf(mPalette.accent));
        row.addView(bar, matchWrap());
        row.setOnClickListener(onClick);
        return row;
    }

    // ---------------------------------------------------------------------------------------
    // Queue, recent files and bookmarks
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View queueScreen() {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(6), dp(4), dp(24));
        scroll.addView(page);
        List<TerminowTransferQueue.Job> jobs = mQueue.getJobs();
        if (jobs.isEmpty()) {
            page.addView(emptyText(R.string.terminow_explorer_queue_empty), matchWrap());
            return scroll;
        }
        TextView clear = chip(mActivity.getString(R.string.terminow_explorer_queue_clear), false);
        clear.setOnClickListener(v -> {
            mQueue.clearFinished();
            render();
        });
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.END);
        head.setPadding(0, 0, 0, dp(8));
        head.addView(clear, chipParams());
        page.addView(head, matchWrap());
        for (int i = jobs.size() - 1; i >= 0; i--) {
            TerminowTransferQueue.Job job = jobs.get(i);
            LinearLayout card = card();
            TextView title = text(jobTitle(job), 14, mPalette.text);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(title, matchWrap());
            String detail;
            switch (job.status) {
                case QUEUED: detail = mActivity.getString(R.string.terminow_explorer_queue_waiting); break;
                case RUNNING: detail = job.currentName + (job.bytesTotal > 0 ? "  ·  " + size(job.bytesDone) + " / " + size(job.bytesTotal) : "") +
                    (job.filesTotal > 0 ? "  ·  " + job.filesDone + "/" + job.filesTotal : ""); break;
                case CANCELLED: detail = mActivity.getString(R.string.terminow_explorer_queue_cancelled); break;
                default: detail = job.result != null ? TerminowTransfer.describe(job.result) : "";
            }
            card.addView(text(detail, 12, job.status == TerminowTransferQueue.Status.FAILED ? mPalette.danger : mPalette.secondary), matchWrap());
            if (!job.isFinished() || job.status == TerminowTransferQueue.Status.DONE) card.addView(progressBar(job), matchWrap());
            if (!job.isFinished()) {
                TextView cancel = chip(mActivity.getString(android.R.string.cancel), false);
                cancel.setOnClickListener(v -> mQueue.cancel(job));
                LinearLayout buttons = new LinearLayout(mActivity);
                buttons.setPadding(0, dp(8), 0, 0);
                buttons.addView(cancel, chipParams());
                card.addView(buttons);
            }
            page.addView(card, cardParams());
        }
        return scroll;
    }

    @NonNull
    private View recentsScreen() {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(6), dp(4), dp(24));
        scroll.addView(page);
        JSONArray recents = mStore.getExplorer().optJSONArray("recents");
        if (recents == null || recents.length() == 0) {
            page.addView(emptyText(R.string.terminow_explorer_no_recents), matchWrap());
            return scroll;
        }
        LinearLayout card = card();
        for (int i = 0; i < recents.length(); i++) {
            JSONObject recent = recents.optJSONObject(i);
            if (recent == null) continue;
            String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(recent.optLong("time")));
            TerminowFileScan.Category category = TerminowFileScan.Category.of(recent.optString("name"));
            card.addView(listRow(category != null ? categoryIcon(category) : R.drawable.ic_terminow_file,
                category != null ? categoryColor(category) : mPalette.secondary, recent.optString("name"),
                recent.optString("fsTitle") + " · " + recent.optString("path") + " · " + when, null, v -> openRecent(recent)), matchWrap());
        }
        page.addView(card, cardParams());
        TextView clear = chip(mActivity.getString(R.string.terminow_explorer_clear_recents), false);
        clear.setOnClickListener(v -> {
            JSONObject state = mStore.getExplorer();
            state.remove("recents");
            mStore.setExplorer(state);
            render();
        });
        page.addView(clear, chipParams());
        return scroll;
    }

    @NonNull
    private View bookmarksScreen() {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(6), dp(4), dp(24));
        scroll.addView(page);
        JSONArray bookmarks = mStore.getExplorer().optJSONArray("bookmarks");
        if (bookmarks == null || bookmarks.length() == 0) {
            page.addView(emptyText(R.string.terminow_explorer_no_bookmarks), matchWrap());
            return scroll;
        }
        LinearLayout card = card();
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject bookmark = bookmarks.optJSONObject(i);
            if (bookmark == null) continue;
            int index = i;
            View row = listRow(R.drawable.ic_terminow_bookmark, 0xFFB57BEE, bookmark.optString("name"),
                bookmark.optString("fsTitle") + " · " + bookmark.optString("path"), null, v -> openBookmark(bookmark));
            row.setOnLongClickListener(v -> {
                confirm(mActivity.getString(R.string.terminow_explorer_remove_bookmark, bookmark.optString("name")), () -> removeAt("bookmarks", index));
                return true;
            });
            card.addView(row, matchWrap());
        }
        page.addView(card, cardParams());
        page.addView(text(mActivity.getString(R.string.terminow_explorer_bookmark_hint), 12, mPalette.secondary), matchWrap());
        return scroll;
    }

    @NonNull
    private TextView emptyText(int textRes) {
        TextView empty = text(mActivity.getString(textRes), 14, mPalette.secondary);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(30), dp(16), 0);
        return empty;
    }

    private void removeAt(@NonNull String key, int index) {
        JSONObject state = mStore.getExplorer();
        JSONArray array = state.optJSONArray(key);
        if (array == null) return;
        JSONArray updated = new JSONArray();
        for (int i = 0; i < array.length(); i++) if (i != index) updated.put(array.opt(i));
        try {
            state.put(key, updated);
        } catch (JSONException e) {
            return;
        }
        mStore.setExplorer(state);
        render();
    }

    // ---------------------------------------------------------------------------------------
    // Opening locations
    // ---------------------------------------------------------------------------------------

    @NonNull
    private TerminowLocalFs getHomeFs() throws IOException {
        TerminowFs fs = mFileSystems.get(TerminowLocalFs.ID);
        if (fs == null) {
            fs = new TerminowLocalFs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), mActivity.getString(R.string.terminow_explorer_home));
            mFileSystems.put(fs.getId(), fs);
        }
        return (TerminowLocalFs) fs;
    }

    private boolean isConnected(@NonNull TerminowStore.Host host) {
        TerminowFs fs = mFileSystems.get("sftp:" + host.getKey());
        return fs instanceof TerminowSftpFs && ((TerminowSftpFs) fs).isConnected();
    }

    private void openHome(@Nullable List<TerminowFs.Node> crumbs) {
        try {
            TerminowLocalFs fs = getHomeFs();
            open(fs, crumbs != null ? crumbs : Collections.singletonList(fs.getRoot()));
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    private void openSaf(@NonNull Uri uri, @NonNull String name, @Nullable List<TerminowFs.Node> crumbs) {
        TerminowFs fs = findFs(TerminowSafFs.idFor(uri), name);
        if (fs == null) return;
        background(() -> crumbs != null ? crumbs : Collections.singletonList(fs.getRoot()), result -> open(fs, result));
    }

    @NonNull
    TerminowSftpFs sftpFor(@NonNull TerminowStore.Host saved) {
        String id = "sftp:" + saved.getKey();
        // With the username, password and key of its identity
        TerminowStore.Host host = TerminowStore.resolve(saved, mStore.getIdentities());
        TerminowFs existing = mFileSystems.get(id);
        if (existing instanceof TerminowSftpFs) return (TerminowSftpFs) existing;
        TerminowSftpFs fs = new TerminowSftpFs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), id, host.getTitle(), host.address,
            host.port, host.username, host.password, host.keyPath, new Prompts());
        mFileSystems.put(id, fs);
        return fs;
    }

    private void openSftp(@NonNull TerminowStore.Host host, @Nullable List<TerminowFs.Node> crumbs) {
        TerminowSftpFs fs = sftpFor(host);
        background(() -> {
            fs.connect();
            return crumbs != null ? crumbs : Collections.singletonList(fs.getRoot());
        }, result -> open(fs, result));
    }

    private void disconnect(@NonNull TerminowFs fs) {
        mExecutor.execute(fs::close);
        for (Pane pane : mPanes) {
            if (pane.fs == fs) {
                pane.fs = null;
                pane.crumbs.clear();
                pane.entries.clear();
            }
        }
        if (mPanes[mActivePane].fs == null) mScreen = Screen.DASHBOARD;
        render();
    }

    private void open(@NonNull TerminowFs fs, @NonNull List<TerminowFs.Node> crumbs) {
        Pane pane = mPanes[mActivePane];
        if (pane.cancel != null) pane.cancel.cancel();
        pane.fs = fs;
        pane.crumbs.clear();
        pane.crumbs.addAll(crumbs);
        pane.selection.clear();
        pane.query = "";
        pane.searching = false;
        mScreen = Screen.BROWSER;
        mDrawerOpen = false;
        load(pane);
    }

    @Nullable
    private TerminowFs findFs(@NonNull String id, @NonNull String title) {
        TerminowFs fs = mFileSystems.get(id);
        if (fs != null) return fs;
        try {
            if (id.equals(TerminowLocalFs.ID)) return getHomeFs();
        } catch (IOException e) {
            return null;
        }
        if (id.startsWith("saf:")) {
            fs = new TerminowSafFs(mActivity, Uri.parse(id.substring(4)), title);
            mFileSystems.put(id, fs);
            return fs;
        }
        if (id.startsWith("device:")) {
            if (!TerminowStorageAccess.hasAccess(mActivity)) return null;
            for (TerminowStorageAccess.Volume volume : TerminowStorageAccess.getVolumes(mActivity)) {
                if (!volume.getFsId().equals(id)) continue;
                try {
                    return deviceFs(volume);
                } catch (IOException e) {
                    return null;
                }
            }
        }
        if (id.startsWith("sftp:")) {
            for (TerminowStore.Host host : mStore.getHosts())
                if (("sftp:" + host.getKey()).equals(id)) return sftpFor(host);
        }
        return null;
    }

    private void openBookmark(@NonNull JSONObject bookmark) {
        TerminowFs fs = findFs(bookmark.optString("fs"), bookmark.optString("fsTitle"));
        if (fs == null) {
            toast(mActivity.getString(R.string.terminow_explorer_location_gone));
            return;
        }
        List<TerminowFs.Node> crumbs = crumbsFromJson(bookmark.optJSONArray("crumbs"));
        background(() -> {
            if (fs instanceof TerminowSftpFs) ((TerminowSftpFs) fs).connect();
            return crumbs;
        }, result -> open(fs, result));
    }

    private void openRecent(@NonNull JSONObject recent) {
        TerminowFs fs = findFs(recent.optString("fs"), recent.optString("fsTitle"));
        if (fs == null) {
            toast(mActivity.getString(R.string.terminow_explorer_location_gone));
            return;
        }
        TerminowFs.Node file = new TerminowFs.Node(recent.optString("id"), recent.optString("name"), false, false, -1, 0, null);
        openFile(fs, file, recent.optString("path"));
    }

    @NonNull
    private static JSONArray crumbsToJson(@NonNull List<TerminowFs.Node> crumbs) {
        JSONArray array = new JSONArray();
        for (TerminowFs.Node node : crumbs) {
            try {
                array.put(new JSONObject().put("id", node.id).put("name", node.name));
            } catch (JSONException e) {
                // Skip
            }
        }
        return array;
    }

    @NonNull
    private static List<TerminowFs.Node> crumbsFromJson(@Nullable JSONArray array) {
        List<TerminowFs.Node> crumbs = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object != null) crumbs.add(new TerminowFs.Node(object.optString("id"), object.optString("name"), true, false, -1, 0, null));
        }
        return crumbs;
    }

    // ---------------------------------------------------------------------------------------
    // Device folders (Storage Access Framework)
    // ---------------------------------------------------------------------------------------

    private void addFolder() {
        mRepickIndex = -1;
        launchTreePicker(REQUEST_ADD_FOLDER, null);
    }

    private void repickFolder(int index) {
        JSONArray folders = mStore.getExplorer().optJSONArray("folders");
        JSONObject folder = folders != null ? folders.optJSONObject(index) : null;
        dialog()
            .setTitle(folder != null ? folder.optString("name") : "")
            .setMessage(R.string.terminow_explorer_access_lost_message)
            .setPositiveButton(R.string.terminow_explorer_choose_again, (d, w) -> {
                mRepickIndex = index;
                launchTreePicker(REQUEST_REPICK_FOLDER, folder != null ? Uri.parse(folder.optString("uri")) : null);
            })
            .setNeutralButton(R.string.terminow_explorer_remove_folder, (d, w) -> removeFolder(index))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void launchTreePicker(int requestCode, @Nullable Uri initial) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (initial != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initial);
        try {
            mActivity.startActivityForResult(intent, requestCode);
        } catch (ActivityNotFoundException e) {
            toast(mActivity.getString(R.string.terminow_explorer_no_picker));
        }
    }

    private void removeFolder(int index) {
        JSONArray folders = mStore.getExplorer().optJSONArray("folders");
        JSONObject folder = folders != null ? folders.optJSONObject(index) : null;
        if (folder == null) return;
        confirm(mActivity.getString(R.string.terminow_explorer_remove_folder_confirm, folder.optString("name")), () -> {
            Uri uri = Uri.parse(folder.optString("uri"));
            try {
                mActivity.getContentResolver().releasePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                // The permission is gone already
            }
            mFileSystems.remove(TerminowSafFs.idFor(uri));
            removeAt("folders", index);
        });
    }

    /** Handle the folder picker. Returns {@code true} if it was for the File Manager. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_ADD_FOLDER && requestCode != REQUEST_REPICK_FOLDER) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            mActivity.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            toast(mActivity.getString(R.string.terminow_explorer_not_kept));
        }
        String name = uri.getLastPathSegment();
        try (android.database.Cursor cursor = mActivity.getContentResolver().query(
            android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri)),
            new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        } catch (RuntimeException e) {
            // Keep the name from the URI
        }
        JSONObject state = mStore.getExplorer();
        JSONArray folders = state.optJSONArray("folders");
        if (folders == null) folders = new JSONArray();
        try {
            JSONObject folder = new JSONObject().put("uri", uri.toString()).put("name", name != null ? name : "Folder");
            if (requestCode == REQUEST_REPICK_FOLDER && mRepickIndex >= 0 && mRepickIndex < folders.length()) {
                folders.put(mRepickIndex, folder);
            } else {
                for (int i = 0; i < folders.length(); i++)
                    if (uri.toString().equals(folders.optJSONObject(i).optString("uri"))) {
                        render();
                        return true;
                    }
                folders.put(folder);
            }
            state.put("folders", folders);
        } catch (JSONException e) {
            return true;
        }
        mStore.setExplorer(state);
        mFileSystems.remove(TerminowSafFs.idFor(uri));
        mRepickIndex = -1;
        render();
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------

    private void load(@NonNull Pane pane) {
        TerminowFs fs = pane.fs;
        TerminowFs.Node directory = pane.current();
        if (fs == null || directory == null) return;
        if (pane.cancel != null) pane.cancel.cancel();
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        pane.cancel = cancel;
        int generation = ++pane.generation;
        pane.loading = true;
        pane.error = null;
        render();
        mExecutor.execute(() -> {
            List<TerminowFs.Node> entries = null;
            String error = null;
            try {
                entries = fs.list(directory, cancel);
            } catch (TerminowFs.CancelledException e) {
                return;
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            List<TerminowFs.Node> finalEntries = entries;
            String finalError = error;
            mHandler.post(() -> {
                if (pane.generation != generation) return;
                pane.loading = false;
                pane.error = finalError;
                pane.entries = finalEntries != null ? finalEntries : new ArrayList<>();
                pane.selection.clear();
                render();
            });
        });
    }

    /** How a folder is shown: Grid, Compact or List, the icon size, and the order. */
    private static class ViewPrefs {
        String mode = "list";
        String size = "medium";
        String sort = "name";
        boolean descending;
        /** The folder has its own view, not the one of all folders. */
        boolean own;
    }

    @NonNull
    private static String folderKey(@NonNull Pane pane) {
        return pane.fs.getId() + "|" + pane.current().id;
    }

    /** The view of the folder of a pane: its own if it has one, else the one of all folders. */
    @NonNull
    private ViewPrefs viewOf(@Nullable Pane pane) {
        JSONObject state = mStore.getExplorer();
        ViewPrefs view = new ViewPrefs();
        view.mode = state.optString("viewMode", state.optBoolean("grid") ? "grid" : "list");
        view.size = state.optString("iconSize", "medium");
        view.sort = state.optString("sort", "name");
        view.descending = state.optBoolean("descending");
        if (pane != null && pane.fs != null && !pane.crumbs.isEmpty()) {
            JSONObject folders = state.optJSONObject("folderViews");
            JSONObject own = folders != null ? folders.optJSONObject(folderKey(pane)) : null;
            if (own != null) {
                view.own = true;
                view.mode = own.optString("mode", view.mode);
                view.size = own.optString("size", view.size);
                view.sort = own.optString("sort", view.sort);
                view.descending = own.optBoolean("descending", view.descending);
            }
        }
        return view;
    }

    /** Keep a view for the folder only, or for all folders, which then drops the folder's own. */
    private void saveView(@Nullable Pane pane, @NonNull ViewPrefs view, boolean allFolders) {
        JSONObject state = mStore.getExplorer();
        try {
            JSONObject folders = state.optJSONObject("folderViews");
            if (folders == null) folders = new JSONObject();
            if (allFolders || pane == null || pane.fs == null || pane.crumbs.isEmpty()) {
                state.put("viewMode", view.mode).put("grid", view.mode.equals("grid")).put("iconSize", view.size)
                    .put("sort", view.sort).put("descending", view.descending);
                if (pane != null && pane.fs != null && !pane.crumbs.isEmpty()) folders.remove(folderKey(pane));
            } else {
                folders.put(folderKey(pane), new JSONObject().put("mode", view.mode).put("size", view.size)
                    .put("sort", view.sort).put("descending", view.descending));
                // A few hundred folders at most, the oldest go
                while (folders.length() > 300) folders.remove(folders.keys().next());
            }
            state.put("folderViews", folders);
        } catch (JSONException e) {
            return;
        }
        mStore.setExplorer(state);
        render();
    }

    @NonNull
    private List<TerminowFs.Node> visibleEntries(@NonNull Pane pane) {
        JSONObject state = mStore.getExplorer();
        ViewPrefs view = viewOf(pane);
        boolean hidden = state.optBoolean("showHidden");
        String sort = view.sort;
        boolean descending = view.descending;
        String query = pane.query.toLowerCase(Locale.ROOT);
        List<TerminowFs.Node> list = new ArrayList<>();
        for (TerminowFs.Node node : pane.entries) {
            if (!hidden && node.isHidden()) continue;
            if (!query.isEmpty() && !node.name.toLowerCase(Locale.ROOT).contains(query)) continue;
            list.add(node);
        }
        Comparator<TerminowFs.Node> comparator;
        switch (sort) {
            case "size": comparator = (a, b) -> Long.compare(a.size, b.size); break;
            case "modified": comparator = (a, b) -> Long.compare(a.modified, b.modified); break;
            case "type": comparator = (a, b) -> TerminowFileScan.extension(a.name).compareTo(TerminowFileScan.extension(b.name)); break;
            default: comparator = (a, b) -> 0;
        }
        Comparator<TerminowFs.Node> order = comparator.thenComparing((a, b) -> a.name.compareToIgnoreCase(b.name));
        if (descending) order = order.reversed();
        Comparator<TerminowFs.Node> foldersFirst = (a, b) -> a.directory == b.directory ? 0 : a.directory ? -1 : 1;
        Collections.sort(list, foldersFirst.thenComparing(order));
        return list;
    }

    private void toggleTwoPanes() {
        mTwoPanes = !mTwoPanes;
        Pane pane = mPanes[mActivePane];
        Pane other = mPanes[1 - mActivePane];
        if (mTwoPanes && other.fs == null && pane.fs != null) {
            other.fs = pane.fs;
            other.crumbs.clear();
            other.crumbs.addAll(pane.crumbs);
            load(other);
        }
        render();
    }

    @NonNull
    private View browsers() {
        if (widthDp() < 900) mTwoPanes = false;
        Pane active = mPanes[mActivePane];
        LinearLayout content = vertical();
        if (!mTwoPanes) {
            content.addView(paneView(mActivePane), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        } else {
            // Where a transfer goes from and to, always in view
            Pane other = mPanes[1 - mActivePane];
            TextView route = text(mActivity.getString(R.string.terminow_explorer_route,
                active.fs != null ? active.fs.getDisplayPath(active.crumbs) : "—",
                other.fs != null ? other.fs.getDisplayPath(other.crumbs) : "—"), 12, mPalette.secondary);
            route.setSingleLine(true);
            route.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            route.setPadding(dp(4), 0, 0, dp(6));
            content.addView(route, matchWrap());
            LinearLayout row = new LinearLayout(mActivity);
            for (int i = 0; i < 2; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if (i == 0) params.setMarginEnd(dp(8));
                row.addView(paneView(i), params);
            }
            content.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        if (!active.selection.isEmpty() && active.fs != null) content.addView(actionBar(mActivePane, active), matchWrap());
        return content;
    }

    @NonNull
    private View paneView(int index) {
        Pane pane = mPanes[index];
        LinearLayout view = vertical();
        boolean active = index == mActivePane;
        if (mTwoPanes) {
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(dp(14));
            border.setStroke(dp(active ? 2 : 1), active ? mPalette.accent : mPalette.divider);
            view.setBackground(border);
            view.setPadding(dp(6), dp(6), dp(6), dp(6));
            TextView role = text(mActivity.getString(active ? R.string.terminow_explorer_pane_source : R.string.terminow_explorer_pane_destination),
                11, active ? mPalette.accent : mPalette.secondary);
            role.setTypeface(Typeface.DEFAULT_BOLD);
            view.addView(role);
        }
        view.setOnClickListener(v -> setActive(index));
        if (pane.fs == null) {
            TextView empty = text(mActivity.getString(R.string.terminow_explorer_pick_location), 14, mPalette.secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(40), dp(16), dp(16));
            empty.setOnClickListener(v -> {
                setActive(index);
                if (isLarge()) mSidebarCollapsed = false;
                else mDrawerOpen = true;
                render();
            });
            view.addView(empty, matchWrap());
            return view;
        }
        TerminowFs fs = pane.fs;

        // Breadcrumbs
        HorizontalScrollView crumbScroll = new HorizontalScrollView(mActivity);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout crumbs = new LinearLayout(mActivity);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < pane.crumbs.size(); i++) {
            int depth = i;
            if (i > 0) crumbs.addView(text("  ›  ", 14, mPalette.secondary));
            TextView crumb = text(i == 0 ? fs.getTitle() : pane.crumbs.get(i).name, 14, i == pane.crumbs.size() - 1 ? mPalette.text : mPalette.secondary);
            if (i == pane.crumbs.size() - 1) crumb.setTypeface(Typeface.DEFAULT_BOLD);
            crumb.setPadding(dp(4), dp(10), dp(4), dp(10));
            crumb.setOnClickListener(v -> {
                setActive(index);
                while (pane.crumbs.size() > depth + 1) pane.crumbs.remove(pane.crumbs.size() - 1);
                load(pane);
            });
            crumbs.addView(crumb);
        }
        crumbScroll.addView(crumbs);
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
        LinearLayout crumbRow = new LinearLayout(mActivity);
        crumbRow.setGravity(Gravity.CENTER_VERTICAL);
        crumbRow.addView(crumbScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        View usage = storageChip(fs, pane);
        if (usage != null) crumbRow.addView(usage);
        view.addView(crumbRow, matchWrap());

        // Up, sort and view
        JSONObject prefs = mStore.getExplorer();
        LinearLayout tools = new LinearLayout(mActivity);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(0, 0, 0, dp(4));
        if (fs instanceof TerminowLinuxFs) addLinuxShortcuts(tools, (TerminowLinuxFs) fs);
        TextView up = chip("↑ " + mActivity.getString(R.string.terminow_explorer_up), false);
        up.setOnClickListener(v -> {
            setActive(index);
            if (pane.crumbs.size() > 1) {
                pane.crumbs.remove(pane.crumbs.size() - 1);
                load(pane);
            }
        });
        up.setAlpha(pane.crumbs.size() > 1 ? 1f : 0.4f);
        tools.addView(up, chipParams());
        ViewPrefs paneView = viewOf(pane);
        TextView viewChip = chip("☷ " + mActivity.getString(R.string.terminow_explorer_view) + " · " + sortLabel(paneView.sort) +
            (paneView.descending ? " ↓" : " ↑"), false);
        viewChip.setOnClickListener(v -> {
            setActive(index);
            showViewPanel(pane, v);
        });
        tools.addView(viewChip, chipParams());
        if (!fs.getCapabilities().write) {
            TextView readOnly = chip(mActivity.getString(R.string.terminow_explorer_read_only), false);
            readOnly.setOnClickListener(v -> toast(fs.getCapabilities().readOnlyReason));
            tools.addView(readOnly, chipParams());
        }
        HorizontalScrollView toolScroll = new HorizontalScrollView(mActivity);
        toolScroll.setHorizontalScrollBarEnabled(false);
        toolScroll.addView(tools);
        view.addView(toolScroll, matchWrap());

        if (pane.searching && active) {
            EditText search = new EditText(mActivity);
            search.setHint(R.string.terminow_explorer_search_hint);
            search.setSingleLine(true);
            search.setTextColor(mPalette.text);
            search.setHintTextColor(mPalette.secondary);
            search.setBackground(rounded(mPalette.field, 20));
            search.setPadding(dp(16), dp(8), dp(16), dp(8));
            search.setText(pane.query);
            search.setSelection(pane.query.length());
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    pane.query = s.toString();
                    if (mListAdapter != null) mListAdapter.notifyDataSetChanged();
                }
            });
            view.addView(search, matchWrap());
            search.requestFocus();
        }
        if (mClip != null) view.addView(clipRow(pane), matchWrap());

        if (pane.loading) {
            ProgressBar progress = new ProgressBar(mActivity);
            progress.setIndeterminateTintList(ColorStateList.valueOf(mPalette.accent));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(40));
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(40);
            view.addView(progress, params);
            return view;
        }
        if (pane.error != null) {
            view.addView(errorCard(pane), matchWrap());
            return view;
        }

        if (visibleEntries(pane).isEmpty()) {
            view.addView(emptyText(pane.query.isEmpty() ? R.string.terminow_explorer_empty_folder : R.string.terminow_no_results), matchWrap());
            return view;
        }
        GridView list = gridView(paneView);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return visibleEntries(pane).size(); }
            @Override public Object getItem(int position) { return visibleEntries(pane).get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TerminowFs.Node node = visibleEntries(pane).get(position);
                return entryView(fs, node, pane.selection.contains(node.id), paneView, null);
            }
        };
        if (active) mListAdapter = adapter;
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> {
            TerminowFs.Node node = visibleEntries(pane).get(position);
            if (index != mActivePane) {
                setActive(index);
                return;
            }
            if (!pane.selection.isEmpty()) {
                toggle(pane, node);
            } else if (node.directory) {
                pane.crumbs.add(node);
                load(pane);
            } else if (node.link && fs instanceof TerminowLocalFs) {
                toast(mActivity.getString(R.string.terminow_explorer_link_outside));
            } else {
                openFile(fs, node, fs.getDisplayPath(pane.crumbs));
            }
        });
        list.setOnItemLongClickListener((parent, v, position, id) -> {
            mActivePane = index;
            toggle(pane, visibleEntries(pane).get(position));
            return true;
        });
        view.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return view;
    }

    /** What went wrong, and the action that can fix it. */
    @NonNull
    private View errorCard(@NonNull Pane pane) {
        TerminowFs fs = pane.fs;
        LinearLayout card = card();
        String title;
        String message = pane.error;
        TextView action;
        if (fs instanceof TerminowDeviceFs && pane.error != null && pane.error.contains("protected by Android")) {
            title = mActivity.getString(R.string.terminow_explorer_error_protected);
            action = button(mActivity.getString(R.string.terminow_explorer_up), true);
            action.setOnClickListener(v -> {
                if (pane.crumbs.size() > 1) pane.crumbs.remove(pane.crumbs.size() - 1);
                load(pane);
            });
        } else if (fs instanceof TerminowDeviceFs && !TerminowStorageAccess.hasAccess(mActivity)) {
            title = mActivity.getString(R.string.terminow_explorer_error_no_access);
            message = mActivity.getString(R.string.terminow_explorer_access_message);
            action = button(mActivity.getString(R.string.terminow_explorer_access_open), true);
            action.setOnClickListener(v -> TerminowStorageAccess.request(mActivity));
        } else if (fs instanceof TerminowSafFs && !((TerminowSafFs) fs).hasAccess()) {
            title = mActivity.getString(R.string.terminow_explorer_error_access);
            message = mActivity.getString(R.string.terminow_explorer_access_lost_message);
            action = button(mActivity.getString(R.string.terminow_explorer_choose_again), true);
            action.setOnClickListener(v -> {
                JSONArray folders = mStore.getExplorer().optJSONArray("folders");
                for (int i = 0; folders != null && i < folders.length(); i++)
                    if (TerminowSafFs.idFor(Uri.parse(folders.optJSONObject(i).optString("uri"))).equals(fs.getId())) repickFolder(i);
            });
        } else if (fs instanceof TerminowSftpFs && !((TerminowSftpFs) fs).isConnected()) {
            title = mActivity.getString(R.string.terminow_explorer_error_disconnected, fs.getTitle());
            action = button(mActivity.getString(R.string.terminow_explorer_reconnect), true);
            action.setOnClickListener(v -> background(() -> {
                ((TerminowSftpFs) fs).connect();
                return true;
            }, ok -> load(pane)));
        } else if (pane.error != null && (pane.error.toLowerCase(Locale.ROOT).contains("permission") || pane.error.contains("denied access"))) {
            title = mActivity.getString(R.string.terminow_explorer_error_permission);
            action = button(mActivity.getString(R.string.terminow_retry), true);
            action.setOnClickListener(v -> load(pane));
        } else {
            title = mActivity.getString(R.string.terminow_explorer_error_title);
            action = button(mActivity.getString(R.string.terminow_retry), true);
            action.setOnClickListener(v -> load(pane));
        }
        TextView titleView = text(title, 15, mPalette.text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleView, matchWrap());
        if (message != null) card.addView(text(message, 13, mPalette.secondary), matchWrap());
        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setPadding(0, dp(12), 0, 0);
        buttons.addView(action, buttonParams());
        TextView home = button(mActivity.getString(R.string.terminow_explorer_dashboard), false);
        home.setOnClickListener(v -> go(Screen.DASHBOARD));
        buttons.addView(home, buttonParams());
        card.addView(buttons);
        return card;
    }

    @NonNull
    private GridView gridView(@NonNull ViewPrefs view) {
        boolean grid = view.mode.equals("grid");
        GridView list = new GridView(mActivity);
        list.setNumColumns(grid ? GridView.AUTO_FIT : 1);
        list.setColumnWidth(dp(view.size.equals("big") ? 132 : view.size.equals("small") ? 84 : 106));
        list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        list.setVerticalSpacing(dp(6));
        list.setHorizontalSpacing(dp(6));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(24));
        return list;
    }

    private void setActive(int index) {
        if (mActivePane == index) return;
        mActivePane = index;
        render();
    }

    private void toggle(@NonNull Pane pane, @NonNull TerminowFs.Node node) {
        if (!pane.selection.remove(node.id)) pane.selection.add(node.id);
        render();
    }

    @NonNull
    private List<TerminowFs.Node> selected(@NonNull Pane pane) {
        List<TerminowFs.Node> nodes = new ArrayList<>();
        for (TerminowFs.Node node : pane.entries) if (pane.selection.contains(node.id)) nodes.add(node);
        return nodes;
    }

    /** Copy, Move, Rename, Delete, Share and More, for the selected items. */
    @NonNull
    private View actionBar(int index, @NonNull Pane pane) {
        TerminowFs fs = pane.fs;
        TerminowFs.Capabilities capabilities = fs.getCapabilities();
        List<TerminowFs.Node> nodes = selected(pane);
        LinearLayout bar = vertical();
        bar.setBackground(rounded(mPalette.card, 16));
        bar.setPadding(dp(6), dp(4), dp(6), dp(4));
        LinearLayout head = new LinearLayout(mActivity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(2), 0, 0);
        TextView count = text(mActivity.getString(R.string.terminow_explorer_selected, nodes.size()), 13, mPalette.text);
        count.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView all = chip(mActivity.getString(R.string.terminow_explorer_select_all), false);
        all.setOnClickListener(v -> {
            for (TerminowFs.Node node : visibleEntries(pane)) pane.selection.add(node.id);
            render();
        });
        head.addView(all, chipParams());
        TextView close = chip("✕", false);
        close.setContentDescription(mActivity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> {
            pane.selection.clear();
            render();
        });
        head.addView(close, chipParams());
        bar.addView(head, matchWrap());

        LinearLayout actions = new LinearLayout(mActivity);
        String path = fs.getDisplayPath(pane.crumbs);
        Pane other = mPanes[1 - index];
        boolean toOther = mTwoPanes && other.fs != null;
        action(actions, R.string.terminow_explorer_copy, true, null, () -> {
            if (toOther) queueTransfer(fs, pane.current(), nodes, path, other.fs, other.current(), other.fs.getDisplayPath(other.crumbs), false);
            else {
                mClip = new Clip(fs, pane.current(), nodes, false, path);
                pane.selection.clear();
                toast(mActivity.getString(R.string.terminow_explorer_copied_hint));
                render();
            }
        });
        action(actions, R.string.terminow_explorer_move, capabilities.delete, capabilities.readOnlyReason, () -> {
            if (toOther) queueTransfer(fs, pane.current(), nodes, path, other.fs, other.current(), other.fs.getDisplayPath(other.crumbs), true);
            else {
                mClip = new Clip(fs, pane.current(), nodes, true, path);
                pane.selection.clear();
                toast(mActivity.getString(R.string.terminow_explorer_moved_hint));
                render();
            }
        });
        action(actions, R.string.terminow_explorer_rename, capabilities.rename && nodes.size() == 1,
            nodes.size() != 1 ? mActivity.getString(R.string.terminow_explorer_one_only) : capabilities.readOnlyReason,
            () -> rename(pane, nodes.get(0)));
        action(actions, R.string.terminow_delete, capabilities.delete, capabilities.readOnlyReason, () -> delete(pane, nodes));
        action(actions, R.string.terminow_explorer_share, true, null, () -> share(fs, nodes, path));
        action(actions, R.string.terminow_explorer_more, true, null, () -> {
            List<CharSequence> labels = new ArrayList<>();
            List<Runnable> handlers = new ArrayList<>();
            labels.add(mActivity.getString(R.string.terminow_explorer_properties));
            handlers.add(() -> {
                if (nodes.size() == 1) showProperties(fs, nodes.get(0), path);
                else toast(mActivity.getString(R.string.terminow_explorer_one_only));
            });
            if (!(fs instanceof TerminowLocalFs)) {
                labels.add(mActivity.getString(R.string.terminow_explorer_to_home));
                handlers.add(() -> copyToHome(fs, pane.current(), nodes));
            }
            if (nodes.size() == 1 && nodes.get(0).directory && fs instanceof TerminowLocalFs) {
                labels.add(mActivity.getString(R.string.terminow_explorer_open_terminal));
                handlers.add(() -> openInTerminal(fs, nodes.get(0)));
            }
            if (toOther) {
                labels.add(mActivity.getString(R.string.terminow_explorer_copy_clipboard));
                handlers.add(() -> {
                    mClip = new Clip(fs, pane.current(), nodes, false, path);
                    pane.selection.clear();
                    render();
                });
            }
            dialog().setTitle(mActivity.getString(R.string.terminow_explorer_selected, nodes.size()))
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> handlers.get(which).run()).show();
        });
        bar.addView(actions, matchWrap());
        LinearLayout wrapper = vertical();
        wrapper.setPadding(0, dp(6), dp(4), dp(6));
        wrapper.addView(bar, matchWrap());
        return wrapper;
    }

    /** An action of the action bar, which explains why when it can not be used. */
    private void action(@NonNull LinearLayout bar, int labelRes, boolean enabled, @Nullable String reason, @NonNull Runnable run) {
        TextView button = text(mActivity.getString(labelRes), 13, enabled ? mPalette.text : mPalette.secondary);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setBackground(ripple());
        button.setAlpha(enabled ? 1f : 0.5f);
        button.setOnClickListener(v -> {
            if (enabled) run.run();
            else toast(reason != null ? reason : mActivity.getString(R.string.terminow_explorer_not_supported));
        });
        bar.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    @NonNull
    private View clipRow(@NonNull Pane pane) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(6), dp(6));
        row.setBackground(rounded(mPalette.card, 12));
        Clip clip = mClip;
        TextView label = text(mActivity.getResources().getQuantityString(clip.move ? R.plurals.terminow_explorer_clip_move :
            R.plurals.terminow_explorer_clip_copy, clip.nodes.size(), clip.nodes.size(), clip.from), 13, mPalette.text);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        boolean writable = pane.fs != null && pane.fs.getCapabilities().write;
        TextView paste = button(mActivity.getString(R.string.terminow_explorer_paste), writable);
        paste.setAlpha(writable ? 1f : 0.5f);
        paste.setOnClickListener(v -> {
            if (!writable) {
                toast(pane.fs != null ? pane.fs.getCapabilities().readOnlyReason : null);
                return;
            }
            mClip = null;
            queueTransfer(clip.fs, clip.parent, clip.nodes, clip.from, pane.fs, pane.current(), pane.fs.getDisplayPath(pane.crumbs), clip.move);
        });
        row.addView(paste, buttonParams());
        TextView cancel = chip("✕", false);
        cancel.setContentDescription(mActivity.getString(android.R.string.cancel));
        cancel.setOnClickListener(v -> {
            mClip = null;
            render();
        });
        row.addView(cancel, chipParams());
        LinearLayout wrapper = vertical();
        wrapper.setPadding(0, dp(4), 0, dp(4));
        wrapper.addView(row, matchWrap());
        return wrapper;
    }

    @NonNull
    private String sortLabel(@NonNull String sort) {
        switch (sort) {
            case "size": return mActivity.getString(R.string.terminow_explorer_sort_size);
            case "modified": return mActivity.getString(R.string.terminow_explorer_sort_modified);
            case "type": return mActivity.getString(R.string.terminow_explorer_sort_type);
            default: return mActivity.getString(R.string.terminow_explorer_sort_name);
        }
    }

    private void showSortDialog() {
        String[] sorts = {"name", "size", "modified", "type"};
        CharSequence[] labels = new CharSequence[sorts.length + 1];
        for (int i = 0; i < sorts.length; i++) labels[i] = sortLabel(sorts[i]);
        labels[sorts.length] = mActivity.getString(R.string.terminow_explorer_sort_reverse);
        dialog().setTitle(R.string.terminow_explorer_sort_title)
            .setItems(labels, (d, which) -> {
                if (which == sorts.length) setView("descending", !mStore.getExplorer().optBoolean("descending"));
                else setView("sort", sorts[which]);
            }).show();
    }

    private void setView(@NonNull String key, @NonNull Object value) {
        JSONObject state = mStore.getExplorer();
        try {
            state.put(key, value);
        } catch (JSONException e) {
            return;
        }
        mStore.setExplorer(state);
        render();
    }

    // ---------------------------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------------------------

    private void createFolder(@NonNull Pane pane) {
        askName(R.string.terminow_explorer_new_folder, "", name -> background(() -> pane.fs.createDirectory(pane.current(), name), node -> load(pane)));
    }

    private void rename(@NonNull Pane pane, @NonNull TerminowFs.Node node) {
        askName(R.string.terminow_explorer_rename, node.name, name -> {
            if (name.equals(node.name)) return;
            background(() -> pane.fs.rename(node, pane.current(), name), renamed -> reloadShowing(pane.fs));
        });
    }

    private void delete(@NonNull Pane pane, @NonNull List<TerminowFs.Node> nodes) {
        String what = nodes.size() == 1 ? "\"" + nodes.get(0).name + "\"" :
            mActivity.getResources().getQuantityString(R.plurals.terminow_explorer_items, nodes.size(), nodes.size());
        dialog()
            .setTitle(R.string.terminow_explorer_delete_title)
            .setMessage(mActivity.getString(R.string.terminow_explorer_delete_confirm, what, pane.fs.getTitle()))
            .setPositiveButton(R.string.terminow_explorer_delete_permanently, (d, w) -> {
                TerminowFs fs = pane.fs;
                TerminowFs.Node parent = pane.current();
                Progress progress = new Progress(mActivity.getString(R.string.terminow_explorer_deleting));
                mExecutor.execute(() -> {
                    String error = null;
                    for (TerminowFs.Node node : nodes) {
                        try {
                            progress.update(node.name);
                            fs.delete(node, parent, progress.cancel);
                        } catch (TerminowFs.CancelledException e) {
                            break;
                        } catch (IOException | RuntimeException e) {
                            error = node.name + ": " + e.getMessage();
                        }
                    }
                    String finalError = error;
                    mHandler.post(() -> {
                        progress.dismiss();
                        if (finalError != null) showMessage(mActivity.getString(R.string.terminow_explorer_failed_title), finalError);
                        reloadShowing(fs);
                        rescanIfShown();
                    });
                });
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void copyToHome(@NonNull TerminowFs fs, @NonNull TerminowFs.Node parent, @NonNull List<TerminowFs.Node> nodes) {
        background(() -> {
            TerminowLocalFs home = getHomeFs();
            TerminowFs.Node imports = home.find(home.getRoot(), IMPORTS_DIR);
            if (imports == null) imports = home.createDirectory(home.getRoot(), IMPORTS_DIR);
            return imports;
        }, imports -> {
            try {
                queueTransfer(fs, parent, nodes, fs.getTitle(), getHomeFs(), imports, "~/" + IMPORTS_DIR, false);
            } catch (IOException e) {
                toast(e.getMessage());
            }
        });
    }

    /** Add a copy or move to the transfer queue, which asks about conflicts when they happen. */
    private void queueTransfer(@NonNull TerminowFs source, @NonNull TerminowFs.Node sourceParent, @NonNull List<TerminowFs.Node> nodes,
                               @NonNull String sourceTitle, @NonNull TerminowFs target, @NonNull TerminowFs.Node targetDirectory,
                               @NonNull String targetTitle, boolean move) {
        if (!target.getCapabilities().write) {
            toast(target.getCapabilities().readOnlyReason);
            return;
        }
        for (Pane pane : mPanes) pane.selection.clear();
        final TerminowTransfer.Resolution[] all = {null};
        mQueue.add(source, sourceParent, nodes, sourceTitle, target, targetDirectory, targetTitle, move, (job, name) -> {
            if (all[0] != null) return all[0];
            AtomicReference<TerminowTransfer.Resolution> answer = new AtomicReference<>(TerminowTransfer.Resolution.SKIP);
            AtomicReference<Boolean> applyAll = new AtomicReference<>(false);
            waitForUi(latch -> askConflict(name, job, answer, applyAll, latch));
            if (applyAll.get()) all[0] = answer.get();
            return answer.get();
        });
        toast(mActivity.getString(R.string.terminow_explorer_queued));
        render();
    }

    private void askConflict(@NonNull String name, @NonNull TerminowTransferQueue.Job job, @NonNull AtomicReference<TerminowTransfer.Resolution> answer,
                             @NonNull AtomicReference<Boolean> all, @NonNull CountDownLatch latch) {
        CheckBox applyAll = new CheckBox(mActivity);
        applyAll.setText(R.string.terminow_explorer_apply_all);
        applyAll.setTextColor(mPalette.text);
        applyAll.setButtonTintList(ColorStateList.valueOf(mPalette.accent));
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(applyAll);
        dialog()
            .setTitle(R.string.terminow_explorer_conflict_title)
            .setMessage(mActivity.getString(R.string.terminow_explorer_conflict_message, name, job.destinationTitle))
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(R.string.terminow_explorer_keep_both, (d, w) -> {
                answer.set(TerminowTransfer.Resolution.KEEP_BOTH);
                all.set(applyAll.isChecked());
                latch.countDown();
            })
            .setNeutralButton(R.string.terminow_explorer_replace, (d, w) -> {
                answer.set(TerminowTransfer.Resolution.REPLACE);
                all.set(applyAll.isChecked());
                latch.countDown();
            })
            .setNegativeButton(R.string.terminow_explorer_skip, (d, w) -> {
                answer.set(TerminowTransfer.Resolution.SKIP);
                all.set(applyAll.isChecked());
                latch.countDown();
            })
            .show();
    }

    /** Search results are a snapshot, so they are run again after files changed. */
    private void rescanIfShown() {
        if (mVisible && (mScreen == Screen.CATEGORY || mScreen == Screen.SEARCH) && mScan.filter != null && !mScan.running) runScan(mScan.filter);
    }

    private void reloadShowing(@NonNull TerminowFs fs) {
        for (Pane pane : mPanes) if (pane.fs == fs && mScreen == Screen.BROWSER) load(pane);
    }

    private void bookmark(@NonNull Pane pane) {
        TerminowFs.Node current = pane.current();
        if (pane.fs == null || current == null) return;
        JSONObject state = mStore.getExplorer();
        JSONArray bookmarks = state.optJSONArray("bookmarks");
        if (bookmarks == null) bookmarks = new JSONArray();
        try {
            bookmarks.put(new JSONObject().put("fs", pane.fs.getId()).put("fsTitle", pane.fs.getTitle())
                .put("name", pane.crumbs.size() > 1 ? current.name : pane.fs.getTitle())
                .put("path", pane.fs.getDisplayPath(pane.crumbs)).put("crumbs", crumbsToJson(pane.crumbs)));
            state.put("bookmarks", bookmarks);
        } catch (JSONException e) {
            return;
        }
        mStore.setExplorer(state);
        toast(mActivity.getString(R.string.terminow_explorer_bookmarked));
    }

    /** Open a real folder of HOME in a new terminal. Other folders can not be a working directory. */
    private void openInTerminal(@Nullable TerminowFs fs, @Nullable TerminowFs.Node directory) {
        if (fs == null || directory == null) return;
        if (fs instanceof TerminowLinuxFs) {
            // The environment and its default user, in the Linux folder
            TerminowLinuxEnv env = ((TerminowLinuxFs) fs).getEnv();
            mHost.openLinuxTerminal(env.alias, env.defaultUser, directory.directory ? directory.id : TerminowLinuxPaths.parent(directory.id));
            return;
        }
        if (!(fs instanceof TerminowLocalFs)) {
            showMessage(mActivity.getString(R.string.terminow_explorer_open_terminal),
                mActivity.getString(fs instanceof TerminowSafFs ? R.string.terminow_explorer_terminal_saf : R.string.terminow_explorer_terminal_sftp));
            return;
        }
        try {
            TerminowLocalFs home = (TerminowLocalFs) fs;
            File dir = home.toFile(directory.id);
            if (!dir.isDirectory() || !home.isInsideHome(dir)) throw new IOException(mActivity.getString(R.string.terminow_explorer_link_outside));
            mHost.openTerminalAt(dir);
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    /** The files of ~/.ssh are keys and credentials, which are not given to other apps. */
    private static boolean isSensitive(@NonNull TerminowFs fs, @NonNull TerminowFs.Node node) {
        return TerminowLocalFs.ID.equals(fs.getId()) && (node.id.equals(".ssh") || node.id.startsWith(".ssh/"));
    }

    private void openFile(@NonNull TerminowFs fs, @NonNull TerminowFs.Node file, @NonNull String path) {
        if (isSensitive(fs, file)) {
            showMessage(file.name, mActivity.getString(R.string.terminow_explorer_sensitive));
            return;
        }
        recordRecent(fs, file, path);
        background(() -> shareableUri(fs, file), uri -> {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType(file));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                mActivity.startActivity(Intent.createChooser(intent, file.name));
            } catch (ActivityNotFoundException e) {
                toast(mActivity.getString(R.string.terminow_explorer_no_app));
            }
        });
    }

    private void share(@NonNull TerminowFs fs, @NonNull List<TerminowFs.Node> nodes, @NonNull String path) {
        for (TerminowFs.Node node : nodes) {
            if (node.directory) {
                toast(mActivity.getString(R.string.terminow_explorer_share_folders));
                return;
            }
            if (isSensitive(fs, node)) {
                showMessage(node.name, mActivity.getString(R.string.terminow_explorer_sensitive));
                return;
            }
        }
        background(() -> {
            ArrayList<Uri> uris = new ArrayList<>();
            for (TerminowFs.Node node : nodes) uris.add(shareableUri(fs, node));
            return uris;
        }, uris -> {
            Intent intent;
            if (uris.size() == 1) {
                intent = new Intent(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                intent.setType(mimeType(nodes.get(0)));
            } else {
                intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                intent.setType("*/*");
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            for (TerminowFs.Node node : nodes) recordRecent(fs, node, path);
            try {
                mActivity.startActivity(Intent.createChooser(intent, null));
            } catch (ActivityNotFoundException e) {
                toast(mActivity.getString(R.string.terminow_explorer_no_app));
            }
        });
    }

    /**
     * A content URI that another app may read: the document URI of a device folder, or a
     * FileProvider URI of a HOME file. A remote file is downloaded to the cache first.
     */
    @NonNull
    private Uri shareableUri(@NonNull TerminowFs fs, @NonNull TerminowFs.Node file) throws IOException {
        String authority = mActivity.getPackageName() + ".explorer";
        if (fs instanceof TerminowSafFs) return Uri.parse(file.id);
        if (fs instanceof TerminowLocalFs) {
            File local = ((TerminowLocalFs) fs).toFile(file.id);
            if (!((TerminowLocalFs) fs).isInsideHome(local)) throw new IOException(mActivity.getString(R.string.terminow_explorer_link_outside));
            return FileProvider.getUriForFile(mActivity, authority, local);
        }
        File dir = new File(mActivity.getCacheDir(), "explorer/" + UUID.randomUUID());
        if (!dir.mkdirs()) throw new IOException("Failed to prepare the download");
        TerminowLocalFs cache = new TerminowLocalFs(dir, "cache");
        TerminowTransfer.Result result = new TerminowTransfer(fs, cache, false, new TerminowTransfer.Listener() {
            @Override
            public void onProgress(@NonNull String name, long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
            }

            @NonNull
            @Override
            public TerminowTransfer.Resolution onConflict(@NonNull String name) {
                return TerminowTransfer.Resolution.REPLACE;
            }
        }, new TerminowFs.Cancel()).run(new TerminowFs.Node("", "", true, false, -1, 0, null), Collections.singletonList(file), cache.getRoot());
        File downloaded = new File(dir, file.name);
        if (!result.errors.isEmpty() || !downloaded.isFile()) throw new IOException(TerminowTransfer.describe(result));
        return FileProvider.getUriForFile(mActivity, authority, downloaded);
    }

    @NonNull
    private static String mimeType(@NonNull TerminowFs.Node node) {
        if (node.mimeType != null && !node.mimeType.isEmpty()) return node.mimeType;
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(TerminowFileScan.extension(node.name));
        return type != null ? type : "*/*";
    }

    private void recordRecent(@NonNull TerminowFs fs, @NonNull TerminowFs.Node file, @NonNull String path) {
        try {
            mStore.addExplorerRecent(new JSONObject().put("fs", fs.getId()).put("fsTitle", fs.getTitle()).put("id", file.id)
                .put("name", file.name).put("path", path).put("time", System.currentTimeMillis()));
        } catch (JSONException e) {
            // Not recorded
        }
    }

    private void showProperties(@NonNull TerminowFs fs, @Nullable TerminowFs.Node node, @NonNull String path) {
        if (node == null) return;
        StringBuilder text = new StringBuilder();
        text.append(mActivity.getString(R.string.terminow_explorer_prop_name, node.name)).append('\n');
        text.append(mActivity.getString(R.string.terminow_explorer_prop_type, node.link ? mActivity.getString(R.string.terminow_explorer_type_link) :
            node.directory ? mActivity.getString(R.string.terminow_explorer_type_folder) : mimeType(node))).append('\n');
        if (!node.directory && node.size >= 0)
            text.append(mActivity.getString(R.string.terminow_explorer_prop_size, Formatter.formatFileSize(mActivity, node.size), node.size)).append('\n');
        if (node.modified > 0)
            text.append(mActivity.getString(R.string.terminow_explorer_prop_modified,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(node.modified)))).append('\n');
        text.append(mActivity.getString(R.string.terminow_explorer_prop_location, fs.getTitle(), path)).append('\n');
        if (fs instanceof TerminowSafFs) text.append(mActivity.getString(R.string.terminow_explorer_prop_saf));
        if (fs instanceof TerminowLocalFs) text.append(mActivity.getString(R.string.terminow_explorer_prop_home));
        AlertDialog dialog = dialog()
            .setTitle(R.string.terminow_explorer_properties)
            .setMessage(text.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show();
        if (!node.directory || node.link) return;
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        dialog.setOnDismissListener(d -> cancel.cancel());
        mExecutor.execute(() -> {
            String result;
            try {
                TerminowFileScan.Space space = TerminowFileScan.analyze(fs, node, cancel, null);
                result = mActivity.getString(R.string.terminow_explorer_prop_contents, space.files, Formatter.formatFileSize(mActivity, space.totalBytes));
            } catch (TerminowFs.CancelledException e) {
                return;
            } catch (IOException | RuntimeException e) {
                result = String.valueOf(e.getMessage());
            }
            String finalResult = result;
            mHandler.post(() -> {
                if (dialog.isShowing()) dialog.setMessage(text.toString().trim() + "\n\n" + finalResult);
            });
        });
    }

    // ---------------------------------------------------------------------------------------
    // Workers and prompts
    // ---------------------------------------------------------------------------------------

    private interface Work<T> {
        T run() throws IOException;
    }

    private interface Done<T> {
        void accept(T result);
    }

    /** Run work on a worker with a progress dialog, then {@code done} on the main thread. */
    private <T> void background(@NonNull Work<T> work, @NonNull Done<T> done) {
        Progress progress = new Progress(mActivity.getString(R.string.terminow_explorer_working));
        mExecutor.execute(() -> {
            T result = null;
            String error = null;
            boolean hostKeyChanged = false;
            try {
                result = work.run();
            } catch (TerminowSftpFs.HostKeyChangedException e) {
                error = e.getMessage();
                hostKeyChanged = true;
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            T finalResult = result;
            String finalError = error;
            boolean finalHostKey = hostKeyChanged;
            mHandler.post(() -> {
                progress.dismiss();
                if (progress.cancel.isCancelled()) return;
                if (finalError != null) {
                    showMessage(mActivity.getString(finalHostKey ? R.string.terminow_explorer_host_key_changed : R.string.terminow_explorer_failed_title), finalError);
                    if (mVisible) render();
                } else {
                    done.accept(finalResult);
                }
            });
        });
    }

    private interface UiQuestion {
        void ask(@NonNull CountDownLatch latch);
    }

    /** Ask on the main thread and wait on the worker until answered. */
    private void waitForUi(@NonNull UiQuestion question) {
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> question.ask(latch));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The questions of an SFTP connection. */
    private class Prompts implements TerminowSftpFs.Prompts {
        @Override
        public boolean confirmHostKey(@NonNull String host, @NonNull String keyType, @NonNull String fingerprint) {
            AtomicReference<Boolean> answer = new AtomicReference<>(false);
            waitForUi(latch -> dialog()
                .setTitle(R.string.terminow_explorer_host_key_title)
                .setMessage(mActivity.getString(R.string.terminow_explorer_host_key_message, host, keyType, fingerprint))
                .setCancelable(false)
                .setPositiveButton(R.string.terminow_explorer_host_key_accept, (d, w) -> {
                    answer.set(true);
                    latch.countDown();
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> latch.countDown())
                .show());
            return answer.get();
        }

        @Nullable
        @Override
        public String askPassword(@NonNull String prompt) {
            return askSecret(prompt);
        }

        @Nullable
        @Override
        public String askPassphrase(@NonNull String keyName) {
            return askSecret(mActivity.getString(R.string.terminow_explorer_passphrase, keyName));
        }

        @Nullable
        private String askSecret(@NonNull String prompt) {
            AtomicReference<String> answer = new AtomicReference<>(null);
            waitForUi(latch -> {
                EditText field = new EditText(mActivity);
                field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                field.setTextColor(mPalette.text);
                LinearLayout box = vertical();
                box.setPadding(dp(20), dp(4), dp(20), 0);
                box.addView(field, matchWrap());
                dialog()
                    .setTitle(prompt)
                    .setView(box)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        answer.set(field.getText().toString());
                        latch.countDown();
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> latch.countDown())
                    .show();
            });
            return answer.get();
        }
    }

    /** A progress dialog with a Cancel button, updated from a worker. */
    private class Progress {
        final TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        private final AlertDialog mDialog;
        private final TextView mText;
        private boolean mDismissed;
        private long mLastUpdate;

        Progress(@NonNull String title) {
            LinearLayout box = vertical();
            box.setPadding(dp(22), dp(10), dp(22), 0);
            ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(true);
            bar.setIndeterminateTintList(ColorStateList.valueOf(mPalette.accent));
            box.addView(bar, matchWrap());
            mText = text("", 13, mPalette.secondary);
            mText.setPadding(0, dp(8), 0, 0);
            box.addView(mText, matchWrap());
            mDialog = dialog()
                .setTitle(title)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancel.cancel())
                .create();
            // Short operations finish before the dialog would only flash
            mHandler.postDelayed(() -> {
                if (!mDismissed && !mActivity.isFinishing()) mDialog.show();
            }, 300);
        }

        void update(@NonNull String name) {
            long now = System.currentTimeMillis();
            if (now - mLastUpdate < 150) return;
            mLastUpdate = now;
            mHandler.post(() -> mText.setText(name));
        }

        void dismiss() {
            mDismissed = true;
            if (mDialog.isShowing()) mDialog.dismiss();
        }
    }

    private interface NameCallback {
        void onName(@NonNull String name);
    }

    private void askName(int titleRes, @NonNull String initial, @NonNull NameCallback callback) {
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setText(initial);
        field.setTextColor(mPalette.text);
        int dot = initial.lastIndexOf('.');
        field.setSelection(0, dot > 0 ? dot : initial.length());
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(field, matchWrap());
        AlertDialog dialog = dialog()
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = field.getText().toString().trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.length() > 255) {
                field.setError(mActivity.getString(R.string.terminow_explorer_invalid_name));
                return;
            }
            dialog.dismiss();
            callback.onName(name);
        }));
        dialog.show();
    }

    // ---------------------------------------------------------------------------------------
    /**
     * How full the storage of the folder is, like "55%", for real folders of the device and of
     * Linux environments; a tap analyzes the space.
     */
    @Nullable
    private View storageChip(@NonNull TerminowFs fs, @NonNull Pane pane) {
        File file = pane.crumbs.isEmpty() ? null : hostFileOf(fs, pane.current());
        if (file == null || !file.isDirectory()) return null;
        long total, free;
        try {
            android.os.StatFs stat = new android.os.StatFs(file.getPath());
            total = stat.getTotalBytes();
            free = stat.getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (total <= 0) return null;
        float used = (float) (total - free) / total;
        LinearLayout chip = new LinearLayout(mActivity);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(8), dp(4), dp(12), dp(4));
        chip.setBackground(rounded(mPalette.field, 16));
        chip.addView(new UsageRing(mActivity, used, mPalette), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView percent = text(Math.round(used * 100) + "%", 13, mPalette.text);
        percent.setTypeface(Typeface.DEFAULT_BOLD);
        percent.setPadding(dp(6), 0, 0, 0);
        chip.addView(percent);
        chip.setContentDescription(mActivity.getString(R.string.terminow_explorer_storage_used, Math.round(used * 100), size(free), size(total)));
        chip.setOnClickListener(v -> new android.app.AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_explorer_storage)
            .setMessage(mActivity.getString(R.string.terminow_explorer_storage_used, Math.round(used * 100), size(free), size(total)))
            .setPositiveButton(R.string.terminow_explorer_analyze, (d, w) -> startAnalyzer())
            .setNegativeButton(android.R.string.ok, null)
            .show());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        params.setMarginStart(dp(6));
        chip.setLayoutParams(params);
        return chip;
    }

    /** The View panel: Grid, Compact or List, the icon size, the order, for this folder or all, and hidden files. */
    private void showViewPanel(@Nullable Pane pane, @NonNull View anchor) {
        ViewPrefs view = viewOf(pane);
        LinearLayout panel = vertical();
        panel.setBackground(rounded(mPalette.card, 16));
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.widget.PopupWindow popup = new android.widget.PopupWindow(panel, dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(10));
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        boolean[] allFolders = {!view.own};
        Runnable apply = () -> {
            popup.dismiss();
            saveView(pane, view, allFolders[0]);
        };

        panel.addView(panelTitle(R.string.terminow_explorer_view), matchWrap());
        String[][] modes = {{"grid", "▦"}, {"compact", "≡"}, {"list", "☰"}};
        int[] modeLabels = {R.string.terminow_explorer_view_grid, R.string.terminow_explorer_view_compact, R.string.terminow_explorer_view_list};
        LinearLayout modeRow = new LinearLayout(mActivity);
        for (int i = 0; i < modes.length; i++) {
            String mode = modes[i][0];
            modeRow.addView(panelOption(modes[i][1], mActivity.getString(modeLabels[i]), view.mode.equals(mode), () -> {
                view.mode = mode;
                apply.run();
            }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        panel.addView(modeRow, matchWrap());
        String[][] sizes = {{"big", "▣"}, {"medium", "▤"}, {"small", "▪"}};
        int[] sizeLabels = {R.string.terminow_explorer_size_big, R.string.terminow_explorer_size_medium, R.string.terminow_explorer_size_small};
        LinearLayout sizeRow = new LinearLayout(mActivity);
        for (int i = 0; i < sizes.length; i++) {
            String size = sizes[i][0];
            sizeRow.addView(panelOption(sizes[i][1], mActivity.getString(sizeLabels[i]), view.size.equals(size), () -> {
                view.size = size;
                apply.run();
            }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        panel.addView(sizeRow, matchWrap());

        panel.addView(divider(), dividerParams());
        panel.addView(panelTitle(R.string.terminow_explorer_sort_title), matchWrap());
        String[][] sorts = {{"name", "Az"}, {"type", "T"}, {"size", "≣"}, {"modified", "◷"}};
        LinearLayout sortRow = new LinearLayout(mActivity);
        for (String[] sort : sorts) {
            boolean selected = view.sort.equals(sort[0]);
            // Tapping the order that is chosen turns it around
            sortRow.addView(panelOption(sort[1], sortLabel(sort[0]) + (selected ? (view.descending ? " ↓" : " ↑") : ""), selected, () -> {
                if (view.sort.equals(sort[0])) view.descending = !view.descending;
                else {
                    view.sort = sort[0];
                    view.descending = false;
                }
                apply.run();
            }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        panel.addView(sortRow, matchWrap());

        panel.addView(divider(), dividerParams());
        android.widget.CheckBox all = new android.widget.CheckBox(mActivity);
        all.setText(R.string.terminow_explorer_apply_all_folders);
        all.setTextColor(mPalette.text);
        all.setButtonTintList(ColorStateList.valueOf(mPalette.accent));
        all.setChecked(allFolders[0]);
        all.setEnabled(pane != null && pane.fs != null);
        all.setOnCheckedChangeListener((b, checked) -> {
            allFolders[0] = checked;
            // Turned on: this folder follows all folders again, with what it shows now
            saveView(pane, view, checked);
        });
        panel.addView(all, matchWrap());
        android.widget.CheckBox hidden = new android.widget.CheckBox(mActivity);
        hidden.setText(R.string.terminow_explorer_show_hidden_files);
        hidden.setTextColor(mPalette.text);
        hidden.setButtonTintList(ColorStateList.valueOf(mPalette.accent));
        hidden.setChecked(mStore.getExplorer().optBoolean("showHidden"));
        hidden.setOnCheckedChangeListener((b, checked) -> setView("showHidden", checked));
        panel.addView(hidden, matchWrap());
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    @NonNull
    private TextView panelTitle(int titleRes) {
        TextView title = text(mActivity.getString(titleRes), 15, mPalette.text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(4), 0, dp(8));
        return title;
    }

    @NonNull
    private View panelOption(@NonNull String symbol, @NonNull String label, boolean selected, @NonNull Runnable onClick) {
        LinearLayout option = vertical();
        option.setGravity(Gravity.CENTER_HORIZONTAL);
        option.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView icon = text(symbol, 22, selected ? mPalette.accent : mPalette.secondary);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        option.addView(icon, matchWrap());
        View bar = new View(mActivity);
        bar.setBackgroundColor(selected ? mPalette.accent : Color.TRANSPARENT);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(44), dp(3));
        barParams.gravity = Gravity.CENTER_HORIZONTAL;
        barParams.topMargin = dp(4);
        option.addView(bar, barParams);
        TextView text = text(label, 12, selected ? mPalette.text : mPalette.secondary);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(4), 0, 0);
        option.addView(text, matchWrap());
        option.setOnClickListener(v -> onClick.run());
        return option;
    }

    @NonNull
    private View divider() {
        View line = new View(mActivity);
        line.setBackgroundColor(mPalette.divider);
        return line;
    }

    @NonNull
    private LinearLayout.LayoutParams dividerParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.setMargins(0, dp(10), 0, dp(10));
        return params;
    }

    /** New folder or new empty file in the folder of the pane. */
    private void createNew(@NonNull Pane pane) {
        if (!pane.fs.getCapabilities().write) {
            toast(pane.fs.getCapabilities().readOnlyReason);
            return;
        }
        CharSequence[] items = {mActivity.getString(R.string.terminow_explorer_new_folder), mActivity.getString(R.string.terminow_explorer_new_file)};
        dialog().setTitle(R.string.terminow_explorer_new)
            .setItems(items, (d, which) -> {
                if (which == 0) createFolder(pane);
                else askName(R.string.terminow_explorer_new_file, "", name -> background(() -> {
                    pane.fs.openOutput(pane.current(), name, false).close();
                    return true;
                }, done -> load(pane)));
            })
            .show();
    }

    // List items and thumbnails
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View entryView(@NonNull TerminowFs fs, @NonNull TerminowFs.Node node, boolean selected, @NonNull ViewPrefs view, @Nullable String location) {
        boolean grid = view.mode.equals("grid");
        boolean compact = view.mode.equals("compact");
        float scale = view.size.equals("big") ? 1.3f : view.size.equals("small") ? 0.75f : 1f;
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(grid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        row.setGravity(grid ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(grid ? 120 * scale : compact ? 40 * scale : 60 * scale));
        row.setPadding(dp(10), dp(grid ? 12 : compact ? 4 : 8), dp(10), dp(grid ? 10 : compact ? 4 : 8));
        row.setBackground(rounded(selected ? mPalette.selected : mPalette.card, 12));

        ImageView icon = new ImageView(mActivity);
        Bitmap thumbnail = selected ? null : thumbnail(fs, node);
        int iconSize = Math.round((grid ? 56 : compact ? 26 : 40) * scale);
        if (selected) {
            icon.setImageResource(R.drawable.ic_terminow_check);
            icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            icon.setBackground(round(mPalette.accent));
            icon.setPadding(dp(8), dp(8), dp(8), dp(8));
        } else if (thumbnail != null) {
            icon.setImageBitmap(thumbnail);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setClipToOutline(true);
            icon.setBackground(rounded(mPalette.field, 8));
        } else {
            TerminowFileScan.Category category = node.directory ? null : TerminowFileScan.Category.of(node.name);
            int iconRes = node.directory ? R.drawable.ic_terminow_folder : category != null ? categoryIcon(category) : R.drawable.ic_terminow_file;
            int tint = node.link ? 0xFFB57BEE : node.directory ? mPalette.accent : category != null ? categoryColor(category) : mPalette.secondary;
            icon.setImageResource(iconRes);
            icon.setImageTintList(ColorStateList.valueOf(tint));
            icon.setPadding(dp(6), dp(6), dp(6), dp(6));
        }
        row.addView(icon, new LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)));

        LinearLayout texts = vertical();
        if (!grid) texts.setPadding(dp(12), 0, 0, 0);
        TextView name = text(node.name + (node.link ? "  ⤷" : ""), compact ? 14 : grid && scale < 1 ? 12 : 14, mPalette.text);
        name.setSingleLine(!grid);
        name.setMaxLines(grid ? 2 : 1);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        if (grid) {
            name.setGravity(Gravity.CENTER);
            name.setPadding(0, dp(6), 0, 0);
        }
        texts.addView(name, matchWrap());
        StringBuilder details = new StringBuilder();
        String duration = mDurations.get(thumbnailKey(fs, node));
        if (duration != null) details.append("♪ ").append(duration).append("  ");
        if (compact) {
            // One line: the name, and the size at the end
            if (!node.directory && node.size >= 0) {
                TextView sizeView = text(size(node.size), 12, mPalette.secondary);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(sizeView);
                return row;
            }
            row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return row;
        }
        if (!node.directory && node.size >= 0) details.append(size(node.size));
        if (node.modified > 0 && !grid) {
            if (details.length() > 0) details.append("  ·  ");
            details.append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(node.modified)));
        }
        if (location != null && !grid) {
            if (details.length() > 0) details.append("\n");
            details.append(location);
        }
        if (details.length() > 0) {
            TextView detailView = text(details.toString(), 12, mPalette.secondary);
            detailView.setMaxLines(2);
            detailView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            if (grid) detailView.setGravity(Gravity.CENTER);
            texts.addView(detailView, matchWrap());
        }
        row.addView(texts, grid ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    /** The length of audio and video files, like "04:00", by thumbnail key. */
    private final Map<String, String> mDurations = new HashMap<>();

    @NonNull
    private static String thumbnailKey(@NonNull TerminowFs fs, @NonNull TerminowFs.Node node) {
        return fs.getId() + "|" + node.id + "|" + node.modified;
    }

    /** The real file of a node, for the thumbnails of videos, audio and apps, {@code null} for SAF and SFTP. */
    @Nullable
    private static File hostFileOf(@NonNull TerminowFs fs, @NonNull TerminowFs.Node node) {
        try {
            if (fs instanceof TerminowLocalFs) return ((TerminowLocalFs) fs).toFile(node.id);
            if (fs instanceof TerminowLinuxFs) return ((TerminowLinuxFs) fs).hostFileOf(node.id);
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /** A video frame, the art of audio and its length, or the icon of an app, from a real file. */
    @Nullable
    private Bitmap mediaThumbnail(@NonNull File file, @NonNull TerminowFileScan.Category category, @NonNull String key, int target) {
        try {
            if (category == TerminowFileScan.Category.APK) {
                android.content.pm.PackageManager pm = mActivity.getPackageManager();
                android.content.pm.PackageInfo info = pm.getPackageArchiveInfo(file.getPath(), 0);
                if (info == null || info.applicationInfo == null) return null;
                info.applicationInfo.sourceDir = file.getPath();
                info.applicationInfo.publicSourceDir = file.getPath();
                android.graphics.drawable.Drawable drawable = info.applicationInfo.loadIcon(pm);
                Bitmap bitmap = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                drawable.setBounds(0, 0, target, target);
                drawable.draw(canvas);
                return bitmap;
            }
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getPath());
                String millis = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (millis != null) {
                    long seconds = Long.parseLong(millis) / 1000;
                    String length = seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) :
                        String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
                    mHandler.post(() -> mDurations.put(key, length));
                }
                Bitmap frame;
                if (category == TerminowFileScan.Category.VIDEOS) {
                    frame = retriever.getFrameAtTime(1000000);
                } else {
                    byte[] art = retriever.getEmbeddedPicture();
                    frame = art != null ? BitmapFactory.decodeByteArray(art, 0, art.length) : null;
                }
                if (frame == null) return null;
                Bitmap scaled = Bitmap.createScaledBitmap(frame, target, Math.max(1, target * frame.getHeight() / Math.max(1, frame.getWidth())), true);
                if (scaled != frame) frame.recycle();
                return scaled;
            } finally {
                retriever.release();
            }
        } catch (RuntimeException | IOException | OutOfMemoryError e) {
            return null;
        }
    }

    /** A thumbnail of an image, a video, audio or an app, loaded in the background. Never of SFTP files. */
    @Nullable
    private Bitmap thumbnail(@NonNull TerminowFs fs, @NonNull TerminowFs.Node node) {
        if (node.directory || node.link || fs instanceof TerminowSftpFs || isSensitive(fs, node)) return null;
        TerminowFileScan.Category category = TerminowFileScan.Category.of(node.name);
        boolean media = category == TerminowFileScan.Category.VIDEOS || category == TerminowFileScan.Category.AUDIO ||
            (category == TerminowFileScan.Category.APK && TerminowFileScan.extension(node.name).equals("apk"));
        if (media) {
            File file = hostFileOf(fs, node);
            if (file == null) return null;
            String key = thumbnailKey(fs, node);
            Bitmap cached = mThumbnails.get(key);
            if (cached != null || mThumbnailsLoading.contains(key)) return cached;
            mThumbnailsLoading.add(key);
            int target = dp(96);
            mExecutor.execute(() -> {
                Bitmap result = mediaThumbnail(file, category, key, target);
                mHandler.post(() -> {
                    if (result != null) mThumbnails.put(key, result);
                    // Loading stays marked when there is none, so it is not tried again
                    if (mListAdapter != null) mListAdapter.notifyDataSetChanged();
                });
            });
            return null;
        }
        if (!TerminowFileScan.Category.IMAGES.matches(node.name) || TerminowFileScan.extension(node.name).equals("svg")) return null;
        String key = thumbnailKey(fs, node);
        Bitmap cached = mThumbnails.get(key);
        if (cached != null || mThumbnailsLoading.contains(key)) return cached;
        mThumbnailsLoading.add(key);
        int target = dp(96);
        mExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (fs instanceof TerminowSafFs && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    bitmap = mActivity.getContentResolver().loadThumbnail(Uri.parse(node.id), new Size(target, target), null);
                } else {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    try (InputStream in = fs.openInput(node)) {
                        BitmapFactory.decodeStream(in, null, bounds);
                    }
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 1;
                    while (bounds.outWidth / (options.inSampleSize * 2) >= target && bounds.outHeight / (options.inSampleSize * 2) >= target)
                        options.inSampleSize *= 2;
                    try (InputStream in = fs.openInput(node)) {
                        bitmap = BitmapFactory.decodeStream(in, null, options);
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // The icon stays
            }
            Bitmap result = bitmap;
            mHandler.post(() -> {
                mThumbnailsLoading.remove(key);
                if (result != null) {
                    mThumbnails.put(key, result);
                    if (mListAdapter != null) mListAdapter.notifyDataSetChanged();
                }
            });
        });
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------

    /** The ring of the storage card: how much of the device storage is used. */
    private static class UsageRing extends View {
        private final float mUsed;
        private final Palette mPalette;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mBounds = new RectF();

        UsageRing(@NonNull Context context, float used, @NonNull Palette palette) {
            super(context);
            mUsed = used;
            mPalette = palette;
            setContentDescription(Math.round(used * 100) + "%");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float stroke = getWidth() * 0.11f;
            mBounds.set(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(stroke);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setColor(mPalette.field);
            canvas.drawArc(mBounds, 0, 360, false, mPaint);
            mPaint.setColor(mUsed > 0.9f ? mPalette.danger : mUsed > 0.75f ? mPalette.warning : mPalette.accent);
            canvas.drawArc(mBounds, -90, 360 * mUsed, false, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mPalette.text);
            mPaint.setTextSize(getWidth() * 0.24f);
            mPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(Math.round(mUsed * 100) + "%", getWidth() / 2f, getHeight() / 2f - (mPaint.descent() + mPaint.ascent()) / 2, mPaint);
        }
    }

    @NonNull
    private View listRow(int iconRes, int iconColor, @NonNull String title, @Nullable String subtitle, @Nullable View trailing,
                         @NonNull View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(4), dp(6), dp(4), dp(6));
        row.setBackground(ripple());
        row.addView(round(iconRes, iconColor, 40), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(12), 0, dp(8), 0);
        TextView titleView = text(title, 15, mPalette.text);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView, matchWrap());
        if (subtitle != null) {
            TextView subtitleView = text(subtitle, 12, mPalette.secondary);
            subtitleView.setMaxLines(2);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, matchWrap());
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (trailing != null) row.addView(trailing, buttonParams());
        row.setOnClickListener(onClick);
        return row;
    }

    @NonNull
    private TextView sectionTitle(int titleRes) {
        TextView title = text(mActivity.getString(titleRes), 14, mPalette.accent);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), 0, 0, dp(6));
        return title;
    }

    /** A colored circle with a white icon. */
    @NonNull
    private ImageView round(int iconRes, int circleColor, int sizeDp) {
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
        icon.setBackground(round(circleColor));
        int padding = Math.round(sizeDp * 0.24f);
        icon.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        return icon;
    }

    @NonNull
    private static GradientDrawable round(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(color);
        return shape;
    }

    @NonNull
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radiusDp));
        shape.setColor(color);
        return shape;
    }

    @Nullable
    private android.graphics.drawable.Drawable ripple() {
        TypedValue value = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return ContextCompat.getDrawable(mActivity, value.resourceId);
    }

    @NonNull
    private ImageView iconButton(int iconRes, int descriptionRes) {
        ImageView button = new ImageView(mActivity);
        button.setImageResource(iconRes);
        button.setImageTintList(ColorStateList.valueOf(mPalette.text));
        button.setContentDescription(mActivity.getString(descriptionRes));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        TypedValue value = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        button.setBackgroundResource(value.resourceId);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(10);
        return params;
    }

    @NonNull
    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setBackground(rounded(mPalette.card, 16));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        return card;
    }

    @NonNull
    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(mActivity);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    @NonNull
    private TextView chip(@NonNull String label, boolean selected) {
        TextView chip = text(label, 13, selected ? 0xFFFFFFFF : mPalette.text);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setMinWidth(dp(40));
        chip.setBackground(rounded(selected ? mPalette.accent : mPalette.field, 16));
        return chip;
    }

    @NonNull
    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        params.setMarginEnd(dp(6));
        return params;
    }

    @NonNull
    private TextView button(@NonNull String label, boolean primary) {
        TextView button = chip(label, primary);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(16), 0, dp(16), 0);
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.setMarginEnd(dp(8));
        return params;
    }

    @NonNull
    private TextView text(@NonNull String text, int sp, int color) {
        TextView view = new TextView(mActivity);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        return view;
    }

    @NonNull
    private String size(long bytes) {
        return Formatter.formatShortFileSize(mActivity, bytes);
    }

    @NonNull
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    @NonNull
    private static FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void confirm(@NonNull String message, @NonNull Runnable action) {
        dialog()
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, (d, w) -> action.run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showMessage(@NonNull String title, @NonNull String message) {
        if (mActivity.isFinishing()) return;
        dialog()
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void toast(@Nullable String text) {
        if (text != null) mActivity.showToast(text, true);
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics()));
    }

}
