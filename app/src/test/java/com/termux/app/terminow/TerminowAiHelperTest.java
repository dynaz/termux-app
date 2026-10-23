package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class TerminowAiHelperTest {

    @Test
    public void guestPathsJoinUnderTheHome() {
        assertEquals("/root/.local/share/terminow-ai/terminow-ai-bridge.py", TerminowAiHelper.guestHelperPath("/root"));
        assertEquals("/home/dev/.config/terminow-ai/token", TerminowAiHelper.guestTokenPath("/home/dev/"));
    }

    @Test
    public void registrationEntryForUnixCarriesSocketAndTokenFileButNoSecret() throws Exception {
        TerminowAiHelper.Transport t = TerminowAiHelper.Transport.unix("/data/data/com.terminow/files/home/.terminow/ai/bridge.sock");
        JSONObject entry = TerminowAiHelper.registrationEntry("/root/.local/share/terminow-ai/terminow-ai-bridge.py", t,
            "/root/.config/terminow-ai/token");
        assertEquals("stdio", entry.optString("type"));
        assertEquals("python3", entry.optString("command"));
        assertEquals("/root/.local/share/terminow-ai/terminow-ai-bridge.py", entry.optJSONArray("args").optString(0));
        JSONObject env = entry.optJSONObject("env");
        assertEquals("unix", env.optString("TERMINOW_AI_TRANSPORT"));
        assertEquals("/data/data/com.terminow/files/home/.terminow/ai/bridge.sock", env.optString("TERMINOW_AI_SOCK"));
        assertEquals("/root/.config/terminow-ai/token", env.optString("TERMINOW_AI_TOKEN_FILE"));
        // The token value itself is never in the config
        assertFalse(env.has("TERMINOW_AI_TOKEN"));
    }

    @Test
    public void registrationEntryForLoopbackCarriesHostAndPort() throws Exception {
        TerminowAiHelper.Transport t = TerminowAiHelper.Transport.loopback(47123);
        JSONObject entry = TerminowAiHelper.registrationEntry("/root/helper.py", t, "/root/.config/terminow-ai/token");
        JSONObject env = entry.optJSONObject("env");
        assertEquals("tcp", env.optString("TERMINOW_AI_TRANSPORT"));
        assertEquals("127.0.0.1", env.optString("TERMINOW_AI_HOST"));
        assertEquals("47123", env.optString("TERMINOW_AI_PORT"));
    }

    @Test
    public void registrationIsIdempotentThroughTheConfigPlanner() throws Exception {
        TerminowAiHelper.Transport t = TerminowAiHelper.Transport.loopback(47123);
        JSONObject entry = TerminowAiHelper.registrationEntry("/root/helper.py", t, "/root/.config/terminow-ai/token");
        // First add writes the server, keeping any other servers that are there
        String existing = "{\"mcpServers\":{\"github\":{\"type\":\"http\",\"url\":\"https://x\"}}}";
        TerminowClaudeConfig.Plan add = TerminowClaudeConfig.planAdd(existing, "user", "/root",
            TerminowAiHelper.SERVER_NAME, entry, null, false);
        assertEquals(TerminowClaudeConfig.Action.ADD, add.action);
        assertTrue(add.otherServers.contains("github"));
        assertTrue(add.newText.contains(TerminowAiHelper.SERVER_NAME));
        assertTrue(add.newText.contains("github"));
        // Adding the same entry again changes nothing
        TerminowClaudeConfig.Plan again = TerminowClaudeConfig.planAdd(add.newText, "user", "/root",
            TerminowAiHelper.SERVER_NAME, entry, null, false);
        assertEquals(TerminowClaudeConfig.Action.UNCHANGED, again.action);
    }
}
