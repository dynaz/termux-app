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
public final class TerminowAiMcpServer implements TerminowAiBridgeServer.Handler {

    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String SERVER_NAME = "terminow-hosts";

    /** The secrets to redact from anything sent back; read fresh so it follows credential changes. */
    public interface Secrets {
        @NonNull Collection<String> all();
    }

    private final TerminowAiBridge mBridge;
    private final String mEnvKey;
    /** The per-process client id from the CLIENT handshake line, or {@code null} for a legacy client. */
    @Nullable private final String mClientId;
    private final Secrets mSecrets;
    private final String mVersion;

    public TerminowAiMcpServer(@NonNull TerminowAiBridge bridge, @NonNull String envKey, @Nullable String clientId,
                               @NonNull Secrets secrets, @NonNull String version) {
        mBridge = bridge;
        mEnvKey = envKey;
        mClientId = clientId;
        mSecrets = secrets;
        mVersion = version;
    }

    /**
     * Answer one JSON-RPC message. Returns the response to write back, or {@code null} for a
     * notification, which has no reply. Never throws: a failure becomes a JSON-RPC error.
     */
    @Nullable
    @Override
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
        sshTest.put("description", "Test the SSH connection to an allowed host, using TermiNow's own credentials and " +
            "known-hosts. Pass host_id, or omit it to use this client's selected host (see select_host). Returns whether " +
            "it is reachable, authenticated and the host key is known. No secrets are returned.");
        JSONObject props = new JSONObject().put("host_id", new JSONObject()
            .put("type", "string")
            .put("description", "The host_id from hosts_list. Optional; defaults to the selected host."));
        sshTest.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("additionalProperties", false));
        tools.put(sshTest);

        JSONObject sshExec = new JSONObject();
        sshExec.put("name", "ssh_exec");
        sshExec.put("description", "Run a command over SSH on an allowed host (needs the run_command capability). " +
            "Pass host_id, or omit it to use this client's selected host (see select_host). The command has all the " +
            "access of the SSH account. Returns exit_code, stdout, stderr, whether output was truncated, and duration. No secrets are returned.");
        JSONObject execProps = new JSONObject();
        execProps.put("host_id", new JSONObject().put("type", "string").put("description", "The host_id from hosts_list. Optional; defaults to the selected host."));
        execProps.put("command", new JSONObject().put("type", "string").put("description", "The command to run, via /bin/sh -lc."));
        execProps.put("timeout_seconds", new JSONObject().put("type", "integer")
            .put("description", "How long to allow, up to " + TerminowAiBridge.EXEC_TIMEOUT_MAX_S + "; default " + TerminowAiBridge.EXEC_TIMEOUT_DEFAULT_S + "."));
        sshExec.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", execProps)
            .put("required", new JSONArray().put("command"))
            .put("additionalProperties", false));
        tools.put(sshExec);

        JSONObject selectHost = new JSONObject();
        selectHost.put("name", "select_host");
        selectHost.put("description", "Choose the current host for this client, so later ssh_exec/ssh_test calls can omit " +
            "host_id. Selecting does not grant access; a host that needs approval must still be approved in TermiNow. " +
            "The selection is private to this client and is never used for another client. Returns the selected host's non-secret details.");
        selectHost.put("inputSchema", new JSONObject()
            .put("type", "object")
            .put("properties", new JSONObject().put("host_id", new JSONObject().put("type", "string").put("description", "The host_id from hosts_list.")))
            .put("required", new JSONArray().put("host_id"))
            .put("additionalProperties", false));
        tools.put(selectHost);

        JSONObject disconnectHost = new JSONObject();
        disconnectHost.put("name", "disconnect_host");
        disconnectHost.put("description", "Clear this client's selected host. Does not close any command already running.");
        disconnectHost.put("inputSchema", new JSONObject()
            .put("type", "object").put("properties", new JSONObject()).put("additionalProperties", false));
        tools.put(disconnectHost);

        JSONObject bridgeStatus = new JSONObject();
        bridgeStatus.put("name", "bridge_status");
        bridgeStatus.put("description", "Report this client's bridge state: whether it is registered and which host it has selected. No secrets are returned.");
        bridgeStatus.put("inputSchema", new JSONObject()
            .put("type", "object").put("properties", new JSONObject()).put("additionalProperties", false));
        tools.put(bridgeStatus);

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
                    String hostId = mBridge.resolveHostId(mEnvKey, mClientId, args.optString("host_id"));
                    return toolContent(mBridge.sshTest(mEnvKey, hostId));
                }
                case "ssh_exec": {
                    String command = args.optString("command");
                    if (command.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "command is required");
                    String hostId = mBridge.resolveHostId(mEnvKey, mClientId, args.optString("host_id"));
                    int timeout = args.optInt("timeout_seconds", TerminowAiBridge.EXEC_TIMEOUT_DEFAULT_S);
                    return toolContent(mBridge.sshExec(mEnvKey, hostId, command, timeout));
                }
                case "select_host": {
                    String hostId = args.optString("host_id");
                    if (hostId.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "host_id is required");
                    return toolContent(mBridge.selectHost(mEnvKey, mClientId, hostId));
                }
                case "disconnect_host":
                    mBridge.clearSelection(mEnvKey, mClientId);
                    return toolContent(new JSONObject().put("disconnected", true));
                case "bridge_status":
                    return toolContent(mBridge.bridgeStatus(mEnvKey, mClientId));
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
