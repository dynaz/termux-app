package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.termux.app.terminow.TerminowStore.Host;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Save-as-Host: a device prefills a host without guessing credentials, picks the protocol from the open
 * ports, and a same-address device is offered as a possible duplicate instead of a new entry.
 */
public class TerminowDiscoveryHostsTest {

    private static TerminowDiscoveredDevice device(String ip, int... ports) {
        TerminowDiscoveredDevice d = new TerminowDiscoveredDevice("local:wlan0:192.168.1.0/24", ip);
        for (int p : ports) d.openPorts.add(p);
        return d;
    }

    @Test public void prefillsSshWhen22Open() {
        Host h = TerminowDiscoveryHosts.prefill(device("192.168.1.10", 22, 80));
        assertEquals("192.168.1.10", h.address);
        assertEquals(Host.PROTOCOL_SSH, h.protocol);
        assertEquals(22, h.port);
        assertTrue(h.tags.contains("Discovered"));
    }

    @Test public void prefillsRdpWhenOnly3389Open() {
        Host h = TerminowDiscoveryHosts.prefill(device("192.168.1.11", 3389));
        assertEquals(Host.PROTOCOL_RDP, h.protocol);
        assertEquals(3389, h.port);
    }

    @Test public void neverGuessesCredentials() {
        Host h = TerminowDiscoveryHosts.prefill(device("192.168.1.12", 22));
        assertEquals("", h.username);
        assertEquals("", h.password);
        assertEquals("", h.keyPath);
    }

    @Test public void usesHostnameAsLabelWhenKnown() {
        TerminowDiscoveredDevice d = device("192.168.1.13", 22);
        d.hostname = "nas.local";
        assertEquals("nas.local", TerminowDiscoveryHosts.prefill(d).label);
    }

    @Test public void findsPossibleDuplicateByAddress() {
        Host existing = new Host();
        existing.address = "192.168.1.10";
        existing.label = "My NAS";
        List<Host> hosts = new ArrayList<>(Arrays.asList(existing));
        assertSame(existing, TerminowDiscoveryHosts.findPossibleDuplicate(hosts, "192.168.1.10"));
        assertNull(TerminowDiscoveryHosts.findPossibleDuplicate(hosts, "192.168.1.99"));
    }
}
