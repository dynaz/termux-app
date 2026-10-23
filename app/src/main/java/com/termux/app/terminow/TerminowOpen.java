package com.termux.app.terminow;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowSftpFs;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens a file path selected in a terminal, in the environment of that tab: a local file of
 * TermiNow or of a proot Linux distribution (through its rootfs and binds) is shown with another
 * app, and a file on a managed SSH host is downloaded first. An .apk opens the system installer.
 * Nothing is ever typed into the terminal, and a path that can not be resolved is explained, never
 * opened in the wrong place. Private files are copied into an export cache and shared as a
 * content URI with a temporary read grant; old copies are cleaned up.
 */
public final class TerminowOpen {

    private static final String LOG_TAG = "TerminowOpen";
    static final String APK_MIME = "application/vnd.android.package-archive";
    private static final long CACHE_TTL_MS = 60 * 60 * 1000L;

    private TerminowOpen() {}

    /** Resolve the selected path in the tab's environment and open it, with a chooser if asked. */
    public static void open(@NonNull TermuxActivity activity, @Nullable TerminalSession session,
                            @NonNull String path, boolean chooser) {
        TerminowHome home = activity.getTerminowHome();
        if (home == null || session == null) {
            toast(activity, activity.getString(R.string.terminow_open_unresolved,
                activity.getString(R.string.terminow_open_no_session)));
            return;
        }
        TerminowPathResolver.Target target = targetOf(home, session);
        TerminowPathResolver.Resolved resolved = TerminowPathResolver.resolve(target, path);
        switch (resolved.kind) {
            case UNRESOLVED:
                toast(activity, activity.getString(R.string.terminow_open_unresolved, resolved.reason));
                return;
            case LOCAL_FILE:
                openLocal(activity, new File(resolved.localPath), chooser);
                return;
            case REMOTE_SSH:
                openRemote(activity, home, session, resolved.remotePath, chooser);
                return;
        }
    }

    // ---------------------------------------------------------------------------------------
    // The target of the tab
    // ---------------------------------------------------------------------------------------

    @NonNull
    private static TerminowPathResolver.Target targetOf(@NonNull TerminowHome home, @NonNull TerminalSession session) {
        TerminowPathResolver.Target target = new TerminowPathResolver.Target();
        TerminowLauncher launcher = home.getLauncher();
        TerminowLauncher.SessionInfo info = launcher.getSessionInfo(session);
        if (info == null) return target; // UNKNOWN
        target.cwd = info.folder == null ? "" : info.folder;

        if (info.environmentKey != null) {
            if (TerminowMcpTargets.TARGET_TERMINOW.equals(info.environmentKey)) {
                target.env = TerminowPathResolver.Env.TERMUX;
                target.home = TermuxConstants.TERMUX_HOME_DIR_PATH;
                return target;
            }
            // A proot Linux distribution: map guest paths through its rootfs and binds
            TerminowLauncher.Environment environment = launcher.environmentFor(info.environmentKey);
            if (environment != null && environment.container != null) {
                target.env = TerminowPathResolver.Env.PROOT;
                target.rootfs = environment.container.rootfs.getAbsolutePath();
                String user = info.user == null || info.user.isEmpty() ? "root" : info.user;
                target.home = TerminowMcpTargets.getGuestHome(environment.container, user);
                // proot shares shared storage at the same path, plus the user's shared folders
                target.binds.add(new TerminowPathResolver.Bind("/sdcard", "/sdcard"));
                target.binds.add(new TerminowPathResolver.Bind("/storage", "/storage"));
                com.termux.app.terminow.linux.TerminowLinuxEnv linux = launcher.linuxEnvOf(environment);
                if (linux != null)
                    for (com.termux.app.terminow.linux.TerminowLinuxEnv.SharedFolder folder : linux.sharedFolders)
                        target.binds.add(new TerminowPathResolver.Bind(folder.hostPath, folder.guestPath));
                return target;
            }
            return target; // UNKNOWN
        }
        // No environment key: a connection to a host. Only a managed SSH/mosh host can transfer files.
        if ("ssh".equals(info.tool) || "mosh".equals(info.tool)) {
            target.env = TerminowPathResolver.Env.SSH;
            return target;
        }
        return target; // UNKNOWN (telnet, sftp without a shell, or unknown)
    }

    // ---------------------------------------------------------------------------------------
    // Local files
    // ---------------------------------------------------------------------------------------

    private static void openLocal(@NonNull TermuxActivity activity, @NonNull File file, boolean chooser) {
        if (!file.exists()) {
            toast(activity, activity.getString(R.string.terminow_open_not_found, file.getName()));
            return;
        }
        if (file.isDirectory()) {
            toast(activity, activity.getString(R.string.terminow_open_is_folder));
            return;
        }
        cleanCache(activity);
        new Thread(() -> {
            File staged;
            try {
                staged = stage(activity, file.getName(), new java.io.FileInputStream(file));
            } catch (IOException | RuntimeException e) {
                post(activity, () -> toast(activity, activity.getString(R.string.terminow_open_failed, String.valueOf(e.getMessage()))));
                return;
            }
            post(activity, () -> launch(activity, staged, chooser));
        }, "TerminowOpen stage").start();
    }

