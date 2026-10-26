package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import com.termux.app.terminow.TerminowStore.Host;

import org.json.JSONObject;
import org.junit.Test;

/**
 * The device type and web admin URL of a host survive saving and loading, and a host saved before
 * these fields existed loads as a generic Linux server with no web admin — so existing hosts are not
 * broken by the migration.
 */
public class TerminowHostDeviceTest {

    @Test public void deviceTypeAndWebAdminRoundTrip() throws Exception {
        Host host = new Host();
        host.label = "Router";
        host.address = "10.0.0.1";
        host.deviceType = "mikrotik";
        host.webAdminUrl = "https://10.0.0.1";
        Host back = TerminowStore.hostFromJson(TerminowStore.hostToJson(host));
        assertEquals("mikrotik", back.deviceType);
        assertEquals("https://10.0.0.1", back.webAdminUrl);
        assertEquals(TerminowDeviceProfile.Type.MIKROTIK, TerminowDeviceProfile.typeOf(back.deviceType));
    }

    @Test public void oldHostWithoutDeviceTypeMigratesToGenericLinux() throws Exception {
        // A host as saved before device types existed: no deviceType or webAdminUrl keys.
        JSONObject old = new JSONObject().put("label", "Server").put("address", "1.2.3.4").put("username", "admin");
        Host back = TerminowStore.hostFromJson(old);
        assertEquals("", back.deviceType);
        assertEquals("", back.webAdminUrl);
        assertEquals(TerminowDeviceProfile.Type.GENERIC_LINUX, TerminowDeviceProfile.typeOf(back.deviceType));
    }
}
