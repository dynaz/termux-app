package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * The encryption of vaults, done on the phone so that the server only stores ciphertext.
 *
 * - The vault password gives a key with PBKDF2-HMAC-SHA256, which encrypts the RSA private key
 *   of the account with AES-256-GCM.
 * - Each vault has a random AES-256 key, which encrypts its data with AES-256-GCM, bound to the
 *   vault id, and is wrapped for each member with RSA-OAEP (SHA-256) and their public key.
 *
 * Base64 is the standard alphabet without line breaks, which the server checks.
 */
public final class TerminowVaultCrypto {

    public static final int ITERATIONS = 310000;
    private static final int RSA_BITS = 3072;
    private static final int IV_LENGTH = 12;
    private static final String PRIVATE_KEY_AAD = "terminow-private-key";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Thrown when a password is wrong or data was changed. */
    public static class DecryptException extends Exception {
        DecryptException(String message) {
            super(message);
        }
    }

    /** What the server keeps for an account, and the private key that only the phone knows. */
    public static class AccountKeys {
        public String publicKey;
        public String encryptedPrivateKey;
        public String salt;
        public int iterations;
        public PrivateKey privateKey;
    }

    private TerminowVaultCrypto() {}

    // ---------------------------------------------------------------------------------------

    @NonNull
    static String base64(@NonNull byte[] bytes) {
        return TerminowPpk.encodeBase64(bytes);
    }

    @NonNull
    static byte[] unbase64(@NonNull String text) throws DecryptException {
        try {
            return TerminowPpk.decodeBase64(text);
        } catch (RuntimeException | TerminowPpk.PpkException e) {
            throw new DecryptException("Damaged data");
        }
    }

    @NonNull
    public static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** A new vault id, 24 hex digits, which the server checks. */
    @NonNull
    public static String newVaultId() {
        StringBuilder id = new StringBuilder();
        for (byte b : random(12)) id.append(String.format("%02x", b & 0xff));
        return id.toString();
    }

