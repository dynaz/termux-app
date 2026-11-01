package com.termux.app.terminow.vnc;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One VNC connection, apart from any screen: its target, its lifecycle state, the desktop
 * {@link Bitmap}, and the read thread and socket that feed it. It drives {@link TerminowVncClient}
 * (a pure-Java RFB client) over a plain TCP socket. The connection lives as long as the app process
 * and the socket do, so rotating the phone or leaving the viewer does not drop it.
 *
 * <p>{@link #disconnect()} closes only this session's own socket and ends only its own thread — it
 * never touches SSH, SFTP or any other session. Each session owns exactly one {@link Socket}.</p>
 */
public final class TerminowVncSession {

    public enum State { CONNECTING, AUTHENTICATING, CONNECTED, DISCONNECTED, FAILED }

    /** Where and how to connect. The password is held only for the handshake, then cleared. */
    public static final class Target {
        public String title = "";
        public String hostKey = "";
        public String address = "";
        public int port = 5900;
        @Nullable public char[] password;
        public boolean viewOnly;
    }

    /** What a screen does for a connection while it is attached. */
    public interface Ui {
        void onStateChanged(@NonNull TerminowVncSession session);
        void onDesktop(@NonNull Bitmap bitmap);
        void onFrame(int x, int y, int width, int height);
        void onBell();
        void onCutText(@NonNull String text);
    }

    private static final Map<String, TerminowVncSession> SESSIONS = Collections.synchronizedMap(new LinkedHashMap<>());

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    public final String id = UUID.randomUUID().toString();
    public final Target target;
    public final long started = System.currentTimeMillis();

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile State mState = State.CONNECTING;
    private volatile String mError = "";
    private volatile long mLastUsed = System.currentTimeMillis();
    private volatile boolean mStopped;

    @Nullable private volatile Bitmap mBitmap;
    @Nullable private volatile Ui mUi;
    @Nullable private volatile Socket mSocket;
    @Nullable private volatile TerminowVncClient mClient;
    @Nullable private Thread mThread;

    private TerminowVncSession(@NonNull Target target) {
        this.target = target;
    }

    @NonNull
    public static TerminowVncSession start(@NonNull Target target) {
        TerminowVncSession session = new TerminowVncSession(target);
        SESSIONS.put(session.id, session);
        session.mThread = new Thread(session::run, "TerminowVnc-" + session.id.substring(0, 8));
        session.mThread.start();
        return session;
    }

    @Nullable
    public static TerminowVncSession get(@NonNull String id) {
        return SESSIONS.get(id);
    }

    /** Open connections, newest first. */
    @NonNull
    public static List<TerminowVncSession> open() {
        List<TerminowVncSession> out = new ArrayList<>();
        synchronized (SESSIONS) {
            for (TerminowVncSession session : SESSIONS.values()) if (session.isOpen()) out.add(0, session);
        }
        return out;
    }

    /** The most recent ended connection per host that has no open one — for a Reconnect entry. */
    @NonNull
    public static List<TerminowVncSession> ended() {
        List<TerminowVncSession> out = new ArrayList<>();
        synchronized (SESSIONS) {
            java.util.Set<String> hosts = new java.util.HashSet<>();
            for (TerminowVncSession session : SESSIONS.values()) if (session.isOpen()) hosts.add(session.target.hostKey);
            List<TerminowVncSession> all = new ArrayList<>(SESSIONS.values());
            Collections.reverse(all);
            for (TerminowVncSession session : all)
                if (!session.isOpen() && hosts.add(session.target.hostKey)) out.add(session);
        }
        return out;
    }

    @NonNull public State getState() { return mState; }
    @NonNull public String getError() { return mError; }
    public boolean isOpen() { return mState != State.DISCONNECTED && mState != State.FAILED; }
    public long getLastUsed() { return mLastUsed; }
    @Nullable public Bitmap getBitmap() { return mBitmap; }
    public boolean isViewOnly() { return target.viewOnly; }
    public int framebufferWidth() { TerminowVncClient c = mClient; return c == null ? 0 : c.width(); }
    public int framebufferHeight() { TerminowVncClient c = mClient; return c == null ? 0 : c.height(); }

    /** Show this connection on a screen, or {@code null} to detach. */
    public void attach(@Nullable Ui ui) {
        mUi = ui;
        if (ui != null) {
            mLastUsed = System.currentTimeMillis();
            Bitmap bitmap = mBitmap;
            if (bitmap != null) ui.onDesktop(bitmap);
            ui.onStateChanged(this);
        }
    }

    /** End this connection: close only its socket and let only its own thread finish. */
    public void disconnect() {
        mStopped = true;
        closeSocket();
        Thread thread = mThread;
        if (thread != null) thread.interrupt();
        if (isOpen()) setState(State.DISCONNECTED, "");
    }

    private void closeSocket() {
        Socket socket = mSocket;
        mSocket = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
    }

    // ---------------------------------------------------------------------------------------
    // Input, from the main thread

    public void pointer(int buttonMask, int x, int y) {
        TerminowVncClient client = mClient;
        if (client == null || mState != State.CONNECTED) return;
        try { client.pointer(buttonMask, x, y); } catch (IOException e) { fail("Lost connection: " + e.getMessage()); }
    }

    public void key(boolean down, int keysym) {
        TerminowVncClient client = mClient;
        if (client == null || mState != State.CONNECTED || keysym == 0) return;
        try { client.key(down, keysym); } catch (IOException e) { fail("Lost connection: " + e.getMessage()); }
    }

    /** Type text as a sequence of key press/release pairs. */
    public void text(@NonNull CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            int keysym = TerminowVncKeys.keysymForChar(text.charAt(i));
            if (keysym != 0) { key(true, keysym); key(false, keysym); }
        }
    }

    // ---------------------------------------------------------------------------------------

    private void fail(@NonNull String message) {
        if (mStopped) { setState(State.DISCONNECTED, ""); return; }
        setState(State.FAILED, message);
    }

    private void setState(@NonNull State state, @NonNull String error) {
        mState = state;
        if (!error.isEmpty()) mError = error;
        mMain.post(() -> {
            Ui ui = mUi;
            if (ui != null) ui.onStateChanged(this);
        });
    }

    private void run() {
        Socket socket = new Socket();
        mSocket = socket;
        try {
            setState(State.CONNECTING, "");
            socket.connect(new InetSocketAddress(target.address, target.port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            TerminowVncClient client = new TerminowVncClient(
                socket.getInputStream(), socket.getOutputStream(), target.password, target.viewOnly, new FrameSink());
            mClient = client;

            setState(State.AUTHENTICATING, "");
            client.handshakeAndInit();
            wipePassword();
            if (mStopped) { setState(State.DISCONNECTED, ""); return; }

            setState(State.CONNECTED, "");
            client.requestUpdate(false);                 // first frame: full
            while (!mStopped && !Thread.currentThread().isInterrupted()) {
                int type = client.pump();
                if (type == TerminowVncClient.MESSAGE_FRAMEBUFFER_UPDATE) client.requestUpdate(true);
            }
            setState(State.DISCONNECTED, "");
        } catch (TerminowVncClient.VncException e) {
            wipePassword();
            fail(e.getMessage() == null ? "VNC error" : e.getMessage());
        } catch (java.net.SocketTimeoutException e) {
            wipePassword();
            fail(mState == State.CONNECTING ? "Could not reach the server (timed out)" : "The connection timed out");
        } catch (IOException e) {
            wipePassword();
            if (mStopped) setState(State.DISCONNECTED, "");
            else fail(friendly(e));
        } finally {
            wipePassword();
            closeSocket();
            mClient = null;
        }
    }

    private void wipePassword() {
        char[] p = target.password;
        if (p != null) { java.util.Arrays.fill(p, '\0'); target.password = null; }
    }

    @NonNull
    private String friendly(@NonNull IOException e) {
        String message = e.getMessage();
        if (e instanceof java.net.ConnectException) return "Could not connect to " + target.address + ":" + target.port;
        if (e instanceof java.io.EOFException) return "The server closed the connection";
        return message == null ? e.getClass().getSimpleName() : message;
    }

    /** Receives framebuffer events on the read thread; writes the Bitmap and nudges the screen. */
    private final class FrameSink implements TerminowVncClient.Listener {
        @Override
        public void onFramebufferResized(int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
            bitmap.setHasAlpha(false);
            mBitmap = bitmap;
            mMain.post(() -> {
                Ui ui = mUi;
                if (ui != null) ui.onDesktop(bitmap);
            });
        }

        @Override
        public void onFramebufferUpdated(int x, int y, int width, int height) {
            Bitmap bitmap = mBitmap;
            TerminowVncClient client = mClient;
            if (bitmap == null || client == null || width <= 0 || height <= 0) return;
            int fbw = client.width();
            if (x < 0 || y < 0 || x + width > bitmap.getWidth() || y + height > bitmap.getHeight()) return;
            try {
                bitmap.setPixels(client.pixels(), y * fbw + x, fbw, x, y, width, height);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                return;   // a concurrent resize; the next full frame will repaint
            }
            mMain.post(() -> {
                Ui ui = mUi;
                if (ui != null) ui.onFrame(x, y, width, height);
            });
        }

        @Override
        public void onBell() {
            mMain.post(() -> { Ui ui = mUi; if (ui != null) ui.onBell(); });
        }

        @Override
        public void onCutText(@NonNull String text) {
            mMain.post(() -> { Ui ui = mUi; if (ui != null) ui.onCutText(text); });
        }
    }
}
