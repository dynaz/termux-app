package com.termux.app.terminow;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts a short secret (the cloud config, which holds the S3 secret key) at rest with a key that
 * lives in the Android Keystore and never leaves it. Used to wrap the whole config JSON before it is
 * saved to preferences, so a copy of the preferences file has no usable credential in it. Keystore
 * is hardware-backed where the device has it. This needs a real Android device/emulator — there is no
 * Keystore on the plain JVM, so it is compile-checked here, not unit-tested.
 */
final class TerminowSecret {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "terminow_backup_secret";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;

    private TerminowSecret() {}

    /** Encrypt {@code plaintext}, returning a base64 blob of {@code iv || ciphertext+tag}. */
    @NonNull
    static String wrap(@NonNull String plaintext) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not protect the cloud credentials", e);
        }
    }

    /** Reverse {@link #wrap}. Throws if the key is gone (e.g. app data cleared) or the blob is bad. */
    @NonNull
    static String unwrap(@NonNull String blob) throws IOException {
        try {
            byte[] all = Base64.decode(blob, Base64.NO_WRAP);
            if (all.length <= IV_LEN) throw new IOException("The stored cloud credentials are not valid");
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] pt = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not read the cloud credentials", e);
        }
    }

    @NonNull
    private static SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null);
            if (entry != null) return entry.getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

    @NonNull
    private static SecretKey getKey() throws GeneralSecurityException, IOException {
        return getOrCreateKey();
    }
}
