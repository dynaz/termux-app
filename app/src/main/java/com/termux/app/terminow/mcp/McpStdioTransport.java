package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The stdio transport: the server is a child process, and each JSON-RPC message is one line on
 * its stdin or stdout. A reader thread hands responses to the requests that wait for them by id,
 * and requests and notifications of the server to the listener. What the server writes to stderr
 * is kept, the last part only, to explain why it failed.
 */
public final class McpStdioTransport implements McpTransport {

    /** Starts the server process. The command is a list of arguments, never parsed by a shell. */
    @FunctionalInterface
    public interface ProcessStarter {
        @NonNull
        Process start(@NonNull List<String> command, @NonNull Map<String, String> extraEnv, @Nullable String workingDir) throws IOException;
    }

    static final int STDERR_KEEP_CHARS = 16 * 1024;
    private static final int STDERR_SHOWN_CHARS = 4 * 1024;
    private static final long EXIT_WAIT_MS = 2000;

    /** A request that waits for its response. */
    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        volatile JSONObject response;
        volatile McpException failure;

        void complete(@NonNull JSONObject message) {
            response = message;
            done.countDown();
        }

        void fail(@NonNull McpException e) {
            if (failure == null && response == null) failure = e;
            done.countDown();
        }
    }

    private final ProcessStarter mStarter;
    private final List<String> mCommand;
    private final Map<String, String> mEnv;
    @Nullable private final String mWorkingDir;

    private final Map<String, Pending> mPending = new ConcurrentHashMap<>();
    private final StringBuilder mStderr = new StringBuilder();
    private final Object mWriteLock = new Object();
    @Nullable private Process mProcess;
    @Nullable private OutputStream mStdin;
    @Nullable private Thread mStderrThread;
    @Nullable private volatile Listener mListener;
    private volatile boolean mClosed;
    @Nullable private volatile McpException mDeath;

    public McpStdioTransport(@NonNull ProcessStarter starter, @NonNull List<String> command, @NonNull Map<String, String> env,
                             @Nullable String workingDir) {
        if (command.isEmpty()) throw new IllegalArgumentException("No command");
        mStarter = starter;
        mCommand = Collections.unmodifiableList(new ArrayList<>(command));
        mEnv = Collections.unmodifiableMap(new LinkedHashMap<>(env));
        mWorkingDir = workingDir;
    }

    @Override
    public void start(@NonNull Listener listener) throws McpException {
        mListener = listener;
        Process process;
        try {
            process = mStarter.start(mCommand, mEnv, mWorkingDir);
        } catch (IOException | RuntimeException e) {
            throw new McpException(McpException.Kind.PROCESS_START_FAILED, "Failed to start " + mCommand.get(0) + ": " + e.getMessage());
        }
        synchronized (this) {
            mProcess = process;
            mStdin = process.getOutputStream();
        }
        Thread stderr = new Thread(() -> readStderr(process.getErrorStream()), "mcp-stdio-stderr");
        stderr.setDaemon(true);
        mStderrThread = stderr;
        stderr.start();
        Thread stdout = new Thread(() -> readStdout(process), "mcp-stdio-stdout");
        stdout.setDaemon(true);
        stdout.start();
    }

    @Override
    public void setProtocolVersion(@Nullable String version) {
        // stdio messages carry no headers
    }

    @NonNull
    @Override
    public JSONObject request(@NonNull JSONObject message, @NonNull String method, long timeoutMs) throws McpException {
        String id = String.valueOf(message.opt("id"));
        Pending pending = new Pending();
        mPending.put(id, pending);
        try {
            McpException death = mDeath;
            if (death != null) throw death;
            write(message);
            boolean answered;
            try {
                answered = pending.done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpException(McpException.Kind.CANCELLED, "Interrupted");
            }
            if (!answered) throw new McpException(McpException.Kind.TIMEOUT, "The server did not answer " + method + " in time");
            if (pending.failure != null) throw pending.failure;
            return pending.response;
        } finally {
            mPending.remove(id, pending);
        }
    }

    @Override
    public void notify(@NonNull JSONObject message, @NonNull String method) throws McpException {
        write(message);
    }

    @Override
    public void respond(@NonNull JSONObject response) throws McpException {
        write(response);
    }

    @Override
    public void cancel(@NonNull Object id) {
        Pending pending = mPending.get(String.valueOf(id));
        if (pending != null) pending.fail(new McpException(McpException.Kind.CANCELLED, "Cancelled"));
    }

    /** Close stdin, which asks the server to exit, then end the process if it does not. */
    @Override
    @SuppressWarnings("NewApi")
    public void close() {
        Process process;
        synchronized (this) {
            if (mClosed) return;
            mClosed = true;
            process = mProcess;
        }
        failPending(new McpException(McpException.Kind.CANCELLED, "The connection was closed"));
        if (process == null) return;
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // Already closed
        }
        if (waitForExit(process, EXIT_WAIT_MS)) return;
        process.destroy();
        if (waitForExit(process, 1000)) return;
        try {
            process.destroyForcibly();
        } catch (NoSuchMethodError e) {
            // Before Android 8 destroy() already sends SIGKILL
        }
    }

    /** The last part of what the server wrote to stderr. */
    @NonNull
    public String getStderrTail() {
        synchronized (mStderr) {
            int start = Math.max(0, mStderr.length() - STDERR_SHOWN_CHARS);
            return mStderr.substring(start).trim();
        }
    }

    private void write(@NonNull JSONObject message) throws McpException {
        McpException death = mDeath;
        if (death != null) throw death;
        if (mClosed) throw new McpException(McpException.Kind.CANCELLED, "The connection was closed");
        // JSON text never has a raw newline, so a message is always one line
        byte[] line = (message.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (mWriteLock) {
            try {
                OutputStream stdin = mStdin;
                if (stdin == null) throw new IOException("Not started");
                stdin.write(line);
                stdin.flush();
            } catch (IOException e) {
                death = mDeath;
                if (death != null) throw death;
                if (mClosed) throw new McpException(McpException.Kind.CANCELLED, "The connection was closed");
                throw new McpException(McpException.Kind.PROCESS_EXITED, "Failed to write to the server: " + e.getMessage());
            }
        }
    }

    private void readStdout(@NonNull Process process) {
        McpException reason = null;
        try (InputStream in = new BufferedInputStream(process.getInputStream())) {
            byte[] line;
            while ((line = readLine(in)) != null) {
                if (line.length == 0) continue;
                dispatch(new String(line, StandardCharsets.UTF_8));
            }
        } catch (McpException e) {
            reason = e;
        } catch (IOException e) {
            // The pipe broke, the process is ending
        }
        if (reason != null) {
            // A server that breaks the framing can not be read any further
            process.destroy();
        }
        int exitCode = waitForExit(process, EXIT_WAIT_MS) ? process.exitValue() : -1;
        Thread stderr = mStderrThread;
        if (stderr != null) {
            try {
                stderr.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (mClosed) return;
        if (reason == null) {
            String tail = getStderrTail();
            reason = new McpException(McpException.Kind.PROCESS_EXITED, (exitCode >= 0 ? "The server process exited with code " + exitCode :
                "The server closed its output") + (tail.isEmpty() ? "" : ". Its last output:\n" + tail));
        }
        mDeath = reason;
        Listener listener = mListener;
        // The session learns first, so a request that fails next finds it failed already
        if (listener != null) listener.onClosed(reason);
        failPending(reason);
    }

    private void dispatch(@NonNull String text) {
        JSONObject message;
        try {
            message = new JSONObject(text);
        } catch (JSONException e) {
            // Not a message, like a log line a server should have written to stderr
            appendStderr("stdout: " + text + "\n");
            return;
        }
        Listener listener = mListener;
        if (message.has("method")) {
            if (listener == null) return;
            if (message.has("id")) listener.onServerRequest(message);
            else listener.onNotification(message);
            return;
        }
        Pending pending = mPending.get(String.valueOf(message.opt("id")));
        if (pending != null) pending.complete(message);
    }

    /** One line without its newline, {@code null} at the end of the stream. */
    @Nullable
    private static byte[] readLine(@NonNull InputStream in) throws IOException, McpException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return out.toByteArray();
            if (c == '\r') continue;
            out.write(c);
            if (out.size() > MAX_MESSAGE_BYTES)
                throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server sent a message that is too large");
        }
        return out.size() > 0 ? out.toByteArray() : null;
    }

    private void readStderr(@NonNull InputStream in) {
        byte[] buffer = new byte[4096];
        int read;
        try (InputStream input = in) {
            while ((read = input.read(buffer)) != -1)
                appendStderr(new String(buffer, 0, read, StandardCharsets.UTF_8));
        } catch (IOException e) {
            // The process ended
        }
    }

    private void appendStderr(@NonNull String text) {
        synchronized (mStderr) {
            mStderr.append(text);
            if (mStderr.length() > STDERR_KEEP_CHARS) mStderr.delete(0, mStderr.length() - STDERR_KEEP_CHARS);
        }
    }

    private void failPending(@NonNull McpException reason) {
        for (Pending pending : mPending.values()) pending.fail(reason);
    }

    /** Whether the process ended in time. Polls, since Process.waitFor with a timeout needs Android 8. */
    private static boolean waitForExit(@NonNull Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException e) {
                if (System.currentTimeMillis() >= deadline) return false;
                try {
                    Thread.sleep(20);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

}
