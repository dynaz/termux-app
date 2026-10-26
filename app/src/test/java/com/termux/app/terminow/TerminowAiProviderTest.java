package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import com.termux.app.terminow.TerminowKeyBarModel.Choice;
import com.termux.app.terminow.TerminowKeyBarModel.Mode;

/**
 * The adaptive AI toolbar's detection, resolution and registry: which AI a process is, which AI runs
 * in a tab's process tree, how a per-tab override and detection resolve to a mode, and that each tab
 * resolves on its own so one session's AI never leaks to another.
 */
public class TerminowAiProviderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void providerOfMatchesEachCliByNameOrInstall() {
        assertEquals(Mode.CLAUDE, TerminowKeyBarModel.providerOf(Arrays.asList("claude")));
        assertEquals(Mode.CURSOR, TerminowKeyBarModel.providerOf(Arrays.asList("cursor-agent")));
        assertEquals(Mode.CURSOR, TerminowKeyBarModel.providerOf(Arrays.asList("/opt/cursor-agent/bin/x")));
        assertEquals(Mode.CODEX, TerminowKeyBarModel.providerOf(Arrays.asList("codex")));
        assertEquals(Mode.GEMINI, TerminowKeyBarModel.providerOf(Arrays.asList("gemini")));
        assertEquals(Mode.CODEX, TerminowKeyBarModel.providerOf(Arrays.asList("node", "/usr/lib/node_modules/@openai/codex/bin.js")));
        assertEquals(Mode.GEMINI, TerminowKeyBarModel.providerOf(Arrays.asList("node", "/usr/lib/node_modules/@google/gemini-cli/index.js")));
    }

    @Test
    public void providerOfDoesNotMatchLookalikes() {
        assertNull("bare 'agent' must not match Cursor (ssh-agent etc.)", TerminowKeyBarModel.providerOf(Arrays.asList("agent")));
        assertNull(TerminowKeyBarModel.providerOf(Arrays.asList("ssh-agent")));
        assertNull(TerminowKeyBarModel.providerOf(Arrays.asList("/bin/bash", "-l")));
        assertNull(TerminowKeyBarModel.providerOf(Arrays.asList("vim", "gemini.md")));
        assertNull(TerminowKeyBarModel.providerOf(Arrays.asList()));
    }

    @Test
    public void providerRunningUnderFindsTheAiInTheTabTreeOnly() throws Exception {
        File proc = temp.newFolder("proc");
        // Tab 100: bash -> proot -> bash -> gemini
        process(proc, 100, 1, "bash", "/usr/bin/bash", "-l");
        process(proc, 101, 100, "proot", "proot", "--rootfs=/x");
        process(proc, 102, 101, "bash", "/bin/bash", "-l");
        process(proc, 103, 102, "node", "node", "/usr/lib/node_modules/@google/gemini-cli/index.js");
        // Tab 200: a plain shell
        process(proc, 200, 1, "bash", "bash");

        assertEquals(Mode.GEMINI, TerminowKeyBarModel.providerRunningUnder(proc, 100));
        assertNull("the shell tab has no AI", TerminowKeyBarModel.providerRunningUnder(proc, 200));
        assertNull(TerminowKeyBarModel.providerRunningUnder(proc, 999));
    }

    @Test
    public void resolveRunningStateAndOverrideAndDetection() {
        // Not running is always the shell.
        assertEquals(Mode.TERMINAL, TerminowKeyBarModel.resolve(Choice.CLAUDE, false, Mode.CLAUDE, Mode.CLAUDE));

        // AUTO: launched wins, else detected, else shell.
        assertEquals(Mode.CURSOR, TerminowKeyBarModel.resolve(Choice.AUTO, true, Mode.CURSOR, null));
        assertEquals(Mode.GEMINI, TerminowKeyBarModel.resolve(Choice.AUTO, true, null, Mode.GEMINI));
        assertEquals(Mode.CLAUDE, TerminowKeyBarModel.resolve(Choice.AUTO, true, Mode.CLAUDE, Mode.CURSOR));
        assertEquals(Mode.TERMINAL, TerminowKeyBarModel.resolve(Choice.AUTO, true, null, null));

        // A manual override wins over detection, both ways.
        assertEquals(Mode.CODEX, TerminowKeyBarModel.resolve(Choice.CODEX, true, null, Mode.GEMINI));
        assertEquals(Mode.TERMINAL, TerminowKeyBarModel.resolve(Choice.TERMINAL, true, Mode.CLAUDE, Mode.CLAUDE));
    }

    @Test
    public void eachSessionResolvesIndependently() {
        // Local tab forced to Claude, SSH tab forced to Cursor: neither affects the other.
        Mode local = TerminowKeyBarModel.resolve(Choice.CLAUDE, true, null, null);
        Mode ssh = TerminowKeyBarModel.resolve(Choice.CURSOR, true, null, Mode.GEMINI);
        assertEquals(Mode.CLAUDE, local);
        assertEquals(Mode.CURSOR, ssh);
    }

    @Test
    public void keysAndRegistriesDifferByProvider() {
        assertTrue(TerminowKeyBarModel.keysOf(Mode.GEMINI).contains("@"));
        assertTrue(TerminowKeyBarModel.keysOf(Mode.GEMINI).contains("!"));
        assertFalse(TerminowKeyBarModel.keysOf(Mode.CODEX).contains("SHIFT TAB"));
        assertTrue(TerminowKeyBarModel.keysOf(Mode.CURSOR).contains("SHIFT TAB"));

        assertTrue(hasCommand(TerminowKeyBarModel.commandsForProvider(Mode.GEMINI), "/help"));
        assertTrue(hasCommand(TerminowKeyBarModel.commandsForProvider(Mode.CODEX), "/model"));
        assertTrue(hasCommand(TerminowKeyBarModel.commandsForProvider(Mode.CURSOR), "/plan"));
        assertTrue(TerminowKeyBarModel.commandsForProvider(Mode.TERMINAL).isEmpty());
        assertFalse(TerminowKeyBarModel.shortcutsForProvider(Mode.GEMINI).isEmpty());

        assertEquals("Gemini CLI", TerminowKeyBarModel.displayName(Mode.GEMINI));
        assertEquals("Shell", TerminowKeyBarModel.displayName(Mode.TERMINAL));
    }

    private static boolean hasCommand(java.util.List<TerminowKeyBarModel.Command> commands, String text) {
        for (TerminowKeyBarModel.Command c : commands) if (c.text.equals(text)) return true;
        return false;
    }

    private void process(File proc, int pid, int parent, String name, String... argv) throws Exception {
        File dir = new File(proc, String.valueOf(pid));
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "stat").toPath(), (pid + " (" + name + ") S " + parent + " 1 1 0").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "cmdline").toPath(), (String.join("\0", argv) + "\0").getBytes(StandardCharsets.UTF_8));
    }
}
