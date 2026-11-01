package com.termux.app.terminow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.app.terminow.vnc.TerminowVncClient;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives a real {@link TerminowVncClient} against a scripted RFB server on a loopback socket: the
 * ProtocolVersion/security/auth handshake, ServerInit, and a Raw framebuffer update. This exercises
 * the actual bytes on the wire, not a mock of the client.
 */
public class TerminowVncClientTest {

    /** A listener that records the last resize and the rectangles it is told changed. */
    private static final class Recorder implements TerminowVncClient.Listener {
        int width, height, updates;
        @Override public void onFramebufferResized(int w, int h) { width = w; height = h; }
        @Override public void onFramebufferUpdated(int x, int y, int w, int h) { updates++; }
        @Override public void onBell() { }
        @Override public void onCutText(String text) { }
    }

    @Test
    public void reverseBitsMatchesTheVncKeySchedule() {
        assertEquals((byte) 0x80, TerminowVncClient.reverseBits((byte) 0x01));
        assertEquals((byte) 0x01, TerminowVncClient.reverseBits((byte) 0x80));
        assertEquals((byte) 0x55, TerminowVncClient.reverseBits((byte) 0xAA));
        assertEquals((byte) 0x0F, TerminowVncClient.reverseBits((byte) 0xF0));
    }

