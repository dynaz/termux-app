package com.termux.app.terminow.rdp;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One RDP connection, apart from any screen: its target, its state, the desktop Bitmap, and the
 * SSH tunnel it goes through if it has one. It lives as long as the app process and the
 * connection do, so that rotating the phone or leaving the viewer does not end it; it can not
 * outlive the process, which Android may end in the background.
 *
 * Questions of the connection, a certificate or credentials, are asked through the {@link Ui}
 * that shows it. Without a screen they are refused: nothing is ever accepted silently.
 */
public final class TerminowRdpSession {

    public enum State { STARTING_TUNNEL, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, FAILED }

    /** Where and how to connect. The password stays in memory only, for the connection. */
    public static final class Target {
        public String title = "";
        /** The key of the saved host, for Recent sessions and to connect again. */
        public String hostKey = "";
        public String address = "";
        public int port = 3389;
        public String username = "";
        public String domain = "";
        public String password = "";
        public boolean nla = true;
        public boolean clipboard;
        /** The size of the desktop; 0 for the size of the screen of the phone. */
        public int width;
        public int height;
        /** The ssh command of the tunnel, without the -L of it, empty for a direct connection. */
        public String tunnelCommand = "";
        public String tunnelTitle = "";
    }

    /** What the screen of a connection does for it. */
    public interface Ui {
        void onStateChanged(@NonNull TerminowRdpSession session);

        void onDesktop(@NonNull Bitmap bitmap);

        void onFrame(int x, int y, int width, int height);

        /** On the main thread; {@code answer} gets 1, 2 or 0, see {@link TerminowRdpNative.Callbacks#onVerifyCertificate}. */
        void askCertificate(@NonNull Certificate certificate, @NonNull java.util.function.IntConsumer answer);

        /** On the main thread; {@code answer} gets username, domain, password, or {@code null}. */
        void askCredentials(@NonNull String username, @NonNull String domain, @NonNull java.util.function.Consumer<String[]> answer);

        void onRemoteClipboard(@NonNull String text);
    }

    /** A certificate to decide about, as FreeRDP gave it. */
    public static final class Certificate {
        public String host = "";
        public int port;
        public String commonName = "";
        public String subject = "";
        public String issuer = "";
        public String fingerprint = "";
        /** It is not the one that was accepted before for this host. */
        public boolean changed;
        public String oldSubject = "";
        public String oldIssuer = "";
        public String oldFingerprint = "";
        public int flags;
    }

    /** The sessions of this process, by id, for the viewer and Recent sessions. */
    private static final Map<String, TerminowRdpSession> SESSIONS = Collections.synchronizedMap(new LinkedHashMap<>());

    /** How long a question waits for a screen to answer it before it is refused. */
    private static final long ASK_TIMEOUT_MS = 5 * 60 * 1000;

    public final String id = UUID.randomUUID().toString();
    public final Target target;
    private final File mConfigDir;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile State mState = State.CONNECTING;
    private volatile String mError = "";
    private volatile long mHandle;
    @Nullable private volatile Bitmap mBitmap;
    @Nullable private volatile Ui mUi;
    @Nullable private Process mTunnel;
    private volatile boolean mStopped;
    public final long started = System.currentTimeMillis();
    private volatile long mLastUsed = System.currentTimeMillis();

    private TerminowRdpSession(@NonNull Context context, @NonNull Target target) {
        mContext = context.getApplicationContext();
        this.target = target;
        mConfigDir = new File(context.getFilesDir(), "rdp");
    }

    /** Start a connection; it says how it goes through the {@link Ui} that attaches to it. */
    @NonNull
    public static TerminowRdpSession start(@NonNull Context context, @NonNull Target target) {
        TerminowRdpSession session = new TerminowRdpSession(context, target);
        SESSIONS.put(session.id, session);
        new Thread(session::run, "TerminowRdpStart").start();
        return session;
    }

    @Nullable
    public static TerminowRdpSession get(@NonNull String id) {
        return SESSIONS.get(id);
    }

