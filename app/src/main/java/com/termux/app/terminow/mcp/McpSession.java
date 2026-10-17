package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.TerminowMcpRedactor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The MCP protocol with one server, over a transport from a factory, so that a reconnect starts
 * from a fresh transport. The state only changes in the methods of this class, along
 * {@link #TRANSITIONS}, and is read through immutable {@link Snapshot}s.
 *
 * The handshake speaks both eras of the protocol: the modern `server/discover` request
 * (revision 2026-07-28 and later, without sessions) and the legacy `initialize` handshake
 * (revisions 2025-03-26 to 2025-11-25), as the backward compatibility rules of the specification
 * say. The blocking methods are called from background threads.
 *
 * Every error that leaves the session has the secrets removed.
 */
public final class McpSession {

    public static final String MODERN_VERSION = "2026-07-28";
    /** The legacy versions that use the `initialize` handshake, newest first. */
    public static final List<String> LEGACY_VERSIONS = Collections.unmodifiableList(Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26"));

    static final int ERROR_UNSUPPORTED_VERSION = -32022;
    static final int ERROR_MISSING_CAPABILITY = -32021;
    static final int ERROR_HEADER_MISMATCH = -32020;
    static final int ERROR_METHOD_NOT_FOUND = -32601;

    static final int MAX_TOOL_PAGES = 50;
    static final int MAX_TOOLS = 2000;

    public enum State { IDLE, CONNECTING, READY, CLOSED, FAILED }

    public enum Era { MODERN, LEGACY }

    /** Which handshake is tried first. Most stdio servers only speak the legacy one. */
    public enum Handshake { MODERN_FIRST, LEGACY_FIRST }

    private static final Map<State, Set<State>> TRANSITIONS = new EnumMap<>(State.class);

    static {
        TRANSITIONS.put(State.IDLE, EnumSet.of(State.CONNECTING, State.CLOSED));
        TRANSITIONS.put(State.CONNECTING, EnumSet.of(State.CONNECTING, State.READY, State.FAILED, State.CLOSED));
        TRANSITIONS.put(State.READY, EnumSet.of(State.READY, State.CONNECTING, State.FAILED, State.CLOSED));
        TRANSITIONS.put(State.FAILED, EnumSet.of(State.CONNECTING, State.CLOSED));
        TRANSITIONS.put(State.CLOSED, EnumSet.of(State.CONNECTING));
    }

    /** What is known about the server at one moment. */
    public static final class Snapshot {
        @NonNull public final State state;
        @Nullable public final Era era;
        @NonNull public final String protocolVersion;
        @NonNull public final String serverName;
        @NonNull public final String serverVersion;
        @NonNull public final String instructions;
        public final boolean hasTools;
        @NonNull public final List<McpTool> tools;
        /** Whether the server has more tools than are kept. */
        public final boolean toolsTruncated;
        /** Why the session failed, for {@link State#FAILED}. */
        @Nullable public final McpException error;

        static final Snapshot IDLE = new Snapshot(State.IDLE, null, "", "", "", "", false, Collections.<McpTool>emptyList(), false, null);

        Snapshot(@NonNull State state, @Nullable Era era, @NonNull String protocolVersion, @NonNull String serverName,
                 @NonNull String serverVersion, @NonNull String instructions, boolean hasTools, @NonNull List<McpTool> tools,
                 boolean toolsTruncated, @Nullable McpException error) {
            this.state = state;
            this.era = era;
            this.protocolVersion = protocolVersion;
            this.serverName = serverName;
            this.serverVersion = serverVersion;
            this.instructions = instructions;
            this.hasTools = hasTools;
            this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
            this.toolsTruncated = toolsTruncated;
            this.error = error;
        }

        @NonNull
        Snapshot with(@NonNull State newState, @Nullable McpException newError) {
            return new Snapshot(newState, era, protocolVersion, serverName, serverVersion, instructions, hasTools, tools, toolsTruncated, newError);
        }

        @NonNull
        Snapshot withTools(@NonNull List<McpTool> newTools, boolean truncated) {
            return new Snapshot(state, era, protocolVersion, serverName, serverVersion, instructions, hasTools, newTools, truncated, error);
        }
    }

    public interface Listener {
        /** Called on any thread, after every change. */
        void onChanged(@NonNull Snapshot snapshot);
    }

    /** Lets another thread cancel a running tool call. */
    public static final class CancelHandle {
        private boolean mCancelled;
        @Nullable private Runnable mAction;

        public void cancel() {
            Runnable action;
            synchronized (this) {
                if (mCancelled) return;
                mCancelled = true;
                action = mAction;
            }
            if (action != null) action.run();
        }

        public synchronized boolean isCancelled() {
            return mCancelled;
        }

        synchronized boolean attach(@NonNull Runnable action) {
            if (mCancelled) return false;
            mAction = action;
            return true;
        }

        synchronized void detach() {
            mAction = null;
        }
    }

    private final McpTransport.Factory mFactory;
    private final Handshake mHandshake;
    private final TerminowMcpRedactor mRedactor;
    private final String mClientVersion;
    private final AtomicInteger mNextId = new AtomicInteger(1);
    private volatile long mRequestTimeoutMs = 30_000;
    @Nullable private volatile Listener mListener;

    // Guarded by this
    @Nullable private McpTransport mTransport;
    @NonNull private Snapshot mSnapshot = Snapshot.IDLE;

    public McpSession(@NonNull McpTransport.Factory factory, @NonNull Handshake handshake, @NonNull TerminowMcpRedactor redactor,
                      @NonNull String clientVersion) {
        mFactory = factory;
        mHandshake = handshake;
        mRedactor = redactor;
        mClientVersion = clientVersion;
    }

    /** The time to wait for the server to answer the handshake and tools/list. */
    public void setRequestTimeout(long timeoutMs) {
        mRequestTimeoutMs = timeoutMs;
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    @NonNull
    public synchronized Snapshot getSnapshot() {
        return mSnapshot;
    }

    /** Connect and list the tools, unless the session is connected or connecting already. */
    public void connect() throws McpException {
        open(false);
    }

    /** Drop the current transport, if any, and connect again with a new one. */
    public void reconnect() throws McpException {
        open(true);
    }

    /** End the session. It can be connected again later. */
    public void close() {
        McpTransport transport;
        synchronized (this) {
            if (mSnapshot.state == State.CLOSED) return;
            transport = mTransport;
            mTransport = null;
            moveTo(Snapshot.IDLE.with(State.CLOSED, null));
        }
        if (transport != null) transport.close();
    }

    private void open(boolean replace) throws McpException {
        McpTransport old;
        synchronized (this) {
            State state = mSnapshot.state;
            if (!replace && (state == State.READY || state == State.CONNECTING)) return;
            old = mTransport;
            mTransport = null;
            moveTo(Snapshot.IDLE.with(State.CONNECTING, null));
        }
        if (old != null) old.close();

        McpTransport transport;
        try {
            transport = mFactory.create();
        } catch (McpException e) {
            throw fail(null, e);
        }
        synchronized (this) {
            if (mSnapshot.state != State.CONNECTING || mTransport != null) {
                transport.close();
                throw new McpException(McpException.Kind.CANCELLED, "The connection was closed");
            }
            mTransport = transport;
        }
        Snapshot connected;
        try {
            transport.start(new TransportListener(transport));
            connected = handshake(transport);
        } catch (McpException e) {
            transport.close();
            throw fail(transport, e);
        }
        synchronized (this) {
            if (mTransport != transport) throw new McpException(McpException.Kind.CANCELLED, "The connection was closed");
            moveTo(connected);
        }
    }

    /** Fail the session if the transport is still its own, and return the redacted error to throw. */
    @NonNull
    private McpException fail(@Nullable McpTransport transport, @NonNull McpException e) {
        McpException redacted = e.redact(mRedactor);
        synchronized (this) {
            if (mTransport == transport && mSnapshot.state == State.CONNECTING) {
                mTransport = null;
                moveTo(Snapshot.IDLE.with(State.FAILED, redacted));
            }
        }
        return redacted;
    }

    /** List the tools again, for example after the server said that they changed. */
    @NonNull
    public List<McpTool> listTools() throws McpException {
        McpTransport transport;
        Snapshot snapshot;
        synchronized (this) {
            transport = requireReady();
            snapshot = mSnapshot;
        }
        try {
            Snapshot listed = snapshot.withTools(Collections.<McpTool>emptyList(), false);
            listed = fetchTools(transport, listed);
            synchronized (this) {
                if (mTransport == transport && mSnapshot.state == State.READY)
                    moveTo(mSnapshot.withTools(listed.tools, listed.toolsTruncated));
            }
            return listed.tools;
        } catch (McpException e) {
            throw e.redact(mRedactor);
        }
    }

    /**
     * Call a tool. A result that reports an error is returned like any other; a JSON-RPC error
     * is thrown. On a timeout or a cancel the server is told to stop with
     * `notifications/cancelled`.
     */
    @NonNull
    public McpToolResult callTool(@NonNull String name, @NonNull JSONObject arguments, long timeoutMs,
                                  @Nullable CancelHandle cancel) throws McpException {
        McpTransport transport;
        Era era;
        synchronized (this) {
            transport = requireReady();
            era = mSnapshot.era;
        }
        int id = mNextId.getAndIncrement();
        JSONObject message;
        try {
            JSONObject params = new JSONObject().put("name", name).put("arguments", arguments);
            if (era == Era.MODERN) params.put("_meta", modernMeta());
            message = message(id, "tools/call", params);
        } catch (JSONException e) {
            throw new McpException(McpException.Kind.INVALID_RESPONSE, String.valueOf(e.getMessage()));
        }
        if (cancel != null && !cancel.attach(() -> transport.cancel(id)))
            throw new McpException(McpException.Kind.CANCELLED, "The call was cancelled");
        try {
            JSONObject response = transport.request(message, "tools/call", timeoutMs);
            return McpToolResult.parse(requireResult(response, "tools/call"));
        } catch (McpException e) {
            if (e.kind == McpException.Kind.TIMEOUT || e.kind == McpException.Kind.CANCELLED) {
                boolean timeout = e.kind == McpException.Kind.TIMEOUT;
                if (sendCancelled(transport, id, timeout ? "Timed out" : "Cancelled by the user")) {
                    throw new McpException(e.kind, timeout ? "The tool did not answer in " + (timeoutMs / 1000) +
                        " s. TermiNow asked the server to stop it" : "The call was cancelled");
                }
            }
            throw e.redact(mRedactor);
        } finally {
            if (cancel != null) cancel.detach();
        }
    }

    /** Tell the server to stop a request. Returns whether the session was still connected. */
    private boolean sendCancelled(@NonNull McpTransport transport, int id, @NonNull String reason) {
        Era era;
        synchronized (this) {
            if (mTransport != transport || mSnapshot.state != State.READY) return false;
            era = mSnapshot.era;
        }
        try {
            JSONObject params = new JSONObject().put("requestId", id).put("reason", reason);
            if (era == Era.MODERN) params.put("_meta", modernMeta());
            transport.notify(notification("notifications/cancelled", params), "notifications/cancelled");
        } catch (McpException | JSONException e) {
            // The server may be gone already
        }
        return true;
    }

    @NonNull
    private McpTransport requireReady() throws McpException {
        McpTransport transport = mTransport;
        if (mSnapshot.state == State.READY && transport != null) return transport;
        if (mSnapshot.error != null) throw mSnapshot.error;
        throw new McpException(McpException.Kind.CANCELLED, "Not connected to the server");
    }

    private void moveTo(@NonNull Snapshot next) {
        Set<State> allowed = TRANSITIONS.get(mSnapshot.state);
        if (allowed == null || !allowed.contains(next.state))
            throw new IllegalStateException("MCP session can not go from " + mSnapshot.state + " to " + next.state);
        mSnapshot = next;
        Listener listener = mListener;
        if (listener != null) listener.onChanged(next);
    }

    /** Hears what the transport receives by itself. Ignored once the transport is not the current one. */
    private final class TransportListener implements McpTransport.Listener {
        private final McpTransport mOwn;

        TransportListener(@NonNull McpTransport transport) {
            mOwn = transport;
        }

        @Override
        public void onServerRequest(@NonNull JSONObject request) {
            try {
                JSONObject response = new JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id"));
                if ("ping".equals(McpJson.string(request, "method")))
                    response.put("result", new JSONObject());
                else
                    response.put("error", new JSONObject().put("code", ERROR_METHOD_NOT_FOUND).put("message", "Method not found"));
                mOwn.respond(response);
            } catch (McpException | JSONException e) {
                // The server asks again or gives up
            }
        }

        @Override
        public void onNotification(@NonNull JSONObject notification) {
            // Logging, progress and list changes are not shown yet
        }

        @Override
        public void onClosed(@NonNull McpException reason) {
            McpException redacted = reason.redact(mRedactor);
            synchronized (McpSession.this) {
                if (mTransport != mOwn) return;
                mTransport = null;
                moveTo(mSnapshot.with(State.FAILED, redacted));
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Handshake
    // ---------------------------------------------------------------------------------------

    /** Agree on a version with the server and list its tools. Returns the snapshot of a ready session. */
    @NonNull
    private Snapshot handshake(@NonNull McpTransport transport) throws McpException {
        Snapshot ready;
        if (mHandshake == Handshake.MODERN_FIRST) {
            ready = tryModern(transport);
            if (ready == null) ready = tryLegacy(transport, false);
        } else {
            ready = tryLegacy(transport, true);
            if (ready == null) ready = tryModern(transport);
        }
        if (ready == null)
            throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server answered neither initialize nor server/discover");
        return ready.hasTools ? fetchTools(transport, ready) : ready;
    }

    /**
     * Try the modern era. Returns {@code null} if the server is a legacy server, which then
     * needs the `initialize` handshake.
     */
    @Nullable
    private Snapshot tryModern(@NonNull McpTransport transport) throws McpException {
        transport.setProtocolVersion(MODERN_VERSION);
        int status = 0;
        JSONObject error;
        JSONObject result;
        try {
            JSONObject response = transport.request(message(mNextId.getAndIncrement(), "server/discover", modernParams(null)),
                "server/discover", mRequestTimeoutMs);
            error = response.optJSONObject("error");
            result = response.optJSONObject("result");
        } catch (McpException e) {
            boolean clientError = e.kind == McpException.Kind.HTTP_ERROR && e.httpStatus >= 400 && e.httpStatus < 500;
            if (e.rpcError == null && !clientError) throw e;
            status = e.httpStatus;
            error = e.rpcError;
            result = null;
        }

        if (error != null && isModernError(status, error)) {
            if (error.optInt("code") == ERROR_UNSUPPORTED_VERSION) {
                List<String> supported = strings(error.optJSONObject("data"), "supported");
                if (supported.contains(MODERN_VERSION))
                    throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server rejected version " + MODERN_VERSION + " but lists it as supported");
                // A dual-era server that only serves legacy versions to us
                for (String legacy : LEGACY_VERSIONS)
                    if (supported.contains(legacy)) return legacyNext(transport);
                throw new McpException(McpException.Kind.UNSUPPORTED_VERSION, "The server only supports the protocol versions " + supported +
                    ", and TermiNow supports " + MODERN_VERSION + " and " + LEGACY_VERSIONS);
            }
            throw new McpException(McpException.Kind.SERVER_ERROR, "The server rejected the request: " + McpJson.string(error, "message"), status);
        }
        if (error == null && result != null) {
            List<String> versions = strings(result, "supportedVersions");
            if (versions.isEmpty()) return legacyNext(transport);
            if (!versions.contains(MODERN_VERSION)) {
                for (String legacy : LEGACY_VERSIONS)
                    if (versions.contains(legacy)) return legacyNext(transport);
                throw new McpException(McpException.Kind.UNSUPPORTED_VERSION, "The server only supports the protocol versions " + versions);
            }
            JSONObject meta = result.optJSONObject("_meta");
            JSONObject serverInfo = meta != null ? meta.optJSONObject("io.modelcontextprotocol/serverInfo") : null;
            if (serverInfo == null) serverInfo = result.optJSONObject("serverInfo");
            return connected(Era.MODERN, MODERN_VERSION, serverInfo, result);
        }
        // Anything else, like a 400 without a modern error, a 404, a 405 or a JSON-RPC error
        // with HTTP 200, identifies a legacy server
        return legacyNext(transport);
    }

    @Nullable
    private static Snapshot legacyNext(@NonNull McpTransport transport) {
        transport.setProtocolVersion(null);
        return null;
    }

    /**
     * The legacy `initialize` handshake. Returns {@code null} if {@code modernMayFollow} and the
     * server does not know the method.
     */
    @Nullable
    private Snapshot tryLegacy(@NonNull McpTransport transport, boolean modernMayFollow) throws McpException {
        transport.setProtocolVersion(null);
        JSONObject response;
        try {
            JSONObject params = new JSONObject();
            params.put("protocolVersion", LEGACY_VERSIONS.get(0));
            params.put("capabilities", new JSONObject());
            params.put("clientInfo", new JSONObject().put("name", "TermiNow").put("version", mClientVersion));
            response = transport.request(message(mNextId.getAndIncrement(), "initialize", params), "initialize", mRequestTimeoutMs);
        } catch (JSONException e) {
            throw new McpException(McpException.Kind.INVALID_RESPONSE, String.valueOf(e.getMessage()));
        } catch (McpException e) {
            if (e.kind == McpException.Kind.HTTP_ERROR && e.rpcError == null &&
                (e.httpStatus == 400 || e.httpStatus == 404 || e.httpStatus == 405))
                throw new McpException(McpException.Kind.OLD_SSE_TRANSPORT, "The server did not accept MCP requests at this URL (HTTP " + e.httpStatus +
                    "). It may use the deprecated HTTP+SSE transport, which is not supported, or the URL may be wrong", e.httpStatus);
            throw e;
        }
        JSONObject error = response.optJSONObject("error");
        if (error != null && modernMayFollow) {
            int code = error.optInt("code");
            if (code == ERROR_METHOD_NOT_FOUND || code == ERROR_UNSUPPORTED_VERSION) return null;
        }
        JSONObject initialize = requireResult(response, "initialize");
        String version = McpJson.string(initialize, "protocolVersion");
        if (!LEGACY_VERSIONS.contains(version))
            throw new McpException(McpException.Kind.UNSUPPORTED_VERSION, "The server wants protocol version \"" + version +
                "\", and TermiNow supports " + MODERN_VERSION + " and " + LEGACY_VERSIONS);
        transport.setProtocolVersion(version);
        Snapshot connected = connected(Era.LEGACY, version, initialize.optJSONObject("serverInfo"), initialize);
        try {
            transport.notify(notification("notifications/initialized", null), "notifications/initialized");
        } catch (JSONException e) {
            throw new McpException(McpException.Kind.INVALID_RESPONSE, String.valueOf(e.getMessage()));
        }
        return connected;
    }

    @NonNull
    private static Snapshot connected(@NonNull Era era, @NonNull String version, @Nullable JSONObject serverInfo, @NonNull JSONObject result) {
        String name = McpJson.string(serverInfo, "title");
        if (name.isEmpty()) name = McpJson.string(serverInfo, "name");
        JSONObject capabilities = result.optJSONObject("capabilities");
        return new Snapshot(State.READY, era, version, name, McpJson.string(serverInfo, "version"), McpJson.string(result, "instructions"),
            capabilities != null && capabilities.has("tools"), Collections.<McpTool>emptyList(), false, null);
    }

    /** All pages of tools/list, up to the limits. */
    @NonNull
    private Snapshot fetchTools(@NonNull McpTransport transport, @NonNull Snapshot snapshot) throws McpException {
        boolean modern = snapshot.era == Era.MODERN;
        List<McpTool> tools = new ArrayList<>();
        String cursor = null;
        Set<String> cursors = new HashSet<>();
        for (int page = 0; page < MAX_TOOL_PAGES; page++) {
            JSONObject params;
            try {
                params = modern ? modernParams(cursor) : new JSONObject();
                if (!modern && cursor != null) params.put("cursor", cursor);
            } catch (JSONException e) {
                throw new McpException(McpException.Kind.INVALID_RESPONSE, String.valueOf(e.getMessage()));
            }
            JSONObject list = requireResult(transport.request(message(mNextId.getAndIncrement(), "tools/list", params), "tools/list",
                mRequestTimeoutMs), "tools/list");
            JSONArray array = list.optJSONArray("tools");
            if (array == null) throw new McpException(McpException.Kind.INVALID_RESPONSE, "The tools/list result has no tools");
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                McpTool tool = object != null ? McpTool.parse(object) : null;
                if (tool == null) throw new McpException(McpException.Kind.INVALID_RESPONSE, "The tools/list result has a tool without a name");
                if (tools.size() >= MAX_TOOLS) return snapshot.withTools(tools, true);
                tools.add(tool);
            }
            cursor = McpJson.string(list, "nextCursor");
            if (cursor.isEmpty()) return snapshot.withTools(tools, false);
            // A server that keeps sending the same cursor would never finish
            if (!cursors.add(cursor)) throw new McpException(McpException.Kind.INVALID_RESPONSE, "The server repeated a tools/list cursor");
        }
        return snapshot.withTools(tools, true);
    }

    // ---------------------------------------------------------------------------------------
    // Messages
    // ---------------------------------------------------------------------------------------

    @NonNull
    private static JSONObject message(int id, @NonNull String method, @NonNull JSONObject params) throws McpException {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params);
        } catch (JSONException e) {
            throw new McpException(McpException.Kind.INVALID_RESPONSE, String.valueOf(e.getMessage()));
        }
    }

    @NonNull
    private static JSONObject notification(@NonNull String method, @Nullable JSONObject params) throws JSONException {
        JSONObject message = new JSONObject().put("jsonrpc", "2.0").put("method", method);
        if (params != null) message.put("params", params);
        return message;
    }

    @NonNull
    private JSONObject modernMeta() throws JSONException {
        JSONObject meta = new JSONObject();
        meta.put("io.modelcontextprotocol/protocolVersion", MODERN_VERSION);
        meta.put("io.modelcontextprotocol/clientInfo", new JSONObject().put("name", "TermiNow").put("version", mClientVersion));
        meta.put("io.modelcontextprotocol/clientCapabilities", new JSONObject());
        return meta;
    }

    @NonNull
    private JSONObject modernParams(@Nullable String cursor) throws McpException {
        try {
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            params.put("_meta", modernMeta());
            return params;
        } catch (JSONException e) {
            throw new McpException(McpException.Kind.INVALID_RESPONSE, String.valueOf(e.getMessage()));
        }
    }

    private static boolean isModernError(int status, @NonNull JSONObject error) {
        int code = error.optInt("code");
        // Status 0 is a response that came without an HTTP error, like every stdio response
        if (status == 400 || status == 0)
            return code == ERROR_UNSUPPORTED_VERSION || code == ERROR_MISSING_CAPABILITY || code == ERROR_HEADER_MISMATCH;
        // A modern server answers an unknown method with 404 and -32601, unlike a legacy server
        return status == 404 && code == ERROR_METHOD_NOT_FOUND;
    }

    @NonNull
    private static List<String> strings(@Nullable JSONObject object, @NonNull String key) {
        List<String> values = new ArrayList<>();
        JSONArray array = object != null ? object.optJSONArray(key) : null;
        if (array != null)
            for (int i = 0; i < array.length(); i++) values.add(array.optString(i));
        return values;
    }

    @NonNull
    private static JSONObject requireResult(@NonNull JSONObject response, @NonNull String method) throws McpException {
        JSONObject error = response.optJSONObject("error");
        if (error != null) throw new McpException(McpException.Kind.SERVER_ERROR, describeError(method, error), 0, "", error);
        JSONObject result = response.optJSONObject("result");
        if (result == null) throw new McpException(McpException.Kind.INVALID_RESPONSE, "The " + method + " response has no result");
        return result;
    }

    @NonNull
    static String describeError(@NonNull String method, @NonNull JSONObject error) {
        return "The server answered " + method + " with an error: " + McpJson.string(error, "message") + " (" + error.optInt("code") + ")";
    }

}
