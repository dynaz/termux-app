package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The AI Agent core, apart from the model and the network: host tool validation and de-duplication,
 * extracting a host from an ssh command, the backend request/response shape, and the orchestrator's
 * tool loop driven by a fake provider. It covers Thai and English, IPv4/IPv6/custom port, missing and
 * invalid input, duplicates, connect, confirmation (approve and decline), idempotency on a repeated
 * tool id, and that no credential is put in the messages sent to the model.
 */
public class TerminowAgentTest {

    private static final class FakeHosts implements TerminowAgentTools.Hosts {
        final List<TerminowStore.Host> list = new ArrayList<>();
        int updates;
        @Override public List<TerminowStore.Host> all() { return list; }
        @Override public void add(TerminowStore.Host host) { list.add(host); }
        @Override public void update(TerminowStore.Host host) { updates++; /* edited in place; persist no-op for the fake */ }
    }

    private static final class FakeSessions implements TerminowAgent.Sessions {
        final List<String> names = new ArrayList<>();
        @Override public List<String> names() { return names; }
    }

    private static final class FakeProvider implements TerminowAgent.Provider {
        final Deque<TerminowAiService.Reply> replies = new ArrayDeque<>();
        JSONArray lastMessages;
        int calls;
        @Override public TerminowAiService.Reply chat(JSONArray messages) {
            calls++;
            lastMessages = messages;
            return replies.poll();
        }
    }

    private static final class FakeConfirmer implements TerminowAgent.Confirmer {
        boolean approve = true;
        int addAsks, updateAsks, connectAsks;
        final List<TerminowStore.Host> proposed = new ArrayList<>();
        @Override public boolean confirmAddHost(TerminowStore.Host host) { addAsks++; proposed.add(host); return approve; }
        @Override public boolean confirmUpdateHost(TerminowStore.Host host) { updateAsks++; proposed.add(host); return approve; }
        @Override public boolean confirmConnect(TerminowStore.Host host) { connectAsks++; return approve; }
    }

    private static TerminowAiService.Reply toolReply(String id, String name, String argsJson) throws Exception {
        JSONObject call = new JSONObject().put("id", id).put("name", name).put("arguments", argsJson);
        JSONObject root = new JSONObject().put("content", "").put("finishReason", "tool_calls")
            .put("toolCalls", new JSONArray().put(call));
        return TerminowAiService.parseChat(root.toString());
    }

    private static TerminowAiService.Reply twoToolReply(String id1, String addr1, String id2, String addr2) throws Exception {
        JSONArray calls = new JSONArray()
            .put(new JSONObject().put("id", id1).put("name", "add_host").put("arguments", "{\"address\":\"" + addr1 + "\"}"))
            .put(new JSONObject().put("id", id2).put("name", "add_host").put("arguments", "{\"address\":\"" + addr2 + "\"}"));
        return TerminowAiService.parseChat(new JSONObject().put("content", "").put("toolCalls", calls).toString());
    }

    private static TerminowAiService.Reply textReply(String text) throws Exception {
        return TerminowAiService.parseChat(new JSONObject().put("content", text).put("toolCalls", new JSONArray()).toString());
    }

    // --- Tools ---------------------------------------------------------------------------------

    @Test
    public void prepareHostValidatesAndDefaults() {
        FakeHosts h = new FakeHosts();
        TerminowAgentTools.Result ok = TerminowAgentTools.prepareHost("Ubuntu", "192.168.1.20", 2299, "root", h);
        assertEquals(TerminowAgentTools.Status.READY, ok.status);
        assertEquals("192.168.1.20", ok.host.address);
        assertEquals(2299, ok.host.port);
        assertEquals("root", ok.host.username);
        assertEquals("Ubuntu", ok.host.label);
        assertEquals("prepare does not save", 0, h.list.size());

        // addHost saves; default port 22, empty username allowed (never forced to root), Thai label.
        TerminowAgentTools.Result th = TerminowAgentTools.addHost("เซิร์ฟเวอร์ไทย", "10.0.0.5", null, "", h);
        assertEquals(TerminowAgentTools.Status.ADDED, th.status);
        assertEquals(22, th.host.port);
        assertEquals("", th.host.username);
        assertEquals("เซิร์ฟเวอร์ไทย", th.host.label);
        assertEquals(1, h.list.size());
    }

