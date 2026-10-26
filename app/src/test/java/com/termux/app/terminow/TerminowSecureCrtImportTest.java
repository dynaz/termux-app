package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;

/**
 * The SecureCRT session adapter: typed S:/D: lines, SSH2 → SSH, the [SSH2] Port DWORD, a folder
 * path from the caller, an Identity file kept as a key reference, and an encrypted saved password
 * surfaced as "credentials required" (never decoded).
 */
public class TerminowSecureCrtImportTest {

    @Test
    public void parsesSshSessionWithKey() throws Exception {
        String ini =
            "S:\"Hostname\"=192.168.1.10\r\n" +
            "S:\"Username\"=admin\r\n" +
            "S:\"Protocol Name\"=SSH2\r\n" +
            "D:\"[SSH2] Port\"=00000016\r\n" +
            "S:\"Identity Filename V2\"=C:\\Users\\me\\key\r\n";
        TerminowImportConnection c = TerminowSecureCrtImport.parse(ini, "Work/Core", "edge-router").get(0);
        assertEquals("edge-router", c.name);
        assertEquals("192.168.1.10", c.host);
        assertEquals("admin", c.username);
        assertEquals(22, c.port);
        assertEquals(TerminowImportConnection.Protocol.SSH, c.protocol);
        assertEquals("Work/Core", c.groupPath());
        assertEquals(TerminowImportConnection.Credential.KEY_REFERENCE, c.credential);
        assertEquals("C:\\Users\\me\\key", c.keyReference);
    }

    @Test
    public void savedPasswordBecomesCredentialsRequired() throws Exception {
        String ini =
            "S:\"Hostname\"=h\r\n" +
            "S:\"Protocol Name\"=SSH2\r\n" +
            "S:\"Password V2\"=02:abcdef0123\r\n";
        TerminowImportConnection c = TerminowSecureCrtImport.parse(ini, "", "h").get(0);
        assertEquals(TerminowImportConnection.Credential.REQUIRED, c.credential);
        assertEquals("", c.password);
    }

    @Test
    public void telnetIsReportedNotConverted() throws Exception {
        String ini = "S:\"Hostname\"=sw\r\nS:\"Protocol Name\"=Telnet\r\nD:\"[Telnet] Port\"=00000017\r\n";
        TerminowImportConnection c = TerminowSecureCrtImport.parse(ini, "", "sw").get(0);
        assertEquals(TerminowImportConnection.Protocol.TELNET, c.protocol);
        assertTrue(!c.protocol.supported);
        assertEquals(23, c.port);
    }

    @Test
    public void nonSecureCrtFileIsRejected() {
        try {
            TerminowSecureCrtImport.parse("[Bookmarks]\nSubRep=\n", "", "x");
            fail("Expected a non-SecureCRT file to throw");
        } catch (IOException expected) {
            // Good.
        }
    }
}
