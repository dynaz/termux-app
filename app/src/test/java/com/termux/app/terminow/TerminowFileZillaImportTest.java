package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.List;

/**
 * The FileZilla Site Manager adapter: nested folders become groups, SFTP stays distinct from and
 * supported unlike FTP/FTPS/FTPES, credentials are read from Logontype/Keyfile/Pass with base64
 * decoded only on opt-in and {@code crypt} left as "credentials required", Thai names survive, and
 * malformed or unsafe (XXE / entity-expansion) XML is rejected instead of parsed. c2VjcmV0 is
 * base64 for "secret".
 */
public class TerminowFileZillaImportTest {

    private static final String EXPORT =
        "<?xml version='1.0' encoding='UTF-8'?>\n" +
        "<FileZilla3 version='3.66.5' platform='windows'>\n" +
        "  <Servers>\n" +
        "    <Server>\n" +
        "      <Host>sftp.example.com</Host><Port>2222</Port><Protocol>1</Protocol>\n" +
        "      <Type>0</Type><User>deploy</User><Pass encoding='base64'>c2VjcmV0</Pass>\n" +
        "      <Logontype>1</Logontype><Name>Prod SFTP</Name>\n" +
        "    </Server>\n" +
        "    <Folder expanded='1'>Work\n" +
        "      <Server>\n" +
        "        <Host>ftp.example.com</Host><Port>21</Port><Protocol>0</Protocol>\n" +
        "        <Logontype>0</Logontype><Name>Legacy FTP</Name>\n" +
        "      </Server>\n" +
        "      <Folder expanded='0'>EU\n" +
        "        <Server>\n" +
        "          <Host>10.0.0.5</Host><Protocol>1</Protocol><Logontype>5</Logontype>\n" +
        "          <Keyfile>C:\\keys\\id_rsa</Keyfile><User>root</User><Name>เซิร์ฟเวอร์ไทย</Name>\n" +
        "        </Server>\n" +
        "      </Folder>\n" +
        "    </Folder>\n" +
        "  </Servers>\n" +
        "</FileZilla3>\n";

    @Test
    public void parsesNestedFoldersSftpAndFtp() throws Exception {
        List<TerminowImportConnection> entries = TerminowFileZillaImport.parse(EXPORT, false);
        assertEquals(3, entries.size());

        TerminowImportConnection sftp = entries.get(0);
        assertEquals("Prod SFTP", sftp.name);
        assertEquals("sftp.example.com", sftp.host);
        assertEquals(2222, sftp.port);
        assertEquals("deploy", sftp.username);
        assertEquals(TerminowImportConnection.Protocol.SFTP, sftp.protocol);
        assertTrue(sftp.protocol.supported);
        assertTrue(sftp.folders.isEmpty());

        TerminowImportConnection ftp = entries.get(1);
        assertEquals(TerminowImportConnection.Protocol.FTP, ftp.protocol);
        assertFalse("FTP must stay unsupported, never converted to SSH", ftp.protocol.supported);
        assertEquals("[Work]", ftp.folders.toString());

        TerminowImportConnection nested = entries.get(2);
        assertEquals("Work/EU", nested.groupPath());
        assertEquals("เซิร์ฟเวอร์ไทย", nested.name);
        assertEquals(TerminowImportConnection.Protocol.SFTP, nested.protocol);
    }

    @Test
    public void passwordsNotImportedByDefault() throws Exception {
        TerminowImportConnection sftp = TerminowFileZillaImport.parse(EXPORT, false).get(0);
        assertEquals(TerminowImportConnection.Credential.NONE, sftp.credential);
        assertEquals("", sftp.password);
    }

    @Test
    public void passwordsDecodedOnlyOnOptIn() throws Exception {
        TerminowImportConnection sftp = TerminowFileZillaImport.parse(EXPORT, true).get(0);
        assertEquals(TerminowImportConnection.Credential.PASSWORD, sftp.credential);
        assertEquals("secret", sftp.password);
        // Even with the password held, the preview/log form must not reveal it.
        assertFalse(sftp.masked().contains("secret"));
        assertFalse(sftp.toString().contains("secret"));
        assertTrue(sftp.masked().contains("****"));
    }

