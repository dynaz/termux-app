package com.termux.app.terminow;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A debug log for when a tab or the app seems stuck, turned on in Settings: every few seconds
 * the I/O of each tab and the memory of the app and the phone, and whenever the main thread,
 * which draws the terminals and takes the keys, does not answer for a while, how long and where
 * it is in the code. Never what a tab shows, what was typed, or a password.
 */
final class TerminowDebugLog {

    private static final String PREFS = "terminow_debug";
    private static final String KEY_RECORDING = "recording";
    private static final long SNAPSHOT_MS = 15_000;
    private static final long PING_MS = 1_000;
    /** How long the main thread may not answer before it is written down. */
    private static final long STALL_MS = 3_000;
    private static final long MAX_BYTES = 2L << 20;

    @Nullable private static Thread sThread;
    private static volatile boolean sRunning;
    private static volatile long sAnsweredAt;
    private static int sLaunchLinesWritten;

    private TerminowDebugLog() {}

    static boolean isRecording(@NonNull Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RECORDING, false);
    }

    @NonNull
    static File file(@NonNull Context context) {
        return new File(context.getFilesDir(), "terminow-debug.log");
    }

    @NonNull
    private static File olderFile(@NonNull Context context) {
        return new File(context.getFilesDir(), "terminow-debug.old.log");
    }

    /** Turn recording on or off, and remember it for the next start of the app. */
    static void setRecording(@NonNull Context context, boolean recording, @NonNull Supplier<List<TerminalSession>> sessions) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RECORDING, recording).apply();
        if (recording) start(context, sessions);
        else stop(context);
    }

    /** Start recording if it was turned on, like when the app starts. */
    static synchronized void start(@NonNull Context context, @NonNull Supplier<List<TerminalSession>> sessions) {
        if (sRunning || !isRecording(context)) return;
        Context app = context.getApplicationContext();
        sRunning = true;
        sAnsweredAt = System.currentTimeMillis();
        write(app, "recording started, TermiNow " + com.termux.BuildConfig.VERSION_NAME + " (" + com.termux.BuildConfig.VERSION_CODE + ")");
        Handler main = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> run(app, main, sessions), "terminow-debug-log");
        thread.setDaemon(true);
        sThread = thread;
        thread.start();
    }

    static synchronized void stop(@NonNull Context context) {
        if (!sRunning) return;
        sRunning = false;
        Thread thread = sThread;
        if (thread != null) thread.interrupt();
        sThread = null;
        write(context.getApplicationContext(), "recording stopped");
    }

    static void clear(@NonNull Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
        //noinspection ResultOfMethodCallIgnored
        olderFile(context).delete();
        if (sRunning) write(context.getApplicationContext(), "log cleared, recording goes on");
    }

    /** The whole log, the older part first, into a file the user picked. */
    static void copyTo(@NonNull Context context, @NonNull OutputStream out) throws IOException {
        for (File file : new File[]{olderFile(context), file(context)}) {
            if (!file.isFile()) continue;
            try (InputStream in = new java.io.FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
        }
    }

    private static void run(@NonNull Context context, @NonNull Handler main, @NonNull Supplier<List<TerminalSession>> sessions) {
        long nextSnapshot = 0;
        boolean stalled = false;
        while (sRunning) {
            long now = System.currentTimeMillis();
            // The main thread answers a ping as soon as it is free
            main.post(() -> sAnsweredAt = System.currentTimeMillis());
            long silent = now - sAnsweredAt;
            if (silent >= STALL_MS && !stalled) {
                stalled = true;
                write(context, "MAIN THREAD NOT ANSWERING for " + silent / 1000 + " s, where it is:\n" + mainStack());
                snapshot(context, sessions);
            } else if (silent < STALL_MS && stalled) {
                stalled = false;
                write(context, "main thread answers again");
            }
            if (now >= nextSnapshot) {
                snapshot(context, sessions);
                nextSnapshot = now + SNAPSHOT_MS;
            }
            try {
                Thread.sleep(PING_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The I/O of every tab, the memory of the app and the phone, and new starts of Linux. */
    private static void snapshot(@NonNull Context context, @NonNull Supplier<List<TerminalSession>> sessions) {
        StringBuilder text = new StringBuilder("snapshot");
        Runtime runtime = Runtime.getRuntime();
        text.append("\n  app memory: ").append((runtime.totalMemory() - runtime.freeMemory()) >> 20).append(" of ")
            .append(runtime.maxMemory() >> 20).append(" MB");
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memory);
            text.append("\n  phone memory: ").append(memory.availMem >> 20).append(" MB free of ").append(memory.totalMem >> 20)
                .append(" MB").append(memory.lowMemory ? ", LOW" : "");
        }
        List<TerminalSession> list;
        try {
            list = sessions.get();
        } catch (RuntimeException e) {
            list = java.util.Collections.emptyList();
        }
        text.append("\n  tabs: ").append(list.size());
        for (TerminalSession session : list) {
            text.append("\n  tab ").append(session.mHandle, 0, Math.min(8, session.mHandle.length()))
                .append(session.mSessionName != null ? " \"" + session.mSessionName + "\"" : "")
                .append(": ").append(session.getIoDiagnostics().replace("\n", "\n    "));
        }
        List<String> launches = TerminowLauncher.LAUNCHES.diagnostics();
        if (sLaunchLinesWritten > launches.size()) sLaunchLinesWritten = 0;
        for (String line : launches.subList(sLaunchLinesWritten, launches.size())) text.append("\n  start: ").append(line);
        sLaunchLinesWritten = launches.size();
        write(context, text.toString());
    }

    /** Where the main thread is: only the names of the code, nothing of what it works on. */
    @NonNull
    static String mainStack() {
        StringBuilder text = new StringBuilder();
        StackTraceElement[] stack = Looper.getMainLooper().getThread().getStackTrace();
        for (int i = 0; i < Math.min(stack.length, 30); i++) text.append("    at ").append(stack[i]).append('\n');
        return text.toString();
    }

    private static synchronized void write(@NonNull Context context, @NonNull String text) {
        File file = file(context);
        if (file.length() > MAX_BYTES) {
            // The older half is kept once
            //noinspection ResultOfMethodCallIgnored
            olderFile(context).delete();
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(olderFile(context));
        }
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((time + "  " + text + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // No log then
        }
    }
}
