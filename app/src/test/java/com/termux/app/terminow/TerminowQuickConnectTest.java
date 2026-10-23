package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminowQuickConnectTest {

    private static TerminowStore.Host parse(String text) {
        TerminowQuickConnect.Result result = TerminowQuickConnect.parse(text);
        assertNull("Unexpected error for '" + text + "': " + result.error, result.error);
        assertNotNull(result.host);
        return result.host;
    }

    private static void assertInvalid(String text) {
        TerminowQuickConnect.Result result = TerminowQuickConnect.parse(text);
        assertNull("Expected '" + text + "' to be invalid", result.host);
        assertNotNull(result.error);
    }

    @Test
    public void sshCommandWithPort() {
        TerminowStore.Host host = parse("ssh user@hostname -p 2222");
        assertEquals("user", host.username);
        assertEquals("hostname", host.address);
        assertEquals(2222, host.port);
        assertTrue(TerminowQuickConnect.parse("ssh user@hostname -p 2222").isComplete());
    }

    @Test
    public void otherForms() {
        TerminowStore.Host host = parse("root@10.0.0.5:2299");
        assertEquals("root", host.username);
        assertEquals("10.0.0.5", host.address);
        assertEquals(2299, host.port);

        host = parse("sftp -P 2200 -l admin example.com");
        assertEquals("admin", host.username);
        assertEquals(2200, host.port);

        host = parse("ssh -p2222 -i ~/.ssh/id_ed25519 me@box");
        assertEquals(2222, host.port);
        assertEquals("~/.ssh/id_ed25519", host.keyPath);

        host = parse("ssh://deploy@server.example:2022/");
        assertEquals("deploy", host.username);
        assertEquals("server.example", host.address);
        assertEquals(2022, host.port);
    }

    @Test
    public void hostOnlyNeedsTheForm() {
        TerminowQuickConnect.Result result = TerminowQuickConnect.parse("192.168.1.10");
        assertNotNull(result.host);
        assertEquals(22, result.host.port);
        assertFalse(result.isComplete());
    }

    @Test
    public void ipv6() {
        assertEquals("fe80::1", parse("fe80::1").address);
        TerminowStore.Host host = parse("root@[fe80::1]:2222");
        assertEquals("fe80::1", host.address);
        assertEquals(2222, host.port);
    }

    @Test
    public void rejectsUnsupportedInput() {
        assertInvalid("");
        assertInvalid("   ");
        assertInvalid("ssh");
        assertInvalid("ssh user@host -p 99999");
        assertInvalid("ssh user@host -p abc");
        assertInvalid("ssh user@host -o ProxyCommand=evil");
        assertInvalid("ssh user@host ls -la");
        assertInvalid("ssh user@host; rm -rf ~");
        assertInvalid("ssh user@host && reboot");
        assertInvalid("ssh 'user@host'");
        assertInvalid("ssh $(whoami)@host");
        assertInvalid("http://host");
        assertInvalid("@host");
        assertInvalid("bad user@host");
        assertInvalid("ssh -p");
    }

}
