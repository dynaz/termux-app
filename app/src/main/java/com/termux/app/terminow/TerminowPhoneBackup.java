package com.termux.app.terminow;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.terminow.linux.TerminowLinuxInstaller;
import com.termux.shared.notification.NotificationUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Backs up a Linux environment to the phone, and restores it, one job at a time, on a thread of
 * its own; a job keeps running when its dialog is hidden, the screen turns or the app goes to
 * the background, while TermuxService keeps the app in the foreground, and its notification
 * shows the progress.
 *
 * <ul>
 * <li>Full: the archive of {@code proot-distro backup} on stdout, with the manifest in front.</li>
 * <li>Folders: GNU tar in the environment, as root, with each argument of its own.</li>
 * </ul>
 * Both are gzipped into a new file in the folder that the user picked with the Storage Access
 * Framework: a content URI, never taken for a path. The file is read through again before the
 * backup is called good; a backup that failed or was stopped is deleted.
 *
 * <p>A restore reads the whole file first ({@link Kind#INSPECT}). A full restore renames the
 * environment that is there aside, restores, and only then removes the old one; if the restore
 * fails, the old one gets its name back. A folders restore unpacks with GNU tar into the
 * environment. Nothing of the files, and no secret, is logged.
 */
final class TerminowPhoneBackup {

    enum Kind { BACKUP_FULL, BACKUP_FOLDERS, INSPECT, RESTORE_FULL, RESTORE_FOLDERS }

    enum Stage { CHECKING, ARCHIVING, VERIFYING, PREPARING, RESTORING, FINISHING }

    enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /** What a job is to do. */
    static final class Request {
        Kind kind = Kind.BACKUP_FULL;
        String alias = "";
        String title = "";
        String distro = "";
        String architecture = "";
        String appVersion = "";
        /** For a folders backup, from {@link TerminowBackupArchive#normalizeSources}. */
        List<String> sources = new ArrayList<>();
        /** Where Android's folders are bound into the environment, never backed up. */
        List<String> binds = new ArrayList<>();
        /** For a backup: the folder that was picked, and the name of the new file. */
        @Nullable Uri tree;
        String fileName = "";
        /** For a backup: the bytes that will be archived, 0 if not known. */
        long estimate;
        /** For a restore: the file and its size, 0 if not known. */
        @Nullable Uri file;
        /** The name of the file, as the picker said it. */
        String fileLabel = "";
        long fileSize;
        /** For a folders restore: where, "/" for where the folders were, and whether existing files are replaced. */
        String destination = "/";
        boolean overwrite;
        /** For a full restore: the bytes that the environment will take, from its inspection. */
        long contentBytes;
    }

    static final class Job {
        final Request request;
        final long startedAt = System.currentTimeMillis();
        volatile long endedAt;
        volatile State state = State.RUNNING;
        volatile Stage stage = Stage.CHECKING;
        /** The bytes done of this stage, and of how many, 0 if that is not known. */
        volatile long bytes;
        volatile long total;
        /** Why it failed: the last lines that the program wrote, or the error. */
        volatile String error = "";
        /** The file that a backup wrote. */
        @Nullable volatile Uri written;
        /** What an inspection found. */
        @Nullable volatile TerminowBackupArchive.Inspection inspection;
        volatile int pid;
        @Nullable volatile Process process;
        long toldAt;

        Job(@NonNull Request request) {
            this.request = request;
        }

        boolean isBackup() {
            return request.kind == Kind.BACKUP_FULL || request.kind == Kind.BACKUP_FOLDERS;
        }

        long elapsed() {
            return (endedAt > 0 ? endedAt : System.currentTimeMillis()) - startedAt;
        }
    }

    private static final Pattern PID = Pattern.compile("TERMINOW_PID=(\\d+)");
    private static final int ERROR_LINES = 6;
    private static final String CHANNEL_ID = "terminow_backup";
    private static final int NOTIFICATION_ID = 7341;
    @Nullable private static Handler sMain;
    private static final List<Runnable> sListeners = new CopyOnWriteArrayList<>();
    @Nullable private static volatile Job sJob;

    private TerminowPhoneBackup() {}

    /** The job that runs or ran last, {@code null} if there was none. */
    @Nullable
    static Job current() {
        return sJob;
    }

    static boolean isRunning() {
        Job job = sJob;
        return job != null && job.state == State.RUNNING;
    }

    static void addListener(@NonNull Runnable listener) {
        sListeners.add(listener);
    }

    static void removeListener(@NonNull Runnable listener) {
        sListeners.remove(listener);
    }

    @NonNull
    private static synchronized Handler main() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        return sMain;
    }

    private static void changed() {
        main().post(() -> {
            for (Runnable listener : sListeners) listener.run();
        });
    }

    /**
     * Backing up to the phone is part of Premium: it is checked here again, where the job
     * starts, whatever showed the button.
     */
    static boolean mayBackUp(@NonNull TerminowStore store) {
        return TerminowEntitlement.get(store) == TerminowEntitlement.State.ACTIVE;
    }

    /**
     * Start a job, {@code null} if one runs already, or if it is a backup and the user has no Premium.
     *
     * @param prefix The prefix of TermiNow, where its proot-distro is.
     */
    @Nullable
    static Job start(@NonNull Context context, @NonNull TerminowStore store, @NonNull TerminowLinuxInstaller.Runner runner,
                     @NonNull String prefix, @NonNull Request request) {
        if (isRunning()) return null;
        if ((request.kind == Kind.BACKUP_FULL || request.kind == Kind.BACKUP_FOLDERS) && !mayBackUp(store)) return null;
        Job job = new Job(request);
        sJob = job;
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> run(app, runner, prefix, job), "terminow-phone-backup");
        thread.setDaemon(true);
        thread.start();
        changed();
        return job;
    }

    /**
     * Stop the job: what it started gets Ctrl-C, which proot-distro and tar answer by cleaning
     * up, and the unfinished backup file is deleted. A restore that replaces files is not stopped
     * halfway, so it is only stopped while it inspects or prepares.
     */
    static boolean cancel(@NonNull TerminowLinuxInstaller.Runner runner) {
        Job job = sJob;
        if (job == null || job.state != State.RUNNING) return false;
        if (job.stage == Stage.RESTORING || job.stage == Stage.FINISHING) return false;
        job.state = State.CANCELLED;
        if (job.pid > 0) runner.interrupt(job.pid);
        Process process = job.process;
        if (process != null) main().postDelayed(process::destroy, 5000);
        changed();
        return true;
    }

    static boolean canCancel(@NonNull Job job) {
        return job.state == State.RUNNING && job.stage != Stage.RESTORING && job.stage != Stage.FINISHING;
    }

    private static void run(@NonNull Context context, @NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix,
                            @NonNull Job job) {
        notifyProgress(context, job);
        try {
            switch (job.request.kind) {
                case BACKUP_FULL:
                case BACKUP_FOLDERS: backUp(context, runner, prefix, job); break;
                case INSPECT: inspect(context, job); break;
                case RESTORE_FULL: restoreFull(context, runner, prefix, job); break;
                case RESTORE_FOLDERS: restoreFolders(context, runner, prefix, job); break;
            }
        } catch (Exception e) {
            if (job.state == State.RUNNING) {
                job.error = e.getMessage() != null ? e.getMessage() : e.toString();
                job.state = State.FAILED;
            }
            Process process = job.process;
            if (process != null) process.destroy();
        }
        if (job.state == State.RUNNING) job.state = State.SUCCEEDED;
        // A backup is only kept when it was read through and found good
        if (job.isBackup() && job.state != State.SUCCEEDED && job.written != null) delete(context, job.written);
        job.endedAt = System.currentTimeMillis();
        notifyProgress(context, job);
        changed();
    }

    // ---------------------------------------------------------------------------------------
    // Backup

    private static void backUp(@NonNull Context context, @NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix,
                               @NonNull Job job) throws Exception {
        Request request = job.request;
        boolean full = request.kind == Kind.BACKUP_FULL;
        if (request.tree == null) throw new IOException("No folder to save the backup in was picked");
        if (!full && (request.sources.isEmpty() || TerminowBackupArchive.normalizeSources(request.sources) == null))
            throw new IOException("The folders to back up are not allowed");
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(request.tree, DocumentsContract.getTreeDocumentId(request.tree));
        Uri file = DocumentsContract.createDocument(context.getContentResolver(), parent, "application/gzip", request.fileName);
        if (file == null) throw new IOException("The backup file could not be made in the folder that was picked");
        job.written = file;

        stage(context, job, Stage.ARCHIVING, request.estimate);
        List<String> command = new ArrayList<>(Arrays.asList(prefix + "/bin/sh", "-c", "echo TERMINOW_PID=$$ >&2; exec \"$@\"", "terminow",
            prefix + "/bin/proot-distro"));
        if (full) {
            command.addAll(Arrays.asList("backup", request.alias));
        } else {
            command.addAll(Arrays.asList("login", request.alias, "--user", "root", "--"));
            command.addAll(TerminowBackupArchive.folderTarArguments(request.sources, request.binds));
        }
        ArrayDeque<String> lines = new ArrayDeque<>();
        Process process = runner.start(command);
        job.process = process;
        process.getOutputStream().close();
        Thread errors = readErrorsInBackground(process, job, lines);
        try (OutputStream file0 = context.getContentResolver().openOutputStream(file, "w")) {
            if (file0 == null) throw new IOException("The backup file could not be opened");
            GZIPOutputStream out = new GZIPOutputStream(file0, 1 << 16);
            TerminowBackupArchive.Manifest manifest = new TerminowBackupArchive.Manifest();
            manifest.type = full ? TerminowBackupArchive.TYPE_FULL : TerminowBackupArchive.TYPE_FOLDERS;
            manifest.alias = request.alias;
            manifest.distro = request.distro;
            manifest.architecture = request.architecture;
            manifest.created = job.startedAt;
            manifest.sources = full ? new ArrayList<>(Arrays.asList("/")) : request.sources;
            manifest.app = request.appVersion;
            TerminowBackupArchive.writeFileEntry(out, (full ? request.alias + "/" : "") + TerminowBackupArchive.MANIFEST,
                manifest.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8), 0644, job.startedAt / 1000);
            BufferedInputStream archive = new BufferedInputStream(process.getInputStream(), 1 << 16);
            // proot-distro may gzip what it writes: it is unpacked, the whole file is gzipped once
            archive.mark(2);
            boolean gzipped = archive.read() == 0x1f && archive.read() == 0x8b;
            archive.reset();
            copy(gzipped ? new GZIPInputStream(archive, 1 << 16) : archive, out, job, context);
            out.finish();
            out.flush();
        }
        int exit = process.waitFor();
        errors.join(2000);
        if (job.state != State.RUNNING) return;
        if (exit != 0) {
            synchronized (lines) {
                job.error = (full ? "proot-distro backup" : "tar") + " ended with " + exit + (lines.isEmpty() ? "" : ":\n" + String.join("\n", lines));
            }
            job.state = State.FAILED;
            return;
        }

        // Read the file back, through gzip's checksum and every tar header, before it is called good
        stage(context, job, Stage.VERIFYING, 0);
        TerminowBackupArchive.Inspection inspection = inspectFile(context, file, job);
        if (job.state != State.RUNNING) return;
        String problem = inspection.problem;
        if (problem == null && (inspection.manifest == null || !manifest(inspection).equals(full ? TerminowBackupArchive.TYPE_FULL :
            TerminowBackupArchive.TYPE_FOLDERS))) problem = "The backup does not have its manifest";
        if (problem == null && full && !request.alias.equals(inspection.container)) problem = "The backup is not of " + request.alias;
        if (problem == null && inspection.entries < 2) problem = "The backup is empty";
        if (problem != null) {
            job.error = "The backup that was written is not good: " + problem;
            job.state = State.FAILED;
            return;
        }
        job.inspection = inspection;
    }

    @NonNull
    private static String manifest(@NonNull TerminowBackupArchive.Inspection inspection) {
        return inspection.manifest != null ? inspection.manifest.type : "";
    }

    // ---------------------------------------------------------------------------------------
    // Restore

    /** Read the file through and say what it is, before anything is restored from it. */
    private static void inspect(@NonNull Context context, @NonNull Job job) throws Exception {
        Uri file = job.request.file;
        if (file == null) throw new IOException("No file was picked");
        stage(context, job, Stage.CHECKING, job.request.fileSize);
        TerminowBackupArchive.Inspection inspection = inspectFile(context, file, job);
        job.inspection = inspection;
        if (job.state == State.RUNNING && inspection.problem != null) {
            job.error = inspection.problem;
            job.state = State.FAILED;
        }
    }

    @NonNull
    private static TerminowBackupArchive.Inspection inspectFile(@NonNull Context context, @NonNull Uri file, @NonNull Job job) throws IOException {
        try (InputStream raw = context.getContentResolver().openInputStream(file)) {
            if (raw == null) throw new IOException("The file could not be opened");
            // The progress is of the file as it is, whose size is known
            InputStream counted = new java.io.FilterInputStream(raw) {
                @Override
                public int read(@NonNull byte[] b, int off, int len) throws IOException {
                    if (job.state != State.RUNNING) throw new IOException("Stopped");
                    int n = super.read(b, off, len);
                    if (n > 0) told(context, job, n);
                    return n;
                }
            };
            BufferedInputStream in = new BufferedInputStream(counted, 1 << 16);
            in.mark(2);
            boolean gzipped = in.read() == 0x1f && in.read() == 0x8b;
            in.reset();
            InputStream tar = gzipped ? new GZIPInputStream(in, 1 << 16) : in;
            TerminowBackupArchive.Inspection inspection = TerminowBackupArchive.inspect(tar, null);
            // To the end of the file, which makes gzip check its checksum
            byte[] rest = new byte[1 << 16];
            while (tar.read(rest) > 0) {
                // What follows the end of the archive is padding
            }
            return inspection;
        }
    }

    /**
     * Restore a full backup: the environment that is there is renamed aside, the backup is
     * restored with proot-distro, and only when that worked the old one is removed. When it
     * fails, what was restored is removed and the old one gets its name back.
     */
    private static void restoreFull(@NonNull Context context, @NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix,
                                    @NonNull Job job) throws Exception {
        Request request = job.request;
        Uri file = request.file;
        if (file == null) throw new IOException("No file was picked");
        File containers = new File(prefix, "var/lib/proot-distro/containers");
        File existing = new File(containers, request.alias);
        // The old and the restored environment are both there for a while
        long available = new File(prefix).getUsableSpace();
        if (request.contentBytes > 0 && available < request.contentBytes + (100L << 20))
            throw new IOException("Not enough space: the restore needs about " + human(request.contentBytes) + ", " + human(available) + " is free");
        String aside = null;
        if (existing.exists()) {
            stage(context, job, Stage.PREPARING, 0);
            aside = asideName(request.alias, containers);
            runOrFail(runner, prefix, job, Arrays.asList("rename", request.alias, aside), "proot-distro rename");
            if (job.state != State.RUNNING) return;
        }
        stage(context, job, Stage.RESTORING, request.fileSize);
        ArrayDeque<String> lines = new ArrayDeque<>();
        Process process = runner.start(Arrays.asList(prefix + "/bin/sh", "-c", "echo TERMINOW_PID=$$ >&2; exec \"$@\"", "terminow",
            prefix + "/bin/proot-distro", "restore"));
        job.process = process;
        process.getInputStream().close();
        Thread errors = readErrorsInBackground(process, job, lines);
        IOException readError = feed(context, file, process, job, false);
        int exit = process.waitFor();
        errors.join(2000);
        boolean restored = readError == null && exit == 0 && new File(containers, request.alias + "/rootfs").isDirectory();
        if (!restored) {
            synchronized (lines) {
                job.error = readError != null ? "The file could not be read: " + readError.getMessage() :
                    "proot-distro restore ended with " + exit + (lines.isEmpty() ? "" : ":\n" + String.join("\n", lines));
            }
            job.state = State.FAILED;
            // What was restored halfway goes, and the environment that was there comes back
            if (aside != null) {
                if (existing.exists()) runQuietly(runner, prefix, Arrays.asList("remove", request.alias));
                if (runQuietly(runner, prefix, Arrays.asList("rename", aside, request.alias)) != 0)
                    job.error += "\n\nThe environment that was there is kept as " + aside + ".";
                else job.error += "\n\nThe environment that was there was kept as it was.";
            }
            return;
        }
        if (aside != null) {
            stage(context, job, Stage.FINISHING, 0);
            if (runQuietly(runner, prefix, Arrays.asList("remove", aside)) != 0)
                job.error = "Restored. The environment that was there before is still kept as " + aside + "; remove it to free space.";
        }
    }

    /** Restore a folders backup into the environment with GNU tar, as root. */
    private static void restoreFolders(@NonNull Context context, @NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix,
                                       @NonNull Job job) throws Exception {
        Request request = job.request;
        Uri file = request.file;
        if (file == null) throw new IOException("No file was picked");
        String destination = TerminowBackupArchive.normalize(request.destination);
        if (destination == null || TerminowBackupArchive.isNotBackedUp(destination))
            throw new IOException("Files can not be restored to " + request.destination);
        stage(context, job, Stage.RESTORING, request.fileSize);
        List<String> command = new ArrayList<>(Arrays.asList(prefix + "/bin/sh", "-c", "echo TERMINOW_PID=$$ >&2; exec \"$@\"", "terminow",
            prefix + "/bin/proot-distro", "login", request.alias, "--user", "root", "--"));
        command.addAll(TerminowBackupArchive.folderRestoreArguments(destination, request.overwrite));
        ArrayDeque<String> lines = new ArrayDeque<>();
        Process process = runner.start(command);
        job.process = process;
        process.getInputStream().close();
        Thread errors = readErrorsInBackground(process, job, lines);
        // tar can not tell gzip on a pipe: it gets the archive unpacked
        IOException readError = feed(context, file, process, job, true);
        int exit = process.waitFor();
        errors.join(2000);
        if (job.state != State.RUNNING) return;
        if (readError != null || exit != 0) {
            synchronized (lines) {
                job.error = readError != null ? "The file could not be read: " + readError.getMessage() :
                    "tar ended with " + exit + (lines.isEmpty() ? "" : ":\n" + String.join("\n", lines));
            }
            job.state = State.FAILED;
        }
    }

    /**
     * The file into the program on stdin, as it is for proot-distro, which unpacks gzip itself,
     * or unpacked. Returns a read error. The progress is of the file as it is.
     */
    @Nullable
    private static IOException feed(@NonNull Context context, @NonNull Uri file, @NonNull Process process, @NonNull Job job, boolean unpack) {
        IOException readError = null;
        try (InputStream raw = context.getContentResolver().openInputStream(file); OutputStream out = process.getOutputStream()) {
            if (raw == null) return new IOException("The file could not be opened");
            InputStream counted = new java.io.FilterInputStream(raw) {
                @Override
                public int read(@NonNull byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) told(context, job, n);
                    return n;
                }
            };
            BufferedInputStream buffered = new BufferedInputStream(counted, 1 << 16);
            buffered.mark(2);
            boolean gzipped = buffered.read() == 0x1f && buffered.read() == 0x8b;
            buffered.reset();
            InputStream in = unpack && gzipped ? new GZIPInputStream(buffered, 1 << 16) : buffered;
            byte[] buffer = new byte[1 << 16];
            while (true) {
                int n;
                try {
                    n = in.read(buffer);
                } catch (IOException e) {
                    readError = e;
                    break;
                }
                if (n <= 0) break;
                try {
                    out.write(buffer, 0, n);
                } catch (IOException e) {
                    // The program stopped reading: why is in what it wrote and in its exit status
                    break;
                }
            }
        } catch (IOException ignored) {
            // Closing stdin of a program that ended already
        }
        if (readError != null && job.pid > 0) android.os.Process.sendSignal(job.pid, 2);
        return readError;
    }

    /** A name for the environment that is there while a backup is restored, which is not taken. */
    @NonNull
    static String asideName(@NonNull String alias, @NonNull File containers) {
        String stamp = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(new Date());
        // Within the 64 characters of a name, also with "-2" and so on
        String base = (alias.length() > 30 ? alias.substring(0, 30) : alias) + "-before-restore-" + stamp;
        String name = base;
        for (int i = 2; new File(containers, name).exists(); i++) name = base + "-" + i;
        return name;
    }

    private static void runOrFail(@NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix, @NonNull Job job,
                                  @NonNull List<String> arguments, @NonNull String what) throws Exception {
        ArrayDeque<String> lines = new ArrayDeque<>();
        List<String> command = new ArrayList<>(Arrays.asList(prefix + "/bin/proot-distro"));
        command.addAll(arguments);
        Process process = runner.start(command);
        job.process = process;
        process.getOutputStream().close();
        Thread errors = readErrorsInBackground(process, job, lines);
        drain(process.getInputStream());
        int exit = process.waitFor();
        errors.join(2000);
        if (exit != 0 && job.state == State.RUNNING) {
            synchronized (lines) {
                job.error = what + " ended with " + exit + (lines.isEmpty() ? "" : ":\n" + String.join("\n", lines));
            }
            job.state = State.FAILED;
        }
    }

    private static int runQuietly(@NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix, @NonNull List<String> arguments) {
        try {
            List<String> command = new ArrayList<>(Arrays.asList(prefix + "/bin/proot-distro"));
            command.addAll(arguments);
            Process process = runner.start(command);
            process.getOutputStream().close();
            Thread errors = new Thread(() -> drain(process.getErrorStream()));
            errors.setDaemon(true);
            errors.start();
            drain(process.getInputStream());
            return process.waitFor();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void drain(@NonNull InputStream in) {
        byte[] buffer = new byte[8192];
        try {
            while (in.read(buffer) > 0) {
                // Read so that the program does not block on a full pipe
            }
        } catch (IOException ignored) {
            // It ended
        }
    }

    // ---------------------------------------------------------------------------------------
    // Progress

    private static void copy(@NonNull InputStream in, @NonNull OutputStream out, @NonNull Job job, @NonNull Context context) throws IOException {
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            if (job.state != State.RUNNING) throw new IOException("Stopped");
            out.write(buffer, 0, n);
            told(context, job, n);
        }
    }

    private static void stage(@NonNull Context context, @NonNull Job job, @NonNull Stage stage, long total) {
        job.stage = stage;
        job.bytes = 0;
        job.total = total;
        notifyProgress(context, job);
        changed();
    }

    private static void told(@NonNull Context context, @NonNull Job job, int bytes) {
        job.bytes += bytes;
        long now = System.currentTimeMillis();
        if (now - job.toldAt >= 500) {
            job.toldAt = now;
            notifyProgress(context, job);
            changed();
        }
    }

    /**
     * The percentage done of the stage, -1 when it is not known: only with a total that was
     * found out, and never 100 before the stage is done.
     */
    static int percent(long bytes, long total) {
        if (total <= 0) return -1;
        return (int) Math.min(99, bytes * 100 / total);
    }

    @NonNull
    static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    /** The notification of the job, while it runs and after it ended; tapping it opens TermiNow. */
    private static void notifyProgress(@NonNull Context context, @NonNull Job job) {
        try {
            NotificationManager manager = NotificationUtils.getNotificationManager(context);
            if (manager == null) return;
            NotificationUtils.setupNotificationChannel(context, CHANNEL_ID, context.getString(R.string.terminow_backup_channel),
                NotificationManager.IMPORTANCE_LOW);
            Intent open = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
            PendingIntent content = open == null ? null : PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = context.getString(job.isBackup() ? R.string.terminow_backup_notification_backup :
                R.string.terminow_backup_notification_restore, job.request.title);
            Notification.Builder builder = NotificationUtils.geNotificationBuilder(context, CHANNEL_ID, Notification.PRIORITY_LOW, title,
                describe(context, job), null, content, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
            if (builder == null) return;
            builder.setSmallIcon(R.drawable.ic_service_notification);
            boolean running = job.state == State.RUNNING;
            builder.setOngoing(running);
            builder.setOnlyAlertOnce(true);
            if (running) {
                int percent = percent(job.bytes, job.total);
                builder.setProgress(100, Math.max(percent, 0), percent < 0);
            } else {
                builder.setAutoCancel(true);
            }
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (RuntimeException ignored) {
            // No notification, the dialog still shows it
        }
    }

    /** One line about where the job is, for the notification and the dialog. */
    @NonNull
    static String describe(@NonNull Context context, @NonNull Job job) {
        String time = elapsed(job.elapsed());
        switch (job.state) {
            case SUCCEEDED:
                return context.getString(R.string.terminow_backup_state_done, time);
            case FAILED:
                return context.getString(R.string.terminow_backup_state_failed, time);
            case CANCELLED:
                return context.getString(R.string.terminow_backup_state_cancelled);
            default:
                break;
        }
        int stage;
        switch (job.stage) {
            case CHECKING: stage = job.request.kind == Kind.INSPECT ? R.string.terminow_backup_stage_inspecting : R.string.terminow_backup_stage_checking; break;
            case ARCHIVING: stage = R.string.terminow_backup_stage_archiving; break;
            case VERIFYING: stage = R.string.terminow_backup_stage_verifying; break;
            case PREPARING: stage = R.string.terminow_backup_stage_preparing; break;
            case RESTORING: stage = R.string.terminow_backup_stage_restoring; break;
            default: stage = R.string.terminow_backup_stage_finishing; break;
        }
        int percent = percent(job.bytes, job.total);
        String amount = percent >= 0 ? context.getString(R.string.terminow_backup_amount_of, human(job.bytes), human(job.total), percent) :
            human(job.bytes);
        return context.getString(R.string.terminow_backup_progress_line, context.getString(stage), amount, time);
    }

    @NonNull
    static String elapsed(long millis) {
        long seconds = millis / 1000;
        return seconds >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60) :
            String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    // ---------------------------------------------------------------------------------------
    // Programs

    @NonNull
    private static Thread readErrorsInBackground(@NonNull Process process, @NonNull Job job, @NonNull ArrayDeque<String> lines) {
        Thread errors = new Thread(() -> readErrors(process.getErrorStream(), job, lines), "terminow-phone-backup-log");
        errors.setDaemon(true);
        errors.start();
        return errors;
    }

    /** The last lines that a program wrote to stderr, for why it failed; its progress bar lines are left out. */
    private static void readErrors(@NonNull InputStream in, @NonNull Job job, @NonNull ArrayDeque<String> lines) {
        StringBuilder line = new StringBuilder();
        try {
            int c;
            while ((c = in.read()) != -1) {
                if (c != '\n' && c != '\r') {
                    if (line.length() < 500) line.append((char) c);
                    continue;
                }
                String text = line.toString().replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").trim();
                line.setLength(0);
                if (text.isEmpty()) continue;
                Matcher pid = PID.matcher(text);
                if (job.pid == 0 && pid.matches()) {
                    job.pid = Integer.parseInt(pid.group(1));
                    continue;
                }
                if (c == '\r') continue;
                synchronized (lines) {
                    lines.addLast(text);
                    while (lines.size() > ERROR_LINES) lines.removeFirst();
                }
            }
        } catch (IOException ignored) {
            // The program ended
        }
    }

    private static void delete(@NonNull Context context, @NonNull Uri uri) {
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), uri);
        } catch (Exception ignored) {
            // A provider that can not delete keeps the unfinished file
        }
    }
}
