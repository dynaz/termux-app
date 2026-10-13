package com.termux.app.terminow;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The proxy connector, run with bash against a SOCKS5 and an HTTP proxy in this test, which
 * lead to a target that talks first like sshd and then echoes.
 */
public class TerminowProxyTest {

    private final List<ServerSocket> mServers = new ArrayList<>();
    private File mDir;
    private File mScript;
    private int mTarget;

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("bash is needed", new File("/bin/bash").canExecute());
        mDir = Files.createTempDirectory("terminow-proxy").toFile();
        mScript = TerminowProxy.install(mDir);
        mTarget = serve(this::echo);
    }

    @After
    public void tearDown() throws IOException {
        for (ServerSocket server : mServers) server.close();
        if (mScript != null) {
            mScript.delete();
            // The connector is only rewritten when it changed
        }
    }

    private interface Handler {
        void handle(Socket socket) throws IOException;
    }

    private int serve(Handler handler) throws IOException {
        ServerSocket server = new ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"));
        mServers.add(server);
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    new Thread(() -> {
                        try {
                            handler.handle(socket);
                        } catch (IOException e) {
                            // The connection ended
                        }
                    }).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }

    private void echo(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write("SSH-2.0-test\r\n".getBytes(StandardCharsets.US_ASCII));
        pipe(socket.getInputStream(), out);
        socket.close();
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buffer = new byte[4096];
        int read;
        try {
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // Closed
        }
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] data = new byte[n];
        int total = 0;
        while (total < n) {
            int read = in.read(data, total, n - total);
            if (read == -1) throw new IOException("Closed");
            total += read;
        }
        return data;
    }

    private void relay(Socket client, String host, int port) throws IOException {
        Socket target = new Socket(host, port);
        Thread back = new Thread(() -> {
            try {
                pipe(target.getInputStream(), client.getOutputStream());
            } catch (IOException e) {
                // Closed
            }
        });
        back.start();
        pipe(client.getInputStream(), target.getOutputStream());
        target.close();
        client.close();
    }

    private void socks(Socket client, boolean wantsLogin) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        byte[] greeting = readN(in, 2);
        readN(in, greeting[1]);
        if (wantsLogin) {
            out.write(new byte[]{5, (byte) 0xFF});
            client.close();
            return;
        }
        out.write(new byte[]{5, 0});
        byte[] request = readN(in, 4);
        assertEquals(3, request[3]);
        String host = new String(readN(in, readN(in, 1)[0] & 0xFF), StandardCharsets.US_ASCII);
        byte[] portBytes = readN(in, 2);
        int port = ((portBytes[0] & 0xFF) << 8) | (portBytes[1] & 0xFF);
        out.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, portBytes[0], portBytes[1]});
        relay(client, host, port);
    }

    private void http(Socket client, boolean forbid) throws IOException {
        InputStream in = client.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) head.append((char) in.read());
        String[] target = head.toString().split(" ")[1].split(":");
        OutputStream out = client.getOutputStream();
        if (forbid) {
            out.write("HTTP/1.1 403 Forbidden\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            client.close();
            return;
        }
        out.write("HTTP/1.1 200 Connection established\r\nVia: test\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        relay(client, target[0], Integer.parseInt(target[1]));
    }

    private static class Run {
        int exitCode;
        String stdout = "";
        String stderr = "";
    }

    /** Run the connector, send a line, read the banner and the echo, then close stdin like ssh does. */
    private Run run(String type, int proxyPort, boolean expectEcho) throws Exception {
        Process process = new ProcessBuilder("bash", mScript.getPath(), type, "127.0.0.1", String.valueOf(proxyPort),
            "localhost", String.valueOf(mTarget)).start();
        Run run = new Run();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorThread = new Thread(() -> pipe(process.getErrorStream(), errors));
        errorThread.start();
        if (expectEcho) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.US_ASCII));
            run.stdout = reader.readLine() + "\n";
            process.getOutputStream().write("hello\n".getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().flush();
            run.stdout += reader.readLine() + "\n";
        }
        process.getOutputStream().close();
        assertTrue("the connector ended", process.waitFor(10, TimeUnit.SECONDS));
        errorThread.join(2000);
        run.exitCode = process.exitValue();
        run.stderr = errors.toString("UTF-8");
        return run;
    }

    @Test
    public void socks5() throws Exception {
        int proxy = serve(socket -> socks(socket, false));
        Run run = run("socks5", proxy, true);
        assertEquals("SSH-2.0-test\nhello\n", run.stdout.replace("\r", ""));
        assertEquals(0, run.exitCode);
    }

    @Test
    public void httpConnect() throws Exception {
        int proxy = serve(socket -> http(socket, false));
        Run run = run("http", proxy, true);
        assertEquals("SSH-2.0-test\nhello\n", run.stdout.replace("\r", ""));
        assertEquals(0, run.exitCode);
    }

    @Test
    public void failuresAreExplained() throws Exception {
        Run login = run("socks5", serve(socket -> socks(socket, true)), false);
        assertEquals(1, login.exitCode);
        assertTrue(login.stderr, login.stderr.contains("wants a login"));

        Run forbidden = run("http", serve(socket -> http(socket, true)), false);
        assertEquals(1, forbidden.exitCode);
        assertTrue(forbidden.stderr, forbidden.stderr.contains("403 Forbidden"));

        ServerSocket closed = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int unused = closed.getLocalPort();
        closed.close();
        Run refused = run("socks5", unused, false);
        assertEquals(1, refused.exitCode);
        assertTrue(refused.stderr, refused.stderr.contains("can not connect to the proxy"));
    }

    @Test
    public void installOnlyWritesWhenChanged() throws Exception {
        long modified = mScript.lastModified();
        Thread.sleep(1100);
        assertEquals(mScript, TerminowProxy.install(mDir));
        assertEquals(modified, mScript.lastModified());
    }

}
