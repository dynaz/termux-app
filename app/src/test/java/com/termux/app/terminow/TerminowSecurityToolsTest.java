package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The security-tools catalogue: every entry is well formed, unique, Premium, and detectable. */
public class TerminowSecurityToolsTest {

    @Test
    public void everyCatalogEntryIsValidAndUnique() {
        Set<String> ids = new HashSet<>();
        Set<String> bins = new HashSet<>();
        assertFalse(TerminowSecurityTools.catalog().isEmpty());
        for (TerminowSecurityTools.Tool t : TerminowSecurityTools.catalog()) {
            assertTrue("invalid entry " + t.id, TerminowSecurityTools.isValid(t));
            assertTrue("duplicate id " + t.id, ids.add(t.id));
            assertTrue("duplicate bin " + t.binName, bins.add(t.binName));
            assertTrue("no apt package for " + t.id, !t.aptPackages.isEmpty());
        }
    }

    @Test
    public void noEntryInstallsAKaliMetapackage() {
        for (TerminowSecurityTools.Tool t : TerminowSecurityTools.catalog())
            for (String pkg : t.aptPackages)
                assertFalse("metapackage " + pkg, pkg.startsWith("kali-linux") || pkg.startsWith("kali-tools"));
    }

    @Test
    public void everyToolRequiresPremium() {
        for (TerminowSecurityTools.Tool t : TerminowSecurityTools.catalog())
            assertTrue(TerminowSecurityTools.requiresPremium(t));
    }

    @Test
    public void nameValidationRejectsShellMetacharacters() {
        assertTrue(TerminowSecurityTools.isValidName("nmap"));
        assertTrue(TerminowSecurityTools.isValidName("aircrack-ng"));
        assertFalse(TerminowSecurityTools.isValidName("nmap; rm -rf /"));
        assertFalse(TerminowSecurityTools.isValidName("$(whoami)"));
        assertFalse(TerminowSecurityTools.isValidName("a b"));
        assertFalse(TerminowSecurityTools.isValidName(""));
    }

    @Test
    public void byIdAndCategoryWork() {
        assertNotNull(TerminowSecurityTools.byId("nmap"));
        assertTrue(TerminowSecurityTools.byId("nmap").category == TerminowSecurityTools.Category.RECON);
        assertFalse(TerminowSecurityTools.inCategory(TerminowSecurityTools.Category.CAPTURE).isEmpty());
    }

    @Test
    public void wiresharkIsMarkedGuiAndCliToolsAreNot() {
        assertTrue(TerminowSecurityTools.byId("wireshark").gui);
        assertFalse(TerminowSecurityTools.byId("tshark").gui);
        assertFalse(TerminowSecurityTools.byId("nmap").gui);
    }

    @Test
    public void detectScriptCoversEveryBinaryAndParsesBack() {
        String script = TerminowSecurityTools.detectScript();
        for (TerminowSecurityTools.Tool t : TerminowSecurityTools.catalog())
            assertTrue("detect misses " + t.binName, script.contains(t.binName));

        Map<String, String> found = TerminowSecurityTools.parseDetection(
            "noise\nTERMINOW_HAS_nmap=Nmap 7.94\nTERMINOW_HAS_tshark=TShark (Wireshark) 4.2.0\n");
        assertEquals(2, found.size());
        assertEquals("Nmap 7.94", found.get("nmap"));
        assertEquals("TShark (Wireshark) 4.2.0", found.get("tshark"));
    }

    @Test
    public void everyEntryHasHttpsDocsAndAComposer() {
        for (TerminowSecurityTools.Tool t : TerminowSecurityTools.catalog()) {
            assertTrue("no https docs for " + t.id, t.documentationUrl.startsWith("https://"));
            assertNotNull("no composer for " + t.id, t.composer);
        }
    }

    // ---- capabilities: PRoot uid=0 is never a real kernel privilege ----

    private static TerminowSecurityTools.Capabilities caps(String arch, boolean apt, boolean root, boolean proot) {
        return new TerminowSecurityTools.Capabilities(arch, "Ubuntu 24.04", apt, root, proot);
    }

    @Test
    public void prootRootDoesNotGrantKernelPrivilege() {
        assertFalse(caps("aarch64", true, true, true).kernelPrivileged());   // uid=0 inside PRoot
        assertTrue(caps("x86_64", true, true, false).kernelPrivileged());    // real root, not PRoot
        assertFalse(caps("aarch64", true, false, false).kernelPrivileged()); // non-root
    }

