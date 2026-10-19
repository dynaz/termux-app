package com.termux.app.terminow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.linux.TerminowLinuxEnv;
import com.termux.app.terminow.linux.TerminowLinuxFs;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Backup &amp; Restore of a Linux environment: to the phone (Premium), to a server (the dialog of
 * {@link TerminowBackup}), and restore from the phone (for everyone). The work is done by
 * {@link TerminowPhoneBackup}; this only asks, shows and follows it.
 */
final class TerminowBackupUi {

    interface Host {
        /** The backup to a server of the environment. */
        void backupToServer(@NonNull String alias, @NonNull String title);

        /** The environments changed, like after a restore. */
        void environmentsChanged();
    }

    static final int REQUEST_TREE = 2007;
    static final int REQUEST_RESTORE = 2008;

    private static final String KEY_TREE = "backup_tree_uri";

    private final TermuxActivity mActivity;
    private final TerminowStore mStore;
    private final TerminowLinuxUi mLinux;
    private final TerminowLauncher mLauncher;
    private final Host mHost;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    /** The form whose folder is being picked with the file picker of Android. */
    @Nullable private Form mPicking;
    @Nullable private AlertDialog mProgress;
    private final Runnable mOnJob = this::showJobState;
    /** The job whose end was already handled, so that it is handled once. */
    @Nullable private TerminowPhoneBackup.Job mEnded;

    TerminowBackupUi(@NonNull TermuxActivity activity, @NonNull TerminowStore store, @NonNull TerminowLinuxUi linux,
                     @NonNull TerminowLauncher launcher, @NonNull Host host) {
        mActivity = activity;
        mStore = store;
        mLinux = linux;
        mLauncher = launcher;
        mHost = host;
        // The end of a job is handled also when its dialog was hidden
        TerminowPhoneBackup.addListener(this::onJobChanged);
    }

    // ---------------------------------------------------------------------------------------
    // Menu

