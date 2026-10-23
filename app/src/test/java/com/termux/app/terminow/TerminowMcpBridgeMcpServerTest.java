package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** The JSON-RPC MCP surface of the bridge: initialize, tools/list, tools/call, and authorization. */
public class TerminowMcpBridgeMcpServerTest {

    private final List<TerminowMcpBridge.Share> shares = new ArrayList<>();
    private boolean clientPaired = true;
    private TerminowMcpBridge bridge;
    private TerminowMcpBridgeMcpServer server;

    @Before
    public void setUp() {
        bridge = new TerminowMcpBridge(new TerminowMcpBridge.Deps() {
            @NonNull @Override public List<TerminowMcpBridge.Share> shares() { return shares; }
            @Override public boolean clientPaired(@NonNull String clientKey) { return clientPaired; }
            @Override public int maxQueue() { return 8; }
        }, new TerminowMcpBridge.Executor() {
            @Override public void start(@NonNull TerminowMcpBridgeTask task, @NonNull TerminowMcpBridge.Share share,
                                        @NonNull TerminowMcpBridge.Callback callback) {}
            @Override public void cancel(@NonNull String taskId) {}
        }, new TerminowMcpBridge.Persistence() {
            @Override public void save(@NonNull List<TerminowMcpBridgeTask> tasks) {}
            @NonNull @Override public List<TerminowMcpBridgeTask> load() { return new ArrayList<>(); }
        }, () -> 1000L);
        shares.add(new TerminowMcpBridge.Share("s1", "Session one", true, "ubuntu", "root", "/home/root",
            Arrays.asList(TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_READ, TerminowMcpBridge.CAP_CANCEL)));
        server = new TerminowMcpBridgeMcpServer(bridge, "client-a", Collections::emptyList, "1.0");
    }

    private JSONObject rpc(String method, JSONObject params, int id) throws JSONException {
        JSONObject message = new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method);
        if (params != null) message.put("params", params);
        return server.handle(message);
    }

    /** The JSON payload inside a tools/call result's text content. */
    private JSONObject toolPayload(JSONObject response) throws JSONException {
        JSONObject result = response.getJSONObject("result");
        String text = result.getJSONArray("content").getJSONObject(0).getString("text");
        return new JSONObject(text);
    }

    @Test
    public void initializeNamesTheServer() throws JSONException {
        JSONObject response = rpc("initialize", new JSONObject(), 1);
        assertEquals("terminow-sessions", response.getJSONObject("result").getJSONObject("serverInfo").getString("name"));
    }

    @Test
    public void toolsListHasTheSessionAndTaskTools() throws JSONException {
        JSONArray tools = rpc("tools/list", null, 2).getJSONObject("result").getJSONArray("tools");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tools.length(); i++) names.add(tools.getJSONObject(i).getString("name"));
        assertTrue(names.containsAll(Arrays.asList(
            "sessions_list", "task_submit", "task_status", "task_output", "task_cancel", "task_list")));
    }

    @Test
    public void sessionsListReturnsTheSharedSession() throws JSONException {
        JSONObject params = new JSONObject().put("name", "sessions_list").put("arguments", new JSONObject());
        JSONObject response = rpc("tools/call", params, 3);
        assertFalse(response.getJSONObject("result").getBoolean("isError"));
        JSONArray sessions = toolPayload(response).getJSONArray("sessions");
        assertEquals(1, sessions.length());
        assertEquals("s1", sessions.getJSONObject(0).getString("session_id"));
        assertTrue(sessions.getJSONObject(0).getBoolean("supported"));
    }

    @Test
    public void submitThenStatusRoundTrips() throws JSONException {
        JSONObject submitArgs = new JSONObject().put("session_id", "s1").put("request", "list files");
        JSONObject submit = rpc("tools/call", new JSONObject().put("name", "task_submit").put("arguments", submitArgs), 4);
        assertFalse(submit.getJSONObject("result").getBoolean("isError"));
        String taskId = toolPayload(submit).getString("task_id");

        JSONObject statusArgs = new JSONObject().put("task_id", taskId);
        JSONObject status = rpc("tools/call", new JSONObject().put("name", "task_status").put("arguments", statusArgs), 5);
        assertEquals(taskId, toolPayload(status).getString("task_id"));
        assertEquals("running", toolPayload(status).getString("state"));
    }

    @Test
    public void unknownToolIsAnError() throws JSONException {
        JSONObject params = new JSONObject().put("name", "nope").put("arguments", new JSONObject());
        JSONObject response = rpc("tools/call", params, 6);
        assertTrue(response.getJSONObject("result").getBoolean("isError"));
    }

    @Test
    public void unpairedClientIsRefusedThroughTheSurface() throws JSONException {
        clientPaired = false;
        JSONObject params = new JSONObject().put("name", "sessions_list").put("arguments", new JSONObject());
        JSONObject response = rpc("tools/call", params, 7);
        assertTrue(response.getJSONObject("result").getBoolean("isError"));
        assertTrue(toolPayload(response).getString("error").contains("CLIENT_NOT_AUTHORIZED"));
    }

    @Test
    public void unknownMethodReturnsJsonRpcError() throws JSONException {
        JSONObject response = rpc("does/notExist", null, 8);
        assertEquals(-32601, response.getJSONObject("error").getInt("code"));
    }

    @SuppressWarnings("unused")
    private static Collection<String> noSecrets() {
        return Collections.emptyList();
    }
}
