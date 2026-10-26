package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The TermiNow Built-in AI client. The provider, model and API key are managed centrally on the
 * TermiNow backend ({@code cp.accmove.com/terminow/ai.php}); the app never holds a provider key.
 * Every call is authenticated with the account's Firebase ID token — the same token Premium uses —
 * which the UI supplies through {@link Tokens}. The backend enforces the plan, the usage limits and
 * the request timeout, and in agent mode returns the model's tool calls for the device to execute.
 *
 * <p>Only the conversation is sent — the person's messages and the results of tool calls — never the
 * whole host list and never a credential. Request building and response parsing are pure so they can
 * be tested without a network or a token.
 */
public final class TerminowAiService {

    public static final String ENDPOINT = "https://cp.accmove.com/terminow/ai.php";

    /** Supplies the account's Firebase ID token for the Authorization header. */
    public interface Tokens {
        @NonNull String idToken() throws IOException;
    }

    private final Tokens mTokens;

    public TerminowAiService(@NonNull Tokens tokens) {
        this.mTokens = tokens;
    }

    // --- model of the answers -------------------------------------------------------------------

    /** One tool call the model asked for, to be executed on the device. */
    public static final class ToolCall {
        public final String id;
        public final String name;
        /** The arguments as the raw JSON object string the model produced. */
        public final String argumentsJson;

        ToolCall(@NonNull String id, @NonNull String name, @NonNull String argumentsJson) {
            this.id = id;
            this.name = name;
            this.argumentsJson = argumentsJson;
        }

        @Nullable
        public JSONObject arguments() {
            try {
                return new JSONObject(argumentsJson == null || argumentsJson.isEmpty() ? "{}" : argumentsJson);
            } catch (JSONException e) {
                return null;
            }
        }
    }

    /** One assistant turn: its text (may be empty), the tool calls it requested, and the message to echo back. */
    public static final class Reply {
        public final String content;
        public final List<ToolCall> toolCalls;
        /** The assistant message to append to the transcript before the tool results, in OpenAI shape. */
        public final JSONObject assistantMessage;

        Reply(@NonNull String content, @NonNull List<ToolCall> toolCalls, @NonNull JSONObject assistantMessage) {
            this.content = content;
            this.toolCalls = toolCalls;
            this.assistantMessage = assistantMessage;
        }

        public boolean wantsTools() {
            return !toolCalls.isEmpty();
        }
    }

    /** What the backend says the account may do with Built-in AI right now. */
    public static final class Config {
        public final boolean enabled;
        public final boolean agent;
        public final String status;
        public final String message;
        public final String chatModel;
        public final String agentModel;
        public final String upgradeUrl;

        Config(boolean enabled, boolean agent, @NonNull String status, @NonNull String message,
               @NonNull String chatModel, @NonNull String agentModel, @NonNull String upgradeUrl) {
            this.enabled = enabled;
            this.agent = agent;
            this.status = status;
            this.message = message;
            this.chatModel = chatModel;
            this.agentModel = agentModel;
            this.upgradeUrl = upgradeUrl;
        }
    }

    // --- request / response (pure) --------------------------------------------------------------

    @NonNull
    public static String buildChatRequest(@NonNull JSONArray messages, boolean agent) throws JSONException {
        return new JSONObject()
            .put("action", "ai.chat")
            .put("mode", agent ? "agent" : "chat")
            .put("messages", messages)
            .toString();
    }

    @NonNull
    public static String buildConfigRequest() {
        try {
            return new JSONObject().put("action", "ai.config").toString();
        } catch (JSONException e) {
            return "{\"action\":\"ai.config\"}";
        }
    }

