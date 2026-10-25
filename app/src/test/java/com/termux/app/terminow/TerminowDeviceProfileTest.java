package com.termux.app.terminow;

import static com.termux.app.terminow.TerminowDeviceProfile.Cap;
import static com.termux.app.terminow.TerminowDeviceProfile.Type;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The device capability model: a host saved before device types existed stays a Linux server
 * (migration), a template maps to the right capabilities, and a Linux bootstrap is never allowed for
 * RouterOS, Cisco IOS or ESXi.
 */
public class TerminowDeviceProfileTest {

    @Test public void unknownOrEmptyTypeMigratesToGenericLinux() {
        assertEquals(Type.GENERIC_LINUX, TerminowDeviceProfile.typeOf(null));
        assertEquals(Type.GENERIC_LINUX, TerminowDeviceProfile.typeOf(""));
        assertEquals(Type.GENERIC_LINUX, TerminowDeviceProfile.typeOf("   "));
        assertEquals(Type.GENERIC_LINUX, TerminowDeviceProfile.typeOf("something-new-later"));
    }

    @Test public void knownTypesParseCaseInsensitively() {
        assertEquals(Type.SYNOLOGY, TerminowDeviceProfile.typeOf("synology"));
        assertEquals(Type.MIKROTIK, TerminowDeviceProfile.typeOf("MikroTik"));
        assertEquals(Type.CISCO, TerminowDeviceProfile.typeOf("cisco"));
        assertEquals(Type.ESXI, TerminowDeviceProfile.typeOf("ESXi"));
        assertEquals(Type.OTHER_SSH, TerminowDeviceProfile.typeOf("other"));
    }

    @Test public void keyRoundTrips() {
        for (Type t : Type.values()) assertEquals(t, TerminowDeviceProfile.typeOf(TerminowDeviceProfile.key(t)));
    }

    @Test public void linuxBootstrapNeverAllowedForNetworkOrHypervisorDevices() {
        assertFalse(TerminowDeviceProfile.allowsLinuxBootstrap(Type.MIKROTIK));
        assertFalse(TerminowDeviceProfile.allowsLinuxBootstrap(Type.CISCO));
        assertFalse(TerminowDeviceProfile.allowsLinuxBootstrap(Type.ESXI));
        // Synology is Linux underneath but TermiNow does not auto-install into it either.
        assertFalse(TerminowDeviceProfile.allowsLinuxBootstrap(Type.SYNOLOGY));
        assertFalse(TerminowDeviceProfile.allowsLinuxBootstrap(Type.OTHER_SSH));
    }

    @Test public void linuxBootstrapAllowedOnlyForGenericLinux() {
        assertTrue(TerminowDeviceProfile.allowsLinuxBootstrap(Type.GENERIC_LINUX));
    }

    @Test public void networkDevicesUseNativeCliNotAPosixShell() {
        assertTrue(TerminowDeviceProfile.isNativeCli(Type.MIKROTIK));
        assertTrue(TerminowDeviceProfile.isNativeCli(Type.CISCO));
        assertTrue(TerminowDeviceProfile.isNativeCli(Type.ESXI));
        assertFalse(TerminowDeviceProfile.isLinuxShell(Type.MIKROTIK));
        assertFalse(TerminowDeviceProfile.isLinuxShell(Type.CISCO));
        assertFalse(TerminowDeviceProfile.isLinuxShell(Type.ESXI));
        assertTrue(TerminowDeviceProfile.isLinuxShell(Type.GENERIC_LINUX));
        assertTrue(TerminowDeviceProfile.isLinuxShell(Type.SYNOLOGY));
    }

    @Test public void sftpIsUnsupportedForRouterOsAndCiscoUnknownForEsxi() {
        assertEquals(Cap.SUPPORTED, TerminowDeviceProfile.sftp(Type.GENERIC_LINUX));
        assertEquals(Cap.SUPPORTED, TerminowDeviceProfile.sftp(Type.SYNOLOGY));
        assertEquals(Cap.UNSUPPORTED, TerminowDeviceProfile.sftp(Type.MIKROTIK));
        assertEquals(Cap.UNSUPPORTED, TerminowDeviceProfile.sftp(Type.CISCO));
        assertEquals(Cap.UNKNOWN, TerminowDeviceProfile.sftp(Type.ESXI));
        assertEquals(Cap.UNKNOWN, TerminowDeviceProfile.sftp(Type.OTHER_SSH));
    }

    @Test public void webAdminSupportedForDevicesWithWebConsoles() {
        assertEquals(Cap.SUPPORTED, TerminowDeviceProfile.webAdmin(Type.SYNOLOGY));
        assertEquals(Cap.SUPPORTED, TerminowDeviceProfile.webAdmin(Type.MIKROTIK));
        assertEquals(Cap.SUPPORTED, TerminowDeviceProfile.webAdmin(Type.ESXI));
        assertEquals(Cap.UNKNOWN, TerminowDeviceProfile.webAdmin(Type.GENERIC_LINUX));
    }
}
