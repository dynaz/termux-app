package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

public class TerminowEntitlementTest {

    private static final long DAY = 24L * 60 * 60 * 1000;

    private static JSONObject premium(boolean active, String due, long checked) throws Exception {
        return new JSONObject().put("premium", active).put("plan", "TermiNow Premium")
            .put("nextDueDate", due == null ? JSONObject.NULL : due).put("checked", checked);
    }

    @Test
    public void states() throws Exception {
        long now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).parse("2026-10-12 12:00").getTime();
        assertEquals(TerminowEntitlement.State.SIGNED_OUT, TerminowEntitlement.decide(false, false, null, now));
        assertEquals(TerminowEntitlement.State.UNKNOWN, TerminowEntitlement.decide(true, false, null, now));
        assertEquals(TerminowEntitlement.State.UNKNOWN, TerminowEntitlement.decide(true, true, null, now));
        assertEquals(TerminowEntitlement.State.FREE, TerminowEntitlement.decide(true, true, premium(false, null, now), now));
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decide(true, true, premium(true, "2027-10-12", now - DAY), now));
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decide(true, true, premium(true, null, now - 6 * DAY), now));
        // Due today is still active, due yesterday is expired
        assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decide(true, true, premium(true, "2026-10-12", now), now));
        assertEquals(TerminowEntitlement.State.EXPIRED, TerminowEntitlement.decide(true, true, premium(true, "2026-10-11", now), now));
        // Offline for longer than a week needs a new check
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK, TerminowEntitlement.decide(true, true, premium(true, null, now - 8 * DAY), now));
        // A check time in the future, like after changing the clock, is not trusted
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK, TerminowEntitlement.decide(true, true, premium(true, null, now + 30 * DAY), now));
        // An unverified email never has Premium, whatever was cached
        assertEquals(TerminowEntitlement.State.UNKNOWN, TerminowEntitlement.decide(true, false, premium(true, null, now), now));
    }

    private static JSONObject play(boolean premium, String state, long expires, long checked) throws Exception {
        return new JSONObject().put("premium", premium).put("checked", checked).put("google_play",
            new JSONObject().put("premium", premium).put("subscriptionState", state).put("expiresAt", expires));
    }

    @Test
    public void googlePlayIsPremiumUntilItsVerifiedExpiryAndOnlyForAWeekOffline() throws Exception {
        long now = 1_800_000_000_000L;
        assertEquals(TerminowEntitlement.State.ACTIVE,
            TerminowEntitlement.decidePlay(play(true, "SUBSCRIPTION_STATE_ACTIVE", now + 30 * DAY, now - DAY), now));
        // Past the verified expiry, whatever the copy says
        assertEquals(TerminowEntitlement.State.EXPIRED,
            TerminowEntitlement.decidePlay(play(true, "SUBSCRIPTION_STATE_ACTIVE", now - 1, now - DAY), now));
        // A yearly subscription still needs a check within a week offline
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK,
            TerminowEntitlement.decidePlay(play(true, "SUBSCRIPTION_STATE_ACTIVE", now + 300 * DAY, now - 8 * DAY), now));
        assertEquals(TerminowEntitlement.State.EXPIRED,
            TerminowEntitlement.decidePlay(play(false, "SUBSCRIPTION_STATE_ON_HOLD", now - 1, now), now));
        assertEquals(null, TerminowEntitlement.decidePlay(null, now));
    }

    @Test
    public void eitherSourceGivesPremiumAndNeitherErasesTheOther() {
        TerminowEntitlement.State active = TerminowEntitlement.State.ACTIVE;
        assertEquals(active, TerminowEntitlement.combine(TerminowEntitlement.State.FREE, active));
        assertEquals(active, TerminowEntitlement.combine(active, TerminowEntitlement.State.EXPIRED));
        assertEquals(TerminowEntitlement.State.EXPIRED, TerminowEntitlement.combine(TerminowEntitlement.State.FREE, TerminowEntitlement.State.EXPIRED));
        assertEquals(TerminowEntitlement.State.NEEDS_CHECK, TerminowEntitlement.combine(TerminowEntitlement.State.FREE, TerminowEntitlement.State.NEEDS_CHECK));
        assertEquals(TerminowEntitlement.State.FREE, TerminowEntitlement.combine(TerminowEntitlement.State.FREE, null));
    }

    @Test
    public void theAnswerOfTheServerDecidesNotThePurchaseScreen() throws Exception {
        TerminowStore.sTestsWithoutKeystore = true;
        try {
            TerminowStore store = new TerminowStore(new MemoryPreferences());
            store.setPlayPurchaseStatus(TerminowPlayPurchases.Status.VERIFYING.name(), null, false);
            // Waiting for the check: buying again is not offered
            org.junit.Assert.assertTrue(TerminowPlayPurchases.isPurchaseInProgress(store));
            org.junit.Assert.assertNull(TerminowEntitlement.decidePlay(store.getPlayEntitlement(), System.currentTimeMillis()));

            TerminowPlayPurchases.applyResult(store, new JSONObject().put("status", "PENDING").put("entitlement", new JSONObject().put("premium", false)));
            assertEquals(TerminowPlayPurchases.Status.PENDING, TerminowPlayPurchases.getStatus(store));
            org.junit.Assert.assertTrue(TerminowPlayPurchases.isPurchaseInProgress(store));

            long expires = System.currentTimeMillis() + 30 * DAY;
            TerminowPlayPurchases.applyResult(store, new JSONObject().put("status", "ACTIVE").put("test", true).put("entitlement",
                new JSONObject().put("premium", true).put("google_play", new JSONObject().put("premium", true).put("expiresAt", expires))));
            assertEquals(TerminowPlayPurchases.Status.ACTIVE, TerminowPlayPurchases.getStatus(store));
            org.junit.Assert.assertFalse(TerminowPlayPurchases.isPurchaseInProgress(store));
            assertEquals(TerminowEntitlement.State.ACTIVE, TerminowEntitlement.decidePlay(store.getPlayEntitlement(), System.currentTimeMillis()));
            org.junit.Assert.assertTrue(store.getPlayPurchaseStatus().getBoolean("test"));
        } finally {
            TerminowStore.sTestsWithoutKeystore = false;
        }
    }

}
