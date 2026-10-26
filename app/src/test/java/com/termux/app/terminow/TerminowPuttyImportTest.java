package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * The PuTTY {@code .reg} adapter: Default Settings is skipped, session names are %XX-unescaped,
 * DWORD ports are hex, SSH is imported while telnet/rlogin/raw/serial are reported but not converted,
 * and a {@code .ppk} path is kept only as a key reference. The file is parsed as data, never run.
 */
public class TerminowPuttyImportTest {

    private static final String REG =
        "Windows Registry Editor Version 5.00\r\n" +
        "\r\n" +
        "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\Default%20Settings]\r\n" +
        "\"HostName\"=\"\"\r\n" +
        "\"Protocol\"=\"ssh\"\r\n" +
        "\r\n" +
        "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\Prod%20Web]\r\n" +
        "\"HostName\"=\"web.example.com\"\r\n" +
        "\"PortNumber\"=dword:00000016\r\n" +
        "\"Protocol\"=\"ssh\"\r\n" +
        "\"UserName\"=\"deploy\"\r\n" +
        "\"PublicKeyFile\"=\"C:\\\\keys\\\\web.ppk\"\r\n" +
        "\r\n" +
        "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\Old%20Switch]\r\n" +
        "\"HostName\"=\"10.0.0.1\"\r\n" +
        "\"PortNumber\"=dword:00000017\r\n" +
        "\"Protocol\"=\"telnet\"\r\n";

    @Test
    public void importsSessionsAndSkipsDefaults() throws Exception {
        List<TerminowImportConnection> entries = TerminowPuttyImport.parse(REG);
        assertEquals("Default Settings is skipped", 2, entries.size());

        TerminowImportConnection web = entries.get(0);
        assertEquals("Prod Web", web.name);
        assertEquals("web.example.com", web.host);
        assertEquals(22, web.port);
        assertEquals("deploy", web.username);
        assertEquals(TerminowImportConnection.Protocol.SSH, web.protocol);
        assertTrue(web.protocol.supported);
        assertEquals(TerminowImportConnection.Credential.KEY_REFERENCE, web.credential);
        assertEquals("C:\\keys\\web.ppk", web.keyReference);
    }

    @Test
    public void telnetIsReportedNotConverted() throws Exception {
        TerminowImportConnection sw = TerminowPuttyImport.parse(REG).get(1);
        assertEquals("Old Switch", sw.name);
        assertEquals(23, sw.port);
        assertEquals(TerminowImportConnection.Protocol.TELNET, sw.protocol);
        assertFalse(sw.protocol.supported);
        assertFalse("telnet must not be turned into SSH", sw.protocol == TerminowImportConnection.Protocol.SSH);
    }

    @Test
    public void thaiSessionNameIsPreserved() throws Exception {
        String reg = "REGEDIT4\r\n\r\n" +
            "[HKEY_CURRENT_USER\\Software\\SimonTatham\\PuTTY\\Sessions\\เซิร์ฟเวอร์ไทย]\r\n" +
            "\"HostName\"=\"th.example.com\"\r\n\"Protocol\"=\"ssh\"\r\n";
        assertEquals("เซิร์ฟเวอร์ไทย", TerminowPuttyImport.parse(reg).get(0).name);
    }

    @Test
    public void helpersDecodeNamesStringsAndDwords() {
        assertEquals("My Session", TerminowPuttyImport.unescapeSessionName("My%20Session"));
        assertEquals("100%done", TerminowPuttyImport.unescapeSessionName("100%25done"));
        assertEquals("C:\\a\\b", TerminowPuttyImport.unescapeString("\"C:\\\\a\\\\b\""));
        assertEquals("say \"hi\"", TerminowPuttyImport.unescapeString("\"say \\\"hi\\\"\""));
        assertEquals(22, TerminowPuttyImport.parseDword("dword:00000016"));
        assertEquals(0, TerminowPuttyImport.parseDword("dword:00000000"));
        assertEquals(0, TerminowPuttyImport.parseDword("\"notadword\""));
    }

    @Test
    public void nonPuttyFileIsRejected() {
        try {
            TerminowPuttyImport.parse("name,host,port\nfoo,bar,22\n");
            fail("Expected a non-PuTTY file to throw");
        } catch (IOException expected) {
            // Good.
        }
    }
}
