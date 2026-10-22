package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.MimeTypeMap;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.files.TerminowFileScan;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowLocalFs;
import com.termux.app.terminow.files.TerminowSafFs;
import com.termux.app.terminow.files.TerminowSftpFs;
import com.termux.app.terminow.files.TerminowTransfer;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * An SFTP tab like in Termius: the files of the host in place of a terminal, with the folder
 * name, Upload, a menu and search at the top, and each file with its permissions, size and date.
 * A file is downloaded, opened, shared, renamed or deleted from its menu. Each tab has its own
 * connection, with the host key checked against `~/.ssh/known_hosts` like `ssh` does, and the
 * sftp command line can still be used in the tab instead.
 */
public final class TerminowSftpBrowser {

    public static final int REQUEST_UPLOAD = 2050;
    public static final int REQUEST_DOWNLOAD_FOLDER = 2051;

    private static final String LOG_TAG = "TerminowSftpBrowser";
    private static final String PARTIAL_SUFFIX = ".terminow-partial";

    /** What a tab shows: its connection, the folders that were opened, and a transfer. */
    private static final class Tab {
        final TerminowStore.Host host;
        final TerminowSftpFs fs;
        final Runnable onConnected;
        final Runnable commandLine;
        /** The folders that were opened, the shown one last, for Back. */
        final List<TerminowFs.Node> history = new ArrayList<>();
        List<TerminowFs.Node> entries = new ArrayList<>();
        boolean connecting;
        boolean loading;
        @Nullable String error;
        @Nullable TerminowFs.Cancel cancel;
        boolean searching;
        String query = "";
        @Nullable TerminowFs.Cancel transfer;
        String transferText = "";
        int transferPercent = -1;

        Tab(@NonNull TerminowStore.Host host, @NonNull TerminowSftpFs fs, @NonNull Runnable onConnected, @NonNull Runnable commandLine) {
            this.host = host;
            this.fs = fs;
            this.onConnected = onConnected;
            this.commandLine = commandLine;
        }

        @Nullable
        TerminowFs.Node folder() {
            return history.isEmpty() ? null : history.get(history.size() - 1);
        }
    }

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final FrameLayout mContainer;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newCachedThreadPool();
    private final Map<TerminalSession, Tab> mTabs = new WeakHashMap<>();
    /** The session whose files are shown, {@code null} while a terminal or the home screen is. */
    @Nullable private TerminalSession mShown;
    /** The rows that are listed, a {@code null} first for "..". */
    private final List<TerminowFs.Node> mRows = new ArrayList<>();
    @Nullable private BaseAdapter mAdapter;
    @Nullable private TextView mTransferText;
    @Nullable private ProgressBar mTransferBar;
    @Nullable private TerminalSession mUploadSession;
    /** A download that waits for the user to pick the folder it goes to. */
    @Nullable private Runnable mPendingDownload;

    public TerminowSftpBrowser(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull FrameLayout container) {
        mActivity = activity;
        mStore = store;
        mContainer = container;
        // Touches never reach the terminal under it
        mContainer.setClickable(true);
    }

    // ---------------------------------------------------------------------------------------
    // Tabs
    // ---------------------------------------------------------------------------------------

