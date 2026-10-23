package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class TerminowAiBridgeTest {

    private final List<TerminowStore.Host> hosts = new ArrayList<>();
    private boolean unlocked = true;
    private long now = 1_000_000L;
    private TerminowAiBridge bridge;

    private TerminowStore.Host host(String uuid, String label, boolean enabled, String... caps) {
        TerminowStore.Host h = new TerminowStore.Host();
        h.uuid = uuid;
        h.label = label;
        h.address = "10.0.0." + uuid;
        h.username = "root";
        h.port = 22;
        h.password = "s3cret";
        h.aiAccess.enabled = enabled;
        h.aiAccess.approvalPolicy = "rules";
        for (String c : caps) h.aiAccess.capabilities.add(c);
        h.aiAccess.fingerprint = TerminowStore.aiFingerprint(h);
        return h;
    }

    @Before
    public void setUp() {
        hosts.clear();
        unlocked = true;
        bridge = new TerminowAiBridge(new TerminowAiBridge.Deps() {
            @Override public List<TerminowStore.Host> hosts() { return hosts; }
            @Override public List<TerminowStore.Identity> identities() { return new ArrayList<>(); }
            @Override public boolean keychainUnlocked() { return unlocked; }
            @Override public String savedPassphrase(String keyName) { return null; }
            @Override public File sshHome() { return new File("/tmp"); }
        }, () -> now);
    }

    @Test
    public void hostsListReturnsOnlyEnabledHostsAndNoSecret() {
        hosts.add(host("a", "Server A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        hosts.add(host("b", "Server B", false, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        JSONArray list = bridge.hostsList("ubuntu");
        assertEquals(1, list.length());
        JSONObject a = list.optJSONObject(0);
        assertEquals("a", a.optString("host_id"));
        assertEquals("Server A", a.optString("alias"));
        assertTrue(a.optBoolean("credential_available"));
        // The serialized host never carries the password or key material
        assertFalse(a.toString().contains("s3cret"));
    }

    @Test
    public void hostsListHonoursTheAllowedEnvironment() {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.envs.add("ubuntu");
        hosts.add(h);
        assertEquals(1, bridge.hostsList("ubuntu").length());
        assertEquals(0, bridge.hostsList("debian").length());
    }

    @Test
    public void authorizeDeniesUnknownOrDisabledHost() {
        hosts.add(host("a", "A", false, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        assertCode(TerminowAiError.HOST_NOT_AUTHORIZED, "ubuntu", "a");
        assertCode(TerminowAiError.HOST_NOT_AUTHORIZED, "ubuntu", "missing");
    }

    @Test
    public void authorizeDeniesMissingCapability() {
        hosts.add(host("a", "A", true)); // enabled, but no test_connection capability
        assertCode(TerminowAiError.CAPABILITY_DENIED, "ubuntu", "a");
    }

    @Test
    public void authorizeReportsLockedKeychain() {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        unlocked = false;
        assertCode(TerminowAiError.KEYCHAIN_LOCKED, "ubuntu", "a");
    }

    @Test
    public void askPolicyNeedsAGrantThatExpires() throws Exception {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        assertCode(TerminowAiError.APPROVAL_REQUIRED, "ubuntu", "a");
        bridge.grant("ubuntu", h, 60_000);
        // Now authorized
        assertEquals("root", bridge.authorize("ubuntu", "a", TerminowStore.AiAccess.CAP_TEST_CONNECTION).username);
        // After it expires, it needs approval again
        now += 61_000;
        assertCode(TerminowAiError.APPROVAL_REQUIRED, "ubuntu", "a");
    }

    @Test
    public void changingCredentialsRevokesGrantsAndNeedsApprovalAgain() throws Exception {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.grant("ubuntu", h, 60_000);
        assertEquals(1, bridge.activeGrantCount());
        // The user edits the address and saves: the grant no longer matches
        h.address = "10.9.9.9";
        bridge.onHostSaved(h);
        assertEquals(0, bridge.activeGrantCount());
    }

    @Test
    public void disablingAccessAndRemovalRevokeGrants() {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.grant("ubuntu", h, 60_000);
        h.aiAccess.enabled = false;
        bridge.onHostSaved(h);
        assertEquals(0, bridge.activeGrantCount());
        // And removal clears any that remain
        h.aiAccess.enabled = true;
        bridge.grant("ubuntu", h, 60_000);
        bridge.onHostRemoved("a");
        assertEquals(0, bridge.activeGrantCount());
    }

    @Test
    public void fingerprintStableOnRenameChangesOnEndpointOrCredentials() {
        TerminowStore.Host h = host("a", "A", true);
        String base = TerminowStore.aiFingerprint(h);
        h.label = "Renamed";
        h.group = "Prod";
        h.tags.add("x");
        assertEquals(base, TerminowStore.aiFingerprint(h)); // rename/move does not change identity
        h.port = 2222;
        assertNotEquals(base, TerminowStore.aiFingerprint(h));
        TerminowStore.Host h2 = host("a", "A", true);
        h2.password = "";
        assertNotEquals(TerminowStore.aiFingerprint(host("a", "A", true)), TerminowStore.aiFingerprint(h2));
    }

    private void assertCode(TerminowAiError expected, String env, String uuid) {
        try {
            bridge.authorize(env, uuid, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
            fail("Expected " + expected);
        } catch (TerminowAiError.Exception e) {
            assertEquals(expected, e.error);
        }
    }
}
