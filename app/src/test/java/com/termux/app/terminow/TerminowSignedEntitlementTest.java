package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.util.encoders.Base64;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class TerminowSignedEntitlementTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private final AsymmetricCipherKeyPair mPair = generate();
    private final String[][] mKeys = {{"k1", Base64.toBase64String(((Ed25519PublicKeyParameters) mPair.getPublic()).getEncoded())}};

    private static AsymmetricCipherKeyPair generate() {
        Ed25519KeyPairGenerator generator = new Ed25519KeyPairGenerator();
        generator.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        return generator.generateKeyPair();
    }

    private JSONObject sign(String payload, AsymmetricCipherKeyPair pair) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, (Ed25519PrivateKeyParameters) pair.getPrivate());
        signer.update(bytes, 0, bytes.length);
        return new JSONObject().put("kid", "k1").put("payload", Base64.toBase64String(bytes))
            .put("signature", Base64.toBase64String(signer.generateSignature()));
    }

    private static String payload(String uid, boolean premium, long iat, long exp) throws Exception {
        return new JSONObject().put("v", 1).put("aud", "com.terminow").put("uid", uid).put("premium", premium)
            .put("expiresAt", NOW + 30 * DAY).put("iat", iat).put("exp", exp).toString();
    }

    private TerminowSignedEntitlement.Result result(JSONObject signed, String uid) {
        return TerminowSignedEntitlement.verify(signed, uid, NOW, mKeys).result;
    }

    @Test
    public void aSignedEntitlementOfTheBackendIsValidForItsAccountUntilItsExpiry() throws Exception {
        TerminowSignedEntitlement.Verified verified = TerminowSignedEntitlement.verify(
            sign(payload("alice", true, NOW - 1000, NOW + DAY), mPair), "alice", NOW, mKeys);
        assertEquals(TerminowSignedEntitlement.Result.VALID, verified.result);
        assertTrue(verified.premium);
        assertEquals(NOW + 30 * DAY, verified.expiresAt);
    }

    @Test
    public void aChangedPayloadOrAnotherKeyIsRefused() throws Exception {
        JSONObject signed = sign(payload("alice", false, NOW - 1000, NOW + DAY), mPair);
        // Premium turned on by editing the cache on the phone
        signed.put("payload", Base64.toBase64String(payload("alice", true, NOW - 1000, NOW + DAY).getBytes(StandardCharsets.UTF_8)));
        assertEquals(TerminowSignedEntitlement.Result.BAD_SIGNATURE, result(signed, "alice"));
        assertEquals(TerminowSignedEntitlement.Result.BAD_SIGNATURE, result(sign(payload("alice", true, NOW - 1000, NOW + DAY), generate()), "alice"));
        assertEquals(TerminowSignedEntitlement.Result.UNKNOWN_KEY, result(sign(payload("alice", true, NOW, NOW + DAY), mPair).put("kid", "k9"), "alice"));
    }

    @Test
    public void anEntitlementOfAnotherAccountOrOutOfItsTimeIsRefused() throws Exception {
        assertEquals(TerminowSignedEntitlement.Result.WRONG_ACCOUNT, result(sign(payload("bob", true, NOW - 1000, NOW + DAY), mPair), "alice"));
        assertEquals(TerminowSignedEntitlement.Result.EXPIRED, result(sign(payload("alice", true, NOW - 2 * DAY, NOW - 1), mPair), "alice"));
        assertEquals("issued far in the future", TerminowSignedEntitlement.Result.EXPIRED,
            result(sign(payload("alice", true, NOW + DAY, NOW + 2 * DAY), mPair), "alice"));
        assertEquals("a little ahead is clock skew", TerminowSignedEntitlement.Result.VALID,
            result(sign(payload("alice", true, NOW + 60_000, NOW + DAY), mPair), "alice"));
    }

    /** The copy of the Play entitlement as the app keeps it, read at {@code NOW}. */
    private static JSONObject cached(String uid, boolean premium, JSONObject signed) throws Exception {
        JSONObject play = new JSONObject().put("premium", premium).put("expiresAt", NOW + 30 * DAY).put("subscriptionState", "SUBSCRIPTION_STATE_ACTIVE");
        JSONObject cached = new JSONObject().put("premium", premium).put("google_play", play).put("checked", NOW).put("uid", uid);
        return signed != null ? cached.put("signed", signed) : cached;
    }

    @Test
    public void aCachedEntitlementOfAnotherAccountIsNotUsed() throws Exception {
        assertEquals(null, TerminowEntitlement.decidePlay(cached("bob", true, null), "alice", NOW, false, mKeys));
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decidePlay(cached("alice", true, null), "alice", NOW, false, mKeys));
        // A copy from before the uid was kept is still the account's: it was cleared on every switch
        JSONObject old = cached("alice", true, null);
        old.remove("uid");
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decidePlay(old, "alice", NOW, false, mKeys));
    }

    @Test
    public void observingChangesNothingButKeepsTheResult() throws Exception {
        JSONObject forged = sign(payload("alice", true, NOW, NOW + DAY), generate());
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decidePlay(cached("alice", true, forged), "alice", NOW, false, mKeys));
        assertEquals(TerminowSignedEntitlement.Result.BAD_SIGNATURE, TerminowEntitlement.sLastSignedResult);
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decidePlay(cached("alice", true, null), "alice", NOW, false, mKeys));
        assertEquals(TerminowSignedEntitlement.Result.MISSING, TerminowEntitlement.sLastSignedResult);
    }

    @Test
    public void enforcedOnlyASignedPremiumIsPremiumAndAMissingOneAsksForACheck() throws Exception {
        JSONObject valid = sign(payload("alice", true, NOW - 1000, NOW + DAY), mPair);
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decidePlay(cached("alice", true, valid), "alice", NOW, true, mKeys));
        // Unsigned or forged: never Premium, but never taken away as if it ended either
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK, TerminowEntitlement.decidePlay(cached("alice", true, null), "alice", NOW, true, mKeys));
        JSONObject forged = sign(payload("alice", true, NOW, NOW + DAY), generate());
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK, TerminowEntitlement.decidePlay(cached("alice", true, forged), "alice", NOW, true, mKeys));
        // The backend can not be reached for days: the signed entitlement holds until its own expiry
        assertEquals(TerminowEntitlement.State.ACTIVE,
            TerminowEntitlement.decidePlay(cached("alice", true, valid), "alice", NOW + DAY / 2, true, mKeys));
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK,
            TerminowEntitlement.decidePlay(cached("alice", true, valid), "alice", NOW + DAY + 1, true, mKeys));
    }

    @Test
    public void missingOrBrokenDataIsNotValid() throws Exception {
        assertEquals(TerminowSignedEntitlement.Result.MISSING, result(null, "alice"));
        assertEquals(TerminowSignedEntitlement.Result.MALFORMED, result(new JSONObject().put("kid", "k1").put("payload", "!!").put("signature", "??"), "alice"));
        assertEquals("no keys in this build yet", TerminowSignedEntitlement.Result.UNKNOWN_KEY,
            TerminowSignedEntitlement.verify(sign(payload("alice", true, NOW, NOW + DAY), mPair), "alice", NOW).result);
    }

    @Test
    public void theRequestHashIsTheOneOfTheBackend() {
        // The same value is in firebase/functions/test/unit/integrity.test.js
        assertEquals("5b380f59549e17b56ae5d2b795ebf3f243a24379327ab26fa8d1f0470d6b57f6",
            TerminowIntegrity.requestHash("alice", "00112233445566778899aabbccddeeff"));
    }
}
