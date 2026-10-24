package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Runs a managed task by starting Claude Code headless — {@code claude -p "<request>"
 * --output-format stream-json --verbose} — as its own captured process in the shared session's
 * environment, through {@link TerminowClaudeSetup}. The request is passed as a separate argument,
 * never spliced into the script, so nothing is typed into a terminal and quoting is safe. Output
 * is streamed to the task as it arrives, the run is stopped at the timeout or on cancel, and the
 * final {@code result} event decides success or failure.
 *
 * <p>This is deliberately the reliable, structured path: it does not drive an interactive terminal
 * and it adds no automatic-approval flag, so a task runs with Claude Code's own default
 * permissions and cannot silently gain unrestricted shell or file access.</p>
 */
public final class TerminowMcpBridgeExecutor implements TerminowMcpBridge.Executor {

    private static final String LOG_TAG = "TerminowMcpBridgeExecutor";

    public static final int DEFAULT_TIMEOUT_S = 600;
    public static final int MAX_TIMEOUT_S = 3600;
    /** How much of a task's stream is kept in the mirror log; the rest is dropped (bounded). */
    static final int MIRROR_MAX = 512 * 1024;

    /**
     * Runs {@code claude -p} in the task's folder; the request is {@code $1}, the folder {@code $2}.
     * Every option comes first and {@code --} ends them, so a request that starts with {@code -}
     * (say {@code --dangerously-skip-permissions}) is the prompt, never parsed as a flag.
     */
    static final String RUN_SCRIPT =
        "PATH=\"$HOME/.local/bin:$PATH\"; export PATH\n" +
        "if [ -n \"$2\" ]; then cd -- \"$2\" || { echo \"TermiNow: the folder $2 does not exist\" >&2; exit 97; }; fi\n" +
        "command -v claude >/dev/null 2>&1 || { echo \"TermiNow: Claude Code is not installed in this environment\" >&2; exit 98; }\n" +
        "exec claude -p --output-format stream-json --verbose -- \"$1\"\n";

    private final TerminowClaudeSetup mSetup;
    private final TerminowMcpTargets mTargets;
    @Nullable private final File mLogDir;
    private final Map<String, Run> mRuns = new ConcurrentHashMap<>();

    public TerminowMcpBridgeExecutor(@NonNull TerminowClaudeSetup setup, @NonNull TerminowMcpTargets targets,
                                     @Nullable File logDir) {
        mSetup = setup;
        mTargets = targets;
        mLogDir = logDir;
        cleanStaleLogs();
    }

    /** Delete task logs left by a previous run, so old plaintext output is not kept around. */
    private void cleanStaleLogs() {
        if (mLogDir == null) return;
        File[] files = mLogDir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.getName().endsWith(".log")) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** The file a task's stream is mirrored to, for opening it in a terminal tab; {@code null} if off. */
    @Nullable
    public File logFile(@NonNull String taskId) {
        return mLogDir == null ? null : new File(mLogDir, taskId + ".log");
    }

    /** The process and a cancel request meet under the run's lock, so neither can miss the other. */
    static final class Run {
        private Process process;
        private boolean cancelRequested;

        /** Record a cancel; stop the process if it has started. */
        synchronized void cancel() {
            cancelRequested = true;
            if (process != null) process.destroy();
        }

        /** Hand over the started process; false (and it is stopped) when a cancel came first. */
        synchronized boolean attach(@NonNull Process started) {
            process = started;
            if (cancelRequested) {
                started.destroy();
                return false;
            }
            return true;
        }

        synchronized boolean isCancelRequested() {
            return cancelRequested;
        }
    }

