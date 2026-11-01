package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Optional password encryption for TermiNow backups. A backup is encrypted at the raw-file level —
 * the whole {@code gzip(manifest + tar)} stream is the plaintext — so an encrypted backup is useless
 * without the password, and inspecting or restoring one needs the password first.
 *
 * <p>Format: a fixed header {@code MAGIC(8) VERSION(1) ITER(4, BE) SALT(16) BASE_NONCE(8)} followed
 * by length-framed chunks. The key is PBKDF2-HMAC-SHA256 of the password (hand-rolled over
 * {@link Mac}, since {@code SecretKeyFactory("PBKDF2WithHmacSHA256")} is API 26+ and minSdk is 24).
 * Each chunk is AES-256-GCM: on disk {@code cipherLen(4, BE) flag(1) ciphertext(cipherLen incl. 16-byte
 * tag)}, with nonce {@code BASE_NONCE(8) || counter(4, BE)} and AAD {@code VERSION || counter(4) ||
 * flag}. The last chunk (possibly empty) has {@code flag=1}; a stream that ends before a flagged
 * chunk is a truncation and fails. A wrong password derives a wrong key, so the first chunk's GCM
 * tag fails to verify and {@link BadPasswordException} is thrown.</p>
 *
 * <p>No secret is logged. Password {@code char[]}s are converted to bytes only to derive the key and
 * the working buffers are cleared. Pure JCE (AES/GCM/NoPadding, HmacSHA256), so it is unit-tested on
 * the JVM.</p>
 */
final class TerminowBackupCrypto {

    static final byte[] MAGIC = {'T', 'N', 'B', 'K', 'E', 'N', 'C', 0x01};
    static final int VERSION = 1;
    static final int SALT_LEN = 16;
    static final int BASE_NONCE_LEN = 8;
    static final int TAG_BITS = 128;
    static final int TAG_LEN = TAG_BITS / 8;
    static final int KEY_BITS = 256;
    /** PBKDF2 iterations. High enough for a backup password; stored in the header so it can change. */
    static final int ITERATIONS = 210_000;
    /** Plaintext bytes per chunk. The last chunk is 0..CHUNK and flagged final. */
    static final int CHUNK = 64 * 1024;
    static final int HEADER_LEN = MAGIC.length + 1 + 4 + SALT_LEN + BASE_NONCE_LEN;

    private TerminowBackupCrypto() {}

    /** Thrown when the password is wrong (the first chunk fails to authenticate) or the data was tampered with. */
    static final class BadPasswordException extends IOException {
        BadPasswordException(@NonNull String message) { super(message); }
    }

    /** Whether the bytes begin with the encrypted-backup magic. {@code header} may be shorter than the magic. */
    static boolean looksEncrypted(@NonNull byte[] header) {
        if (header.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) if (header[i] != MAGIC[i]) return false;
        return true;
    }

