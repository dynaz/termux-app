package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * An in-app client of the Local AI Bridge, used to <b>verify</b> the bridge end to end without
 * Claude Code: it connects to the loopback listener exactly as the Ubuntu helper would, authenticates
 * with an environment token, and runs the real MCP handshake ({@code initialize}, {@code tools/list})
 * and {@code tools/call hosts_list}, optionally {@code ssh_test} on one host. It proves the Android
 * side — listener, authentication, MCP, tool discovery and host authorization — actually works, so a
 * bound socket is never mistaken for a working integration. It speaks to the same loopback port the
 * helper reads, so it tests the live path, not a mock.
 *
 * <p>No secret is sent anywhere it would not already go: the token is the environment's own token,
 * sent to the app's own listener on 127.0.0.1. The result carries only non-secret facts.</p>
 */
public final class TerminowAiBridgeProbe {

    public static final class Result {
        public boolean reachable;        // the TCP connect succeeded
        public boolean authenticated;    // AUTH was accepted
        public boolean initialized;      // initialize returned a result
        public boolean toolsDiscovered;  // tools/list returned the expected tools
        public String protocolVersion = "";
        public String serverName = "";
        public final List<String> tools = new ArrayList<>();
        public int hostCount = -1;       // from hosts_list, -1 if not reached
        /** ssh_test on the chosen host: null if not requested, else "ok" or the error code. */
        @Nullable public String sshTest;
        @Nullable public String error;   // the first failing step's sanitized reason

        public boolean ok() {
            return reachable && authenticated && initialized && toolsDiscovered && hostCount >= 0;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 8000;

    private TerminowAiBridgeProbe() {}

    /**
     * Run the handshake against {@code host:port} with {@code token}. If {@code sshTestHostId} is not
     * null, also calls {@code ssh_test} on it. Never throws; a failure is recorded in the result.
     */
    @NonNull
    public static Result probe(@NonNull String host, int port, @NonNull String token, @Nullable String sshTestHostId) {
        Result r = new Result();
        if (port <= 0) { r.error = "the bridge is not listening"; return r; }
        if (token.isEmpty()) { r.error = "no environment token"; return r; }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            r.reachable = true;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)) {
                out.write("AUTH " + token + "\n");
                out.flush();
                String authReply = in.readLine();
                if (!"OK".equals(authReply == null ? null : authReply.trim())) {
                    r.error = "the bridge rejected authentication";
                    return r;
                }
                r.authenticated = true;

                JSONObject init = request(in, out, 1, "initialize", new JSONObject()
                    .put("protocolVersion", TerminowAiMcpServer.PROTOCOL_VERSION)
                    .put("capabilities", new JSONObject())
                    .put("clientInfo", new JSONObject().put("name", "terminow-selftest").put("version", "1")));
                JSONObject initResult = resultOf(init);
                if (initResult == null) { r.error = "initialize failed: " + errorText(init); return r; }
                r.initialized = true;
                r.protocolVersion = initResult.optString("protocolVersion");
                r.serverName = initResult.optJSONObject("serverInfo") != null ? initResult.optJSONObject("serverInfo").optString("name") : "";

                // notifications/initialized has no reply.
                out.write(new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString() + "\n");
                out.flush();

                JSONObject list = request(in, out, 2, "tools/list", new JSONObject());
                JSONObject listResult = resultOf(list);
                if (listResult == null) { r.error = "tools/list failed: " + errorText(list); return r; }
                JSONArray toolArray = listResult.optJSONArray("tools");
                if (toolArray != null) for (int i = 0; i < toolArray.length(); i++) {
                    JSONObject t = toolArray.optJSONObject(i);
                    if (t != null) r.tools.add(t.optString("name"));
                }
                r.toolsDiscovered = r.tools.contains("hosts_list");

                JSONObject hosts = request(in, out, 3, "tools/call",
                    new JSONObject().put("name", "hosts_list").put("arguments", new JSONObject()));
                JSONObject hostsText = toolPayload(hosts);
                if (hostsText != null) {
                    JSONArray arr = hostsText.optJSONArray("hosts");
                    r.hostCount = arr == null ? 0 : arr.length();
                } else {
                    r.error = "hosts_list failed: " + errorText(hosts);
                }

                if (sshTestHostId != null && !sshTestHostId.isEmpty()) {
                    JSONObject test = request(in, out, 4, "tools/call",
                        new JSONObject().put("name", "ssh_test").put("arguments", new JSONObject().put("host_id", sshTestHostId)));
                    JSONObject payload = toolPayload(test);
                    if (payload != null && payload.optBoolean("reachable") && payload.optBoolean("authenticated")) r.sshTest = "ok";
                    else if (payload != null && payload.has("error")) r.sshTest = payload.optString("error");
                    else r.sshTest = "failed";
                }
            }
        } catch (IOException e) {
            if (r.error == null) r.error = r.reachable ? "the bridge stopped responding" : "could not reach the listener";
        } catch (org.json.JSONException e) {
            if (r.error == null) r.error = "a bridge reply was not valid";
        } finally {
            try { socket.close(); } catch (IOException ignored) { /* done */ }
        }
        return r;
    }

    @Nullable
    private static JSONObject request(@NonNull BufferedReader in, @NonNull Writer out, int id, @NonNull String method,
                                      @NonNull JSONObject params) throws IOException, org.json.JSONException {
        out.write(new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).toString() + "\n");
        out.flush();
        String line = in.readLine();
        return line == null ? null : new JSONObject(line);
    }

    @Nullable
    private static JSONObject resultOf(@Nullable JSONObject response) {
        return response == null ? null : response.optJSONObject("result");
    }

    @NonNull
    private static String errorText(@Nullable JSONObject response) {
        if (response == null) return "no reply";
        JSONObject error = response.optJSONObject("error");
        return error == null ? "unexpected reply" : error.optString("message", "error");
    }

    /** The JSON payload inside a tools/call text content, or null if it was an error / not parseable. */
    @Nullable
    private static JSONObject toolPayload(@Nullable JSONObject response) {
        JSONObject result = resultOf(response);
        if (result == null) return null;
        JSONArray content = result.optJSONArray("content");
        if (content == null || content.length() == 0) return null;
        JSONObject first = content.optJSONObject(0);
        if (first == null) return null;
        try {
            return new JSONObject(first.optString("text", ""));
        } catch (org.json.JSONException e) {
            return null;
        }
    }
}
