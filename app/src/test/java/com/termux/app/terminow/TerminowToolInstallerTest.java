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
import java.nio.file.Paths;
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
    public void cursorComesFromItsOfficialInstaller() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "", "ok");
        TerminowToolInstaller.Job job = installAndWait(runner, "root", TerminowToolInstaller.Tool.CURSOR);
        assertEquals(job.getLog(), TerminowToolInstaller.State.SUCCEEDED, job.getState());
        assertEquals("2026.10.18-ea085cb", job.getVersion());
        String calls = calls();
        assertTrue(calls, calls.contains("install -y --no-install-recommends curl ca-certificates"));
        assertTrue(calls, calls.contains("curl -fsSL https://cursor.com/install"));
        assertTrue(bashrc().contains("TermiNow: Claude Code PATH"));
    }

    @Test
    public void gstackAndPluginsNeedClaudeCode() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "", "ok");
        TerminowToolInstaller.Job gstack = installAndWait(runner, "root", TerminowToolInstaller.Tool.GSTACK);
        assertEquals(TerminowToolInstaller.Failure.NEEDS_CLAUDE, gstack.getFailure());
        TerminowToolInstaller.Job plugins = installAndWait(runner, "root", TerminowToolInstaller.Tool.PLUGINS);
        assertEquals(TerminowToolInstaller.Failure.NEEDS_CLAUDE, plugins.getFailure());
        assertEquals("nothing is downloaded or installed", "", calls());
    }

    /** Claude Code that writes its plugin commands to the calls, and fails to install the plugin "broken". */
    private void pluginClaude() throws Exception {
        File claude = new File(mDir, "home/.local/bin/claude");
        Files.write(claude.toPath(), ("#!/bin/sh\n" +
            "echo \"claude $*\" >> \"" + mDir + "/calls\"\n" +
            "case \"$*\" in *broken@*) exit 1;; esac\n" +
            "exit 0\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(claude.setExecutable(true));
    }

    @Test
    public void pluginsComeFromTheOfficialMarketplace() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "claude", "ok");
        pluginClaude();
        CountDownLatch ended = new CountDownLatch(1);
        TerminowToolInstaller.addListener(() -> {
            TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf("ubuntu", "root", TerminowToolInstaller.Tool.PLUGINS);
            if (job != null && job.getState() != TerminowToolInstaller.State.RUNNING) ended.countDown();
        });
        TerminowToolInstaller.Job job = TerminowToolInstaller.install(runner, "ubuntu", "root", TerminowToolInstaller.Tool.PLUGINS,
            "code-review", "commit-commands");
        assertNotNull(job);
        assertTrue(ended.await(60, TimeUnit.SECONDS));
        assertEquals(job.getLog(), TerminowToolInstaller.State.SUCCEEDED, job.getState());
        assertEquals("2", job.getVersion());
        String calls = calls();
        assertTrue(calls, calls.contains("claude plugin marketplace add anthropics/claude-plugins-official"));
        assertTrue(calls, calls.contains("claude plugin install -y code-review@claude-plugins-official"));
        assertTrue(calls, calls.contains("claude plugin install -y commit-commands@claude-plugins-official"));
    }

    @Test
    public void aPluginThatIsNotInstalledFailsTheJobAndIsNamed() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "claude", "ok");
        pluginClaude();
        CountDownLatch ended = new CountDownLatch(1);
        TerminowToolInstaller.addListener(() -> {
            TerminowToolInstaller.Job job = TerminowToolInstaller.jobOf("ubuntu", "root", TerminowToolInstaller.Tool.PLUGINS);
            if (job != null && job.getState() != TerminowToolInstaller.State.RUNNING) ended.countDown();
        });
        TerminowToolInstaller.Job job = TerminowToolInstaller.install(runner, "ubuntu", "root", TerminowToolInstaller.Tool.PLUGINS,
            "code-review", "broken");
        assertNotNull(job);
        assertTrue(ended.await(60, TimeUnit.SECONDS));
        assertEquals(TerminowToolInstaller.State.FAILED, job.getState());
        assertEquals(TerminowToolInstaller.Failure.INSTALLER, job.getFailure());
        assertTrue(job.getLog(), job.getLog().contains("these plugins were not installed: broken"));
    }

    @Test
    public void theOfficialPluginsAreListedMostInstalledFirst() {
        TerminowToolInstaller.PluginCatalog catalog = new TerminowToolInstaller.PluginCatalog();
        TerminowToolInstaller.parsePlugins(catalog, "noise\nTERMINOW_JSON_BEGIN\n" +
            "{\"installed\":[{\"id\":\"code-review@claude-plugins-official\"},{\"id\":\"x@other\"}],\"available\":[" +
            "{\"name\":\"a\",\"description\":\"A\",\"marketplaceName\":\"claude-plugins-official\",\"installCount\":5}," +
            "{\"name\":\"b\",\"description\":\"B\",\"marketplaceName\":\"claude-plugins-official\",\"installCount\":50}," +
            "{\"name\":\"c\",\"description\":\"C\",\"marketplaceName\":\"anthropic-plugin-directory\",\"installCount\":99}]}\n" +
            "TERMINOW_JSON_END\n");
        assertNull(catalog.error);
        assertEquals(2, catalog.plugins.size());
        assertEquals("b", catalog.plugins.get(0).name);
        assertEquals("a", catalog.plugins.get(1).name);
        assertEquals(java.util.Collections.singleton("code-review"), catalog.installed);

        TerminowToolInstaller.PluginCatalog none = new TerminowToolInstaller.PluginCatalog();
        TerminowToolInstaller.parsePlugins(none, "TERMINOW_NO_CLAUDE\n");
        assertTrue(none.noClaude);
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

    @Test
    public void theFoldersOfTermiNowAreNotOnThePath() throws Exception {
        assumeTrue(new File("/bin/sh").exists());
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", TerminowToolInstaller.OWN_PATH + "echo \"$PATH\"");
        builder.environment().put("PATH", "/usr/local/bin:/usr/bin:/data/data/com.terminow/files/usr/bin:/bin");
        builder.environment().put("HOME", "/home/u");
        Process process = builder.start();
        String path = new String(readAll(process.getInputStream()), StandardCharsets.UTF_8).trim();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals("/home/u/.local/bin:/usr/local/bin:/usr/bin:/bin", path);
    }

    @Test
    public void fishIsInstalledWithAptAndOnlyTakenAsInstalledOnceItRuns() throws Exception {
        TerminowToolInstaller.Runner runner = environment("0", "none", "", "ok");
        TerminowToolInstaller.Job job = installAndWait(runner, "root", TerminowToolInstaller.Tool.FISH);
        assertEquals(job.getLog(), TerminowToolInstaller.State.SUCCEEDED, job.getState());
        assertEquals("fish, version 3.7.0", job.getVersion());
        assertTrue(calls(), calls().contains("install -y --no-install-recommends fish"));
        assertEquals("fish, version 3.7.0", TerminowToolInstaller.detect(runner, "ubuntu", "root").versions.get(TerminowToolInstaller.Tool.FISH));
        // Installed already: apt is not run again
        java.nio.file.Files.write(new File(mDir, "calls").toPath(), new byte[0]);
        TerminowToolInstaller.reset();
        TerminowToolInstaller.Job again = installAndWait(runner, "root", TerminowToolInstaller.Tool.FISH);
        assertEquals(TerminowToolInstaller.State.SUCCEEDED, again.getState());
        assertFalse(calls(), calls().contains("apt-get"));
    }

    /** Runs just the bind_node_bin function against the given HOME and managed Node, asserting it exits 0. */
    private String runBindNodeBin(File home, File nodeHome) throws Exception {
        String body = "fail() { echo \"FAIL $*\"; exit 1; }\n"
            + TerminowToolInstaller.BIND_NODE_BIN
            + "NODE_HOME=\"" + nodeHome.getPath() + "\"\n"
            + "bind_node_bin gemini\n";
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", body).redirectErrorStream(true);
        builder.environment().put("HOME", home.getPath());
        Process p = builder.start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        assertTrue("bind_node_bin did not finish", p.waitFor(30, TimeUnit.SECONDS));
        assertEquals("bind_node_bin failed: " + out, 0, p.exitValue());
        return out;
    }

    /**
     * Regression for the Gemini/Codex/Grok launcher bug: bind_node_bin must never write through the
     * npm executable symlink. It replaces only the launcher link with a separate regular launcher
     * file and leaves the package's JS entry point (the symlink's resolved target) unchanged.
     */
    @Test
    public void bindNodeBinNeverWritesThroughTheNpmSymlink() throws Exception {
        assumeTrue(new File("/bin/sh").exists());
        File home = new File(temp.getRoot(), "h");

        // npm's layout: ~/.local/bin/gemini is a relative symlink to the package's JS entry point.
        File entry = new File(home, ".local/lib/node_modules/@google/gemini-cli/bundle/gemini.js");
        assertTrue(entry.getParentFile().mkdirs());
        String js = "#!/usr/bin/env node\nconsole.log('gemini 1.2.3');\n";
        Files.write(entry.toPath(), js.getBytes(StandardCharsets.UTF_8));
        File bin = new File(home, ".local/bin");
        assertTrue(bin.mkdirs());
        File link = new File(bin, "gemini");
        Files.createSymbolicLink(link.toPath(),
            Paths.get("../lib/node_modules/@google/gemini-cli/bundle/gemini.js"));
        assertTrue(Files.isSymbolicLink(link.toPath()));

        // A managed Node with the version-independent "current" link ensure_node maintains.
        File nodeDir = new File(home, ".terminow/node/node-v22.23.4-linux-arm64");
        File node = new File(nodeDir, "bin/node");
        assertTrue(node.getParentFile().mkdirs());
        Files.write(node.toPath(), "#!/bin/sh\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(node.setExecutable(true));
        Files.createSymbolicLink(new File(home, ".terminow/node/current").toPath(), nodeDir.toPath());

        runBindNodeBin(home, nodeDir);

        // The package's JS entry point is byte-for-byte unchanged.
        assertEquals("the package JS entry point must not be modified",
            js, new String(Files.readAllBytes(entry.toPath()), StandardCharsets.UTF_8));
        // The npm symlink is replaced by a separate, regular, executable launcher.
        assertFalse("the launcher must no longer be a symlink", Files.isSymbolicLink(link.toPath()));
        assertTrue("the launcher must be a regular file", link.isFile());
        assertTrue("the launcher must be executable", link.canExecute());
        String launcher = new String(Files.readAllBytes(link.toPath()), StandardCharsets.UTF_8);
        assertTrue(launcher, launcher.startsWith("#!/bin/sh\n"));
        assertTrue(launcher, launcher.contains("/.terminow/node/current/bin/node"));
        assertTrue(launcher, launcher.contains(entry.getCanonicalPath()));
        assertTrue(launcher, launcher.trim().endsWith("\"$@\""));

        // Idempotent: a second run leaves the regular launcher untouched (only symlinks are rewritten),
        // and still never touches the JS entry point.
        runBindNodeBin(home, nodeDir);
        assertFalse(Files.isSymbolicLink(link.toPath()));
        assertEquals("the launcher must be unchanged on a rerun",
            launcher, new String(Files.readAllBytes(link.toPath()), StandardCharsets.UTF_8));
        assertEquals("the JS entry point must still be unchanged",
            js, new String(Files.readAllBytes(entry.toPath()), StandardCharsets.UTF_8));
    }

    /** The package JS entry point npm would install; prints {@code version} on --version. */
    private static String geminiJs(String version) {
        return "#!/usr/bin/env node\n"
            + "if (process.argv.slice(2).includes('--version')) { console.log('" + version + "'); process.exit(0); }\n"
            + "console.log('gemini', '" + version + "');\n";
    }

    /** Recreates npm's layout under home (package JS entry + a fresh bin symlink), as a (re)install/upgrade would. */
    private File npmInstall(File home, String version) throws Exception {
        File entry = new File(home, ".local/lib/node_modules/@google/gemini-cli/bundle/gemini.js");
        if (!entry.getParentFile().isDirectory()) assertTrue(entry.getParentFile().mkdirs());
        Files.write(entry.toPath(), geminiJs(version).getBytes(StandardCharsets.UTF_8));
        File bin = new File(home, ".local/bin");
        if (!bin.isDirectory()) assertTrue(bin.mkdirs());
        File link = new File(bin, "gemini");
        Files.deleteIfExists(link.toPath());
        Files.createSymbolicLink(link.toPath(), Paths.get("../lib/node_modules/@google/gemini-cli/bundle/gemini.js"));
        return entry;
    }

    /** Points ~/.terminow/node/current at a managed Node whose bin/node is the host node; returns the node dir. */
    private File managedNode(File home) throws Exception {
        File nodeDir = new File(home, ".terminow/node/node-v22.23.4-linux-arm64");
        File node = new File(nodeDir, "bin/node");
        if (!node.getParentFile().isDirectory()) assertTrue(node.getParentFile().mkdirs());
        Files.deleteIfExists(node.toPath());
        Files.createSymbolicLink(node.toPath(), Paths.get("/usr/bin/node"));
        File current = new File(home, ".terminow/node/current");
        Files.deleteIfExists(current.toPath());
        Files.createSymbolicLink(current.toPath(), nodeDir.toPath());
        return nodeDir;
    }

    /** Runs just the reset_npm_bin function (the installer's recovery step) for the given bin + package. */
    private void runResetNpmBin(File home, String bin, String pkg) throws Exception {
        String body = TerminowToolInstaller.RESET_NPM_BIN + "reset_npm_bin '" + bin + "' '" + pkg + "'\n";
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", body).redirectErrorStream(true);
        builder.environment().put("HOME", home.getPath());
        Process p = builder.start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        assertTrue("reset_npm_bin did not finish", p.waitFor(30, TimeUnit.SECONDS));
        assertEquals("reset_npm_bin failed: " + out, 0, p.exitValue());
    }

    /**
     * Launcher behaviour only (not Gemini CLI compatibility): with the host node as a stand-in for the
     * managed Node and NO node on PATH — the app's Open environment — the generated launcher still runs
     * the tool through the managed Node. Real Gemini CLI compatibility needs Node 22 on a device.
     */
    @Test
    public void geminiLauncherRunsWithNodeAbsentFromPath() throws Exception {
        assumeTrue(new File("/bin/sh").exists() && new File("/usr/bin/node").exists());
        File home = new File(temp.getRoot(), "h2");
        File entry = npmInstall(home, "9.9.9");
        File nodeDir = managedNode(home);
        runBindNodeBin(home, nodeDir);

        File link = new File(home, ".local/bin/gemini");
        File binDir = new File(home, ".local/bin");
        assertFalse("the test PATH must not contain node", new File(binDir, "node").exists());
        ProcessBuilder pb = new ProcessBuilder(link.getPath(), "--version").redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().put("PATH", binDir.getPath());   // only ~/.local/bin, no node anywhere on PATH
        pb.environment().put("HOME", home.getPath());
        Process p = pb.start();
        String out = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8).trim();
        assertTrue("launcher did not finish", p.waitFor(30, TimeUnit.SECONDS));
        assertEquals("launcher output: " + out, 0, p.exitValue());
        assertEquals("the launcher must run the tool via the managed node with no node on PATH", "9.9.9", out);
        assertEquals("the JS entry point must be unchanged", geminiJs("9.9.9"),
            new String(Files.readAllBytes(entry.toPath()), StandardCharsets.UTF_8));
    }

    /**
     * Across a first install, a retry (same version re-linked), and an upgrade (new version re-linked),
     * pinning never alters the package JS entry point, and each launcher is a complete regular file with
     * no half-written temp left behind (atomic creation).
     */
    @Test
    public void jsEntryPointSurvivesInstallRetryAndUpgrade() throws Exception {
        assumeTrue(new File("/bin/sh").exists());
        File home = new File(temp.getRoot(), "h3");
        File nodeDir = managedNode(home);
        File entry = new File(home, ".local/lib/node_modules/@google/gemini-cli/bundle/gemini.js");
        File binDir = new File(home, ".local/bin");
        File link = new File(binDir, "gemini");
        for (String version : new String[] {"1.0.0", "1.0.0", "2.5.1"}) {
            npmInstall(home, version);                                   // npm (re)creates the entry + a symlink
            assertTrue("npm relinks the bin as a symlink", Files.isSymbolicLink(link.toPath()));
            runBindNodeBin(home, nodeDir);
            assertEquals("pinning must not modify the JS entry point", geminiJs(version),
                new String(Files.readAllBytes(entry.toPath()), StandardCharsets.UTF_8));
            assertFalse("the launcher is a regular file, not a symlink", Files.isSymbolicLink(link.toPath()));
            assertTrue(link.isFile() && link.canExecute());
            String launcher = new String(Files.readAllBytes(link.toPath()), StandardCharsets.UTF_8);
            assertTrue(launcher, launcher.startsWith("#!/bin/sh\n") && launcher.trim().endsWith("\"$@\""));
            assertTrue(launcher, launcher.contains(entry.getCanonicalPath()));
            File[] temps = binDir.listFiles((d, n) -> n.startsWith("gemini.launch."));
            assertTrue("atomic creation leaves no temp launcher", temps == null || temps.length == 0);
        }
    }

    /**
     * A corrupted install (the pre-fix launcher written through the symlink, clobbering gemini.js) is
     * recovered by reset_npm_bin: the launcher and package tree are removed so a reinstall restores a
     * clean entry point, while user settings and credentials under ~/.gemini are preserved.
     */
    @Test
    public void recoversACorruptedInstallPreservingSettingsAndCredentials() throws Exception {
        assumeTrue(new File("/bin/sh").exists());
        File home = new File(temp.getRoot(), "h4");
        File entry = npmInstall(home, "3.0.0");
        File link = new File(home, ".local/bin/gemini");

        // Reproduce the old bug: writing through the symlink clobbers the package's JS entry point.
        String junk = "#!/bin/sh\nexec \"/x/node\" \"" + entry.getCanonicalPath() + "\" \"$@\"\n";
        Files.write(entry.toPath(), junk.getBytes(StandardCharsets.UTF_8));
        assertTrue(new String(Files.readAllBytes(entry.toPath()), StandardCharsets.UTF_8).startsWith("#!/bin/sh"));

        // Settings + credentials live outside node_modules.
        File settings = new File(home, ".gemini/settings.json");
        assertTrue(settings.getParentFile().mkdirs());
        Files.write(settings.toPath(), "{\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8));
        File creds = new File(home, ".gemini/oauth_creds.json");
        Files.write(creds.toPath(), "{\"token\":\"secret\"}".getBytes(StandardCharsets.UTF_8));

        runResetNpmBin(home, "gemini", "@google/gemini-cli");

        assertFalse("the launcher must be removed", link.exists() || Files.isSymbolicLink(link.toPath()));
        assertFalse("the corrupted package tree must be removed",
            new File(home, ".local/lib/node_modules/@google/gemini-cli").exists());
        assertTrue("settings must be preserved", settings.exists());
        assertEquals("{\"theme\":\"dark\"}", new String(Files.readAllBytes(settings.toPath()), StandardCharsets.UTF_8));
        assertTrue("credentials must be preserved", creds.exists());
        assertEquals("{\"token\":\"secret\"}", new String(Files.readAllBytes(creds.toPath()), StandardCharsets.UTF_8));

        // A reinstall (npm restores files + symlink) then pinning yields a clean, uncorrupted launcher.
        File restored = npmInstall(home, "3.0.0");
        File nodeDir = managedNode(home);
        runBindNodeBin(home, nodeDir);
        assertEquals("the reinstalled JS entry point is clean", geminiJs("3.0.0"),
            new String(Files.readAllBytes(restored.toPath()), StandardCharsets.UTF_8));
        assertFalse(Files.isSymbolicLink(link.toPath()));
        assertTrue(link.isFile());
    }
}