    // ---------------------------------------------------------------------------------------
    // Remote files over the host's SFTP
    // ---------------------------------------------------------------------------------------

    private static void openRemote(@NonNull TermuxActivity activity, @NonNull TerminowHome home,
                                   @NonNull TerminalSession session, @NonNull String remotePath, boolean chooser) {
        TerminowStore store = home.getStore();
        TerminowLauncher.SessionInfo info = home.getLauncher().getSessionInfo(session);
        TerminowStore.Host host = info != null ? hostByTitle(store, info.environmentLabel) : null;
        if (host == null || host.telnet || !host.isTerminal()) {
            toast(activity, activity.getString(R.string.terminow_open_no_transfer));
            return;
        }
        cleanCache(activity);
        String name = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        if (name.isEmpty()) name = "download";

        Progress progress = new Progress(activity, name);
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        progress.onCancel(cancel::cancel);
        progress.show();

        String finalName = name;
        new Thread(() -> {
            TerminowSftpFs fs = new TerminowSftpFs(new File(TermuxConstants.TERMUX_HOME_DIR_PATH), "open:" + host.getKey(),
                host.getTitle(), host.address, host.port, host.username, host.password, host.keyPath,
                new OpenPrompts(activity, store));
            File staged = null;
            String error = null;
            try {
                String parent = remotePath.contains("/") ? remotePath.substring(0, remotePath.lastIndexOf('/')) : "/";
                if (parent.isEmpty()) parent = "/";
                TerminowFs.Node dir = fs.getDirectory(parent);
                TerminowFs.Node node = fs.find(dir, finalName);
                if (node == null) throw new IOException("Not found on the server");
                if (node.directory) throw new IOException("It is a folder on the server");
                long total = node.size;
                File dest = newCacheFile(activity, finalName);
                try (InputStream in = fs.openInput(node); OutputStream out = new FileOutputStream(dest)) {
                    byte[] buffer = new byte[64 * 1024];
                    long done = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        cancel.check();
                        out.write(buffer, 0, read);
                        done += read;
                        long shown = done;
                        progress.update(shown, total);
                    }
                }
                staged = dest;
            } catch (TerminowFs.CancelledException e) {
                error = null; // cancelled: no error toast
            } catch (IOException | RuntimeException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Remote open failed", e);
                error = e.getMessage();
            } finally {
                new Thread(fs::close, "TerminowOpen close").start();
            }
            File result = staged;
            String failure = error;
            post(activity, () -> {
                progress.dismiss();
                if (result != null) launch(activity, result, chooser);
                else if (failure != null) toast(activity, activity.getString(R.string.terminow_open_failed, failure));
            });
        }, "TerminowOpen download").start();
    }

    @Nullable
    private static TerminowStore.Host hostByTitle(@NonNull TerminowStore store, @NonNull String title) {
        for (TerminowStore.Host host : store.getHosts())
            if (host.getTitle().equals(title)) return TerminowStore.resolve(host, store.getIdentities());
        return null;
    }

    // ---------------------------------------------------------------------------------------
    // Launching an app for a staged file
    // ---------------------------------------------------------------------------------------

    private static void launch(@NonNull TermuxActivity activity, @NonNull File staged, boolean chooser) {
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".explorer", staged);
        } catch (IllegalArgumentException e) {
            toast(activity, activity.getString(R.string.terminow_open_failed, String.valueOf(e.getMessage())));
            return;
        }
        String mime = mimeType(staged.getName());
        if (APK_MIME.equals(mime)) {
            installApk(activity, uri);
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(chooser ? Intent.createChooser(view, staged.getName()) : view);
        } catch (ActivityNotFoundException e) {
            toast(activity, activity.getString(R.string.terminow_open_no_app));
        }
    }

    private static void installApk(@NonNull TermuxActivity activity, @NonNull Uri apk) {
        // On Android 8+ the app needs the user's permission to install from this source
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_open_apk_source_title)
                .setMessage(R.string.terminow_open_apk_source_message)
                .setPositiveButton(R.string.terminow_open_apk_source_settings, (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())));
                    } catch (ActivityNotFoundException e) {
                        toast(activity, activity.getString(R.string.terminow_open_no_app));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apk, APK_MIME);
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(install);
        } catch (ActivityNotFoundException e) {
            toast(activity, activity.getString(R.string.terminow_open_no_app));
        }
    }

    // ---------------------------------------------------------------------------------------
    // The export cache
    // ---------------------------------------------------------------------------------------

    @NonNull
    private static File stage(@NonNull Context context, @NonNull String name, @NonNull InputStream in) throws IOException {
        File dest = newCacheFile(context, name);
        try (InputStream input = in; OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) out.write(buffer, 0, read);
        }
        return dest;
    }

    @NonNull
    private static File newCacheFile(@NonNull Context context, @NonNull String name) throws IOException {
        File dir = new File(context.getCacheDir(), "explorer/open-" + System.currentTimeMillis());
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Could not prepare the export cache");
        return new File(dir, safeName(name));
    }

    /** Keep the name, with its Unicode and spaces, but drop any path and empty names. */
    @NonNull
    static String safeName(@NonNull String name) {
        String base = name.substring(name.lastIndexOf('/') + 1).replace('\u0000', '_').trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "file";
        return base.length() > 180 ? base.substring(base.length() - 180) : base;
    }

    /** Remove staged files older than an hour, without touching one being read now. */
    private static void cleanCache(@NonNull Context context) {
        File root = new File(context.getCacheDir(), "explorer");
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        long now = System.currentTimeMillis();
        for (File dir : dirs) {
            if (!dir.getName().startsWith("open-")) continue;
            if (now - dir.lastModified() < CACHE_TTL_MS) continue;
            deleteTree(dir);
        }
    }

    private static void deleteTree(@NonNull File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    @NonNull
    static String mimeType(@NonNull String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (ext.equals("apk")) return APK_MIME;
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return type != null ? type : "application/octet-stream";
    }

    private static void toast(@NonNull TermuxActivity activity, @NonNull String message) {
        activity.showToast(message, true);
    }

    private static void post(@NonNull TermuxActivity activity, @NonNull Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    /** A small progress dialog for a download, with Cancel. */
    private static final class Progress {
        private final TermuxActivity mActivity;
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final String mName;
        @Nullable private AlertDialog mDialog;
        @Nullable private ProgressBar mBar;
        @Nullable private TextView mText;
        @Nullable private Runnable mOnCancel;

        Progress(@NonNull TermuxActivity activity, @NonNull String name) {
            mActivity = activity;
            mName = name;
        }

        void onCancel(@NonNull Runnable onCancel) {
            mOnCancel = onCancel;
        }

        void show() {
            LinearLayout box = new LinearLayout(mActivity);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(20 * mActivity.getResources().getDisplayMetrics().density);
            box.setPadding(pad, pad, pad, 0);
            mText = new TextView(mActivity);
            mText.setText(mActivity.getString(R.string.terminow_open_downloading, mName));
            box.addView(mText);
            mBar = new ProgressBar(mActivity, null, android.R.attr.progressBarStyleHorizontal);
            mBar.setIndeterminate(true);
            mBar.setMax(100);
            box.addView(mBar);
            mDialog = new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    if (mOnCancel != null) mOnCancel.run();
                })
                .show();
        }

        void update(long done, long total) {
            mHandler.post(() -> {
                if (mBar == null) return;
                if (total > 0) {
                    mBar.setIndeterminate(false);
                    mBar.setProgress((int) Math.min(100, done * 100 / total));
                }
            });
        }

        void dismiss() {
            if (mDialog != null) mDialog.dismiss();
        }
    }

    /** Questions for an SFTP download: the key passphrase from the Keychain, then dialogs. */
    private static final class OpenPrompts implements TerminowSftpFs.Prompts {
        private final TermuxActivity mActivity;
        private final TerminowStore mStore;
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        OpenPrompts(@NonNull TermuxActivity activity, @NonNull TerminowStore store) {
            mActivity = activity;
            mStore = store;
        }

        @Override
        public boolean confirmHostKey(@NonNull String host, @NonNull String keyType, @NonNull String fingerprint) {
            AtomicReference<Boolean> answer = new AtomicReference<>(false);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            mHandler.post(() -> new AlertDialog.Builder(mActivity, R.style.TerminowAlertDialog)
                .setTitle(R.string.terminow_explorer_host_key_title)
                .setMessage(mActivity.getString(R.string.terminow_explorer_host_key_message, host, keyType, fingerprint))
                .setCancelable(false)
                .setPositiveButton(R.string.terminow_explorer_host_key_accept, (d, w) -> { answer.set(true); latch.countDown(); })
                .setNegativeButton(android.R.string.cancel, (d, w) -> latch.countDown())
                .show());
            await(latch);
            return answer.get();
        }

        @Nullable
        @Override
        public String askPassword(@NonNull String prompt) {
            return null; // a saved password is used; otherwise the download fails with a clear error
        }

        @Nullable
        @Override
        public String askPassphrase(@NonNull String keyName) {
            return null;
        }

        @Nullable
        @Override
        public String savedPassphrase(@NonNull String keyName) {
            String value = mStore.getKeyPassphrase(keyName);
            return value.isEmpty() ? null : value;
        }

        private static void await(@NonNull java.util.concurrent.CountDownLatch latch) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