    @Test
    public void addHostIpv6AndMissingAndInvalid() {
        FakeHosts h = new FakeHosts();
        assertEquals(TerminowAgentTools.Status.ADDED, TerminowAgentTools.addHost("", "2001:db8::1", 22, "root", h).status);

        TerminowAgentTools.Result missing = TerminowAgentTools.prepareHost("x", "", 22, "root", h);
        assertEquals(TerminowAgentTools.Status.NEEDS_INFO, missing.status);
        assertEquals("address", missing.missingField);

        assertEquals(TerminowAgentTools.Status.INVALID, TerminowAgentTools.prepareHost("x", "bad host!", 22, "root", h).status);
        assertEquals(TerminowAgentTools.Status.INVALID, TerminowAgentTools.prepareHost("x", "10.0.0.9", 70000, "root", h).status);
    }

    @Test
    public void prepareHostDeduplicates() {
        FakeHosts h = new FakeHosts();
        assertEquals(TerminowAgentTools.Status.ADDED, TerminowAgentTools.addHost("A", "host.example.com", 22, "root", h).status);
        TerminowAgentTools.Result dup = TerminowAgentTools.prepareHost("B", "HOST.example.com", 22, "ROOT", h);
        assertEquals(TerminowAgentTools.Status.DUPLICATE, dup.status);
        assertEquals("only one host saved", 1, h.list.size());
    }

    @Test
    public void parseSshCommandVariants() {
        TerminowStore.Host a = TerminowAgentTools.parseSshCommand("run this: ssh root@192.168.1.20 -p 2299 then build");
        assertNotNull(a);
        assertEquals("192.168.1.20", a.address);
        assertEquals(2299, a.port);
        assertEquals("root", a.username);

        TerminowStore.Host b = TerminowAgentTools.parseSshCommand("ssh -p 2222 -l deploy build.example.com");
        assertEquals("build.example.com", b.address);
        assertEquals(2222, b.port);
        assertEquals("deploy", b.username);

        TerminowStore.Host v6 = TerminowAgentTools.parseSshCommand("ssh user@[2001:db8::1]");
        assertNotNull(v6);
        assertEquals("2001:db8::1", v6.address);
        assertEquals("user", v6.username);

        assertNull(TerminowAgentTools.parseSshCommand("there is no ssh command here, just text"));
    }

    // --- Backend request / response ------------------------------------------------------------

    @Test
    public void buildChatRequestHasActionModeMessagesAndNoTools() throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", "hi"));
        JSONObject agent = new JSONObject(TerminowAiService.buildChatRequest(messages, true));
        assertEquals("ai.chat", agent.getString("action"));
        assertEquals("agent", agent.getString("mode"));
        assertEquals("hi", agent.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertFalse("the device never sends tool schemas; the backend injects them", agent.has("tools"));
        assertEquals("chat", new JSONObject(TerminowAiService.buildChatRequest(messages, false)).getString("mode"));
    }

