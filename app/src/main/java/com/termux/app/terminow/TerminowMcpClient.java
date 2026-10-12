package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * Test a remote MCP server over the Streamable HTTP transport.
 *
 * The client speaks both eras of the protocol: it first sends the modern `server/discover`
 * request (revision 2026-07-28 and later, without sessions), and falls back to the legacy
 * `initialize` handshake (revisions 2025-03-26 to 2025-11-25) when the server does not answer
 * with a recognized modern error, as the backward compatibility rules of the specification say.
 *
 * Only the methods in {@link #ALLOWED_METHODS} can ever be sent, so a test can not call a tool or
 * change anything on the server. TLS certificates are always verified by the platform.
 */
public class TerminowMcpClient {

    public static final String MODERN_VERSION = "2026-07-28";
    /** The legacy versions that use the `initialize` handshake, newest first. */
    public static final List<String> LEGACY_VERSIONS = Collections.unmodifiableList(Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26"));

    /** The only methods that a test sends. Never `tools/call` or anything else with side effects. */
    static final Set<String> ALLOWED_METHODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "server/discover", "initialize", "notifications/initialized", "tools/list")));

    static final int ERROR_UNSUPPORTED_VERSION = -32022;
    static final int ERROR_MISSING_CAPABILITY = -32021;
    static final int ERROR_HEADER_MISMATCH = -32020;
    static final int ERROR_METHOD_NOT_FOUND = -32601;

    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOOL_PAGES = 50;
    private static final int MAX_TOOLS = 2000;

    public enum Failure {
        /** The URL or headers of the profile are not valid. */
        INVALID_PROFILE,
        NETWORK,
        TLS,
        TIMEOUT,
        CANCELLED,
        /** The server needs authentication, and says how to get it with OAuth. */
        OAUTH_REQUIRED,
        /** The server rejected the credentials, or needs some that were not given. */
        AUTH_FAILED,
        FORBIDDEN,
        REDIRECT,
        HTTP_ERROR,
        /** The server answered, but not with valid MCP messages. */
        INVALID_RESPONSE,
        UNSUPPORTED_VERSION,
        /** The server only speaks the deprecated HTTP+SSE transport of revision 2024-11-05. */
        OLD_SSE_TRANSPORT,
        SERVER_ERROR
    }

    public static class Tool {
        public final String name;
        public final String description;

        Tool(@NonNull String name, @NonNull String description) {
            this.name = name;
            this.description = description;
        }
    }

    public static class Result {
        public boolean ok;
        @Nullable public Failure failure;
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
        public final List<Tool> tools = new ArrayList<>();
        /** Whether there were more tools than are kept. */
        public boolean toolsTruncated;
        /** For {@link Failure#OAUTH_REQUIRED}, the protected resource metadata URL, if given. */
        public String resourceMetadata = "";
        /** The methods that were sent, in order, to show that nothing else was. */
        public final List<String> methodsSent = new ArrayList<>();
    }

    public static class McpException extends Exception {
        final Failure failure;
        final int httpStatus;
        String resourceMetadata = "";

        McpException(@NonNull Failure failure, @NonNull String message) {
            this(failure, message, 0);
        }

        McpException(@NonNull Failure failure, @NonNull String message, int httpStatus) {
            super(message);
            this.failure = failure;
            this.httpStatus = httpStatus;
        }
    }

    /** A response of the server to a request that was sent. */
    static class Response {
        int status;
        @Nullable JSONObject message;
        String body = "";
        @Nullable String sessionId;
    }

    private final URL mUrl;
    private final Map<String, String> mHeaders;
    private final TerminowMcpRedactor mRedactor;
    private final String mClientVersion;
    private int mConnectTimeoutMs = 15000;
    private int mReadTimeoutMs = 30000;

    private volatile boolean mCancelled;
    @Nullable private volatile HttpURLConnection mConnection;
    private int mNextId = 1;

    @Nullable private String mSessionId;
    @Nullable private String mLegacyVersion;

    /**
     * @param url The MCP endpoint, already validated with {@link #validateUrl(String, boolean)}.
     * @param headers The headers to send with every request, like `Authorization`.
     */
    public TerminowMcpClient(@NonNull URL url, @NonNull Map<String, String> headers, @NonNull String clientVersion) {
        mUrl = url;
        mHeaders = new LinkedHashMap<>(headers);
        mRedactor = new TerminowMcpRedactor(headers.values());
        mClientVersion = clientVersion;
    }

    public void setTimeouts(int connectTimeoutMs, int readTimeoutMs) {
        mConnectTimeoutMs = connectTimeoutMs;
        mReadTimeoutMs = readTimeoutMs;
    }

    /** Stop a running test from another thread. */
    public void cancel() {
        mCancelled = true;
        HttpURLConnection connection = mConnection;
        if (connection != null) connection.disconnect();
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
        try {
            if (!runModern(result))
                runLegacy(result);
            result.ok = true;
        } catch (McpException e) {
            result.ok = false;
            result.failure = mCancelled ? Failure.CANCELLED : e.failure;
            result.httpStatus = e.httpStatus;
            result.resourceMetadata = e.resourceMetadata;
            result.message = mRedactor.redact(mCancelled ? "The test was cancelled" : String.valueOf(e.getMessage()));
        } finally {
            closeSession(result);
        }
        return result;
    }

    /**
     * Try the modern era. Returns {@code false} if the server is a legacy server, which then
     * needs the `initialize` handshake.
     */
    private boolean runModern(@NonNull Result result) throws McpException {
        String version = MODERN_VERSION;
        Response response = post(result, "server/discover", modernParams(version, null), version, true);
        JSONObject error = response.message != null ? response.message.optJSONObject("error") : null;

        if (error != null && isModernError(response.status, error)) {
            int code = error.optInt("code");
            if (code == ERROR_UNSUPPORTED_VERSION) {
                List<String> supported = getStrings(error.optJSONObject("data"), "supported");
                if (supported.contains(MODERN_VERSION))
                    throw new McpException(Failure.INVALID_RESPONSE, "The server rejected version " + MODERN_VERSION + " but lists it as supported");
                // A dual-era server that only serves legacy versions to us
                for (String legacy : LEGACY_VERSIONS)
                    if (supported.contains(legacy)) return false;
                throw new McpException(Failure.UNSUPPORTED_VERSION, "The server only supports the protocol versions " + supported +
                    ", and TermiNow supports " + MODERN_VERSION + " and " + LEGACY_VERSIONS);
            }
            throw new McpException(Failure.SERVER_ERROR, "The server rejected the request: " + error.optString("message"), response.status);
        }
        if (response.status >= 200 && response.status < 300 && response.message != null && response.message.has("result")) {
            JSONObject discover = response.message.optJSONObject("result");
            List<String> versions = getStrings(discover, "supportedVersions");
            if (discover == null || versions.isEmpty())
                return false;
            if (!versions.contains(MODERN_VERSION)) {
                for (String legacy : LEGACY_VERSIONS)
                    if (versions.contains(legacy)) return false;
                throw new McpException(Failure.UNSUPPORTED_VERSION, "The server only supports the protocol versions " + versions);
            }
            result.era = "modern";
            result.protocolVersion = MODERN_VERSION;
            JSONObject meta = discover.optJSONObject("_meta");
            JSONObject serverInfo = meta != null ? meta.optJSONObject("io.modelcontextprotocol/serverInfo") : null;
            if (serverInfo == null) serverInfo = discover.optJSONObject("serverInfo");
            setServerInfo(result, serverInfo);
            result.instructions = discover.optString("instructions");
            JSONObject capabilities = discover.optJSONObject("capabilities");
            result.hasTools = capabilities != null && capabilities.has("tools");
            if (result.hasTools) listTools(result, version, true);
            return true;
        }
        // Anything else, like a 400 without a modern error, a 404, a 405 or a JSON-RPC error
        // with HTTP 200, identifies a legacy server
        return false;
    }

    private void runLegacy(@NonNull Result result) throws McpException {
        mSessionId = null;
        JSONObject params = new JSONObject();
        try {
            params.put("protocolVersion", LEGACY_VERSIONS.get(0));
            params.put("capabilities", new JSONObject());
            params.put("clientInfo", new JSONObject().put("name", "TermiNow").put("version", mClientVersion));
        } catch (JSONException e) {
            throw new McpException(Failure.INVALID_PROFILE, String.valueOf(e.getMessage()));
        }
        Response response = post(result, "initialize", params, null, true);
        if (response.status == 400 || response.status == 404 || response.status == 405) {
            if (response.message == null || !response.message.has("error"))
                throw new McpException(Failure.OLD_SSE_TRANSPORT, "The server did not accept MCP requests at this URL (HTTP " + response.status +
                    "). It may use the deprecated HTTP+SSE transport, which is not supported, or the URL may be wrong", response.status);
        }
        JSONObject initialize = requireResult(response, "initialize");
        String version = initialize.optString("protocolVersion");
        if (!LEGACY_VERSIONS.contains(version))
            throw new McpException(Failure.UNSUPPORTED_VERSION, "The server wants protocol version \"" + version +
                "\", and TermiNow supports " + MODERN_VERSION + " and " + LEGACY_VERSIONS);
        mLegacyVersion = version;
        mSessionId = response.sessionId;
        result.era = "legacy";
        result.protocolVersion = version;
        setServerInfo(result, initialize.optJSONObject("serverInfo"));
        result.instructions = initialize.optString("instructions");
        JSONObject capabilities = initialize.optJSONObject("capabilities");
        result.hasTools = capabilities != null && capabilities.has("tools");

        Response initialized = post(result, "notifications/initialized", null, version, false);
        if (initialized.status < 200 || initialized.status >= 300)
            throw new McpException(Failure.INVALID_RESPONSE, "The server did not accept the initialized notification (HTTP " + initialized.status + ")", initialized.status);

        if (result.hasTools) listTools(result, version, false);
    }

    private void listTools(@NonNull Result result, @NonNull String version, boolean modern) throws McpException {
        String cursor = null;
        Set<String> cursors = new HashSet<>();
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            JSONObject params;
            try {
                params = modern ? modernParams(version, cursor) : new JSONObject();
                if (!modern && cursor != null) params.put("cursor", cursor);
            } catch (JSONException e) {
                throw new McpException(Failure.INVALID_RESPONSE, String.valueOf(e.getMessage()));
            }
            JSONObject list = requireResult(post(result, "tools/list", params, modern ? version : mLegacyVersion, true), "tools/list");
            JSONArray tools = list.optJSONArray("tools");
            if (tools == null) throw new McpException(Failure.INVALID_RESPONSE, "The tools/list result has no tools");
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.optJSONObject(i);
                if (tool == null || tool.optString("name").isEmpty())
                    throw new McpException(Failure.INVALID_RESPONSE, "The tools/list result has a tool without a name");
                if (result.tools.size() >= MAX_TOOLS) {
                    result.toolsTruncated = true;
                    return;
                }
                result.tools.add(new Tool(tool.optString("name"), tool.optString("description")));
            }
            cursor = list.has("nextCursor") && !list.isNull("nextCursor") ? list.optString("nextCursor") : null;
            if (cursor == null || cursor.isEmpty()) return;
            // A server that keeps sending the same cursor would never finish
            if (!cursors.add(cursor)) throw new McpException(Failure.INVALID_RESPONSE, "The server repeated a tools/list cursor");
        }
        result.toolsTruncated = true;
    }

    @NonNull
    private JSONObject modernParams(@NonNull String version, @Nullable String cursor) throws McpException {
        try {
            JSONObject meta = new JSONObject();
            meta.put("io.modelcontextprotocol/protocolVersion", version);
            meta.put("io.modelcontextprotocol/clientInfo", new JSONObject().put("name", "TermiNow").put("version", mClientVersion));
            meta.put("io.modelcontextprotocol/clientCapabilities", new JSONObject());
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            params.put("_meta", meta);
            return params;
        } catch (JSONException e) {
            throw new McpException(Failure.INVALID_RESPONSE, String.valueOf(e.getMessage()));
        }
    }

    private static boolean isModernError(int status, @NonNull JSONObject error) {
        int code = error.optInt("code");
        if (status == 400)
            return code == ERROR_UNSUPPORTED_VERSION || code == ERROR_MISSING_CAPABILITY || code == ERROR_HEADER_MISMATCH;
        // A modern server answers an unknown method with 404 and -32601, unlike a legacy server
        return status == 404 && code == ERROR_METHOD_NOT_FOUND;
    }

    private static void setServerInfo(@NonNull Result result, @Nullable JSONObject serverInfo) {
        if (serverInfo == null) return;
        result.serverName = serverInfo.optString("title", serverInfo.optString("name"));
        if (result.serverName.isEmpty()) result.serverName = serverInfo.optString("name");
        result.serverVersion = serverInfo.optString("version");
    }

    @NonNull
    private static List<String> getStrings(@Nullable JSONObject object, @NonNull String key) {
        List<String> values = new ArrayList<>();
        JSONArray array = object != null ? object.optJSONArray(key) : null;
        if (array != null)
            for (int i = 0; i < array.length(); i++) values.add(array.optString(i));
        return values;
    }

    @NonNull
    private JSONObject requireResult(@NonNull Response response, @NonNull String method) throws McpException {
        if (response.message == null)
            throw new McpException(Failure.INVALID_RESPONSE, "The server did not answer " + method + " with a JSON-RPC message (HTTP " + response.status + ")", response.status);
        JSONObject error = response.message.optJSONObject("error");
        if (error != null)
            throw new McpException(Failure.SERVER_ERROR, "The server answered " + method + " with an error: " +
                error.optString("message") + " (" + error.optInt("code") + ")", response.status);
        JSONObject result = response.message.optJSONObject("result");
        if (result == null)
            throw new McpException(Failure.INVALID_RESPONSE, "The " + method + " response has no result", response.status);
        return result;
    }

    /**
     * Send a request or notification and read the response, which is a JSON object or an SSE
     * stream that ends with the response. HTTP errors other than authentication are returned for
     * the caller to inspect.
     */
    @NonNull
    Response post(@NonNull Result result, @NonNull String method, @Nullable JSONObject params, @Nullable String protocolVersion,
                  boolean isRequest) throws McpException {
        if (!ALLOWED_METHODS.contains(method))
            throw new IllegalArgumentException("A connection test never sends " + method);
        if (mCancelled) throw new McpException(Failure.CANCELLED, "The test was cancelled");

        int id = mNextId++;
        JSONObject message = new JSONObject();
        try {
            message.put("jsonrpc", "2.0");
            if (isRequest) message.put("id", id);
            message.put("method", method);
            if (params != null) message.put("params", params);
        } catch (JSONException e) {
            throw new McpException(Failure.INVALID_RESPONSE, String.valueOf(e.getMessage()));
        }
        result.methodsSent.add(method);

        HttpURLConnection connection = null;
        try {
            connection = open("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            if (protocolVersion != null) connection.setRequestProperty("MCP-Protocol-Version", protocolVersion);
            // The modern revision mirrors the method into a header, which legacy servers ignore
            if (MODERN_VERSION.equals(protocolVersion)) connection.setRequestProperty("Mcp-Method", method);
            if (mSessionId != null) connection.setRequestProperty("Mcp-Session-Id", mSessionId);
            byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }

            Response response = new Response();
            response.status = connection.getResponseCode();
            response.sessionId = connection.getHeaderField("Mcp-Session-Id");
            checkStatus(connection, response.status);

            if (!isRequest) {
                readLimited(errorOrInput(connection, response.status));
                return response;
            }
            String contentType = String.valueOf(connection.getContentType()).toLowerCase(Locale.ROOT);
            InputStream in = errorOrInput(connection, response.status);
            if (response.status >= 200 && response.status < 300 && contentType.startsWith("text/event-stream")) {
                response.message = readSse(in, id);
            } else {
                response.body = readLimited(in);
                response.message = parseMessage(response.body, response.status);
            }
            if (response.status >= 200 && response.status < 300 && response.message == null)
                throw new McpException(Failure.INVALID_RESPONSE, "The server answered " + method + " without a JSON-RPC response (content type " + contentType + ")", response.status);
            if (response.message != null && response.message.has("id") && response.message.opt("id") != null &&
                !String.valueOf(id).equals(response.message.optString("id")) && response.status < 300)
                throw new McpException(Failure.INVALID_RESPONSE, "The server answered with the id of another request");
            return response;
        } catch (SocketTimeoutException e) {
            throw new McpException(Failure.TIMEOUT, "The server did not answer " + method + " in time");
        } catch (SSLException e) {
            throw new McpException(Failure.TLS, "The secure connection failed: " + e.getMessage() +
                ". The certificate of the server must be valid, TermiNow never skips the check");
        } catch (InterruptedIOException e) {
            throw new McpException(mCancelled ? Failure.CANCELLED : Failure.TIMEOUT, "The request was interrupted");
        } catch (IOException e) {
            if (mCancelled) throw new McpException(Failure.CANCELLED, "The test was cancelled");
            throw new McpException(Failure.NETWORK, "Failed to connect to " + mUrl.getHost() + ": " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
            mConnection = null;
        }
    }

    @NonNull
    private HttpURLConnection open(@NonNull String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) mUrl.openConnection();
        mConnection = connection;
        // A redirect could send the credentials to another server
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(mConnectTimeoutMs);
        connection.setReadTimeout(mReadTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestMethod(method);
        connection.setRequestProperty("User-Agent", "TermiNow/" + mClientVersion);
        for (Map.Entry<String, String> header : mHeaders.entrySet())
            connection.setRequestProperty(header.getKey(), header.getValue());
        if (mCancelled) {
            connection.disconnect();
            throw new InterruptedIOException("Cancelled");
        }
        return connection;
    }

    private void checkStatus(@NonNull HttpURLConnection connection, int status) throws McpException, IOException {
        if (status >= 300 && status < 400)
            throw new McpException(Failure.REDIRECT, "The server redirected to " + connection.getHeaderField("Location") +
                ". Enter that URL instead, if you trust it", status);
        if (status == 401) {
            String authenticate = String.valueOf(connection.getHeaderField("WWW-Authenticate"));
            readLimited(errorOrInput(connection, status));
            if (mHeaders.isEmpty() && authenticate.toLowerCase(Locale.ROOT).startsWith("bearer")) {
                McpException e = new McpException(Failure.OAUTH_REQUIRED, "The server requires OAuth sign-in", status);
                Matcher matcher = Pattern.compile("resource_metadata=\"([^\"]*)\"").matcher(authenticate);
                if (matcher.find()) e.resourceMetadata = matcher.group(1);
                throw e;
            }
            throw new McpException(Failure.AUTH_FAILED, mHeaders.isEmpty() ? "The server requires authentication" :
                "The server rejected the credentials (HTTP 401)", status);
        }
        if (status == 403) {
            readLimited(errorOrInput(connection, status));
            throw new McpException(Failure.FORBIDDEN, "The server refused access (HTTP 403). The credentials may lack permissions, or the server rejected the request origin", status);
        }
        if (status >= 500)
            throw new McpException(Failure.HTTP_ERROR, "The server failed with HTTP " + status, status);
    }

    @Nullable
    private static InputStream errorOrInput(@NonNull HttpURLConnection connection, int status) throws IOException {
        return status >= 400 ? connection.getErrorStream() : connection.getInputStream();
    }

    @NonNull
    private static String readLimited(@Nullable InputStream in) throws IOException, McpException {
        if (in == null) return "";
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_RESPONSE_BYTES) throw new McpException(Failure.INVALID_RESPONSE, "The response of the server is too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Nullable
    private static JSONObject parseMessage(@NonNull String body, int status) throws McpException {
        String trimmed = body.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return new JSONObject(trimmed);
        } catch (JSONException e) {
            if (status >= 200 && status < 300)
                throw new McpException(Failure.INVALID_RESPONSE, "The server did not answer with JSON. Is this the MCP endpoint URL?", status);
            return null;
        }
    }

    /**
     * Read an SSE stream until the response with the id. Notifications and requests of the
     * server before it are skipped, since a test does not act on them.
     */
    @Nullable
    private JSONObject readSse(@Nullable InputStream in, int id) throws IOException, McpException {
        if (in == null) return null;
        long total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                total += line.length() + 1;
                if (total > MAX_RESPONSE_BYTES) throw new McpException(Failure.INVALID_RESPONSE, "The response of the server is too large");
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        JSONObject message;
                        try {
                            message = new JSONObject(data.toString());
                        } catch (JSONException e) {
                            throw new McpException(Failure.INVALID_RESPONSE, "The server sent an event that is not JSON");
                        }
                        data.setLength(0);
                        if (!message.has("method") && String.valueOf(id).equals(message.optString("id")))
                            return message;
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
                }
                // Comments, event names, ids and retry fields carry no message
            }
        }
        throw new McpException(Failure.INVALID_RESPONSE, "The event stream ended without the response");
    }

    /** End a legacy session, if the server started one. Errors are ignored. */
    private void closeSession(@NonNull Result result) {
        String sessionId = mSessionId;
        mSessionId = null;
        if (sessionId == null) return;
        HttpURLConnection connection = null;
        try {
            boolean cancelled = mCancelled;
            mCancelled = false;
            connection = open("DELETE");
            mCancelled = cancelled;
            connection.setRequestProperty("Mcp-Session-Id", sessionId);
            if (mLegacyVersion != null) connection.setRequestProperty("MCP-Protocol-Version", mLegacyVersion);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            // 405 means that the server does not let clients end sessions, which is fine
            connection.getResponseCode();
            result.methodsSent.add("DELETE session");
        } catch (IOException e) {
            // The server ends the session itself later
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

}
