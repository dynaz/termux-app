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
}
