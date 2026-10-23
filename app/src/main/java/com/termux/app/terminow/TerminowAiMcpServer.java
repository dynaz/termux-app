package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;

/**
 * The MCP server the Local AI Bridge speaks to Claude Code: it answers {@code initialize},
 * {@code tools/list} and {@code tools/call} as newline-delimited JSON-RPC, the framing Claude
 * Code uses over stdio. The Ubuntu helper only carries the bytes; every tool call is answered
 * here, after the connection was authenticated and bound to one environment, and authorization is
 * checked again in {@link TerminowAiBridge} for each call. Secrets are redacted from everything
 * that goes back, as a second guard on top of the bridge never putting them there.
 */
public final class TerminowAiMcpServer {

    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String SERVER_NAME = "terminow-hosts";

    /** The secrets to redact from anything sent back; read fresh so it follows credential changes. */
    public interface Secrets {
        @NonNull Collection<String> all();
    }

    private final TerminowAiBridge mBridge;
    private final String mEnvKey;
    private final Secrets mSecrets;
    private final String mVersion;

    public TerminowAiMcpServer(@NonNull TerminowAiBridge bridge, @NonNull String envKey, @NonNull Secrets secrets,
                               @NonNull String version) {
        mBridge = bridge;
        mEnvKey = envKey;
        mSecrets = secrets;
        mVersion = version;
    }

    /**
     * Answer one JSON-RPC message. Returns the response to write back, or {@code null} for a
     * notification, which has no reply. Never throws: a failure becomes a JSON-RPC error.
     */
    @Nullable
    public JSONObject handle(@NonNull JSONObject message) {
        String method = message.optString("method");
        boolean isRequest = message.has("id") && !message.isNull("id");
        if (method.isEmpty()) return null;
        if (!isRequest) return null; // a notification, such as notifications/initialized
        Object id = message.opt("id");
        try {
            switch (method) {
                case "initialize":
                    return result(id, initialize(message.optJSONObject("params")));
                case "ping":
                    return result(id, new JSONObject());
                case "tools/list":
                    return result(id, toolsList());
                case "tools/call":
                    return result(id, toolsCall(message.optJSONObject("params")));
                default:
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (JSONException e) {
            return error(id, -32603, "Internal error");
        }
    }

    @NonNull
    private JSONObject initialize(@Nullable JSONObject params) throws JSONException {
        String version = params != null ? params.optString("protocolVersion", PROTOCOL_VERSION) : PROTOCOL_VERSION;
        JSONObject capabilities = new JSONObject().put("tools", new JSONObject());
        JSONObject serverInfo = new JSONObject().put("name", SERVER_NAME).put("version", mVersion);
        return new JSONObject()
            .put("protocolVersion", version.isEmpty() ? PROTOCOL_VERSION : version)
            .put("capabilities", capabilities)
            .put("serverInfo", serverInfo);
    }

    @NonNull
    private JSONObject toolsList() throws JSONException {
        JSONArray tools = new JSONArray();

        JSONObject hostsList = new JSONObject();
        hostsList.put("name", "hosts_list");
        hostsList.put("description", "List the TermiNow SSH hosts this environment is allowed to use. " +
            "Returns host_id, alias, protocol, hostname, port, username, whether a credential is stored, and the allowed capabilities. No secrets are returned.");
        hostsList.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject())
            .put("additionalProperties", false));
        tools.put(hostsList);

        JSONObject sshTest = new JSONObject();
        sshTest.put("name", "ssh_test");
        sshTest.put("description", "Test the SSH connection to an allowed host by its host_id, using TermiNow's own " +
            "credentials and known-hosts. Returns whether it is reachable, authenticated and the host key is known. No secrets are returned.");
        JSONObject props = new JSONObject().put("host_id", new JSONObject()
            .put("type", "string")
            .put("description", "The host_id from hosts_list."));
        sshTest.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", new JSONArray().put("host_id"))
            .put("additionalProperties", false));
        tools.put(sshTest);

        return new JSONObject().put("tools", tools);
    }

    @NonNull
    private JSONObject toolsCall(@Nullable JSONObject params) throws JSONException {
        if (params == null) return toolError(TerminowAiError.BAD_REQUEST, "Missing params");
        String name = params.optString("name");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        try {
            switch (name) {
                case "hosts_list":
                    return toolContent(new JSONObject().put("hosts", mBridge.hostsList(mEnvKey)));
                case "ssh_test": {
                    String hostId = args.optString("host_id");
                    if (hostId.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "host_id is required");
                    return toolContent(mBridge.sshTest(mEnvKey, hostId));
                }
                default:
                    return toolError(TerminowAiError.BAD_REQUEST, "Unknown tool: " + name);
            }
        } catch (TerminowAiError.Exception e) {
            return toolError(e.error, e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private JSONObject toolContent(@NonNull JSONObject payload) throws JSONException {
        String text = redact(payload.toString());
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", text));
        return new JSONObject().put("content", content).put("isError", false);
    }

    @NonNull
    private JSONObject toolError(@NonNull TerminowAiError code, @Nullable String messageText) throws JSONException {
        JSONObject body = new JSONObject().put("error", code.name()).put("message", redact(messageText == null ? "" : messageText));
        JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", body.toString()));
        return new JSONObject().put("content", content).put("isError", true);
    }

    @NonNull
    private String redact(@Nullable String text) {
        return new TerminowMcpRedactor(mSecrets.all()).redact(text);
    }

    @NonNull
    private static JSONObject result(@NonNull Object id, @NonNull JSONObject result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    @NonNull
    private static JSONObject error(@NonNull Object id, int code, @NonNull String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }
}
