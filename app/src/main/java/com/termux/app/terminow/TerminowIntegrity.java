package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.bouncycastle.util.encoders.Hex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Play Integrity for the backend to observe: a token bound to one request of the signed-in
 * account. The app never decides anything from it, and Premium never depends on it; the backend
 * only records the verdicts until there is enough data for a policy. The Play build asks Google
 * Play for the token (see TerminowPlayIntegrity), other builds send none.
 */
final class TerminowIntegrity {

    interface Callback {
        /** The token, or {@code null} with why there is none, like UNSUPPORTED or an error code. */
        void done(@Nullable String token, @Nullable String error);
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private TerminowIntegrity() {}

    /** A random nonce of one request, which the backend accepts once. */
    @NonNull
    static String newNonce() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Hex.toHexString(bytes);
    }

    /**
     * The request hash that binds a token to its request: the account and the nonce, the same
     * as the backend computes. No secret is in it.
     */
    @NonNull
    static String requestHash(@NonNull String uid, @NonNull String nonce) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(("terminow-entitlement:" + uid + ":" + nonce).getBytes(StandardCharsets.UTF_8));
            return Hex.toHexString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
