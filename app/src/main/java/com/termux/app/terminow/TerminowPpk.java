package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Convert a PuTTY private key file (`.ppk`, versions 2 and 3) to an unencrypted OpenSSH private
 * key, which ssh can use. RSA, Ed25519 and ECDSA keys are supported. An encrypted key is
 * decrypted with its passphrase, which is checked with the MAC of the file.
 */
public class TerminowPpk {

    /** Thrown when the key can not be converted, with a message to show. */
    public static class PpkException extends Exception {
        public PpkException(@NonNull String message) {
            super(message);
        }
    }

    /** Thrown when the key is encrypted and the passphrase is missing or wrong. */
    public static class PassphraseException extends PpkException {
        public PassphraseException(@NonNull String message) {
            super(message);
        }
    }

    public static class Result {
        /** The OpenSSH private key, with the `-----BEGIN OPENSSH PRIVATE KEY-----` armor. */
        public final String privateKey;
        /** The OpenSSH public key line, like `ssh-ed25519 AAAA... comment`. */
        public final String publicKey;
        public final String comment;

        Result(String privateKey, String publicKey, String comment) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.comment = comment;
        }
    }

    public static boolean isPpk(@Nullable String content) {
        return content != null && content.startsWith("PuTTY-User-Key-File-");
    }

    /** Whether the key needs a passphrase to be converted. */
    public static boolean isEncrypted(@NonNull String content) throws PpkException {
        return !"none".equals(parse(content).get("Encryption"));
    }

    @NonNull
    public static Result convert(@NonNull String content, @Nullable String passphrase) throws PpkException {
        Map<String, String> fields = parse(content);
        int version = Integer.parseInt(fields.get("Version"));
        String algorithm = fields.get("Algorithm");
        String encryption = required(fields, "Encryption");
        String comment = fields.containsKey("Comment") ? fields.get("Comment") : "";
        byte[] publicBlob = decodeBase64(required(fields, "Public-Lines"));
        byte[] privateBlob = decodeBase64(required(fields, "Private-Lines"));
        byte[] mac = hex(required(fields, "Private-MAC"));

        boolean encrypted;
        if ("none".equals(encryption)) {
            encrypted = false;
        } else if ("aes256-cbc".equals(encryption)) {
            encrypted = true;
        } else {
            throw new PpkException("The encryption " + encryption + " of the key is not supported");
        }
        if (encrypted && (passphrase == null || passphrase.isEmpty()))
            throw new PassphraseException("The key is protected with a passphrase");
        byte[] passphraseBytes = passphrase == null || !encrypted ? new byte[0] : passphrase.getBytes(StandardCharsets.UTF_8);

        byte[] macKey;
        try {
            if (version == 3) {
                byte[] cipherKey = null, iv = null;
                if (encrypted) {
                    byte[] derived = argon2(fields, passphraseBytes);
                    cipherKey = Arrays.copyOfRange(derived, 0, 32);
                    iv = Arrays.copyOfRange(derived, 32, 48);
                    macKey = Arrays.copyOfRange(derived, 48, 80);
                } else {
                    macKey = new byte[0];
                }
                if (encrypted) privateBlob = decrypt(privateBlob, cipherKey, iv);
                verifyMac("HmacSHA256", macKey, algorithm, encryption, comment, publicBlob, privateBlob, mac);
            } else if (version == 2) {
                if (encrypted) {
                    byte[] key = new byte[32];
                    System.arraycopy(sha1(new byte[]{0, 0, 0, 0}, passphraseBytes), 0, key, 0, 20);
                    System.arraycopy(sha1(new byte[]{0, 0, 0, 1}, passphraseBytes), 0, key, 20, 12);
                    privateBlob = decrypt(privateBlob, key, new byte[16]);
                }
                macKey = sha1("putty-private-key-file-mac-key".getBytes(StandardCharsets.US_ASCII), passphraseBytes);
                verifyMac("HmacSHA1", macKey, algorithm, encryption, comment, publicBlob, privateBlob, mac);
            } else {
                throw new PpkException("PuTTY key file version " + version + " is not supported");
            }
        } catch (GeneralSecurityException e) {
            throw new PpkException("Failed to decrypt the key: " + e.getMessage());
        }

        return toOpenSsh(algorithm, publicBlob, privateBlob, comment);
    }

    @NonNull
    private static Map<String, String> parse(@NonNull String content) throws PpkException {
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        Map<String, String> fields = new HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(": ");
            if (colon < 0) continue;
            String key = line.substring(0, colon);
            String value = line.substring(colon + 2).trim();
            if (key.startsWith("PuTTY-User-Key-File-")) {
                fields.put("Version", key.substring("PuTTY-User-Key-File-".length()));
                fields.put("Algorithm", value);
            } else if (key.equals("Public-Lines") || key.equals("Private-Lines")) {
                int count;
                try {
                    count = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new PpkException("The key file is damaged");
                }
                StringBuilder data = new StringBuilder();
                for (int j = 0; j < count; j++) {
                    if (i + 1 + j >= lines.length) throw new PpkException("The key file is cut off");
                    data.append(lines[i + 1 + j].trim());
                }
                fields.put(key, data.toString());
                i += count;
            } else {
                fields.put(key, value);
            }
        }
        if (!fields.containsKey("Version") || !fields.containsKey("Algorithm"))
            throw new PpkException("This is not a PuTTY key file");
        try {
            Integer.parseInt(fields.get("Version"));
        } catch (NumberFormatException e) {
            throw new PpkException("This is not a PuTTY key file");
        }
        return fields;
    }

    @NonNull
    private static String required(@NonNull Map<String, String> fields, @NonNull String key) throws PpkException {
        String value = fields.get(key);
        if (value == null) throw new PpkException("The key file has no " + key);
        return value;
    }

    @NonNull
    private static byte[] argon2(@NonNull Map<String, String> fields, @NonNull byte[] passphrase) throws PpkException {
        String flavour = required(fields, "Key-Derivation");
        int type;
        switch (flavour) {
            case "Argon2id": type = Argon2Parameters.ARGON2_id; break;
            case "Argon2i": type = Argon2Parameters.ARGON2_i; break;
            case "Argon2d": type = Argon2Parameters.ARGON2_d; break;
            default: throw new PpkException("The key derivation " + flavour + " is not supported");
        }
        int memory, passes, parallelism;
        try {
            memory = Integer.parseInt(required(fields, "Argon2-Memory"));
            passes = Integer.parseInt(required(fields, "Argon2-Passes"));
            parallelism = Integer.parseInt(required(fields, "Argon2-Parallelism"));
        } catch (NumberFormatException e) {
            throw new PpkException("The key file is damaged");
        }
        // Limits against a file that would use too much memory or time on a phone
        if (memory <= 0 || memory > 1024 * 1024 || passes <= 0 || passes > 1000 || parallelism <= 0 || parallelism > 64)
            throw new PpkException("The key derivation settings of the key are not supported");

        Argon2Parameters parameters = new Argon2Parameters.Builder(type)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memory)
            .withIterations(passes)
            .withParallelism(parallelism)
            .withSalt(hex(required(fields, "Argon2-Salt")))
            .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] out = new byte[80];
        generator.generateBytes(passphrase, out);
        return out;
    }

    @NonNull
    private static byte[] decrypt(@NonNull byte[] data, @NonNull byte[] key, @NonNull byte[] iv) throws GeneralSecurityException, PpkException {
        if (data.length % 16 != 0) throw new PpkException("The key file is damaged");
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    private static void verifyMac(@NonNull String macAlgorithm, @NonNull byte[] macKey, @NonNull String algorithm,
                                  @NonNull String encryption, @NonNull String comment, @NonNull byte[] publicBlob,
                                  @NonNull byte[] privateBlob, @NonNull byte[] expected) throws GeneralSecurityException, PpkException {
        SshWriter data = new SshWriter();
        data.string(algorithm.getBytes(StandardCharsets.UTF_8));
        data.string(encryption.getBytes(StandardCharsets.UTF_8));
        data.string(comment.getBytes(StandardCharsets.UTF_8));
        data.string(publicBlob);
        data.string(privateBlob);
        byte[] actual;
        if (macKey.length == 0) {
            // Unencrypted v3 keys use HMAC with an empty key, which SecretKeySpec does not allow
            actual = emptyKeyHmac(macAlgorithm, data.toByteArray());
        } else {
            Mac mac = Mac.getInstance(macAlgorithm);
            mac.init(new SecretKeySpec(macKey, macAlgorithm));
            actual = mac.doFinal(data.toByteArray());
        }
        if (!MessageDigest.isEqual(actual, expected)) {
            if (!"none".equals(encryption))
                throw new PassphraseException("The passphrase is wrong");
            throw new PpkException("The key file is damaged");
        }
    }

    /** HMAC with an empty key, which is the same as a key of zero bytes padded to the block size. */
    @NonNull
    private static byte[] emptyKeyHmac(@NonNull String macAlgorithm, @NonNull byte[] data) throws GeneralSecurityException {
        String digest = macAlgorithm.equals("HmacSHA256") ? "SHA-256" : "SHA-1";
        int blockSize = 64;
        byte[] ipad = new byte[blockSize], opad = new byte[blockSize];
        Arrays.fill(ipad, (byte) 0x36);
        Arrays.fill(opad, (byte) 0x5c);
        MessageDigest inner = MessageDigest.getInstance(digest);
        inner.update(ipad);
        inner.update(data);
        MessageDigest outer = MessageDigest.getInstance(digest);
        outer.update(opad);
        outer.update(inner.digest());
        return outer.digest();
    }

    @NonNull
    private static Result toOpenSsh(@NonNull String algorithm, @NonNull byte[] publicBlob, @NonNull byte[] privateBlob,
                                    @NonNull String comment) throws PpkException {
        SshReader pub = new SshReader(publicBlob);
        SshReader priv = new SshReader(privateBlob);
        String type = new String(pub.string(), StandardCharsets.US_ASCII);
        if (!type.equals(algorithm)) throw new PpkException("The key file is damaged");

        SshWriter key = new SshWriter();
        key.string(type.getBytes(StandardCharsets.US_ASCII));
        if (type.equals("ssh-rsa")) {
            byte[] e = pub.mpint(), n = pub.mpint();
            byte[] d = priv.mpint(), p = priv.mpint(), q = priv.mpint(), iqmp = priv.mpint();
            key.string(n).string(e).string(d).string(iqmp).string(p).string(q);
        } else if (type.equals("ssh-ed25519")) {
            byte[] publicKey = pub.string();
            byte[] seed = priv.string();
            if (publicKey.length != 32 || seed.length != 32) throw new PpkException("The key file is damaged");
            byte[] secret = new byte[64];
            System.arraycopy(seed, 0, secret, 0, 32);
            System.arraycopy(publicKey, 0, secret, 32, 32);
            key.string(publicKey).string(secret);
        } else if (type.startsWith("ecdsa-sha2-")) {
            byte[] curve = pub.string(), point = pub.string();
            byte[] d = priv.mpint();
            key.string(curve).string(point).string(d);
        } else {
            throw new PpkException("Keys of type " + type + " are not supported");
        }
        key.string(comment.getBytes(StandardCharsets.UTF_8));

        // The private section starts with a random check number written twice, and is padded to
        // the block size of the (no) cipher, 8
        byte[] check = new byte[4];
        new SecureRandom().nextBytes(check);
        SshWriter section = new SshWriter();
        section.raw(check).raw(check).raw(key.toByteArray());
        for (int i = 1; section.size() % 8 != 0; i++)
            section.raw(new byte[]{(byte) i});

        SshWriter file = new SshWriter();
        file.raw("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
        file.string("none".getBytes(StandardCharsets.US_ASCII));
        file.string("none".getBytes(StandardCharsets.US_ASCII));
        file.string(new byte[0]);
        file.uint32(1);
        file.string(publicBlob);
        file.string(section.toByteArray());

        String base64 = encodeBase64(file.toByteArray());
        StringBuilder pem = new StringBuilder("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        for (int i = 0; i < base64.length(); i += 70)
            pem.append(base64, i, Math.min(base64.length(), i + 70)).append('\n');
        pem.append("-----END OPENSSH PRIVATE KEY-----\n");

        String publicKey = type + " " + encodeBase64(publicBlob) + (comment.isEmpty() ? "" : " " + comment);
        return new Result(pem.toString(), publicKey, comment);
    }

    // ---------------------------------------------------------------------------------------
    // Writing a .ppk (version 3): the reverse of convert(), with the same MAC and crypto so a
    // file written here is read back by convert() and loads in PuTTY. The caller gives the SSH
    // public blob and the PuTTY private blob (the private fields of the type, without the type).
    // ---------------------------------------------------------------------------------------

    @NonNull
    public static String toPpk(@NonNull String algorithm, @NonNull byte[] publicBlob, @NonNull byte[] privateBlob,
                               @NonNull String comment, @Nullable String passphrase) throws PpkException {
        boolean encrypted = passphrase != null && !passphrase.isEmpty();
        String encryption = encrypted ? "aes256-cbc" : "none";
        byte[] plaintext = privateBlob;
        String kdfLines = "";
        byte[] stored;
        byte[] macKey;
        try {
            if (encrypted) {
                // Pad the plaintext to the AES block; the padding is covered by the MAC and ignored
                // by the reader, so its content does not matter.
                int pad = (16 - plaintext.length % 16) % 16;
                if (pad != 0) {
                    byte[] padded = Arrays.copyOf(plaintext, plaintext.length + pad);
                    byte[] random = new byte[pad];
                    new SecureRandom().nextBytes(random);
                    System.arraycopy(random, 0, padded, plaintext.length, pad);
                    plaintext = padded;
                }
                byte[] salt = new byte[16];
                new SecureRandom().nextBytes(salt);
                int memory = 8192, passes = 21, parallelism = 1;
                byte[] derived = argon2Write(salt, memory, passes, parallelism, passphrase.getBytes(StandardCharsets.UTF_8));
                byte[] cipherKey = Arrays.copyOfRange(derived, 0, 32);
                byte[] iv = Arrays.copyOfRange(derived, 32, 48);
                macKey = Arrays.copyOfRange(derived, 48, 80);
                stored = encrypt(plaintext, cipherKey, iv);
                kdfLines = "Key-Derivation: Argon2id\n"
                    + "Argon2-Memory: " + memory + "\n"
                    + "Argon2-Passes: " + passes + "\n"
                    + "Argon2-Parallelism: " + parallelism + "\n"
                    + "Argon2-Salt: " + toHex(salt) + "\n";
            } else {
                macKey = new byte[0];
                stored = plaintext;
            }
            byte[] mac = computeMac("HmacSHA256", macKey, algorithm, encryption, comment, publicBlob, plaintext);
            StringBuilder out = new StringBuilder();
            out.append("PuTTY-User-Key-File-3: ").append(algorithm).append('\n');
            out.append("Encryption: ").append(encryption).append('\n');
            out.append("Comment: ").append(comment).append('\n');
            appendBase64Lines(out, "Public-Lines", publicBlob);
            out.append(kdfLines);
            appendBase64Lines(out, "Private-Lines", stored);
            out.append("Private-MAC: ").append(toHex(mac)).append('\n');
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new PpkException("Failed to build the key: " + e.getMessage());
        }
    }

    private static void appendBase64Lines(@NonNull StringBuilder out, @NonNull String label, @NonNull byte[] data) {
        String base64 = encodeBase64(data);
        int lines = Math.max(1, (base64.length() + 63) / 64);
        out.append(label).append(": ").append(lines).append('\n');
        for (int i = 0; i < base64.length(); i += 64)
            out.append(base64, i, Math.min(base64.length(), i + 64)).append('\n');
        if (base64.isEmpty()) out.append('\n');
    }

    @NonNull
    private static byte[] encrypt(@NonNull byte[] data, @NonNull byte[] key, @NonNull byte[] iv) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    @NonNull
    private static byte[] computeMac(@NonNull String macAlgorithm, @NonNull byte[] macKey, @NonNull String algorithm,
                                     @NonNull String encryption, @NonNull String comment, @NonNull byte[] publicBlob,
                                     @NonNull byte[] privateBlob) throws GeneralSecurityException {
        SshWriter data = new SshWriter();
        data.string(algorithm.getBytes(StandardCharsets.UTF_8));
        data.string(encryption.getBytes(StandardCharsets.UTF_8));
        data.string(comment.getBytes(StandardCharsets.UTF_8));
        data.string(publicBlob);
        data.string(privateBlob);
        if (macKey.length == 0) return emptyKeyHmac(macAlgorithm, data.toByteArray());
        Mac mac = Mac.getInstance(macAlgorithm);
        mac.init(new SecretKeySpec(macKey, macAlgorithm));
        return mac.doFinal(data.toByteArray());
    }

    @NonNull
    private static byte[] argon2Write(@NonNull byte[] salt, int memory, int passes, int parallelism, @NonNull byte[] passphrase) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memory).withIterations(passes).withParallelism(parallelism).withSalt(salt).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] out = new byte[80];
        generator.generateBytes(passphrase, out);
        return out;
    }

    @NonNull
    private static String toHex(@NonNull byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }

    /** An SSH mpint of a non-negative integer: its two's-complement big-endian bytes (0 -> empty). */
    @NonNull
    static byte[] mpint(@NonNull BigInteger value) {
        if (value.signum() == 0) return new byte[0];
        return value.toByteArray();
    }

    // ---------------------------------------------------------------------------------------
    // Exporting a stored key file to .ppk: JSch reads and decrypts it (any format, including the
    // bcrypt/aes/3des OpenSSH encryption), then the private material is re-encoded into PuTTY's
    // blobs and written with toPpk(). The .ppk keeps the same passphrase. ed25519, RSA and ECDSA.
    // ---------------------------------------------------------------------------------------

    /**
     * Read the private key at {@code keyFile}, decrypting with {@code passphrase} if it has one, and
     * return it as a PuTTY {@code .ppk} (version 3) with the same passphrase and the given comment.
     */
    @NonNull
    public static String exportKeyFile(@NonNull File keyFile, @Nullable String passphrase, @NonNull String comment)
            throws PpkException {
        JSch jsch = new JSch();
        KeyPair pair = null;
        try {
            pair = KeyPair.load(jsch, keyFile.getPath());
            if (pair.isEncrypted()) {
                if (passphrase == null || passphrase.isEmpty())
                    throw new PassphraseException("The key is protected with a passphrase");
                if (!pair.decrypt(passphrase.getBytes(StandardCharsets.UTF_8)))
                    throw new PassphraseException("The passphrase is wrong");
            }
            byte[] publicBlob = pair.getPublicKeyBlob();
            if (publicBlob == null || publicBlob.length == 0)
                throw new PpkException("The key has no public key to export");
            String algorithm = TerminowKeyFormat.blobType(publicBlob);
            if (algorithm.isEmpty()) throw new PpkException("The key type is not known");
            byte[] privateBlob;
            if (algorithm.equals("ssh-ed25519")) {
                // JSch can decrypt an Ed25519 key but can not re-serialize it (writePrivateKey is
                // unsupported for EdDSA), so the 32-byte seed is read from the decrypted key pair.
                privateBlob = new SshWriter().string(ed25519Seed(pair)).toByteArray();
            } else {
                ByteArrayOutputStream prv = new ByteArrayOutputStream();
                pair.writePrivateKey(prv);
                privateBlob = puttyPrivateBlob(algorithm, prv.toByteArray());
            }
            return toPpk(algorithm, publicBlob, privateBlob, comment, passphrase);
        } catch (JSchException e) {
            throw new PpkException("The key could not be read: " + e.getMessage());
        } finally {
            if (pair != null) pair.dispose();
        }
    }

    /**
     * The 32-byte Ed25519 seed of a decrypted JSch key pair. JSch holds it after {@code decrypt()}
     * but does not expose it (getPrivateKey throws), so it is read from the key pair's field. The
     * caller verifies correctness: the seed derives the same public key.
     */
    @NonNull
    private static byte[] ed25519Seed(@NonNull KeyPair pair) throws PpkException {
        // prv_array is declared on the KeyPairEdDSA superclass, so walk up to find it.
        for (Class<?> c = pair.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field field = c.getDeclaredField("prv_array");
                field.setAccessible(true);
                byte[] value = (byte[]) field.get(pair);
                if (value == null || value.length < 32) throw new PpkException("The Ed25519 key could not be read");
                return Arrays.copyOfRange(value, 0, 32);
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new PpkException("This Ed25519 key can not be exported to .ppk");
            }
        }
        throw new PpkException("This Ed25519 key can not be exported to .ppk");
    }

    /** The PuTTY private blob (the private fields only) from the unencrypted key JSch wrote. */
    @NonNull
    private static byte[] puttyPrivateBlob(@NonNull String algorithm, @NonNull byte[] keyBytes) throws PpkException {
        String text = new String(keyBytes, StandardCharsets.UTF_8);
        if (text.contains("BEGIN OPENSSH PRIVATE KEY")) return fromOpenSsh(text);
        if (text.contains("BEGIN RSA PRIVATE KEY")) return fromRsaPem(text);
        if (text.contains("BEGIN EC PRIVATE KEY")) return fromEcPem(text);
        throw new PpkException("This key can not be exported to .ppk (unsupported format)");
    }

    /** Parse an unencrypted openssh-key-v1 and build the PuTTY private blob for its one key. */
    @NonNull
    private static byte[] fromOpenSsh(@NonNull String pem) throws PpkException {
        byte[] data = decodeBase64(stripArmor(pem));
        byte[] magic = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);
        if (data.length < magic.length) throw new PpkException("The key file is damaged");
        for (int i = 0; i < magic.length; i++) if (data[i] != magic[i]) throw new PpkException("The key file is damaged");
        SshReader r = new SshReader(data);
        r.skip(magic.length);
        r.string();              // cipher (none, it was decrypted)
        r.string();              // kdfname
        r.string();              // kdfoptions
        r.uint32();              // number of keys
        r.string();              // the public key
        SshReader p = new SshReader(r.string()); // the private section (plaintext)
        p.uint32();              // check int 1
        p.uint32();              // check int 2
        String type = new String(p.string(), StandardCharsets.US_ASCII);
        SshWriter blob = new SshWriter();
        if (type.equals("ssh-ed25519")) {
            p.string();                                  // public key (32)
            byte[] secret = p.string();                  // seed(32) || public(32)
            if (secret.length < 32) throw new PpkException("The key file is damaged");
            blob.string(Arrays.copyOfRange(secret, 0, 32));
        } else if (type.equals("ssh-rsa")) {
            p.mpint(); p.mpint();                        // n, e
            BigInteger d = p.mpintValue(), iqmp = p.mpintValue(), pp = p.mpintValue(), q = p.mpintValue();
            blob.string(mpint(d)).string(mpint(pp)).string(mpint(q)).string(mpint(iqmp));
        } else if (type.startsWith("ecdsa-sha2-")) {
            p.string(); p.string();                      // curve, point
            blob.string(mpint(p.mpintValue()));          // d
        } else {
            throw new PpkException("Keys of type " + type + " can not be exported to .ppk");
        }
        return blob.toByteArray();
    }

    /** PKCS#1 RSA private key (-----BEGIN RSA PRIVATE KEY-----) -> PuTTY private blob. */
    @NonNull
    private static byte[] fromRsaPem(@NonNull String pem) throws PpkException {
        try {
            org.bouncycastle.asn1.pkcs.RSAPrivateKey rsa =
                org.bouncycastle.asn1.pkcs.RSAPrivateKey.getInstance(decodeBase64(stripArmor(pem)));
            return new SshWriter()
                .string(mpint(rsa.getPrivateExponent()))
                .string(mpint(rsa.getPrime1()))
                .string(mpint(rsa.getPrime2()))
                .string(mpint(rsa.getCoefficient()))
                .toByteArray();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new PpkException("The RSA key could not be read");
        }
    }

    /** SEC1 EC private key (-----BEGIN EC PRIVATE KEY-----) -> PuTTY private blob (the scalar). */
    @NonNull
    private static byte[] fromEcPem(@NonNull String pem) throws PpkException {
        try {
            org.bouncycastle.asn1.sec.ECPrivateKey ec =
                org.bouncycastle.asn1.sec.ECPrivateKey.getInstance(decodeBase64(stripArmor(pem)));
            return new SshWriter().string(mpint(ec.getKey())).toByteArray();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new PpkException("The ECDSA key could not be read");
        }
    }

    @NonNull
    private static String stripArmor(@NonNull String pem) {
        StringBuilder out = new StringBuilder();
        for (String line : pem.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-----")) continue;
            out.append(trimmed);
        }
        return out.toString();
    }

    @NonNull
    private static byte[] sha1(@NonNull byte[] prefix, @NonNull byte[] data) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(prefix);
        digest.update(data);
        return digest.digest();
    }

    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    // java.util.Base64 needs Android 8 and android.util.Base64 is not in the JVM of the unit tests

    @NonNull
    static String encodeBase64(@NonNull byte[] data) {
        StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            int b0 = data[i] & 0xff, b1 = i + 1 < data.length ? data[i + 1] & 0xff : 0, b2 = i + 2 < data.length ? data[i + 2] & 0xff : 0;
            out.append(BASE64.charAt(b0 >> 2)).append(BASE64.charAt((b0 & 3) << 4 | b1 >> 4));
            out.append(i + 1 < data.length ? BASE64.charAt((b1 & 15) << 2 | b2 >> 6) : '=');
            out.append(i + 2 < data.length ? BASE64.charAt(b2 & 63) : '=');
        }
        return out.toString();
    }

    @NonNull
    static byte[] decodeBase64(@NonNull String data) throws PpkException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (c == '=') break;
            int value = BASE64.indexOf(c);
            if (value < 0) throw new PpkException("The key file is damaged");
            buffer = buffer << 6 | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write(buffer >> bits & 0xff);
            }
        }
        return out.toByteArray();
    }

    @NonNull
    private static byte[] hex(@NonNull String text) throws PpkException {
        if (text.length() % 2 != 0) throw new PpkException("The key file is damaged");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(text.charAt(2 * i), 16), low = Character.digit(text.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) throw new PpkException("The key file is damaged");
            out[i] = (byte) (high << 4 | low);
        }
        return out;
    }

    private static class SshReader {
        private final byte[] mData;
        private int mPosition;

        SshReader(@NonNull byte[] data) {
            mData = data;
        }

        @NonNull
        byte[] string() throws PpkException {
            if (mPosition + 4 > mData.length) throw new PpkException("The key file is damaged");
            int length = (mData[mPosition] & 0xff) << 24 | (mData[mPosition + 1] & 0xff) << 16 |
                (mData[mPosition + 2] & 0xff) << 8 | (mData[mPosition + 3] & 0xff);
            mPosition += 4;
            if (length < 0 || mPosition + length > mData.length) throw new PpkException("The key file is damaged");
            byte[] value = Arrays.copyOfRange(mData, mPosition, mPosition + length);
            mPosition += length;
            return value;
        }

        /** An mpint, as its bytes in the minimal two's complement form that OpenSSH writes. */
        @NonNull
        byte[] mpint() throws PpkException {
            return new BigInteger(1, string()).toByteArray();
        }

        /** The mpint as a BigInteger, for re-encoding. */
        @NonNull
        BigInteger mpintValue() throws PpkException {
            return new BigInteger(1, string());
        }

        int uint32() throws PpkException {
            if (mPosition + 4 > mData.length) throw new PpkException("The key file is damaged");
            int value = (mData[mPosition] & 0xff) << 24 | (mData[mPosition + 1] & 0xff) << 16 |
                (mData[mPosition + 2] & 0xff) << 8 | (mData[mPosition + 3] & 0xff);
            mPosition += 4;
            return value;
        }

        void skip(int n) throws PpkException {
            if (mPosition + n > mData.length) throw new PpkException("The key file is damaged");
            mPosition += n;
        }
    }

    private static class SshWriter {
        private final ByteArrayOutputStream mOut = new ByteArrayOutputStream();

        SshWriter uint32(int value) {
            mOut.write(value >>> 24);
            mOut.write(value >>> 16);
            mOut.write(value >>> 8);
            mOut.write(value);
            return this;
        }

        SshWriter string(@NonNull byte[] value) {
            uint32(value.length);
            mOut.write(value, 0, value.length);
            return this;
        }

        SshWriter raw(@NonNull byte[] value) {
            mOut.write(value, 0, value.length);
            return this;
        }

        int size() {
            return mOut.size();
        }

        @NonNull
        byte[] toByteArray() {
            return mOut.toByteArray();
        }
    }

}
