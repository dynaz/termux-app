package com.termux.app.terminow;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The mosh server of a host, new host keys, passphrases of keys and why a connection failed. */
public class TerminowMoshLoginTest {

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
    }

    @After
    public void tearDown() {
        TerminowStore.sTestsWithoutKeystore = false;
    }

    private static TerminowStore.Host moshHost(String server) {
        TerminowStore.Host host = new TerminowStore.Host();
        host.address = "202.129.205.81";
        host.port = 2288;
        host.username = "root";
        host.keyPath = "~/.ssh/Drivo81";
        host.mosh = true;
        host.moshServer = server;
        return host;
    }

    @Test
    public void theMoshServerCommandIsTheProgramAndThePorts() {
        assertArrayEquals(new String[]{"mosh-server", "60000:60010"}, TerminowStore.parseMoshServer("mosh-server new -p 60000:60010"));
        assertArrayEquals(new String[]{"mosh-server", "60001"}, TerminowStore.parseMoshServer("mosh-server new --port=60001"));
        assertArrayEquals(new String[]{"/usr/local/bin/mosh-server", ""}, TerminowStore.parseMoshServer("/usr/local/bin/mosh-server"));
        assertArrayEquals(new String[]{"mosh-server", ""}, TerminowStore.parseMoshServer(""));
        // Nothing that mosh can not pass on, and no shell
        assertNull(TerminowStore.parseMoshServer("mosh-server new -p 70000"));
        assertNull(TerminowStore.parseMoshServer("mosh-server new -p 60010:60000"));
        assertNull(TerminowStore.parseMoshServer("mosh-server new; rm -rf ~"));
        assertNull(TerminowStore.parseMoshServer("mosh-server new -c 256"));
    }

    @Test
    public void moshStartsTheServerOnItsPortsAndAcceptsANewHostKey() {
        String command = moshHost("mosh-server new -p 60000:60010").getCommand(60, 10);
        assertEquals("mosh --port=60000:60010 --ssh='ssh -i ~/.ssh/Drivo81 -p 2288 -o StrictHostKeyChecking=accept-new" +
            " -o ServerAliveInterval=60 -o ServerAliveCountMax=10' root@202.129.205.81", command);
        assertTrue(moshHost("/opt/mosh/bin/mosh-server new").getCommand(0, 0).startsWith("mosh --server=/opt/mosh/bin/mosh-server --ssh="));
        assertNotNull(moshHost("mosh-server new -p x").validateOptions(new java.util.ArrayList<>()));
    }

    @Test
    public void sshAndSftpAcceptANewHostKeyButNotAChangedOne() {
        TerminowStore.Host host = moshHost("");
        host.mosh = false;
        assertTrue(host.getCommand(0, 0).contains(" -o StrictHostKeyChecking=accept-new "));
        assertTrue(host.getSftpCommand(0, 0).contains(" -o StrictHostKeyChecking=accept-new "));
    }

    @Test
    public void theMoshServerIsSavedWithTheHost() throws Exception {
        TerminowStore.Host host = moshHost("mosh-server new -p 60000:60010");
        JSONObject json = TerminowStore.hostToJson(host);
        assertEquals("mosh-server new -p 60000:60010", TerminowStore.hostFromJson(json).moshServer);
        // A host saved before has the defaults of mosh
        json.remove("moshServer");
        assertEquals("", TerminowStore.hostFromJson(json).moshServer);
    }

    @Test
    public void thePassphraseIsTypedOnlyForItsKey() {
        assertEquals("Drivo81", TerminowHome.keyFileName("~/.ssh/Drivo81"));
        assertNull(TerminowHome.keyFileName(""));
        assertNull(TerminowHome.keyFileName("/sdcard/keys/Drivo81"));
        java.util.regex.Pattern prompt = TerminowHome.keyPassphrasePrompt("Drivo81");
        assertTrue(prompt.matcher("Enter passphrase for key '/data/data/com.terminow/files/home/.ssh/Drivo81': ").find());
        assertTrue(!prompt.matcher("Enter passphrase for key '/data/data/com.terminow/files/home/.ssh/Drivo81.old': ").find());
        assertTrue(!prompt.matcher("root@202.129.205.81's password: ").find());
    }

    @Test
    public void saysWhyAConnectionFailed() {
        TerminowHome.Failure failure = TerminowHome.findConnectionFailure("$ ssh -p 2288 root@h\nroot@h: Permission denied (publickey).\n");
        assertEquals(TerminowHome.FailureKind.AUTH, failure.kind);
        assertEquals("root@h: Permission denied (publickey).", failure.line);
        assertEquals(TerminowHome.FailureKind.REFUSED,
            TerminowHome.findConnectionFailure("ssh: connect to host h port 2288: Connection refused").kind);
        assertEquals(TerminowHome.FailureKind.HOST_KEY_CHANGED,
            TerminowHome.findConnectionFailure("@    WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!     @\nHost key verification failed.").kind);
        assertEquals(TerminowHome.FailureKind.MOSH_SERVER,
            TerminowHome.findConnectionFailure("bash: line 1: mosh-server: command not found\nConnection to h closed.").kind);
        assertEquals(TerminowHome.FailureKind.MOSH_UDP,
            TerminowHome.findConnectionFailure("mosh: Nothing received from server on UDP port 60001. [To quit: Ctrl-^ .]").kind);
        assertEquals(TerminowHome.FailureKind.KEY,
            TerminowHome.findConnectionFailure("Load key \"/home/.ssh/x\": invalid format").kind);
        // The command of the connection is not a failure, nor the prompts of the login
        assertNull(TerminowHome.findConnectionFailure("$ mosh --port=60000:60010 --ssh='ssh -o StrictHostKeyChecking=accept-new' root@h\n" +
            "Enter passphrase for key '/home/.ssh/Drivo81': "));
    }
}
