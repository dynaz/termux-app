package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.app.terminow.TerminowCidr.Cidr;

import java.util.List;

import org.junit.Test;

/**
 * CIDR parsing and range limits: the prefix comes from the CIDR (never assumed /24), the address count
 * is computed from it, oversized ranges are refused with a count, and malformed input returns null.
 */
public class TerminowCidrTest {

    @Test public void addressCountFollowsThePrefixNotAlways256() {
        assertEquals(256, TerminowCidr.parse("192.168.1.0/24").addressCount());
        assertEquals(128, TerminowCidr.parse("10.0.0.0/25").addressCount());
        assertEquals(512, TerminowCidr.parse("10.0.0.0/23").addressCount());
        assertEquals(1, TerminowCidr.parse("10.0.0.5/32").addressCount());
        assertEquals(2, TerminowCidr.parse("10.0.0.0/31").addressCount());
        assertEquals(65536, TerminowCidr.parse("172.16.0.0/16").addressCount());
    }

    @Test public void masksToTheNetworkBase() {
        Cidr c = TerminowCidr.parse("192.168.1.207/24");
        assertEquals("192.168.1.0", c.baseAddress());
        assertEquals(24, c.prefix);
    }

    @Test public void enumeratesWithinLimit() {
        List<String> a = TerminowCidr.parse("192.168.1.0/30").addresses(1024);
        assertEquals(4, a.size());
        assertEquals("192.168.1.0", a.get(0));
        assertEquals("192.168.1.3", a.get(3));
    }

    @Test public void refusesOversizedRange() {
        Cidr big = TerminowCidr.parse("10.0.0.0/16");
        assertFalse(big.withinLimit(1024));
        try {
            big.addresses(1024);
            fail("expected an oversized range to be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("65536"));
        }
    }

    @Test public void rejectsMalformedInput() {
        assertNull(TerminowCidr.parse(null));
        assertNull(TerminowCidr.parse("192.168.1.0"));       // no prefix
        assertNull(TerminowCidr.parse("192.168.1.256/24"));  // bad octet
        assertNull(TerminowCidr.parse("192.168.1.0/33"));    // bad prefix
        assertNull(TerminowCidr.parse("192.168.1/24"));      // three octets
        assertNull(TerminowCidr.parse("x.y.z.w/24"));        // non-numeric
        assertNull(TerminowCidr.parse("192.168.1.0/ab"));    // non-numeric prefix
    }

    @Test public void validatesAddressesAndPorts() {
        assertTrue(TerminowCidr.isValidIpv4("10.0.0.1"));
        assertFalse(TerminowCidr.isValidIpv4("10.0.0.256"));
        assertFalse(TerminowCidr.isValidIpv4("10.0.0"));
        assertTrue(TerminowCidr.isValidPort(22));
        assertTrue(TerminowCidr.isValidPort(65535));
        assertFalse(TerminowCidr.isValidPort(0));
        assertFalse(TerminowCidr.isValidPort(70000));
    }
}