    /** The connections that are still open, newest first. */
    /**
     * Connections of this process that ended, newest first, one per saved host and none of a
     * host that has an open one: they are connected to again, never resumed.
     */
    @NonNull
    public static List<TerminowRdpSession> ended() {
        List<TerminowRdpSession> out = new ArrayList<>();
        java.util.Set<String> hosts = new java.util.HashSet<>();
        synchronized (SESSIONS) {
            for (TerminowRdpSession session : SESSIONS.values()) if (session.isOpen()) hosts.add(session.target.hostKey);
            List<TerminowRdpSession> all = new ArrayList<>(SESSIONS.values());
            java.util.Collections.reverse(all);
            for (TerminowRdpSession session : all)
                if (!session.isOpen() && hosts.add(session.target.hostKey)) out.add(session);
        }
        return out;
    }

    @NonNull
    public static List<TerminowRdpSession> open() {
        List<TerminowRdpSession> out = new ArrayList<>();
        synchronized (SESSIONS) {
            for (TerminowRdpSession session : SESSIONS.values()) if (session.isOpen()) out.add(0, session);
        }
        return out;
    }

    @NonNull
    public State getState() {
        return mState;
    }

    /** "NAME|text" of FreeRDP, or a message of TermiNow, for a failure. */
    @NonNull
    public String getError() {
        return mError;
    }

    public boolean isOpen() {
        return mState != State.DISCONNECTED && mState != State.FAILED;
    }

    public long getLastUsed() {
        return mLastUsed;
    }

    @Nullable
    public Bitmap getBitmap() {
        return mBitmap;
    }

    /** Show this connection on a screen; {@code null} when it is not shown. */
    public void attach(@Nullable Ui ui) {
        mUi = ui;
        if (ui != null) {
            mLastUsed = System.currentTimeMillis();
            Bitmap bitmap = mBitmap;
            if (bitmap != null) ui.onDesktop(bitmap);
            ui.onStateChanged(this);
        }
    }

    /** End the connection and its tunnel. */
    public void disconnect() {
        mStopped = true;
        long handle = mHandle;
        if (handle != 0) TerminowRdpNative.nativeStop(handle);
        stopTunnel();
        if (handle == 0) setState(State.DISCONNECTED, "");
    }

    // ---------------------------------------------------------------------------------------
    // Input, from the main thread

    public void sendMouse(int flags, int x, int y) {
        long handle = mHandle;
        if (handle != 0 && mState == State.CONNECTED) TerminowRdpNative.nativeSendMouse(handle, flags, x, y);
    }

    public void sendKey(int virtualKey, boolean down) {
        long handle = mHandle;
        if (handle != 0 && mState == State.CONNECTED && virtualKey != 0) TerminowRdpNative.nativeSendKey(handle, virtualKey, down);
    }

