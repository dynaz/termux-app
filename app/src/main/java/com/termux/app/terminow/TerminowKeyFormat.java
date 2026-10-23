package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * What a pasted or picked key is, for the New Key panel, like the key form of Termius: an
 * OpenSSH, PEM or PuTTY private key with its type, whether it has a passphrase, and its public
 * key when that can be read without the passphrase. Also what it is when it is not a private
 * key, like a public key that was pasted by mistake.
 */
public final class TerminowKeyFormat {

    public enum Kind {
        EMPTY,
        /** -----BEGIN OPENSSH PRIVATE KEY----- */
        OPENSSH,
        /** -----BEGIN RSA/EC/DSA PRIVATE KEY----- */
        PEM,
        /** -----BEGIN PRIVATE KEY----- or ENCRYPTED PRIVATE KEY */
        PKCS8,
        /** PuTTY-User-Key-File-2 or -3, converted when it is saved */
        PPK,
        /** ssh-ed25519 AAAA..., a public key and not a private one */
        PUBLIC_KEY,
        /** ssh-ed25519-cert-v01@openssh.com AAAA..., a certificate */
        CERTIFICATE,
        UNKNOWN
    }

    /** What a text is. */
    public static final class Info {
        public Kind kind = Kind.UNKNOWN;
        /** Like "ED25519", "RSA" or "ECDSA", empty if not known. */
        public String type = "";
        /** Whether it is protected with a passphrase. */
        public boolean encrypted;
        /** The public key blob, {@code null} if it can not be read from the private key alone. */
        @Nullable public byte[] publicBlob;

        /** Whether it can be saved as a private key. */
        public boolean isPrivateKey() {
            return kind == Kind.OPENSSH || kind == Kind.PEM || kind == Kind.PKCS8 || kind == Kind.PPK;
        }
    }

    private TerminowKeyFormat() {}

    @NonNull
    public static Info analyze(@Nullable String text) {
        Info info = new Info();
        String value = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (value.isEmpty()) {
            info.kind = Kind.EMPTY;
            return info;
        }
        if (value.startsWith("PuTTY-User-Key-File-")) {
            info.kind = Kind.PPK;
            String first = value.substring(0, Math.max(0, value.indexOf('\n') < 0 ? value.length() : value.indexOf('\n')));
            int colon = first.indexOf(':');
            info.type = colon >= 0 ? typeLabel(first.substring(colon + 1).trim()) : "";
            info.encrypted = !value.contains("\nEncryption: none");
            return info;
        }
        if (value.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) {
            info.kind = Kind.OPENSSH;
            readOpenSsh(body(value), info);
            return info;
        }
        if (value.startsWith("-----BEGIN ") && value.contains("PRIVATE KEY-----")) {
            String header = value.substring(0, value.indexOf('\n') < 0 ? value.length() : value.indexOf('\n'));
            if (header.contains("BEGIN RSA PRIVATE KEY")) info.type = "RSA";
            else if (header.contains("BEGIN EC PRIVATE KEY")) info.type = "ECDSA";
            else if (header.contains("BEGIN DSA PRIVATE KEY")) info.type = "DSA";
            if (header.contains("BEGIN ENCRYPTED PRIVATE KEY")) {
                info.kind = Kind.PKCS8;
                info.encrypted = true;
            } else if (header.contains("BEGIN PRIVATE KEY")) {
                info.kind = Kind.PKCS8;
            } else {
                info.kind = Kind.PEM;
                info.encrypted = value.contains("Proc-Type: 4,ENCRYPTED");
                if (!info.encrypted && "RSA".equals(info.type)) info.publicBlob = rsaPublicBlob(decode(body(value)));
            }
            return info;
        }
        TerminowSshWire.PublicKeyLine line = TerminowSshWire.parseLine(value);
        if (line != null) {
            info.kind = line.type.contains("-cert-v01@openssh.com") ? Kind.CERTIFICATE : Kind.PUBLIC_KEY;
            info.type = typeLabel(line.type);
            info.publicBlob = line.blob;
        }
        return info;
    }

    /** Like "ED25519" for "ssh-ed25519", empty if not known. */
    @NonNull
    public static String typeLabel(@NonNull String name) {
        if (name.startsWith("sk-")) return "FIDO2";
        if (name.startsWith("ssh-ed25519")) return "ED25519";
        if (name.startsWith("ssh-rsa") || name.startsWith("rsa-sha2")) return "RSA";
        if (name.startsWith("ecdsa-sha2")) return "ECDSA";
        if (name.startsWith("ssh-dss")) return "DSA";
        return "";
    }

