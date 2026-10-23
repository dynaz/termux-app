package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collection;

/**
 * The MCP server surface of the TermiNow MCP Bridge: it answers {@code initialize},
 * {@code tools/list} and {@code tools/call} as JSON-RPC, over whatever transport carries the
 * bytes. It exposes the session and managed-task tools of {@link TerminowMcpBridge}, bound to one
 * paired client that the transport authenticated; every call is authorized again in the bridge.
 * Secrets are redacted from everything returned, as a second guard.
 *
 * <p>This is the same server object whether the client reached it over the device-local transport
 * or through the relay: the relay forwards standard MCP JSON-RPC to it and never sees the custom
 * device handshake, so the tool surface a remote client talks to is plain MCP.</p>
 */
public final class TerminowMcpBridgeMcpServer implements TerminowAiBridgeServer.Handler {

    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String SERVER_NAME = "terminow-sessions";

    /** The secrets to redact from anything sent back; read fresh so it follows changes. */
    public interface Secrets {
        @NonNull Collection<String> all();
    }

    private final TerminowMcpBridge mBridge;
    private final String mClientKey;
    private final Secrets mSecrets;
    private final String mVersion;

    public TerminowMcpBridgeMcpServer(@NonNull TerminowMcpBridge bridge, @NonNull String clientKey,
                                      @NonNull Secrets secrets, @NonNull String version) {
        mBridge = bridge;
        mClientKey = clientKey;
        mSecrets = secrets;
        mVersion = version;
    }

    @Nullable
    @Override
    public JSONObject handle(@NonNull JSONObject message) {
        String method = message.optString("method");
        boolean isRequest = message.has("id") && !message.isNull("id");
        if (method.isEmpty()) return null;
        if (!isRequest) return null;
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

        tools.put(tool("sessions_list",
            "List the TermiNow sessions shared with you. Returns session_id, label, mode " +
                "(managed or interactive), whether it is supported for tasks, the environment, folder, allowed " +
                "capabilities, how many tasks are queued, and whether it is busy. No secrets are returned.",
            new JSONObject(), new JSONArray()));

        JSONObject submit = new JSONObject();
        submit.put("session_id", str("The session_id from sessions_list (must be a managed session)."));
        submit.put("request", str("The task for the Claude Code session to carry out."));
        submit.put("idempotency_key", str("Optional. Submitting the same key again returns the first task and does not run it twice."));
        submit.put("timeout_seconds", integer("Optional. How long the managed run may take before it is stopped."));
        tools.put(tool("task_submit",
            "Submit a task to a shared, managed session. One task runs at a time per session; more are " +
                "queued. The task is bound to the session's target at submit time and is never retargeted.",
            submit, new JSONArray().put("session_id").put("request")));

        JSONObject status = new JSONObject();
        status.put("task_id", str("The task_id returned by task_submit."));
        tools.put(tool("task_status",
            "Get a task's state, timings, result, failure reason, and any pending user action.",
            status, new JSONArray().put("task_id")));

        JSONObject output = new JSONObject();
        output.put("task_id", str("The task_id returned by task_submit."));
        output.put("cursor", integer("Where to read output from; use the cursor returned by the previous call. Start at 0."));
        output.put("max_chars", integer("Optional. The most characters to return in this page."));
        tools.put(tool("task_output",
            "Read a page of a task's output from a cursor. Returns the output, the next cursor, whether it " +
                "is the end, and whether output was truncated.",
            output, new JSONArray().put("task_id")));

        JSONObject cancel = new JSONObject();
        cancel.put("task_id", str("The task_id to cancel."));
        tools.put(tool("task_cancel",
            "Ask a task to stop. A queued task is cancelled at once; a running task moves to " +
                "cancellation_requested until the run confirms it.",
            cancel, new JSONArray().put("task_id")));

        JSONObject list = new JSONObject();
        list.put("session_id", str("Optional. Only the tasks of this session."));
        tools.put(tool("task_list",
            "List your tasks, newest first, optionally only for one session.",
            list, new JSONArray()));

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
                case "sessions_list":
                    return toolContent(new JSONObject().put("sessions", mBridge.sessionsList(mClientKey)));
                case "task_submit": {
                    String sessionId = args.optString("session_id");
                    String request = args.optString("request");
                    if (sessionId.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "session_id is required");
                    if (request.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "request is required");
                    return toolContent(mBridge.taskSubmit(mClientKey, sessionId, request,
                        args.optString("idempotency_key"), args.optInt("timeout_seconds")));
                }
                case "task_status": {
                    String taskId = args.optString("task_id");
                    if (taskId.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "task_id is required");
                    return toolContent(mBridge.taskStatus(mClientKey, taskId));
                }
                case "task_output": {
                    String taskId = args.optString("task_id");
                    if (taskId.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "task_id is required");
                    return toolContent(mBridge.taskOutput(mClientKey, taskId, args.optInt("cursor"), args.optInt("max_chars")));
                }
                case "task_cancel": {
                    String taskId = args.optString("task_id");
                    if (taskId.isEmpty()) return toolError(TerminowAiError.BAD_REQUEST, "task_id is required");
                    return toolContent(mBridge.taskCancel(mClientKey, taskId));
                }
                case "task_list": {
                    String sessionId = args.optString("session_id");
                    return toolContent(new JSONObject().put("tasks", mBridge.taskList(mClientKey, sessionId)));
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
    private static JSONObject tool(@NonNull String name, @NonNull String description, @NonNull JSONObject properties,
                                   @NonNull JSONArray required) throws JSONException {
        JSONObject schema = new JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("additionalProperties", false);
        if (required.length() > 0) schema.put("required", required);
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", schema);
    }

    @NonNull
    private static JSONObject str(@NonNull String description) throws JSONException {
        return new JSONObject().put("type", "string").put("description", description);
    }

    @NonNull
    private static JSONObject integer(@NonNull String description) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", description);
    }

    @NonNull
    private JSONObject toolContent(@NonNull Object payload) throws JSONException {
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
