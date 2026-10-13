package com.termux.app.terminow;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The options of the host panel: what they make of the ssh or telnet command, and how they are kept. */
public class TerminowHostOptionsTest {

    @Before
    public void setUp() {
        TerminowStore.sTestsWithoutKeystore = true;
    }

    @After
    public void tearDown() {
        TerminowStore.sTestsWithoutKeystore = false;
    }

    private static TerminowStore.Host host(String user, String address, int port) {
        TerminowStore.Host host = new TerminowStore.Host();
        host.username = user;
        host.address = address;
        host.port = port;
        return host;
    }

    @Test
    public void plainHostIsUnchanged() {
        TerminowStore.Host host = host("root", "example.com", 22);
        assertEquals("ssh root@example.com", host.getCommand(0, 3));
        assertEquals("ssh -p 2222 root@example.com", host("root", "example.com", 2222).getCommand(0, 3));
    }

    @Test
    public void agentForwardingAndEnvironmentAreQuotedForTheShell() {
        TerminowStore.Host host = host("root", "example.com", 22);
        host.agentForwarding = true;
        host.env.put("LANG", "en_US.UTF-8");
        host.env.put("GREETING", "it's a \"test\"");
        assertEquals("ssh -A -o 'SetEnv=LANG=en_US.UTF-8' -o 'SetEnv=GREETING=\"it'\\''s a \\\"test\\\"\"' root@example.com",
            host.getCommand(0, 3));
    }

    @Test
    public void jumpHostUsesItsOwnKeyAndPort() {
        TerminowStore.Host jump = host("admin", "bastion.example.com", 2200);
        jump.keyPath = "~/.ssh/bastion";
        TerminowStore.Host host = host("root", "10.0.0.5", 22);
        host.jumpHost = jump.getKey();
        assertEquals("ssh -o 'ProxyCommand=ssh -i ~/.ssh/bastion -p 2200 -W %h:%p admin@bastion.example.com' root@10.0.0.5",
            host.getCommand(jump, "", 0, 3));
        // sftp and port forwards go the same way
        assertEquals("sftp -o 'ProxyCommand=ssh -i ~/.ssh/bastion -p 2200 -W %h:%p admin@bastion.example.com' root@10.0.0.5",
            host.getSftpCommand(jump, "", 0, 3));
    }

    @Test
    public void proxyUsesTheConnector() {
        TerminowStore.Host host = host("root", "example.com", 22);
        host.proxyType = TerminowStore.Host.PROXY_SOCKS5;
        host.proxyHost = "127.0.0.1";
        host.proxyPort = 1080;
        assertEquals("ssh -o 'ProxyCommand=bash /p/libexec/terminow/proxy-connect socks5 127.0.0.1 1080 %h %p' root@example.com",
            host.getCommand(null, "/p/libexec/terminow/proxy-connect", 0, 3));
    }

    @Test
    public void telnetAndMosh() {
        TerminowStore.Host host = host("pi", "router.lan", 22);
        host.telnet = true;
        host.telnetPort = 2323;
        assertEquals("telnet -l pi router.lan 2323", host.getCommand(0, 3));
        TerminowStore.Host mosh = host("root", "example.com", 22);
        mosh.mosh = true;
        mosh.agentForwarding = true;
        assertEquals("mosh --ssh='ssh -A' root@example.com", mosh.getCommand(0, 3));
    }

