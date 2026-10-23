package com.termux.terminal;

import junit.framework.TestCase;

/**
 * What the user types never waits on the main thread, also when the program does not read its
 * input: the writer thread is not even started here, like a program that reads nothing.
 */
public class TerminalSessionInputTest extends TestCase {

    private static TerminalSession runningSession() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 100, null);
        // As if its process started, without starting one
        session.mShellPid = 4242;
        return session;
    }

    public void testTypingIntoAProgramThatReadsNothingDoesNotWait() {
        TerminalSession session = runningSession();
        byte[] key = {'a'};
        long start = System.nanoTime();
        // Far more than the old queue of 4096 bytes, which made the 4097th byte wait for ever
        for (int i = 0; i < 100_000; i++) session.write(key, 0, 1);
        assertTrue("it returned at once", System.nanoTime() - start < 5_000_000_000L);
        assertTrue(session.getIoDiagnostics(), session.getIoDiagnostics().contains("100000 bytes typed and not read yet"));
    }

    public void testAPasteBeyondTheLimitIsDroppedAndSaidSoInsteadOfWaiting() {
        TerminalSession session = runningSession();
        byte[] paste = new byte[3 << 20];
        session.write(paste, 0, paste.length);
        session.write(paste, 0, paste.length);
        session.write(paste, 0, paste.length);
        String diagnostics = session.getIoDiagnostics();
        assertTrue(diagnostics, diagnostics.contains((6 << 20) + " bytes typed and not read yet"));
        assertTrue(diagnostics, diagnostics.contains("input dropped"));
    }

    public void testNothingIsKeptBeforeTheProcessStarted() {
        TerminalSession session = new TerminalSession("/bin/sh", "/", new String[0], new String[0], 100, null);
        session.write(new byte[]{'a'}, 0, 1);
        assertTrue(session.getIoDiagnostics(), session.getIoDiagnostics().contains("0 bytes typed and not read yet"));
    }

    public void testTheDiagnosticsHoldNoInput() {
        TerminalSession session = runningSession();
        byte[] secret = "hunter2-password".getBytes();
        session.write(secret, 0, secret.length);
        assertFalse(session.getIoDiagnostics().contains("hunter2"));
        assertTrue(session.getIoDiagnostics().contains("pid 4242"));
        assertTrue(session.getIoDiagnostics().contains("reader: not started"));
    }
}
