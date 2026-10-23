package com.termux.app.terminow.rdp;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The RDP engine: libterminow-rdp.so, FreeRDP behind a small JNI layer (native/rdp). Nothing of
 * the UI or of the hosts is here: a {@link Callbacks} gets what the connection needs and does.
 *
 * The callbacks run on the client thread of the connection, which waits for their answer:
 * a certificate or missing credentials are asked to the user before the connection goes on.
 */
public final class TerminowRdpNative {

    /** What a connection tells, from its client thread. */
    public interface Callbacks {
        /** The desktop has this size now: a Bitmap of it, ARGB_8888, that the desktop is copied into. */
        @Nullable Bitmap onResize(int width, int height);

        /** This part of the Bitmap changed. */
        void onUpdate(int x, int y, int width, int height);

        /** One of the {@code STATE_} values, with "NAME|text" of the last error for a failure. */
        void onState(int state, @NonNull String message);

        /**
         * A certificate that could not be verified, or that changed since it was accepted.
         *
         * @return 1 to accept it and remember it, 2 to accept it for this connection only, 0 to refuse.
         */
        int onVerifyCertificate(@NonNull String host, int port, @NonNull String commonName, @NonNull String subject,
                                @NonNull String issuer, @NonNull String fingerprint, boolean changed, @NonNull String oldSubject,
                                @NonNull String oldIssuer, @NonNull String oldFingerprint, int flags);

        /** Credentials are needed: username, domain and password, or {@code null} to cancel. */
        @Nullable String[] onAuthenticate(@NonNull String username, @NonNull String domain, int reason);

        /** Text that was copied on Windows, when the clipboard is shared. */
        void onRemoteClipboard(@NonNull String text);
    }

    public static final int STATE_CONNECTING = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_RECONNECTING = 2;
    public static final int STATE_DISCONNECTED = 3;
    public static final int STATE_FAILED = 4;

    /** Mouse flags of RDP, MS-RDPBCGR 2.2.8.1.1.3.1.1.3. */
    public static final int PTR_FLAGS_HWHEEL = 0x0400;
    public static final int PTR_FLAGS_WHEEL = 0x0200;
    public static final int PTR_FLAGS_WHEEL_NEGATIVE = 0x0100;
    public static final int PTR_FLAGS_MOVE = 0x0800;
    public static final int PTR_FLAGS_DOWN = 0x8000;
    public static final int PTR_FLAGS_BUTTON1 = 0x1000;
    public static final int PTR_FLAGS_BUTTON2 = 0x2000;
    public static final int PTR_FLAGS_BUTTON3 = 0x4000;

    @Nullable private static final String LOAD_ERROR;

    static {
        String error = null;
        try {
            System.loadLibrary("terminow-rdp");
        } catch (UnsatisfiedLinkError e) {
            error = String.valueOf(e.getMessage());
        }
        LOAD_ERROR = error;
    }

    private TerminowRdpNative() {}

    /** Whether this build has the RDP engine; a build without native/rdp/build.sh has not. */
    public static boolean isAvailable() {
        return LOAD_ERROR == null;
    }

    @Nullable
    public static String loadError() {
        return LOAD_ERROR;
    }

    /** The version of FreeRDP, empty without the engine. */
    @NonNull
    public static String version() {
        return isAvailable() ? nativeVersion() : "";
    }

    static native long nativeCreate(@NonNull Callbacks callbacks);

    static native boolean nativeConfigure(long handle, @NonNull String host, int port, @NonNull String username, @NonNull String domain,
                                          @NonNull String password, int width, int height, boolean nla, boolean clipboard,
                                          @NonNull String configDir);

    static native boolean nativeStart(long handle);

    static native void nativeStop(long handle);

    static native void nativeFree(long handle);

    static native void nativeSendMouse(long handle, int flags, int x, int y);

    /** A key by its Windows virtual key code, see {@link TerminowRdpKeys}. */
    static native void nativeSendKey(long handle, int virtualKey, boolean down);

    static native void nativeSendUnicode(long handle, int codeUnit, boolean down);

    static native void nativeSetClipboard(long handle, @NonNull String text);

    static native String nativeVersion();
}
