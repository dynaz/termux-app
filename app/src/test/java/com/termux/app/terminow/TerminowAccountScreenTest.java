package com.termux.app.terminow;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What Account & Premium and the crown of the top bar show is decided from the server-verified
 * entitlement only; signing in or out never gives Premium or removes anything of the device.
 */
public class TerminowAccountScreenTest {

    private MemoryPreferences mPreferences;
    private TerminowStore mStore;

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
        mPreferences = new MemoryPreferences();
        mStore = new TerminowStore(mPreferences);
    }

    private TerminowAccount.Session googleSession() {
        TerminowAccount.Session session = new TerminowAccount.Session();
        session.uid = "uid1";
        session.email = "dev@example.com";
        session.emailVerified = true;
        session.refreshToken = "refresh";
        session.displayName = "Dev Example";
        session.photoUrl = "https://lh3.googleusercontent.com/a/photo";
        return session;
    }

    private static TerminowAccount.Premium premium(boolean active, String source) {
        TerminowAccount.Premium premium = new TerminowAccount.Premium();
        premium.premium = active;
        premium.plan = active ? "TermiNow Premium" : "";
        premium.source = source;
        return premium;
    }

    @Test
    public void signingInWithGoogleGivesNoPremium() {
        assertEquals(TerminowEntitlement.State.SIGNED_OUT, TerminowEntitlement.get(mStore));
        mStore.setAccount(googleSession());
        // Signed in and verified by Google, but not Premium until the server says so
        assertFalse(TerminowAccountUi.isPremium(mStore));
        assertEquals(TerminowEntitlement.State.UNKNOWN, TerminowEntitlement.get(mStore));
        assertEquals(TerminowAccountScreen.Source.NONE, TerminowAccountScreen.sourceOf(mStore));
        mStore.setPremium(premium(false, ""));
        assertEquals(TerminowEntitlement.State.FREE, TerminowEntitlement.get(mStore));
    }

    @Test
    public void theNameAndPhotoOfTheAccountAreKept() {
        mStore.setAccount(googleSession());
        TerminowAccount.Session loaded = new TerminowStore(mPreferences).getAccount();
        assertEquals("Dev Example", loaded.displayName);
        assertEquals("https://lh3.googleusercontent.com/a/photo", loaded.photoUrl);
        assertEquals("dev@example.com", loaded.email);
    }

    @Test
    public void premiumComesFromWhereTheServerSays() throws Exception {
        mStore.setAccount(googleSession());
        mStore.setPremium(premium(true, "accmove"));
        assertTrue(TerminowAccountUi.isPremium(mStore));
        assertEquals(TerminowAccountScreen.Source.ACCMOVE, TerminowAccountScreen.sourceOf(mStore));

        JSONObject play = new JSONObject().put("google_play", new JSONObject().put("premium", true)
            .put("expiresAt", System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000).put("subscriptionState", "SUBSCRIPTION_STATE_ACTIVE"));
        mStore.setPlayEntitlement(play);
        assertEquals(TerminowAccountScreen.Source.BOTH, TerminowAccountScreen.sourceOf(mStore));

        mStore.setPremium(premium(false, ""));
        assertEquals(TerminowAccountScreen.Source.PLAY, TerminowAccountScreen.sourceOf(mStore));
        assertTrue(TerminowAccountUi.isPremium(mStore));

        // An expired Play subscription is not Premium
        play.getJSONObject("google_play").put("expiresAt", System.currentTimeMillis() - 1000);
        mStore.setPlayEntitlement(play);
        assertFalse(TerminowAccountUi.isPremium(mStore));
        assertEquals(TerminowAccountScreen.Source.NONE, TerminowAccountScreen.sourceOf(mStore));
    }

    @Test
    public void signingOutKeepsEverythingOfTheDevice() throws Exception {
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "server.example.com";
        host.username = "root";
        host.password = "hostpassword";
        mStore.setHosts(Collections.singletonList(host));
        TerminowStore.Identity identity = new TerminowStore.Identity();
        identity.id = "id1";
        identity.username = "deploy";
        identity.password = "idpw";
        mStore.setIdentities(Collections.singletonList(identity));
        mStore.setKeyLabel("id_ed25519", "Laptop key");
        mStore.setAccount(googleSession());
        mStore.setPremium(premium(true, "accmove"));
        assertTrue(TerminowAccountUi.isPremium(mStore));

        mStore.setAccount(null);

        assertNull(mStore.getAccount());
        assertFalse(TerminowAccountUi.isPremium(mStore));
        assertNull(mStore.getPremium());
        List<TerminowStore.Host> hosts = mStore.getHosts();
        assertEquals(1, hosts.size());
        assertEquals("hostpassword", hosts.get(0).password);
        assertEquals("idpw", mStore.getIdentities().get(0).password);
        assertEquals("Laptop key", mStore.getKeyLabel("id_ed25519"));
    }

    @Test
    public void purchasesAreForThisAccountOnly() {
        // The id that a purchase carries is a hash of the uid, so another account can not use it
        String own = TerminowBilling.accountId("uid1");
        assertEquals(own, TerminowBilling.accountId("uid1"));
        assertNotEquals(own, TerminowBilling.accountId("uid2"));
        assertFalse(own.contains("uid1"));
        assertTrue(TerminowBilling.manageUrl("com.terminow").startsWith("https://play.google.com/store/account/subscriptions?sku="));
    }

}
