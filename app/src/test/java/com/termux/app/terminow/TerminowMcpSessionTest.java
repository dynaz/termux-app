package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.app.terminow.mcp.McpException;
import com.termux.app.terminow.mcp.McpHttpTransport;
import com.termux.app.terminow.mcp.McpSession;
import com.termux.app.terminow.mcp.McpToolResult;

import org.json.JSONObject;
import org.junit.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** {@link McpSession} over the HTTP transport, against {@link FakeMcpServer}. */
public class TerminowMcpSessionTest {

    private static McpSession session(String url, Map<String, String> headers) throws Exception {
        URL endpoint = new URL(url);
        McpSession session = new McpSession(() -> new McpHttpTransport(endpoint, headers, "test"), McpSession.Handshake.MODERN_FIRST,
            new TerminowMcpRedactor(headers.values()), "test");
        session.setRequestTimeout(3000);
        return session;
    }

    private static McpSession session(FakeMcpServer server) throws Exception {
        return session(server.getUrl(), Collections.emptyMap());
    }

    private static JSONObject echo(String message) throws Exception {
        return new JSONObject().put("message", message);
    }

    private static String text(McpToolResult result) {
        assertTrue(result.content.get(0) instanceof McpToolResult.Text);
        return ((McpToolResult.Text) result.content.get(0)).text;
    }