    /** Backup &amp; Restore of an environment; a job that runs is shown instead. */
    void showMenu(@NonNull String alias, @NonNull String title) {
        if (TerminowPhoneBackup.isRunning()) {
            showProgress();
            return;
        }
        boolean premium = TerminowPhoneBackup.mayBackUp(mStore);
        CharSequence[] items = {
            mActivity.getString(premium ? R.string.terminow_phone_backup_menu : R.string.terminow_backup_menu_premium),
            mActivity.getString(R.string.terminow_backup_menu),
            mActivity.getString(R.string.terminow_phone_restore_menu)};
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(mActivity.getString(R.string.terminow_backup_restore_title, title))
            .setItems(items, (d, which) -> {
                if (which == 0) showBackup(alias, title);
                else if (which == 1) mHost.backupToServer(alias, title);
                else pickRestoreFile();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Backup

    /** What the backup form holds. */
    private static final class Form {
        TerminowLinuxEnv env;
        String title;
        boolean folders;
        final List<String> sources = new ArrayList<>();
        @Nullable Uri tree;
        boolean nameEdited;
        EditText nameView;
        TextView treeView;
        LinearLayout sourcesView;
        View sourcesSection;
        TextView note;
        AlertDialog dialog;
    }

    /** The backup to the phone, for Premium; without it the upgrade of Premium is shown. */
    void showBackup(@NonNull String alias, @NonNull String title) {
        TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.BACKUP, 0, () -> {
            TerminowLinuxEnv env = mLinux.envOf(alias);
            if (env == null) {
                mActivity.showToast(mActivity.getString(R.string.terminow_profiles_env_missing, alias), true);
                return;
            }
            showForm(env, title);
        });
    }

    private void showForm(@NonNull TerminowLinuxEnv env, @NonNull String title) {
        Form form = new Form();
        form.env = env;
        form.title = title;
        form.tree = rememberedTree();
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog);
        Context context = builder.getContext();
        LinearLayout box = vertical(context);
        int padding = dp(20);
        box.setPadding(padding, dp(4), padding, 0);

        box.addView(label(context, mActivity.getString(R.string.terminow_backup_instance, title, env.alias,
            env.prettyName.isEmpty() ? env.distroId : env.prettyName, env.arch), false));

        RadioGroup modes = new RadioGroup(context);
        RadioButton full = new RadioButton(context);
        full.setId(View.generateViewId());
        full.setText(R.string.terminow_backup_mode_full);
        RadioButton folders = new RadioButton(context);
        folders.setId(View.generateViewId());
        folders.setText(R.string.terminow_backup_mode_folders_title);
        modes.addView(full);
        modes.addView(folders);
        modes.setPadding(0, dp(8), 0, 0);
        box.addView(modes);

        // Source folders: in the environment
        LinearLayout sourcesSection = vertical(context);
        sourcesSection.addView(heading(context, R.string.terminow_backup_sources));
        sourcesSection.addView(label(context, mActivity.getString(R.string.terminow_backup_sources_hint), true));
        form.sourcesView = vertical(context);
        sourcesSection.addView(form.sourcesView);
        TextView add = button(context, R.string.terminow_backup_add_folders);
        add.setOnClickListener(v -> showFolderPicker(env, true, "/root", form.sources, picked -> {
            List<String> merged = new ArrayList<>(form.sources);
            merged.addAll(picked);
            List<String> normalized = TerminowBackupArchive.normalizeSources(merged);
            if (normalized == null) {
                mActivity.showToast(mActivity.getString(R.string.terminow_backup_folder_not_allowed), true);
                return;
            }
            form.sources.clear();
            form.sources.addAll(normalized);
            renderSources(form);
        }));
        sourcesSection.addView(add);
        form.sourcesSection = sourcesSection;
        box.addView(sourcesSection);

        // Save backup to: a folder of Android
        box.addView(heading(context, R.string.terminow_backup_destination));
        form.treeView = label(context, "", false);
        box.addView(form.treeView);
        TextView choose = button(context, R.string.terminow_backup_choose_folder);
        choose.setOnClickListener(v -> pickTree(form));
        box.addView(choose);
        box.addView(label(context, mActivity.getString(R.string.terminow_backup_file_name), true));
        form.nameView = new EditText(context);
        form.nameView.setSingleLine(true);
        form.nameView.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        form.nameView.setText(TerminowBackupArchive.fileName(env.alias, TerminowBackupArchive.TYPE_FULL, System.currentTimeMillis()));
        form.nameView.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (form.nameView.hasFocus()) form.nameEdited = true;
            }
        });
        box.addView(form.nameView);
        form.note = label(context, "", true);
        box.addView(form.note);

        modes.setOnCheckedChangeListener((group, id) -> {
            form.folders = id == folders.getId();
            sourcesSection.setVisibility(form.folders ? View.VISIBLE : View.GONE);
            form.note.setText(form.folders ? R.string.terminow_backup_note_folders_phone : R.string.terminow_backup_note_full_phone);
            if (!form.nameEdited)
                form.nameView.setText(TerminowBackupArchive.fileName(env.alias, form.folders ? TerminowBackupArchive.TYPE_FOLDERS :
                    TerminowBackupArchive.TYPE_FULL, System.currentTimeMillis()));
        });
        modes.check(full.getId());
        renderSources(form);
        renderTree(form);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        AlertDialog dialog = builder
            .setTitle(mActivity.getString(R.string.terminow_backup_phone_title, title))
            .setView(scroll)
            .setPositiveButton(R.string.terminow_backup_next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        form.dialog = dialog;
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = fileNameOf(form.nameView.getText().toString());
            if (name == null) {
                form.nameView.setError(mActivity.getString(R.string.terminow_backup_bad_name));
                return;
            }
            if (form.folders && form.sources.isEmpty()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_backup_no_sources), true);
                return;
            }
            if (form.tree == null) {
                mActivity.showToast(mActivity.getString(R.string.terminow_backup_no_destination), true);
                pickTree(form);
                return;
            }
            dialog.dismiss();
            check(form, name);
        }));
        dialog.show();
    }

    private void renderSources(@NonNull Form form) {
        form.sourcesView.removeAllViews();
        Context context = form.sourcesView.getContext();
        if (form.sources.isEmpty()) {
            form.sourcesView.addView(label(context, mActivity.getString(R.string.terminow_backup_no_sources_yet), true));
            return;
        }
        for (String source : form.sources) {
            LinearLayout row = new LinearLayout(context);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView path = label(context, source, false);
            path.setTypeface(Typeface.MONOSPACE);
            row.addView(path, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            ImageView remove = new ImageView(context);
            remove.setImageResource(R.drawable.ic_terminow_close);
            remove.setColorFilter(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
            remove.setContentDescription(mActivity.getString(R.string.terminow_backup_remove_source, source));
            remove.setPadding(dp(8), dp(8), dp(8), dp(8));
            remove.setOnClickListener(v -> {
                form.sources.remove(source);
                renderSources(form);
            });
            row.addView(remove, new LinearLayout.LayoutParams(dp(40), dp(40)));
            form.sourcesView.addView(row);
        }
    }

    private void renderTree(@NonNull Form form) {
        form.treeView.setText(form.tree == null ? mActivity.getString(R.string.terminow_backup_destination_none) : treeLabel(form.tree));
    }

    /** The folder of the last backup, if the app may still write to it. */
    @Nullable
    private Uri rememberedTree() {
        String value = mActivity.getSharedPreferences("terminow_backup", Context.MODE_PRIVATE).getString(KEY_TREE, "");
        if (value.isEmpty()) return null;
        Uri uri = Uri.parse(value);
        for (UriPermission permission : mActivity.getContentResolver().getPersistedUriPermissions())
            if (permission.getUri().equals(uri) && permission.isWritePermission()) return uri;
        return null;
    }

    private void pickTree(@NonNull Form form) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        mPicking = form;
        try {
            mActivity.startActivityForResult(intent, REQUEST_TREE);
        } catch (Exception e) {
            mPicking = null;
            mActivity.showToast(String.valueOf(e.getMessage()), true);
        }
    }

    /** A result of the file pickers of Android; returns whether it was one of these. */
    boolean onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_TREE) {
            Form form = mPicking;
            mPicking = null;
            if (form == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
            Uri tree = data.getData();
            // Kept for the next backup where the provider lets it be kept; a content URI, never a path
            try {
                mActivity.getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                mActivity.getSharedPreferences("terminow_backup", Context.MODE_PRIVATE).edit().putString(KEY_TREE, tree.toString()).apply();
            } catch (SecurityException e) {
                // Only for now
            }
            form.tree = tree;
            renderTree(form);
            return true;
        }
        if (requestCode == REQUEST_RESTORE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) inspect(data.getData());
            return true;
        }
        return false;
    }

    /** A file name for the backup, with .tar.gz; {@code null} if it can not be one. */
    @Nullable
    static String fileNameOf(@NonNull String text) {
        String name = text.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\0") || name.length() > 200 || name.startsWith(".")) return null;
        if (!name.endsWith(".tar.gz")) name = name.endsWith(".tar") ? name + ".gz" : name + ".tar.gz";
        return name;
    }

    /** How big it is and how much space there is, found out in the background, then the summary. */
    private void check(@NonNull Form form, @NonNull String name) {
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        AlertDialog checking = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_backup_stage_checking)
            .setView(spinner())
            .setNegativeButton(android.R.string.cancel, (d, w) -> cancel.cancel())
            .setCancelable(false)
            .show();
        List<String> sources = new ArrayList<>(form.sources);
        mExecutor.execute(() -> {
            long estimate;
            try {
                estimate = form.folders ? mLinux.sizeOf(form.env, sources, cancel) : mLinux.fullSizeOf(form.env, cancel);
            } catch (Exception e) {
                estimate = 0;
            }
            long free = freeSpaceOf(form.tree);
            long size = estimate;
            mHandler.post(() -> {
                if (mActivity.isFinishing()) return;
                checking.dismiss();
                if (cancel.isCancelled()) return;
                showSummary(form, name, size, free);
            });
        });
    }

    /** The free space of the picked folder when it is in the storage of the phone, -1 when it can not be known. */
    private static long freeSpaceOf(@Nullable Uri tree) {
        if (tree == null || !"com.android.externalstorage.documents".equals(tree.getAuthority())) return -1;
        try {
            if (!DocumentsContract.getTreeDocumentId(tree).startsWith("primary:")) return -1;
            return Environment.getExternalStorageDirectory().getUsableSpace();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** What will be backed up, where to, and what to stop first; then it starts. */
    private void showSummary(@NonNull Form form, @NonNull String name, long estimate, long free) {
        StringBuilder text = new StringBuilder();
        text.append(mActivity.getString(R.string.terminow_backup_summary_instance, form.title, form.env.alias)).append('\n');
        text.append(mActivity.getString(R.string.terminow_backup_summary_mode, mActivity.getString(form.folders ?
            R.string.terminow_backup_mode_folders_title : R.string.terminow_backup_mode_full))).append('\n');
        if (form.folders) {
            text.append(mActivity.getString(R.string.terminow_backup_summary_sources)).append('\n');
            for (String source : form.sources) text.append("  • ").append(source).append('\n');
        }
        text.append(mActivity.getString(R.string.terminow_backup_summary_file, name)).append('\n');
        text.append(mActivity.getString(R.string.terminow_backup_summary_destination, form.tree != null ? treeLabel(form.tree) : "")).append('\n');
        if (estimate > 0) text.append(mActivity.getString(R.string.terminow_backup_summary_size, TerminowPhoneBackup.human(estimate))).append('\n');
        if (free >= 0) text.append(mActivity.getString(R.string.terminow_backup_summary_free, TerminowPhoneBackup.human(free))).append('\n');
        if (free >= 0 && estimate > 0 && free < estimate)
            text.append('\n').append(mActivity.getString(R.string.terminow_backup_summary_space_warning)).append('\n');
        text.append('\n').append(mActivity.getString(R.string.terminow_backup_summary_stop_writing));
        List<TerminalSession> sessions = mLauncher.sessionsOf(form.env.alias);
        if (!form.folders && !sessions.isEmpty())
            text.append("\n\n").append(mActivity.getResources().getQuantityString(R.plurals.terminow_backup_summary_close_tabs,
                sessions.size(), sessions.size(), form.title));
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(R.string.terminow_backup_summary_title)
            .setMessage(text.toString())
            .setPositiveButton(R.string.terminow_backup_start, (d, w) -> startBackup(form, name, estimate))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void startBackup(@NonNull Form form, @NonNull String name, long estimate) {
        TerminowPhoneBackup.Request request = new TerminowPhoneBackup.Request();
        request.kind = form.folders ? TerminowPhoneBackup.Kind.BACKUP_FOLDERS : TerminowPhoneBackup.Kind.BACKUP_FULL;
        request.alias = form.env.alias;
        request.title = form.title;
        request.distro = form.env.distroId + (form.env.version.isEmpty() ? "" : " " + form.env.version);
        request.architecture = form.env.arch;
        request.appVersion = "TermiNow " + com.termux.BuildConfig.VERSION_NAME + " (" + com.termux.BuildConfig.VERSION_CODE + ")";
        request.sources = new ArrayList<>(form.sources);
        request.binds = mLinux.bindsOf(form.env);
        request.tree = form.tree;
        request.fileName = name;
        request.estimate = estimate;
        start(request);
    }

    private void start(@NonNull TerminowPhoneBackup.Request request) {
        TerminowPhoneBackup.Job job = TerminowPhoneBackup.start(mActivity, mStore, mLinux.newRunner(),
            com.termux.shared.termux.TermuxConstants.TERMUX_PREFIX_DIR_PATH, request);
        if (job == null) {
            if (TerminowPhoneBackup.isRunning()) {
                mActivity.showToast(mActivity.getString(R.string.terminow_phone_backup_busy), true);
                showProgress();
            } else {
                // Premium was checked again where the job starts, and it was not there
                TerminowPremiumGate.require(mActivity, mStore, TerminowPremiumGate.Feature.BACKUP, 0, () -> {});
            }
            return;
        }
        showProgress();
    }

    // ---------------------------------------------------------------------------------------
    // Progress

    /** The progress of the job, which keeps running when this is hidden. */
    void showProgress() {
        TerminowPhoneBackup.Job job = TerminowPhoneBackup.current();
        if (job == null) return;
        if (mProgress != null && mProgress.isShowing()) return;
        AlertDialog dialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(titleOf(job))
            .setMessage(" ")
            .setPositiveButton(R.string.terminow_phone_backup_hide, null)
            .setNegativeButton(R.string.terminow_phone_backup_cancel, null)
            .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setMessage(job.isBackup() ? R.string.terminow_phone_backup_cancel_confirm : R.string.terminow_backup_cancel_restore_confirm)
                .setPositiveButton(R.string.terminow_phone_backup_cancel, (c, w) -> {
                    if (!TerminowPhoneBackup.cancel(mLinux.newRunner()))
                        mActivity.showToast(mActivity.getString(R.string.terminow_backup_cannot_stop_now), true);
                })
                .setNegativeButton(R.string.terminow_phone_backup_keep, null)
                .show()));
        dialog.setOnDismissListener(d -> {
            TerminowPhoneBackup.removeListener(mOnJob);
            if (mProgress == dialog) mProgress = null;
        });
        mProgress = dialog;
        TerminowPhoneBackup.addListener(mOnJob);
        dialog.show();
        showJobState();
    }

    @NonNull
    private String titleOf(@NonNull TerminowPhoneBackup.Job job) {
        switch (job.request.kind) {
            case INSPECT: return mActivity.getString(R.string.terminow_backup_checking_title);
            case RESTORE_FULL:
            case RESTORE_FOLDERS: return mActivity.getString(R.string.terminow_backup_notification_restore, job.request.title);
            default: return mActivity.getString(R.string.terminow_backup_notification_backup, job.request.title);
        }
    }

    private void showJobState() {
        AlertDialog dialog = mProgress;
        TerminowPhoneBackup.Job job = TerminowPhoneBackup.current();
        if (dialog == null || !dialog.isShowing() || job == null) return;
        StringBuilder text = new StringBuilder(TerminowPhoneBackup.describe(mActivity, job));
        if (job.isBackup()) text.append("\n\n").append(mActivity.getString(R.string.terminow_backup_summary_file, job.request.fileName));
        else if (job.request.kind != TerminowPhoneBackup.Kind.INSPECT)
            text.append("\n\n").append(mActivity.getString(R.string.terminow_backup_summary_file, job.request.fileLabel));
        if (job.state == TerminowPhoneBackup.State.SUCCEEDED && job.isBackup()) {
            TerminowBackupArchive.Inspection inspection = job.inspection;
            text.append("\n\n").append(mActivity.getString(R.string.terminow_backup_done_checked,
                inspection != null ? inspection.entries : 0, TerminowPhoneBackup.human(inspection != null ? inspection.contentBytes : 0),
                job.request.tree != null ? treeLabel(job.request.tree) : ""));
        }
        if (!job.error.isEmpty()) text.append("\n\n").append(job.error);
        dialog.setMessage(text);
        boolean running = job.state == TerminowPhoneBackup.State.RUNNING;
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setVisibility(running && TerminowPhoneBackup.canCancel(job) ? View.VISIBLE : View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(running ? R.string.terminow_phone_backup_hide : android.R.string.ok);
        // An inspection goes on to the restore by itself
        if (job.request.kind == TerminowPhoneBackup.Kind.INSPECT && job.state == TerminowPhoneBackup.State.SUCCEEDED) dialog.dismiss();
    }

    /** The end of a job, also when its dialog is hidden: what it found, or that it is done. */
    private void onJobChanged() {
        TerminowPhoneBackup.Job job = TerminowPhoneBackup.current();
        if (job == null || job.state == TerminowPhoneBackup.State.RUNNING || job == mEnded) return;
        mEnded = job;
        boolean shown = mProgress != null && mProgress.isShowing();
        switch (job.request.kind) {
            case INSPECT:
                if (job.state == TerminowPhoneBackup.State.SUCCEEDED) afterInspection(job);
                else if (!shown && job.state == TerminowPhoneBackup.State.FAILED) showProgress();
                return;
            case RESTORE_FULL:
            case RESTORE_FOLDERS:
                mHost.environmentsChanged();
                break;
            default:
                break;
        }
        if (shown) return;
        if (job.state == TerminowPhoneBackup.State.FAILED) showProgress();
        else if (job.state == TerminowPhoneBackup.State.SUCCEEDED)
            mActivity.showToast(mActivity.getString(job.isBackup() ? R.string.terminow_phone_backup_done_toast : R.string.terminow_phone_restore_done_toast,
                job.request.title, job.isBackup() ? job.request.fileName : job.request.fileLabel), true);
    }

    // ---------------------------------------------------------------------------------------
    // Restore

    /** The file picker of Android for a backup to restore, for everyone; a job that runs is shown instead. */
    void pickRestoreFile() {
        if (TerminowPhoneBackup.isRunning()) {
            showProgress();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            mActivity.startActivityForResult(intent, REQUEST_RESTORE);
        } catch (Exception e) {
            mActivity.showToast(String.valueOf(e.getMessage()), true);
        }
    }

    /** Read the whole file and find out what it is, before anything is restored. */
    private void inspect(@NonNull Uri file) {
        TerminowPhoneBackup.Request request = new TerminowPhoneBackup.Request();
        request.kind = TerminowPhoneBackup.Kind.INSPECT;
        request.file = file;
        request.title = displayNameOf(file);
        request.fileLabel = request.title;
        try (android.database.Cursor cursor = mActivity.getContentResolver().query(file, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) request.fileSize = cursor.getLong(0);
        } catch (RuntimeException e) {
            // The size is not known, the progress has no percentage
        }
        start(request);
    }

    @NonNull
    private String displayNameOf(@Nullable Uri file) {
        if (file == null) return "";
        String name = file.getLastPathSegment() != null ? file.getLastPathSegment() : "";
        try (android.database.Cursor cursor = mActivity.getContentResolver().query(file, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        } catch (RuntimeException e) {
            // The name of the uri
        }
        return name.substring(name.lastIndexOf('/') + 1);
    }

    private void afterInspection(@NonNull TerminowPhoneBackup.Job job) {
        TerminowBackupArchive.Inspection inspection = job.inspection;
        Uri file = job.request.file;
        if (inspection == null || file == null || mActivity.isFinishing()) return;
        if (inspection.isFolders()) showFolderRestore(job, inspection);
        else if (inspection.isFull()) confirmFullRestore(job, inspection);
    }

    /**
     * Restoring a full backup replaces all files of the environment, so which one, which of its
     * tabs close and that it is replaced have to be confirmed. The one that is there stays until
     * the restore worked.
     */
    private void confirmFullRestore(@NonNull TerminowPhoneBackup.Job inspected, @NonNull TerminowBackupArchive.Inspection inspection) {
        String alias = inspection.container;
        if (alias == null) return;
        String device = TerminowLinuxUi.deviceArch();
        if (inspection.manifest != null && !inspection.manifest.architecture.isEmpty() && !inspection.manifest.architecture.equals(device)) {
            message(R.string.terminow_phone_restore_menu, mActivity.getString(R.string.terminow_backup_restore_wrong_arch,
                inspection.manifest.architecture, device));
            return;
        }
        if (!mLinux.isRuntimeReady()) {
            mLinux.needRuntime(alias, false);
            return;
        }
        if (TerminowLinuxUi.isBusy()) {
            mActivity.showToast(mActivity.getString(R.string.terminow_linux_install_busy), true);
            return;
        }
        TerminowLinuxEnv env = mLinux.envOf(alias);
        String title = env != null ? env.getTitle() : alias;
        List<TerminalSession> sessions = env != null ? mLauncher.sessionsOf(alias) : new ArrayList<>();
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog);
        Context context = builder.getContext();
        LinearLayout box = vertical(context);
        box.setPadding(dp(24), dp(6), dp(24), 0);
        StringBuilder text = new StringBuilder(mActivity.getString(inspection.plainProotDistro ? R.string.terminow_backup_restore_plain :
            R.string.terminow_backup_restore_full_kind));
        text.append("\n\n").append(env != null ? mActivity.getString(R.string.terminow_phone_restore_replace, title, alias, inspected.request.title) :
            mActivity.getString(R.string.terminow_phone_restore_new, alias, inspected.request.title));
        text.append("\n\n").append(mActivity.getString(R.string.terminow_backup_restore_safe));
        if (!sessions.isEmpty()) {
            text.append("\n\n").append(mActivity.getString(R.string.terminow_linux_uninstall_sessions, sessions.size()));
            for (TerminalSession session : sessions) text.append("\n• ").append(session.mSessionName != null ? session.mSessionName : "?");
        }
        box.addView(label(context, text.toString(), false));
        CheckBox understood = new CheckBox(context);
        understood.setText(mActivity.getString(R.string.terminow_phone_restore_confirm_check, alias));
        if (env != null) box.addView(understood);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        AlertDialog dialog = builder
            .setTitle(mActivity.getString(R.string.terminow_phone_restore_title, title))
            .setView(scroll)
            .setPositiveButton(sessions.isEmpty() ? R.string.terminow_phone_restore_run : R.string.terminow_phone_restore_close_tabs, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
        dialog.setOnShowListener(d -> {
            View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setEnabled(env == null);
            understood.setOnCheckedChangeListener((b, checked) -> positive.setEnabled(checked));
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                // The environment is restored only when nothing runs in it: its tabs end first
                for (TerminalSession session : sessions) session.finishIfRunning();
                waitForTabsThen(sessions, 0, () -> {
                    TerminowPhoneBackup.Request request = new TerminowPhoneBackup.Request();
                    request.kind = TerminowPhoneBackup.Kind.RESTORE_FULL;
                    request.alias = alias;
                    request.title = title;
                    request.file = inspected.request.file;
                    request.fileLabel = inspected.request.fileLabel;
                    request.fileSize = inspected.request.fileSize;
                    request.contentBytes = inspection.contentBytes;
                    start(request);
                });
            });
        });
        dialog.show();
    }

    private void waitForTabsThen(@NonNull List<TerminalSession> sessions, int waited, @NonNull Runnable then) {
        boolean running = false;
        for (TerminalSession session : sessions) if (session.isRunning()) running = true;
        if (!running || waited >= 40) {
            if (running) {
                mActivity.showToast(mActivity.getString(R.string.terminow_backup_tabs_still_run), true);
                return;
            }
            // proot-distro needs a moment after the last tab to see the environment free
            mHandler.postDelayed(then, 500);
            return;
        }
        mHandler.postDelayed(() -> waitForTabsThen(sessions, waited + 1, then), 250);
    }

    /** A folders backup goes into an environment: where the folders were, or into a folder; existing files kept or replaced. */
    private void showFolderRestore(@NonNull TerminowPhoneBackup.Job inspected, @NonNull TerminowBackupArchive.Inspection inspection) {
        List<TerminowLinuxEnv> envs = mLinux.installedEnvs();
        if (envs.isEmpty()) {
            message(R.string.terminow_phone_restore_menu, mActivity.getString(R.string.terminow_backup_restore_no_env));
            return;
        }
        if (!mLinux.isRuntimeReady()) {
            mLinux.needRuntime(envs.get(0).alias, false);
            return;
        }
        TerminowLinuxEnv[] target = {envs.get(0)};
        if (inspection.manifest != null)
            for (TerminowLinuxEnv env : envs) if (env.alias.equals(inspection.manifest.alias)) target[0] = env;
        String[] destination = {"/"};

        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog);
        Context context = builder.getContext();
        LinearLayout box = vertical(context);
        box.setPadding(dp(20), dp(4), dp(20), 0);
        StringBuilder about = new StringBuilder(mActivity.getString(R.string.terminow_backup_restore_folders_kind));
        if (inspection.manifest != null) for (String source : inspection.manifest.sources) about.append("\n  • ").append(source);
        box.addView(label(context, about.toString(), false));

        box.addView(heading(context, R.string.terminow_backup_restore_into));
        TextView envView = button(context, 0);
        envView.setText(target[0].getTitle() + " (" + target[0].alias + ")");
        envView.setOnClickListener(v -> {
            CharSequence[] names = new CharSequence[envs.size()];
            for (int i = 0; i < envs.size(); i++) names[i] = envs.get(i).getTitle() + " (" + envs.get(i).alias + ")";
            new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setItems(names, (d, which) -> {
                    target[0] = envs.get(which);
                    envView.setText(names[which]);
                })
                .show();
        });
        box.addView(envView);

        box.addView(heading(context, R.string.terminow_backup_restore_where));
        RadioGroup places = new RadioGroup(context);
        RadioButton original = new RadioButton(context);
        original.setId(View.generateViewId());
        original.setText(R.string.terminow_backup_restore_original);
        RadioButton inside = new RadioButton(context);
        inside.setId(View.generateViewId());
        inside.setText(mActivity.getString(R.string.terminow_backup_restore_inside, "…"));
        places.addView(original);
        places.addView(inside);
        places.check(original.getId());
        inside.setOnClickListener(v -> showFolderPicker(target[0], false, "/root", Collections.emptyList(), picked -> {
            if (picked.isEmpty()) return;
            destination[0] = picked.get(0);
            inside.setText(mActivity.getString(R.string.terminow_backup_restore_inside, destination[0]));
        }));
        original.setOnClickListener(v -> destination[0] = "/");
        box.addView(places);

        box.addView(heading(context, R.string.terminow_backup_restore_existing));
        RadioGroup existing = new RadioGroup(context);
        RadioButton skip = new RadioButton(context);
        skip.setId(View.generateViewId());
        skip.setText(R.string.terminow_backup_restore_skip);
        RadioButton overwrite = new RadioButton(context);
        overwrite.setId(View.generateViewId());
        overwrite.setText(R.string.terminow_backup_restore_overwrite);
        existing.addView(skip);
        existing.addView(overwrite);
        existing.check(skip.getId());
        box.addView(existing);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(box);
        builder.setTitle(R.string.terminow_backup_restore_folders_title)
            .setView(scroll)
            .setPositiveButton(R.string.terminow_phone_restore_run, (d, w) -> {
                String into = places.getCheckedRadioButtonId() == inside.getId() ? destination[0] : "/";
                boolean replace = existing.getCheckedRadioButtonId() == overwrite.getId();
                List<TerminalSession> sessions = mLauncher.sessionsOf(target[0].alias);
                Runnable run = () -> {
                    TerminowPhoneBackup.Request request = new TerminowPhoneBackup.Request();
                    request.kind = TerminowPhoneBackup.Kind.RESTORE_FOLDERS;
                    request.alias = target[0].alias;
                    request.title = target[0].getTitle();
                    request.file = inspected.request.file;
                    request.fileLabel = inspected.request.fileLabel;
                    request.fileSize = inspected.request.fileSize;
                    request.destination = into;
                    request.overwrite = replace;
                    start(request);
                };
                // Replacing files that programs in it use could break them: said, and asked
                if (replace && !sessions.isEmpty()) {
                    new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                        .setMessage(mActivity.getResources().getQuantityString(R.plurals.terminow_backup_restore_in_use, sessions.size(),
                            sessions.size(), target[0].getTitle()))
                        .setPositiveButton(R.string.terminow_phone_restore_run, (c, x) -> run.run())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                } else {
                    run.run();
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    // ---------------------------------------------------------------------------------------
    // Folders of an environment

    /**
     * A browser of the folders of the environment itself: breadcrumbs, up, hidden folders on or
     * off, and check boxes for several folders, or one folder to use. Links, bound folders and
     * what is not a file of the environment, like /proc, are not offered.
     */
    void showFolderPicker(@NonNull TerminowLinuxEnv env, boolean multiple, @NonNull String start, @NonNull List<String> already,
                          @NonNull Consumer<List<String>> onPicked) {
        TerminowLinuxFs fs = mLinux.filesOf(env).forScan();
        AlertDialog.Builder builder = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog);
        Context context = builder.getContext();
        LinearLayout box = vertical(context);
        box.setPadding(dp(16), dp(4), dp(16), 0);
        HorizontalScrollView crumbsScroll = new HorizontalScrollView(context);
        LinearLayout crumbs = new LinearLayout(context);
        crumbsScroll.addView(crumbs);
        box.addView(crumbsScroll);
        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView up = button(context, R.string.terminow_backup_picker_up);
        controls.addView(up);
        CheckBox hidden = new CheckBox(context);
        hidden.setText(R.string.terminow_backup_picker_hidden);
        controls.addView(hidden);
        box.addView(controls);
        ProgressBar loading = new ProgressBar(context);
        box.addView(loading);
        TextView empty = label(context, mActivity.getString(R.string.terminow_backup_picker_empty), true);
        box.addView(empty);
        ListView list = new ListView(context);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        TextView selectedView = label(context, "", true);
        box.addView(selectedView);

        Set<String> selected = new LinkedHashSet<>();
        // A start that is not there lists as empty, and up leads out of it
        String[] current = {start};
        List<TerminowFs.Node> children = new ArrayList<>();
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return children.size(); }
            @Override public Object getItem(int position) { return children.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TerminowFs.Node node = children.get(position);
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(2), 0, dp(2));
                boolean allowed = TerminowBackupArchive.normalize(node.id) != null && !TerminowBackupArchive.isNotBackedUp(node.id);
                if (multiple) {
                    CheckBox check = new CheckBox(context);
                    check.setChecked(selected.contains(node.id) || already.contains(node.id));
                    check.setEnabled(allowed && !already.contains(node.id));
                    check.setContentDescription(mActivity.getString(R.string.terminow_backup_picker_select, node.id));
                    check.setOnCheckedChangeListener((b, checked) -> {
                        if (checked) selected.add(node.id);
                        else selected.remove(node.id);
                        selectedView.setText(mActivity.getString(R.string.terminow_backup_picker_selected, selected.size()));
                    });
                    row.addView(check);
                }
                TextView name = label(context, node.name + "/", false);
                name.setAlpha(allowed ? 1f : 0.5f);
                name.setPadding(dp(8), dp(10), dp(8), dp(10));
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                return row;
            }
        };
        list.setAdapter(adapter);
        Runnable[] load = new Runnable[1];
        load[0] = () -> {
            String path = current[0];
            loading.setVisibility(View.VISIBLE);
            empty.setVisibility(View.GONE);
            crumbs.removeAllViews();
            String crumbPath = "";
            List<String> parts = new ArrayList<>();
            parts.add("/");
            for (String part : path.split("/")) if (!part.isEmpty()) parts.add(part);
            for (String part : parts) {
                crumbPath = part.equals("/") ? "/" : (crumbPath.equals("/") ? "/" : crumbPath + "/") + part;
                String target = crumbPath;
                TextView crumb = button(context, 0);
                crumb.setText(part.equals("/") ? env.getShortName() : part);
                crumb.setOnClickListener(v -> {
                    current[0] = target;
                    load[0].run();
                });
                crumbs.addView(crumb);
            }
            up.setEnabled(!path.equals("/"));
            boolean showHidden = hidden.isChecked();
            mExecutor.execute(() -> {
                List<TerminowFs.Node> found = new ArrayList<>();
                try {
                    for (TerminowFs.Node node : fs.list(new TerminowFs.Node(path, path, true, false, -1, 0, null), new TerminowFs.Cancel()))
                        if (node.directory && !node.link && (showHidden || !node.isHidden())) found.add(node);
                } catch (Exception e) {
                    // An unreadable folder is shown as empty
                }
                Collections.sort(found, (a, b) -> a.name.compareToIgnoreCase(b.name));
                mHandler.post(() -> {
                    if (!path.equals(current[0])) return;
                    children.clear();
                    children.addAll(found);
                    adapter.notifyDataSetChanged();
                    loading.setVisibility(View.GONE);
                    empty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                    crumbsScroll.post(() -> crumbsScroll.fullScroll(View.FOCUS_RIGHT));
                });
            });
        };
        list.setOnItemClickListener((parent, view, position, id) -> {
            current[0] = children.get(position).id;
            load[0].run();
        });
        up.setOnClickListener(v -> {
            String path = current[0];
            int slash = path.lastIndexOf('/');
            current[0] = slash <= 0 ? "/" : path.substring(0, slash);
            load[0].run();
        });
        hidden.setOnCheckedChangeListener((b, checked) -> load[0].run());
        selectedView.setText(multiple ? mActivity.getString(R.string.terminow_backup_picker_selected, 0) :
            mActivity.getString(R.string.terminow_backup_picker_single));
        builder.setTitle(multiple ? R.string.terminow_backup_picker_title : R.string.terminow_backup_picker_title_one)
            .setView(box)
            .setPositiveButton(multiple ? R.string.terminow_backup_picker_add : R.string.terminow_backup_picker_use, (d, w) -> {
                if (multiple) {
                    onPicked.accept(new ArrayList<>(selected));
                } else {
                    String path = current[0];
                    if (TerminowBackupArchive.normalize(path) == null || TerminowBackupArchive.isNotBackedUp(path)) {
                        mActivity.showToast(mActivity.getString(R.string.terminow_backup_folder_not_allowed), true);
                        return;
                    }
                    onPicked.accept(Collections.singletonList(path));
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
        load[0].run();
    }

    // ---------------------------------------------------------------------------------------
    // Views

    /** The picked folder as people know it, like "Internal storage/termux". */
    @NonNull
    private String treeLabel(@NonNull Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            if ("com.android.externalstorage.documents".equals(tree.getAuthority()) && id.startsWith("primary:"))
                return mActivity.getString(R.string.terminow_backup_internal_storage) + "/" + id.substring("primary:".length());
            return id;
        } catch (RuntimeException e) {
            return tree.toString();
        }
    }

    private void message(int titleRes, @NonNull String message) {
        new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    @NonNull
    private View spinner() {
        ProgressBar bar = new ProgressBar(mActivity);
        bar.setPadding(0, dp(16), 0, dp(16));
        return bar;
    }

    @NonNull
    private static LinearLayout vertical(@NonNull Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    @NonNull
    private TextView heading(@NonNull Context context, int textRes) {
        TextView view = new TextView(context);
        view.setText(textRes);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setPadding(0, dp(14), 0, dp(4));
        return view;
    }

    @NonNull
    private TextView label(@NonNull Context context, @NonNull String text, boolean secondary) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, secondary ? 13 : 14);
        if (secondary) view.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_text_secondary));
        view.setPadding(0, dp(2), 0, dp(2));
        return view;
    }

    @NonNull
    private TextView button(@NonNull Context context, int textRes) {
        TextView view = new TextView(context);
        if (textRes != 0) view.setText(textRes);
        view.setTextColor(ContextCompat.getColor(mActivity, R.color.terminow_accent));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(4), dp(10), dp(12), dp(10));
        view.setMinHeight(dp(44));
        view.setGravity(Gravity.CENTER_VERTICAL);
        android.util.TypedValue ripple = new android.util.TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        return view;
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, mActivity.getResources().getDisplayMetrics());
    }
}
