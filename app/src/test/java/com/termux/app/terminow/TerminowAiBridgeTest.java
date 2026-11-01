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

    private static TerminowAiBridge.Caller caller(String env, String clientId) {
        return new TerminowAiBridge.Caller(env, clientId, "Claude Code", env + " · session");
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
        // Default: no interactive approval granted (fail closed), like a build with no popup.
        bridge.setApprovalGate(spec -> TerminowAiApprovals.Decision.DENY);
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
        assertCode(TerminowAiError.HOST_NOT_AUTHORIZED, caller("ubuntu", "c1"), "a");
        assertCode(TerminowAiError.HOST_NOT_AUTHORIZED, caller("ubuntu", "c1"), "missing");
    }

    @Test
    public void authorizeDeniesMissingCapability() {
        hosts.add(host("a", "A", true)); // enabled, but no test_connection capability
        assertCode(TerminowAiError.CAPABILITY_DENIED, caller("ubuntu", "c1"), "a");
    }

    @Test
    public void authorizeReportsLockedKeychain() {
        hosts.add(host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        unlocked = false;
        assertCode(TerminowAiError.KEYCHAIN_LOCKED, caller("ubuntu", "c1"), "a");
    }

    @Test
    public void rulesPolicyNeedsNoGrant() throws Exception {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "rules";
        hosts.add(h);
        assertEquals("root", bridge.authorize(caller("ubuntu", "c1"), "a",
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, null).username);
    }

    @Test
    public void askPolicyNeedsAGrantThatExpires() throws Exception {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        // No grant and the gate denies -> approval required.
        assertCode(TerminowAiError.APPROVAL_REQUIRED, caller("ubuntu", "c1"), "a");
        bridge.grantSession("ubuntu", "c1", h, TerminowStore.AiAccess.CAP_TEST_CONNECTION, 60_000);
        assertEquals("root", bridge.authorize(caller("ubuntu", "c1"), "a",
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, null).username);
        now += 61_000; // the grant expires
        assertCode(TerminowAiError.APPROVAL_REQUIRED, caller("ubuntu", "c1"), "a");
    }

    @Test
    public void allowOnceAuthorizesOnlyThisCallAndStoresNoGrant() throws Exception {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.setApprovalGate(spec -> TerminowAiApprovals.Decision.ALLOW_ONCE);
        assertEquals("root", bridge.authorize(caller("ubuntu", "c1"), "a",
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, null).username);
        // Nothing remembered: a one-time allow leaves no grant behind.
        assertEquals(0, bridge.activeGrantCount());
    }

    @Test
    public void allowSessionRemembersForThatClientOnly() throws Exception {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "session";
        hosts.add(h);
        final int[] prompts = {0};
        bridge.setApprovalGate(spec -> { prompts[0]++; return TerminowAiApprovals.Decision.ALLOW_SESSION; });
        // First call prompts and is granted.
        bridge.authorize(caller("ubuntu", "c1"), "a", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null);
        // Second call from the same client reuses the grant: no new prompt.
        bridge.authorize(caller("ubuntu", "c1"), "a", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null);
        assertEquals(1, prompts[0]);
        // A different client/session is NOT authorized by c1's grant: it prompts again.
        bridge.authorize(caller("ubuntu", "c2"), "a", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null);
        assertEquals(2, prompts[0]);
    }

    @Test
    public void grantIsScopedToTheCapabilityItWasApprovedFor() throws Exception {
        TerminowStore.Host h = host("a", "A", true,
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, TerminowStore.AiAccess.CAP_RUN_COMMAND);
        h.aiAccess.approvalPolicy = "session";
        hosts.add(h);
        bridge.grantSession("ubuntu", "c1", h, TerminowStore.AiAccess.CAP_TEST_CONNECTION, 60_000);
        // test_connection is now allowed without a prompt...
        assertEquals("root", bridge.authorize(caller("ubuntu", "c1"), "a",
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, null).username);
        // ...but run_command still needs its own approval (the gate denies here).
        assertCodeCap(TerminowAiError.APPROVAL_REQUIRED, caller("ubuntu", "c1"), "a",
            TerminowStore.AiAccess.CAP_RUN_COMMAND, "whoami");
    }

    @Test
    public void deniedApprovalThrowsApprovalRequired() {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.setApprovalGate(spec -> TerminowAiApprovals.Decision.DENY);
        assertCode(TerminowAiError.APPROVAL_REQUIRED, caller("ubuntu", "c1"), "a");
    }

    @Test
    public void cancelledApprovalThrowsCancelled() {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.setApprovalGate(spec -> TerminowAiApprovals.Decision.CANCELLED);
        assertCodeCap(TerminowAiError.CANCELLED, caller("ubuntu", "c1"), "a",
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, null);
    }

    @Test
    public void approvalIsRevalidatedBeforeItProceeds() {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        // The gate "allows", but while it waited the host's AI access was turned off.
        bridge.setApprovalGate(spec -> { h.aiAccess.enabled = false; return TerminowAiApprovals.Decision.ALLOW_ONCE; });
        assertCodeCap(TerminowAiError.HOST_NOT_AUTHORIZED, caller("ubuntu", "c1"), "a",
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, null);
    }

    @Test
    public void changingCredentialsRevokesGrantsAndNeedsApprovalAgain() {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.grantSession("ubuntu", "c1", h, TerminowStore.AiAccess.CAP_TEST_CONNECTION, 60_000);
        assertEquals(1, bridge.activeGrantCount());
        h.address = "10.9.9.9"; // the user edits the address and saves
        bridge.onHostSaved(h);
        assertEquals(0, bridge.activeGrantCount());
    }

    @Test
    public void disablingAccessAndRemovalRevokeGrants() {
        TerminowStore.Host h = host("a", "A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.grantSession("ubuntu", "c1", h, TerminowStore.AiAccess.CAP_TEST_CONNECTION, 60_000);
        h.aiAccess.enabled = false;
        bridge.onHostSaved(h);
        assertEquals(0, bridge.activeGrantCount());
        h.aiAccess.enabled = true;
        bridge.grantSession("ubuntu", "c1", h, TerminowStore.AiAccess.CAP_TEST_CONNECTION, 60_000);
        bridge.onHostRemoved("a");
        assertEquals(0, bridge.activeGrantCount());
    }

    @Test
    public void envGrantAuthorizesAnyClientAndIsCredentialBound() throws Exception {
        TerminowStore.Host h = host("a", "A", true,
            TerminowStore.AiAccess.CAP_TEST_CONNECTION, TerminowStore.AiAccess.CAP_RUN_COMMAND);
        h.aiAccess.approvalPolicy = "ask";
        hosts.add(h);
        bridge.grantEnv("ubuntu", h, 60_000); // a managed task grant
        // Any client of the env may use either capability without a prompt.
        assertEquals("root", bridge.authorize(caller("ubuntu", "cX"), "a",
            TerminowStore.AiAccess.CAP_RUN_COMMAND, "ls").username);
        // A credential change drops it.
        h.address = "10.9.9.9";
        bridge.onHostSaved(h);
        assertEquals(0, bridge.activeGrantCount());
    }

    @Test
    public void fingerprintStableOnRenameChangesOnEndpointOrCredentials() {
        TerminowStore.Host h = host("a", "A", true);
        String base = TerminowStore.aiFingerprint(h);
        h.label = "Renamed";
        h.group = "Prod";
        h.tags.add("x");
        assertEquals(base, TerminowStore.aiFingerprint(h));
        h.port = 2222;
        assertNotEquals(base, TerminowStore.aiFingerprint(h));
        TerminowStore.Host h2 = host("a", "A", true);
        h2.password = "";
        assertNotEquals(TerminowStore.aiFingerprint(host("a", "A", true)), TerminowStore.aiFingerprint(h2));
    }

    private void assertCode(TerminowAiError expected, TerminowAiBridge.Caller caller, String uuid) {
        assertCodeCap(expected, caller, uuid, TerminowStore.AiAccess.CAP_TEST_CONNECTION, null);
    }

    private void assertCodeCap(TerminowAiError expected, TerminowAiBridge.Caller caller, String uuid,
                               String capability, String command) {
        try {
            bridge.authorize(caller, uuid, capability, command);
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
        assertEquals("a", bridge.resolveHostId("ubuntu", "c1", ""));
        now += TerminowAiBridge.SELECTION_TTL_MS / 2 + 1;
        assertEquals("a", bridge.resolveHostId("ubuntu", "c1", ""));
        now += TerminowAiBridge.SELECTION_TTL_MS + 1;
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
        hosts.add(host("a", "A", false, TerminowStore.AiAccess.CAP_RUN_COMMAND));
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
        assertNull(bridge.selectedHostIdFor("debian"));
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
