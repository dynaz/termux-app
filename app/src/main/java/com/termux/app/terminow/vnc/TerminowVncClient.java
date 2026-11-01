package com.termux.app.terminow.vnc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * A small, dependency-free RFB (VNC) protocol client — the Remote Framebuffer protocol of RFC 6143.
 * It is written in plain Java (no native library, no GPL viewer embedded) so it compiles for every
 * Android ABI and is unit-testable against a fake server over ordinary streams: the constructor takes
 * an {@link InputStream}/{@link OutputStream} (a real socket in the app, piped streams in tests).
 *
 * <p>Implemented: ProtocolVersion 3.3/3.7/3.8, security None and VNC authentication (DES with the
 * bit-reversed password as the key), ClientInit/ServerInit, a fixed 32-bpp true-colour pixel format,
 * SetEncodings, and the Raw, CopyRect and DesktopSize(-223) framebuffer encodings, plus Bell and
 * ServerCutText. Pointer and key events are sent back unless the connection is view-only. Colour-map
 * (non-true-colour) servers and compressed encodings (Hextile/Tight/ZRLE) are a documented follow-up;
 * every server supports Raw, which is requested first, so the screen always renders.</p>
 */
public final class TerminowVncClient {

    /** Receives framebuffer and side-channel events on the client's read thread. */
    public interface Listener {
        void onFramebufferResized(int width, int height);
        void onFramebufferUpdated(int x, int y, int width, int height);
        void onBell();
        void onCutText(@NonNull String text);
    }

    /** A protocol or authentication failure with a message safe to show (never the password). */
    public static final class VncException extends IOException {
        public VncException(@NonNull String message) { super(message); }
    }

    // Server→client message types
    private static final int MSG_FRAMEBUFFER_UPDATE = 0;
    private static final int MSG_SET_COLOUR_MAP = 1;
    private static final int MSG_BELL = 2;
    private static final int MSG_SERVER_CUT_TEXT = 3;

    // Encodings
    private static final int ENC_RAW = 0;
    private static final int ENC_COPY_RECT = 1;
    private static final int ENC_DESKTOP_SIZE = -223;

    private final DataInputStream mIn;
    private final DataOutputStream mOut;
    private final char[] mPassword;
    private final boolean mViewOnly;
    private final Listener mListener;

    private int mWidth;
    private int mHeight;
    private int[] mPixels = new int[0];   // ARGB, row-major, mWidth*mHeight
    private String mName = "";
    private int mProtocol;                // e.g. 0x3008 for 3.8

    public TerminowVncClient(@NonNull InputStream in, @NonNull OutputStream out,
                             @Nullable char[] password, boolean viewOnly, @NonNull Listener listener) {
        mIn = new DataInputStream(in);
        mOut = new DataOutputStream(out);
        mPassword = password;
        mViewOnly = viewOnly;
        mListener = listener;
    }

    public int width() { return mWidth; }
    public int height() { return mHeight; }
    @NonNull public int[] pixels() { return mPixels; }
    @NonNull public String desktopName() { return mName; }

    // ---------------------------------------------------------------------------------------
    // Handshake

    /** ProtocolVersion → security → authentication → ClientInit/ServerInit → pixel format/encodings. */
    public void handshakeAndInit() throws IOException {
        mProtocol = readProtocolVersion();
        writeProtocolVersion(mProtocol);

        int security = negotiateSecurity();
        if (security == 2) authenticateVnc();
        else if (security != 1) throw new VncException("The server asked for an unsupported security type (" + security + ")");

        readSecurityResult(security);

        mOut.writeByte(1);   // ClientInit: shared = 1 (do not disconnect other viewers)
        mOut.flush();
        readServerInit();

        writeSetPixelFormat();
        writeSetEncodings(new int[]{ENC_COPY_RECT, ENC_RAW, ENC_DESKTOP_SIZE});
    }

