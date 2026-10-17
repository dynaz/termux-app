package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.mcp.McpException;
import com.termux.app.terminow.mcp.McpHttpTransport;
import com.termux.app.terminow.mcp.McpSession;
import com.termux.app.terminow.mcp.McpTool;
import com.termux.app.terminow.mcp.McpTransport;

import org.json.JSONObject;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Test an MCP server: connect, list its tools, and disconnect, with {@link McpSession}.
 *
 * Only the methods in {@link #ALLOWED_METHODS} can ever be sent, so a test can not call a tool or
 * change anything on the server.
 */
public class TerminowMcpClient {

    /** The only methods that a test sends. Never `tools/call` or anything else with side effects. */
    static final Set<String> ALLOWED_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "server/discover", "initialize", "notifications/initialized", "tools/list")));

    public static class Result {
        public boolean ok;
        @Nullable public McpException.Kind failure;
        /** What went wrong, with the secrets of the profile removed. */
        public String message = "";
        public int httpStatus;
        /** "modern" or "legacy". */
        public String era = "";
        public String protocolVersion = "";
        public String serverName = "";
        public String serverVersion = "";
        public String instructions = "";
        public boolean hasTools;
        public final List<McpTool> tools = new ArrayList<>();
        /** Whether there were more tools than are kept. */
        public boolean toolsTruncated;
        /** For {@link McpException.Kind#OAUTH_REQUIRED}, the protected resource metadata URL, if given. */
        public String resourceMetadata = "";
        /** The methods that were sent, in order, to show that nothing else was. */
        public final List<String> methodsSent = new ArrayList<>();
    }

    @Nullable private final URL mUrl;
    @Nullable private final Map<String, String> mHeaders;
    @Nullable private final McpTransport.Factory mFactory;
    private final McpSession.Handshake mHandshake;
    private final TerminowMcpRedactor mRedactor;
    private final String mClientVersion;
    private int mConnectTimeoutMs = 15000;
    private long mReadTimeoutMs = 30000;

    private volatile boolean mCancelled;
    @Nullable private volatile McpSession mSession;

    /**
     * @param url The MCP endpoint, already validated with {@link #validateUrl(String, boolean)}.
     * @param headers The headers to send with every request, like `Authorization`.
     */
    public TerminowMcpClient(@NonNull URL url, @NonNull Map<String, String> headers, @NonNull String clientVersion) {
        mUrl = url;
        mHeaders = headers;
        mFactory = null;
        mHandshake = McpSession.Handshake.MODERN_FIRST;
        mRedactor = new TerminowMcpRedactor(headers.values());
        mClientVersion = clientVersion;
    }

    /** A test over another transport, like a local stdio server. */
    public TerminowMcpClient(@NonNull McpTransport.Factory factory, @NonNull McpSession.Handshake handshake,
                             @NonNull Collection<String> secrets, @NonNull String clientVersion) {
        mUrl = null;
        mHeaders = null;
        mFactory = factory;
        mHandshake = handshake;
        mRedactor = new TerminowMcpRedactor(secrets);
        mClientVersion = clientVersion;
    }

    public void setTimeouts(int connectTimeoutMs, int readTimeoutMs) {
        mConnectTimeoutMs = connectTimeoutMs;
        mReadTimeoutMs = readTimeoutMs;
    }

    /** Stop a running test from another thread. */
    public void cancel() {
        mCancelled = true;
        McpSession session = mSession;
        if (session != null) session.close();
    }

    /**
     * Validate an MCP endpoint URL. Returns {@code null} if it is valid, or why it is not.
     * HTTP is only allowed to the local device, and only if {@code allowLocalHttp}.
     */
    @Nullable
    public static String validateUrl(@Nullable String text, boolean allowLocalHttp) {
        if (text == null || text.trim().isEmpty()) return "Enter the URL of the MCP endpoint";
        text = text.trim();
        if (text.contains(" ") || text.contains("\n")) return "The URL can not contain spaces";
        URL url;
        try {
            url = new URL(text);
            url.toURI();
        } catch (Exception e) {
            return "This is not a valid URL";
        }
        String protocol = url.getProtocol().toLowerCase(Locale.ROOT);
        String host = url.getHost();
        if (host == null || host.isEmpty()) return "The URL has no host";
        if (url.getUserInfo() != null) return "Put credentials in the authentication headers, not in the URL";
        if (url.getRef() != null) return "The URL can not have a #fragment";
        if (protocol.equals("https")) return null;
        if (protocol.equals("http")) {
            if (!isLoopbackHost(host))
                return "HTTP is only allowed to this device (localhost). Use HTTPS for other servers";
            if (!allowLocalHttp)
                return "HTTP is off. Turn on \"Local development HTTP\" to use a server on this device";
            return null;
        }
        return "The URL must start with https://";
    }

    public static boolean isLoopbackHost(@NonNull String host) {
        host = host.toLowerCase(Locale.ROOT);
        // The same hosts as the network security config of the app, which blocks HTTP to others
        return host.equals("localhost") || host.equals("127.0.0.1");
    }

    /** Run the test: discover or initialize the server, then list all its tools. */
    @NonNull
    public Result test() {
        Result result = new Result();
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        McpSession session = new McpSession(() -> readOnly(createTransport(), sent), mHandshake, mRedactor, mClientVersion);
        session.setRequestTimeout(mReadTimeoutMs);
        mSession = session;
        try {
            if (mCancelled) throw new McpException(McpException.Kind.CANCELLED, "The test was cancelled");
            session.connect();
            McpSession.Snapshot snapshot = session.getSnapshot();
            result.ok = true;
            result.era = snapshot.era == McpSession.Era.MODERN ? "modern" : "legacy";
            result.protocolVersion = snapshot.protocolVersion;
            result.serverName = snapshot.serverName;
            result.serverVersion = snapshot.serverVersion;
            result.instructions = snapshot.instructions;
            result.hasTools = snapshot.hasTools;
            result.tools.addAll(snapshot.tools);
            result.toolsTruncated = snapshot.toolsTruncated;
        } catch (McpException e) {
            result.ok = false;
            result.failure = mCancelled ? McpException.Kind.CANCELLED : e.kind;
            result.httpStatus = e.httpStatus;
            result.resourceMetadata = e.resourceMetadata;
            result.message = mRedactor.redact(mCancelled ? "The test was cancelled" : String.valueOf(e.getMessage()));
        } finally {
            session.close();
            result.methodsSent.addAll(sent);
        }
        return result;
    }

    @NonNull
    private McpTransport createTransport() throws McpException {
        if (mFactory != null) return mFactory.create();
        McpHttpTransport transport = new McpHttpTransport(mUrl, mHeaders, mClientVersion);
        transport.setConnectTimeout(mConnectTimeoutMs);
        return transport;
    }

    /** A transport that refuses every method but {@link #ALLOWED_METHODS}, and notes the ones it sends. */
    @NonNull
    static McpTransport readOnly(@NonNull McpTransport transport, @NonNull List<String> sent) {
        return new McpTransport() {
            @Override
            public void start(@NonNull Listener listener) throws McpException {
                transport.start(listener);
            }

            @Override
            public void setProtocolVersion(@Nullable String version) {
                transport.setProtocolVersion(version);
            }

            @NonNull
            @Override
            public JSONObject request(@NonNull JSONObject message, @NonNull String method, long timeoutMs) throws McpException {
                check(method);
                return transport.request(message, method, timeoutMs);
            }

            @Override
            public void notify(@NonNull JSONObject message, @NonNull String method) throws McpException {
                check(method);
                transport.notify(message, method);
            }

            @Override
            public void respond(@NonNull JSONObject response) throws McpException {
                transport.respond(response);
            }

            @Override
            public void cancel(@NonNull Object id) {
                transport.cancel(id);
            }

            @Override
            public void close() {
                transport.close();
            }

            private void check(@NonNull String method) {
                if (!ALLOWED_METHODS.contains(method))
                    throw new IllegalArgumentException("A connection test never sends " + method);
                sent.add(method);
            }
        };
    }

}
