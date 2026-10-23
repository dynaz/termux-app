package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.app.terminow.mcp.McpException;

import org.json.JSONObject;
import org.junit.Test;

import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class TerminowMcpClientTest {

    private static TerminowMcpClient.Result test(FakeMcpServer server, Map<String, String> headers) throws Exception {
        TerminowMcpClient client = new TerminowMcpClient(new URL(server.getUrl()), headers, "test");
        client.setTimeouts(2000, 1000);
        return client.test();
    }

    private static void assertNoToolCalled(FakeMcpServer server, TerminowMcpClient.Result result) {
        for (String method : server.methods) {
            assertFalse("A test must never call a tool, but sent " + method, method.equals("tools/call"));
            assertTrue("Unexpected method " + method, TerminowMcpClient.ALLOWED_METHODS.contains(method));
        }
        assertFalse(result.methodsSent.contains("tools/call"));
    }

    private static void assertAllTools(TerminowMcpClient.Result result) {
        assertEquals(FakeMcpServer.TOOL_COUNT, result.tools.size());
        assertEquals("delete_everything", result.tools.get(0).name);
        assertEquals("Tool number 6", result.tools.get(6).description);
        assertTrue(result.hasTools);
    }

    @Test
    public void modernServerUsesDiscoverAndPaginates() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.MODERN)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertTrue(result.message, result.ok);
            assertEquals("modern", result.era);
            assertEquals("2026-07-28", result.protocolVersion);
            assertEquals("fake-modern", result.serverName);
            assertEquals("9.1", result.serverVersion);
            assertAllTools(result);
            // server/discover, then 3 pages of tools, and no initialize
            assertEquals(java.util.Arrays.asList("server/discover", "tools/list", "tools/list", "tools/list"), server.methods);
            assertNoToolCalled(server, result);
        }
    }

    @Test
    public void legacyServerFallsBackToInitializeAndEndsTheSession() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_JSON)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertTrue(result.message, result.ok);
            assertEquals("legacy", result.era);
            assertEquals("2025-11-25", result.protocolVersion);
            assertEquals("fake-legacy", result.serverName);
            assertEquals("Use carefully", result.instructions);
            assertAllTools(result);
            assertEquals(java.util.Arrays.asList("server/discover", "initialize", "notifications/initialized",
                "tools/list", "tools/list", "tools/list"), server.methods);
            // The session is ended
            assertEquals("DELETE", server.httpMethods.get(server.httpMethods.size() - 1));
            assertNoToolCalled(server, result);
        }
    }

    @Test
    public void legacySseResponsesWithServerMessagesFirst() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_SSE)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertTrue(result.message, result.ok);
            assertEquals("2025-06-18", result.protocolVersion);
            assertAllTools(result);
            assertNoToolCalled(server, result);
        }
    }

    @Test
    public void unsupportedModernVersion() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.MODERN_FUTURE_ONLY)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertFalse(result.ok);
            assertEquals(McpException.Kind.UNSUPPORTED_VERSION, result.failure);
            assertTrue(result.message, result.message.contains("2099-01-01"));
            // A modern error must not make the client fall back to initialize
            assertFalse(server.methods.contains("initialize"));
        }
    }

    @Test
    public void oauthIsDetectedNotFaked() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.OAUTH)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertFalse(result.ok);
            assertEquals(McpException.Kind.OAUTH_REQUIRED, result.failure);
            assertEquals("https://auth.example/.well-known/oauth-protected-resource", result.resourceMetadata);
            assertTrue(result.tools.isEmpty());
        }
    }

    @Test
    public void bearerTokenIsSentAndRejectedTokensAreRedacted() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.BEARER)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + FakeMcpServer.GOOD_TOKEN);
            TerminowMcpClient.Result result = test(server, headers);
            assertTrue(result.message, result.ok);
            assertAllTools(result);
        }
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.BEARER)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer wrong-secret-98765");
            TerminowMcpClient.Result result = test(server, headers);
            assertFalse(result.ok);
            assertEquals(McpException.Kind.AUTH_FAILED, result.failure);
            assertFalse(result.message, result.message.contains("wrong-secret-98765"));
        }
    }

    @Test
    public void timeoutAndInvalidResponses() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.SLOW)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertEquals(McpException.Kind.TIMEOUT, result.failure);
        }
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.HTML)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            // An HTTP 200 alone is never a success
            assertFalse(result.ok);
            assertEquals(McpException.Kind.INVALID_RESPONSE, result.failure);
        }
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.OLD_SSE)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertEquals(McpException.Kind.OLD_SSE_TRANSPORT, result.failure);
        }
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.REDIRECT)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertEquals(McpException.Kind.REDIRECT, result.failure);
            assertEquals(1, server.httpMethods.size());
        }
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LOOPING_CURSOR)) {
            TerminowMcpClient.Result result = test(server, Collections.emptyMap());
            assertEquals(McpException.Kind.INVALID_RESPONSE, result.failure);
        }
    }

    @Test
    public void cancellation() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.SLOW)) {
            TerminowMcpClient client = new TerminowMcpClient(new URL(server.getUrl()), Collections.emptyMap(), "test");
            client.setTimeouts(2000, 10000);
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                client.cancel();
            }).start();
            long start = System.currentTimeMillis();
            TerminowMcpClient.Result result = client.test();
            assertEquals(McpException.Kind.CANCELLED, result.failure);
            assertTrue(System.currentTimeMillis() - start < 2500);
        }
    }

    @Test
    public void noOtherMethodCanBeSent() throws Exception {
        java.util.List<String> sent = new java.util.ArrayList<>();
        com.termux.app.terminow.mcp.McpTransport transport = TerminowMcpClient.readOnly(
            new com.termux.app.terminow.mcp.McpHttpTransport(new URL("https://example.com/mcp"), Collections.emptyMap(), "test"), sent);
        JSONObject call = new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call");
        try {
            transport.request(call, "tools/call", 1000);
            fail("tools/call was allowed");
        } catch (IllegalArgumentException expected) {
            // Expected
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    public void urlValidation() {
        assertNull(TerminowMcpClient.validateUrl("https://mcp.example.com/mcp", false));
        assertNull(TerminowMcpClient.validateUrl("https://mcp.example.com:8443/a/b?x=1", false));
        assertNotNull(TerminowMcpClient.validateUrl("", false));
        assertNotNull(TerminowMcpClient.validateUrl("mcp.example.com/mcp", false));
        assertNotNull(TerminowMcpClient.validateUrl("ftp://mcp.example.com", false));
        assertNotNull(TerminowMcpClient.validateUrl("https://user:pass@mcp.example.com/mcp", false));
        assertNotNull(TerminowMcpClient.validateUrl("https://mcp.example.com/mcp#x", false));
        assertNotNull(TerminowMcpClient.validateUrl("https://mcp.example .com/mcp", false));
        // HTTP only to this device, and only when allowed
        assertNotNull(TerminowMcpClient.validateUrl("http://127.0.0.1:8080/mcp", false));
        assertNull(TerminowMcpClient.validateUrl("http://127.0.0.1:8080/mcp", true));
        assertNull(TerminowMcpClient.validateUrl("http://localhost:8080/mcp", true));
        assertNotNull(TerminowMcpClient.validateUrl("http://192.168.1.5:8080/mcp", true));
        assertNotNull(TerminowMcpClient.validateUrl("http://mcp.example.com/mcp", true));
    }

}
