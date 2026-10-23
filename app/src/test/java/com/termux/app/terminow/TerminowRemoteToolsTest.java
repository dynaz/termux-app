package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminowRemoteToolsTest {

    @Test
    public void aliasScopesByHostApartFromLocal() {
        TerminowStore.Host host = new TerminowStore.Host();
        host.username = "root";
        host.address = "202.129.205.70";
        host.port = 2299;
        // The key tells one host from another and from a local environment ("ubuntu", "terminow")
        assertEquals("ssh:root@202.129.205.70:2299", TerminowRemoteTools.aliasOf(host));
    }

    @Test
    public void remoteCommandRunsScriptWithToolAsFirstArgument() {
        // $0 is terminow-setup, so the script sees the tool in $1, like the local proot command
        String command = TerminowRemoteTools.remoteCommand("echo hi\n", "fish");
        assertEquals("/bin/sh -c 'echo hi\n' 'terminow-setup' 'fish'", command);
    }

    @Test
    public void remoteCommandQuotesEveryArgumentForTheRemoteShell() {
        String command = TerminowRemoteTools.remoteCommand("s", "plugins", "a b", "x'y");
        assertEquals("/bin/sh -c 's' 'terminow-setup' 'plugins' 'a b' 'x'\\''y'", command);
    }

    @Test
    public void remoteCommandWithNoArgumentsStillPassesTheMarker() {
        String command = TerminowRemoteTools.remoteCommand("script");
        assertTrue(command.endsWith("'script' 'terminow-setup'"));
    }
}
