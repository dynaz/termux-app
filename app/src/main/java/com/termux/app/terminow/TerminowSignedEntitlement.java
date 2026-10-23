package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Base64;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * The Premium entitlement that the TermiNow backend signs: the backend keeps the Ed25519 private
 * key, the app only the public keys below. A signed entitlement says who it is for, whether
 * Premium is active, until when the subscription is verified and until when this statement may
 * be used offline.
 *
 * It makes a cached Premium that was edited on the phone fail, but it can not stop a patched
 * app from skipping the check: local features stay patchable, only the backend can refuse a
 * protected service.
 *
 * The format is {"payload": base64(JSON), "signature": base64(Ed25519(payload bytes)),
 * "kid": key id}, with the payload {"v": 1, "uid", "premium", "expiresAt", "iat", "exp", "aud"}.
 */
final class TerminowSignedEntitlement {

    static final String AUDIENCE = "com.terminow";
    /** Clocks of phones are wrong: an entitlement issued a little in the future is fine. */
    static final long CLOCK_SKEW_MS = 5 * 60 * 1000;

    /** The public keys of the backend by id; a new key is added here before the backend uses it. */
    private static final String[][] PUBLIC_KEYS = {
        // {"kid", "base64 of the 32 byte Ed25519 public key"}, filled in when the backend key is made
    };

    enum Result { VALID, MISSING, BAD_SIGNATURE, WRONG_ACCOUNT, EXPIRED, MALFORMED, UNKNOWN_KEY }

    static final class Verified {
        @NonNull final Result result;
        final boolean premium;
        final long expiresAt;
        final long exp;

        Verified(@NonNull Result result, boolean premium, long expiresAt, long exp) {
            this.result = result;
            this.premium = premium;
            this.expiresAt = expiresAt;
            this.exp = exp;
        }

        static Verified failed(@NonNull Result result) {
            return new Verified(result, false, 0, 0);
        }
    }

    private TerminowSignedEntitlement() {}

    @NonNull
    static Verified verify(@Nullable JSONObject signed, @NonNull String uid, long now) {
        return verify(signed, uid, now, PUBLIC_KEYS);
    }

    @NonNull
    static Verified verify(@Nullable JSONObject signed, @NonNull String uid, long now, @NonNull String[][] keys) {
        if (signed == null) return Verified.failed(Result.MISSING);
        String kid = signed.optString("kid");
        byte[] publicKey = null;
        for (String[] key : keys) if (key[0].equals(kid)) publicKey = decode(key[1]);
        if (publicKey == null || publicKey.length != Ed25519PublicKeyParameters.KEY_SIZE) return Verified.failed(Result.UNKNOWN_KEY);
        byte[] payloadBytes = decode(signed.optString("payload"));
        byte[] signature = decode(signed.optString("signature"));
        if (payloadBytes == null || signature == null) return Verified.failed(Result.MALFORMED);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        verifier.update(payloadBytes, 0, payloadBytes.length);
        if (!verifier.verifySignature(signature)) return Verified.failed(Result.BAD_SIGNATURE);
        JSONObject payload;
        try {
            payload = new JSONObject(new String(payloadBytes, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            return Verified.failed(Result.MALFORMED);
        }
        if (payload.optInt("v") != 1 || !AUDIENCE.equals(payload.optString("aud"))) return Verified.failed(Result.MALFORMED);
        if (!uid.equals(payload.optString("uid"))) return Verified.failed(Result.WRONG_ACCOUNT);
        long iat = payload.optLong("iat");
        long exp = payload.optLong("exp");
        if (iat <= 0 || exp <= iat || iat > now + CLOCK_SKEW_MS || exp <= now) return Verified.failed(Result.EXPIRED);
        return new Verified(Result.VALID, payload.optBoolean("premium"), payload.optLong("expiresAt"), exp);
    }

    @Nullable
    private static byte[] decode(@Nullable String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            return Base64.decode(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
