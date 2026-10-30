package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TerminowAiBridgeServerTest {

    private final Map<String, String> tokenStore = new HashMap<>();
    private final List<TerminowStore.Host> hosts = new ArrayList<>();
    private TerminowAiSession session;
    private TerminowAiBridgeServer server;

    @Before
    public void setUp() {
        tokenStore.clear();
        hosts.clear();
        session = new TerminowAiSession(new TerminowAiSession.Store() {
            @Override public void save(String e, String t) { tokenStore.put(e, t); }
            @NonNull @Override public String load(String e) { return tokenStore.getOrDefault(e, ""); }
            @Override public void remove(String e) { tokenStore.remove(e); }
            @NonNull @Override public Map<String, String> all() { return new HashMap<>(tokenStore); }
        });
        TerminowAiBridge bridge = new TerminowAiBridge(new TerminowAiBridge.Deps() {
            @Override public List<TerminowStore.Host> hosts() { return hosts; }
            @Override public List<TerminowStore.Identity> identities() { return new ArrayList<>(); }
            @Override public boolean keychainUnlocked() { return true; }
            @Override public String savedPassphrase(String keyName) { return null; }
            @Override public File sshHome() { return new File("/tmp"); }
        });
        server = new TerminowAiBridgeServer(session,
            env -> new TerminowAiMcpServer(bridge, env, () -> (Collection<String>) new ArrayList<String>(), "test"));
    }

    @Test
    public void authParsesTokenLine() {
        String token = session.mint("ubuntu");
        assertEquals("ubuntu", TerminowAiBridgeServer.authenticatedEnvironment(session, "AUTH " + token));
        assertNull(TerminowAiBridgeServer.authenticatedEnvironment(session, "AUTH wrong"));
        assertNull(TerminowAiBridgeServer.authenticatedEnvironment(session, "hello"));
        assertNull(TerminowAiBridgeServer.authenticatedEnvironment(session, null));
    }

    @Test
    public void unauthenticatedConnectionIsRejected() throws Exception {
        StringWriter out = new StringWriter();
        String env = server.serve(new BufferedReader(new StringReader("")), out, "AUTH nope");
        assertNull(env);
        assertTrue(out.toString().startsWith("ERR"));
    }

    @Test
    public void authenticatedConnectionAnswersMcp() throws Exception {
        String token = session.mint("ubuntu");
        // An initialize request after the auth line
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n";
        StringWriter out = new StringWriter();
        String env = server.serve(new BufferedReader(new StringReader(input)), out, "AUTH " + token);
        assertEquals("ubuntu", env);
        String text = out.toString();
        assertTrue(text.startsWith("OK\n"));
        // The initialize response follows, naming the server
        assertTrue(text.contains(TerminowAiMcpServer.SERVER_NAME));
        assertTrue(text.contains("\"id\":1"));
    }

    @Test
    public void loopbackTransportServesAnAuthenticatedClientEndToEnd() throws Exception {
        String token = session.mint("ubuntu");
        assertNull(server.start(0));
        try {
            int port = server.getPort();
            assertTrue(port > 0);
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                Writer w = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                w.write("AUTH " + token + "\n");
                w.flush();
                assertEquals("OK", r.readLine());
                w.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n");
                w.flush();
                String response = r.readLine();
                assertTrue(response.contains(TerminowAiMcpServer.SERVER_NAME));
                assertTrue(response.contains("\"id\":1"));
            }
        } finally {
            server.stop();
        }
        assertTrue(!server.isRunning());
    }

    @Test
    public void loopbackRejectsABadTokenEndToEnd() throws Exception {
        session.mint("ubuntu");
        assertNull(server.start(0));
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            Writer w = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            w.write("AUTH not-a-real-token\n");
            w.flush();
            assertTrue(r.readLine().startsWith("ERR"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void malformedJsonGetsAParseError() throws Exception {
        String token = session.mint("ubuntu");
        StringWriter out = new StringWriter();
        server.serve(new BufferedReader(new StringReader("not json\n")), out, "AUTH " + token);
        assertTrue(out.toString().contains("-32700"));
    }

    /**
     * Bridge restart: the process-singleton listener is stopped and started again (a new port), and
     * the same per-environment token still authenticates. The grant lives in the session, not in the
     * socket, so a restart must not force the user to re-pair.
     */
    @Test
    public void sameTokenAuthenticatesAcrossABridgeRestart() throws Exception {
        String token = session.mint("ubuntu");
        assertNull(server.start(0));
        assertTrue(server.getPort() > 0);
        server.stop();
        assertTrue(!server.isRunning());
        assertEquals(0, server.getPort());

        assertNull(server.start(0));
        try {
            int secondPort = server.getPort();
            assertTrue(secondPort > 0);
            // A fresh listener; the helper re-reads the live port before it reconnects.
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), secondPort)) {
                Writer w = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                w.write("AUTH " + token + "\n");
                w.flush();
                assertEquals("OK", r.readLine());
            }
        } finally {
            server.stop();
        }
    }

    /**
     * Terminal reconnect leaves a stale connection for an environment. closeConnectionsOf() must end
     * the live connection (not only a finished one), so the reconnecting helper is not shut out and
     * no second session lingers under the same grant.
     */
    @Test
    public void closeConnectionsOfEndsALiveConnection() throws Exception {
        String token = session.mint("ubuntu");
        assertNull(server.start(0));
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            Writer w = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            w.write("AUTH " + token + "\n");
            w.flush();
            assertEquals("OK", r.readLine());

            // Round-trip one request so the server has entered its serve loop and mapped the socket
            // to its environment (the mapping is set just before the loop). No arbitrary sleep.
            w.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n");
            w.flush();
            assertTrue(r.readLine().contains("\"id\":1"));

            // A reconnect/revoke closes the live connection the way disconnectClaude does.
            server.closeConnectionsOf("ubuntu");

            // The client sees the stream end (null), not a hang.
            assertNull(r.readLine());
        } finally {
            server.stop();
        }
    }

    /** A revoke of one environment must not close another environment's live connection. */
    @Test
    public void closeConnectionsOfLeavesOtherEnvironmentsAlone() throws Exception {
        String ubuntuToken = session.mint("ubuntu");
        session.mint("debian");
        assertNull(server.start(0));
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
            Writer w = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            w.write("AUTH " + ubuntuToken + "\n");
            w.flush();
            assertEquals("OK", r.readLine());

            // Establish the mapped, live connection first.
            w.write("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"initialize\",\"params\":{}}\n");
            w.flush();
            assertTrue(r.readLine().contains("\"id\":7"));

            // Revoking a different environment must not touch this one.
            server.closeConnectionsOf("debian");

            w.write("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"initialize\",\"params\":{}}\n");
            w.flush();
            String response = r.readLine();
            assertTrue(response != null && response.contains("\"id\":8"));
        } finally {
            server.stop();
        }
    }

    /**
     * Regression: the listener must bind the IPv4 loopback (127.0.0.1) — the address the Ubuntu helper
     * and the in-app self-test both connect to. {@link InetAddress#getLoopbackAddress()} returns ::1 on
     * an IPv6-preferring device, which bound the socket on ::1 while every client used 127.0.0.1:
     * isRunning() read true yet the connect was refused. Asserting the bound address (not the host's
     * own loopback) guards this on any JVM, and a literal-IPv4 client must be served end to end.
     */
    @Test
    public void listenerBindsIpv4LoopbackForLiteralClients() throws Exception {
        String token = session.mint("ubuntu");
        assertNull(server.start(0));
        try {
            assertEquals("127.0.0.1", server.boundHostAddress());
            InetAddress ipv4 = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            try (Socket socket = new Socket(ipv4, server.getPort())) {
                Writer w = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                BufferedReader r = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                w.write("AUTH " + token + "\n");
                w.flush();
                assertEquals("OK", r.readLine());
            }
        } finally {
            server.stop();
        }
        assertEquals("", server.boundHostAddress());
    }
}
