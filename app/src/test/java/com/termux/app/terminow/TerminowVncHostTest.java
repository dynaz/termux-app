package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.terminow.vnc.TerminowVncBridge;
import com.termux.app.terminow.vnc.TerminowVncSession;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * A VNC host survives the JSON round-trip that backup, export and app restart use, keeps its
 * protocol (it is never silently turned into SSH), and maps to the right session target.
 */
public class TerminowVncHostTest {

    @Before public void setUp() { TerminowStore.sTestsWithoutKeystore = true; }
    @After public void tearDown() { TerminowStore.sTestsWithoutKeystore = false; }

    private static TerminowStore.Host vncHost() {
        TerminowStore.Host host = new TerminowStore.Host();
        host.label = "Desktop PC";
        host.address = "192.168.1.50";
        host.port = 5901;
        host.protocol = TerminowStore.Host.PROTOCOL_VNC;
        host.vncViewOnly = true;
        host.password = "sekret";
        return host;
    }

    @Test
    public void protocolHelpersClassifyVnc() {
        TerminowStore.Host host = vncHost();
        assertTrue(host.isVnc());
        assertFalse(host.isRdp());
        assertFalse(host.isWinrm());
        assertFalse("a VNC host is not a terminal", host.isTerminal());
    }

    @Test
    public void jsonRoundTripKeepsProtocolAndViewOnly() throws Exception {
        JSONObject json = TerminowStore.hostToJson(vncHost());
        TerminowStore.Host restored = TerminowStore.hostFromJson(json);
        assertEquals(TerminowStore.Host.PROTOCOL_VNC, restored.protocol);
        assertTrue(restored.isVnc());
        assertTrue(restored.vncViewOnly);
        assertEquals("192.168.1.50", restored.address);
        assertEquals(5901, restored.port);
    }

    @Test
    public void vncHostIsRunnableAndNotConvertedOnReload() throws Exception {
        TerminowStore.Host restored = TerminowStore.hostFromJson(TerminowStore.hostToJson(vncHost()));
        assertTrue("a saved VNC host must load, not be dropped", TerminowStore.isRunnable(restored));
        assertEquals(TerminowStore.Host.PROTOCOL_VNC, restored.protocol);   // still VNC, not "ssh"
    }

    @Test
    public void emptyUsernameVncHostStillRunnable() {
        TerminowStore.Host host = vncHost();
        host.username = "";
        assertTrue(TerminowStore.isRunnable(host));
    }

    @Test
    public void bridgeBuildsTargetWithDefaultPortAndViewOnly() {
        TerminowVncSession.Target target = TerminowVncBridge.vncTarget(vncHost());
        assertEquals("Desktop PC", target.title);
        assertEquals("192.168.1.50", target.address);
        assertEquals(5901, target.port);
        assertTrue(target.viewOnly);
        assertEquals("sekret", new String(target.password));
    }

    @Test
    public void bridgeFallsBackToPort5900WhenUnset() {
        TerminowStore.Host host = vncHost();
        host.port = 0;
        assertEquals(5900, TerminowVncBridge.vncTarget(host).port);
    }
}
