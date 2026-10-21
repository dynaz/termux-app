package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.SpecialButton;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TerminowKeyBarModelTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static List<String> keysOf(TerminowKeyBarModel.Mode mode) {
        List<String> keys = new ArrayList<>(TerminowKeyBarModel.keysOf(mode));
        keys.addAll(TerminowKeyBarModel.NAVIGATION_KEYS);
        return keys;
    }

    @Test
    public void theKeysOfATerminalAndOfClaudeCodeWithTheArrowsTogether() {
        assertEquals(Arrays.asList("ESC", "TAB", "CTRL", "ALT", "/", "LEFT", "DOWN", "UP", "RIGHT"),
            keysOf(TerminowKeyBarModel.Mode.TERMINAL));
        assertEquals(Arrays.asList("ESC", "TAB", "SHIFT TAB", "CTRL", "ALT", "/", "LEFT", "DOWN", "UP", "RIGHT"),
            keysOf(TerminowKeyBarModel.Mode.CLAUDE));
        for (String arrow : TerminowKeyBarModel.NAVIGATION_KEYS) assertTrue(TerminowKeyBarModel.repeats(arrow));
        assertFalse(TerminowKeyBarModel.repeats("ESC"));
    }

    /**
     * What each key sends: a key with a key code, a modifier of the bar, a character, or More,
     * which opens the panel. A name like "SHIFT TAB" is never typed as text.
     */
    private static void assertRealInput(String key) {
        for (String part : key.split(" ")) {
            boolean real = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(part) ||
                Arrays.asList(SpecialButton.CTRL.getKey(), SpecialButton.ALT.getKey(), SpecialButton.SHIFT.getKey(), SpecialButton.FN.getKey()).contains(part) ||
                part.codePointCount(0, part.length()) == 1 ||
                (part.equals(TerminowKeyBarModel.MORE) && key.equals(part));
            assertTrue("\"" + part + "\" of \"" + key + "\" would be typed as text", real);
        }
    }

    @Test
    public void everyKeySendsInputAndNeverItsName() throws Exception {
        for (TerminowKeyBarModel.Mode mode : TerminowKeyBarModel.Mode.values())
            for (String key : keysOf(mode)) assertRealInput(key);
        for (TerminowKeyBarModel.Shortcut shortcut : TerminowKeyBarModel.CLAUDE_SHORTCUTS) assertRealInput(shortcut.keys);
        for (TerminowKeyBarModel.Shortcut shortcut : TerminowKeyBarModel.TERMINAL_SHORTCUTS) assertRealInput(shortcut.keys);
    }

    @Test
    public void ctrlAndAltAreOnForTheNextKeyOrLockedWhenHeld() {
        assertTrue(TerminowKeyBarModel.isModifier("CTRL"));
        assertTrue(TerminowKeyBarModel.isModifier("ALT"));
        assertFalse(TerminowKeyBarModel.isModifier("SHIFT TAB"));
        TerminowKeyBarModel.Modifier ctrl = new TerminowKeyBarModel.Modifier();
        assertFalse(ctrl.consume());
        // A tap: for one key, then off
        ctrl.tap();
        assertTrue(ctrl.isOn());
        assertFalse(ctrl.isLocked());
        assertTrue(ctrl.consume());
        assertFalse(ctrl.isOn());
        assertFalse(ctrl.consume());
        // A tap again before a key: off
        ctrl.tap();
        ctrl.tap();
        assertFalse(ctrl.isOn());
        // Held: for every key until it is tapped or held again
        ctrl.hold();
        assertTrue(ctrl.isLocked());
        assertTrue(ctrl.consume());
        assertTrue(ctrl.consume());
        ctrl.tap();
        assertFalse(ctrl.isOn());
        ctrl.hold();
        ctrl.hold();
        assertFalse(ctrl.isOn());
        // Another tab
        ctrl.hold();
        ctrl.reset();
        assertFalse(ctrl.consume());
    }

    @Test
    public void aCommandIsOnlyPutOnThePrompt() {
        for (TerminowKeyBarModel.Command command : TerminowKeyBarModel.CLAUDE_COMMANDS) {
            String text = TerminowKeyBarModel.promptText(command);
            assertTrue(text, text.startsWith("/"));
            assertFalse("never sent with Enter: " + text, text.contains("\r") || text.contains("\n"));
            // Flags of the command line, like --resume, are not commands of the prompt
            assertFalse(text, text.contains("--"));
        }
    }

    @Test
    public void theKeysFollowWhatTheTabRuns() {
        TerminowKeyBarModel.Choice auto = TerminowKeyBarModel.Choice.AUTO;
        assertEquals(TerminowKeyBarModel.Mode.CLAUDE, TerminowKeyBarModel.resolve(auto, true, true, false));
        assertEquals(TerminowKeyBarModel.Mode.CLAUDE, TerminowKeyBarModel.resolve(auto, true, false, true));
        assertEquals(TerminowKeyBarModel.Mode.TERMINAL, TerminowKeyBarModel.resolve(auto, true, false, false));
        // Claude Code ended: the tab is a terminal again
        assertEquals(TerminowKeyBarModel.Mode.TERMINAL, TerminowKeyBarModel.resolve(auto, false, true, true));
        assertEquals(TerminowKeyBarModel.Mode.TERMINAL, TerminowKeyBarModel.resolve(TerminowKeyBarModel.Choice.CLAUDE, false, false, false));
        // What the user chose wins while the tab runs
        assertEquals(TerminowKeyBarModel.Mode.TERMINAL, TerminowKeyBarModel.resolve(TerminowKeyBarModel.Choice.TERMINAL, true, true, true));
        assertEquals(TerminowKeyBarModel.Mode.CLAUDE, TerminowKeyBarModel.resolve(TerminowKeyBarModel.Choice.CLAUDE, true, false, false));
    }

    @Test
    public void claudeCodeIsKnownByItsProgram() {
        assertTrue(TerminowKeyBarModel.isClaudeProcess(Arrays.asList("claude", "--resume")));
        assertTrue(TerminowKeyBarModel.isClaudeProcess(Arrays.asList("/root/.local/share/claude/versions/2.1.303")));
        assertTrue(TerminowKeyBarModel.isClaudeProcess(Arrays.asList("node", "/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js")));
        // The shell that starts it, and tools it runs, are not Claude Code
        assertFalse(TerminowKeyBarModel.isClaudeProcess(Arrays.asList("/bin/sh", "-c", "exec claude \"$@\"", "claude")));
        assertFalse(TerminowKeyBarModel.isClaudeProcess(Arrays.asList("/bin/bash", "-c", "source /root/.claude/shell-snapshots/x.sh")));
        assertFalse(TerminowKeyBarModel.isClaudeProcess(Arrays.asList("vim", "claude.md")));
    }

    private void process(File proc, int pid, int parent, String name, String... argv) throws Exception {
        File dir = new File(proc, String.valueOf(pid));
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "stat").toPath(), (pid + " (" + name + ") S " + parent + " 1 1 0").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "cmdline").toPath(), (String.join("\0", argv) + "\0").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void claudeCodeIsFoundOnlyInTheProcessesOfTheTab() throws Exception {
        File proc = temp.newFolder("proc");
        // The tab: bash, then proot, then bash in Ubuntu, then claude
        process(proc, 100, 1, "bash", "/data/data/com.terminow/files/usr/bin/bash", "-l");
        process(proc, 101, 100, "proot", "proot", "--rootfs=/x");
        process(proc, 102, 101, "a (b) c", "/bin/bash", "-l");
        process(proc, 103, 102, "claude", "claude");
        // Another tab, with no Claude Code
        process(proc, 200, 1, "bash", "bash");
        process(proc, 201, 200, "vim", "vim", "notes");
        new File(proc, "self").mkdirs();
        assertTrue(TerminowKeyBarModel.isClaudeRunningUnder(proc, 100));
        assertFalse(TerminowKeyBarModel.isClaudeRunningUnder(proc, 200));
        assertFalse(TerminowKeyBarModel.isClaudeRunningUnder(proc, 999));
        // It ended
        for (File file : new File(proc, "103").listFiles()) assertTrue(file.delete());
        assertTrue(new File(proc, "103").delete());
        assertFalse(TerminowKeyBarModel.isClaudeRunningUnder(proc, 100));
    }

    @Test
    public void theParentIsReadAfterANameWithParentheses() throws Exception {
        File stat = temp.newFile("stat");
        Files.write(stat.toPath(), "42 (my (odd) name) S 7 42 42 0".getBytes(StandardCharsets.UTF_8));
        assertEquals(7, TerminowKeyBarModel.parentOf(stat));
    }

    @Test
    public void commandsAreThoseOfTheInstalledVersion() {
        assertEquals(TerminowKeyBarModel.CLAUDE_COMMANDS, TerminowKeyBarModel.commandsFor("2.1.303 (Claude Code)"));
        // An old version has none of those that came later
        assertTrue(TerminowKeyBarModel.commandsFor("0.2.9 (Claude Code)").isEmpty());
        // Not known: all, and the panel says that they were not checked
        assertEquals(TerminowKeyBarModel.CLAUDE_COMMANDS, TerminowKeyBarModel.commandsFor(null));
        assertEquals(TerminowKeyBarModel.CLAUDE_COMMANDS, TerminowKeyBarModel.commandsFor("unknown"));
        assertTrue(TerminowKeyBarModel.commandOf("/login") != null);
        assertArrayEqualsInt(new int[]{2, 1, 303}, TerminowKeyBarModel.parseVersion("2.1.303 (Claude Code)"));
        assertTrue(TerminowKeyBarModel.parseVersion("Claude Code") == null);
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(Arrays.toString(expected), Arrays.toString(actual));
    }

    @Test
    public void theCommandsOfTheDocsAreThereOnceAndNoneThatWereRemoved() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (TerminowKeyBarModel.Command command : TerminowKeyBarModel.CLAUDE_COMMANDS)
            assertTrue("twice: " + command.text, seen.add(command.text));
        for (String command : Arrays.asList("/rewind", "/context", "/effort", "/plan", "/add-dir", "/usage", "/btw", "/diff"))
            assertTrue(command, seen.contains(command));
        // Removed from Claude Code, or an alias of another
        for (String command : Arrays.asList("/vim", "/pr-comments", "/ultraplan", "/cost"))
            assertFalse(command, seen.contains(command));
    }

    @Test
    public void aCommandThatNeedsMoreGetsASpaceAndNeverAnEnter() {
        TerminowKeyBarModel.Command addDir = TerminowKeyBarModel.commandOf("/add-dir");
        assertTrue(addDir.needsArgument());
        assertEquals("/add-dir ", TerminowKeyBarModel.promptText(addDir));
        // An optional argument: the command alone runs
        assertEquals("/compact", TerminowKeyBarModel.promptText(TerminowKeyBarModel.commandOf("/compact")));
    }

    @Test
    public void theFilterFindsANameOrWhatItDoes() {
        TerminowKeyBarModel.Command rewind = TerminowKeyBarModel.commandOf("/rewind");
        assertTrue(TerminowKeyBarModel.matches(rewind, "Go back to an earlier point", ""));
        assertTrue(TerminowKeyBarModel.matches(rewind, "Go back to an earlier point", "/rew"));
        assertTrue(TerminowKeyBarModel.matches(rewind, "Go back to an earlier point", "EARLIER"));
        assertFalse(TerminowKeyBarModel.matches(rewind, "Go back to an earlier point", "model"));
    }
}
