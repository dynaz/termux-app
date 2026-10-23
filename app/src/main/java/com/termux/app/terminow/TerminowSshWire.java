package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The SSH wire format of keys, certificates and signatures (RFC 4251, PROTOCOL.certkeys of
 * OpenSSH): reading the public key and certificate lines of `~/.ssh`, and writing what the agent
 * of the app answers.
 */
public final class TerminowSshWire {

    public static final String CERT_SUFFIX = "-cert-v01@openssh.com";
    public static final String ECDSA_P256 = "ecdsa-sha2-nistp256";

    private TerminowSshWire() {}

    /** Reads the fields of a blob. */
    public static final class Reader {
        private final byte[] mData;
        private int mOffset;

        public Reader(@NonNull byte[] data) {
            mData = data;
        }

        @NonNull
        public byte[] string() {
            int length = (int) uint32();
            if (length < 0 || length > mData.length - mOffset) throw new IllegalArgumentException("Truncated");
            byte[] value = Arrays.copyOfRange(mData, mOffset, mOffset + length);
            mOffset += length;
            return value;
        }

        @NonNull
        public String text() {
            return new String(string(), StandardCharsets.UTF_8);
        }

        public long uint32() {
            if (mData.length - mOffset < 4) throw new IllegalArgumentException("Truncated");
            long value = ((mData[mOffset] & 0xffL) << 24) | ((mData[mOffset + 1] & 0xffL) << 16) |
                ((mData[mOffset + 2] & 0xffL) << 8) | (mData[mOffset + 3] & 0xffL);
            mOffset += 4;
            return value;
        }

        public long uint64() {
            return (uint32() << 32) | uint32();
        }

        public int byte1() {
            if (mOffset >= mData.length) throw new IllegalArgumentException("Truncated");
            return mData[mOffset++] & 0xff;
        }

        public boolean hasMore() {
            return mOffset < mData.length;
        }

        @NonNull
        public Reader skipString() {
            string();
            return this;
        }
    }

    /** Writes the fields of a blob. */
    public static final class Writer {
        private final ByteArrayOutputStream mOut = new ByteArrayOutputStream();

        @NonNull
        public Writer string(@NonNull byte[] value) {
            uint32(value.length);
            mOut.write(value, 0, value.length);
            return this;
        }

        @NonNull
        public Writer text(@NonNull String value) {
            return string(value.getBytes(StandardCharsets.UTF_8));
        }

        @NonNull
        public Writer uint32(long value) {
            mOut.write((int) (value >>> 24) & 0xff);
            mOut.write((int) (value >>> 16) & 0xff);
            mOut.write((int) (value >>> 8) & 0xff);
            mOut.write((int) value & 0xff);
            return this;
        }

        @NonNull
        public Writer byte1(int value) {
            mOut.write(value & 0xff);
            return this;
        }

        @NonNull
        public Writer mpint(@NonNull BigInteger value) {
            // Two's complement, big endian, with a leading zero byte if the high bit is set
            return string(value.signum() == 0 ? new byte[0] : value.toByteArray());
        }

        @NonNull
        public byte[] toBytes() {
            return mOut.toByteArray();
        }
    }

    /** How many fields the public part of a key type has after its name. */
    static int publicFieldCount(@NonNull String type) {
        switch (type) {
            case "ssh-ed25519": return 1;
            case "ssh-rsa": return 2;
            case "ssh-dss": return 4;
            case "sk-ssh-ed25519@openssh.com": return 2;
            case "sk-ecdsa-sha2-nistp256@openssh.com": return 3;
            default: return type.startsWith("ecdsa-sha2-") ? 2 : -1;
        }
    }

    /** A line of a `.pub` file: type, blob and comment. */
    public static final class PublicKeyLine {
        public final String type;
        public final byte[] blob;
        public final String comment;

        PublicKeyLine(@NonNull String type, @NonNull byte[] blob, @NonNull String comment) {
            this.type = type;
            this.blob = blob;
            this.comment = comment;
        }
    }

