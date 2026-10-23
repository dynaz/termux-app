package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.app.terminow.mcp.McpException;
import com.termux.app.terminow.mcp.McpSession;
import com.termux.app.terminow.mcp.McpStdioTransport;
import com.termux.app.terminow.mcp.McpToolResult;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link McpSession} over the stdio transport, against a small python server. */
public class TerminowMcpStdioTest {

    private static final String SECRET = "env-secret-value-4242";

    /** Starts the command as it is, like a plain JVM does. */
    static final McpStdioTransport.ProcessStarter PLAIN = (command, env, dir) -> {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        if (dir != null) builder.directory(new File(dir));
        return builder.start();
    };

    private String mScript;

    @Before
    public void findPython() throws Exception {
        boolean python;
        try {
            python = new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (java.io.IOException e) {
            python = false;
        }
        Assume.assumeTrue("python3 is needed for the stdio fixture", python);
        URL url = getClass().getResource("/mcp/stdio_server.py");
        File script = url != null ? new File(url.toURI()) : new File("src/test/resources/mcp/stdio_server.py");
        assertTrue(script.getPath(), script.isFile());
        mScript = script.getAbsolutePath();
    }

    private McpSession session(Map<String, String> env, String... command) {
        List<String> line = command.length > 0 ? Arrays.asList(command) : Arrays.asList("python3", "-u", mScript);
        McpSession session = new McpSession(() -> new McpStdioTransport(PLAIN, line, env, null), McpSession.Handshake.LEGACY_FIRST,
            new TerminowMcpRedactor(env.values()), "test");
        session.setRequestTimeout(10_000);
        return session;
    }

    private static String text(McpToolResult result) {
        return ((McpToolResult.Text) result.content.get(0)).text;
    }

    @Test
    public void initializeListAndCall() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("FIXTURE_NOISE", "1");
        McpSession session = session(env);
        session.connect();
        McpSession.Snapshot snapshot = session.getSnapshot();
        assertEquals(McpSession.State.READY, snapshot.state);
        assertEquals(McpSession.Era.LEGACY, snapshot.era);
        assertEquals("2025-11-25", snapshot.protocolVersion);
        assertEquals("fixture", snapshot.serverName);
        // Two pages of tools
        assertEquals(6, snapshot.tools.size());
        assertEquals("echo", snapshot.tools.get(0).name);
        assertTrue(snapshot.tools.get(0).isReadOnly());
        assertTrue(snapshot.tools.get(2).isDestructive());

        McpToolResult result = session.callTool("echo", new JSONObject().put("message", "hi"), 5000, null);
        assertEquals("Echo: hi", text(result));
        assertEquals("hi", result.structuredContent.getString("echoed"));
        session.close();
        assertEquals(McpSession.State.CLOSED, session.getSnapshot().state);
    }

    @Test
    public void anOlderVersionOfTheServerIsAccepted() throws Exception {
        McpSession session = session(Collections.singletonMap("FIXTURE_VERSION", "2025-06-18"));
        session.connect();
        assertEquals("2025-06-18", session.getSnapshot().protocolVersion);
        session.close();
    }

    @Test
    public void serverPingIsAnswered() throws Exception {
        McpSession session = session(Collections.emptyMap());
        session.connect();
        assertEquals("pong {}", text(session.callTool("ping_me", new JSONObject(), 5000, null)));
        session.close();
    }

    @Test
    public void timeoutAndCancelTellTheServer() throws Exception {
        McpSession session = session(Collections.emptyMap());
        session.connect();
        try {
            session.callTool("slow", new JSONObject(), 300, null);
            fail("The slow tool did not time out");
        } catch (McpException e) {
            assertEquals(McpException.Kind.TIMEOUT, e.kind);
        }
        McpSession.CancelHandle cancel = new McpSession.CancelHandle();
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
            cancel.cancel();
        }).start();
        try {
            session.callTool("slow", new JSONObject(), 10_000, cancel);
            fail("The call was not cancelled");
        } catch (McpException e) {
            assertEquals(McpException.Kind.CANCELLED, e.kind);
        }
        String ids = text(session.callTool("cancelled_ids", new JSONObject(), 5000, null));
        assertEquals(2, new org.json.JSONArray(ids).length());
        session.close();
    }

    @Test
    public void crashFailsTheSessionWithStderrAndReconnectStartsAgain() throws Exception {
        McpSession session = session(Collections.singletonMap("FIXTURE_SECRET", SECRET));
        session.connect();
        String firstPid = text(session.callTool("pid", new JSONObject(), 5000, null));
        try {
            session.callTool("crash", new JSONObject(), 5000, null);
            fail("The crash was not noticed");
        } catch (McpException e) {
            assertEquals(McpException.Kind.PROCESS_EXITED, e.kind);
            assertTrue(e.getMessage(), e.getMessage().contains("code 3"));
            assertTrue(e.getMessage(), e.getMessage().contains("fatal: the fixture crashed on purpose"));
            assertFalse("The env secret is in the message: " + e.getMessage(), e.getMessage().contains(SECRET));
        }
        McpSession.Snapshot failed = session.getSnapshot();
        assertEquals(McpSession.State.FAILED, failed.state);
        assertNotNull(failed.error);
        assertFalse(failed.error.getMessage().contains(SECRET));

        session.reconnect();
        assertEquals(McpSession.State.READY, session.getSnapshot().state);
        assertNotEquals(firstPid, text(session.callTool("pid", new JSONObject(), 5000, null)));
        session.close();
    }

    @Test
    public void aCommandThatDoesNotStart() {
        McpSession session = session(Collections.emptyMap(), "/nonexistent/terminow-mcp-server");
        try {
            session.connect();
            fail("A missing command started");
        } catch (McpException e) {
            assertEquals(McpException.Kind.PROCESS_START_FAILED, e.kind);
        }
        assertEquals(McpSession.State.FAILED, session.getSnapshot().state);
    }

    @Test
    public void aServerThatExitsAtOnce() {
        McpSession session = session(Collections.emptyMap(), "python3", "-c", "import sys; sys.stderr.write('missing API key\\n'); sys.exit(2)");
        try {
            session.connect();
            fail("A server that exited connected");
        } catch (McpException e) {
            assertEquals(McpException.Kind.PROCESS_EXITED, e.kind);
            assertTrue(e.getMessage(), e.getMessage().contains("missing API key"));
        }
    }

}
