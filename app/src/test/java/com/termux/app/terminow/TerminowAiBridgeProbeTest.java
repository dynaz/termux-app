package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-to-end test of the Local AI Bridge on the Android side, over a real loopback socket: an
 * in-process {@link TerminowAiBridgeServer} serving {@link TerminowAiMcpServer}, verified by
 * {@link TerminowAiBridgeProbe} exactly as the Ubuntu helper would reach it. It proves the self-test
 * actually exercises listener + authentication + MCP handshake + tool discovery + host authorization,
 * so a bound socket is never mistaken for a working integration. No real SSH is performed.
 */
public class TerminowAiBridgeProbeTest {

    private static TerminowAiSession sessionWith(String env, String token) {
        Map<String, String> store = new HashMap<>();
        TerminowAiSession session = new TerminowAiSession(new TerminowAiSession.Store() {
            @Override public void save(String envKey, String t) { store.put(envKey, t); }
            @Override public String load(String envKey) { return store.getOrDefault(envKey, ""); }
            @Override public void remove(String envKey) { store.remove(envKey); }
            @Override public Map<String, String> all() { return store; }
        });
        store.put(env, token);
        return session;
    }

    private static TerminowStore.Host authorizedHost(String env) {
        TerminowStore.Host host = new TerminowStore.Host();
        host.uuid = "host-1";
        host.label = "81 Drivo";
        host.address = "10.0.0.81";
        host.port = 22;
        host.username = "root";
        host.password = "s3cret";
        host.aiAccess.enabled = true;
        host.aiAccess.envs.add(env);
        host.aiAccess.capabilities.add(TerminowStore.AiAccess.CAP_TEST_CONNECTION);
        host.aiAccess.approvalPolicy = "rules";
        return host;
    }

    private static TerminowAiBridgeServer serverFor(TerminowAiSession session, List<TerminowStore.Host> hosts) {
        TerminowAiBridge bridge = new TerminowAiBridge(new TerminowAiBridge.Deps() {
            @Override public List<TerminowStore.Host> hosts() { return hosts; }
            @Override public List<TerminowStore.Identity> identities() { return new ArrayList<>(); }
            @Override public boolean keychainUnlocked() { return true; }
            @Override public String savedPassphrase(String keyName) { return null; }
            @Override public File sshHome() { return new File(System.getProperty("java.io.tmpdir")); }
        });
        return new TerminowAiBridgeServer(session,
            env -> new TerminowAiMcpServer(bridge, env, Collections::emptyList, "test"));
    }

    @Test
    public void selfTestVerifiesTheWholeAndroidPath() {
        String env = "ubuntu", token = "a".repeat(64);
        TerminowAiSession session = sessionWith(env, token);
        TerminowAiBridgeServer server = serverFor(session, Collections.singletonList(authorizedHost(env)));
        assertNull("listener starts", server.start(0));
        try {
            TerminowAiBridgeProbe.Result r = TerminowAiBridgeProbe.probe("127.0.0.1", server.getPort(), token, null);
            assertTrue("reachable", r.reachable);
            assertTrue("authenticated", r.authenticated);
            assertTrue("initialized", r.initialized);
            assertEquals(TerminowAiMcpServer.SERVER_NAME, r.serverName);
            assertTrue("tools discovered (hosts_list)", r.toolsDiscovered);
            assertTrue("ssh_test tool present", r.tools.contains("ssh_test"));
            assertEquals("one authorized host", 1, r.hostCount);
            assertTrue("overall ok", r.ok());
            assertNull(r.error);
        } finally {
            server.stop();
        }
    }

    @Test
    public void wrongTokenIsNotAuthenticated() {
        String env = "ubuntu";
        TerminowAiSession session = sessionWith(env, "a".repeat(64));
        TerminowAiBridgeServer server = serverFor(session, new ArrayList<>());
        assertNull(server.start(0));
        try {
            TerminowAiBridgeProbe.Result r = TerminowAiBridgeProbe.probe("127.0.0.1", server.getPort(), "b".repeat(64), null);
            assertTrue("reachable", r.reachable);
            assertFalse("rejected", r.authenticated);
            assertFalse(r.ok());
        } finally {
            server.stop();
        }
    }

    @Test
    public void notListeningIsReportedNotCrashed() {
        TerminowAiBridgeProbe.Result r = TerminowAiBridgeProbe.probe("127.0.0.1", 0, "a".repeat(64), null);
        assertFalse(r.reachable);
        assertFalse(r.ok());
        assertEquals("the bridge is not listening", r.error);
    }

    @Test
    public void onlyAuthorizedHostsAreListed() {
        String env = "ubuntu", token = "c".repeat(64);
        TerminowAiSession session = sessionWith(env, token);
        TerminowStore.Host mine = authorizedHost(env);
        TerminowStore.Host other = authorizedHost("other-env");   // allowed only for a different environment
        other.uuid = "host-2";
        other.aiAccess.envs.clear();
        other.aiAccess.envs.add("other-env");
        TerminowAiBridgeServer server = serverFor(session, java.util.Arrays.asList(mine, other));
        assertNull(server.start(0));
        try {
            TerminowAiBridgeProbe.Result r = TerminowAiBridgeProbe.probe("127.0.0.1", server.getPort(), token, null);
            assertEquals("only this environment's host", 1, r.hostCount);
        } finally {
            server.stop();
        }
    }
}
