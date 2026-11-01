package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
}