    /** The first key of a public key or certificate file, {@code null} if it is not one. */
    @Nullable
    public static PublicKeyLine parseLine(@NonNull String text) {
        for (String line : text.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+", 3);
            if (parts.length < 2) return null;
            try {
                byte[] blob = Base64.getDecoder().decode(parts[1]);
                String inner = new Reader(blob).text();
                if (!inner.equals(parts[0])) return null;
                return new PublicKeyLine(parts[0], blob, parts.length > 2 ? parts[2] : "");
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** What a certificate says, and the public key that it certifies. */
    public static final class Certificate {
        public final String type;
        /** The type of the key it certifies, like ssh-ed25519. */
        public final String keyType;
        /** The public key blob it certifies, as in the `.pub` file of that key. */
        public final byte[] keyBlob;
        public final long serial;
        /** 1 for a user certificate, 2 for a host certificate. */
        public final int certType;
        public final String keyId;
        public final List<String> principals;
        /** Seconds since 1970; 0 and 2^64-1 mean always. */
        public final long validAfter;
        public final long validBefore;

        Certificate(String type, String keyType, byte[] keyBlob, long serial, int certType, String keyId,
                    List<String> principals, long validAfter, long validBefore) {
            this.type = type;
            this.keyType = keyType;
            this.keyBlob = keyBlob;
            this.serial = serial;
            this.certType = certType;
            this.keyId = keyId;
            this.principals = principals;
            this.validAfter = validAfter;
            this.validBefore = validBefore;
        }

        /** Whether it expires: false for a certificate that is valid forever. */
        public boolean expires() {
            return validBefore != -1L;
        }

        public boolean isValidAt(long epochSeconds) {
            return Long.compareUnsigned(epochSeconds, validAfter) >= 0 && Long.compareUnsigned(epochSeconds, validBefore) < 0;
        }
    }

    /** A certificate blob, {@code null} if it is not a certificate of a key type that is known. */
    @Nullable
    public static Certificate parseCertificate(@NonNull byte[] blob) {
        try {
            Reader reader = new Reader(blob);
            String type = reader.text();
            if (!type.endsWith(CERT_SUFFIX)) return null;
            String keyType = type.substring(0, type.length() - CERT_SUFFIX.length());
            if (keyType.equals("sk-ssh-ed25519")) keyType = "sk-ssh-ed25519@openssh.com";
            if (keyType.equals("sk-ecdsa-sha2-nistp256")) keyType = "sk-ecdsa-sha2-nistp256@openssh.com";
            int fields = publicFieldCount(keyType);
            if (fields < 0) return null;
            reader.string(); // nonce
            Writer key = new Writer().text(keyType);
            for (int i = 0; i < fields; i++) key.string(reader.string());
            long serial = reader.uint64();
            int certType = (int) reader.uint32();
            String keyId = reader.text();
            List<String> principals = new ArrayList<>();
            Reader principalReader = new Reader(reader.string());
            while (principalReader.hasMore()) principals.add(principalReader.text());
            long validAfter = reader.uint64();
            long validBefore = reader.uint64();
            return new Certificate(type, keyType, key.toBytes(), serial, certType, keyId, principals, validAfter, validBefore);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The public key blob of a P-256 key, as `ecdsa-sha2-nistp256`. */
    @NonNull
    public static byte[] ecdsaP256Blob(@NonNull ECPublicKey key) {
        byte[] point = new byte[65];
        point[0] = 4;
        copyUnsigned(key.getW().getAffineX(), point, 1);
        copyUnsigned(key.getW().getAffineY(), point, 33);
        return new Writer().text(ECDSA_P256).text("nistp256").string(point).toBytes();
    }

    private static void copyUnsigned(@NonNull BigInteger value, @NonNull byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length > 32 ? bytes.length - 32 : 0;
        int length = bytes.length - start;
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }

    /** The `.pub` line of a key blob. */
    @NonNull
    public static String publicKeyLine(@NonNull byte[] blob, @NonNull String comment) {
        String type = new Reader(blob).text();
        return type + " " + Base64.getEncoder().encodeToString(blob) + (comment.isEmpty() ? "" : " " + comment);
    }

    /**
     * An SSH `ecdsa-sha2-nistp256` signature from the DER signature of Java: a SEQUENCE of the
     * INTEGERs r and s.
     */
    @NonNull
    public static byte[] ecdsaSignatureFromDer(@NonNull byte[] der) {
        int offset = 0;
        if ((der[offset++] & 0xff) != 0x30) throw new IllegalArgumentException("Not a DER sequence");
        offset += derLengthSize(der, offset);
        BigInteger[] values = new BigInteger[2];
        for (int i = 0; i < 2; i++) {
            if ((der[offset++] & 0xff) != 0x02) throw new IllegalArgumentException("Not a DER integer");
            int lengthSize = derLengthSize(der, offset);
            int length = derLength(der, offset);
            offset += lengthSize;
            values[i] = new BigInteger(1, Arrays.copyOfRange(der, offset, offset + length));
            offset += length;
        }
        byte[] rs = new Writer().mpint(values[0]).mpint(values[1]).toBytes();
        return new Writer().text(ECDSA_P256).string(rs).toBytes();
    }

    private static int derLengthSize(@NonNull byte[] der, int offset) {
        int first = der[offset] & 0xff;
        return first < 0x80 ? 1 : 1 + (first & 0x7f);
    }

    private static int derLength(@NonNull byte[] der, int offset) {
        int first = der[offset] & 0xff;
        if (first < 0x80) return first;
        int length = 0;
        for (int i = 1; i <= (first & 0x7f); i++) length = (length << 8) | (der[offset + i] & 0xff);
        return length;
    }


    // ---------------------------------------------------------------------------------------
    // FIDO2 security keys (PROTOCOL.u2f of OpenSSH)
    // ---------------------------------------------------------------------------------------

    public static final String SK_ECDSA = "sk-ecdsa-sha2-nistp256@openssh.com";

    /** The public key blob of a security key credential: the P-256 point and the application, like "ssh:". */
    @NonNull
    public static byte[] skEcdsaBlob(@NonNull byte[] x, @NonNull byte[] y, @NonNull String application) {
        if (x.length != 32 || y.length != 32) throw new IllegalArgumentException("Not a P-256 point");
        byte[] point = new byte[65];
        point[0] = 4;
        System.arraycopy(x, 0, point, 1, 32);
        System.arraycopy(y, 0, point, 33, 32);
        return new Writer().text(SK_ECDSA).text("nistp256").string(point).text(application).toBytes();
    }

    /**
     * The SSH signature of a security key from its assertion: the authenticator signed its
     * authenticator data (the hash of the application, the flags and the counter) followed by
     * the SHA-256 of the data, which is what OpenSSH checks.
     */
    @NonNull
    public static byte[] skEcdsaSignature(@NonNull byte[] der, @NonNull byte[] authenticatorData) {
        if (authenticatorData.length < 37) throw new IllegalArgumentException("Authenticator data too short");
        byte[] rs = new Reader(ecdsaSignatureFromDer(der)).skipString().string();
        long counter = ((authenticatorData[33] & 0xffL) << 24) | ((authenticatorData[34] & 0xffL) << 16) |
            ((authenticatorData[35] & 0xffL) << 8) | (authenticatorData[36] & 0xffL);
        return new Writer().text(SK_ECDSA).string(rs).byte1(authenticatorData[32]).uint32(counter).toBytes();
    }

}
