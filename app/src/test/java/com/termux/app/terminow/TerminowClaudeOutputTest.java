package com.termux.app.terminow;

import static com.termux.app.terminow.TerminowKeyBarModel.isClaudeInOutput;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Claude Code running over SSH (so it is not in this device's process tree) is recognised from the
 * markers of its interface in the recent terminal output, and a plain shell or an exited Claude far
 * up the scrollback is not mistaken for it.
 */
public class TerminowClaudeOutputTest {

    @Test public void detectsShortcutsHint() {
        assertTrue(isClaudeInOutput("admin@server:~$ claude\n\n  │ > \n\n  ? for shortcuts\n"));
    }

    @Test public void detectsWelcomeBanner() {
        assertTrue(isClaudeInOutput("✻ Welcome to Claude Code!\n\n  /help for help\n"));
    }

    @Test public void ignoresPlainShell() {
        assertFalse(isClaudeInOutput("admin@server:~$ ls -la\ntotal 20\ndrwxr-xr-x 2 admin admin\nadmin@server:~$ "));
    }

    @Test public void ignoresNullAndEmpty() {
        assertFalse(isClaudeInOutput(null));
        assertFalse(isClaudeInOutput(""));
    }

    @Test public void doesNotMatchMarkerFarUpTheScrollback() {
        StringBuilder sb = new StringBuilder("? for shortcuts\n");
        for (int i = 0; i < 3000; i++) sb.append('x');       // scrolls the marker out of the last screen
        sb.append("\nadmin@server:~$ ");
        assertFalse(isClaudeInOutput(sb.toString()));
    }

    @Test public void matchesMarkerInTheRecentTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) sb.append('x');
        sb.append("\n  │ > \n  ? for shortcuts\n");
        assertTrue(isClaudeInOutput(sb.toString()));
    }
}