    private int readProtocolVersion() throws IOException {
        byte[] b = new byte[12];
        mIn.readFully(b);
        String s = new String(b, StandardCharsets.US_ASCII);   // "RFB 003.008\n"
        if (!s.startsWith("RFB 003.")) throw new VncException("Not a VNC server (bad protocol greeting)");
        try {
            int minor = Integer.parseInt(s.substring(8, 11));
            return 0x3000 + minor;
        } catch (NumberFormatException e) {
            throw new VncException("The server sent an unreadable protocol version");
        }
    }

    private void writeProtocolVersion(int serverProtocol) throws IOException {
        int minor = (serverProtocol >= 0x3008) ? 8 : (serverProtocol >= 0x3007) ? 7 : 3;
        mProtocol = 0x3000 + minor;
        mOut.write(("RFB 003.00" + minor + "\n").getBytes(StandardCharsets.US_ASCII));
        mOut.flush();
    }

    private int negotiateSecurity() throws IOException {
        if (mProtocol >= 0x3007) {
            int count = mIn.readUnsignedByte();
            if (count == 0) throw new VncException(failureReason("The server refused the connection"));
            int[] types = new int[count];
            for (int i = 0; i < count; i++) types[i] = mIn.readUnsignedByte();
            int chosen = -1;
            for (int t : types) if (t == 2) { chosen = 2; break; }      // prefer VNC auth
            if (chosen == -1) for (int t : types) if (t == 1) { chosen = 1; break; }   // then None
            if (chosen == -1) throw new VncException("The server offered no security type TermiNow supports");
            mOut.writeByte(chosen);
            mOut.flush();
            return chosen;
        } else {
            long type = readUInt32();   // 3.3: the server dictates a single type
            return (int) type;
        }
    }

    private void authenticateVnc() throws IOException {
        byte[] challenge = new byte[16];
        mIn.readFully(challenge);
        mOut.write(encodeDesResponse(challenge, mPassword));
        mOut.flush();
    }

    private void readSecurityResult(int security) throws IOException {
        // 3.3 with None has no SecurityResult; 3.7/3.8 always do; 3.3 with VNC auth does.
        boolean present = mProtocol >= 0x3007 || security == 2;
        if (!present) return;
        long result = readUInt32();
        if (result != 0) {
            String reason = mProtocol >= 0x3008 ? readString() : "";
            if (security == 2) throw new VncException(reason.isEmpty() ? "Wrong VNC password" : reason);
            throw new VncException(reason.isEmpty() ? "The server rejected the connection" : reason);
        }
    }

    private String failureReason(@NonNull String fallback) throws IOException {
        if (mProtocol >= 0x3008) {
            String reason = readString();
            if (!reason.isEmpty()) return reason;
        }
        return fallback;
    }

    private void readServerInit() throws IOException {
        mWidth = mIn.readUnsignedShort();
        mHeight = mIn.readUnsignedShort();
        byte[] pixelFormat = new byte[16];
        mIn.readFully(pixelFormat);   // the server's format; we override it with SetPixelFormat
        mName = readString();
        if (mWidth <= 0 || mHeight <= 0 || (long) mWidth * mHeight > 50_000_000L)
            throw new VncException("The server reported an unreasonable screen size " + mWidth + "x" + mHeight);
        mPixels = new int[mWidth * mHeight];
        mListener.onFramebufferResized(mWidth, mHeight);
    }

    /** Ask the server for 32-bpp little-endian true colour laid out as 0x00RRGGBB (Android-friendly). */
    private void writeSetPixelFormat() throws IOException {
        mOut.writeByte(0);                 // message type
        mOut.write(new byte[3]);           // padding
        mOut.writeByte(32);                // bits-per-pixel
        mOut.writeByte(24);                // depth
        mOut.writeByte(0);                 // big-endian flag (0 = little-endian)
        mOut.writeByte(1);                 // true-colour flag
        mOut.writeShort(255);              // red-max
        mOut.writeShort(255);              // green-max
        mOut.writeShort(255);              // blue-max
        mOut.writeByte(16);                // red-shift
        mOut.writeByte(8);                 // green-shift
        mOut.writeByte(0);                 // blue-shift
        mOut.write(new byte[3]);           // padding
        mOut.flush();
    }

