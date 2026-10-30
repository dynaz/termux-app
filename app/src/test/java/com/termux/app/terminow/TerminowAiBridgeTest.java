package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
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

    // ----- Per-client host selection -----

    @Test
    public void selectHostThenResolveUsesItAndEchoesDetails() throws Exception {
        hosts.add(host("a", "Server A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        JSONObject chosen = bridge.selectHost("ubuntu", "c1", "a");
        assertEquals("a", chosen.optString("host_id"));
        assertEquals("Server A", chosen.optString("alias"));
        assertFalse(chosen.toString().contains("s3cret"));
        assertEquals("a", bridge.resolveHostId("ubuntu", "c1", ""));
    }

    @Test
    public void explicitHostIdAlwaysWinsOverSelection() throws Exception {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        hosts.add(host("b", "B", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        bridge.selectHost("ubuntu", "c1", "a");
        assertEquals("b", bridge.resolveHostId("ubuntu", "c1", "b"));
    }

    @Test
    public void resolveWithoutSelectionOrHostIdFailsLoudly() {
        try {
            bridge.resolveHostId("ubuntu", "c1", "");
            fail("Expected BAD_REQUEST");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.BAD_REQUEST, e.error);
        }
    }

    @Test
    public void selectionIsNeverSharedAcrossClients() throws Exception {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        bridge.selectHost("ubuntu", "c1", "a");
        // A different client in the same environment has no selection and must not inherit c1's.
        try {
            bridge.resolveHostId("ubuntu", "c2", "");
            fail("Expected BAD_REQUEST for the other client");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.BAD_REQUEST, e.error);
        }
        assertEquals("a", bridge.resolveHostId("ubuntu", "c1", ""));
    }

    @Test
    public void selectionExpiresAfterIdleTtlButIsRefreshedOnUse() throws Exception {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        bridge.selectHost("ubuntu", "c1", "a");
        now += TerminowAiBridge.SELECTION_TTL_MS / 2;
        assertEquals("a", bridge.resolveHostId("ubuntu", "c1", "")); // within TTL, refreshes it
        now += TerminowAiBridge.SELECTION_TTL_MS / 2 + 1; // < TTL since the refresh, so still valid
        assertEquals("a", bridge.resolveHostId("ubuntu", "c1", ""));
        now += TerminowAiBridge.SELECTION_TTL_MS + 1; // now idle past the TTL
        try {
            bridge.resolveHostId("ubuntu", "c1", "");
            fail("Expected expiry");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.BAD_REQUEST, e.error);
        }
    }

    @Test
    public void clearSelectionForgetsIt() throws Exception {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        bridge.selectHost("ubuntu", "c1", "a");
        bridge.clearSelection("ubuntu", "c1");
        try {
            bridge.resolveHostId("ubuntu", "c1", "");
            fail("Expected BAD_REQUEST");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.BAD_REQUEST, e.error);
        }
    }

    @Test
    public void selectHostRequiresARegisteredClient() {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        try {
            bridge.selectHost("ubuntu", null, "a");
            fail("Expected BAD_REQUEST");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.BAD_REQUEST, e.error);
        }
    }

    @Test
    public void selectHostRejectsAHostNotAvailableToTheEnvironment() {
        hosts.add(host("a", "A", false, TerminowStore.AiAccess.CAP_RUN_COMMAND)); // AI access off
        try {
            bridge.selectHost("ubuntu", "c1", "a");
            fail("Expected HOST_NOT_AUTHORIZED");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.HOST_NOT_AUTHORIZED, e.error);
        }
        try {
            bridge.selectHost("ubuntu", "c1", "missing");
            fail("Expected HOST_NOT_AUTHORIZED");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.HOST_NOT_AUTHORIZED, e.error);
        }
    }

    @Test
    public void selectedHostIdForReportsAClientSelectionForDisplay() throws Exception {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND));
        assertNull(bridge.selectedHostIdFor("ubuntu"));
        bridge.selectHost("ubuntu", "c1", "a");
        assertEquals("a", bridge.selectedHostIdFor("ubuntu"));
        // Scoped to the environment: another environment sees nothing.
        assertNull(bridge.selectedHostIdFor("debian"));
        // Expires with the selection.
        now += TerminowAiBridge.SELECTION_TTL_MS + 1;
        assertNull(bridge.selectedHostIdFor("ubuntu"));
    }

    @Test
    public void disablingOrRemovingTheHostDropsTheSelection() throws Exception {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_RUN_COMMAND);
        hosts.add(h);
        bridge.selectHost("ubuntu", "c1", "a");
        bridge.onHostRemoved("a");
        try {
            bridge.resolveHostId("ubuntu", "c1", "");
            fail("Expected the selection to be gone");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.BAD_REQUEST, e.error);
        }
    }
}
