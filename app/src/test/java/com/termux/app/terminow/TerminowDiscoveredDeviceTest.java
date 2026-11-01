package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Deduplication merges only devices with the same identity (scope + IP). The same private IP found on
 * two different networks stays two devices, and merging unions the open ports and keeps the latest
 * last-seen.
 */
public class TerminowDiscoveredDeviceTest {

    private static TerminowDiscoveredDevice dev(String scope, String ip, long lastSeen, int... ports) {
        TerminowDiscoveredDevice d = new TerminowDiscoveredDevice(scope, ip);
        for (int p : ports) d.openPorts.add(p);
        d.lastSeenMs = lastSeen;
        return d;
    }

    @Test public void mergesSameScopeAndIp() {
        List<TerminowDiscoveredDevice> out = TerminowDiscoveredDevice.dedup(Arrays.asList(
            dev("local:wlan0:192.168.1.0/24", "192.168.1.10", 100, 22),
            dev("local:wlan0:192.168.1.0/24", "192.168.1.10", 200, 80)));
        assertEquals(1, out.size());
        assertTrue(out.get(0).openPorts.contains(22));
        assertTrue(out.get(0).openPorts.contains(80));
        assertEquals(200, out.get(0).lastSeenMs);
    }

    @Test public void keepsSameIpOnDifferentNetworksSeparate() {
        List<TerminowDiscoveredDevice> out = TerminowDiscoveredDevice.dedup(Arrays.asList(
            dev("local:wlan0:192.168.1.0/24", "192.168.1.10", 100, 22),
            dev("ssh:host-abc:192.168.1.0/24", "192.168.1.10", 100, 22)));
        assertEquals(2, out.size());
    }

    @Test public void preservesFirstSeenOrder() {
        List<TerminowDiscoveredDevice> out = TerminowDiscoveredDevice.dedup(Arrays.asList(
            dev("s", "10.0.0.3", 1), dev("s", "10.0.0.1", 1), dev("s", "10.0.0.3", 2)));
        assertEquals(2, out.size());
        assertEquals("10.0.0.3", out.get(0).ip);
        assertEquals("10.0.0.1", out.get(1).ip);
    }

    @Test public void doesNotFabricateUnknownFields() {
        TerminowDiscoveredDevice d = new TerminowDiscoveredDevice("s", "10.0.0.1");
        assertEquals("", d.hostname);
        assertEquals("", d.mac);
        assertEquals("", d.vendor);
        assertTrue(d.openPorts.isEmpty());
        assertEquals(TerminowDiscoveredDevice.Kind.UNKNOWN, d.kind());
    }

    @Test public void kindIsInferredFromPortsAndGatewayFlag() {
        assertEquals(TerminowDiscoveredDevice.Kind.SERVER, dev("s", "10.0.0.1", 1, 22).kind());
        assertEquals(TerminowDiscoveredDevice.Kind.PRINTER, dev("s", "10.0.0.1", 1, 9100).kind());
        assertEquals(TerminowDiscoveredDevice.Kind.WEB, dev("s", "10.0.0.1", 1, 80).kind());
        TerminowDiscoveredDevice nas = dev("s", "10.0.0.1", 1, 445, 5000);
        assertEquals(TerminowDiscoveredDevice.Kind.NAS, nas.kind());
        TerminowDiscoveredDevice gw = dev("s", "10.0.0.1", 1, 80);
        gw.isGateway = true;
        assertEquals("gateway flag wins over port heuristics", TerminowDiscoveredDevice.Kind.GATEWAY, gw.kind());
    }

    @Test public void mergePreservesReachabilityAndGatewayFlags() {
        TerminowDiscoveredDevice a = dev("s", "10.0.0.1", 1, 22);
        TerminowDiscoveredDevice b = dev("s", "10.0.0.1", 2);
        b.respondsToPing = true;
        b.isGateway = true;
        a.mergeFrom(b);
        assertTrue(a.respondsToPing);
        assertTrue(a.isGateway);
    }
}
