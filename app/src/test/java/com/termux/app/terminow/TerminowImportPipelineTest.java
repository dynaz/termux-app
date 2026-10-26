package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * The import pipeline's pure decisions and the model→host mapping: categories and duplicate policy,
 * identity matching against saved hosts, the FileZilla/CSV wrapping of credentials, and that an
 * import never overwrites an existing credential or imports a password unless the person opted in.
 */
public class TerminowImportPipelineTest {

    private static TerminowImportConnection sftp(String host, int port, String user) {
        TerminowImportConnection c = new TerminowImportConnection("FileZilla");
        c.name = host;
        c.host = host;
        c.port = port;
        c.username = user;
        c.protocol = TerminowImportConnection.Protocol.SFTP;
        return c;
    }

    @Test
    public void categorizesByProtocolValidityDuplicateAndCredentials() {
        assertEquals(TerminowImportPlan.Category.VALID,
            TerminowImportPlan.categorize(sftp("h", 22, "u"), false));

        TerminowImportConnection ftp = sftp("h", 21, "u");
        ftp.protocol = TerminowImportConnection.Protocol.FTP;
        assertEquals(TerminowImportPlan.Category.UNSUPPORTED, TerminowImportPlan.categorize(ftp, false));

        TerminowImportConnection noHost = sftp("", 22, "u");
        assertEquals(TerminowImportPlan.Category.INVALID, TerminowImportPlan.categorize(noHost, false));

        assertEquals(TerminowImportPlan.Category.DUPLICATE, TerminowImportPlan.categorize(sftp("h", 22, "u"), true));

        TerminowImportConnection enc = sftp("h", 22, "u");
        enc.credential = TerminowImportConnection.Credential.REQUIRED;
        assertEquals(TerminowImportPlan.Category.CREDENTIALS_REQUIRED, TerminowImportPlan.categorize(enc, false));
    }

    @Test
    public void importablePerDuplicatePolicy() {
        assertFalse(TerminowImportPlan.importable(TerminowImportPlan.Category.UNSUPPORTED, TerminowImportPlan.DuplicatePolicy.KEEP_BOTH));
        assertFalse(TerminowImportPlan.importable(TerminowImportPlan.Category.INVALID, TerminowImportPlan.DuplicatePolicy.UPDATE));
        assertFalse(TerminowImportPlan.importable(TerminowImportPlan.Category.DUPLICATE, TerminowImportPlan.DuplicatePolicy.SKIP));
        assertTrue(TerminowImportPlan.importable(TerminowImportPlan.Category.DUPLICATE, TerminowImportPlan.DuplicatePolicy.KEEP_BOTH));
        assertTrue(TerminowImportPlan.importable(TerminowImportPlan.Category.CREDENTIALS_REQUIRED, TerminowImportPlan.DuplicatePolicy.SKIP));
        assertTrue(TerminowImportPlan.importable(TerminowImportPlan.Category.VALID, TerminowImportPlan.DuplicatePolicy.SKIP));
    }

    @Test
    public void duplicateMatchesByNormalizedHostPortUser() {
        TerminowStore.Host existing = new TerminowStore.Host();
        existing.address = "Host.Example.com";
        existing.port = 22;
        existing.username = "Deploy";
        List<TerminowStore.Host> hosts = new ArrayList<>();
        hosts.add(existing);

        assertTrue(TerminowImportPlan.isDuplicate(sftp("host.example.com", 22, "deploy"), hosts));
        assertFalse("different port is a different host", TerminowImportPlan.isDuplicate(sftp("host.example.com", 2222, "deploy"), hosts));
        assertFalse(TerminowImportPlan.isDuplicate(sftp("other.example.com", 22, "deploy"), hosts));
    }

    @Test
    public void toHostMapsSftpWithGroupTagsAndOptInPassword() {
        TerminowImportConnection c = sftp("srv", 2222, "root");
        c.folders.add("Work");
        c.folders.add("EU");
        c.credential = TerminowImportConnection.Credential.PASSWORD;
        c.password = "s3cret";

        TerminowImportPersist.KeyResolver none = ref -> null;

        TerminowStore.Host optOut = TerminowImportPersist.toHost(c, false, none, "");
        assertEquals(TerminowStore.Host.PROTOCOL_SSH, optOut.protocol);
        assertEquals(2222, optOut.port);
        assertEquals("Work/EU", optOut.group);
        assertTrue(optOut.tags.contains("Imported"));
        assertTrue(optOut.tags.contains("SFTP"));
        assertEquals("password must not be imported by default", "", optOut.password);

        TerminowStore.Host optIn = TerminowImportPersist.toHost(c, true, none, "");
        assertEquals("s3cret", optIn.password);
    }

    @Test
    public void toHostResolvesKeyReferenceWhenAvailable() {
        TerminowImportConnection c = sftp("srv", 22, "root");
        c.credential = TerminowImportConnection.Credential.KEY_REFERENCE;
        c.keyReference = "C:\\keys\\id_rsa";

        TerminowStore.Host unresolved = TerminowImportPersist.toHost(c, false, ref -> null, "Default");
        assertEquals("", unresolved.keyPath);
        assertEquals("falls back to the destination group", "Default", unresolved.group);

        TerminowStore.Host resolved = TerminowImportPersist.toHost(c, false, ref -> "~/.ssh/id_rsa", "");
        assertEquals("~/.ssh/id_rsa", resolved.keyPath);
    }

    @Test
    public void csvWrappingDropsPasswordUnlessOptedIn() {
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "10.0.0.9";
        host.username = "admin";
        host.port = 22;
        host.password = "plain";

        TerminowImportConnection optOut = TerminowImportParsers.fromHost(host, "CSV", false);
        assertEquals(TerminowImportConnection.Credential.NONE, optOut.credential);
        assertEquals("", optOut.password);

        TerminowImportConnection optIn = TerminowImportParsers.fromHost(host, "CSV", true);
        assertEquals(TerminowImportConnection.Credential.PASSWORD, optIn.credential);
        assertEquals("plain", optIn.password);
    }

    @Test
    public void csvWrappingKeepsTelnetDistinct() {
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "sw";
        host.telnet = true;
        host.telnetPort = 23;
        TerminowImportConnection c = TerminowImportParsers.fromHost(host, "CSV", false);
        assertEquals(TerminowImportConnection.Protocol.TELNET, c.protocol);
        assertEquals(23, c.port);
        assertFalse(c.protocol.supported);
    }

    @Test
    public void updateExistingNeverOverwritesACredential() {
        TerminowStore.Host existing = new TerminowStore.Host();
        existing.address = "h";
        existing.port = 22;
        existing.username = "u";
        existing.password = "original";

        TerminowImportConnection c = sftp("h", 2222, "u");
        c.credential = TerminowImportConnection.Credential.PASSWORD;
        c.password = "fromImport";

        TerminowImportPersist.updateExisting(existing, c, true, ref -> null);
        assertEquals("existing password kept", "original", existing.password);
        assertEquals("non-credential fields still update", 2222, existing.port);
    }
}
