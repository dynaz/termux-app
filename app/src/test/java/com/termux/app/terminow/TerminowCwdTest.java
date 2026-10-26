package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

/**
 * Reading a local process's working directory from {@code /proc/<pid>/cwd} resolves the symlink, and
 * returns null for a bad pid or a link that can not be read (the process ended).
 */
public class TerminowCwdTest {

    @Test public void readsSymlinkTarget() throws Exception {
        File procRoot = Files.createTempDirectory("proc").toFile();
        File pidDir = new File(procRoot, "1234");
        assertEquals(true, pidDir.mkdirs());
        File target = Files.createTempDirectory("cwd").toFile();
        Files.createSymbolicLink(new File(pidDir, "cwd").toPath(), target.toPath());
        assertEquals(target.getCanonicalPath(), TerminowCwd.readProcCwd(procRoot, 1234));
    }

    @Test public void nullForNonPositivePid() {
        File procRoot = new File("/proc");
        assertNull(TerminowCwd.readProcCwd(procRoot, 0));
        assertNull(TerminowCwd.readProcCwd(procRoot, -1));
    }

    @Test public void nullWhenLinkMissing() throws Exception {
        File procRoot = Files.createTempDirectory("proc").toFile();
        assertNull(TerminowCwd.readProcCwd(procRoot, 424242));
    }
}
