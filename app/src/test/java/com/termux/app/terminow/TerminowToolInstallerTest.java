package com.termux.app.terminow;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The Tool Installer: its install script run for real with stand-ins for id, dpkg-query, apt-get,
 * sudo and the installer of Claude Code, which say what they were asked to do, and the jobs that
 * run it as their own process.
 */
public class TerminowToolInstallerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private File mDir;

    @Before
    public void setUp() {
        TerminowToolInstaller.reset();
    }

    @After
    public void tearDown() {
        TerminowToolInstaller.reset();
    }

    /** An environment of stand-ins: the uid, sudo (none, password, ok), the packages it has, curl (ok, net). */
    private TerminowToolInstaller.Runner environment(String uid, String sudo, String installed, String curl) throws Exception {
        assumeTrue(new File("/bin/bash").exists() && new File("/bin/sh").exists());
        mDir = new File(temp.getRoot(), "env");
        // A test may make a few environments one after the other
        File script = new File(temp.getRoot(), "standins.sh");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("terminow-tool-standins.sh")) {
            assertNotNull(in);
            Files.write(script.toPath(), readAll(in));
        }
        Process setup = new ProcessBuilder("/bin/bash", script.getPath(), mDir.getPath(), uid, sudo, installed, curl)
            .redirectErrorStream(true).start();
        readAll(setup.getInputStream());
        assertTrue(setup.waitFor(30, TimeUnit.SECONDS));
        File dir = mDir;
        // Like proot-distro login: the script, then its arguments, in the environment as its user
        return (alias, user, body, args) -> {
            List<String> command = new ArrayList<>(Arrays.asList(dir + "/bin/sh", "-c", body, "terminow-setup"));
            command.addAll(Arrays.asList(args));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().clear();
            builder.environment().put("PATH", dir + "/bin");
            builder.environment().put("HOME", dir + "/home");
            return builder.start();
        };
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private String calls() throws Exception {
        File file = new File(mDir, "calls");
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    private String bashrc() throws Exception {
        File file = new File(mDir, "home/.bashrc");
        return file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
    }

    /** Install and wait for the job to end. */
    private TerminowToolInstaller.Job installAndWait(TerminowToolInstaller.Runner runner, String user, TerminowToolInstaller.Tool tool)
        throws Exception {
        CountDownLatch ended = new CountDownLatch(1);
        Runnable listener = () -> {
            TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf("ubuntu", user, tool);
            if (job != null && job.getState() != TerminowToolInstaller.State.RUNNING) ended.countDown();
        };
        TerminowToolInstaller.addListener(listener);
        TerminowToolInstaller.Job job = TerminowToolInstaller.install(runner, "ubuntu", user, tool);
        assertNotNull(job);
        assertTrue("the install did not end", ended.await(60, TimeUnit.SECONDS));
        TerminowToolInstaller.removeListener(listener);
        return job;
    }

    @Test
    public void rootInstallsWithAptItselfAndNeverWithSudo() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "", "ok");
        TerminowToolInstaller.Job job = installAndWait(runner, "root", TerminowToolInstaller.Tool.GIT);
        assertEquals(job.getLog(), TerminowToolInstaller.State.SUCCEEDED, job.getState());
        assertEquals("git version 2.43.0", job.getVersion());
        assertEquals(TerminowToolInstaller.Stage.VERIFYING, job.getStage());
        String calls = calls();
        assertTrue(calls, calls.contains("apt-get -o DPkg::Lock::Timeout=300 install -y --no-install-recommends git"));
        assertFalse(calls, calls.contains("sudo"));
        assertFalse(calls, calls.contains("install -y --no-install-recommends sudo"));
    }

    @Test
    public void claudeThatIsThereIsOnlyVerified() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "curl ca-certificates git claude", "ok");
        TerminowToolInstaller.Job job = installAndWait(runner, "root", TerminowToolInstaller.Tool.CLAUDE);
        assertEquals(job.getLog(), TerminowToolInstaller.State.SUCCEEDED, job.getState());
        assertTrue(job.wasAlreadyInstalled());
        assertEquals("2.1.29 (Claude Code)", job.getVersion());
        assertEquals("nothing is downloaded or installed", "", calls());
        // ~/.local/bin on the PATH of bash, once, also after another run
        TerminowToolInstaller.forget("ubuntu", "root", TerminowToolInstaller.Tool.CLAUDE);
        installAndWait(runner, "root", TerminowToolInstaller.Tool.CLAUDE);
        String bashrc = bashrc();
        assertEquals(bashrc, 1, bashrc.split("TermiNow: Claude Code PATH", -1).length - 1);
        assertTrue(bashrc, bashrc.contains("export PATH=\"$HOME/.local/bin:$PATH\""));
    }

    @Test
    public void claudeGetsItsDependenciesThenTheOfficialInstaller() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "", "ok");
        TerminowToolInstaller.Job job = installAndWait(runner, "root", TerminowToolInstaller.Tool.CLAUDE);
        assertEquals(job.getLog(), TerminowToolInstaller.State.SUCCEEDED, job.getState());
        assertFalse(job.wasAlreadyInstalled());
        assertEquals("2.1.30 (Claude Code)", job.getVersion());
        String calls = calls();
        assertTrue(calls, calls.contains("install -y --no-install-recommends curl ca-certificates git"));
        assertTrue(calls, calls.contains("curl -fsSL https://claude.ai/install.sh"));
        assertTrue(calls, calls.indexOf("apt-get") < calls.indexOf("curl -fsSL"));
        assertTrue(bashrc().contains("TermiNow: Claude Code PATH"));
    }

    @Test
    public void aNetworkFailureFailsAtInstallingAndIsNeverInstalled() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "curl ca-certificates git", "net");
        TerminowToolInstaller.Job job = installAndWait(runner, "root", TerminowToolInstaller.Tool.CLAUDE);
        assertEquals(TerminowToolInstaller.State.FAILED, job.getState());
        assertEquals(TerminowToolInstaller.Stage.INSTALLING, job.getStage());
        assertEquals(TerminowToolInstaller.Failure.NETWORK, job.getFailure());
        assertEquals(6, job.getExitCode());
        assertEquals("", job.getVersion());
        assertTrue(job.getLog(), job.getLogTail(3).contains("Could not resolve host: claude.ai"));
        assertFalse("no PATH change for what was not installed", bashrc().contains("TermiNow"));
    }

    @Test
    public void anotherUserNeedsSudoWithoutAPassword() throws Exception {
        TerminowToolInstaller.Job none = installAndWait(environment("1000", "none", "", "ok"), "dev", TerminowToolInstaller.Tool.GIT);
        assertEquals(TerminowToolInstaller.Failure.NO_SUDO, none.getFailure());
        assertFalse(calls().contains("apt-get"));

        TerminowToolInstaller.reset();
        TerminowToolInstaller.Job password = installAndWait(environment("1000", "password", "", "ok"), "dev", TerminowToolInstaller.Tool.TMUX);
        assertEquals(TerminowToolInstaller.Failure.SUDO_PASSWORD, password.getFailure());
        assertFalse(calls().contains("apt-get"));

        TerminowToolInstaller.reset();
        TerminowToolInstaller.Job ok = installAndWait(environment("1000", "ok", "", "ok"), "dev", TerminowToolInstaller.Tool.TMUX);
        assertEquals(ok.getLog(), TerminowToolInstaller.State.SUCCEEDED, ok.getState());
        assertTrue(calls().contains("sudo -n env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=300 install -y --no-install-recommends tmux"));
    }

    @Test
    public void anotherUserWithTheDependenciesNeedsNoSudoForClaude() throws Exception {
        TerminowToolInstaller.Job job = installAndWait(environment("1000", "none", "curl ca-certificates git", "ok"), "dev",
            TerminowToolInstaller.Tool.CLAUDE);
        assertEquals(job.getLog(), TerminowToolInstaller.State.SUCCEEDED, job.getState());
        assertFalse(calls().contains("sudo"));
    }

    @Test
    public void aSecondTapGetsTheSameJobAndAptRunsOnceAtATime() throws Exception {
        // A process that waits, like apt that downloads
        File gate = temp.newFile("gate");
        assertTrue(gate.delete());
        TerminowToolInstaller.Runner slow = (alias, user, body, args) ->
            new ProcessBuilder("/bin/sh", "-c", "while [ ! -e '" + gate + "' ]; do sleep 0.05; done; echo TERMINOW_VERSION=1; exit 0").start();
        TerminowToolInstaller.Job first = TerminowToolInstaller.install(slow, "ubuntu", "root", TerminowToolInstaller.Tool.GIT);
        assertNotNull(first);
        assertSame(first, TerminowToolInstaller.install(slow, "ubuntu", "root", TerminowToolInstaller.Tool.GIT));
        assertNull(TerminowToolInstaller.install(slow, "ubuntu", "root", TerminowToolInstaller.Tool.TMUX));
        assertNull(TerminowToolInstaller.install(slow, "ubuntu", "dev", TerminowToolInstaller.Tool.CURL));
        // Another environment has its own apt
        TerminowToolInstaller.Job other = TerminowToolInstaller.install(slow, "debian", "root", TerminowToolInstaller.Tool.TMUX);
        assertNotNull(other);
        assertSame(first, TerminowToolInstaller.runningIn("ubuntu"));
        assertTrue(gate.createNewFile());
        long until = System.currentTimeMillis() + 20000;
        while ((first.getState() == TerminowToolInstaller.State.RUNNING || other.getState() == TerminowToolInstaller.State.RUNNING)
            && System.currentTimeMillis() < until) Thread.sleep(50);
        assertEquals(TerminowToolInstaller.State.SUCCEEDED, first.getState());
        assertNull(TerminowToolInstaller.runningIn("ubuntu"));
        assertNotNull(TerminowToolInstaller.install(slow, "ubuntu", "root", TerminowToolInstaller.Tool.TMUX));
    }

    @Test
    public void onlyAVerifiedVersionIsASuccess() {
        TerminowToolInstaller.Job quiet = new TerminowToolInstaller.Job("ubuntu", "root", TerminowToolInstaller.Tool.GIT);
        quiet.onLine("TERMINOW_STAGE=installing");
        quiet.onExit(0);
        assertEquals(TerminowToolInstaller.State.FAILED, quiet.getState());
        assertEquals(TerminowToolInstaller.Failure.VERIFY, quiet.getFailure());

        TerminowToolInstaller.Job failed = new TerminowToolInstaller.Job("ubuntu", "root", TerminowToolInstaller.Tool.GIT);
        failed.onLine("TERMINOW_STAGE=dependencies");
        failed.onLine("E: Unable to locate package git");
        failed.onLine("TERMINOW_FAILED=apt-install");
        failed.onExit(21);
        assertEquals(TerminowToolInstaller.Failure.APT_INSTALL, failed.getFailure());
        assertEquals(TerminowToolInstaller.Stage.DEPENDENCIES, failed.getStage());
        assertEquals("E: Unable to locate package git", failed.getLogTail(1));

        TerminowToolInstaller.Job cancelled = new TerminowToolInstaller.Job("ubuntu", "root", TerminowToolInstaller.Tool.GIT);
        cancelled.onLine("TERMINOW_VERSION=git version 2");
        cancelled.cancel();
        cancelled.onExit(0);
        assertEquals(TerminowToolInstaller.Failure.CANCELLED, cancelled.getFailure());
    }

    @Test
    public void detectionSaysWhoWhereAndWhatIsThere() throws Exception {
        TerminowToolInstaller.Detection detection = TerminowToolInstaller.detect(environment("0", "none", "claude", "ok"), "ubuntu", "root");
        assertTrue(detection.ran);
        assertTrue(detection.isRoot());
        assertFalse(detection.hasSudo);
        assertTrue(detection.hasApt);
        assertEquals("2.1.29 (Claude Code)", detection.versions.get(TerminowToolInstaller.Tool.CLAUDE));
        assertFalse(detection.versions.containsKey(TerminowToolInstaller.Tool.GIT));
        assertFalse(detection.arch.isEmpty());
        assertTrue(TerminowToolInstaller.claudeSupports("aarch64"));
        assertFalse(TerminowToolInstaller.claudeSupports("armv7l"));
    }

    @Test
    public void itRunsInTheEnvironmentAsItsUserNeverInTermiNow() {
        List<String> command = TerminowToolInstaller.command(new File("/p/usr"), "ubuntu", "dev", "SCRIPT", "claude");
        assertEquals(Arrays.asList("/p/usr/bin/proot-distro", "login", "ubuntu", "--user", "dev", "--", "/bin/sh", "-c", "SCRIPT",
            "terminow-setup", "claude"), command);
    }
}