    @Test
    public void capabilitiesMapFromDetection() {
        TerminowToolInstaller.Detection d = new TerminowToolInstaller.Detection();
        d.arch = "aarch64"; d.os = "Kali GNU/Linux"; d.hasApt = true; d.uid = "0";
        TerminowSecurityTools.Capabilities c = TerminowSecurityTools.capabilitiesOf(d, true);
        assertTrue(c.hasApt);
        assertTrue(c.isRoot);
        assertTrue(c.arm64());
        assertFalse("uid=0 in PRoot must not read as kernel privilege", c.kernelPrivileged());
    }

    // ---- support verdicts ----

    @Test
    public void unsupportedWhenNoApt() {
        TerminowSecurityTools.Status s = TerminowSecurityTools.support(
            TerminowSecurityTools.byId("nmap"), caps("aarch64", false, false, true));
        assertEquals(TerminowSecurityTools.Support.UNSUPPORTED, s.support);
        assertEquals(TerminowSecurityTools.Reason.NO_APT, s.reason);
    }

    @Test
    public void unsupportedArchIsReported() {
        TerminowSecurityTools.Status s = TerminowSecurityTools.support(
            TerminowSecurityTools.byId("nmap"), caps("mips64", true, false, true));
        assertEquals(TerminowSecurityTools.Support.UNSUPPORTED, s.support);
        assertEquals(TerminowSecurityTools.Reason.UNSUPPORTED_ARCH, s.reason);
    }

    @Test
    public void supportedLimitedAndUnsupportedCases() {
        TerminowSecurityTools.Capabilities arm = caps("aarch64", true, true, true);
        // Plain CLI recon: supported.
        assertEquals(TerminowSecurityTools.Support.SUPPORTED,
            TerminowSecurityTools.support(TerminowSecurityTools.byId("nmap"), arm).support);
        // GUI tool: limited (no display).
        TerminowSecurityTools.Status ws = TerminowSecurityTools.support(TerminowSecurityTools.byId("wireshark"), arm);
        assertEquals(TerminowSecurityTools.Support.LIMITED, ws.support);
        assertEquals(TerminowSecurityTools.Reason.GUI_DISPLAY, ws.reason);
        // Wireless: limited (monitor mode).
        assertEquals(TerminowSecurityTools.Reason.WIRELESS_MONITOR,
            TerminowSecurityTools.support(TerminowSecurityTools.byId("aircrack-ng"), arm).reason);
        // Raw-only scanner: limited (raw sockets).
        assertEquals(TerminowSecurityTools.Reason.RAW_SOCKET,
            TerminowSecurityTools.support(TerminowSecurityTools.byId("masscan"), arm).reason);
    }

    // ---- injection-safe argument builders ----

    @Test
    public void nmapTcpArgsBuildConnectScanAndRejectInjection() {
        assertEquals(java.util.Arrays.asList("-sT", "-Pn", "-p", "22,80,443", "scanme.nmap.org"),
            TerminowSecurityTools.nmapTcpArgs("scanme.nmap.org", "22,80,443"));
        assertEquals(java.util.Arrays.asList("-sT", "-Pn", "192.168.1.1"),
            TerminowSecurityTools.nmapTcpArgs("192.168.1.1", ""));
        assertNull(TerminowSecurityTools.nmapTcpArgs("host; rm -rf /", "22"));
        assertNull(TerminowSecurityTools.nmapTcpArgs("$(id)", ""));
        assertNull(TerminowSecurityTools.nmapTcpArgs("host", "22; reboot"));
    }

    @Test
    public void tsharkReadArgsValidateThePath() {
        assertEquals(java.util.Arrays.asList("-r", "/root/capture.pcapng"),
            TerminowSecurityTools.tsharkReadArgs("/root/capture.pcapng"));
        assertNull(TerminowSecurityTools.tsharkReadArgs("/root/$(cat secrets).pcap"));
        assertNull(TerminowSecurityTools.tsharkReadArgs("relative.pcap"));
        assertNull(TerminowSecurityTools.tsharkReadArgs("/root/a b;c.pcap"));
    }

    @Test
    public void launchCommandQuotesEveryArgument() {
        assertEquals("nmap '-sT' '-Pn' '10.0.0.1'",
            TerminowSecurityTools.launchCommand("nmap", java.util.Arrays.asList("-sT", "-Pn", "10.0.0.1")));
        // A single quote in a value is neutralised, not passed through.
        assertEquals("tshark 'a'\\''b'",
            TerminowSecurityTools.launchCommand("tshark", java.util.Collections.singletonList("a'b")));
    }
}