    @Test
    public void desResponseIsReversibleWithTheSameKey() throws Exception {
        byte[] challenge = new byte[16];
        for (int i = 0; i < 16; i++) challenge[i] = (byte) (i * 7 + 1);
        char[] password = "secret12".toCharArray();
        byte[] response = TerminowVncClient.encodeDesResponse(challenge, password);
        assertEquals(16, response.length);

        // Decrypt with the same bit-reversed key and recover the challenge: proves real DES/ECB was used.
        byte[] key = new byte[8];
        for (int i = 0; i < 8; i++) key[i] = TerminowVncClient.reverseBits((byte) password[i]);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "DES"));
        assertArrayEquals(challenge, cipher.doFinal(response));
    }

    @Test
    public void handshakeNoAuthThenRawUpdateRenders() throws Exception {
        Recorder recorder = new Recorder();
        runAgainstServer(recorder, null, server -> {
            greetAndSelectSecurity(server, 1);            // offer None
            server.out.writeInt(0);                       // SecurityResult OK
            server.in.readUnsignedByte();                 // ClientInit shared flag
            writeServerInit(server, 2, 2, "test");
            readClientConfig(server);                     // SetPixelFormat + SetEncodings
            server.in.skipBytes(10);                      // FramebufferUpdateRequest
            writeRedRawFrame(server);                     // 2x2 red
        });
        assertEquals(2, recorder.width);
        assertEquals(2, recorder.height);
        assertTrue("expected at least one framebuffer update", recorder.updates >= 1);
    }

    @Test
    public void vncAuthWithCorrectPasswordConnects() throws Exception {
        Recorder recorder = new Recorder();
        char[] password = "hunter22".toCharArray();
        runAgainstServer(recorder, password.clone(), server -> {
            greetAndSelectSecurity(server, 2);            // offer VNC auth
            byte[] challenge = new byte[16];
            for (int i = 0; i < 16; i++) challenge[i] = (byte) (i + 3);
            server.out.write(challenge);
            byte[] response = new byte[16];
            server.in.readFully(response);
            byte[] expected = TerminowVncClient.encodeDesResponse(challenge, password);
            server.out.writeInt(java.util.Arrays.equals(response, expected) ? 0 : 1);   // SecurityResult
            server.in.readUnsignedByte();                 // ClientInit
            writeServerInit(server, 4, 4, "auth");
            readClientConfig(server);
            server.in.skipBytes(10);
            writeRedRawFrame(server);
        });
        assertEquals(4, recorder.width);
        assertTrue(recorder.updates >= 1);
    }

    @Test
    public void vncAuthWithWrongPasswordFailsWithReason() throws Exception {
        AtomicReference<Exception> thrown = new AtomicReference<>();
        char[] wrong = "wrongpas".toCharArray();
        try {
            runAgainstServer(new Recorder(), wrong, server -> {
                greetAndSelectSecurity(server, 2);
                byte[] challenge = new byte[16];
                server.out.write(challenge);
                server.in.readFully(new byte[16]);        // the (wrong) response
                server.out.writeInt(1);                   // SecurityResult: failed
                byte[] reason = "Authentication failure".getBytes(StandardCharsets.US_ASCII);
                server.out.writeInt(reason.length);
                server.out.write(reason);
            }, false);
        } catch (Exception e) {
            thrown.set(e);
        }
        assertNotNull("handshake should have failed", thrown.get());
        assertTrue(thrown.get() instanceof TerminowVncClient.VncException);
        assertTrue(thrown.get().getMessage().contains("Authentication failure"));
    }

    // ---------------------------------------------------------------------------------------
    // Fake server scaffolding

    private interface Script { void run(Server server) throws IOException; }

    private static final class Server {
        DataInputStream in;
        DataOutputStream out;
    }

    private void runAgainstServer(Recorder recorder, char[] password, Script script) throws Exception {
        runAgainstServer(recorder, password, script, true);
    }

    /**
     * Runs {@code script} as the server on a background thread and the client on this thread. When
     * {@code pumpOnce} is true the client also reads one server message after the handshake. Any
     * client-side exception is rethrown to the test.
     */
    private void runAgainstServer(Recorder recorder, char[] password, Script script, boolean pumpOnce) throws Exception {
        try (ServerSocket listen = new ServerSocket()) {
            listen.setReuseAddress(true);
            listen.bind(new InetSocketAddress("127.0.0.1", 0));
            AtomicReference<IOException> serverError = new AtomicReference<>();
            Thread serverThread = new Thread(() -> {
                try (Socket accepted = listen.accept()) {
                    accepted.setTcpNoDelay(true);
                    Server server = new Server();
                    server.in = new DataInputStream(accepted.getInputStream());
                    server.out = new DataOutputStream(accepted.getOutputStream());
                    script.run(server);
                    server.out.flush();
                    // Keep the socket open briefly so the client can read what was written.
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { }
                } catch (IOException e) {
                    serverError.set(e);
                }
            }, "FakeVncServer");
            serverThread.start();

            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", listen.getLocalPort()), 3000);
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(4000);
                TerminowVncClient client = new TerminowVncClient(
                    socket.getInputStream(), socket.getOutputStream(), password, false, recorder);
                client.handshakeAndInit();
                if (pumpOnce) {
                    client.requestUpdate(false);
                    client.pump();
                }
            } finally {
                serverThread.join(4000);
            }
            if (serverError.get() != null) throw serverError.get();
        }
    }

    private void greetAndSelectSecurity(Server server, int type) throws IOException {
        server.out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        server.out.flush();
        server.in.readFully(new byte[12]);               // client's version
        server.out.writeByte(1);                         // one security type
        server.out.writeByte(type);
        server.out.flush();
        int chosen = server.in.readUnsignedByte();
        assertEquals(type, chosen);
    }

    private void writeServerInit(Server server, int width, int height, String name) throws IOException {
        server.out.writeShort(width);
        server.out.writeShort(height);
        server.out.write(new byte[16]);                  // pixel format; the client overrides it
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        server.out.writeInt(n.length);
        server.out.write(n);
        server.out.flush();
    }

    private void readClientConfig(Server server) throws IOException {
        server.in.skipBytes(20);                         // SetPixelFormat (1 + 3 pad + 16)
        server.in.skipBytes(2);                          // SetEncodings type + pad
        int count = server.in.readUnsignedShort();
        server.in.skipBytes(count * 4);
    }

    private void writeRedRawFrame(Server server) throws IOException {
        server.out.writeByte(0);                         // FramebufferUpdate
        server.out.writeByte(0);                         // padding
        server.out.writeShort(1);                        // one rectangle
        server.out.writeShort(0);                        // x
        server.out.writeShort(0);                        // y
        server.out.writeShort(2);                        // w
        server.out.writeShort(2);                        // h
        server.out.writeInt(0);                          // Raw
        for (int i = 0; i < 4; i++) { server.out.writeByte(0); server.out.writeByte(0); server.out.writeByte(255); server.out.writeByte(0); }
        server.out.flush();
    }
}
