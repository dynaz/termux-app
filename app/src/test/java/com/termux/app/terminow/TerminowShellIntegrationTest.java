package com.termux.app.terminow;

import static com.termux.app.terminow.TerminowShellIntegration.Shell;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The OSC 7 shell integration is added to an rc file exactly once: installing it again changes nothing
 * (idempotent), it is detected when present, and each supported shell gets its own emitter and rc file.
 */
public class TerminowShellIntegrationTest {

    @Test public void installsTheBlockWhenAbsent() {
        String rc = "export FOO=bar\n";
        String out = TerminowShellIntegration.ensureInstalled(Shell.BASH, rc);
        assertTrue(out.startsWith(rc));
        assertTrue(TerminowShellIntegration.isInstalled(out));
        assertTrue(out.contains("]7;file://"));
    }

    @Test public void isIdempotent() {
        String once = TerminowShellIntegration.ensureInstalled(Shell.BASH, "x\n");
        String twice = TerminowShellIntegration.ensureInstalled(Shell.BASH, once);
        assertEquals(once, twice);
        // Only one marker, even after two installs.
        assertEquals(once.indexOf(">>> TermiNow"), once.lastIndexOf(">>> TermiNow"));
    }

    @Test public void handlesEmptyAndMissingContent() {
        assertTrue(TerminowShellIntegration.isInstalled(TerminowShellIntegration.ensureInstalled(Shell.ZSH, "")));
        assertTrue(TerminowShellIntegration.isInstalled(TerminowShellIntegration.ensureInstalled(Shell.FISH, null)));
    }

    @Test public void appendsANewlineBeforeTheBlockWhenMissing() {
        String out = TerminowShellIntegration.ensureInstalled(Shell.BASH, "no trailing newline");
        assertTrue(out.contains("no trailing newline\n# >>> TermiNow"));
    }

    @Test public void notInstalledForPlainContent() {
        assertFalse(TerminowShellIntegration.isInstalled(""));
        assertFalse(TerminowShellIntegration.isInstalled("export FOO=bar"));
    }

    @Test public void eachShellHasItsOwnEmitterAndRcFile() {
        assertTrue(TerminowShellIntegration.block(Shell.BASH).contains("PROMPT_COMMAND"));
        assertTrue(TerminowShellIntegration.block(Shell.ZSH).contains("precmd_functions"));
        assertTrue(TerminowShellIntegration.block(Shell.FISH).contains("--on-variable PWD"));
        assertEquals(".bashrc", TerminowShellIntegration.rcFile(Shell.BASH));
        assertEquals(".zshrc", TerminowShellIntegration.rcFile(Shell.ZSH));
        assertEquals(".config/fish/config.fish", TerminowShellIntegration.rcFile(Shell.FISH));
    }

    @Test public void shellOfRecognisesNameOrPath() {
        assertEquals(Shell.BASH, TerminowShellIntegration.shellOf("/bin/bash"));
        assertEquals(Shell.ZSH, TerminowShellIntegration.shellOf("/usr/bin/zsh"));
        assertEquals(Shell.FISH, TerminowShellIntegration.shellOf("fish"));
        assertEquals(Shell.BASH, TerminowShellIntegration.shellOf(null));      // default
        assertEquals(Shell.BASH, TerminowShellIntegration.shellOf("/bin/sh")); // unknown -> bash profile
    }
}
