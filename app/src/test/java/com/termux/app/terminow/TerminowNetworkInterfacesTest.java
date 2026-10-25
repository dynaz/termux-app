package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.termux.app.terminow.TerminowNetworkInterfaces.Iface;

import org.junit.Test;

/**
 * An interface's network CIDR uses its real prefix, not an assumed /24, and invalid input yields null.
 */
public class TerminowNetworkInterfacesTest {

    @Test public void networkCidrUsesTheRealPrefix() {
        assertEquals("192.168.1.0/24", new Iface("wlan0", "192.168.1.207", 24).cidr());
        assertEquals("10.0.0.0/16", new Iface("tun0", "10.0.5.3", 16).cidr());   // VPN-style /16, not /24
        assertEquals("10.0.0.0/8", new Iface("eth0", "10.1.2.3", 8).cidr());
        assertEquals("172.16.32.0/20", new Iface("eth1", "172.16.47.9", 20).cidr());
    }

    @Test public void invalidAddressYieldsNull() {
        assertNull(new Iface("x", "not-an-ip", 24).cidr());
        assertNull(new Iface("x", "10.0.0.256", 24).cidr());
    }

    @Test public void listDoesNotCrash() {
        // Enumeration is environment-dependent; it must at least return a non-null list without throwing.
        assertNotNull(TerminowNetworkInterfaces.list());
    }
}
