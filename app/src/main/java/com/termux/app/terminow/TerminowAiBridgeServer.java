package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The listener of the Local AI Bridge, on an authenticated loopback (127.0.0.1) port. A Linux
 * environment shares the device's network namespace through proot, so the environment's MCP helper
 * reaches it at 127.0.0.1 with no extra mount. Only loopback peers are served, and every
 * connection must authenticate with the per-environment token before any tool call; the token is
 * the authority, not the address.
 *
 * <p>Loopback is used because the filesystem Unix-socket path needs a native library that is not
 * part of this build, so it could not bind (LocalSocket error 100). The authorization is the same
 * either way.</p>
 */
public final class TerminowAiBridgeServer {

    static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int BACKLOG = 8;

    /**
     * Answers one JSON-RPC message, the same contract {@link TerminowAiMcpServer} and
     * {@link TerminowMcpBridgeMcpServer} both meet, so this loopback transport serves either bridge
     * without a parallel listener. {@code null} is returned for a notification, which has no reply.
     */
    public interface Handler {
        @Nullable JSONObject handle(@NonNull JSONObject message);
    }

    /** Builds the MCP handler for one authenticated peer (an environment or a paired client). */
    public interface ServerFactory {
        @NonNull Handler forEnvironment(@NonNull String environmentKey);
    }

    private final TerminowAiSession mSession;
    private final ServerFactory mFactory;
    /** Open connections and the environment each serves, so they can be closed on shutdown or revocation. */
    private final Map<Socket, String> mConnections = new ConcurrentHashMap<>();

    @Nullable private ServerSocket mServerSocket;
    @Nullable private Thread mAcceptThread;
    private volatile int mPort;

    public TerminowAiBridgeServer(@NonNull TerminowAiSession session, @NonNull ServerFactory factory) {
        mSession = session;
        mFactory = factory;
    }

    public synchronized boolean isRunning() {
        return mServerSocket != null && !mServerSocket.isClosed();
    }

    /** The loopback port it listens on, 0 when not running. */
    public int getPort() {
        return isRunning() ? mPort : 0;
    }

    /**
     * Start listening on 127.0.0.1, at {@code wantedPort} if it is free, otherwise any free port.
     * Returns {@code null} on success, or the full (sanitized) reason it could not start.
     */
    @Nullable
    public synchronized String start(int wantedPort) {
        if (isRunning()) return null;
        InetAddress loopback = InetAddress.getLoopbackAddress();
        ServerSocket socket = null;
        try {
            socket = bind(loopback, wantedPort);
        } catch (IOException first) {
            if (wantedPort > 0) {
                try {
                    socket = bind(loopback, 0);
                } catch (IOException second) {
                    return "Could not open the loopback port: " + second;
                }
            } else {
                return "Could not open the loopback port: " + first;
            }
        }
        mServerSocket = socket;
        mPort = socket.getLocalPort();
        ServerSocket started = socket;
        mAcceptThread = new Thread(() -> acceptLoop(started), "TerminowAiBridge accept");
        mAcceptThread.setDaemon(true);
        mAcceptThread.start();
        return null;
    }

    @NonNull
    private static ServerSocket bind(@NonNull InetAddress loopback, int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(loopback, port), BACKLOG);
        return socket;
    }

    public synchronized void stop() {
        for (Socket socket : mConnections.keySet()) closeSocket(socket);
        mConnections.clear();
        if (mServerSocket != null) {
            try {
                mServerSocket.close();
            } catch (IOException ignored) {
                // Already closing
            }
            mServerSocket = null;
        }
    }

    /** Close the open connections of an environment, so a revoked token stops working at once. */
    public void closeConnectionsOf(@NonNull String environmentKey) {
        for (Map.Entry<Socket, String> entry : mConnections.entrySet())
            if (environmentKey.equals(entry.getValue())) closeSocket(entry.getKey());
    }

    public int openConnectionCount() {
        return mConnections.size();
    }

    private void acceptLoop(@NonNull ServerSocket serverSocket) {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                break; // closed
            }
            // Only the loopback is served; the token is still required next
            if (socket.getInetAddress() == null || !socket.getInetAddress().isLoopbackAddress()) {
                closeSocket(socket);
                continue;
            }
            mConnections.put(socket, "");
            Thread thread = new Thread(() -> handle(socket), "TerminowAiBridge conn");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void handle(@NonNull Socket socket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
            // The handshake is bounded; after it the MCP loop blocks until the client closes
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            String firstLine;
            try {
                firstLine = in.readLine();
            } catch (SocketException | java.net.SocketTimeoutException e) {
                firstLine = null;
            }
            socket.setSoTimeout(0);
            // Map the socket to its environment the moment it authenticates, not when it ends, so a
            // revoke or a reconnect can close this live connection through closeConnectionsOf().
            serve(in, out, firstLine, env -> mConnections.put(socket, env));
        } catch (IOException ignored) {
            // The client went away
        } finally {
            mConnections.remove(socket);
            closeSocket(socket);
        }
    }

    private static void closeSocket(@NonNull Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing more to do
        }
    }

    // ---------------------------------------------------------------------------------------
    // Protocol (testable without a socket)
    // ---------------------------------------------------------------------------------------

    /** The environment a first line "AUTH &lt;token&gt;" belongs to, {@code null} if it is not valid. */
    @Nullable
    static String authenticatedEnvironment(@NonNull TerminowAiSession session, @Nullable String firstLine) {
        if (firstLine == null) return null;
        String line = firstLine.trim();
        if (!line.startsWith("AUTH ")) return null;
        String token = line.substring("AUTH ".length()).trim();
        return session.environmentOf(token);
    }

    /**
     * Serve one connection: authenticate, then answer MCP messages until the stream ends. Returns
     * the environment it served, or {@code null} if authentication failed. Pure enough to test
     * with a reader and writer.
     */
    @Nullable
    String serve(@NonNull BufferedReader in, @NonNull Writer out, @Nullable String firstLine) throws IOException {
        return serve(in, out, firstLine, null);
    }

    @Nullable
    String serve(@NonNull BufferedReader in, @NonNull Writer out, @Nullable String firstLine,
                 @Nullable java.util.function.Consumer<String> onAuthenticated) throws IOException {
        String environmentKey = authenticatedEnvironment(mSession, firstLine);
        if (environmentKey == null) {
            out.write("ERR unauthorized\n");
            out.flush();
            return null;
        }
        out.write("OK\n");
        out.flush();
        if (onAuthenticated != null) onAuthenticated.accept(environmentKey);
        Handler server = mFactory.forEnvironment(environmentKey);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            JSONObject response = handleLine(server, line);
            if (response != null) {
                out.write(response.toString());
                out.write("\n");
                out.flush();
            }
        }
        return environmentKey;
    }

    @Nullable
    private static JSONObject handleLine(@NonNull Handler server, @NonNull String line) {
        JSONObject message;
        try {
            message = new JSONObject(line);
        } catch (org.json.JSONException e) {
            try {
                return new JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
                    .put("error", new JSONObject().put("code", -32700).put("message", "Parse error"));
            } catch (org.json.JSONException ignored) {
                return null;
            }
        }
        return server.handle(message);
    }
}
