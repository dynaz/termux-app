package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The AI Agent orchestrator. It holds the transcript, asks the TermiNow backend (which holds the
 * provider key and injects the tool schemas) for the next turn, runs any tool calls <b>on the
 * device</b> through {@link TerminowAgentTools}, feeds the results back, and repeats until the model
 * replies with text. It manages hosts and opens terminals only — no shell, no deletes, no credential
 * export. Every host saved and every connection opened is confirmed by the person first, through
 * {@link Confirmer}. The model never receives an SSH password or private key.
 *
 * <p>The backend caps the tool loop too; {@link #MAX_STEPS} is the device-side guard. A tool call id
 * is executed at most once, so a retried turn does not repeat a mutation. The provider, the host
 * store and the confirmer are interfaces so the loop is tested without a network, Android, or a UI.
 */
public final class TerminowAgent {

    /** One model turn in agent mode: the real one wraps {@link TerminowAiService#chat}. */
    public interface Provider {
        @NonNull TerminowAiService.Reply chat(@NonNull JSONArray messages) throws IOException;
    }

    /** Asks the person to confirm a host before it is saved or a connection before it is opened. */
    public interface Confirmer {
        boolean confirmAddHost(@NonNull TerminowStore.Host proposed);

        boolean confirmConnect(@NonNull TerminowStore.Host host);
    }

    public static final String SYSTEM_PROMPT =
        "You are the TermiNow assistant. You help the person manage their SSH hosts and open terminals. "
        + "Use the tools to add hosts and open connections; do not invent results. Ask only for a required "
        + "field that is missing (the host address is required; the port defaults to 22; the username may be "
        + "left empty). Never ask for or include a password or a private key — credentials stay on the device. "
        + "Reply in the person's language (Thai or English). Be brief. You cannot delete hosts, run shell "
        + "commands, or read credentials.";

    private static final int MAX_STEPS = 6;

    private final Provider mProvider;
    private final TerminowAgentTools.Hosts mHosts;
    private final Confirmer mConfirmer;
    private final JSONArray mMessages = new JSONArray();
    private final Set<String> mExecuted = new HashSet<>();

    public TerminowAgent(@NonNull Provider provider, @NonNull TerminowAgentTools.Hosts hosts, @NonNull Confirmer confirmer) {
        this(provider, hosts, confirmer, SYSTEM_PROMPT);
    }

    public TerminowAgent(@NonNull Provider provider, @NonNull TerminowAgentTools.Hosts hosts,
                         @NonNull Confirmer confirmer, @NonNull String systemPrompt) {
        this.mProvider = provider;
        this.mHosts = hosts;
        this.mConfirmer = confirmer;
        add("system", systemPrompt);
    }

    /** What one user message produced: the reply text and the hosts added / to connect this turn. */
    public static final class Turn {
        public String reply = "";
        public final List<TerminowStore.Host> added = new ArrayList<>();
        public final List<TerminowStore.Host> connect = new ArrayList<>();
    }

    /** Send a user message and run the tool loop until the model replies with text. */
    @NonNull
    public Turn send(@NonNull String userMessage) throws IOException {
        add("user", userMessage);
        Turn turn = new Turn();
        for (int step = 0; step < MAX_STEPS; step++) {
            TerminowAiService.Reply reply = mProvider.chat(mMessages);
            mMessages.put(reply.assistantMessage);
            if (!reply.wantsTools()) {
                turn.reply = reply.content;
                return turn;
            }
            for (TerminowAiService.ToolCall call : reply.toolCalls) {
                String result = call.id != null && !mExecuted.add(call.id)
                    ? result("duplicate", "That action was already handled.")
                    : execute(call, turn);
                addTool(call.id, call.name, result);
            }
        }
        turn.reply = "I stopped after several steps without finishing. Please try again or be more specific.";
        return turn;
    }

    private String execute(@NonNull TerminowAiService.ToolCall call, @NonNull Turn turn) {
        JSONObject args = call.arguments();
        if (args == null) return result("error", "The tool arguments were not valid JSON.");
        switch (call.name) {
            case "add_host":
                return runAdd(args.optString("label", ""), args.optString("address", ""),
                    args.has("port") ? args.optInt("port") : null, args.optString("username", ""), turn);
            case "connect_host": {
                TerminowStore.Host host = find(args.optString("host", ""));
                if (host == null) return result("invalid", "No saved host matches that. Add it first or check the name.");
                if (!mConfirmer.confirmConnect(host)) return result("declined", "The person declined to open the connection.");
                turn.connect.add(host);
                return result("ok", "Opening a terminal to " + host.getTitle() + ".");
            }
            default:
                return result("error", "Unknown tool: " + call.name);
        }
    }

    private String runAdd(@NonNull String label, @NonNull String address, @Nullable Integer port,
                          @NonNull String username, @NonNull Turn turn) {
        TerminowAgentTools.Result r = TerminowAgentTools.prepareHost(label, address, port, username, mHosts);
        if (r.status == TerminowAgentTools.Status.READY && r.host != null) {
            if (!mConfirmer.confirmAddHost(r.host)) return result("declined", "The person declined to add the host.");
            mHosts.add(r.host);
            turn.added.add(r.host);
            return describe(TerminowAgentTools.Status.ADDED, "Added host " + r.host.getTitle(), r.host, "");
        }
        return describe(r.status, r.message, r.host, r.missingField);
    }

    @NonNull
    private static String describe(@NonNull TerminowAgentTools.Status status, @NonNull String message,
                                   @Nullable TerminowStore.Host host, @NonNull String missingField) {
        try {
            JSONObject out = new JSONObject();
            out.put("status", status.name().toLowerCase(Locale.ROOT));
            out.put("message", message);
            if (!missingField.isEmpty()) out.put("missing_field", missingField);
            if (host != null) {
                JSONObject h = new JSONObject();
                h.put("label", host.label);
                h.put("address", host.address);
                h.put("port", host.port);
                h.put("username", host.username);
                out.put("host", h);
            }
            return out.toString();
        } catch (JSONException e) {
            return result(status.name().toLowerCase(Locale.ROOT), message);
        }
    }

    @Nullable
    private TerminowStore.Host find(@NonNull String reference) {
        String ref = reference.trim();
        if (ref.isEmpty()) return null;
        for (TerminowStore.Host host : mHosts.all())
            if (host.address.equalsIgnoreCase(ref) || host.label.equalsIgnoreCase(ref)) return host;
        return null;
    }

    // --- conversation --------------------------------------------------------------------------

    private void add(@NonNull String role, @NonNull String content) {
        try {
            mMessages.put(new JSONObject().put("role", role).put("content", content));
        } catch (JSONException ignored) {
            // role/content are always valid strings.
        }
    }

    private void addTool(@Nullable String toolCallId, @Nullable String name, @NonNull String content) {
        try {
            mMessages.put(new JSONObject().put("role", "tool").put("tool_call_id", toolCallId == null ? "" : toolCallId)
                .put("name", name == null ? "" : name).put("content", content));
        } catch (JSONException ignored) {
            // ignore
        }
    }

    @NonNull
    private static String result(@NonNull String status, @NonNull String message) {
        try {
            return new JSONObject().put("status", status).put("message", message).toString();
        } catch (JSONException e) {
            return "{\"status\":\"" + status + "\"}";
        }
    }
}
