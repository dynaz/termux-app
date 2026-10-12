package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowLocalFs;
import com.termux.app.terminow.files.TerminowSafFs;
import com.termux.app.terminow.files.TerminowSftpFs;
import com.termux.app.terminow.files.TerminowTransfer;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
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
 * The File Explorer page, a Premium feature: TermiNow HOME, folders of shared storage that the
 * user picked with the Storage Access Framework, and saved SFTP hosts.
 *
 * Listing, searching and transfers run on worker threads. Nothing outside HOME is browsable on
 * the device, there is no device "/" and no Ubuntu rootfs. Deleting is permanent, there is no
 * recycle bin.
 */
public class TerminowExplorer {

    public static final int REQUEST_ADD_FOLDER = 2030;
    public static final int REQUEST_REPICK_FOLDER = 2031;

    private static final String IMPORTS_DIR = "Imports";

    public interface Host {
        void openTerminalAt(@NonNull File directory);

        void exportFile();

        void showAccountSettings();
    }

    /** One browser pane. Wide screens can show two, to copy between them. */
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

        Clip(TerminowFs fs, TerminowFs.Node parent, List<TerminowFs.Node> nodes, boolean move) {
            this.fs = fs;
            this.parent = parent;
            this.nodes = nodes;
            this.move = move;
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
    private int mActivePane;
    private boolean mTwoPanes;
    private boolean mDashboard = true;
    private boolean mDrawerOpen;
    @Nullable private Clip mClip;
    /** The device folder that is chosen again, by its index. */
    private int mRepickIndex = -1;
    private boolean mVisible;

    public TerminowExplorer(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull FrameLayout container,
                            @NonNull Host host) {
        mActivity = activity;
        mStore = store;
        mContainer = container;
        mHost = host;
        mAccountUi = new TerminowAccountUi(activity, store, () -> {
            if (mVisible) render();
        });
    }

    // ---------------------------------------------------------------------------------------
    // Page life
    // ---------------------------------------------------------------------------------------

    public void show() {
        mVisible = true;
        TerminowEntitlement.State state = TerminowEntitlement.get(mStore);
        // An old check is renewed when the explorer opens, like the settings do
        if ((state == TerminowEntitlement.State.NEEDS_CHECK || state == TerminowEntitlement.State.EXPIRED) &&
            !mAccountUi.isCheckingPremium())
            mAccountUi.checkPremiumNow();
        render();
    }

    /** Leaving the page closes the SFTP connections of the explorer, nothing else. */
    public void onHidden() {
        mVisible = false;
        for (Pane pane : mPanes) if (pane.cancel != null) pane.cancel.cancel();
        for (TerminowFs fs : mFileSystems.values()) {
            if (fs instanceof TerminowSftpFs) mExecutor.execute(fs::close);
        }
        mFileSystems.keySet().removeIf(id -> id.startsWith("sftp:"));
        for (Pane pane : mPanes) {
            if (pane.fs instanceof TerminowSftpFs) {
                pane.fs = null;
                pane.crumbs.clear();
                pane.entries.clear();
            }
        }
        if (mClip != null && mClip.fs instanceof TerminowSftpFs) mClip = null;
        if (mPanes[mActivePane].fs == null) mDashboard = true;
    }

    public boolean onBackPressed() {
        if (!mVisible) return false;
        Pane pane = mPanes[mActivePane];
        if (mDrawerOpen) {
            mDrawerOpen = false;
            render();
            return true;
        }
        if (!pane.selection.isEmpty()) {
            pane.selection.clear();
            render();
            return true;
        }
        if (mDashboard) return false;
        if (pane.crumbs.size() > 1) {
            pane.crumbs.remove(pane.crumbs.size() - 1);
            load(pane);
            return true;
        }
        mDashboard = true;
        render();
        return true;
    }

    private boolean isWide() {
        return mActivity.getResources().getConfiguration().screenWidthDp >= 760;
    }

    private void render() {
        if (!mVisible) return;
        mContainer.removeAllViews();
        TerminowEntitlement.State state = TerminowEntitlement.get(mStore);
        if (state != TerminowEntitlement.State.ACTIVE) {
            renderLocked(state);
            return;
        }
        if (isWide()) {
            // The locations stay at the side, like the rail of the app
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            ScrollView side = new ScrollView(mActivity);
            LinearLayout locations = vertical();
            locations.setPadding(0, dp(12), dp(10), dp(24));
            side.addView(locations);
            addLocations(locations, true);
            row.addView(side, new LinearLayout.LayoutParams(dp(250), ViewGroup.LayoutParams.MATCH_PARENT));
            View content = mDashboard ? dashboard() : browsers();
            row.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            mContainer.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            mContainer.addView(mDashboard ? dashboard() : browsers(),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (mDrawerOpen && !mDashboard) addDrawer();
        }
    }

    /** On a narrow screen, the locations are a drawer over the browser. */
    private void addDrawer() {
        FrameLayout scrim = new FrameLayout(mActivity);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> {
            mDrawerOpen = false;
            render();
        });
        ScrollView panel = new ScrollView(mActivity);
        panel.setBackgroundColor(color(R.color.terminow_bar_home));
        panel.setClickable(true);
        LinearLayout locations = vertical();
        locations.setPadding(dp(12), dp(16), dp(12), dp(24));
        panel.addView(locations);
        addLocations(locations, true);
        scrim.addView(panel, new FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT));
        mContainer.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------------------------------------------------------------------------------
    // Free users and Premium states
    // ---------------------------------------------------------------------------------------

    private void renderLocked(@NonNull TerminowEntitlement.State state) {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(18), 0, dp(24));
        scroll.addView(page);
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView crown = new ImageView(mActivity);
        crown.setImageResource(R.drawable.ic_terminow_crown);
        card.addView(crown, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView title = text(mActivity.getString(R.string.terminow_explorer_premium_title), 18, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, dp(10), 0, dp(10));
        card.addView(title);
        TextView features = text(mActivity.getString(R.string.terminow_explorer_premium_features), 14, R.color.terminow_text);
        card.addView(features, matchWrap());

        String status;
        boolean hadPremium = state == TerminowEntitlement.State.EXPIRED || state == TerminowEntitlement.State.NEEDS_CHECK;
        if (mAccountUi.isCheckingPremium()) status = mActivity.getString(R.string.terminow_account_premium_checking);
        else if (state == TerminowEntitlement.State.SIGNED_OUT) status = mActivity.getString(R.string.terminow_explorer_state_signed_out);
        else if (state == TerminowEntitlement.State.UNKNOWN) status = mActivity.getString(R.string.terminow_explorer_state_unknown);
        else if (state == TerminowEntitlement.State.NEEDS_CHECK) status = mActivity.getString(R.string.terminow_explorer_state_needs_check, TerminowEntitlement.OFFLINE_DAYS);
        else if (state == TerminowEntitlement.State.EXPIRED) status = mActivity.getString(R.string.terminow_explorer_state_expired);
        else status = mActivity.getString(R.string.terminow_explorer_state_free);
        if (mAccountUi.getPremiumError() != null) status += "\n" + mAccountUi.getPremiumError();
        TextView statusView = text(status, 14, R.color.terminow_text_secondary);
        statusView.setPadding(0, dp(14), 0, 0);
        statusView.setGravity(Gravity.CENTER);
        card.addView(statusView, matchWrap());

        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(16), 0, 0);
        TextView check = button(mActivity.getString(state == TerminowEntitlement.State.SIGNED_OUT ?
            R.string.terminow_account_sign_in : R.string.terminow_explorer_check_premium), true);
        check.setEnabled(!mAccountUi.isCheckingPremium());
        check.setOnClickListener(v -> mAccountUi.checkPremiumNow());
        buttons.addView(check, buttonParams());
        TextView account = button(mActivity.getString(R.string.terminow_explorer_account), false);
        account.setOnClickListener(v -> mHost.showAccountSettings());
        buttons.addView(account, buttonParams());
        card.addView(buttons);

        if (hadPremium || state == TerminowEntitlement.State.FREE) {
            TextView keep = text(mActivity.getString(R.string.terminow_explorer_data_kept), 13, R.color.terminow_text_secondary);
            keep.setPadding(0, dp(16), 0, dp(6));
            card.addView(keep, matchWrap());
            TextView export = button(mActivity.getString(R.string.terminow_explorer_export), false);
            export.setOnClickListener(v -> mHost.exportFile());
            card.addView(export, buttonParams());
        }
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, dp(4), 0);
        page.addView(card, params);
        mContainer.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    // ---------------------------------------------------------------------------------------
    // Dashboard
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View dashboard() {
        ScrollView scroll = new ScrollView(mActivity);
        LinearLayout page = vertical();
        page.setPadding(0, dp(14), dp(4), dp(24));
        scroll.addView(page);
        TextView title = text(mActivity.getString(R.string.terminow_explorer_title), 20, R.color.terminow_text);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);
        if (!isWide()) addLocations(page, false);
        else {
            TextView hint = text(mActivity.getString(R.string.terminow_explorer_pick_location), 14, R.color.terminow_text_secondary);
            hint.setPadding(0, dp(8), 0, 0);
            page.addView(hint);
        }
        addBookmarksAndRecents(page);
        return scroll;
    }

    /** TermiNow Home, the device folders and the SFTP hosts. */
    private void addLocations(@NonNull LinearLayout parent, boolean compact) {
        section(parent, R.string.terminow_explorer_section_local);
        String homeStats = getHomeStorage();
        parent.addView(locationCard(R.drawable.ic_terminow_folder, R.color.terminow_panel_accent,
            mActivity.getString(R.string.terminow_explorer_home), compact ? null : homeStats, v -> openHome(null), null), cardParams());

        section(parent, R.string.terminow_explorer_section_device);
        JSONArray folders = mStore.getExplorer().optJSONArray("folders");
        for (int i = 0; folders != null && i < folders.length(); i++) {
            JSONObject folder = folders.optJSONObject(i);
            if (folder == null) continue;
            Uri uri = Uri.parse(folder.optString("uri"));
            TerminowSafFs fs = new TerminowSafFs(mActivity, uri, folder.optString("name"));
            boolean access = fs.hasAccess();
            int index = i;
            View card = locationCard(R.drawable.ic_terminow_folder, access ? R.color.terminow_accent : R.color.terminow_text_secondary,
                folder.optString("name"), access ? mActivity.getString(R.string.terminow_explorer_device_folder) :
                    mActivity.getString(R.string.terminow_explorer_access_lost),
                v -> {
                    if (access) openSaf(uri, folder.optString("name"), null);
                    else repickFolder(index);
                }, v -> {
                    removeFolder(index);
                    return true;
                });
            parent.addView(card, cardParams());
        }
        parent.addView(locationCard(R.drawable.ic_terminow_add, R.color.terminow_text_secondary,
            mActivity.getString(R.string.terminow_explorer_add_folder), compact ? null : mActivity.getString(R.string.terminow_explorer_add_folder_summary),
            v -> addFolder(), null), cardParams());

        section(parent, R.string.terminow_explorer_section_sftp);
        List<TerminowStore.Host> hosts = mStore.getHosts();
        if (hosts.isEmpty()) {
            TextView none = text(mActivity.getString(R.string.terminow_explorer_no_hosts), 13, R.color.terminow_text_secondary);
            none.setPadding(0, 0, 0, dp(8));
            parent.addView(none);
        }
        for (TerminowStore.Host host : hosts) {
            parent.addView(locationCard(R.drawable.ic_terminow_hosts, R.color.terminow_host_ssh, host.getTitle(),
                (host.username.isEmpty() ? "" : host.username + "@") + host.address + (host.port == 22 ? "" : ":" + host.port),
                v -> openSftp(host, null), null), cardParams());
        }
    }

    @Nullable
    private String getHomeStorage() {
        try {
            StatFs stat = new StatFs(TermuxConstants.TERMUX_HOME_DIR_PATH);
            return mActivity.getString(R.string.terminow_explorer_home_storage,
                Formatter.formatShortFileSize(mActivity, stat.getAvailableBytes()),
                Formatter.formatShortFileSize(mActivity, stat.getTotalBytes()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void addBookmarksAndRecents(@NonNull LinearLayout page) {
        JSONObject explorer = mStore.getExplorer();
        JSONArray bookmarks = explorer.optJSONArray("bookmarks");
        section(page, R.string.terminow_explorer_section_bookmarks);
        if (bookmarks == null || bookmarks.length() == 0) {
            page.addView(text(mActivity.getString(R.string.terminow_explorer_no_bookmarks), 13, R.color.terminow_text_secondary));
        }
        for (int i = 0; bookmarks != null && i < bookmarks.length(); i++) {
            JSONObject bookmark = bookmarks.optJSONObject(i);
            if (bookmark == null) continue;
            int index = i;
            page.addView(locationCard(R.drawable.ic_terminow_bookmark, R.color.terminow_host_group, bookmark.optString("name"),
                bookmark.optString("fsTitle") + " · " + bookmark.optString("path"), v -> openBookmark(bookmark), v -> {
                    confirm(mActivity.getString(R.string.terminow_explorer_remove_bookmark, bookmark.optString("name")), () -> removeAt("bookmarks", index));
                    return true;
                }), cardParams());
        }

        JSONArray recents = explorer.optJSONArray("recents");
        section(page, R.string.terminow_explorer_section_recent);
        if (recents == null || recents.length() == 0) {
            page.addView(text(mActivity.getString(R.string.terminow_explorer_no_recents), 13, R.color.terminow_text_secondary));
            return;
        }
        for (int i = 0; i < Math.min(10, recents.length()); i++) {
            JSONObject recent = recents.optJSONObject(i);
            if (recent == null) continue;
            String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(recent.optLong("time")));
            page.addView(locationCard(R.drawable.ic_terminow_file, R.color.terminow_text_secondary, recent.optString("name"),
                recent.optString("fsTitle") + " · " + recent.optString("path") + " · " + when, v -> openRecent(recent), null), cardParams());
        }
        TextView clear = text(mActivity.getString(R.string.terminow_explorer_clear_recents), 14, R.color.terminow_panel_accent);
        clear.setPadding(0, dp(6), 0, dp(6));
        clear.setOnClickListener(v -> {
            JSONObject state = mStore.getExplorer();
            state.remove("recents");
            mStore.setExplorer(state);
            render();
        });
        page.addView(clear);
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

    private void openHome(@Nullable List<TerminowFs.Node> crumbs) {
        try {
            TerminowLocalFs fs = getHomeFs();
            open(fs, crumbs != null ? crumbs : Collections.singletonList(fs.getRoot()));
        } catch (IOException e) {
            toast(e.getMessage());
        }
    }

    private void openSaf(@NonNull Uri uri, @NonNull String name, @Nullable List<TerminowFs.Node> crumbs) {
        String id = TerminowSafFs.idFor(uri);
        TerminowFs fs = mFileSystems.get(id);
        if (fs == null) {
            fs = new TerminowSafFs(mActivity, uri, name);
            mFileSystems.put(id, fs);
        }
        TerminowFs finalFs = fs;
        background(() -> crumbs != null ? crumbs : Collections.singletonList(finalFs.getRoot()), result -> open(finalFs, result));
    }

    private void openSftp(@NonNull TerminowStore.Host host, @Nullable List<TerminowFs.Node> crumbs) {
        String id = "sftp:" + host.getKey();
        TerminowFs existing = mFileSystems.get(id);
        TerminowFs fs = existing != null ? existing : new TerminowSftpFs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), id, host.getTitle(),
            host.address, host.port, host.username, host.password, host.keyPath, new Prompts());
        mFileSystems.put(id, fs);
        background(() -> {
            ((TerminowSftpFs) fs).connect();
            return crumbs != null ? crumbs : Collections.singletonList(fs.getRoot());
        }, result -> open(fs, result));
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
        mDashboard = false;
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
        } else if (id.startsWith("sftp:")) {
            for (TerminowStore.Host host : mStore.getHosts()) {
                if (("sftp:" + host.getKey()).equals(id))
                    fs = new TerminowSftpFs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), id, host.getTitle(), host.address,
                        host.port, host.username, host.password, host.keyPath, new Prompts());
            }
        }
        if (fs != null) mFileSystems.put(id, fs);
        return fs;
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
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
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
        if (initial != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
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

    /** Handle the folder picker. Returns {@code true} if it was for the explorer. */
    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_ADD_FOLDER && requestCode != REQUEST_REPICK_FOLDER) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            mActivity.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException e) {
            // Some providers do not keep a permission, the folder then works until the app restarts
            toast(mActivity.getString(R.string.terminow_explorer_not_kept));
        }
        String name = uri.getLastPathSegment();
        try {
            android.database.Cursor cursor = mActivity.getContentResolver().query(
                android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, android.provider.DocumentsContract.getTreeDocumentId(uri)),
                new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
                cursor.close();
            }
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

    @NonNull
    private List<TerminowFs.Node> visibleEntries(@NonNull Pane pane) {
        JSONObject view = mStore.getExplorer();
        boolean hidden = view.optBoolean("showHidden");
        String sort = view.optString("sort", "name");
        boolean descending = view.optBoolean("descending");
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
            case "type": comparator = (a, b) -> extension(a.name).compareTo(extension(b.name)); break;
            default: comparator = (a, b) -> 0;
        }
        Comparator<TerminowFs.Node> byName = (a, b) -> a.name.compareToIgnoreCase(b.name);
        Comparator<TerminowFs.Node> order = comparator.thenComparing(byName);
        if (descending) order = order.reversed();
        Comparator<TerminowFs.Node> foldersFirst = (a, b) -> a.directory == b.directory ? 0 : a.directory ? -1 : 1;
        Collections.sort(list, foldersFirst.thenComparing(order));
        return list;
    }

    @NonNull
    private static String extension(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    @NonNull
    private View browsers() {
        boolean canTwoPanes = mActivity.getResources().getConfiguration().screenWidthDp >= 900;
        if (!canTwoPanes) mTwoPanes = false;
        if (!mTwoPanes || mPanes[1].fs == null && mPanes[0].fs == null) return paneView(mActivePane, canTwoPanes);
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 2; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i == 0) params.setMarginEnd(dp(8));
            row.addView(paneView(i, true), params);
        }
        return row;
    }

    @NonNull
    private View paneView(int index, boolean canTwoPanes) {
        Pane pane = mPanes[index];
        LinearLayout view = vertical();
        boolean active = index == mActivePane;
        if (mTwoPanes) {
            GradientDrawable border = new GradientDrawable();
            border.setCornerRadius(dp(14));
            border.setStroke(dp(active ? 2 : 1), color(active ? R.color.terminow_panel_accent : R.color.terminow_surface));
            view.setBackground(border);
            view.setPadding(dp(6), dp(6), dp(6), dp(6));
        }
        view.setOnClickListener(v -> setActive(index));

        if (pane.fs == null) {
            TextView empty = text(mActivity.getString(R.string.terminow_explorer_pick_location), 14, R.color.terminow_text_secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(40), dp(16), dp(16));
            empty.setOnClickListener(v -> setActive(index));
            view.addView(empty, matchWrap());
            return view;
        }
        TerminowFs fs = pane.fs;

        // Top bar: locations, up, breadcrumbs
        LinearLayout top = new LinearLayout(mActivity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(10), 0, dp(4));
        if (!isWide()) {
            TextView menu = chip("☰", false);
            menu.setContentDescription(mActivity.getString(R.string.terminow_explorer_locations));
            menu.setOnClickListener(v -> {
                mDrawerOpen = true;
                render();
            });
            top.addView(menu, chipParams());
        }
        TextView up = chip("‹", false);
        up.setContentDescription(mActivity.getString(R.string.terminow_explorer_up));
        up.setOnClickListener(v -> {
            setActive(index);
            onBackPressed();
        });
        top.addView(up, chipParams());
        HorizontalScrollView crumbScroll = new HorizontalScrollView(mActivity);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout crumbs = new LinearLayout(mActivity);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        TextView fsName = text(fs.getTitle(), 15, R.color.terminow_text);
        fsName.setTypeface(Typeface.DEFAULT_BOLD);
        crumbs.addView(fsName);
        for (int i = 1; i < pane.crumbs.size(); i++) {
            int depth = i;
            crumbs.addView(text("  ›  ", 15, R.color.terminow_text_secondary));
            TextView crumb = text(pane.crumbs.get(i).name, 15, i == pane.crumbs.size() - 1 ? R.color.terminow_text : R.color.terminow_text_secondary);
            crumb.setOnClickListener(v -> {
                setActive(index);
                while (pane.crumbs.size() > depth + 1) pane.crumbs.remove(pane.crumbs.size() - 1);
                load(pane);
            });
            crumbs.addView(crumb);
        }
        fsName.setOnClickListener(v -> {
            setActive(index);
            while (pane.crumbs.size() > 1) pane.crumbs.remove(pane.crumbs.size() - 1);
            load(pane);
        });
        crumbScroll.addView(crumbs);
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
        top.addView(crumbScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        view.addView(top, matchWrap());

        // Tools or selection actions
        view.addView(pane.selection.isEmpty() ? toolRow(index, pane, canTwoPanes) : selectionRow(index, pane), matchWrap());
        if (pane.searching) {
            EditText search = new EditText(mActivity);
            search.setHint(R.string.terminow_explorer_search_hint);
            search.setSingleLine(true);
            search.setTextColor(color(R.color.terminow_text));
            search.setHintTextColor(color(R.color.terminow_text_secondary));
            search.setText(pane.query);
            search.setSelection(pane.query.length());
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    pane.query = s.toString();
                    GridView grid = view.findViewWithTag("grid");
                    if (grid != null) ((BaseAdapter) grid.getAdapter()).notifyDataSetChanged();
                }
            });
            view.addView(search, matchWrap());
            search.requestFocus();
        }
        if (mClip != null && fs.getCapabilities().write) view.addView(clipRow(pane), matchWrap());

        if (pane.loading) {
            ProgressBar progress = new ProgressBar(mActivity);
            progress.setIndeterminateTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(40), dp(40));
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(40);
            view.addView(progress, params);
            return view;
        }
        if (pane.error != null) {
            LinearLayout card = card();
            card.addView(text(pane.error, 14, R.color.terminow_text));
            LinearLayout buttons = new LinearLayout(mActivity);
            buttons.setPadding(0, dp(12), 0, 0);
            TextView retry = button(mActivity.getString(R.string.terminow_retry), true);
            retry.setOnClickListener(v -> load(pane));
            buttons.addView(retry, buttonParams());
            if (fs instanceof TerminowSafFs && !((TerminowSafFs) fs).hasAccess()) {
                TextView again = button(mActivity.getString(R.string.terminow_explorer_choose_again), false);
                again.setOnClickListener(v -> {
                    JSONArray folders = mStore.getExplorer().optJSONArray("folders");
                    for (int i = 0; folders != null && i < folders.length(); i++)
                        if (TerminowSafFs.idFor(Uri.parse(folders.optJSONObject(i).optString("uri"))).equals(fs.getId())) repickFolder(i);
                });
                buttons.addView(again, buttonParams());
            }
            card.addView(buttons);
            LinearLayout.LayoutParams params = matchWrap();
            params.topMargin = dp(12);
            view.addView(card, params);
            return view;
        }

        List<TerminowFs.Node> shown = visibleEntries(pane);
        boolean grid = mStore.getExplorer().optBoolean("grid");
        GridView list = new GridView(mActivity);
        list.setTag("grid");
        list.setNumColumns(grid ? GridView.AUTO_FIT : 1);
        list.setColumnWidth(dp(112));
        list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        list.setVerticalSpacing(dp(6));
        list.setHorizontalSpacing(dp(6));
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        list.setPadding(0, dp(6), 0, dp(24));
        EntryAdapter adapter = new EntryAdapter(pane, grid);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> {
            setActive(index);
            TerminowFs.Node node = adapter.items().get(position);
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
            setActive(index);
            toggle(pane, adapter.items().get(position));
            return true;
        });
        view.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (shown.isEmpty()) {
            TextView empty = text(mActivity.getString(pane.query.isEmpty() ? R.string.terminow_explorer_empty_folder : R.string.terminow_no_results),
                14, R.color.terminow_text_secondary);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(30), 0, 0);
            view.addView(empty, 2 + (pane.searching ? 1 : 0) + (mClip != null && fs.getCapabilities().write ? 1 : 0), matchWrap());
        }
        return view;
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

    @NonNull
    private View toolRow(int index, @NonNull Pane pane, boolean canTwoPanes) {
        JSONObject view = mStore.getExplorer();
        LinearLayout row = new LinearLayout(mActivity);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView search = chip(mActivity.getString(R.string.terminow_explorer_search), pane.searching);
        search.setOnClickListener(v -> {
            setActive(index);
            pane.searching = !pane.searching;
            if (!pane.searching) pane.query = "";
            render();
        });
        row.addView(search, chipParams());
        String sort = view.optString("sort", "name");
        TextView sortChip = chip(mActivity.getString(R.string.terminow_explorer_sort, sortLabel(sort)) + (view.optBoolean("descending") ? " ↓" : " ↑"), false);
        sortChip.setOnClickListener(v -> showSortDialog());
        row.addView(sortChip, chipParams());
        TextView grid = chip(mActivity.getString(view.optBoolean("grid") ? R.string.terminow_explorer_view_grid : R.string.terminow_explorer_view_list), false);
        grid.setOnClickListener(v -> setView("grid", !view.optBoolean("grid")));
        row.addView(grid, chipParams());
        TextView hidden = chip(mActivity.getString(R.string.terminow_explorer_hidden), view.optBoolean("showHidden"));
        hidden.setOnClickListener(v -> setView("showHidden", !view.optBoolean("showHidden")));
        row.addView(hidden, chipParams());
        if (canTwoPanes) {
            TextView two = chip(mActivity.getString(R.string.terminow_explorer_two_panes), mTwoPanes);
            two.setOnClickListener(v -> {
                mTwoPanes = !mTwoPanes;
                if (mTwoPanes && mPanes[1 - mActivePane].fs == null && pane.fs != null) {
                    Pane other = mPanes[1 - mActivePane];
                    other.fs = pane.fs;
                    other.crumbs.clear();
                    other.crumbs.addAll(pane.crumbs);
                    load(other);
                }
                render();
            });
            row.addView(two, chipParams());
        }
        TextView more = chip("⋯", false);
        more.setContentDescription(mActivity.getString(R.string.terminow_explorer_more));
        more.setOnClickListener(v -> {
            setActive(index);
            showFolderActions(pane);
        });
        row.addView(more, chipParams());
        HorizontalScrollView scroll = new HorizontalScrollView(mActivity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    @NonNull
    private View selectionRow(int index, @NonNull Pane pane) {
        TerminowFs fs = pane.fs;
        TerminowFs.Capabilities capabilities = fs.getCapabilities();
        List<TerminowFs.Node> nodes = selected(pane);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        TextView count = chip("✕  " + mActivity.getString(R.string.terminow_explorer_selected, nodes.size()), true);
        count.setOnClickListener(v -> {
            pane.selection.clear();
            render();
        });
        row.addView(count, chipParams());
        TextView all = chip(mActivity.getString(R.string.terminow_explorer_select_all), false);
        all.setOnClickListener(v -> {
            for (TerminowFs.Node node : visibleEntries(pane)) pane.selection.add(node.id);
            render();
        });
        row.addView(all, chipParams());
        addAction(row, R.string.terminow_explorer_copy, true, null, () -> {
            mClip = new Clip(fs, pane.current(), nodes, false);
            pane.selection.clear();
            toast(mActivity.getString(R.string.terminow_explorer_copied_hint));
            render();
        });
        addAction(row, R.string.terminow_explorer_move, capabilities.delete, capabilities.readOnlyReason, () -> {
            mClip = new Clip(fs, pane.current(), nodes, true);
            pane.selection.clear();
            toast(mActivity.getString(R.string.terminow_explorer_moved_hint));
            render();
        });
        if (mTwoPanes && mPanes[1 - index].fs != null) {
            Pane other = mPanes[1 - index];
            boolean otherWritable = other.fs.getCapabilities().write;
            addAction(row, R.string.terminow_explorer_copy_other, otherWritable, other.fs.getCapabilities().readOnlyReason,
                () -> transfer(fs, pane.current(), nodes, other.fs, other.current(), false));
            addAction(row, R.string.terminow_explorer_move_other, otherWritable && capabilities.delete, capabilities.readOnlyReason,
                () -> transfer(fs, pane.current(), nodes, other.fs, other.current(), true));
        }
        if (!(fs instanceof TerminowLocalFs))
            addAction(row, R.string.terminow_explorer_to_home, true, null, () -> copyToHome(fs, pane.current(), nodes));
        addAction(row, R.string.terminow_explorer_rename, capabilities.rename && nodes.size() == 1,
            nodes.size() != 1 ? mActivity.getString(R.string.terminow_explorer_one_only) : capabilities.readOnlyReason,
            () -> rename(pane, nodes.get(0)));
        addAction(row, R.string.terminow_explorer_share, true, null, () -> share(fs, nodes, fs.getDisplayPath(pane.crumbs)));
        addAction(row, R.string.terminow_explorer_properties, nodes.size() == 1, mActivity.getString(R.string.terminow_explorer_one_only),
            () -> showProperties(fs, nodes.get(0), fs.getDisplayPath(pane.crumbs)));
        addAction(row, R.string.terminow_delete, capabilities.delete, capabilities.readOnlyReason, () -> delete(pane, nodes));
        HorizontalScrollView scroll = new HorizontalScrollView(mActivity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(row);
        return scroll;
    }

    /** An action chip, which explains why when it can not be used. */
    private void addAction(@NonNull LinearLayout row, int labelRes, boolean enabled, @Nullable String reason, @NonNull Runnable action) {
        TextView chip = chip(mActivity.getString(labelRes), false);
        chip.setAlpha(enabled ? 1f : 0.45f);
        chip.setOnClickListener(v -> {
            if (enabled) action.run();
            else toast(reason != null ? reason : mActivity.getString(R.string.terminow_explorer_not_supported));
        });
        row.addView(chip, chipParams());
    }

    @NonNull
    private View clipRow(@NonNull Pane pane) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(6), dp(6), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(12));
        background.setColor(color(R.color.terminow_surface));
        row.setBackground(background);
        TextView label = text(mActivity.getResources().getQuantityString(mClip.move ? R.plurals.terminow_explorer_clip_move :
            R.plurals.terminow_explorer_clip_copy, mClip.nodes.size(), mClip.nodes.size(), mClip.fs.getTitle()), 13, R.color.terminow_text);
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView paste = button(mActivity.getString(R.string.terminow_explorer_paste), true);
        Clip clip = mClip;
        paste.setOnClickListener(v -> {
            mClip = null;
            transfer(clip.fs, clip.parent, clip.nodes, pane.fs, pane.current(), clip.move);
        });
        row.addView(paste, buttonParams());
        TextView cancel = chip("✕", false);
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
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_explorer_sort_title)
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

    private void showFolderActions(@NonNull Pane pane) {
        TerminowFs fs = pane.fs;
        TerminowFs.Node current = pane.current();
        if (fs == null || current == null) return;
        TerminowFs.Capabilities capabilities = fs.getCapabilities();
        List<CharSequence> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(mActivity.getString(R.string.terminow_explorer_new_folder));
        actions.add(() -> {
            if (capabilities.write) createFolder(pane);
            else toast(capabilities.readOnlyReason);
        });
        labels.add(mActivity.getString(R.string.terminow_explorer_open_terminal));
        actions.add(() -> openInTerminal(fs, current));
        if (!(fs instanceof TerminowLocalFs)) {
            labels.add(mActivity.getString(R.string.terminow_explorer_folder_to_home));
            actions.add(() -> copyToHome(fs, pane.crumbs.size() > 1 ? pane.crumbs.get(pane.crumbs.size() - 2) : current,
                Collections.singletonList(current)));
        }
        labels.add(mActivity.getString(R.string.terminow_explorer_bookmark));
        actions.add(() -> bookmark(pane));
        labels.add(mActivity.getString(R.string.terminow_explorer_properties));
        actions.add(() -> showProperties(fs, current, fs.getDisplayPath(pane.crumbs)));
        labels.add(mActivity.getString(R.string.terminow_explorer_refresh));
        actions.add(() -> load(pane));
        if (fs instanceof TerminowSftpFs) {
            labels.add(mActivity.getString(R.string.terminow_explorer_disconnect));
            actions.add(() -> {
                mFileSystems.remove(fs.getId());
                mExecutor.execute(fs::close);
                for (Pane p : mPanes) if (p.fs == fs) {
                    p.fs = null;
                    p.crumbs.clear();
                    p.entries.clear();
                }
                mDashboard = mPanes[mActivePane].fs == null;
                render();
            });
        }
        labels.add(mActivity.getString(R.string.terminow_explorer_dashboard));
        actions.add(() -> {
            mDashboard = true;
            render();
        });
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(fs.getDisplayPath(pane.crumbs))
            .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
            .show();
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
        String message = mActivity.getString(R.string.terminow_explorer_delete_confirm, what, pane.fs.getTitle());
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_explorer_delete_title)
            .setMessage(message)
            .setPositiveButton(R.string.terminow_explorer_delete_permanently, (d, w) -> {
                TerminowFs fs = pane.fs;
                TerminowFs.Node parent = pane.current();
                Progress progress = new Progress(mActivity.getString(R.string.terminow_explorer_deleting));
                mExecutor.execute(() -> {
                    String error = null;
                    for (TerminowFs.Node node : nodes) {
                        try {
                            progress.update(node.name, -1, -1, 0, 0);
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
                transfer(fs, parent, nodes, getHomeFs(), imports, false);
            } catch (IOException e) {
                toast(e.getMessage());
            }
        });
    }

    /** Copy or move with progress, cancellation and conflict questions. */
    private void transfer(@NonNull TerminowFs source, @NonNull TerminowFs.Node sourceParent, @NonNull List<TerminowFs.Node> nodes,
                          @NonNull TerminowFs target, @NonNull TerminowFs.Node targetDirectory, boolean move) {
        if (!target.getCapabilities().write) {
            toast(target.getCapabilities().readOnlyReason);
            return;
        }
        Progress progress = new Progress(mActivity.getString(move ? R.string.terminow_explorer_moving : R.string.terminow_explorer_copying));
        mExecutor.execute(() -> {
            TerminowTransfer.Result result = new TerminowTransfer(source, target, move, new TerminowTransfer.Listener() {
                TerminowTransfer.Resolution mAll;

                @Override
                public void onProgress(@NonNull String name, long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
                    progress.update(name, bytesDone, bytesTotal, filesDone, filesTotal);
                }

                @NonNull
                @Override
                public TerminowTransfer.Resolution onConflict(@NonNull String name) {
                    if (mAll != null) return mAll;
                    AtomicReference<TerminowTransfer.Resolution> answer = new AtomicReference<>(TerminowTransfer.Resolution.SKIP);
                    AtomicReference<Boolean> all = new AtomicReference<>(false);
                    waitForUi(latch -> askConflict(name, answer, all, latch));
                    if (all.get()) mAll = answer.get();
                    return answer.get();
                }
            }, progress.cancel).run(sourceParent, nodes, targetDirectory);
            mHandler.post(() -> {
                progress.dismiss();
                String summary = TerminowTransfer.describe(result);
                if (result.errors.isEmpty() && result.skippedLinks.isEmpty()) toast(summary);
                else showMessage(mActivity.getString(move ? R.string.terminow_explorer_moving : R.string.terminow_explorer_copying), summary);
                reloadShowing(source);
                reloadShowing(target);
            });
        });
    }

    private void askConflict(@NonNull String name, @NonNull AtomicReference<TerminowTransfer.Resolution> answer,
                             @NonNull AtomicReference<Boolean> all, @NonNull CountDownLatch latch) {
        CheckBox applyAll = new CheckBox(mActivity);
        applyAll.setText(R.string.terminow_explorer_apply_all);
        applyAll.setTextColor(color(R.color.terminow_text));
        applyAll.setButtonTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(applyAll);
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_explorer_conflict_title)
            .setMessage(mActivity.getString(R.string.terminow_explorer_conflict_message, name))
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

    private void reloadShowing(@NonNull TerminowFs fs) {
        for (Pane pane : mPanes) if (pane.fs == fs && !mDashboard) load(pane);
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
    private void openInTerminal(@NonNull TerminowFs fs, @NonNull TerminowFs.Node directory) {
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
        return fs instanceof TerminowLocalFs && (node.id.equals(".ssh") || node.id.startsWith(".ssh/"));
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
        // Each download in its own folder, so that names never collide
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
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(node.name));
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

    private void showProperties(@NonNull TerminowFs fs, @NonNull TerminowFs.Node node, @NonNull String path) {
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
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_explorer_properties)
            .setMessage(text.toString().trim())
            .setPositiveButton(android.R.string.ok, null)
            .show();
        if (!node.directory || node.link) return;
        // The size of a folder is counted in the background, without following links
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        dialog.setOnDismissListener(d -> cancel.cancel());
        mExecutor.execute(() -> {
            long[] totals = new long[2];
            String error = null;
            try {
                count(fs, node, totals, cancel, 0);
            } catch (TerminowFs.CancelledException e) {
                return;
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            String finalError = error;
            mHandler.post(() -> {
                if (!dialog.isShowing()) return;
                dialog.setMessage(text.toString().trim() + "\n\n" + (finalError != null ? finalError :
                    mActivity.getString(R.string.terminow_explorer_prop_contents, totals[0], Formatter.formatFileSize(mActivity, totals[1]))));
            });
        });
    }

    private void count(@NonNull TerminowFs fs, @NonNull TerminowFs.Node directory, @NonNull long[] totals,
                       @NonNull TerminowFs.Cancel cancel, int depth) throws IOException {
        if (depth > 64) return;
        for (TerminowFs.Node child : fs.list(directory, cancel)) {
            if (child.link) continue;
            if (child.directory) count(fs, child, totals, cancel, depth + 1);
            else {
                totals[0]++;
                if (child.size > 0) totals[1] += child.size;
            }
        }
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
            try {
                result = work.run();
            } catch (TerminowSftpFs.HostKeyChangedException e) {
                error = "!" + e.getMessage();
            } catch (IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            T finalResult = result;
            String finalError = error;
            mHandler.post(() -> {
                progress.dismiss();
                if (progress.cancel.isCancelled()) return;
                if (finalError != null) {
                    if (finalError.startsWith("!")) showMessage(mActivity.getString(R.string.terminow_explorer_host_key_changed), finalError.substring(1));
                    else showMessage(mActivity.getString(R.string.terminow_explorer_failed_title), finalError);
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
            waitForUi(latch -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
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
                field.setTextColor(color(R.color.terminow_text));
                LinearLayout box = vertical();
                box.setPadding(dp(20), dp(4), dp(20), 0);
                box.addView(field, matchWrap());
                new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
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
        private final ProgressBar mBar;
        private final TextView mText;
        private long mLastUpdate;

        Progress(@NonNull String title) {
            LinearLayout box = vertical();
            box.setPadding(dp(22), dp(10), dp(22), 0);
            mBar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
            mBar.setIndeterminate(true);
            mBar.setProgressTintList(ColorStateList.valueOf(color(R.color.terminow_panel_accent)));
            box.addView(mBar, matchWrap());
            mText = text("", 13, R.color.terminow_text_secondary);
            mText.setPadding(0, dp(8), 0, 0);
            box.addView(mText, matchWrap());
            mDialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
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

        private boolean mDismissed;

        void update(@NonNull String name, long done, long total, int filesDone, int filesTotal) {
            long now = System.currentTimeMillis();
            if (now - mLastUpdate < 150) return;
            mLastUpdate = now;
            mHandler.post(() -> {
                if (total > 0) {
                    mBar.setIndeterminate(false);
                    mBar.setMax(1000);
                    mBar.setProgress((int) Math.min(1000, done * 1000 / total));
                }
                mText.setText(name + (total > 0 ? "\n" + Formatter.formatShortFileSize(mActivity, done) + " / " +
                    Formatter.formatShortFileSize(mActivity, total) : "") + (filesTotal > 0 ? "  ·  " + filesDone + "/" + filesTotal : ""));
            });
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
        field.setTextColor(color(R.color.terminow_text));
        int dot = initial.lastIndexOf('.');
        field.setSelection(0, dot > 0 ? dot : initial.length());
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(field, matchWrap());
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
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
    // List items
    // ---------------------------------------------------------------------------------------

    private class EntryAdapter extends BaseAdapter {
        private final Pane mPane;
        private final boolean mGrid;

        EntryAdapter(@NonNull Pane pane, boolean grid) {
            mPane = pane;
            mGrid = grid;
        }

        @NonNull
        List<TerminowFs.Node> items() {
            return visibleEntries(mPane);
        }

        @Override
        public int getCount() {
            return items().size();
        }

        @Override
        public Object getItem(int position) {
            return items().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TerminowFs.Node node = items().get(position);
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(mGrid ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            row.setGravity(mGrid ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(mGrid ? 12 : 8), dp(10), dp(mGrid ? 10 : 8));
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(12));
            boolean selected = mPane.selection.contains(node.id);
            background.setColor(color(selected ? R.color.terminow_rail_selected : R.color.terminow_surface));
            row.setBackground(background);

            ImageView icon = new ImageView(mActivity);
            icon.setImageResource(node.directory ? R.drawable.ic_terminow_folder : R.drawable.ic_terminow_file);
            icon.setImageTintList(ColorStateList.valueOf(color(node.link ? R.color.terminow_host_group :
                node.directory ? R.color.terminow_panel_accent : R.color.terminow_text_secondary)));
            row.addView(icon, new LinearLayout.LayoutParams(dp(mGrid ? 40 : 30), dp(mGrid ? 40 : 30)));

            LinearLayout texts = vertical();
            if (!mGrid) texts.setPadding(dp(12), 0, 0, 0);
            TextView name = text(node.name + (node.link ? "  ⤷" : ""), 14, R.color.terminow_text);
            name.setSingleLine(!mGrid);
            name.setMaxLines(mGrid ? 2 : 1);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            if (mGrid) name.setGravity(Gravity.CENTER);
            texts.addView(name, matchWrap());
            StringBuilder details = new StringBuilder();
            if (!node.directory && node.size >= 0) details.append(Formatter.formatShortFileSize(mActivity, node.size));
            if (node.modified > 0 && !mGrid) {
                if (details.length() > 0) details.append("  ·  ");
                details.append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(node.modified)));
            }
            if (details.length() > 0) {
                TextView detailView = text(details.toString(), 12, R.color.terminow_text_secondary);
                if (mGrid) detailView.setGravity(Gravity.CENTER);
                texts.addView(detailView, matchWrap());
            }
            row.addView(texts, mGrid ? matchWrap() : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            return row;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------------------------------

    @NonNull
    private View locationCard(int iconRes, int colorRes, @NonNull String title, @Nullable String subtitle,
                              @NonNull View.OnClickListener onClick, @Nullable View.OnLongClickListener onLongClick) {
        LinearLayout card = new LinearLayout(mActivity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.terminow_card);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setBackgroundResource(R.drawable.terminow_host_icon);
        icon.getBackground().mutate().setTint(color(colorRes));
        icon.setPadding(dp(7), dp(7), dp(7), dp(7));
        card.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout texts = vertical();
        texts.setPadding(dp(12), 0, 0, 0);
        TextView titleView = text(title, 15, R.color.terminow_text);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(titleView, matchWrap());
        if (subtitle != null) {
            TextView subtitleView = text(subtitle, 12, R.color.terminow_text_secondary);
            subtitleView.setMaxLines(2);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitleView, matchWrap());
        }
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.setOnClickListener(onClick);
        if (onLongClick != null) card.setOnLongClickListener(onLongClick);
        return card;
    }

    private void section(@NonNull LinearLayout parent, int titleRes) {
        TextView title = text(mActivity.getString(titleRes), 13, R.color.terminow_text_secondary);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(2), dp(16), 0, dp(6));
        parent.addView(title);
    }

    @NonNull
    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(8);
        return params;
    }

    @NonNull
    private LinearLayout card() {
        LinearLayout card = vertical();
        card.setBackgroundResource(R.drawable.terminow_card);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
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
        TextView chip = text(label, 13, selected ? R.color.terminow_bar_home : R.color.terminow_text);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), 0, dp(12), 0);
        chip.setMinWidth(dp(40));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(16));
        shape.setColor(color(selected ? R.color.terminow_panel_accent : R.color.terminow_surface));
        chip.setBackground(shape);
        return chip;
    }

    @NonNull
    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        params.setMarginEnd(dp(8));
        return params;
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

    private void confirm(@NonNull String message, @NonNull Runnable action) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, (d, w) -> action.run())
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void showMessage(@NonNull String title, @NonNull String message) {
        if (mActivity.isFinishing()) return;
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
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