    /** Parse a chat/agent answer into the assistant text, its tool calls, and the message to echo back. */
    @NonNull
    public static Reply parseChat(@NonNull String json) throws IOException {
        JSONObject root = parse(json);
        failIfError(root);
        String content = root.optString("content", "");
        List<ToolCall> calls = new ArrayList<>();
        JSONArray toolCalls = root.optJSONArray("toolCalls");
        if (toolCalls != null) {
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject call = toolCalls.optJSONObject(i);
                if (call == null) continue;
                String name = call.optString("name", "");
                if (name.isEmpty()) continue;
                calls.add(new ToolCall(call.optString("id", "call_" + i), name, call.optString("arguments", "{}")));
            }
        }
        return new Reply(content == null ? "" : content, calls, assistantMessage(content, calls));
    }

    /** Rebuild the OpenAI-shape assistant message the backend expects echoed back with the tool results. */
    @NonNull
    private static JSONObject assistantMessage(@Nullable String content, @NonNull List<ToolCall> calls) {
        JSONObject message = new JSONObject();
        try {
            message.put("role", "assistant");
            message.put("content", content == null ? "" : content);
            if (!calls.isEmpty()) {
                JSONArray array = new JSONArray();
                for (ToolCall call : calls) {
                    JSONObject fn = new JSONObject().put("name", call.name).put("arguments", call.argumentsJson);
                    array.put(new JSONObject().put("id", call.id).put("type", "function").put("function", fn));
                }
                message.put("tool_calls", array);
            }
        } catch (JSONException ignored) {
            // role/content are valid strings; a failure here is a programming error.
        }
        return message;
    }

    @NonNull
    public static Config parseConfig(@NonNull String json) throws IOException {
        JSONObject root = parse(json);
        failIfError(root);
        JSONObject builtin = root.optJSONObject("builtin");
        if (builtin == null) builtin = new JSONObject();
        return new Config(
            root.optBoolean("enabled", false),
            builtin.optBoolean("agent", false),
            root.optString("status", ""),
            root.optString("message", ""),
            builtin.optString("chatModel", ""),
            builtin.optString("agentModel", ""),
            root.optString("upgradeUrl", ""));
    }

    // --- network --------------------------------------------------------------------------------

    @NonNull
    public Config config() throws IOException {
        return parseConfig(send(buildConfigRequest()));
    }

    /** One agent turn (tools on). Matches {@link TerminowAgent.Provider} so it can drive the loop. */
    @NonNull
    public Reply chatAgent(@NonNull JSONArray messages) throws IOException {
        return chat(messages, true);
    }

    /** One chat or agent turn. The caller holds the transcript; the backend injects the tools. */
    @NonNull
    public Reply chat(@NonNull JSONArray messages, boolean agent) throws IOException {
        String body;
        try {
            body = buildChatRequest(messages, agent);
        } catch (JSONException e) {
            throw new IOException("Could not build the request: " + e.getMessage(), e);
        }
        return parseChat(send(body));
    }

    @NonNull
    private String send(@NonNull String body) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + mTokens.idToken());
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");

        TerminowCloudHttp.Response response = TerminowCloudHttp.request("POST", ENDPOINT, headers, body);
        if (response.ok()) return response.body;
        // Surface the backend's own message when it sent one (never a secret — the backend redacts).
        String message = messageOf(response.body);
        if (response.code == 401) throw new IOException(message.isEmpty() ? "Sign in to use the assistant." : message);
        if (response.code == 403 || response.code == 409 || response.code == 429)
            throw new IOException(message.isEmpty() ? "Built-in AI is not available right now." : message);
        throw new IOException(message.isEmpty() ? ("The server answered with HTTP " + response.code + ".") : message);
    }

    @NonNull
    private static String messageOf(@Nullable String body) {
        if (body == null || body.isEmpty()) return "";
        try {
            return new JSONObject(body).optString("error", "");
        } catch (JSONException e) {
            return "";
        }
    }

    @NonNull
    private static JSONObject parse(@NonNull String json) throws IOException {
        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            throw new IOException("The server answer was not valid JSON.", e);
        }
    }

    private static void failIfError(@NonNull JSONObject root) throws IOException {
        if (root.has("error")) throw new IOException(root.optString("error", "Built-in AI could not answer."));
    }
}