    /** Text, as Unicode key presses: UTF-16 code units, like RDP takes them. */
    public void sendText(@NonNull CharSequence text) {
        long handle = mHandle;
        if (handle == 0 || mState != State.CONNECTED) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                sendKey(TerminowRdpKeys.VK_RETURN, true);
                sendKey(TerminowRdpKeys.VK_RETURN, false);
                continue;
            }
            TerminowRdpNative.nativeSendUnicode(handle, c, true);
            TerminowRdpNative.nativeSendUnicode(handle, c, false);
        }
    }

    /** Offer text of the phone to Windows; only when the clipboard of the host is shared. */
    public void setClipboard(@NonNull String text) {
        long handle = mHandle;
        if (handle != 0 && target.clipboard) TerminowRdpNative.nativeSetClipboard(handle, text);
    }

    // ---------------------------------------------------------------------------------------

    private void setState(@NonNull State state, @NonNull String error) {
        mState = state;
        if (!error.isEmpty() || state == State.CONNECTED) mError = error;
        mMain.post(() -> {
            Ui ui = mUi;
            if (ui != null) ui.onStateChanged(this);
        });
    }

    private void run() {
        if (!TerminowRdpNative.isAvailable()) {
            setState(State.FAILED, "NO_ENGINE|" + TerminowRdpNative.loadError());
            return;
        }
        String address = target.address;
        int port = target.port;
        if (!target.tunnelCommand.isEmpty()) {
            setState(State.STARTING_TUNNEL, "");
            try {
                port = startTunnel();
                address = "127.0.0.1";
            } catch (IOException e) {
                setState(State.FAILED, "TUNNEL|" + e.getMessage());
                return;
            }
            if (mStopped) {
                stopTunnel();
                setState(State.DISCONNECTED, "");
                return;
            }
        }
        if (!mConfigDir.isDirectory() && !mConfigDir.mkdirs()) {
            setState(State.FAILED, "CONFIG|" + mConfigDir);
            return;
        }
        useSystemCertificates(mConfigDir);
        long handle = TerminowRdpNative.nativeCreate(new Callbacks());
        if (handle == 0) {
            setState(State.FAILED, "CREATE|");
            return;
        }
        int width = Math.max(640, Math.min(target.width, 8192));
        int height = Math.max(480, Math.min(target.height, 8192));
        // The engine copies what it needs: the password is not kept in this object beyond the start
        boolean configured = TerminowRdpNative.nativeConfigure(handle, address, port, target.username, target.domain, target.password,
            width & ~1, height, target.nla, target.clipboard, mConfigDir.getAbsolutePath());
        target.password = "";
        if (!configured || !TerminowRdpNative.nativeStart(handle)) {
            TerminowRdpNative.nativeFree(handle);
            stopTunnel();
            setState(State.FAILED, "CONFIGURE|");
            return;
        }
        mHandle = handle;
        if (mStopped) TerminowRdpNative.nativeStop(handle);
    }

    private static boolean sCertificatesSet;

    /**
     * The CAs that Android trusts, for the engine to verify certificates with, like a browser:
     * OpenSSL can not read the folder of Android, whose files are named by the old hash of
     * OpenSSL, so they are written to one PEM file once a process. Only the CAs of the system,
     * not ones the user added, as for any app. Without them every certificate asks the user.
     */
    private static synchronized void useSystemCertificates(@NonNull File configDir) {
        if (sCertificatesSet) return;
        File file = new File(configDir, "cacerts.pem");
        File temporary = new File(configDir, "cacerts.pem.tmp");
        try {
            java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidCAStore");
            store.load(null, null);
            StringBuilder pem = new StringBuilder();
            for (String alias : Collections.list(store.aliases())) {
                if (!alias.startsWith("system:")) continue;
                java.security.cert.Certificate certificate = store.getCertificate(alias);
                if (certificate == null) continue;
                pem.append("-----BEGIN CERTIFICATE-----\n")
                    .append(android.util.Base64.encodeToString(certificate.getEncoded(), android.util.Base64.DEFAULT))
                    .append("-----END CERTIFICATE-----\n");
            }
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(temporary)) {
                out.write(pem.toString().getBytes(StandardCharsets.US_ASCII));
            }
            if (!temporary.renameTo(file)) throw new IOException("rename " + temporary);
            android.system.Os.setenv("SSL_CERT_FILE", file.getAbsolutePath(), true);
            sCertificatesSet = true;
        } catch (Exception e) {
            // Not fatal: certificates are then all shown to the user to accept or refuse
            android.util.Log.w("TerminowRdp", "System certificates not available: " + e);
        }
    }

    /** The callbacks of the engine, on its client thread. */
    private final class Callbacks implements TerminowRdpNative.Callbacks {
        @Nullable
        @Override
        public Bitmap onResize(int width, int height) {
            Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
            // Windows sends no alpha: drawn opaque
            bitmap.setHasAlpha(false);
            mBitmap = bitmap;
            mMain.post(() -> {
                Ui ui = mUi;
                if (ui != null) ui.onDesktop(bitmap);
            });
            return bitmap;
        }

        @Override
        public void onUpdate(int x, int y, int width, int height) {
            Ui ui = mUi;
            if (ui != null) mMain.post(() -> {
                Ui current = mUi;
                if (current != null) current.onFrame(x, y, width, height);
            });
        }

        @Override
        public void onState(int state, @NonNull String message) {
            switch (state) {
                case TerminowRdpNative.STATE_CONNECTING: setState(State.CONNECTING, ""); break;
                case TerminowRdpNative.STATE_CONNECTED: setState(State.CONNECTED, ""); break;
                case TerminowRdpNative.STATE_RECONNECTING: setState(State.RECONNECTING, ""); break;
                default:
                    stopTunnel();
                    setState(state == TerminowRdpNative.STATE_FAILED && !mStopped ? State.FAILED : State.DISCONNECTED, message);
                    // The engine is done with this connection: freed off its own thread
                    long handle = mHandle;
                    mHandle = 0;
                    if (handle != 0) new Thread(() -> TerminowRdpNative.nativeFree(handle), "TerminowRdpFree").start();
                    break;
            }
        }

        @Override
        public int onVerifyCertificate(@NonNull String host, int port, @NonNull String commonName, @NonNull String subject,
                                       @NonNull String issuer, @NonNull String fingerprint, boolean changed, @NonNull String oldSubject,
                                       @NonNull String oldIssuer, @NonNull String oldFingerprint, int flags) {
            Certificate certificate = new Certificate();
            certificate.host = host;
            certificate.port = port;
            certificate.commonName = commonName;
            certificate.subject = subject;
            certificate.issuer = issuer;
            certificate.fingerprint = fingerprint;
            certificate.changed = changed;
            certificate.oldSubject = oldSubject;
            certificate.oldIssuer = oldIssuer;
            certificate.oldFingerprint = oldFingerprint;
            certificate.flags = flags;
            AtomicReference<Integer> answer = new AtomicReference<>(0);
            CountDownLatch done = new CountDownLatch(1);
            mMain.post(() -> {
                Ui ui = mUi;
                if (ui == null) {
                    done.countDown();
                    return;
                }
                ui.askCertificate(certificate, value -> {
                    answer.set(value == 1 || value == 2 ? value : 0);
                    done.countDown();
                });
            });
            try {
                if (!done.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return 0;
            } catch (InterruptedException e) {
                return 0;
            }
            return answer.get();
        }

        @Nullable
        @Override
        public String[] onAuthenticate(@NonNull String username, @NonNull String domain, int reason) {
            AtomicReference<String[]> answer = new AtomicReference<>(null);
            CountDownLatch done = new CountDownLatch(1);
            mMain.post(() -> {
                Ui ui = mUi;
                if (ui == null) {
                    done.countDown();
                    return;
                }
                ui.askCredentials(username, domain, value -> {
                    answer.set(value);
                    done.countDown();
                });
            });
            try {
                if (!done.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
            } catch (InterruptedException e) {
                return null;
            }
            return answer.get();
        }

        @Override
        public void onRemoteClipboard(@NonNull String text) {
            if (!target.clipboard) return;
            mMain.post(() -> {
                Ui ui = mUi;
                if (ui != null) ui.onRemoteClipboard(text);
            });
        }
    }

    // ---------------------------------------------------------------------------------------
    // The SSH tunnel: it starts before the connection and ends with it

    /** A free port of the phone, for the tunnel only: it listens on 127.0.0.1. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private int startTunnel() throws IOException {
        int port = freePort();
        // The command is made of validated host fields by TerminowStore; ssh may not ask anything
        String command = target.tunnelCommand.replace("ssh -N ", "exec ssh -N -o BatchMode=yes -o ExitOnForwardFailure=yes -L 127.0.0.1:" +
            port + ":" + target.address + ":" + target.port + " ");
        Process process = com.termux.app.terminow.TerminowRdpBridge.startInTerminal(mContext, command);
        synchronized (this) {
            mTunnel = process;
        }
        StringBuilder errors = new StringBuilder();
        Thread reader = new Thread(() -> drain(process.getErrorStream(), errors), "TerminowRdpTunnel");
        reader.setDaemon(true);
        reader.start();
        long deadline = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < deadline && !mStopped) {
            if (!process.isAlive()) {
                String text;
                synchronized (errors) {
                    text = errors.toString().trim();
                }
                throw new IOException(text.isEmpty() ? "ssh ended" : text.substring(Math.max(0, text.length() - 400)));
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return port;
            } catch (IOException e) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
        stopTunnel();
        throw new IOException("The SSH tunnel did not open in 20 seconds");
    }

    private static void drain(@NonNull InputStream stream, @NonNull StringBuilder out) {
        byte[] buffer = new byte[4096];
        try (InputStream in = stream) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                synchronized (out) {
                    out.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    if (out.length() > 8000) out.delete(0, out.length() - 4000);
                }
            }
        } catch (IOException e) {
            // ssh ended
        }
    }

    private synchronized void stopTunnel() {
        if (mTunnel != null) {
            mTunnel.destroy();
            mTunnel = null;
        }
    }
}
