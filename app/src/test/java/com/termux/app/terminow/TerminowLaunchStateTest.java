package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import com.termux.terminal.TerminalSession;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class TerminowLaunchStateTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private long mNow = 1_000_000;

    private TerminowLaunchState state() {
        return new TerminowLaunchState(() -> mNow);
    }

    /** A session that is never started: only its identity matters here. */
    private static TerminalSession session() {
        return new TerminalSession("/bin/sh", "/", new String[0], new String[0], 100, null);
    }

    @Test
    public void aStartRunsOnlyWhenItsShellSaysSo() {
        TerminowLaunchState state = state();
        TerminalSession session = session();
        TerminowLaunchState.Launch launch = state.begin("ubuntu|root|bash", session, 60_000, false);
        assertSame(launch, state.starting("ubuntu|root|bash"));
        // Another title, like one of proot-distro or a program, is no sign
        assertFalse(state.onTitle(session, "root@localhost: ~"));
        assertEquals(TerminowLaunchState.State.STARTING, launch.state);
        mNow += 4_000;
        assertTrue("the sign is not shown as a title", state.onTitle(session, TerminowLaunchPlan.READY_TITLE));
        assertEquals(TerminowLaunchState.State.RUNNING, launch.state);
        assertTrue("the empty title right after it is not shown either", state.onTitle(session, ""));
        assertFalse("a title the shell sets later is shown", state.onTitle(session, "root@localhost: ~"));
        assertNull("it is not starting anymore, so another tap starts another", state.starting("ubuntu|root|bash"));
    }

    @Test
    public void aStartThatTakesTooLongFailsOnceAndOnlyAfterItsTime() {
        TerminowLaunchState state = state();
        TerminalSession session = session();
        TerminowLaunchState.Launch launch = state.begin("ubuntu|root|bash", session, 60_000, false);
        // A tab that is not shown has no process yet, however long that takes
        mNow += 600_000;
        assertNull(state.timeout(session));
        state.processStarted(session);
        mNow += 59_999;
        assertNull(state.timeout(session));
        mNow += 1;
        assertSame(launch, state.timeout(session));
        assertEquals(TerminowLaunchState.State.FAILED, launch.state);
        assertEquals(TerminowLaunchState.Failure.TIMEOUT, launch.failure);
        assertNull("it is stopped once", state.timeout(session));
        // A sign that comes after the failure does not make it run
        state.onTitle(session, TerminowLaunchPlan.READY_TITLE);
        assertEquals(TerminowLaunchState.State.FAILED, launch.state);
    }

    @Test
    public void aStartThatEndsBeforeItRunsFailedAndOneThatRanExited() {
        TerminowLaunchState state = state();
        TerminalSession early = session();
        TerminowLaunchState.Launch failed = state.begin("ubuntu|root|bash", early, 60_000, false);
        state.processStarted(early);
        state.onExited(early, 1);
        assertEquals(TerminowLaunchState.State.FAILED, failed.state);
        assertEquals(TerminowLaunchState.Failure.EXITED_EARLY, failed.failure);
        assertEquals(1, failed.exitStatus);

        TerminalSession later = session();
        TerminowLaunchState.Launch ran = state.begin("ubuntu|root|bash", later, 60_000, false);
        state.onTitle(later, TerminowLaunchPlan.READY_TITLE);
        state.onExited(later, 0);
        assertEquals(TerminowLaunchState.State.EXITED, ran.state);
        assertEquals(TerminowLaunchState.Failure.NONE, ran.failure);
    }

    @Test
    public void linuxThatLeavesATabThatWasOpenIsForgotten() {
        TerminowLaunchState state = state();
        TerminalSession tab = session();
        TerminowLaunchState.Launch launch = state.begin("tab|1", tab, 60_000, true);
        assertTrue("a shell that runs started already, so its time runs from the start", launch.processStarted);
        state.onTitle(tab, TerminowLaunchPlan.READY_TITLE);
        state.onLeft(tab);
        assertEquals(TerminowLaunchState.State.EXITED, launch.state);
        assertNull(state.of(tab));
        assertFalse("the shell of the tab sets its titles again", state.onTitle(tab, "local"));
    }

    @Test
    public void aStartInATabThatWasOpenTimesOutFromWhenItWasTyped() {
        TerminowLaunchState state = state();
        TerminalSession tab = session();
        state.begin("tab|2", tab, 60_000, true);
        mNow += 60_000;
        TerminowLaunchState.Launch failed = state.timeout(tab);
        assertNotNull(failed);
        assertTrue(failed.inExistingTab);
    }

    @Test
    public void startsOfDifferentTargetsDoNotBlockEachOther() {
        TerminowLaunchState state = state();
        state.begin("ubuntu|root|bash", session(), 60_000, false);
        assertNull(state.starting("ubuntu|dev|bash"));
        assertNull(state.starting("debian|root|bash"));
        assertNotNull(state.starting("ubuntu|root|bash"));
    }

    @Test
    public void theDiagnosticsHoldNoTitleOrText() {
        TerminowLaunchState state = state();
        TerminalSession session = session();
        state.begin("ubuntu|root|bash", session, 60_000, false);
        state.onTitle(session, "secret-token-in-a-title");
        state.onTitle(session, TerminowLaunchPlan.READY_TITLE);
        state.onExited(session, 0);
        String all = String.join("\n", state.diagnostics());
        assertFalse(all, all.contains("secret"));
        assertTrue(all, all.contains("ubuntu|root|bash"));
        assertTrue(all, all.contains("running after"));
    }

    // ---------------------------------------------------------------------------------------
    // Where to start: what runs in the tab, and what is typed there

    @Test
    public void theForegroundGroupIsReadAfterTheNameWhateverItHas() {
        assertEquals(4321, TerminowLauncher.parseForegroundGroup("1234 (bash) S 1 1234 1234 34816 4321 4194304 0"));
        assertEquals(77, TerminowLauncher.parseForegroundGroup("1234 (a (b) c) S 1 1234 1234 34816 77 4194304 0"));
        assertEquals(-1, TerminowLauncher.parseForegroundGroup("garbage"));
        assertEquals(-1, TerminowLauncher.parseForegroundGroup("1 (x) S 1"));
    }

    @Test
    public void theForegroundGroupOfThisProcessCanBeRead() throws Exception {
        assumeTrue(new File("/proc/self/stat").exists());
        // The pid of this process, from the name of /proc/self
        int pid = Integer.parseInt(new File("/proc/self").getCanonicalFile().getName());
        // A test has no terminal: the group is a number, maybe -1 or 0, never an error
        TerminowLauncher.foregroundGroupOf(pid);
        assertEquals(-1, TerminowLauncher.foregroundGroupOf(-5));
    }

    @Test
    public void onlyAPromptWithNothingTypedIsTakenAsEmpty() {
        assertTrue(TerminowLauncher.looksLikeEmptyPrompt("~ $ ", ""));
        assertTrue(TerminowLauncher.looksLikeEmptyPrompt("root@localhost:~# ", "   "));
        assertTrue(TerminowLauncher.looksLikeEmptyPrompt("% ", ""));
        assertFalse("text after the cursor", TerminowLauncher.looksLikeEmptyPrompt("~ $ ", "ls -la"));
        assertFalse("text before the cursor", TerminowLauncher.looksLikeEmptyPrompt("~ $ git com", ""));
        assertFalse("no prompt", TerminowLauncher.looksLikeEmptyPrompt("", ""));
    }

    @Test
    public void theTypedStartIsOneArgumentForEachWordWhateverTheFolderHas() throws Exception {
        assumeTrue(new File("/bin/sh").exists());
        File prefix = temp.newFolder("usr");
        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdirs());
        File fake = new File(bin, "proot-distro");
        Files.write(fake.toPath(), "#!/bin/sh\nfor a in \"$@\"; do printf '[%s]\\n' \"$a\"; done\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(fake.setExecutable(true));
        String folder = "/root/it's a \"folder\" $HOME `x`";
        String line = TerminowLauncher.enterCommand(prefix.getPath(), "ubuntu", "root", folder, Arrays.asList("--bind", "/a b:/c"));
        assertTrue("kept out of the history", line.startsWith(" "));
        Process process = new ProcessBuilder("/bin/sh", "-c", line).redirectErrorStream(true).start();
        String output = read(process.getInputStream());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        List<String> expected = Arrays.asList("login", "ubuntu", "--user", "root", "--work-dir", folder, "--bind", "/a b:/c", "--",
            "/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL);
        StringBuilder want = new StringBuilder();
        for (String word : expected) want.append('[').append(word).append("]\n");
        assertEquals(want.toString(), output);
    }

    @Test
    public void theShellOfLinuxSaysItRunsWithTheTitleAndThenClearsIt() throws Exception {
        assumeTrue(new File("/bin/sh").exists());
        // Only the sign, without starting a shell
        Process process = new ProcessBuilder("/bin/sh", "-c", TerminowLaunchPlan.READY_SIGN + "echo after").start();
        String output = read(process.getInputStream());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals("\033]0;" + TerminowLaunchPlan.READY_TITLE + "\007\033]0;\007after\n", output);
        assertTrue(TerminowLaunchPlan.NO_XOFF_SHELL.startsWith(TerminowLaunchPlan.READY_SIGN));
        assertTrue(TerminowLaunchPlan.CONTAINER_TMUX_SCRIPT.startsWith(TerminowLaunchPlan.READY_SIGN));
        assertTrue(TerminowLauncher.CLAUDE_SCRIPT.startsWith(TerminowLaunchPlan.READY_SIGN));
    }

    @Test
    public void startsThatSayWhenTheyRunAreTracked() {
        assertTrue(TerminowLauncher.isTrackedStart(new String[]{"login", "ubuntu", "--", "/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL}));
        assertTrue(TerminowLauncher.isTrackedStart(new String[]{"login", "ubuntu", "--", "/bin/sh", "-c", TerminowLauncher.CLAUDE_SCRIPT}));
        assertFalse("a custom command does not say it", TerminowLauncher.isTrackedStart(new String[]{"login", "ubuntu", "--", "/bin/bash", "-lc", "top"}));
    }

    @Test
    public void aNewTabGetsATmuxSessionNoTabShows() {
        assertEquals("ubuntu", TerminowLauncher.freeTmuxName("ubuntu", Collections.emptySet()));
        assertEquals("ubuntu-2", TerminowLauncher.freeTmuxName("ubuntu", new HashSet<>(Arrays.asList("ubuntu", ""))));
        assertEquals("ubuntu-3", TerminowLauncher.freeTmuxName("ubuntu", new HashSet<>(Arrays.asList("ubuntu", "ubuntu-2"))));
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