    /** PBKDF2 with HMAC-SHA256, written out because Android before 8 has no PBKDF2WithHmacSHA256. */
    @NonNull
    static byte[] pbkdf2(@NonNull String password, @NonNull byte[] salt, int iterations, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        if (passwordBytes.length == 0) passwordBytes = new byte[1];
        mac.init(new SecretKeySpec(passwordBytes, "HmacSHA256"));
        byte[] out = new byte[length];
        int blocks = (length + 31) / 32;
        for (int block = 1; block <= blocks; block++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (block >>> 24), (byte) (block >>> 16), (byte) (block >>> 8), (byte) block});
            byte[] u = mac.doFinal();
            byte[] t = u.clone();
            for (int i = 1; i < iterations; i++) {
                u = mac.doFinal(u);
                for (int j = 0; j < t.length; j++) t[j] ^= u[j];
            }
            System.arraycopy(t, 0, out, (block - 1) * 32, Math.min(32, length - (block - 1) * 32));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    static String seal(@NonNull byte[] key, @NonNull byte[] plain, @Nullable String aad) throws GeneralSecurityException {
        byte[] iv = random(IV_LENGTH);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = cipher.doFinal(plain);
        byte[] out = new byte[iv.length + sealed.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(sealed, 0, out, iv.length, sealed.length);
        return base64(out);
    }

    @NonNull
    static byte[] open(@NonNull byte[] key, @NonNull String sealed, @Nullable String aad) throws DecryptException {
        byte[] bytes = unbase64(sealed);
        if (bytes.length < IV_LENGTH + 16) throw new DecryptException("Damaged data");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bytes, 0, IV_LENGTH));
            if (aad != null) cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(bytes, IV_LENGTH, bytes.length - IV_LENGTH);
        } catch (GeneralSecurityException e) {
            throw new DecryptException("Wrong password or damaged data");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Account keys
    // ---------------------------------------------------------------------------------------

    /** New account keys, with the private key encrypted with the vault password. Takes a few seconds. */
    @NonNull
    public static AccountKeys createAccount(@NonNull String password) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_BITS, RANDOM);
        KeyPair pair = generator.generateKeyPair();
        AccountKeys keys = new AccountKeys();
        keys.publicKey = base64(pair.getPublic().getEncoded());
        keys.privateKey = pair.getPrivate();
        protectPrivateKey(keys, password);
        return keys;
    }

    /** Encrypt the private key of the keys with a (new) vault password. */
    public static void protectPrivateKey(@NonNull AccountKeys keys, @NonNull String password) throws GeneralSecurityException {
        byte[] salt = random(16);
        keys.salt = base64(salt);
        keys.iterations = ITERATIONS;
        byte[] key = pbkdf2(password, salt, ITERATIONS, 32);
        keys.encryptedPrivateKey = seal(key, keys.privateKey.getEncoded(), PRIVATE_KEY_AAD);
        Arrays.fill(key, (byte) 0);
    }

    /** The private key of an account, with its vault password. */
    @NonNull
    public static PrivateKey unlock(@NonNull String encryptedPrivateKey, @NonNull String salt, int iterations,
                                    @NonNull String password) throws DecryptException {
        try {
            byte[] key = pbkdf2(password, unbase64(salt), iterations, 32);
            byte[] pkcs8 = open(key, encryptedPrivateKey, PRIVATE_KEY_AAD);
            Arrays.fill(key, (byte) 0);
            return privateKey(pkcs8);
        } catch (GeneralSecurityException e) {
            throw new DecryptException("Wrong password or damaged data");
        }
    }

    @NonNull
    static PrivateKey privateKey(@NonNull byte[] pkcs8) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
    }

    @NonNull
    static PublicKey publicKey(@NonNull String base64) throws GeneralSecurityException, DecryptException {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(unbase64(base64)));
    }

    /**
     * A short fingerprint of a public key, to compare on both phones before giving a member
     * access, like "3F2A 91C0 7B1D 44E8".
     */
    @NonNull
    public static String fingerprint(@NonNull String publicKey) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(publicKey.getBytes(StandardCharsets.US_ASCII));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                if (i > 0 && i % 2 == 0) out.append(' ');
                out.append(String.format("%02X", hash[i] & 0xff));
            }
            return out.toString();
        } catch (GeneralSecurityException e) {
            return "?";
        }
    }

    // ---------------------------------------------------------------------------------------
    // Vault keys and data
    // ---------------------------------------------------------------------------------------

    private static Cipher oaep(int mode, java.security.Key key) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        cipher.init(mode, key, new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return cipher;
    }

    @NonNull
    public static byte[] newVaultKey() {
        return random(32);
    }

    /** Wrap a vault key for a member with their public key. */
    @NonNull
    public static String wrap(@NonNull byte[] vaultKey, @NonNull String publicKey) throws GeneralSecurityException, DecryptException {
        return base64(oaep(Cipher.ENCRYPT_MODE, publicKey(publicKey)).doFinal(vaultKey));
    }

    @NonNull
    public static byte[] unwrap(@NonNull String wrapped, @NonNull PrivateKey privateKey) throws DecryptException {
        try {
            byte[] key = oaep(Cipher.DECRYPT_MODE, privateKey).doFinal(unbase64(wrapped));
            if (key.length != 32) throw new DecryptException("Damaged vault key");
            return key;
        } catch (GeneralSecurityException e) {
            throw new DecryptException("The vault key is not for this account");
        }
    }

    @NonNull
    public static String encryptVault(@NonNull byte[] vaultKey, @NonNull String vaultId, @NonNull String json) throws GeneralSecurityException {
        return seal(vaultKey, json.getBytes(StandardCharsets.UTF_8), "terminow-vault:" + vaultId);
    }

    @NonNull
    public static String decryptVault(@NonNull byte[] vaultKey, @NonNull String vaultId, @NonNull String data) throws DecryptException {
        return new String(open(vaultKey, data, "terminow-vault:" + vaultId), StandardCharsets.UTF_8);
    }

}
