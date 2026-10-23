package com.termux.app.terminow;

import android.content.Context;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.net.socket.local.ILocalSocketManager;
import com.termux.shared.net.socket.local.LocalClientSocket;
import com.termux.shared.net.socket.local.LocalSocketManager;
import com.termux.shared.net.socket.local.LocalSocketManagerClientBase;
import com.termux.shared.net.socket.local.LocalSocketRunConfig;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The listener of the Local AI Bridge: a private AF_UNIX/SOCK_STREAM socket on the app's own
 * filesystem, reached from a Linux environment through a proot bind. Each connection first
 * authenticates with the per-environment token (so it is bound to one environment), then speaks
 * MCP to {@link TerminowAiMcpServer}. The token is the authority: the peer's uid is only a second
 * check, since local processes can share the app's uid, so it never stands in for the token.
 *
 * <p>Connections are tracked so the bridge can close them on shutdown or when a token is revoked.
 * The authentication handshake has a short timeout; a connection that does not authenticate in
 * time is dropped.</p>
 */
public final class TerminowAiBridgeServer {

    public static final String TITLE = "terminow-ai-bridge";
    static final int HANDSHAKE_TIMEOUT_MS = 15_000;
    private static final int BACKLOG = 4;

    /** Builds the MCP server for an environment, with the current bridge and secrets. */
    public interface ServerFactory {
        @NonNull TerminowAiMcpServer forEnvironment(@NonNull String environmentKey);
    }

    private final Context mContext;
    private final String mSocketPath;
    private final TerminowAiSession mSession;
    private final ServerFactory mFactory;
    /** Open connections, so they can be closed on shutdown or revocation. */
    private final Set<Connection> mConnections = Collections.newSetFromMap(new ConcurrentHashMap<>());

    @Nullable private LocalSocketManager mManager;

    public TerminowAiBridgeServer(@NonNull Context context, @NonNull String socketPath, @NonNull TerminowAiSession session,
                                  @NonNull ServerFactory factory) {
        mContext = context;
        mSocketPath = socketPath;
        mSession = session;
        mFactory = factory;
    }

    public synchronized boolean isRunning() {
        return mManager != null;
    }

    @NonNull
    public String getSocketPath() {
        return mSocketPath;
    }

    /** Start listening. Returns {@code null} on success, or a message describing why it failed. */
    @Nullable
    public synchronized String start() {
        if (mManager != null) return null;
        try {
            java.io.File socket = new java.io.File(mSocketPath);
            java.io.File dir = socket.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return "Could not create " + dir;
            // A stale socket file from a previous run would stop bind
            if (socket.exists() && !socket.delete()) return "Could not remove the old socket";
            LocalSocketRunConfig config = new LocalSocketRunConfig(TITLE, mSocketPath, new Client());
            config.setBacklog(BACKLOG);
            LocalSocketManager manager = new LocalSocketManager(mContext, config);
            Error error = manager.start();
            if (error != null) return error.getErrorLogString();
            mManager = manager;
            try {
                // Only the app's own user may reach it on the filesystem
                new java.io.File(mSocketPath).setReadable(false, false);
                new java.io.File(mSocketPath).setWritable(false, false);
                new java.io.File(mSocketPath).setReadable(true, true);
                new java.io.File(mSocketPath).setWritable(true, true);
            } catch (RuntimeException ignored) {
                // Best effort; the directory is already app-private
            }
            return null;
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            return String.valueOf(e.getMessage());
        }
    }

    public synchronized void stop() {
        for (Connection connection : mConnections) connection.close();
        mConnections.clear();
        if (mManager != null) {
            mManager.stop();
            mManager = null;
        }
        try {
            new java.io.File(mSocketPath).delete();
        } catch (RuntimeException ignored) {
            // Nothing more to do
        }
    }

    /** Close the open connections of an environment, so a revoked token stops working at once. */
    public void closeConnectionsOf(@NonNull String environmentKey) {
        for (Connection connection : mConnections)
            if (environmentKey.equals(connection.environmentKey)) connection.close();
    }

    public int openConnectionCount() {
        return mConnections.size();
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
        String environmentKey = authenticatedEnvironment(mSession, firstLine);
        if (environmentKey == null) {
            out.write("ERR unauthorized\n");
            out.flush();
            return null;
        }
        out.write("OK\n");
        out.flush();
        TerminowAiMcpServer server = mFactory.forEnvironment(environmentKey);
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
    private static JSONObject handleLine(@NonNull TerminowAiMcpServer server, @NonNull String line) {
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

    // ---------------------------------------------------------------------------------------
    // Socket wiring
    // ---------------------------------------------------------------------------------------

    private final class Client extends LocalSocketManagerClientBase {
        @Override
        public void onClientAccepted(@NonNull LocalSocketManager manager, @NonNull LocalClientSocket clientSocket) {
            // The token, read next, is the real authority. The peer uid is only noted: a local
            // process can share the app uid, so it is never enough on its own.
            int peerUid = clientSocket.getPeerCred() != null ? clientSocket.getPeerCred().uid : -1;
            if (peerUid != -1 && peerUid != Process.myUid()) {
                clientSocket.closeClientSocket(false);
                return;
            }
            Connection connection = new Connection(clientSocket);
            mConnections.add(connection);
            manager.startLocalSocketManagerClientThread(connection);
        }

        @Override
        protected String getLogTag() {
            return "TerminowAiBridge";
        }
    }

    /** One accepted connection: the handshake has a timeout, then the MCP loop runs until it ends. */
    private final class Connection implements Runnable {
        final LocalClientSocket socket;
        volatile String environmentKey = "";

        Connection(@NonNull LocalClientSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(socket.getInputStreamReader());
                 Writer out = socket.getOutputStreamWriter()) {
                String firstLine = readLineWithTimeout(in, HANDSHAKE_TIMEOUT_MS);
                String env = serve(in, out, firstLine);
                if (env != null) environmentKey = env;
            } catch (IOException ignored) {
                // The client went away
            } finally {
                close();
            }
        }

        void close() {
            mConnections.remove(this);
            socket.closeClientSocket(false);
        }
    }

    /** Read one line, giving up after {@code timeoutMs}, so an unauthenticated peer can not linger. */
    @Nullable
    private static String readLineWithTimeout(@NonNull BufferedReader in, int timeoutMs) {
        final String[] holder = new String[1];
        Thread reader = new Thread(() -> {
            try {
                holder[0] = in.readLine();
            } catch (IOException ignored) {
                // Left null
            }
        }, "TerminowAiBridge handshake");
        reader.setDaemon(true);
        reader.start();
        try {
            reader.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }
}
