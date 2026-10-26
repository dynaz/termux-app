package com.termux.app.terminow;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The post-quantum advisory is found in the SSH terminal output even when ANSI escape codes are
 * interleaved and the phrase is split across output chunks, and never from unrelated output. The
 * detector keeps only a small rolling window, so fragments too far apart do not form a false match.
 */
public class TerminowSshSecurityTest {

    private static final String WARNING =
        "** WARNING: connection is not using a post-quantum key exchange algorithm.\r\n"
            + "This session may be vulnerable to \"store now, decrypt later\" attacks.\r\n";

    /** Feed one string as a single chunk. */
    private static boolean feedOnce(String out) {
        return new TerminowSshSecurity().onOutput(out);
    }

    @Test public void detectsWholeWarningInOneChunk() {
        assertTrue(feedOnce(WARNING));
    }

    @Test public void detectsWhenSplitAcrossChunks() {
        TerminowSshSecurity sec = new TerminowSshSecurity();
        assertFalse(sec.onOutput("** WARNING: connection is not "));
        assertFalse(sec.onOutput("using a post-quantum "));
        assertTrue(sec.onOutput("key exchange algorithm.\r\n"));
        assertTrue(sec.isDetected());
    }

    @Test public void detectsSplitBetweenEveryCharacter() {
        TerminowSshSecurity sec = new TerminowSshSecurity();
        boolean seen = false;
        for (int i = 0; i < WARNING.length(); i++) seen |= sec.onOutput(String.valueOf(WARNING.charAt(i)));
        assertTrue(seen);
        assertTrue(sec.isDetected());
    }

    @Test public void detectsWithAnsiColorCodesInterleaved() {
        // Yellow/bold around the advisory, reset after, as a client might colorize stderr.
        String colored = "\u001B[1;33m** WARNING: \u001B[0mconnection is not using a "
            + "\u001B[33mpost-quantum key exchange\u001B[0m algorithm.\r\n";
        assertTrue(feedOnce(colored));
    }

    @Test public void detectsWhenAnsiEscapeSplitAcrossChunkBoundary() {
        TerminowSshSecurity sec = new TerminowSshSecurity();
        // The CSI sequence "\u001B[33m" is cut in half across the two chunks.
        assertFalse(sec.onOutput("connection is not using a \u001B[3"));
        assertTrue(sec.onOutput("3mpost-quantum key exchange algorithm"));
        assertTrue(sec.isDetected());
    }

    @Test public void isCaseInsensitive() {
        assertTrue(feedOnce("Connection Is Not Using A Post-Quantum Key Exchange algorithm"));
    }

    @Test public void ignoresUnrelatedOutput() {
        TerminowSshSecurity sec = new TerminowSshSecurity();
        assertFalse(sec.onOutput("Welcome to Ubuntu 24.04 LTS\r\n"));
        assertFalse(sec.onOutput("Last login: Sat Oct 25 12:30:00 2026\r\n"));
        assertFalse(sec.onOutput("admin@server:~$ "));
        assertFalse(sec.isDetected());
    }

    @Test public void reportsDetectionOnlyOnce() {
        TerminowSshSecurity sec = new TerminowSshSecurity();
        assertTrue(sec.onOutput(WARNING));
        assertFalse(sec.onOutput(WARNING));
        assertTrue(sec.isDetected());
    }

    @Test public void doesNotMatchFragmentsSeparatedByMoreThanTheWindow() {
        TerminowSshSecurity sec = new TerminowSshSecurity();
        assertFalse(sec.onOutput("connection is not using a "));
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 400; i++) filler.append('x');
        assertFalse(sec.onOutput(filler.toString()));
        assertFalse(sec.onOutput("post-quantum key exchange algorithm"));
        assertFalse(sec.isDetected());
    }
}