    /**
     * Show the files of a host in the tab of a session, which then is not a terminal.
     *
     * @param login The host as it logs in, with the username, password and key of its identity.
     * @param onConnected Run once the host was connected to.
     * @param commandLine Use the sftp command line in the tab instead.
     */
    public void open(@NonNull TerminalSession session, @NonNull TerminowStore.Host login,
                     @NonNull Runnable onConnected, @NonNull Runnable commandLine) {
        TerminowSftpFs fs = new TerminowSftpFs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH),
            "sftp-tab:" + login.getKey() + ":" + UUID.randomUUID(), login.getTitle(), login.address, login.port,
            login.username, login.password, login.keyPath, new Prompts());
        Tab tab = new Tab(login, fs, onConnected, commandLine);
        mTabs.put(session, tab);
        connect(tab);
    }

    /** Whether the tab of the session shows the files of its host. */
    public boolean has(@Nullable TerminalSession session) {
        return session != null && mTabs.containsKey(session);
    }

    /**
     * Show the files of the session if it is an SFTP tab and a terminal would be shown, else
     * nothing. Returns whether they are shown.
     */
    public boolean update(@Nullable TerminalSession current, boolean terminalShown) {
        prune();
        TerminalSession shown = terminalShown && has(current) ? current : null;
        if (shown != mShown) {
            mShown = shown;
            render();
            // The keyboard of the terminal is not for the files
            if (shown != null) KeyboardUtils.hideSoftKeyboard(mActivity, mActivity.getTerminalView());
        }
        mContainer.setVisibility(shown != null ? View.VISIBLE : View.GONE);
        return shown != null;
    }

    /** Back: out of search, then to the folder that was shown before. */
    public boolean onBackPressed() {
        Tab tab = shownTab();
        if (tab == null) return false;
        if (tab.searching) {
            closeSearch(tab);
            return true;
        }
        if (tab.history.size() > 1 && tab.transfer == null) {
            tab.history.remove(tab.history.size() - 1);
            load(tab);
            return true;
        }
        return false;
    }

    public boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_UPLOAD) {
            TerminalSession session = mUploadSession;
            mUploadSession = null;
            Tab tab = session != null ? mTabs.get(session) : null;
            if (resultCode != Activity.RESULT_OK || data == null || tab == null) return true;
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null)
                uris.add(data.getData());
            if (!uris.isEmpty()) upload(tab, uris);
            return true;
        }
        if (requestCode == REQUEST_DOWNLOAD_FOLDER) {
            Runnable pending = mPendingDownload;
            mPendingDownload = null;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
            Uri tree = data.getData();
            try {
                mActivity.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException e) {
                toast(e.getMessage());
                return true;
            }
            mStore.setSftpDownloadFolder(tree.toString());
            toast(mActivity.getString(R.string.terminow_sftp_download_folder_set, folderLabel(tree)));
            if (pending != null) pending.run();
            return true;
        }
        return false;
    }

    /** Pick files to upload to the folder that is shown, for Import of the home screen too. */
    public void pickUpload() {
        Tab tab = shownTab();
        if (tab == null || tab.folder() == null) return;
        if (tab.transfer != null) {
            toast(mActivity.getString(R.string.terminow_sftp_busy));
            return;
        }
        mUploadSession = mShown;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            mActivity.startActivityForResult(intent, REQUEST_UPLOAD);
        } catch (ActivityNotFoundException e) {
            mUploadSession = null;
            toast(mActivity.getString(R.string.terminow_explorer_no_app));
        }
    }

    @Nullable
    private Tab shownTab() {
        return mShown != null ? mTabs.get(mShown) : null;
    }

    /** Close the connections of tabs that were closed. */
    private void prune() {
        Iterator<Map.Entry<TerminalSession, Tab>> it = mTabs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<TerminalSession, Tab> entry = it.next();
            if (entry.getKey().isRunning()) continue;
            close(entry.getValue());
            it.remove();
        }
    }

    private void close(@NonNull Tab tab) {
        if (tab.cancel != null) tab.cancel.cancel();
        if (tab.transfer != null) tab.transfer.cancel();
        mExecutor.execute(tab.fs::close);
    }

    // ---------------------------------------------------------------------------------------
    // Connecting and listing
    // ---------------------------------------------------------------------------------------

    private void connect(@NonNull Tab tab) {
        tab.connecting = true;
        tab.error = null;
        renderIfShown(tab);
        background(() -> {
            tab.fs.connect();
            return tab.fs.getRoot();
        }, root -> {
            tab.connecting = false;
            tab.history.clear();
            tab.history.add(root);
            tab.onConnected.run();
            load(tab);
        }, e -> {
            tab.connecting = false;
            tab.error = e.getMessage() != null ? e.getMessage() : e.toString();
            renderIfShown(tab);
        });
    }

    private void load(@NonNull Tab tab) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null) {
            connect(tab);
            return;
        }
        if (tab.cancel != null) tab.cancel.cancel();
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        tab.cancel = cancel;
        tab.loading = true;
        tab.error = null;
        renderIfShown(tab);
        background(() -> tab.fs.list(folder, cancel), entries -> {
            if (cancel.isCancelled()) return;
            tab.loading = false;
            tab.entries = entries;
            renderIfShown(tab);
        }, e -> {
            if (cancel.isCancelled()) return;
            tab.loading = false;
            tab.error = e.getMessage() != null ? e.getMessage() : e.toString();
            renderIfShown(tab);
        });
    }

    /** Open a folder of the tab, a parent one too, which is kept for Back. */
    private void openFolder(@NonNull Tab tab, @NonNull TerminowFs.Node folder) {
        if (tab.searching) {
            tab.searching = false;
            tab.query = "";
        }
        tab.history.add(folder);
        tab.entries = new ArrayList<>();
        load(tab);
    }

    private void openParent(@NonNull Tab tab) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null) return;
        String parent = parentPath(folder.id);
        if (parent == null) return;
        background(() -> tab.fs.getDirectory(parent), node -> openFolder(tab, node), e -> toast(e.getMessage()));
    }

    // ---------------------------------------------------------------------------------------
    // What is listed
    // ---------------------------------------------------------------------------------------

    /**
     * The entries of a folder as they are listed: without hidden files unless they are shown,
     * only those whose name has the query, folders first, then by the sort, ties by name.
     */
    @NonNull
    static List<TerminowFs.Node> arrange(@NonNull List<TerminowFs.Node> entries, @NonNull String sort, boolean descending,
                                         boolean hidden, @NonNull String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<TerminowFs.Node> nodes = new ArrayList<>();
        for (TerminowFs.Node node : entries) {
            if (!hidden && node.isHidden()) continue;
            if (!needle.isEmpty() && !node.name.toLowerCase(Locale.ROOT).contains(needle)) continue;
            nodes.add(node);
        }
        Comparator<TerminowFs.Node> byName = (a, b) -> {
            int c = String.CASE_INSENSITIVE_ORDER.compare(a.name, b.name);
            return c != 0 ? c : a.name.compareTo(b.name);
        };
        Comparator<TerminowFs.Node> key;
        switch (sort) {
            case TerminowStore.SFTP_SORT_DATE: key = (a, b) -> Long.compare(a.modified, b.modified); break;
            case TerminowStore.SFTP_SORT_SIZE: key = (a, b) -> Long.compare(a.size, b.size); break;
            case TerminowStore.SFTP_SORT_KIND: key = (a, b) -> kindOf(a).compareTo(kindOf(b)); break;
            default: key = (a, b) -> 0; break;
        }
        Comparator<TerminowFs.Node> order = key.thenComparing(byName);
        if (descending) order = order.reversed();
        Comparator<TerminowFs.Node> foldersFirst = (a, b) -> Boolean.compare(!a.directory, !b.directory);
        Collections.sort(nodes, foldersFirst.thenComparing(order));
        return nodes;
    }

    @NonNull
    private static String kindOf(@NonNull TerminowFs.Node node) {
        return node.directory ? "" : TerminowFileScan.extension(node.name).toLowerCase(Locale.ROOT);
    }

    /** A size like Termius shows it: "117 bytes", "3.4 kB", "580.1 kB", "2.0 GB". */
    @NonNull
    static String formatSize(long bytes, @NonNull String bytesFormat) {
        if (bytes < 1000) return String.format(Locale.getDefault(), bytesFormat, bytes);
        String[] units = {"kB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    /** The parent of an absolute path, {@code null} for "/". */
    @Nullable
    static String parentPath(@NonNull String path) {
        String trimmed = path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        if (trimmed.equals("/") || trimmed.isEmpty()) return null;
        int slash = trimmed.lastIndexOf('/');
        return slash <= 0 ? "/" : trimmed.substring(0, slash);
    }

    /** The name with "-1", "-2"... before its extension, for one that is taken. */
    @NonNull
    static String numberedName(@NonNull String name, int number) {
        return TerminowHome.numberedName(name, number);
    }

    // ---------------------------------------------------------------------------------------
    // The page
    // ---------------------------------------------------------------------------------------

    private void renderIfShown(@NonNull Tab tab) {
        if (shownTab() == tab) render();
    }

    private void render() {
        mContainer.removeAllViews();
        mAdapter = null;
        mTransferText = null;
        mTransferBar = null;
        Tab tab = shownTab();
        if (tab == null) return;
        LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(color(R.color.terminow_bg));
        page.addView(header(tab), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        if (tab.transfer != null) page.addView(transferRow(tab));

        FrameLayout card = new FrameLayout(mActivity);
        GradientDrawable background = new GradientDrawable();
        background.setColor(color(R.color.terminow_panel_card));
        float r = dp(24);
        background.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        card.setBackground(background);
        card.setClipToOutline(true);
        if (tab.connecting) {
            card.addView(status(mActivity.getString(R.string.terminow_sftp_connecting, tab.host.getTitle()), true));
        } else if (tab.error != null) {
            card.addView(errorView(tab));
        } else if (tab.loading && tab.entries.isEmpty()) {
            card.addView(status(mActivity.getString(R.string.terminow_sftp_loading), true));
        } else {
            card.addView(list(tab));
        }
        page.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        mContainer.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @NonNull
    private View header(@NonNull Tab tab) {
        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), 0, dp(8), 0);
        TerminowFs.Node folder = tab.folder();
        if (tab.searching) {
            EditText field = new EditText(mActivity);
            field.setSingleLine(true);
            field.setHint(R.string.terminow_sftp_search_hint);
            field.setText(tab.query);
            field.setSelection(field.length());
            field.setTextColor(color(R.color.terminow_text));
            field.setHintTextColor(color(R.color.terminow_text_secondary));
            field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            field.setBackground(null);
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    tab.query = s.toString();
                    refreshRows(tab);
                }
            });
            header.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            field.requestFocus();
            mHandler.post(() -> KeyboardUtils.showSoftKeyboard(mActivity, field));
        } else {
            TextView title = new TextView(mActivity);
            title.setText(folder == null ? tab.host.getTitle() : folder.name);
            title.setTextColor(color(R.color.terminow_text));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            // The whole path, which a long press copies
            if (folder != null) {
                title.setOnClickListener(v -> toast(folder.id));
                title.setOnLongClickListener(v -> {
                    copyPath(folder.id);
                    return true;
                });
            }
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            boolean ready = folder != null && !tab.connecting && tab.error == null;
            LinearLayout upload = new LinearLayout(mActivity);
            upload.setOrientation(LinearLayout.HORIZONTAL);
            upload.setGravity(Gravity.CENTER_VERTICAL);
            upload.setPadding(dp(12), dp(8), dp(14), dp(8));
            GradientDrawable pill = new GradientDrawable();
            pill.setColor(color(R.color.terminow_surface));
            pill.setCornerRadius(dp(10));
            upload.setBackground(pill);
            ImageView icon = new ImageView(mActivity);
            icon.setImageResource(R.drawable.ic_terminow_upload);
            icon.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_text)));
            upload.addView(icon, new LinearLayout.LayoutParams(dp(20), dp(20)));
            TextView label = new TextView(mActivity);
            label.setText(R.string.terminow_sftp_upload);
            label.setTextColor(color(R.color.terminow_text));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            label.setPadding(dp(6), 0, 0, 0);
            upload.addView(label);
            upload.setEnabled(ready);
            upload.setAlpha(ready ? 1f : 0.4f);
            upload.setOnClickListener(v -> pickUpload());
            header.addView(upload);
        }
        ImageButton more = iconButton(R.drawable.ic_terminow_more, R.string.terminow_sftp_more);
        more.setOnClickListener(v -> showMoreMenu(v, tab));
        header.addView(more);
        ImageButton search = iconButton(tab.searching ? R.drawable.ic_terminow_close : R.drawable.ic_terminow_search,
            tab.searching ? R.string.terminow_sftp_close_search : R.string.terminow_sftp_search);
        search.setOnClickListener(v -> {
            if (tab.searching) {
                closeSearch(tab);
            } else if (tab.folder() != null) {
                tab.searching = true;
                tab.query = "";
                render();
            }
        });
        header.addView(search);
        return header;
    }

    private void closeSearch(@NonNull Tab tab) {
        tab.searching = false;
        tab.query = "";
        KeyboardUtils.hideSoftKeyboard(mActivity, mContainer);
        render();
    }

    @NonNull
    private View transferRow(@NonNull Tab tab) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), 0, dp(8), dp(8));
        TextView text = new TextView(mActivity);
        text.setText(tab.transferText);
        text.setTextColor(color(R.color.terminow_text_secondary));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ProgressBar bar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setIndeterminate(tab.transferPercent < 0);
        if (tab.transferPercent >= 0) bar.setProgress(tab.transferPercent);
        bar.setProgressTintList(ColorStateList.valueOf(color(R.color.terminow_accent)));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.setMarginStart(dp(12));
        row.addView(bar, barParams);
        ImageButton cancel = iconButton(R.drawable.ic_terminow_close, android.R.string.cancel);
        cancel.setOnClickListener(v -> {
            if (tab.transfer != null) tab.transfer.cancel();
        });
        row.addView(cancel);
        mTransferText = text;
        mTransferBar = bar;
        return row;
    }

    @NonNull
    private View status(@NonNull String text, boolean progress) {
        LinearLayout box = new LinearLayout(mActivity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));
        if (progress) {
            ProgressBar bar = new ProgressBar(mActivity);
            bar.setIndeterminateTintList(ColorStateList.valueOf(color(R.color.terminow_accent)));
            box.addView(bar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        }
        TextView label = new TextView(mActivity);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        label.setTextColor(color(R.color.terminow_text_secondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setPadding(0, dp(12), 0, 0);
        box.addView(label);
        return box;
    }

    @NonNull
    private View errorView(@NonNull Tab tab) {
        LinearLayout box = (LinearLayout) status(mActivity.getString(R.string.terminow_sftp_failed,
            tab.folder() != null ? tab.folder().id : tab.host.getTitle()), false);
        TextView reason = new TextView(mActivity);
        reason.setText(tab.error);
        reason.setGravity(Gravity.CENTER);
        reason.setTextColor(color(R.color.terminow_text));
        reason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        reason.setPadding(0, dp(8), 0, dp(16));
        box.addView(reason);
        LinearLayout buttons = new LinearLayout(mActivity);
        buttons.setGravity(Gravity.CENTER);
        buttons.addView(textButton(R.string.terminow_retry, () -> {
            // A lost connection is made again, a folder that could not be read is read again
            if (!tab.fs.isConnected()) connect(tab);
            else load(tab);
        }));
        buttons.addView(textButton(R.string.terminow_sftp_command_line, () -> useCommandLine(tab)));
        box.addView(buttons);
        return box;
    }

    @NonNull
    private View list(@NonNull Tab tab) {
        ListView list = new ListView(mActivity);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setPadding(0, dp(8), 0, dp(8));
        list.setClipToPadding(false);
        fillRows(tab);
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return mRows.size(); }
            @Override public Object getItem(int position) { return mRows.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                return rowView(mRows.get(position), convertView);
            }
        };
        mAdapter = adapter;
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, v, position, id) -> {
            TerminowFs.Node node = mRows.get(position);
            if (node == null) openParent(tab);
            else if (node.directory) openFolder(tab, node);
            else showItemMenu(v, tab, node);
        });
        list.setOnItemLongClickListener((parent, v, position, id) -> {
            TerminowFs.Node node = mRows.get(position);
            if (node != null) showItemMenu(v, tab, node);
            return true;
        });
        if (noRows()) {
            FrameLayout box = new FrameLayout(mActivity);
            box.addView(list);
            View empty = status(tab.query.trim().isEmpty() ? mActivity.getString(R.string.terminow_sftp_empty) :
                mActivity.getString(R.string.terminow_sftp_no_match, tab.query.trim()), false);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.gravity = Gravity.CENTER;
            box.addView(empty, params);
            return box;
        }
        return list;
    }

    private void fillRows(@NonNull Tab tab) {
        mRows.clear();
        TerminowFs.Node folder = tab.folder();
        if (folder != null && parentPath(folder.id) != null && tab.query.trim().isEmpty()) mRows.add(null);
        mRows.addAll(arrange(tab.entries, mStore.getSftpSort(), mStore.isSftpSortDescending(), mStore.isSftpHiddenShown(), tab.query));
    }

    /** The search changed: only the rows, so that the field keeps its focus, unless the message of no match comes or goes. */
    private void refreshRows(@NonNull Tab tab) {
        if (mAdapter == null) return;
        boolean wasEmpty = noRows();
        fillRows(tab);
        if (noRows() != wasEmpty) render();
        else mAdapter.notifyDataSetChanged();
    }

    private boolean noRows() {
        return mRows.isEmpty() || (mRows.size() == 1 && mRows.get(0) == null);
    }

    private static final class RowViews {
        ImageView icon;
        TextView name;
        TextView permissions;
        TextView size;
        TextView date;
    }

    @NonNull
    private View rowView(@Nullable TerminowFs.Node node, @Nullable View convertView) {
        RowViews views;
        View row;
        if (convertView != null && convertView.getTag() instanceof RowViews) {
            row = convertView;
            views = (RowViews) convertView.getTag();
        } else {
            LinearLayout layout = new LinearLayout(mActivity);
            layout.setOrientation(LinearLayout.HORIZONTAL);
            layout.setGravity(Gravity.CENTER_VERTICAL);
            layout.setMinimumHeight(dp(72));
            layout.setPadding(dp(20), dp(10), dp(20), dp(10));
            layout.setBackgroundResource(selectableBackground());
            views = new RowViews();
            views.icon = new ImageView(mActivity);
            views.icon.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_text_secondary)));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(34), dp(34));
            iconParams.setMarginEnd(dp(18));
            layout.addView(views.icon, iconParams);
            LinearLayout middle = new LinearLayout(mActivity);
            middle.setOrientation(LinearLayout.VERTICAL);
            views.name = new TextView(mActivity);
            views.name.setTextColor(color(R.color.terminow_text));
            views.name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            views.name.setSingleLine(true);
            views.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            middle.addView(views.name);
            views.permissions = monospace(13);
            middle.addView(views.permissions);
            layout.addView(middle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout end = new LinearLayout(mActivity);
            end.setOrientation(LinearLayout.VERTICAL);
            end.setGravity(Gravity.END);
            views.size = monospace(13);
            views.size.setGravity(Gravity.END);
            end.addView(views.size);
            views.date = monospace(13);
            views.date.setGravity(Gravity.END);
            end.addView(views.date);
            LinearLayout.LayoutParams endParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            endParams.setMarginStart(dp(12));
            layout.addView(end, endParams);
            layout.setTag(views);
            row = layout;
        }
        if (node == null) {
            views.icon.setImageResource(R.drawable.ic_terminow_folder);
            views.name.setText("..");
            views.icon.setContentDescription(mActivity.getString(R.string.terminow_sftp_up));
            views.permissions.setVisibility(View.GONE);
            views.size.setVisibility(View.GONE);
            views.date.setVisibility(View.GONE);
            return row;
        }
        views.icon.setImageResource(node.directory ? R.drawable.ic_terminow_folder : R.drawable.ic_terminow_file);
        views.icon.setContentDescription(null);
        views.name.setText(node.name);
        views.permissions.setText(node.permissions);
        views.permissions.setVisibility(node.permissions != null ? View.VISIBLE : View.GONE);
        views.size.setText(node.directory || node.size < 0 ? "" : formatSize(node.size, mActivity.getString(R.string.terminow_sftp_bytes)));
        views.size.setVisibility(node.directory || node.size < 0 ? View.GONE : View.VISIBLE);
        views.date.setText(node.modified > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(node.modified)) : "");
        views.date.setVisibility(node.modified > 0 ? View.VISIBLE : View.GONE);
        return row;
    }

    // ---------------------------------------------------------------------------------------
    // Menus
    // ---------------------------------------------------------------------------------------

    private static final class Item {
        final int icon;
        final String label;
        final Runnable action;
        final int trailing;
        final boolean divider;

        Item(@DrawableRes int icon, @NonNull String label, @NonNull Runnable action, @DrawableRes int trailing, boolean divider) {
            this.icon = icon;
            this.label = label;
            this.action = action;
            this.trailing = trailing;
            this.divider = divider;
        }
    }

    private void showMoreMenu(@NonNull View anchor, @NonNull Tab tab) {
        TerminowFs.Node folder = tab.folder();
        boolean ready = folder != null && !tab.connecting && tab.error == null;
        List<Item> items = new ArrayList<>();
        if (ready) items.add(new Item(R.drawable.ic_terminow_folder_add, mActivity.getString(R.string.terminow_sftp_new_folder),
            () -> createFolder(tab), 0, false));
        String sort = mStore.getSftpSort();
        boolean descending = mStore.isSftpSortDescending();
        String[] sorts = {TerminowStore.SFTP_SORT_NAME, TerminowStore.SFTP_SORT_DATE, TerminowStore.SFTP_SORT_SIZE, TerminowStore.SFTP_SORT_KIND};
        int[] icons = {R.drawable.ic_terminow_sort_name, R.drawable.ic_terminow_sort_date, R.drawable.ic_terminow_sort_size, R.drawable.ic_terminow_sort_kind};
        int[] labels = {R.string.terminow_sftp_sort_name, R.string.terminow_sftp_sort_date, R.string.terminow_sftp_sort_size, R.string.terminow_sftp_sort_kind};
        for (int i = 0; i < sorts.length; i++) {
            String value = sorts[i];
            boolean active = value.equals(sort);
            items.add(new Item(icons[i], mActivity.getString(labels[i]), () -> {
                // The sort that is used again turns around; another starts with the newest or largest first
                boolean down = active ? !descending : value.equals(TerminowStore.SFTP_SORT_DATE) || value.equals(TerminowStore.SFTP_SORT_SIZE);
                mStore.setSftpSort(value, down);
                render();
            }, active ? (descending ? R.drawable.ic_terminow_sort_down : R.drawable.ic_terminow_sort_up) : 0, i == 0 && ready));
        }
        items.add(new Item(R.drawable.ic_terminow_folder_change, mActivity.getString(R.string.terminow_sftp_download_folder),
            this::pickDownloadFolder, 0, true));
        if (folder != null) items.add(new Item(R.drawable.ic_terminow_chain, mActivity.getString(R.string.terminow_sftp_copy_path),
            () -> copyPath(folder.id), 0, false));
        boolean hidden = mStore.isSftpHiddenShown();
        items.add(new Item(hidden ? R.drawable.ic_terminow_hidden : R.drawable.ic_terminow_visible,
            mActivity.getString(hidden ? R.string.terminow_sftp_hide_hidden : R.string.terminow_sftp_show_hidden), () -> {
            mStore.setSftpHiddenShown(!hidden);
            render();
        }, 0, false));
        if (ready) items.add(new Item(R.drawable.ic_terminow_refresh, mActivity.getString(R.string.terminow_sftp_refresh), () -> load(tab), 0, false));
        items.add(new Item(R.drawable.ic_terminow_terminal, mActivity.getString(R.string.terminow_sftp_command_line),
            () -> useCommandLine(tab), 0, true));
        showMenu(anchor, items);
    }

    private void showItemMenu(@NonNull View anchor, @NonNull Tab tab, @NonNull TerminowFs.Node node) {
        List<Item> items = new ArrayList<>();
        if (node.directory) {
            items.add(new Item(R.drawable.ic_terminow_folder, mActivity.getString(R.string.terminow_sftp_open), () -> openFolder(tab, node), 0, false));
        } else {
            items.add(new Item(R.drawable.ic_terminow_launch, mActivity.getString(R.string.terminow_sftp_open_with), () -> openWith(tab, node, false), 0, false));
            items.add(new Item(R.drawable.ic_terminow_share, mActivity.getString(R.string.terminow_sftp_share), () -> openWith(tab, node, true), 0, false));
        }
        items.add(new Item(R.drawable.ic_terminow_download, mActivity.getString(R.string.terminow_sftp_download),
            () -> download(tab, Collections.singletonList(node)), 0, false));
        items.add(new Item(R.drawable.ic_terminow_rename, mActivity.getString(R.string.terminow_sftp_rename), () -> rename(tab, node), 0, true));
        items.add(new Item(R.drawable.ic_terminow_chain, mActivity.getString(R.string.terminow_sftp_copy_path), () -> copyPath(node.id), 0, false));
        items.add(new Item(R.drawable.ic_terminow_delete, mActivity.getString(R.string.terminow_sftp_delete), () -> delete(tab, node), 0, true));
        showMenu(anchor, items);
    }

    /** A menu like that of Termius: icons and names in a rounded panel, groups between lines. */
    private void showMenu(@NonNull View anchor, @NonNull List<Item> items) {
        LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackgroundResource(R.drawable.terminow_menu);
        menu.setPadding(0, dp(8), 0, dp(8));
        int width = Math.min(dp(320), mContainer.getWidth() - dp(32));
        PopupWindow popup = new PopupWindow(menu, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        for (Item item : items) {
            if (item.divider && menu.getChildCount() > 0) {
                View line = new View(mActivity);
                line.setBackgroundColor(color(R.color.terminow_panel_line));
                line.setAlpha(0.4f);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
                params.setMargins(dp(20), dp(6), dp(20), dp(6));
                menu.addView(line, params);
            }
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(20), dp(13), dp(20), dp(13));
            row.setBackgroundResource(selectableBackground());
            ImageView icon = new ImageView(mActivity);
            icon.setImageResource(item.icon);
            icon.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_text_secondary)));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
            iconParams.setMarginEnd(dp(20));
            row.addView(icon, iconParams);
            TextView label = new TextView(mActivity);
            label.setText(item.label);
            label.setTextColor(color(R.color.terminow_text));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (item.trailing != 0) {
                ImageView trailing = new ImageView(mActivity);
                trailing.setImageResource(item.trailing);
                trailing.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_text_secondary)));
                trailing.setContentDescription(mActivity.getString(item.trailing == R.drawable.ic_terminow_sort_down ?
                    R.string.terminow_sftp_sort_descending : R.string.terminow_sftp_sort_ascending));
                row.addView(trailing, new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            row.setOnClickListener(v -> {
                popup.dismiss();
                item.action.run();
            });
            menu.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        popup.showAsDropDown(anchor, anchor.getWidth() - width, 0, Gravity.NO_GRAVITY);
    }

    // ---------------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------------

    private void useCommandLine(@NonNull Tab tab) {
        TerminalSession session = mShown;
        if (session == null) return;
        if (tab.transfer != null) {
            toast(mActivity.getString(R.string.terminow_sftp_busy));
            return;
        }
        mTabs.remove(session);
        close(tab);
        mShown = null;
        render();
        mContainer.setVisibility(View.GONE);
        tab.commandLine.run();
    }

    private void copyPath(@NonNull String path) {
        ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(path, path));
        toast(mActivity.getString(R.string.terminow_sftp_path_copied));
    }

    private void createFolder(@NonNull Tab tab) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null) return;
        askName(R.string.terminow_sftp_new_folder, "", name ->
            background(() -> tab.fs.createDirectory(folder, name), created -> load(tab), e -> toast(e.getMessage())));
    }

    private void rename(@NonNull Tab tab, @NonNull TerminowFs.Node node) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null) return;
        askName(R.string.terminow_sftp_rename, node.name, name -> {
            if (name.equals(node.name)) return;
            background(() -> tab.fs.rename(node, folder, name), renamed -> load(tab), e -> toast(e.getMessage()));
        });
    }

    private void delete(@NonNull Tab tab, @NonNull TerminowFs.Node node) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null) return;
        dialog()
            .setMessage(mActivity.getString(node.directory ? R.string.terminow_sftp_delete_folder : R.string.terminow_sftp_delete_file, node.name))
            .setPositiveButton(R.string.terminow_sftp_delete, (d, w) -> background(() -> {
                tab.fs.delete(node, folder, new TerminowFs.Cancel());
                return Boolean.TRUE;
            }, done -> load(tab), e -> {
                toast(e.getMessage());
                load(tab);
            }))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void askName(@StringRes int title, @NonNull String name, @NonNull Consumer<String> done) {
        EditText field = new EditText(mActivity);
        field.setSingleLine(true);
        field.setText(name);
        field.setHint(R.string.terminow_sftp_name_hint);
        field.setTextColor(color(R.color.terminow_text));
        // The name without its extension is selected, to type a new one
        int dot = name.lastIndexOf('.');
        field.setSelection(0, dot > 0 ? dot : name.length());
        LinearLayout box = new LinearLayout(mActivity);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = dialog()
            .setTitle(title)
            .setView(box)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String text = field.getText().toString().trim();
                if (!text.isEmpty()) done.accept(text);
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        field.requestFocus();
        mHandler.post(() -> KeyboardUtils.showSoftKeyboard(mActivity, field));
        dialog.setOnDismissListener(d -> KeyboardUtils.hideSoftKeyboard(mActivity, field));
    }

    /** Upload picked files to the folder that is shown: each is complete or not there at all. */
    private void upload(@NonNull Tab tab, @NonNull List<Uri> uris) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null || tab.transfer != null) return;
        TerminowFs.Cancel cancel = startTransfer(tab, mActivity.getString(R.string.terminow_sftp_uploading, ""));
        mExecutor.execute(() -> {
            int uploaded = 0;
            String error = null;
            for (int i = 0; i < uris.size() && !cancel.isCancelled(); i++) {
                Uri uri = uris.get(i);
                String name = null;
                long size = -1;
                try (Cursor cursor = mActivity.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        if (!cursor.isNull(0)) name = cursor.getString(0);
                        if (!cursor.isNull(1)) size = cursor.getLong(1);
                    }
                } catch (RuntimeException e) {
                    // The name of the link is used
                }
                if (name == null) name = uri.getLastPathSegment();
                name = name == null ? "upload" : name.substring(name.lastIndexOf('/') + 1).replace('\0', '_');
                if (name.isEmpty() || name.equals(".") || name.equals("..")) name = "upload";
                try {
                    if (uploadOne(tab, folder, uri, name, size, i, uris.size(), cancel)) uploaded++;
                } catch (TerminowFs.CancelledException e) {
                    break;
                } catch (IOException | RuntimeException e) {
                    Logger.logStackTraceWithMessage(LOG_TAG, "Failed to upload " + name, e);
                    error = name + ": " + e.getMessage();
                }
            }
            int count = uploaded;
            String failure = error;
            mHandler.post(() -> {
                finishTransfer(tab);
                if (cancel.isCancelled()) toast(mActivity.getString(R.string.terminow_sftp_cancelled));
                else if (failure != null) toast(mActivity.getString(R.string.terminow_sftp_transfer_failed, failure));
                else if (count > 0) toast(mActivity.getString(R.string.terminow_sftp_uploaded, folder.id));
                if (tab.folder() == folder) load(tab);
            });
        });
    }

    /** Returns {@code false} if the user skipped the file. */
    private boolean uploadOne(@NonNull Tab tab, @NonNull TerminowFs.Node folder, @NonNull Uri uri, @NonNull String name, long size,
                              int index, int count, @NonNull TerminowFs.Cancel cancel) throws IOException {
        TerminowFs.Node existing = tab.fs.find(folder, name);
        String target = name;
        boolean replace = false;
        if (existing != null) {
            TerminowTransfer.Resolution resolution = askConflict(name);
            if (resolution == TerminowTransfer.Resolution.SKIP) return false;
            if (resolution == TerminowTransfer.Resolution.REPLACE && !existing.directory) {
                replace = true;
            } else {
                for (int n = 1; tab.fs.find(folder, target) != null; n++) target = numberedName(name, n);
            }
        }
        String label = count > 1 ? (index + 1) + "/" + count + " · " + name : name;
        String partial = target + PARTIAL_SUFFIX;
        boolean written = false;
        try {
            try (InputStream in = mActivity.getContentResolver().openInputStream(uri);
                 OutputStream out = tab.fs.openOutput(folder, partial, true)) {
                if (in == null) throw new IOException("Failed to open " + name);
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                long shown = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    cancel.check();
                    out.write(buffer, 0, read);
                    done += read;
                    if (done - shown > 256 * 1024 || shown == 0) {
                        shown = done;
                        progress(tab, mActivity.getString(R.string.terminow_sftp_uploading, label), done, size);
                    }
                }
            }
            TerminowFs.Node node = tab.fs.find(folder, partial);
            if (node == null) throw new IOException("The upload of " + name + " is gone");
            // Only a complete file replaces the one that was there
            if (replace) {
                TerminowFs.Node old = tab.fs.find(folder, target);
                if (old != null) tab.fs.delete(old, folder, cancel);
            }
            tab.fs.rename(node, folder, target);
            written = true;
            return true;
        } finally {
            if (!written) {
                try {
                    TerminowFs.Node node = tab.fs.find(folder, partial);
                    if (node != null) tab.fs.delete(node, folder, new TerminowFs.Cancel());
                } catch (IOException | RuntimeException e) {
                    // The partial file stays, with its name that tells what it is
                }
            }
        }
    }

    /** Download to the folder of downloads: Download, or the one the user picked. */
    private void download(@NonNull Tab tab, @NonNull List<TerminowFs.Node> nodes) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null) return;
        if (tab.transfer != null) {
            toast(mActivity.getString(R.string.terminow_sftp_busy));
            return;
        }
        TerminowFs target = downloadTarget();
        if (target == null) {
            mPendingDownload = () -> download(tab, nodes);
            toast(mActivity.getString(R.string.terminow_sftp_pick_download_folder));
            pickDownloadFolder();
            return;
        }
        String name = nodes.size() == 1 ? nodes.get(0).name : String.valueOf(nodes.size());
        TerminowFs.Cancel cancel = startTransfer(tab, mActivity.getString(R.string.terminow_sftp_downloading, name));
        mExecutor.execute(() -> {
            TerminowTransfer.Result result;
            String error = null;
            try {
                result = new TerminowTransfer(tab.fs, target, false, new TerminowTransfer.Listener() {
                    @Override
                    public void onProgress(@NonNull String file, long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
                        progress(tab, mActivity.getString(R.string.terminow_sftp_downloading, file), bytesDone, bytesTotal);
                    }

                    @NonNull
                    @Override
                    public TerminowTransfer.Resolution onConflict(@NonNull String file) {
                        return askConflict(file);
                    }
                }, cancel).run(folder, nodes, target.getRoot());
                if (!result.errors.isEmpty()) error = TerminowTransfer.describe(result);
            } catch (IOException | RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to download", e);
                error = e.getMessage();
            }
            String failure = error;
            mHandler.post(() -> {
                finishTransfer(tab);
                if (cancel.isCancelled()) toast(mActivity.getString(R.string.terminow_sftp_cancelled));
                else if (failure != null) toast(mActivity.getString(R.string.terminow_sftp_transfer_failed, failure));
                else toast(mActivity.getString(R.string.terminow_sftp_downloaded, target.getTitle()));
            });
        });
    }

    /**
     * The folder that downloads go to: the one the user picked while it can still be written,
     * else Download when TermiNow may use the storage of the phone; {@code null} to ask.
     */
    @Nullable
    private TerminowFs downloadTarget() {
        String picked = mStore.getSftpDownloadFolder();
        if (!picked.isEmpty()) {
            Uri tree = Uri.parse(picked);
            for (UriPermission permission : mActivity.getContentResolver().getPersistedUriPermissions())
                if (permission.getUri().equals(tree) && permission.isWritePermission())
                    return new TerminowSafFs(mActivity, tree, folderLabel(tree));
        }
        if (TerminowStorageAccess.hasAccess(mActivity)) {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            try {
                if (downloads.isDirectory() || downloads.mkdirs()) return new TerminowLocalFs(downloads, downloads.getName());
            } catch (IOException e) {
                // The folder is asked for
            }
        }
        return null;
    }

    private void pickDownloadFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            mActivity.startActivityForResult(intent, REQUEST_DOWNLOAD_FOLDER);
        } catch (ActivityNotFoundException e) {
            mPendingDownload = null;
            toast(mActivity.getString(R.string.terminow_explorer_no_app));
        }
    }

    /** "Download/Server" for a picked folder, from its document id like "primary:Download/Server". */
    @NonNull
    private static String folderLabel(@NonNull Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return path.isEmpty() ? id : path;
        } catch (IllegalArgumentException e) {
            return tree.toString();
        }
    }

    /** Download a file to the cache, then open it in another app or share it. */
    private void openWith(@NonNull Tab tab, @NonNull TerminowFs.Node file, boolean share) {
        TerminowFs.Node folder = tab.folder();
        if (folder == null) return;
        if (tab.transfer != null) {
            toast(mActivity.getString(R.string.terminow_sftp_busy));
            return;
        }
        TerminowFs.Cancel cancel = startTransfer(tab, mActivity.getString(R.string.terminow_sftp_downloading, file.name));
        mExecutor.execute(() -> {
            Uri uri = null;
            String error = null;
            try {
                File dir = new File(mActivity.getCacheDir(), "explorer/" + UUID.randomUUID());
                if (!dir.mkdirs()) throw new IOException("Failed to prepare the download");
                TerminowLocalFs cache = new TerminowLocalFs(dir, "cache");
                TerminowTransfer.Result result = new TerminowTransfer(tab.fs, cache, false, new TerminowTransfer.Listener() {
                    @Override
                    public void onProgress(@NonNull String name, long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
                        progress(tab, mActivity.getString(R.string.terminow_sftp_downloading, name), bytesDone, bytesTotal);
                    }

                    @NonNull
                    @Override
                    public TerminowTransfer.Resolution onConflict(@NonNull String name) {
                        return TerminowTransfer.Resolution.REPLACE;
                    }
                }, cancel).run(folder, Collections.singletonList(file), cache.getRoot());
                File downloaded = new File(dir, file.name);
                if (!result.errors.isEmpty() || !downloaded.isFile()) throw new IOException(TerminowTransfer.describe(result));
                uri = FileProvider.getUriForFile(mActivity, mActivity.getPackageName() + ".explorer", downloaded);
            } catch (IOException | RuntimeException e) {
                error = e.getMessage();
            }
            Uri result = uri;
            String failure = error;
            mHandler.post(() -> {
                finishTransfer(tab);
                if (cancel.isCancelled()) return;
                if (result == null) {
                    toast(mActivity.getString(R.string.terminow_sftp_transfer_failed, failure));
                    return;
                }
                Intent intent;
                if (share) {
                    intent = new Intent(Intent.ACTION_SEND);
                    intent.putExtra(Intent.EXTRA_STREAM, result);
                    intent.setType(mimeType(file.name));
                } else {
                    intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(result, mimeType(file.name));
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    mActivity.startActivity(Intent.createChooser(intent, file.name));
                } catch (ActivityNotFoundException e) {
                    toast(mActivity.getString(R.string.terminow_explorer_no_app));
                }
            });
        });
    }

    @NonNull
    private static String mimeType(@NonNull String name) {
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(TerminowFileScan.extension(name).toLowerCase(Locale.ROOT));
        return type != null ? type : "*/*";
    }

    @NonNull
    private TerminowFs.Cancel startTransfer(@NonNull Tab tab, @NonNull String text) {
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        tab.transfer = cancel;
        tab.transferText = text;
        tab.transferPercent = -1;
        renderIfShown(tab);
        return cancel;
    }

    private void finishTransfer(@NonNull Tab tab) {
        tab.transfer = null;
        renderIfShown(tab);
    }

    /** The progress of a transfer, from its worker. */
    private void progress(@NonNull Tab tab, @NonNull String text, long done, long total) {
        int percent = total > 0 ? (int) Math.min(100, done * 100 / total) : -1;
        mHandler.post(() -> {
            tab.transferText = text;
            tab.transferPercent = percent;
            if (shownTab() != tab || mTransferText == null || mTransferBar == null) return;
            mTransferText.setText(text);
            mTransferBar.setIndeterminate(percent < 0);
            if (percent >= 0) mTransferBar.setProgress(percent);
        });
    }

    /** What to do with a file that is there already, asked from a worker, which waits. */
    @NonNull
    private TerminowTransfer.Resolution askConflict(@NonNull String name) {
        AtomicReference<TerminowTransfer.Resolution> answer = new AtomicReference<>(TerminowTransfer.Resolution.SKIP);
        waitForUi(latch -> dialog()
            .setTitle(mActivity.getString(R.string.terminow_sftp_exists, name))
            .setCancelable(false)
            .setPositiveButton(R.string.terminow_sftp_replace, (d, w) -> {
                answer.set(TerminowTransfer.Resolution.REPLACE);
                latch.countDown();
            })
            .setNeutralButton(R.string.terminow_sftp_keep_both, (d, w) -> {
                answer.set(TerminowTransfer.Resolution.KEEP_BOTH);
                latch.countDown();
            })
            .setNegativeButton(R.string.terminow_sftp_skip, (d, w) -> latch.countDown())
            .show());
        return answer.get();
    }

    /** The questions of a connection: an unknown host key, a password, the passphrase of a key. */
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
                field.setTextColor(color(R.color.terminow_text));
                LinearLayout box = new LinearLayout(mActivity);
                box.setPadding(dp(20), dp(4), dp(20), 0);
                box.addView(field, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private interface Work<T> {
        T run() throws IOException;
    }

    private <T> void background(@NonNull Work<T> work, @NonNull Consumer<T> done, @NonNull Consumer<Exception> failed) {
        mExecutor.execute(() -> {
            try {
                T result = work.run();
                mHandler.post(() -> done.accept(result));
            } catch (IOException | RuntimeException e) {
                Logger.logDebug(LOG_TAG, "SFTP: " + e);
                mHandler.post(() -> failed.accept(e));
            }
        });
    }

    /** Show a dialog from a worker and wait until the user answered it. */
    private void waitForUi(@NonNull Consumer<CountDownLatch> show) {
        CountDownLatch latch = new CountDownLatch(1);
        mHandler.post(() -> {
            if (mActivity.isFinishing() || mActivity.isDestroyed()) {
                latch.countDown();
                return;
            }
            show.accept(latch);
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @NonNull
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog);
    }

    @NonNull
    private ImageButton iconButton(@DrawableRes int icon, @StringRes int label) {
        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(color(R.color.terminow_text)));
        button.setBackgroundResource(selectableBackgroundBorderless());
        button.setContentDescription(mActivity.getString(label));
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return button;
    }

    @NonNull
    private TextView textButton(@StringRes int label, @NonNull Runnable action) {
        TextView button = new TextView(mActivity);
        button.setText(label);
        button.setTextColor(color(R.color.terminow_accent));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setBackgroundResource(selectableBackground());
        button.setOnClickListener(v -> action.run());
        return button;
    }

    @NonNull
    private TextView monospace(int sp) {
        TextView text = new TextView(mActivity);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(color(R.color.terminow_text_secondary));
        text.setSingleLine(true);
        return text;
    }

    private int selectableBackground() {
        TypedValue value = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return value.resourceId;
    }

    private int selectableBackgroundBorderless() {
        TypedValue value = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true);
        return value.resourceId;
    }

    private int color(int res) {
        return ContextCompat.getColor(mActivity, res);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private void toast(@Nullable String text) {
        if (text != null) mActivity.showToast(text, false);
    }
}