    private void writeSetEncodings(@NonNull int[] encodings) throws IOException {
        mOut.writeByte(2);                 // message type
        mOut.writeByte(0);                 // padding
        mOut.writeShort(encodings.length);
        for (int e : encodings) mOut.writeInt(e);
        mOut.flush();
    }

    // ---------------------------------------------------------------------------------------
    // Framebuffer

    public void requestUpdate(boolean incremental) throws IOException {
        requestUpdate(incremental, 0, 0, mWidth, mHeight);
    }

    public void requestUpdate(boolean incremental, int x, int y, int w, int h) throws IOException {
        mOut.writeByte(3);                 // FramebufferUpdateRequest
        mOut.writeByte(incremental ? 1 : 0);
        mOut.writeShort(x);
        mOut.writeShort(y);
        mOut.writeShort(w);
        mOut.writeShort(h);
        mOut.flush();
    }

    /**
     * Read and dispatch exactly one server message; returns its type so the caller can, for
     * example, re-request an incremental update after each {@code MSG_FRAMEBUFFER_UPDATE}. Call in
     * a loop on the read thread.
     */
    public int pump() throws IOException {
        int type = mIn.read();
        if (type < 0) throw new EOFException("The server closed the connection");
        switch (type) {
            case MSG_FRAMEBUFFER_UPDATE: readFramebufferUpdate(); break;
            case MSG_SET_COLOUR_MAP: skipColourMap(); break;
            case MSG_BELL: mListener.onBell(); break;
            case MSG_SERVER_CUT_TEXT: readServerCutText(); break;
            default: throw new VncException("The server sent an unknown message type " + type);
        }
        return type;
    }

    /** Message type {@link #pump()} returns for a framebuffer update. */
    public static final int MESSAGE_FRAMEBUFFER_UPDATE = MSG_FRAMEBUFFER_UPDATE;

    private void readFramebufferUpdate() throws IOException {
        mIn.readUnsignedByte();            // padding
        int rects = mIn.readUnsignedShort();
        for (int i = 0; i < rects; i++) {
            int x = mIn.readUnsignedShort();
            int y = mIn.readUnsignedShort();
            int w = mIn.readUnsignedShort();
            int h = mIn.readUnsignedShort();
            int encoding = mIn.readInt();
            switch (encoding) {
                case ENC_RAW: readRaw(x, y, w, h); break;
                case ENC_COPY_RECT: readCopyRect(x, y, w, h); break;
                case ENC_DESKTOP_SIZE: resizeFramebuffer(w, h); break;
                default: throw new VncException("The server used an encoding TermiNow has not implemented (" + encoding + ")");
            }
            if (encoding != ENC_DESKTOP_SIZE) mListener.onFramebufferUpdated(x, y, w, h);
        }
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        if (w == 0 || h == 0) return;
        byte[] row = new byte[w * 4];
        for (int ry = 0; ry < h; ry++) {
            mIn.readFully(row);
            int dest = (y + ry) * mWidth + x;
            for (int rx = 0; rx < w; rx++) {
                int o = rx * 4;
                // little-endian 0x00RRGGBB -> opaque ARGB
                int blue = row[o] & 0xff, green = row[o + 1] & 0xff, red = row[o + 2] & 0xff;
                if (inBounds(x + rx, y + ry)) mPixels[dest + rx] = 0xFF000000 | (red << 16) | (green << 8) | blue;
            }
        }
    }

    private void readCopyRect(int x, int y, int w, int h) throws IOException {
        int srcX = mIn.readUnsignedShort();
        int srcY = mIn.readUnsignedShort();
        // Copy top-to-bottom or bottom-to-top so an overlapping move does not smear.
        int[] copy = new int[w * h];
        for (int ry = 0; ry < h; ry++)
            for (int rx = 0; rx < w; rx++)
                copy[ry * w + rx] = inBounds(srcX + rx, srcY + ry) ? mPixels[(srcY + ry) * mWidth + (srcX + rx)] : 0xFF000000;
        for (int ry = 0; ry < h; ry++)
            for (int rx = 0; rx < w; rx++)
                if (inBounds(x + rx, y + ry)) mPixels[(y + ry) * mWidth + (x + rx)] = copy[ry * w + rx];
    }

