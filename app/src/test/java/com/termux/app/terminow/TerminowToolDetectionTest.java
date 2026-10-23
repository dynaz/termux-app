package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TerminowToolDetectionTest {

    @Test
    public void readsOsArchUidAndAptFromTheProbe() {
        TerminowToolInstaller.Detection detection = new TerminowToolInstaller.Detection();
        TerminowToolInstaller.parseDetection(detection,
            "TERMINOW_UID=0\n" +
            "TERMINOW_ARCH=aarch64\n" +
            "TERMINOW_OS=Ubuntu 24.04.1 LTS\n" +
            "TERMINOW_APT=yes\n" +
            "TERMINOW_HAS_claude=2.1.309 (Claude Code)\n");
        assertTrue(detection.ran);
        assertTrue(detection.isRoot());
        assertEquals("aarch64", detection.arch);
        assertEquals("Ubuntu 24.04.1 LTS", detection.os);
        assertTrue(detection.hasApt);
        assertEquals("2.1.309 (Claude Code)", detection.versions.get(TerminowToolInstaller.Tool.CLAUDE));
    }

    @Test
    public void osIsEmptyWhenTheProbeDidNotReportIt() {
        TerminowToolInstaller.Detection detection = new TerminowToolInstaller.Detection();
        TerminowToolInstaller.parseDetection(detection, "TERMINOW_UID=1000\nTERMINOW_ARCH=x86_64\n");
        assertEquals("", detection.os);
        assertEquals("1000", detection.uid);
        assertTrue(!detection.isRoot());
    }
}
