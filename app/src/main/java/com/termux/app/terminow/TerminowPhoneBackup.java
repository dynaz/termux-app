package com.termux.app.terminow;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.linux.TerminowLinuxInstaller;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/**
 * Backs up a Linux environment to a file on the phone that the user picked, with
 * {@code proot-distro backup <alias>}: its archive comes on stdout and goes straight into the
 * file, so any folder works, without the storage permission and without a copy in the app first.
 * The archive is gzipped here, unless proot-distro gzipped it already. One backup at a time; it
 * keeps running when its dialog is hidden.
 */
final class TerminowPhoneBackup {

    enum State { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    static final class Job {
        final String alias;
        final String title;
        final Uri uri;
        final String fileName;
        final long startedAt = System.currentTimeMillis();
        volatile State state = State.RUNNING;
        volatile long bytes;
        /** Why it failed: the last lines that proot-distro wrote, or the error. */
        volatile String error = "";
        volatile int pid;
        volatile Process process;
        /** When the listeners were told of the progress last, so that they are told at most twice a second. */
        long toldAt;

        Job(@NonNull String alias, @NonNull String title, @NonNull Uri uri, @NonNull String fileName) {
            this.alias = alias;
            this.title = title;
            this.uri = uri;
            this.fileName = fileName;
        }
    }

    private static final Pattern PID = Pattern.compile("TERMINOW_PID=(\\d+)");
    private static final int ERROR_LINES = 6;
    @Nullable private static Handler sMain;
    private static final List<Runnable> sListeners = new CopyOnWriteArrayList<>();
    @Nullable private static volatile Job sJob;

    private TerminowPhoneBackup() {}

    /** The name that the file picker suggests, like ubuntu-backup-20261019-1317.tar.gz. */
    @NonNull
    static String fileName(@NonNull String alias, long now) {
        return alias + "-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(now)) + ".tar.gz";
    }

    /** The backup that runs or ran last, {@code null} if there was none. */
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

    /** The main thread, made when it is first needed. */
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
     * Start backing up into the file, {@code null} if a backup runs already.
     *
     * @param prefix The prefix of TermiNow, where its proot-distro is.
     */
    @Nullable
    static Job start(@NonNull Context context, @NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix,
                     @NonNull String alias, @NonNull String title, @NonNull Uri uri, @NonNull String fileName) {
        if (isRunning()) return null;
        Job job = new Job(alias, title, uri, fileName);
        sJob = job;
        Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> run(app, runner, prefix, job), "terminow-phone-backup");
        thread.setDaemon(true);
        thread.start();
        changed();
        return job;
    }

    /** Stop the backup, like Ctrl-C, which proot-distro answers by cleaning up; the unfinished file is removed. */
    static void cancel(@NonNull TerminowLinuxInstaller.Runner runner) {
        Job job = sJob;
        if (job == null || job.state != State.RUNNING) return;
        job.state = State.CANCELLED;
        if (job.pid > 0) runner.interrupt(job.pid);
        Process process = job.process;
        if (process != null) main().postDelayed(process::destroy, 5000);
        changed();
    }

    private static void run(@NonNull Context context, @NonNull TerminowLinuxInstaller.Runner runner, @NonNull String prefix,
                            @NonNull Job job) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        boolean written = false;
        try {
            // The shell says its pid, which exec keeps for proot-distro, so that it can be interrupted
            Process process = runner.start(Arrays.asList(prefix + "/bin/sh", "-c", "echo TERMINOW_PID=$$ >&2; exec \"$@\"", "terminow",
                prefix + "/bin/proot-distro", "backup", job.alias));
            job.process = process;
            process.getOutputStream().close();
            Thread errors = new Thread(() -> readErrors(process.getErrorStream(), job, lines), "terminow-phone-backup-log");
            errors.setDaemon(true);
            errors.start();
            try (OutputStream out = context.getContentResolver().openOutputStream(job.uri, "w")) {
                if (out == null) throw new IOException("The file could not be opened");
                copyGzipped(process.getInputStream(), out, n -> {
                    job.bytes += n;
                    long now = System.currentTimeMillis();
                    if (now - job.toldAt >= 500) {
                        job.toldAt = now;
                        changed();
                    }
                });
                written = true;
            }
            int exit = process.waitFor();
            errors.join(2000);
            if (job.state == State.CANCELLED) {
                delete(context, job.uri);
            } else if (exit != 0) {
                synchronized (lines) {
                    job.error = "proot-distro backup ended with " + exit + (lines.isEmpty() ? "" : ":\n" + String.join("\n", lines));
                }
                job.state = State.FAILED;
                delete(context, job.uri);
            } else {
                job.state = State.SUCCEEDED;
            }
        } catch (Exception e) {
            if (job.state != State.CANCELLED) {
                job.error = e.getMessage() != null ? e.getMessage() : e.toString();
                job.state = State.FAILED;
            }
            Process process = job.process;
            if (process != null) process.destroy();
            if (!written || job.state != State.SUCCEEDED) delete(context, job.uri);
        }
        changed();
    }

    private static void readErrors(@NonNull InputStream in, @NonNull Job job, @NonNull ArrayDeque<String> lines) {
        StringBuilder line = new StringBuilder();
        try {
            int c;
            while ((c = in.read()) != -1) {
                // The progress bar of proot-distro redraws its line with \r
                if (c != '\n' && c != '\r') {
                    line.append((char) c);
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
            // The process ended
        }
    }

    interface Progress {
        void written(int bytes);
    }

    /** Copy the archive, gzipped: as it is if it starts like gzip already, else compressed here. */
    static void copyGzipped(@NonNull InputStream source, @NonNull OutputStream target, @NonNull Progress progress) throws IOException {
        BufferedInputStream in = new BufferedInputStream(source, 1 << 16);
        in.mark(2);
        boolean gzipped = in.read() == 0x1f && in.read() == 0x8b;
        in.reset();
        OutputStream out = gzipped ? target : new GZIPOutputStream(target, 1 << 16);
        byte[] buffer = new byte[1 << 16];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
            progress.written(n);
        }
        if (out instanceof GZIPOutputStream) ((GZIPOutputStream) out).finish();
        out.flush();
    }

    private static void delete(@NonNull Context context, @NonNull Uri uri) {
        try {
            DocumentsContract.deleteDocument(context.getContentResolver(), uri);
        } catch (Exception ignored) {
            // A provider that can not delete keeps the unfinished file
        }
    }
}
