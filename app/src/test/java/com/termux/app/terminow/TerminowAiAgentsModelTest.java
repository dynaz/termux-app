package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import com.termux.app.terminow.TerminowToolInstaller.Detection;
import com.termux.app.terminow.TerminowToolInstaller.Tool;
import com.termux.app.terminow.TerminowAiAgentsModel.Conversation;

public class TerminowAiAgentsModelTest {

    private static Detection detectionWith(Object... toolVersionPairs) {
        Detection d = new Detection();
        for (int i = 0; i < toolVersionPairs.length; i += 2) {
            d.versions.put((Tool) toolVersionPairs[i], (String) toolVersionPairs[i + 1]);
        }
        return d;
    }

    @Test
    public void onlyAiToolsAreAiTools() {
        assertTrue(TerminowAiAgentsModel.isAiTool(Tool.CLAUDE));
        assertTrue(TerminowAiAgentsModel.isAiTool(Tool.CODEX));
        assertTrue(TerminowAiAgentsModel.isAiTool(Tool.HERMES));
        assertFalse(TerminowAiAgentsModel.isAiTool(Tool.GIT));
        assertFalse(TerminowAiAgentsModel.isAiTool(Tool.TMUX));
        assertFalse(TerminowAiAgentsModel.isAiTool(Tool.PLUGINS));
    }

    @Test
    public void installedListsOnlyDetectedAiToolsInOrder() {
        Detection d = detectionWith(Tool.GEMINI, "1.2.3", Tool.CLAUDE, "2.0.0", Tool.GIT, "2.40", Tool.CURSOR, "");
        List<Tool> got = TerminowAiAgentsModel.installed(d);
        // order follows the catalog (Claude, Cursor, ... Gemini ...), git excluded (not AI)
        assertEquals(List.of(Tool.CLAUDE, Tool.CURSOR, Tool.GEMINI), got);
    }

    @Test
    public void nullOrEmptyDetectionYieldsNothing() {
        assertTrue(TerminowAiAgentsModel.installed(null).isEmpty());
        assertTrue(TerminowAiAgentsModel.installed(new Detection()).isEmpty());
    }

    @Test
    public void claudeGetsVerifiedConversationOptionsOnly() {
        assertEquals(List.of(Conversation.NEW, Conversation.CONTINUE, Conversation.RESUME),
            TerminowAiAgentsModel.conversations(Tool.CLAUDE, true, true));
        assertEquals(List.of(Conversation.NEW, Conversation.CONTINUE),
            TerminowAiAgentsModel.conversations(Tool.CLAUDE, true, false));
        assertEquals(List.of(Conversation.NEW),
            TerminowAiAgentsModel.conversations(Tool.CLAUDE, false, false));
    }

    @Test
    public void otherAgentsGetNewOnly_noClaudeFlags() {
        // even if claude flags were (wrongly) passed true, a non-Claude agent gets NEW only
        assertEquals(List.of(Conversation.NEW), TerminowAiAgentsModel.conversations(Tool.CODEX, true, true));
        assertEquals(List.of(Conversation.NEW), TerminowAiAgentsModel.conversations(Tool.AIDER, true, true));
    }

    @Test
    public void binaryIsTheRegistryId() {
        assertEquals("claude", TerminowAiAgentsModel.binaryOf(Tool.CLAUDE));
        assertEquals("gemini", TerminowAiAgentsModel.binaryOf(Tool.GEMINI));
        assertEquals("hermes", TerminowAiAgentsModel.binaryOf(Tool.HERMES));
    }

    @Test
    public void rememberedResolvesOnlyWhenStillInstalled() {
        List<Tool> installed = List.of(Tool.CLAUDE, Tool.GEMINI);
        assertEquals(Tool.GEMINI, TerminowAiAgentsModel.resolveRemembered("gemini", installed));
        // uninstalled -> null so the picker is shown again
        assertNull(TerminowAiAgentsModel.resolveRemembered("codex", installed));
        assertNull(TerminowAiAgentsModel.resolveRemembered("", installed));
        assertNull(TerminowAiAgentsModel.resolveRemembered(null, installed));
        // a non-AI id never resolves
        assertNull(TerminowAiAgentsModel.resolveRemembered("git", installed));
    }
}
