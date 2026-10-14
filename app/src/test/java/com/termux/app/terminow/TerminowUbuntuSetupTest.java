package com.termux.app.terminow;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Ubuntu setup script, run with bash and fake dpkg, pkg, proot-distro and python3 that
 * record their calls, in a temporary $PREFIX.
 */
public class TerminowUbuntuSetupTest {

    private File mPrefix;
    private File mBin;
    private File mLog;

    @Before
    public void setUp() throws Exception {
        Assume.assumeTrue("bash is needed", new File("/bin/bash").canExecute());
        mPrefix = Files.createTempDirectory("terminow-setup").toFile();
        mBin = new File(mPrefix, "bin");
        assertTrue(mBin.mkdirs());
        mLog = new File(mPrefix, "calls");
        // The shell the script ends in, here it just ends
        fake("bash", "exit 0");
        // A Python that can start programs, unless a test says otherwise
        fake("python3", "echo ok");
    }

    private void fake(String name, String body) throws Exception {
        File file = new File(mBin, name);
        Files.write(file.toPath(), ("#!/bin/sh\necho \"" + name + " $*\" >> " + mLog + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setExecutable(true));
    }

    private void dpkg(boolean dpkgPerl, boolean clang, boolean removeFails) throws Exception {
        fake("dpkg", "case \"$1 $2\" in\n" +
            "  '-s dpkg-perl') " + (dpkgPerl ? "exit 0" : "exit 1") + ";;\n" +
            "  '-s clang') " + (clang ? "exit 0" : "exit 1") + ";;\n" +
            "  '--remove '*) " + (removeFails ? "exit 2" : "exit 0") + ";;\n" +
            "esac");
    }

    /** proot-distro after `pkg install`; install makes the rootfs, login works if {@code starts}. */
    private void packages(boolean installWorks, boolean starts) throws Exception {
        String prootDistro = "#!/bin/sh\necho \"proot-distro $*\" >> " + mLog + "\n" +
            "case \"$1\" in\n" +
            "  install) " + (installWorks ? "mkdir -p \"$PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs\"" : "exit 1") + ";;\n" +
            "  login) " + (starts ? "echo TERMINOW_UBUNTU_OK" : "echo 'proot error: something'; exit 1") + ";;\n" +
            "esac\n";
        File source = new File(mPrefix, "proot-distro.fake");
        Files.write(source.toPath(), prootDistro.getBytes(StandardCharsets.UTF_8));
        fake("pkg", "cp " + source + " " + mBin + "/proot-distro && chmod +x " + mBin + "/proot-distro");
    }

    private String run() throws Exception {
        Process process = new ProcessBuilder("/bin/bash", "-c", "PREFIX=" + mPrefix + "; export PREFIX; PATH=" + mBin + ":/usr/bin:/bin; " +
            TerminowLauncher.UBUNTU_SETUP_SCRIPT).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        return output + "\n--calls--\n" + (mLog.isFile() ? new String(Files.readAllBytes(mLog.toPath()), StandardCharsets.UTF_8) : "");
    }

    private boolean succeeded() {
        return new File(mPrefix, "var/lib/terminow/ubuntu-ok").isFile();
    }

    @Test
    public void aCleanInstallIsOnlyDoneOnceUbuntuStarts() throws Exception {
        dpkg(true, false, false);
        packages(true, true);
        String out = run();
        assertTrue(out, out.contains("dpkg --remove dpkg-scanpackages dpkg-perl"));
        assertTrue(out, out.indexOf("dpkg --remove") < out.indexOf("pkg install -y proot-distro"));
        assertTrue(out, out.split("--calls--")[1].contains("proot-distro install ubuntu"));
        assertTrue(out, out.split("--calls--")[1].contains("proot-distro login ubuntu"));
        assertTrue(out, out.contains("Ubuntu is installed and starts"));
        // The tab becomes the Ubuntu shell
        assertTrue(out, out.split("--calls--")[1].contains("proot-distro login ubuntu\n"));
        assertTrue(succeeded());
        assertFalse("the lock is gone", new File(mPrefix, "tmp/terminow-ubuntu-setup.lock").exists());
    }

    @Test
    public void aDownloadedUbuntuThatDoesNotStartIsNotASuccessAndIsKept() throws Exception {
        dpkg(false, false, false);
        packages(true, false);
        String out = run();
        assertTrue(out, out.contains("does not start"));
        assertFalse(out, out.contains("Ubuntu is installed and starts"));
        assertFalse(succeeded());
        assertTrue("the files are kept", new File(mPrefix, "var/lib/proot-distro/containers/ubuntu/rootfs").isDirectory());
        assertFalse(out, out.contains("proot-distro remove ubuntu\n--calls"));
    }

    @Test
    public void aRetryContinuesWithoutInstallingAgain() throws Exception {
        dpkg(false, false, false);
        packages(false, true);
        String first = run();
        assertTrue(first, first.contains("step 2 failed"));
        assertFalse(succeeded());
        // The download works the second time
        packages(true, true);
        Files.copy(new File(mPrefix, "proot-distro.fake").toPath(), new File(mBin, "proot-distro").toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertTrue(new File(mBin, "proot-distro").setExecutable(true));
        String second = run();
        assertTrue(second, second.contains("proot-distro is installed already"));
        assertTrue(second, succeeded());
        // A third run keeps the files and does not install again
        assertTrue(new File(mLog.getPath()).delete());
        String third = run();
        assertTrue(third, third.contains("Ubuntu files are there already"));
        assertFalse(third, third.split("--calls--")[1].contains("proot-distro install"));
    }

    @Test
    public void oneSetupAtATime() throws Exception {
        dpkg(false, false, false);
        packages(true, true);
        File lock = new File(mPrefix, "tmp/terminow-ubuntu-setup.lock");
        assertTrue(lock.mkdirs());
        // A setup that runs: a process that is alive wrote its pid
        File pid = new File(lock, "pid");
        Process other = new ProcessBuilder("/bin/sh", "-c", "echo $$ > " + pid + "; exec sleep 30").start();
        for (int i = 0; i < 50 && pid.length() == 0; i++) Thread.sleep(100);
        String out;
        try {
            out = run();
        } finally {
            other.destroy();
            other.waitFor(5, TimeUnit.SECONDS);
        }
        assertTrue(out, out.contains("already running"));
        assertFalse(out, out.split("--calls--")[1].contains("pkg install"));
        // A lock of a process that is gone is taken over
        Files.write(new File(lock, "pid").toPath(), "999999".getBytes(StandardCharsets.UTF_8));
        assertTrue(run().contains("Ubuntu is installed and starts"));
    }

    @Test
    public void nothingIsInstalledIfTheRepairFails() throws Exception {
        dpkg(true, false, true);
        packages(true, true);
        String out = run();
        assertFalse(out, out.split("--calls--")[1].contains("pkg install"));
        assertFalse(succeeded());
    }

    @Test
    public void aPythonThatCanNotStartProgramsIsReported() throws Exception {
        dpkg(false, false, false);
        packages(true, true);
        fake("python3", "echo \"PermissionError: [Errno 13] Permission denied: '$PREFIX/bin/sh'\"; exit 1");
        String out = run();
        assertTrue(out, out.contains("Python can not start programs"));
        assertTrue(out, out.contains("Permission denied"));
        // Setup goes on, the check is only for the diagnosis
        assertTrue(out, out.split("--calls--")[1].contains("proot-distro install ubuntu"));
    }

}