    /**
     * Wrap {@code sink} so that everything written is encrypted with {@code password}. The header is
     * written immediately. Closing the returned stream flushes the final chunk and closes {@code sink}.
     */
    @NonNull
    static OutputStream encryptingStream(@NonNull OutputStream sink, @NonNull char[] password) throws IOException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LEN];
        byte[] baseNonce = new byte[BASE_NONCE_LEN];
        random.nextBytes(salt);
        random.nextBytes(baseNonce);
        byte[] key = deriveKey(password, salt, ITERATIONS);
        sink.write(MAGIC);
        sink.write(VERSION);
        sink.write(intToBytes(ITERATIONS));
        sink.write(salt);
        sink.write(baseNonce);
        return new EncryptingOutputStream(sink, key, baseNonce);
    }

    /**
     * Wrap {@code source} so that reading it decrypts a stream written by {@link #encryptingStream}.
     * Reads and checks the header, deriving the key. The first {@code read} that reaches a chunk with
     * a wrong key throws {@link BadPasswordException}.
     */
    @NonNull
    static InputStream decryptingStream(@NonNull InputStream source, @NonNull char[] password) throws IOException {
        byte[] header = new byte[HEADER_LEN];
        readFully(source, header, 0, HEADER_LEN, "the encrypted backup header");
        for (int i = 0; i < MAGIC.length; i++)
            if (header[i] != MAGIC[i]) throw new IOException("This file is not a TermiNow encrypted backup");
        int version = header[MAGIC.length] & 0xff;
        if (version != VERSION) throw new IOException("Unsupported encrypted-backup version " + version);
        int iterations = bytesToInt(header, MAGIC.length + 1);
        if (iterations < 1 || iterations > 10_000_000) throw new IOException("The encrypted backup header is not valid");
        byte[] salt = Arrays.copyOfRange(header, MAGIC.length + 5, MAGIC.length + 5 + SALT_LEN);
        byte[] baseNonce = Arrays.copyOfRange(header, MAGIC.length + 5 + SALT_LEN, HEADER_LEN);
        byte[] key = deriveKey(password, salt, iterations);
        return new DecryptingInputStream(source, key, baseNonce);
    }

    // ---------------------------------------------------------------------------------------
    // PBKDF2-HMAC-SHA256 (hand-rolled so it works on API 24+)

    static byte[] deriveKey(@NonNull char[] password, @NonNull byte[] salt, int iterations) throws IOException {
        byte[] pwd = toUtf8(password);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pwd.length == 0 ? new byte[1] : pwd, "HmacSHA256"));
            int hLen = mac.getMacLength();            // 32
            int dkLen = KEY_BITS / 8;                 // 32
            int blocks = (dkLen + hLen - 1) / hLen;
            byte[] dk = new byte[blocks * hLen];
            byte[] block = new byte[salt.length + 4];
            System.arraycopy(salt, 0, block, 0, salt.length);
            for (int i = 1; i <= blocks; i++) {
                block[salt.length] = (byte) (i >>> 24);
                block[salt.length + 1] = (byte) (i >>> 16);
                block[salt.length + 2] = (byte) (i >>> 8);
                block[salt.length + 3] = (byte) i;
                byte[] u = mac.doFinal(block);        // U1 = HMAC(pwd, salt || INT(i))
                byte[] t = u.clone();
                for (int c = 1; c < iterations; c++) {
                    u = mac.doFinal(u);               // U_{c+1} = HMAC(pwd, U_c)
                    for (int k = 0; k < hLen; k++) t[k] ^= u[k];
                }
                System.arraycopy(t, 0, dk, (i - 1) * hLen, hLen);
            }
            return Arrays.copyOf(dk, dkLen);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not derive the backup key", e);
        } finally {
            Arrays.fill(pwd, (byte) 0);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Streams

    private static final class EncryptingOutputStream extends FilterOutputStream {
        private final byte[] mKey;
        private final byte[] mBaseNonce;
        private final byte[] mBuffer = new byte[CHUNK];
        private int mFill;
        private long mCounter;
        private boolean mClosed;

        EncryptingOutputStream(@NonNull OutputStream out, @NonNull byte[] key, @NonNull byte[] baseNonce) {
            super(out);
            mKey = key;
            mBaseNonce = baseNonce;
        }

        @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }

        @Override public void write(@NonNull byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, CHUNK - mFill);
                System.arraycopy(b, off, mBuffer, mFill, n);
                mFill += n; off += n; len -= n;
                if (mFill == CHUNK) emit(false);      // a full non-final chunk; the final one is written at close
            }
        }

        private void emit(boolean last) throws IOException {
            if (mCounter > 0xFFFFFFFFL) throw new IOException("The backup is too large to encrypt");
            byte[] cipher = gcm(Cipher.ENCRYPT_MODE, mKey, mBaseNonce, mCounter, last, mBuffer, mFill);
            out.write(intToBytes(cipher.length));
            out.write(last ? 1 : 0);
            out.write(cipher);
            mCounter++;
            mFill = 0;
        }

        @Override public void close() throws IOException {
            if (mClosed) return;
            mClosed = true;
            try {
                emit(true);                            // always a final (possibly empty) chunk
                out.flush();
            } finally {
                Arrays.fill(mKey, (byte) 0);
                out.close();
            }
        }
    }

    private static final class DecryptingInputStream extends FilterInputStream {
        private final byte[] mKey;
        private final byte[] mBaseNonce;
        private byte[] mPlain = new byte[0];
        private int mPlainOff;
        private long mCounter;
        private boolean mDone;

        DecryptingInputStream(@NonNull InputStream in, @NonNull byte[] key, @NonNull byte[] baseNonce) {
            super(in);
            mKey = key;
            mBaseNonce = baseNonce;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(@NonNull byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (mPlainOff >= mPlain.length) {
                if (!fill()) return -1;
            }
            int n = Math.min(len, mPlain.length - mPlainOff);
            System.arraycopy(mPlain, mPlainOff, b, off, n);
            mPlainOff += n;
            return n;
        }

        /** Decrypt the next chunk into {@link #mPlain}; returns false at the end of the stream. */
        private boolean fill() throws IOException {
            if (mDone) return false;
            if (mCounter > 0xFFFFFFFFL) throw new IOException("The encrypted backup is too large");
            byte[] lenFlag = new byte[5];
            int read = readSome(in, lenFlag, 0, 5);
            if (read == 0) throw new BadPasswordException("The encrypted backup ends before its final chunk");
            if (read < 5) throw new BadPasswordException("The encrypted backup is truncated");
            int cipherLen = bytesToInt(lenFlag, 0);
            boolean last = lenFlag[4] != 0;
            if (cipherLen < TAG_LEN || cipherLen > CHUNK + TAG_LEN) throw new BadPasswordException("The encrypted backup is not valid");
            byte[] cipher = new byte[cipherLen];
            readFully(in, cipher, 0, cipherLen, "an encrypted backup chunk");
            byte[] plain;
            try {
                plain = gcm(Cipher.DECRYPT_MODE, mKey, mBaseNonce, mCounter, last, cipher, cipherLen);
            } catch (IOException e) {
                throw new BadPasswordException("The password is wrong, or the backup is corrupted");
            }
            mCounter++;
            mPlain = plain;
            mPlainOff = 0;
            if (last) {
                mDone = true;
                Arrays.fill(mKey, (byte) 0);
            }
            // More to serve unless this was an empty final chunk (input that was a multiple of CHUNK).
            return plain.length > 0 || !last;
        }

        @Override public void close() throws IOException {
            Arrays.fill(mKey, (byte) 0);
            super.close();
        }
    }

    // ---------------------------------------------------------------------------------------
    // GCM + helpers

    /** One AES-256-GCM operation over {@code in[0..len)} with the per-chunk nonce and AAD. */
    private static byte[] gcm(int mode, @NonNull byte[] key, @NonNull byte[] baseNonce, long counter,
                              boolean last, @NonNull byte[] in, int len) throws IOException {
        try {
            byte[] nonce = new byte[12];
            System.arraycopy(baseNonce, 0, nonce, 0, BASE_NONCE_LEN);
            nonce[8] = (byte) (counter >>> 24);
            nonce[9] = (byte) (counter >>> 16);
            nonce[10] = (byte) (counter >>> 8);
            nonce[11] = (byte) counter;
            byte[] aad = new byte[6];
            aad[0] = (byte) VERSION;
            aad[1] = nonce[8]; aad[2] = nonce[9]; aad[3] = nonce[10]; aad[4] = nonce[11];
            aad[5] = (byte) (last ? 1 : 0);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(in, 0, len);
        } catch (GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    static byte[] intToBytes(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static int bytesToInt(@NonNull byte[] b, int off) {
        return ((b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
    }

    private static byte[] toUtf8(@NonNull char[] password) {
        java.nio.CharBuffer chars = java.nio.CharBuffer.wrap(password);
        java.nio.ByteBuffer bytes = StandardCharsets.UTF_8.encode(chars);
        byte[] out = new byte[bytes.remaining()];
        bytes.get(out);
        return out;
    }

    private static void readFully(@NonNull InputStream in, @NonNull byte[] b, int off, int len, @NonNull String what) throws IOException {
        int done = readSome(in, b, off, len);
        if (done < len) throw new EOFException("The backup is truncated while reading " + what);
    }

    private static int readSome(@NonNull InputStream in, @NonNull byte[] b, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = in.read(b, off + done, len - done);
            if (n < 0) break;
            done += n;
        }
        return done;
    }
}