    @Test
    public void keyLoginBecomesKeyReference() throws Exception {
        TerminowImportConnection nested = TerminowFileZillaImport.parse(EXPORT, true).get(2);
        assertEquals(TerminowImportConnection.Credential.KEY_REFERENCE, nested.credential);
        assertEquals("C:\\keys\\id_rsa", nested.keyReference);
    }

    @Test
    public void anonymousLoginNeedsNoCredential() throws Exception {
        TerminowImportConnection ftp = TerminowFileZillaImport.parse(EXPORT, true).get(1);
        assertEquals(TerminowImportConnection.Credential.NONE, ftp.credential);
    }

    @Test
    public void cryptPasswordIsCredentialsRequired() throws Exception {
        String xml = "<FileZilla3><Servers><Server>" +
            "<Host>h</Host><Protocol>1</Protocol><Logontype>1</Logontype>" +
            "<Pass encoding='crypt'>AQAAhashhash</Pass><Name>Enc</Name>" +
            "</Server></Servers></FileZilla3>";
        TerminowImportConnection entry = TerminowFileZillaImport.parse(xml, true).get(0);
        assertEquals(TerminowImportConnection.Credential.REQUIRED, entry.credential);
        assertEquals("", entry.password);
    }

    @Test
    public void ftpsAndFtpesStayDistinctAndUnsupported() throws Exception {
        String xml = "<FileZilla3><Servers>" +
            "<Server><Host>a</Host><Protocol>4</Protocol><Logontype>0</Logontype><Name>Implicit</Name></Server>" +
            "<Server><Host>b</Host><Protocol>5</Protocol><Logontype>0</Logontype><Name>Explicit</Name></Server>" +
            "<Server><Host>c</Host><Protocol>6</Protocol><Logontype>0</Logontype><Name>Plain</Name></Server>" +
            "</Servers></FileZilla3>";
        List<TerminowImportConnection> entries = TerminowFileZillaImport.parse(xml, false);
        assertEquals(TerminowImportConnection.Protocol.FTPS, entries.get(0).protocol);
        assertEquals(TerminowImportConnection.Protocol.FTPES, entries.get(1).protocol);
        assertEquals(TerminowImportConnection.Protocol.FTP_INSECURE, entries.get(2).protocol);
        for (TerminowImportConnection e : entries) assertFalse(e.protocol.supported);
    }

    @Test
    public void malformedXmlIsRejected() {
        try {
            TerminowFileZillaImport.parse("<FileZilla3><Servers><Server><Host>h</Host>", false);
            fail("Expected malformed XML to throw");
        } catch (IOException expected) {
            // Good: parsing failed cleanly instead of producing garbage.
        }
    }

    @Test
    public void notAFileZillaExportIsRejected() {
        try {
            TerminowFileZillaImport.parse("<other><Servers/></other>", false);
            fail("Expected a non-FileZilla root to throw");
        } catch (IOException expected) {
            // Good.
        }
    }

    @Test
    public void externalEntityXxeIsRejected() {
        String xxe = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE foo [ <!ENTITY xxe SYSTEM 'file:///etc/passwd'> ]>\n" +
            "<FileZilla3><Servers><Server><Host>&xxe;</Host></Server></Servers></FileZilla3>";
        try {
            TerminowFileZillaImport.parse(xxe, false);
            fail("Expected the DOCTYPE/XXE document to be rejected");
        } catch (IOException expected) {
            // Good: the external entity was never resolved.
        }
    }

    @Test
    public void entityExpansionBillionLaughsIsRejected() {
        String bomb = "<?xml version='1.0'?>\n" +
            "<!DOCTYPE lolz [ <!ENTITY lol 'lol'> <!ENTITY lol2 '&lol;&lol;&lol;'> ]>\n" +
            "<FileZilla3><Servers><Server><Host>&lol2;</Host></Server></Servers></FileZilla3>";
        try {
            TerminowFileZillaImport.parse(bomb, false);
            fail("Expected the entity-expansion document to be rejected");
        } catch (IOException expected) {
            // Good: DOCTYPE is disallowed, so no expansion happens.
        }
    }
}