    @Override
    public void start(@NonNull TerminowMcpBridgeTask task, @NonNull TerminowMcpBridge.Share share,
                      @NonNull TerminowMcpBridge.Callback callback) {
        Run run = new Run();
        mRuns.put(task.id, run);
        Thread thread = new Thread(() -> execute(task, share, callback, run), "TerminowMcpBridge task " + task.id);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void cancel(@NonNull String taskId) {
        Run run = mRuns.get(taskId);
        if (run != null) run.cancel();
    }

    private void execute(@NonNull TerminowMcpBridgeTask task, @NonNull TerminowMcpBridge.Share share,
                         @NonNull TerminowMcpBridge.Callback callback, @NonNull Run run) {
        int timeout = task.timeoutSeconds <= 0 ? DEFAULT_TIMEOUT_S : Math.min(task.timeoutSeconds, MAX_TIMEOUT_S);
        List<String> command;
        try {
            String target = share.environmentKey.isEmpty() ? TerminowMcpTargets.TARGET_TERMINOW : share.environmentKey;
            command = TerminowClaudeSetup.buildCommand(mTargets.getPrefixDir(), target, share.user, RUN_SCRIPT,
                task.request, share.folder);
        } catch (IllegalArgumentException e) {
            callback.failed(task.id, "The session's target is not valid: " + e.getMessage());
            mRuns.remove(task.id);
            return;
        }

        // A cancel that arrived before the process existed must stop it, not let it run to the end
        if (run.isCancelRequested()) {
            mRuns.remove(task.id);
            callback.cancelled(task.id);
            return;
        }
        Process process;
        try {
            process = mSetup.start(command);
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not start the managed task", e);
            callback.failed(task.id, "Could not start Claude Code: " + e.getMessage());
            mRuns.remove(task.id);
            return;
        }
        if (!run.attach(process)) {
            mRuns.remove(task.id);
            callback.cancelled(task.id);
            return;
        }
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // Nothing is sent to its input
        }

        // Mirror the stream to a log file so it can be opened live in a terminal tab.
        OutputStream mirror = openMirror(task);

        ResultCapture result = new ResultCapture();
        Thread stdout = streamTo(process.getInputStream(), callback, task.id, result, false, mirror);
        Thread stderr = streamTo(process.getErrorStream(), callback, task.id, result, true, mirror);

        boolean finished;
        try {
            finished = process.waitFor(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        // Stop a run that outlived its timeout before draining its output
        if (!finished) process.destroy();
        join(stdout);
        join(stderr);
        writeMirror(mirror, "\n── task " + (run.isCancelRequested() ? "cancelled" : finished ? "finished" : "stopped") + " ──\n");
        closeMirror(mirror);

        try {
            if (run.isCancelRequested()) {
                callback.cancelled(task.id);
            } else if (!finished) {
                callback.failed(task.id, "The task ran longer than " + timeout + "s and was stopped");
            } else {
                int exit = process.exitValue();
                if (result.isError || exit != 0) {
                    String reason = !result.resultText.isEmpty() ? result.resultText
                        : "Claude Code exited with code " + exit;
                    callback.failed(task.id, reason);
                } else {
                    callback.completed(task.id, exit, result.resultText);
                }
            }
        } finally {
            mRuns.remove(task.id);
        }
    }

    /** The final {@code result} event decides the outcome; everything else is streamed as output. */
    private static final class ResultCapture {
        volatile String resultText = "";
        volatile boolean isError;
    }

    @NonNull
    private Thread streamTo(@NonNull InputStream in, @NonNull TerminowMcpBridge.Callback callback,
                            @NonNull String taskId, @NonNull ResultCapture result, boolean isStderr,
                            @Nullable OutputStream mirror) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String chunk = (isStderr ? "[stderr] " : "") + line + "\n";
                    callback.output(taskId, chunk);
                    writeMirror(mirror, chunk);
                    if (!isStderr) parseEvent(line, result);
                }
            } catch (IOException ignored) {
                // The process ended
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Nullable
    private OutputStream openMirror(@NonNull TerminowMcpBridgeTask task) {
        File file = logFile(task.id);
        if (file == null) return null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return null;
            FileOutputStream raw = new FileOutputStream(file);
            // Only the app's user may read the mirror; it holds task output in app-private storage.
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            OutputStream out = new BoundedMirror(raw);
            String head = task.request.length() > 200 ? task.request.substring(0, 200) + "…" : task.request;
            writeMirror(out, "── TermiNow task ──\n" + head + "\n\n");
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMirror(@Nullable OutputStream mirror, @NonNull String text) {
        if (mirror == null) return;
        try {
            synchronized (mirror) {
                mirror.write(text.getBytes(StandardCharsets.UTF_8));
                mirror.flush();
            }
        } catch (IOException ignored) {
            // The terminal that tails the file still works; a dropped write is not fatal
        }
    }

    /** A mirror that stops after a cap, so one task's log can not grow without bound. */
    private static final class BoundedMirror extends OutputStream {
        private final OutputStream mOut;
        private int mWritten;
        private boolean mNoted;

        BoundedMirror(@NonNull OutputStream out) {
            mOut = out;
        }

        @Override public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override public void write(@NonNull byte[] b, int off, int len) throws IOException {
            int room = MIRROR_MAX - mWritten;
            if (room <= 0) {
                note();
                return;
            }
            int n = Math.min(len, room);
            mOut.write(b, off, n);
            mWritten += n;
            if (n < len) note();
        }

        private void note() throws IOException {
            if (mNoted) return;
            mNoted = true;
            mOut.write("\n── output truncated ──\n".getBytes(StandardCharsets.UTF_8));
        }

        @Override public void flush() throws IOException { mOut.flush(); }
        @Override public void close() throws IOException { mOut.close(); }
    }

    private static void closeMirror(@Nullable OutputStream mirror) {
        if (mirror == null) return;
        try {
            mirror.close();
        } catch (IOException ignored) {
            // Nothing more to do
        }
    }

    /** Pull the result text out of a stream-json {@code result} event; ignore anything else. */
    private static void parseEvent(@NonNull String line, @NonNull ResultCapture result) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') return;
        try {
            JSONObject event = new JSONObject(trimmed);
            if ("result".equals(event.optString("type"))) {
                result.resultText = event.optString("result", result.resultText);
                result.isError = event.optBoolean("is_error", false);
            }
        } catch (JSONException ignored) {
            // A line that is not a JSON event is still streamed as output above
        }
    }

    private static void join(@NonNull Thread thread) {
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