    private void resizeFramebuffer(int w, int h) {
        if (w <= 0 || h <= 0 || (long) w * h > 50_000_000L) return;
        mWidth = w;
        mHeight = h;
        mPixels = new int[w * h];
        mListener.onFramebufferResized(w, h);
    }

    private boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < mWidth && y < mHeight;
    }

    private void skipColourMap() throws IOException {
        mIn.readUnsignedByte();            // padding
        mIn.readUnsignedShort();           // first colour
        int count = mIn.readUnsignedShort();
        mIn.skipBytes(count * 6);          // RGB entries we ignore (we are true-colour)
    }

    private void readServerCutText() throws IOException {
        mIn.skipBytes(3);                  // padding
        int length = (int) readUInt32();
        if (length < 0 || length > (4 << 20)) { mIn.skipBytes(Math.max(0, length)); return; }
        byte[] text = new byte[length];
        mIn.readFully(text);
        mListener.onCutText(new String(text, StandardCharsets.ISO_8859_1));
    }

    // ---------------------------------------------------------------------------------------
    // Input (suppressed entirely when view-only)

    /** Send a pointer event. {@code buttonMask} bit 0 = left, 1 = middle, 2 = right, 3/4 = wheel. */
    public void pointer(int buttonMask, int x, int y) throws IOException {
        if (mViewOnly) return;
        mOut.writeByte(5);
        mOut.writeByte(buttonMask & 0xff);
        mOut.writeShort(clampX(x));
        mOut.writeShort(clampY(y));
        mOut.flush();
    }

    /** Send a key event with an X11 keysym (see {@link TerminowVncKeys}). */
    public void key(boolean down, int keysym) throws IOException {
        if (mViewOnly) return;
        mOut.writeByte(4);
        mOut.writeByte(down ? 1 : 0);
        mOut.writeShort(0);                // padding
        mOut.writeInt(keysym);
        mOut.flush();
    }

    public boolean isViewOnly() { return mViewOnly; }

    private int clampX(int x) { return x < 0 ? 0 : Math.min(x, Math.max(0, mWidth - 1)); }
    private int clampY(int y) { return y < 0 ? 0 : Math.min(y, Math.max(0, mHeight - 1)); }

    // ---------------------------------------------------------------------------------------
    // Helpers

    private long readUInt32() throws IOException {
        return ((long) mIn.readInt()) & 0xffffffffL;
    }

    private String readString() throws IOException {
        int length = (int) readUInt32();
        if (length <= 0 || length > (1 << 20)) return "";
        byte[] b = new byte[length];
        mIn.readFully(b);
        return new String(b, StandardCharsets.ISO_8859_1);
    }

    /**
     * The VNC authentication response: DES-encrypt the 16-byte challenge in two ECB blocks with the
     * password (up to 8 bytes, zero-padded) as the key, where each key byte has its bits reversed —
     * the long-standing quirk every RFB server expects.
     */
    @NonNull
    public static byte[] encodeDesResponse(@NonNull byte[] challenge, @Nullable char[] password) throws VncException {
        byte[] key = new byte[8];
        if (password != null)
            for (int i = 0; i < 8 && i < password.length; i++) key[i] = reverseBits((byte) password[i]);
        try {
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            byte[] response = new byte[16];
            cipher.doFinal(challenge, 0, 16, response, 0);
            return response;
        } catch (java.security.GeneralSecurityException e) {
            throw new VncException("This device cannot compute the VNC authentication response");
        }
    }

    /** Reverse the 8 bits of a byte (0b00000001 -> 0b10000000), as VNC's DES key schedule expects. */
    public static byte reverseBits(byte b) {
        int v = b & 0xff;
        int r = 0;
        for (int i = 0; i < 8; i++) { r = (r << 1) | (v & 1); v >>= 1; }
        return (byte) r;
    }
}