    @Test
    public void optionsThatCanNotBeCombinedAreRefused() {
        TerminowStore.Host jump = host("admin", "bastion", 22);
        List<TerminowStore.Host> hosts = new ArrayList<>(Collections.singletonList(jump));

        TerminowStore.Host host = host("root", "example.com", 22);
        assertNull(host.validateOptions(hosts));
        host.jumpHost = jump.getKey();
        assertNull(host.validateOptions(hosts));
        host.proxyType = TerminowStore.Host.PROXY_HTTP;
        host.proxyHost = "proxy";
        host.proxyPort = 3128;
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_jump_and_proxy), host.validateOptions(hosts));
        host.jumpHost = "";
        host.mosh = true;
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_mosh_route), host.validateOptions(hosts));

        TerminowStore.Host self = host("root", "example.com", 22);
        self.jumpHost = self.getKey();
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_jump_self), self.validateOptions(hosts));
        self.jumpHost = "nobody@gone:22";
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_jump_missing), self.validateOptions(hosts));

        TerminowStore.Host telnet = host("", "router", 22);
        telnet.telnet = true;
        telnet.agentForwarding = true;
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_telnet_options), telnet.validateOptions(hosts));

        TerminowStore.Host badEnv = host("root", "example.com", 22);
        badEnv.env.put("BAD NAME", "x");
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_invalid_env), badEnv.validateOptions(hosts));
        badEnv.env.clear();
        badEnv.env.put("OK", "two\nlines");
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_invalid_env), badEnv.validateOptions(hosts));

        TerminowStore.Host badTag = host("root", "example.com", 22);
        badTag.tags.add("a,b");
        assertEquals(Integer.valueOf(com.termux.R.string.terminow_host_invalid_tag), badTag.validateOptions(hosts));
    }

    @Test
    public void everyOptionIsStoredAndSyncedAndOldHostsStillLoad() throws Exception {
        TerminowStore store = new TerminowStore(new MemoryPreferences());
        TerminowStore.Host host = host("root", "example.com", 2222);
        host.label = "Web";
        host.password = "secret";
        host.tags.addAll(Arrays.asList("prod", "web"));
        host.backspaceSendsCtrlH = true;
        host.agentForwarding = true;
        host.jumpHost = "admin@bastion:22";
        host.env.put("LANG", "C.UTF-8");
        host.colorScheme = "Dracula";
        TerminowStore.Host telnet = host("", "router", 22);
        telnet.telnet = true;
        telnet.telnetPort = 2323;
        telnet.proxyType = "";
        store.setHosts(Arrays.asList(host, telnet));

        List<TerminowStore.Host> loaded = store.getHosts();
        assertEquals(2, loaded.size());
        TerminowStore.Host back = loaded.get(0);
        assertEquals(Arrays.asList("prod", "web"), back.tags);
        assertTrue(back.backspaceSendsCtrlH);
        assertTrue(back.agentForwarding);
        assertEquals("admin@bastion:22", back.jumpHost);
        assertEquals("C.UTF-8", back.env.get("LANG"));
        assertEquals("Dracula", back.colorScheme);
        assertEquals("secret", back.password);
        assertTrue(loaded.get(1).telnet);
        assertEquals(2323, loaded.get(1).telnetPort);

        // A vault carries the same fields to another phone
        TerminowStore other = new TerminowStore(new MemoryPreferences());
        other.importVault("", store.exportVault(""));
        TerminowStore.Host synced = other.getHosts().get(0);
        assertEquals(back.tags, synced.tags);
        assertEquals("admin@bastion:22", synced.jumpHost);
        assertEquals("C.UTF-8", synced.env.get("LANG"));

        // A host of an older version has none of the new fields
        TerminowStore.Host old = TerminowStore.hostFromJson(new JSONObject("{\"address\":\"old.example.com\",\"port\":22,\"username\":\"me\"}"));
        assertTrue(old.tags.isEmpty());
        assertFalse(old.telnet);
        assertEquals(23, old.telnetPort);
        assertEquals("ssh me@old.example.com", old.getCommand(0, 3));
    }

    @Test
    public void storedHostsWithUnsafeOptionsAreNeverRun() throws Exception {
        TerminowStore.Host unsafe = TerminowStore.hostFromJson(new JSONObject(
            "{\"address\":\"example.com\",\"port\":22,\"username\":\"root\",\"env\":{\"X; rm -rf ~\":\"1\"}}"));
        assertFalse(TerminowStore.isRunnable(unsafe));
        TerminowStore.Host badProxy = TerminowStore.hostFromJson(new JSONObject(
            "{\"address\":\"example.com\",\"port\":22,\"username\":\"root\",\"proxyType\":\"socks5\",\"proxyHost\":\"a b\",\"proxyPort\":1080}"));
        assertFalse(TerminowStore.isRunnable(badProxy));
    }

    @Test
    public void hostsCanBeSavedInAnotherVault() {
        TerminowStore store = new TerminowStore(new MemoryPreferences());
        store.setHostsIn("team1", Collections.singletonList(host("root", "team.example.com", 22)));
        assertTrue(store.getHosts().isEmpty());
        assertEquals("team.example.com", store.getHostsIn("team1").get(0).address);
    }

}
