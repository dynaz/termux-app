package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The pure parts of the Mode-B host readiness check: the command it runs and how it reads the result. */
public class TerminowMcpPreflightTest {

    @Test
    public void commandPassesFolderAsAQuotedArgumentAgainstInjection() {
        String cmd = TerminowMcpPreflight.buildCommand("/home/me");
        assertTrue("runs under bash -lc", cmd.startsWith("bash -lc '"));
        assertTrue("folder is a single-quoted trailing arg", cmd.endsWith(" preflight '/home/me'"));

        String evil = TerminowMcpPreflight.buildCommand("x'; rm -rf / #");
        assertTrue("an embedded quote is escaped, not left to close the string",
            evil.endsWith(" preflight 'x'\\''; rm -rf / #'"));
    }

    @Test
    public void parseReadsEachCheck() {
        TerminowMcpPreflight.Result r = TerminowMcpPreflight.parse(
            "OS=Linux\nSHELL=yes\nCLAUDE=yes\nFOLDER=yes\n");
        assertTrue(r.connected);
        assertEquals("Linux", r.os);
        assertTrue(r.shellOk);
        assertTrue(r.claudeInstalled);
        assertTrue(r.folderExists);
        assertTrue("all green means ready", r.ready());
    }

    @Test
    public void parseMissingClaudeIsNotReady() {
        TerminowMcpPreflight.Result r = TerminowMcpPreflight.parse("OS=Linux\nSHELL=yes\nCLAUDE=no\nFOLDER=yes\n");
        assertFalse(r.claudeInstalled);
        assertFalse("a missing Claude is not ready", r.ready());
    }

    @Test
    public void untrustedHostKeyAndErrorAreNotReady() {
        TerminowMcpPreflight.Result key = TerminowMcpPreflight.untrustedHostKey("host.example", "ssh-ed25519 AA:BB");
        assertFalse(key.hostKeyTrusted);
        assertFalse(key.ready());
        assertTrue(key.hostKeyInfo.contains("host.example"));
        assertTrue(key.hostKeyInfo.contains("ssh-ed25519 AA:BB"));

        TerminowMcpPreflight.Result err = TerminowMcpPreflight.error("timed out");
        assertEquals("timed out", err.error);
        assertFalse(err.ready());
    }
}