    /** The fingerprint of a public key as ssh-keygen -l shows it, like "SHA256:abc...". */
    @NonNull
    public static String fingerprint(@NonNull byte[] blob) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(blob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The type name inside a key blob, like "ssh-ed25519", empty if it can not be read. */
    @NonNull
    public static String blobType(@NonNull byte[] blob) {
        try {
            int length = readInt(blob, 0);
            if (length <= 0 || length > 64 || 4 + length > blob.length) return "";
            return new String(blob, 4, length, StandardCharsets.US_ASCII);
        } catch (IndexOutOfBoundsException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private static String body(@NonNull String pem) {
        StringBuilder body = new StringBuilder();
        for (String line : pem.split("\n")) {
            line = line.trim();
            if (line.startsWith("-----") || line.contains(":") || line.isEmpty()) continue;
            body.append(line);
        }
        return body.toString();
    }

    @Nullable
    private static byte[] decode(@NonNull String base64) {
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The openssh-key-v1 format: magic, cipher name, kdf name, kdf options, number of keys, then
     * the public keys in clear even when the private part is encrypted.
     */
    private static void readOpenSsh(@NonNull String base64, @NonNull Info info) {
        byte[] data = decode(base64);
        byte[] magic = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);
        if (data == null || data.length < magic.length + 16) {
            info.kind = Kind.UNKNOWN;
            return;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                info.kind = Kind.UNKNOWN;
                return;
            }
        }
        try {
            int offset = magic.length;
            int cipherLength = readInt(data, offset);
            String cipher = new String(data, offset + 4, cipherLength, StandardCharsets.US_ASCII);
            offset += 4 + cipherLength;
            offset += 4 + readInt(data, offset);
            offset += 4 + readInt(data, offset);
            int keys = readInt(data, offset);
            offset += 4;
            info.encrypted = !cipher.equals("none");
            if (keys < 1) return;
            int blobLength = readInt(data, offset);
            if (blobLength <= 0 || offset + 4 + blobLength > data.length) return;
            byte[] blob = new byte[blobLength];
            System.arraycopy(data, offset + 4, blob, 0, blobLength);
            info.publicBlob = blob;
            info.type = typeLabel(blobType(blob));
        } catch (IndexOutOfBoundsException | NegativeArraySizeException e) {
            info.kind = Kind.UNKNOWN;
        }
    }

    private static int readInt(@NonNull byte[] data, int offset) {
        if (offset + 4 > data.length) throw new IndexOutOfBoundsException();
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    /**
     * The ssh-rsa public key of a PKCS#1 RSAPrivateKey: SEQUENCE { version, n, e, ... }. The
     * INTEGERs of DER are already the two's complement that an SSH mpint is.
     */
    @Nullable
    static byte[] rsaPublicBlob(@Nullable byte[] der) {
        if (der == null) return null;
        try {
            int[] position = {0};
            if (der[position[0]++] != 0x30) return null;
            readLength(der, position);
            byte[] version = readInteger(der, position);
            byte[] n = readInteger(der, position);
            byte[] e = readInteger(der, position);
            if (version == null || n == null || e == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            writeString(out, "ssh-rsa".getBytes(StandardCharsets.US_ASCII));
            writeString(out, e);
            writeString(out, n);
            return out.toByteArray();
        } catch (IndexOutOfBoundsException ex) {
            return null;
        }
    }

    @Nullable
    private static byte[] readInteger(@NonNull byte[] der, @NonNull int[] position) {
        if (der[position[0]++] != 0x02) return null;
        int length = readLength(der, position);
        if (length <= 0 || position[0] + length > der.length) return null;
        byte[] value = new byte[length];
        System.arraycopy(der, position[0], value, 0, length);
        position[0] += length;
        return value;
    }

    private static int readLength(@NonNull byte[] der, @NonNull int[] position) {
        int first = der[position[0]++] & 0xff;
        if (first < 0x80) return first;
        int bytes = first & 0x7f;
        if (bytes > 3) throw new IndexOutOfBoundsException();
        int length = 0;
        for (int i = 0; i < bytes; i++) length = (length << 8) | (der[position[0]++] & 0xff);
        return length;
    }

    private static void writeString(@NonNull java.io.ByteArrayOutputStream out, @NonNull byte[] value) {
        out.write(value.length >>> 24);
        out.write(value.length >>> 16);
        out.write(value.length >>> 8);
        out.write(value.length);
        out.write(value, 0, value.length);
    }
}
