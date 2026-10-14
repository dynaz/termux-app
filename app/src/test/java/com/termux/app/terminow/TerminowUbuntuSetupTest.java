package com.termux.app.terminow;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The Ubuntu setup script, run with bash and fake dpkg, pkg and proot-distro that record their calls. */
public class TerminowUbuntuSetupTest {

    private static String run(boolean dpkgPerl, boolean clang, boolean removeFails) throws Exception {
        Assume.assumeTrue("bash is needed", new File("/bin/bash").canExecute());
        File dir = Files.createTempDirectory("terminow-setup").toFile();
        File log = new File(dir, "calls");
        fake(dir, "dpkg", "echo \"dpkg $*\" >> " + log + "\n" +
            "case \"$1 $2\" in\n" +
            "  '-s dpkg-perl') " + (dpkgPerl ? "exit 0" : "exit 1") + ";;\n" +
            "  '-s clang') " + (clang ? "exit 0" : "exit 1") + ";;\n" +
            "  '--remove '*) " + (removeFails ? "exit 2" : "exit 0") + ";;\n" +
            "esac\n");
        fake(dir, "pkg", "echo \"pkg $*\" >> " + log);
        fake(dir, "proot-distro", "echo \"proot-distro $*\" >> " + log);
        // The script ends in a login shell, which here just ends
        fake(dir, "bash", "exit 0");
        Process process = new ProcessBuilder("/bin/bash", "-c", "PATH=" + dir + ":/usr/bin:/bin; " +
            TerminowLauncher.UBUNTU_SETUP_SCRIPT.replace("exec bash -l", "exit 0")).redirectErrorStream(true).start();
        process.getOutputStream().close();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        return log.isFile() ? new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8) : "";
    }

    private static void fake(File dir, String name, String body) throws Exception {
        File file = new File(dir, name);
        Files.write(file.toPath(), ("#!/bin/sh\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setExecutable(true));
    }

    @Test
    public void brokenDpkgPerlIsRemovedBeforeInstalling() throws Exception {
        String calls = run(true, false, false);
        assertTrue(calls, calls.contains("dpkg --remove dpkg-scanpackages dpkg-perl"));
        assertTrue(calls, calls.indexOf("dpkg --remove") < calls.indexOf("pkg install -y proot-distro"));
        assertTrue(calls, calls.contains("proot-distro install ubuntu"));
    }

    @Test
    public void aHealthyBootstrapIsLeftAsItIs() throws Exception {
        assertFalse(run(false, false, false).contains("--remove"));
        // With clang there, dpkg-perl is fine
        assertFalse(run(true, true, false).contains("--remove"));
    }

    @Test
    public void nothingIsInstalledIfTheRepairFails() throws Exception {
        String calls = run(true, false, true);
        assertFalse(calls, calls.contains("pkg install"));
        assertEquals(-1, calls.indexOf("proot-distro install"));
    }

}
