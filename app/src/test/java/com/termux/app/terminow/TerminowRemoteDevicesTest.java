package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.terminow.TerminowRemoteDevices.Template;
import com.termux.app.terminow.TerminowStore.Host;

import org.junit.Test;

/**
 * Device templates map to the right device type, prefill a host with the device type and default SSH
 * port but no credentials or root username, and a network/hypervisor template is never allowed a Linux
 * bootstrap.
 */
public class TerminowRemoteDevicesTest {

    @Test public void remoteDevicesCoverTheExpectedTypes() {
        assertEquals(6, TerminowRemoteDevices.remoteDevices().size());
        assertEquals(TerminowDeviceProfile.Type.GENERIC_LINUX, TerminowRemoteDevices.remoteDevices().get(0).type);
        assertEquals(TerminowDeviceProfile.Type.OTHER_SSH, TerminowRemoteDevices.remoteDevices().get(5).type);
    }

    @Test public void prefillSetsTypeAndPortButNoCredentialsOrRoot() {
        for (Template t : TerminowRemoteDevices.remoteDevices()) {
            Host h = TerminowRemoteDevices.prefill(t);
            assertEquals(Host.PROTOCOL_SSH, h.protocol);
            assertEquals(TerminowDeviceProfile.key(t.type), h.deviceType);
            assertEquals(22, h.port);
            assertEquals("", h.username);  // root is never assumed
            assertEquals("", h.password);
            assertEquals("", h.keyPath);
        }
    }

    @Test public void networkAndHypervisorTemplatesNeverAllowLinuxBootstrap() {
        for (Template t : TerminowRemoteDevices.remoteDevices()) {
            boolean networkOrHypervisor = t.type == TerminowDeviceProfile.Type.MIKROTIK
                || t.type == TerminowDeviceProfile.Type.CISCO || t.type == TerminowDeviceProfile.Type.ESXI;
            if (networkOrHypervisor) assertFalse(TerminowDeviceProfile.allowsLinuxBootstrap(t.type));
        }
        assertTrue(TerminowDeviceProfile.allowsLinuxBootstrap(TerminowDeviceProfile.Type.GENERIC_LINUX));
    }

    @Test public void virtualLabsUseSshDeviceTypes() {
        assertEquals(3, TerminowRemoteDevices.virtualLabs().size());
        for (Template t : TerminowRemoteDevices.virtualLabs()) assertEquals(22, t.defaultPort);
    }

    @Test public void tagsAreSensible() {
        assertEquals("NAS", TerminowRemoteDevices.tagFor(TerminowDeviceProfile.Type.SYNOLOGY));
        assertEquals("Router", TerminowRemoteDevices.tagFor(TerminowDeviceProfile.Type.MIKROTIK));
    }
}