    @Test
    public void legacyConnectListsAllPagesAndCallsATool() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_JSON)) {
            McpSession session = session(server);
            List<McpSession.State> states = Collections.synchronizedList(new ArrayList<>());
            session.setListener(snapshot -> states.add(snapshot.state));
            session.connect();
            McpSession.Snapshot snapshot = session.getSnapshot();
            assertEquals(McpSession.State.READY, snapshot.state);
            assertEquals(McpSession.Era.LEGACY, snapshot.era);
            assertEquals("2025-11-25", snapshot.protocolVersion);
            assertEquals("fake-legacy", snapshot.serverName);
            assertEquals(FakeMcpServer.TOOL_COUNT, snapshot.tools.size());
            assertEquals(java.util.Arrays.asList(McpSession.State.CONNECTING, McpSession.State.READY), states);

            McpToolResult result = session.callTool("echo", echo("hello"), 3000, null);
            assertFalse(result.isError);
            assertEquals("Echo: hello", text(result));
            assertNotNull(result.structuredContent);
            assertEquals("hello", result.structuredContent.getString("echoed"));
            assertEquals("hello", result.raw.getJSONObject("structuredContent").getString("echoed"));

            session.close();
            assertEquals(McpSession.State.CLOSED, session.getSnapshot().state);
            assertEquals("DELETE", server.httpMethods.get(server.httpMethods.size() - 1));
        }
    }

    @Test
    public void modernConnectAndCallWithTheToolNameHeader() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.MODERN)) {
            McpSession session = session(server);
            session.connect();
            assertEquals(McpSession.Era.MODERN, session.getSnapshot().era);
            assertEquals("fake-modern", session.getSnapshot().serverName);
            assertEquals(FakeMcpServer.TOOL_COUNT, session.getSnapshot().tools.size());
            assertFalse(server.methods.contains("initialize"));
            assertEquals("Echo: hi", text(session.callTool("echo", echo("hi"), 3000, null)));
            session.close();
        }
    }

    @Test
    public void errorResultsImagesAndJsonRpcErrors() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_JSON)) {
            McpSession session = session(server);
            session.connect();

            McpToolResult failed = session.callTool("fail_tool", new JSONObject(), 3000, null);
            assertTrue(failed.isError);
            assertEquals("The tool failed", text(failed));

            McpToolResult image = session.callTool("image_tool", new JSONObject(), 3000, null);
            McpToolResult.Image png = (McpToolResult.Image) image.content.get(0);
            assertEquals("image/png", png.mimeType);
            assertEquals(FakeMcpServer.PNG_BASE64, png.data);
            assertEquals("file:///tmp/a.txt", ((McpToolResult.ResourceLink) image.content.get(1)).uri);

            try {
                session.callTool("no_such_tool", new JSONObject(), 3000, null);
                fail("A JSON-RPC error was returned as a result");
            } catch (McpException e) {
                assertEquals(McpException.Kind.SERVER_ERROR, e.kind);
                assertNotNull(e.rpcError);
                assertEquals(-32602, e.rpcError.getInt("code"));
            }
            // An error of one call leaves the session usable
            assertEquals(McpSession.State.READY, session.getSnapshot().state);
            session.close();
        }
    }

    @Test
    public void timeoutTellsTheServerToStop() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_JSON)) {
            McpSession session = session(server);
            session.connect();
            long start = System.currentTimeMillis();
            try {
                session.callTool("slow_tool", new JSONObject(), 500, null);
                fail("The slow tool did not time out");
            } catch (McpException e) {
                assertEquals(McpException.Kind.TIMEOUT, e.kind);
            }
            assertTrue(System.currentTimeMillis() - start < 3000);
            assertEquals(1, server.cancelled.size());
            assertEquals(McpSession.State.READY, session.getSnapshot().state);
            assertEquals("Echo: after", text(session.callTool("echo", echo("after"), 3000, null)));
            session.close();
        }
    }

    @Test
    public void cancelStopsTheCallAndTellsTheServer() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_JSON)) {
            McpSession session = session(server);
            session.connect();
            McpSession.CancelHandle cancel = new McpSession.CancelHandle();
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                cancel.cancel();
            }).start();
            long start = System.currentTimeMillis();
            try {
                session.callTool("slow_tool", new JSONObject(), 10_000, cancel);
                fail("The call was not cancelled");
            } catch (McpException e) {
                assertEquals(McpException.Kind.CANCELLED, e.kind);
            }
            assertTrue(System.currentTimeMillis() - start < 3000);
            assertEquals(1, server.cancelled.size());
            session.close();
        }
    }

    @Test
    public void cancelWhileTheEventStreamIsOpen() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_SSE)) {
            McpSession session = session(server);
            session.connect();
            McpSession.CancelHandle cancel = new McpSession.CancelHandle();
            new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                cancel.cancel();
            }).start();
            long start = System.currentTimeMillis();
            try {
                session.callTool("slow_tool", new JSONObject(), 10_000, cancel);
                fail("The call was not cancelled");
            } catch (McpException e) {
                assertEquals(McpException.Kind.CANCELLED, e.kind);
            }
            assertTrue("Cancel took " + (System.currentTimeMillis() - start) + " ms", System.currentTimeMillis() - start < 3000);
            assertEquals(1, server.cancelled.size());
            long closing = System.currentTimeMillis();
            session.close();
            assertTrue(System.currentTimeMillis() - closing < 3000);
        }
    }

    @Test
    public void serverPingIsAnsweredOnTheEventStream() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_SSE)) {
            McpSession session = session(server);
            session.connect();
            assertEquals("Echo: sse", text(session.callTool("echo", echo("sse"), 3000, null)));
            boolean answered = false;
            for (JSONObject response : server.responses)
                answered |= "srv-1".equals(response.opt("id")) && response.getJSONObject("result").length() == 0;
            assertTrue("The ping of the server was not answered with {}", answered);
            session.close();
        }
    }

    @Test
    public void unauthorizedIsMappedAndTheTokenIsNeverShown() throws Exception {
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.BEARER)) {
            McpSession session = session(server.getUrl(), Collections.singletonMap("Authorization", "Bearer wrong-secret-98765"));
            try {
                session.connect();
                fail("A wrong token was accepted");
            } catch (McpException e) {
                assertEquals(McpException.Kind.AUTH_FAILED, e.kind);
                assertEquals(401, e.httpStatus);
                assertFalse(e.getMessage(), e.getMessage().contains("wrong-secret-98765"));
            }
            McpSession.Snapshot snapshot = session.getSnapshot();
            assertEquals(McpSession.State.FAILED, snapshot.state);
            assertEquals(McpException.Kind.AUTH_FAILED, snapshot.error.kind);
        }
        try (FakeMcpServer server = new FakeMcpServer(FakeMcpServer.Mode.OAUTH)) {
            McpSession session = session(server);
            try {
                session.connect();
                fail("OAuth was not detected");
            } catch (McpException e) {
                assertEquals(McpException.Kind.OAUTH_REQUIRED, e.kind);
                assertEquals("https://auth.example/.well-known/oauth-protected-resource", e.resourceMetadata);
            }
        }
    }

    @Test
    public void redactionRemovesSecretsFromEveryMessage() {
        TerminowMcpRedactor redactor = new TerminowMcpRedactor(java.util.Arrays.asList("tok-live-7777", "env-secret-5555"));
        McpException e = new McpException(McpException.Kind.PROCESS_EXITED,
            "exit 1: using tok-live-7777, and API_KEY=env-secret-5555").redact(redactor);
        assertFalse(e.getMessage().contains("tok-live-7777"));
        assertFalse(e.getMessage().contains("env-secret-5555"));
        assertEquals(McpException.Kind.PROCESS_EXITED, e.kind);
    }

    @Test
    public void reconnectAfterTheServerRestarts() throws Exception {
        FakeMcpServer first = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_JSON);
        int port = first.getPort();
        McpSession session = session(first);
        session.connect();
        assertEquals("Echo: one", text(session.callTool("echo", echo("one"), 3000, null)));
        first.close();

        try (FakeMcpServer second = new FakeMcpServer(FakeMcpServer.Mode.LEGACY_JSON, port)) {
            // The new server does not know the session of the old one
            try {
                session.callTool("echo", echo("two"), 3000, null);
                fail("A call in a forgotten session worked");
            } catch (McpException e) {
                assertEquals(404, e.httpStatus);
            }
            assertEquals(McpSession.State.FAILED, session.getSnapshot().state);
            assertNotNull(session.getSnapshot().error);

            session.reconnect();
            assertEquals(McpSession.State.READY, session.getSnapshot().state);
            assertNull(session.getSnapshot().error);
            assertEquals("Echo: three", text(session.callTool("echo", echo("three"), 3000, null)));
            assertTrue(second.methods.contains("initialize"));
            session.close();
        }
    }

}
