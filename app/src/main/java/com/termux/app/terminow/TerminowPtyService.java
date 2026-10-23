package com.termux.app.terminow;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;
import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.termux.shell.TermuxShellUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.terminal.PtyProcess;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs terminal sessions for another app without showing the terminal: the process runs on a
 * pseudo terminal in the app's environment and its screen is streamed to the other app, which
 * shows it in its own terminal view.
 *
 * <p>The other app listens on 127.0.0.1 and sends {@link #ACTION_OPEN} with the port and a one
 * time token. This service connects to that port and sends the token first, so the other app knows
 * the connection comes from the app it sent the intent to. Nothing listens on a port here, so no
 * other app can reach a shell through this service. Who may send {@link #ACTION_OPEN} is checked
 * like for {@code RUN_COMMAND}: the service requires the RUN_COMMAND permission, and the sender,
 * the creator of the {@link #EXTRA_IDENTITY} {@link PendingIntent} set by Android, must be a
 * trusted companion app or allow-external-apps must be enabled.</p>
 *
 * <p>Frames in both directions are a type byte, a 4 byte big endian length and the payload. From
 * this service: {@code H} hello (JSON), {@code O} output, {@code X} exit (JSON). To this service:
 * {@code I} input, {@code R} resize (columns and rows, 2 bytes each), {@code K} kill, {@code D}
 * detach. A session whose connection is lost keeps running for {@link #DETACHED_TIMEOUT_MS} and
 * can be attached again with its id; the recent output is sent again then.</p>
 */
public class TerminowPtyService extends Service {

    public static final String ACTION_OPEN = TermuxConstants.TERMUX_PACKAGE_NAME + ".PTY_OPEN";
    public static final String ACTION_STATUS = TermuxConstants.TERMUX_PACKAGE_NAME + ".PTY_STATUS";

    private static final String EXTRA_PREFIX = TermuxConstants.TERMUX_PACKAGE_NAME + ".PTY_";
    public static final String EXTRA_PORT = EXTRA_PREFIX + "PORT";
    public static final String EXTRA_TOKEN = EXTRA_PREFIX + "TOKEN";
    public static final String EXTRA_SESSION = EXTRA_PREFIX + "SESSION";
    public static final String EXTRA_EXECUTABLE = EXTRA_PREFIX + "EXECUTABLE";
    public static final String EXTRA_ARGUMENTS = EXTRA_PREFIX + "ARGUMENTS";
    public static final String EXTRA_WORKDIR = EXTRA_PREFIX + "WORKDIR";
    public static final String EXTRA_COLUMNS = EXTRA_PREFIX + "COLUMNS";
    public static final String EXTRA_ROWS = EXTRA_PREFIX + "ROWS";
    /** A {@link PendingIntent} of the sender: its creator is the sender, and status replies go to it. */
    public static final String EXTRA_IDENTITY = EXTRA_PREFIX + "IDENTITY";

    public static final int PROTOCOL_VERSION = 1;

    private static final long DETACHED_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int REPLAY_BUFFER_SIZE = 256 * 1024;
    private static final int MAX_SESSIONS = 8;
    private static final int MAX_FRAME = 1024 * 1024;
    private static final int NOTIFICATION_ID = 1340;
    private static final String CHANNEL_ID = "terminow_pty";
    private static final String LOG_TAG = "TerminowPtyService";

    private final Map<String, PtySession> mSessions = new ConcurrentHashMap<>();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Called with startForegroundService(), so this must always be done first
        runStartForeground();
        if (intent == null) return stopIfIdle();

        PendingIntent identity = intent.getParcelableExtra(EXTRA_IDENTITY);
        String error = checkCaller(identity);
        if (error != null) {
            Logger.logError(LOG_TAG, "Refused " + intent.getAction() + ": " + error);
            if (identity != null) sendStatus(identity, false, error);
            return stopIfIdle();
        }

        if (ACTION_STATUS.equals(intent.getAction())) {
            sendStatus(identity, true, null);
        } else if (ACTION_OPEN.equals(intent.getAction())) {
            open(intent);
        }
        return stopIfIdle();
    }

    /** Returns why the sender may not use the service, or null if it may. */
    @Nullable
    private String checkCaller(@Nullable PendingIntent identity) {
        if (identity == null) return "the identity PendingIntent is missing";
        if (TermuxPluginUtils.isTrustedCompanionApp(this, identity)) return null;
        // Like RUN_COMMAND for any other app
        return TermuxPluginUtils.checkIfAllowExternalAppsPolicyIsViolated(this, LOG_TAG);
    }

    private void sendStatus(@NonNull PendingIntent identity, boolean allowed, @Nullable String error) {
        JSONObject status = new JSONObject();
        try {
            status.put("protocol", PROTOCOL_VERSION);
            status.put("versionCode", BuildConfig.VERSION_CODE);
            status.put("allowed", allowed);
            // Only needed to open the terminal of this app from the background, not for this service
            status.put("overlay", Settings.canDrawOverlays(this));
            status.put("sessions", mSessions.size());
            if (error != null) status.put("error", error);
        } catch (JSONException ignored) {}
        Bundle result = new Bundle();
        result.putString("stdout", status.toString());
        result.putString("stderr", "");
        result.putInt("exitCode", allowed ? 0 : 1);
        result.putInt("err", -1);
        result.putString("errmsg", error == null ? "" : error);
        Intent fillIn = new Intent().putExtra("result", result);
        try {
            identity.send(this, 0, fillIn);
        } catch (PendingIntent.CanceledException e) {
            Logger.logDebug(LOG_TAG, "The status receiver is gone");
        }
    }

    private void open(@NonNull Intent intent) {
        int port = intent.getIntExtra(EXTRA_PORT, -1);
        String token = intent.getStringExtra(EXTRA_TOKEN);
        String id = intent.getStringExtra(EXTRA_SESSION);
        if (port <= 0 || port > 65535 || token == null || token.length() < 16 || id == null || id.isEmpty() || id.length() > 64) {
            Logger.logError(LOG_TAG, "Refused an open request with missing or invalid extras");
            return;
        }

        PtySession session = mSessions.get(id);
        if (session == null) {
            if (mSessions.size() >= MAX_SESSIONS) {
                Logger.logError(LOG_TAG, "Refused a new session, " + MAX_SESSIONS + " are running");
                return;
            }
            // The environment is installed when the app is opened the first time
            if (!new java.io.File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash").isFile()) {
                PtySession.reportFailure(port, token, id, getString(R.string.terminow_pty_not_installed));
                return;
            }
            String executable = intent.getStringExtra(EXTRA_EXECUTABLE);
            if (executable == null || executable.isEmpty())
                executable = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
            String[] arguments = intent.getStringArrayExtra(EXTRA_ARGUMENTS);
            String workdir = intent.getStringExtra(EXTRA_WORKDIR);
            int columns = clamp(intent.getIntExtra(EXTRA_COLUMNS, 80), 10, 1000);
            int rows = clamp(intent.getIntExtra(EXTRA_ROWS, 24), 4, 1000);
            try {
                session = PtySession.start(this, id, executable, arguments == null ? new String[0] : arguments,
                    workdir, columns, rows);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start a session", e);
                // Report it to the app on its connection, so it does not just time out
                PtySession.reportFailure(port, token, id, String.valueOf(e.getMessage()));
                return;
            }
            mSessions.put(id, session);
        }
        session.attach(port, token);
        updateNotification();
    }

    void onSessionRemoved(@NonNull PtySession session) {
        mSessions.remove(session.mId, session);
        updateNotification();
        stopIfIdle();
    }

    private int stopIfIdle() {
        if (mSessions.isEmpty()) {
            stopForeground(true);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void runStartForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            NotificationUtils.setupNotificationChannel(this, CHANNEL_ID,
                getString(R.string.terminow_pty_notification_channel), NotificationManager.IMPORTANCE_LOW);
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && !mSessions.isEmpty()) manager.notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        int count = mSessions.size();
        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this, CHANNEL_ID,
            Notification.PRIORITY_LOW, getString(R.string.terminow_pty_notification_title),
            getResources().getQuantityString(R.plurals.terminow_pty_notification_text, count, count),
            null, null, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null) return new Notification();
        builder.setShowWhen(false);
        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setOngoing(true);
        return builder.build();
    }

    /** One process on a pseudo terminal, and the connection that shows it, if any. */
    static final class PtySession {

        final String mId;
        private final TerminowPtyService mService;
        private final int mFd;
        private final int mPid;
        private final FileOutputStream mPtyOut;
        /**
         * The duplicates of the pty master that the streams use, kept until the process exited:
         * a {@link ParcelFileDescriptor} that is dropped closes its duplicate when it is finalized.
         */
        private final ParcelFileDescriptor mPtyOutDescriptor;
        private final ParcelFileDescriptor mPtyInDescriptor;
        private final byte[] mReplay = new byte[REPLAY_BUFFER_SIZE];
        private int mReplayStart = 0;
        private int mReplayLength = 0;
        private Socket mSocket;
        private OutputStream mSocketOut;
        private Integer mExitCode;
        private long mDetachedAt = System.currentTimeMillis();

        private PtySession(TerminowPtyService service, String id, int fd, int pid) throws IOException {
            mService = service;
            mId = id;
            mFd = fd;
            mPid = pid;
            mPtyOutDescriptor = ParcelFileDescriptor.fromFd(fd);
            mPtyInDescriptor = ParcelFileDescriptor.fromFd(fd);
            mPtyOut = new FileOutputStream(mPtyOutDescriptor.getFileDescriptor());
        }

        static PtySession start(@NonNull TerminowPtyService service, @NonNull String id, @NonNull String executable,
                                @NonNull String[] arguments, @Nullable String workdir, int columns, int rows) throws IOException {
            TermuxShellEnvironment environmentClient = new TermuxShellEnvironment();
            ExecutionCommand executionCommand = new ExecutionCommand();
            executionCommand.executable = executable;
            executionCommand.arguments = arguments;
            executionCommand.workingDirectory = workdir == null || workdir.isEmpty()
                ? environmentClient.getDefaultWorkingDirectoryPath() : workdir;
            executionCommand.isPluginExecutionCommand = true;

            // The same as for the sessions of the app: the system linker, the termux-exec library
            // and the proot loader where they are needed
            TermuxShellUtils.setupSystemLinkerExecLdPreloadLib();
            String[] commandArgs = environmentClient.setupShellCommandArguments(executable, arguments);
            String[] argv = Arrays.copyOf(commandArgs, commandArgs.length);
            String processExecutable = TermuxShellUtils.isSystemLinkerPath(commandArgs[0]) && commandArgs.length > 1
                ? commandArgs[1] : commandArgs[0];
            argv[0] = processExecutable.substring(processExecutable.lastIndexOf('/') + 1);

            HashMap<String, String> environment = environmentClient.setupShellCommandEnvironment(service, executionCommand);
            environment.put("TERM", "xterm-256color");
            environment.put("COLORTERM", "truecolor");
            List<String> environmentList = ShellEnvironmentUtils.convertEnvironmentToEnviron(environment);
            Collections.sort(environmentList);

            int[] processId = new int[1];
            int fd = PtyProcess.create(commandArgs[0], executionCommand.workingDirectory, argv,
                environmentList.toArray(new String[0]), processId, rows, columns);
            if (fd < 0 || processId[0] <= 0) throw new IOException("Failed to create the pseudo terminal");

            PtySession session = new PtySession(service, id, fd, processId[0]);
            session.startThreads();
            Logger.logInfo(LOG_TAG, "Started session " + id + " with pid " + processId[0]);
            return session;
        }

        private void startThreads() {
            Thread reader = new Thread(() -> {
                byte[] buffer = new byte[16 * 1024];
                try (InputStream in = new FileInputStream(mPtyInDescriptor.getFileDescriptor())) {
                    int read;
                    while ((read = in.read(buffer)) > 0) onOutput(Arrays.copyOf(buffer, read));
                } catch (IOException ignored) {
                    // The process exited
                }
            }, "PtySession[" + mId + "] reader");
            reader.setDaemon(true);
            reader.start();

            Thread waiter = new Thread(() -> {
                int exitCode = PtyProcess.waitFor(mPid);
                // Let the reader deliver the last output first
                try { reader.join(2000); } catch (InterruptedException ignored) {}
                onExit(exitCode);
            }, "PtySession[" + mId + "] waiter");
            waiter.setDaemon(true);
            waiter.start();

            Thread reaper = new Thread(() -> {
                while (true) {
                    try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
                    synchronized (this) {
                        if (mSocket != null) continue;
                        if (System.currentTimeMillis() - mDetachedAt < DETACHED_TIMEOUT_MS) continue;
                    }
                    Logger.logInfo(LOG_TAG, "Ending session " + mId + ", it was not attached again");
                    kill();
                    remove();
                    return;
                }
            }, "PtySession[" + mId + "] reaper");
            reaper.setDaemon(true);
            reaper.start();
        }

        /** Connect to the app that asked for the session, replacing an earlier connection. */
        void attach(int port, @NonNull String token) {
            Thread connector = new Thread(() -> {
                Socket socket = connect(port, token);
                if (socket == null) return;
                OutputStream out;
                try {
                    out = socket.getOutputStream();
                } catch (IOException e) {
                    closeQuietly(socket);
                    return;
                }
                boolean reattached;
                synchronized (this) {
                    if (mSocket != null) closeQuietly(mSocket);
                    reattached = mReplayLength > 0 || mExitCode != null;
                    mSocket = socket;
                    mSocketOut = out;
                    try {
                        JSONObject hello = new JSONObject();
                        hello.put("protocol", PROTOCOL_VERSION);
                        hello.put("session", mId);
                        hello.put("reattached", reattached);
                        writeFrame(out, 'H', hello.toString().getBytes(StandardCharsets.UTF_8));
                        // The screen as it was, then what comes next
                        if (mReplayLength > 0) writeFrame(out, 'O', replay());
                        if (mExitCode != null) writeFrame(out, 'X', exitJson(mExitCode));
                    } catch (IOException | JSONException e) {
                        detach(socket);
                        return;
                    }
                }
                readInput(socket);
            }, "PtySession[" + mId + "] connection");
            connector.setDaemon(true);
            connector.start();
        }

        @Nullable
        private static Socket connect(int port, @NonNull String token) {
            Socket socket = new Socket();
            try {
                // 127.0.0.1, not getLoopbackAddress(), which is ::1 on Android
                socket.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port), 5000);
                socket.setTcpNoDelay(true);
                // The token goes first, so the app knows who connected
                socket.getOutputStream().write(("TERMINOW-PTY " + PROTOCOL_VERSION + " " + token + "\n").getBytes(StandardCharsets.UTF_8));
                return socket;
            } catch (IOException e) {
                Logger.logError(LOG_TAG, "Failed to connect to the app on port " + port + ": " + e.getMessage());
                closeQuietly(socket);
                return null;
            }
        }

        /** Tell the app that a session could not be started. */
        static void reportFailure(int port, @NonNull String token, @NonNull String id, @NonNull String error) {
            new Thread(() -> {
                Socket socket = connect(port, token);
                if (socket == null) return;
                try {
                    JSONObject hello = new JSONObject();
                    hello.put("protocol", PROTOCOL_VERSION);
                    hello.put("session", id);
                    hello.put("error", error);
                    writeFrame(socket.getOutputStream(), 'H', hello.toString().getBytes(StandardCharsets.UTF_8));
                } catch (IOException | JSONException ignored) {
                } finally {
                    closeQuietly(socket);
                }
            }).start();
        }

        private void readInput(@NonNull Socket socket) {
            try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
                while (true) {
                    int type = in.read();
                    if (type < 0) break;
                    int length = in.readInt();
                    if (length < 0 || length > MAX_FRAME) break;
                    byte[] payload = new byte[length];
                    in.readFully(payload);
                    switch (type) {
                        case 'I':
                            if (mExitCode == null) synchronized (mPtyOut) { mPtyOut.write(payload); }
                            break;
                        case 'R':
                            if (length == 4) {
                                int columns = clamp(((payload[0] & 0xff) << 8) | (payload[1] & 0xff), 10, 1000);
                                int rows = clamp(((payload[2] & 0xff) << 8) | (payload[3] & 0xff), 4, 1000);
                                PtyProcess.setWindowSize(mFd, rows, columns);
                            }
                            break;
                        case 'K':
                            kill();
                            break;
                        case 'D':
                            detach(socket);
                            return;
                        default:
                            break;
                    }
                }
            } catch (IOException ignored) {
                // The app closed the connection or went away
            }
            detach(socket);
        }

        private synchronized void detach(@NonNull Socket socket) {
            closeQuietly(socket);
            if (mSocket != socket) return;
            mSocket = null;
            mSocketOut = null;
            mDetachedAt = System.currentTimeMillis();
        }

        private synchronized void onOutput(@NonNull byte[] data) {
            appendReplay(data);
            if (mSocketOut == null) return;
            try {
                writeFrame(mSocketOut, 'O', data);
            } catch (IOException e) {
                detach(mSocket);
            }
        }

        private void onExit(int exitCode) {
            Logger.logInfo(LOG_TAG, "Session " + mId + " exited with " + exitCode);
            synchronized (this) {
                mExitCode = exitCode;
                if (mSocketOut != null) {
                    try {
                        writeFrame(mSocketOut, 'X', exitJson(exitCode));
                    } catch (IOException ignored) {}
                }
            }
            try { mPtyOut.close(); } catch (IOException ignored) {}
            PtyProcess.close(mFd);
            try { mPtyOutDescriptor.close(); } catch (IOException ignored) {}
            try { mPtyInDescriptor.close(); } catch (IOException ignored) {}
            // Kept for a while, so that an app that lost the connection still gets the exit code
            new Thread(() -> {
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
                synchronized (this) { if (mSocket != null) closeQuietly(mSocket); }
                remove();
            }).start();
        }

        private void remove() {
            mService.onSessionRemoved(this);
        }

        private void kill() {
            if (mExitCode == null) android.os.Process.sendSignal(mPid, 9);
        }

        private static byte[] exitJson(int exitCode) {
            return ("{\"code\":" + exitCode + "}").getBytes(StandardCharsets.UTF_8);
        }

        private void appendReplay(@NonNull byte[] data) {
            for (byte b : data) {
                int end = (mReplayStart + mReplayLength) % mReplay.length;
                mReplay[end] = b;
                if (mReplayLength < mReplay.length) mReplayLength++;
                else mReplayStart = (mReplayStart + 1) % mReplay.length;
            }
        }

        private byte[] replay() {
            byte[] result = new byte[mReplayLength];
            for (int i = 0; i < mReplayLength; i++) result[i] = mReplay[(mReplayStart + i) % mReplay.length];
            return result;
        }

        private static void writeFrame(@NonNull OutputStream out, char type, @NonNull byte[] payload) throws IOException {
            byte[] header = new byte[5];
            header[0] = (byte) type;
            header[1] = (byte) (payload.length >>> 24);
            header[2] = (byte) (payload.length >>> 16);
            header[3] = (byte) (payload.length >>> 8);
            header[4] = (byte) payload.length;
            synchronized (out) {
                out.write(header);
                out.write(payload);
                out.flush();
            }
        }

        private static void closeQuietly(@Nullable Socket socket) {
            if (socket == null) return;
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
