package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class TerminowAiMcpServerTest {

    private final List<TerminowStore.Host> hosts = new ArrayList<>();
    private final List<String> secrets = new ArrayList<>();
    private TerminowAiMcpServer server;

    private TerminowStore.Host host(String uuid, String label, boolean enabled, String... caps) {
        TerminowStore.Host h = new TerminowStore.Host();
        h.uuid = uuid;
        h.label = label;
        h.address = "10.0.0.5";
        h.username = "root";
        h.password = "s3cretpw";
        h.aiAccess.enabled = enabled;
        h.aiAccess.approvalPolicy = "rules";
        for (String c : caps) h.aiAccess.capabilities.add(c);
        h.aiAccess.fingerprint = TerminowStore.aiFingerprint(h);
        return h;
    }

    @Before
    public void setUp() {
        hosts.clear();
        secrets.clear();
        TerminowAiBridge bridge = new TerminowAiBridge(new TerminowAiBridge.Deps() {
            @Override public List<TerminowStore.Host> hosts() { return hosts; }
            @Override public List<TerminowStore.Identity> identities() { return new ArrayList<>(); }
            @Override public boolean keychainUnlocked() { return true; }
            @Override public String savedPassphrase(String keyName) { return null; }
            @Override public File sshHome() { return new File("/tmp"); }
        });
        server = new TerminowAiMcpServer(bridge, "ubuntu", "client-test", () -> (Collection<String>) secrets, "1.0");
    }

    private JSONObject rpc(String method, JSONObject params, Object id) {
        JSONObject m = new JSONObject();
        try {
            m.put("jsonrpc", "2.0").put("method", method);
            if (id != null) m.put("id", id);
            if (params != null) m.put("params", params);
        } catch (Exception e) { throw new RuntimeException(e); }
        return m;
    }

    @Test
    public void initializeReportsServerAndTools() {
        JSONObject resp = server.handle(rpc("initialize", new JSONObject(), 1));
        assertNotNull(resp);
        JSONObject result = resp.optJSONObject("result");
        assertEquals(TerminowAiMcpServer.SERVER_NAME, result.optJSONObject("serverInfo").optString("name"));
        assertTrue(result.optJSONObject("capabilities").has("tools"));
    }

    @Test
    public void notificationsGetNoReply() {
        assertNull(server.handle(rpc("notifications/initialized", null, null)));
    }

    @Test
    public void toolsListHasHostsListAndSshTest() {
        JSONObject resp = server.handle(rpc("tools/list", new JSONObject(), 2));
        String text = resp.toString();
        assertTrue(text.contains("hosts_list"));
        assertTrue(text.contains("ssh_test"));
        // the selection tools are advertised too
        assertTrue(text.contains("select_host"));
        assertTrue(text.contains("bridge_status"));
        // host_id is still an accepted argument (now optional)
        assertTrue(text.contains("host_id"));
    }

    @Test
    public void hostsListToolReturnsAllowedHostsWithoutSecrets() {
        hosts.add(host("a", "Server A", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        JSONObject params = new JSONObject();
        try { params.put("name", "hosts_list").put("arguments", new JSONObject()); } catch (Exception ignored) {}
        JSONObject resp = server.handle(rpc("tools/call", params, 3));
        JSONObject result = resp.optJSONObject("result");
        assertFalse(result.optBoolean("isError"));
        String text = result.optJSONArray("content").optJSONObject(0).optString("text");
        assertTrue(text.contains("Server A"));
        assertTrue(text.contains("\"host_id\":\"a\""));
        assertFalse(text.contains("s3cretpw"));
    }

    @Test
    public void sshTestOnUnauthorizedHostReturnsStructuredError() {
        hosts.add(host("a", "A", false, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        JSONObject params = new JSONObject();
        try { params.put("name", "ssh_test").put("arguments", new JSONObject().put("host_id", "a")); } catch (Exception ignored) {}
        JSONObject resp = server.handle(rpc("tools/call", params, 4));
        JSONObject result = resp.optJSONObject("result");
        assertTrue(result.optBoolean("isError"));
        String text = result.optJSONArray("content").optJSONObject(0).optString("text");
        assertTrue(text.contains("HOST_NOT_AUTHORIZED"));
    }

    @Test
    public void outgoingTextRedactsKnownSecrets() {
        // A label that happens to contain a stored secret must be masked on the way out
        hosts.add(host("a", "token-abcd1234", true, TerminowStore.AiAccess.CAP_TEST_CONNECTION));
        secrets.add("abcd1234");
        JSONObject params = new JSONObject();
        try { params.put("name", "hosts_list").put("arguments", new JSONObject()); } catch (Exception ignored) {}
        JSONObject resp = server.handle(rpc("tools/call", params, 5));
        String text = resp.optJSONObject("result").optJSONArray("content").optJSONObject(0).optString("text");
        assertFalse(text.contains("abcd1234"));
        assertTrue(text.contains(TerminowMcpRedactor.REDACTED));
    }
}