    @Test
    public void parseChatReadsToolCallsEchoAndErrors() throws Exception {
        TerminowAiService.Reply reply = toolReply("call_1", "add_host", "{\"address\":\"h\"}");
        assertTrue(reply.wantsTools());
        assertEquals("add_host", reply.toolCalls.get(0).name);
        assertEquals("h", reply.toolCalls.get(0).arguments().getString("address"));
        // The assistant message to echo back carries the tool_calls in OpenAI shape.
        JSONObject echoed = reply.assistantMessage.getJSONArray("tool_calls").getJSONObject(0);
        assertEquals("call_1", echoed.getString("id"));
        assertEquals("add_host", echoed.getJSONObject("function").getString("name"));

        try {
            TerminowAiService.parseChat("{\"error\":\"bad plan\"}");
            fail("expected error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("bad plan"));
        }
    }

    @Test
    public void parseConfigReadsReadiness() throws Exception {
        JSONObject builtin = new JSONObject().put("agent", true).put("agentModel", "m").put("chatModel", "cm");
        TerminowAiService.Config c = TerminowAiService.parseConfig(new JSONObject()
            .put("enabled", true).put("status", "ready").put("builtin", builtin).toString());
        assertTrue(c.enabled);
        assertTrue(c.agent);
        assertEquals("m", c.agentModel);
        assertEquals("cm", c.chatModel);
    }

    // --- Orchestrator --------------------------------------------------------------------------

    @Test
    public void agentAddsHostAfterConfirmation() throws Exception {
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "add_host", "{\"address\":\"192.168.1.20\",\"port\":2299,\"username\":\"root\",\"label\":\"Ubuntu\"}"));
        p.replies.add(textReply("เพิ่มโฮสต์ Ubuntu แล้ว"));
        FakeHosts h = new FakeHosts();
        FakeConfirmer c = new FakeConfirmer();

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("เพิ่ม host 192.168.1.20 user root port 2299 ชื่อ Ubuntu");
        assertEquals("เพิ่มโฮสต์ Ubuntu แล้ว", turn.reply);
        assertEquals(1, turn.added.size());
        assertEquals("192.168.1.20", turn.added.get(0).address);
        assertEquals("confirmed once before saving", 1, c.addAsks);
        assertEquals("the host appears in the store", 1, h.list.size());
    }

    @Test
    public void declinedAddIsNotSaved() throws Exception {
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "add_host", "{\"address\":\"192.168.1.20\"}"));
        p.replies.add(textReply("Okay, I won't add it."));
        FakeHosts h = new FakeHosts();
        FakeConfirmer c = new FakeConfirmer();
        c.approve = false;

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("add host 192.168.1.20");
        assertEquals(1, c.addAsks);
        assertTrue("nothing saved when declined", turn.added.isEmpty());
        assertEquals(0, h.list.size());
        // The model is told the person declined.
        assertTrue(p.lastMessages.toString().toLowerCase(Locale.ROOT).contains("declined"));
    }

    @Test
    public void agentConnectsToExistingHostAfterConfirmation() throws Exception {
        FakeHosts h = new FakeHosts();
        TerminowAgentTools.addHost("Codemagic iOS", "build.codemagic.io", 8022, "builder", h);

        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "connect_host", "{\"host\":\"Codemagic iOS\"}"));
        p.replies.add(textReply("Opening a terminal."));
        FakeConfirmer c = new FakeConfirmer();

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("connect to Codemagic iOS");
        assertEquals(1, turn.connect.size());
        assertEquals("build.codemagic.io", turn.connect.get(0).address);
        assertEquals(1, c.connectAsks);
        assertTrue(turn.added.isEmpty());
    }

    @Test
    public void agentAsksOnlyForMissingFieldAndDoesNotConfirm() throws Exception {
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "add_host", "{\"username\":\"root\"}"));
        p.replies.add(textReply("What is the address of the host?"));
        FakeHosts h = new FakeHosts();
        FakeConfirmer c = new FakeConfirmer();

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("add a host for user root");
        assertEquals("What is the address of the host?", turn.reply);
        assertTrue(turn.added.isEmpty());
        assertEquals("no confirmation for an invalid/incomplete host", 0, c.addAsks);
        assertEquals("nothing saved without an address", 0, h.list.size());
    }

    @Test
    public void aRepeatedToolIdRunsOnce() throws Exception {
        FakeProvider p = new FakeProvider();
        // Same tool-call id twice (a retry), with different addresses: only the first is executed.
        p.replies.add(twoToolReply("dup", "10.0.0.1", "dup", "10.0.0.2"));
        p.replies.add(textReply("done"));
        FakeHosts h = new FakeHosts();
        FakeConfirmer c = new FakeConfirmer();

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("add it");
        assertEquals("a repeated tool id is not executed twice", 1, turn.added.size());
        assertEquals("10.0.0.1", turn.added.get(0).address);
        assertEquals(1, c.addAsks);
    }

    @Test
    public void agentListsHostsWithoutConfirmation() throws Exception {
        FakeHosts h = new FakeHosts();
        TerminowAgentTools.addHost("Ubuntu", "192.168.1.20", 2299, "root", h);
        TerminowAgentTools.addHost("Build", "build.example.com", 22, "deploy", h);
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "list_hosts", "{}"));
        p.replies.add(textReply("You have 2 hosts."));
        FakeConfirmer c = new FakeConfirmer();

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("list my hosts");
        assertEquals("You have 2 hosts.", turn.reply);
        assertEquals("read-only, no confirmation", 0, c.addAsks + c.updateAsks + c.connectAsks);
        // The tool result fed back to the model carries the host list (no credentials). Scan the data
        // messages only; message 0 is the system prompt, which legitimately mentions "password".
        String convo = p.lastMessages.toString();
        assertTrue(convo.contains("192.168.1.20") && convo.contains("build.example.com"));
        StringBuilder data = new StringBuilder();
        for (int i = 1; i < p.lastMessages.length(); i++) data.append(p.lastMessages.optJSONObject(i));
        assertFalse(data.toString().toLowerCase(Locale.ROOT).contains("password"));
    }

    @Test
    public void agentListsSessions() throws Exception {
        FakeSessions sessions = new FakeSessions();
        sessions.names.add("Ubuntu");
        sessions.names.add("local");
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "list_sessions", "{}"));
        p.replies.add(textReply("2 sessions open."));
        TerminowAgent.Turn turn = new TerminowAgent(p, new FakeHosts(), sessions, new FakeConfirmer()).send("what sessions are open?");
        assertEquals("2 sessions open.", turn.reply);
        String convo = p.lastMessages.toString();
        assertTrue(convo.contains("Ubuntu") && convo.contains("local"));
    }

    @Test
    public void agentUpdatesHostAfterConfirmation() throws Exception {
        FakeHosts h = new FakeHosts();
        TerminowAgentTools.addHost("Ubuntu", "192.168.1.20", 22, "root", h);
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "update_host", "{\"host\":\"Ubuntu\",\"port\":2299}"));
        p.replies.add(textReply("Changed the port."));
        FakeConfirmer c = new FakeConfirmer();

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("change Ubuntu port to 2299");
        assertEquals(1, c.updateAsks);
        assertEquals(1, turn.updated.size());
        assertEquals("the change is persisted in place", 2299, h.list.get(0).port);
        assertEquals(1, h.updates);
    }

    @Test
    public void declinedUpdateIsNotSaved() throws Exception {
        FakeHosts h = new FakeHosts();
        TerminowAgentTools.addHost("Ubuntu", "192.168.1.20", 22, "root", h);
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "update_host", "{\"host\":\"Ubuntu\",\"port\":2299}"));
        p.replies.add(textReply("Left it unchanged."));
        FakeConfirmer c = new FakeConfirmer();
        c.approve = false;

        TerminowAgent.Turn turn = new TerminowAgent(p, h, new FakeSessions(), c).send("change Ubuntu port to 2299");
        assertEquals(1, c.updateAsks);
        assertTrue(turn.updated.isEmpty());
        assertEquals("unchanged when declined", 22, h.list.get(0).port);
        assertEquals(0, h.updates);
    }

    @Test
    public void noCredentialIsSentToTheModel() throws Exception {
        FakeProvider p = new FakeProvider();
        p.replies.add(toolReply("c1", "add_host", "{\"address\":\"h\",\"username\":\"root\"}"));
        p.replies.add(textReply("done"));
        FakeHosts h = new FakeHosts();
        new TerminowAgent(p, h, new FakeSessions(), new FakeConfirmer()).send("add host h");
        // The data messages (user text, tool arguments, tool results) carry no credential — the only
        // place "password" may appear is the system instruction that forbids it, which is message 0.
        StringBuilder data = new StringBuilder();
        for (int i = 1; i < p.lastMessages.length(); i++) data.append(p.lastMessages.optJSONObject(i));
        String conversation = data.toString().toLowerCase(Locale.ROOT);
        assertFalse(conversation.contains("password"));
        assertFalse(conversation.contains("private key"));
    }
}
