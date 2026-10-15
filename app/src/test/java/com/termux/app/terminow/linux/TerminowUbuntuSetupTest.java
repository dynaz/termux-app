package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The automatic Ubuntu setup with a fake runtime that records every command. */
public class TerminowUbuntuSetupTest {

    private File mPrefix;
    private File mHome;
    private TerminowLinuxRegistry mRegistry;
    private final List<List<String>> mCommands = new ArrayList<>();
    private final Set<String> mInstalled = new HashSet<>(Arrays.asList("dpkg", "bash", "python", "proot", "proot-distro"));
    private final List<String> mInstalls = new ArrayList<>();
    private String mStored;
    private final List<String> mLog = new ArrayList<>();
    /** Answers that override the healthy runtime, by a part of the command. */
    private final List<Object[]> mAnswers = new ArrayList<>();
    private String mAptInstallOutput = "";
    private int mAptInstallExit = 0;
    @Nullable private String mFreeAlias;

    @Before
    public void setUp() throws Exception {
        mPrefix = Files.createTempDirectory("terminow-prefix").toFile();
        mHome = Files.createTempDirectory("terminow-home").toFile();
        assertTrue(new File(mPrefix, "var/lib/dpkg/info").mkdirs());
        // The environment of the app, as the bootstrap installs it
        write(new File(mPrefix, "bin/bash"), "elf");
        mRegistry = new TerminowLinuxRegistry(mPrefix, mHome, new TerminowLinuxRegistry.Settings() {
            private JSONObject mSettings = new JSONObject();

            @NonNull
            @Override
            public JSONObject load() {
                return mSettings;
            }

            @Override
            public void save(@NonNull JSONObject settings) {
                mSettings = settings;
            }
        }, new TerminowLinuxTest.JvmOps(), null);
    }

    private void answer(@NonNull String part, int exit, @NonNull String output) {
        mAnswers.add(new Object[]{part, exit, output});
    }

    /** A healthy runtime, where apt installs what it is asked to. */
    private int shell(@NonNull List<String> command, @NonNull TerminowUbuntuSetup.LineSink output) {
        mCommands.add(command);
        String joined = String.join(" ", command);
        for (Object[] answer : mAnswers) {
            if (joined.contains((String) answer[0])) {
                for (String line : ((String) answer[2]).split("\n")) if (!line.isEmpty()) output.line(line);
                return (int) answer[1];
            }
        }
        String tool = new File(command.get(0)).getName();
        if (tool.equals("dpkg-query")) {
            String name = command.get(command.size() - 1);
            output.line(mInstalled.contains(name) ? "install ok installed" : "unknown ok not-installed");
            return mInstalled.contains(name) ? 0 : 1;
        }
        if (tool.equals("apt-get")) {
            if (command.contains("install")) {
                for (String line : mAptInstallOutput.split("\n")) if (!line.isEmpty()) output.line(line);
                if (mAptInstallExit == 0) mInstalled.addAll(Arrays.asList("proot-distro", "proot", "python"));
                return mAptInstallExit;
            }
            return 0;
        }
        if (tool.equals("python3")) {
            output.line(TerminowUbuntuSetup.MARK + "SPAWN_OK");
            return 0;
        }
        if (tool.equals("proot-distro") && command.contains("--help")) {
            output.line("Usage: proot-distro [command] ... install ... login ...");
            return 0;
        }
        if (tool.equals("proot-distro") && command.contains("login")) {
            if (joined.contains("os-release")) {
                output.line("PRETTY_NAME=\"Ubuntu 24.04.5 LTS\"");
                output.line("ID=ubuntu");
                output.line("VERSION_ID=\"24.04\"");
                output.line(TerminowUbuntuSetup.MARK + "OS_OK");
                output.line(TerminowUbuntuSetup.MARK + "HOME_OK /root");
                output.line(TerminowUbuntuSetup.MARK + "CMD_OK aarch64");
            } else if (joined.contains("getent")) {
                output.line(TerminowUbuntuSetup.MARK + "DNS_OK");
            }
            return 0;
        }
        if (tool.equals("bash") && joined.contains(TerminowUbuntuSetup.MARK + "BASH_OK")) {
            output.line(TerminowUbuntuSetup.MARK + "BASH_OK");
            return 0;
        }
        return 0;
    }

    @NonNull
    private TerminowUbuntuSetup setup() {
        return new TerminowUbuntuSetup(mPrefix, mRegistry, this::shell, new TerminowUbuntuSetup.Installer() {
            @NonNull
            @Override
            public TerminowLinuxEnv install(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull String alias,
                                            @NonNull TerminowLinuxInstaller.Listener listener) throws IOException {
                mInstalls.add(entry.distroId + " " + entry.version + " " + alias);
                listener.onPhase(TerminowLinuxInstaller.Phase.DOWNLOADING, entry.url);
                listener.onProgress(10, 100);
                listener.onPhase(TerminowLinuxInstaller.Phase.EXTRACTING, "Extracting");
                makeUbuntu(alias);
                TerminowLinuxEnv env = mRegistry.get(alias);
                if (env == null) throw new IOException("not created");
                return env;
            }

            @NonNull
            @Override
            public String freeAlias(@NonNull String distroId) {
                return mFreeAlias != null ? mFreeAlias : distroId;
            }

            @Override
            public void cancel() {
            }
        }, new TerminowUbuntuSetup.StateStore() {
            @Nullable
            @Override
            public String load() {
                return mStored;
            }

            @Override
            public void save(@NonNull String state) {
                mStored = state;
            }
        }, new TerminowUbuntuSetup.LogSink() {
            @Override
            public void append(@NonNull String line) {
                mLog.add(line);
            }

            @Override
            public void clear() {
                mLog.clear();
            }
        });
    }

    /** An installed Ubuntu container, like proot-distro leaves it. */
    private void makeUbuntu(@NonNull String alias) throws IOException {
        File rootfs = new File(mPrefix, "var/lib/proot-distro/containers/" + alias + "/rootfs");
        write(new File(rootfs, "etc/os-release"), "PRETTY_NAME=\"Ubuntu 24.04.5 LTS\"\nNAME=\"Ubuntu\"\nID=ubuntu\nVERSION_ID=\"24.04\"\n");
        write(new File(rootfs, "bin/sh"), "elf");
        write(new File(rootfs, "etc/passwd"), "root:x:0:0:root:/root:/bin/bash\n");
        assertTrue(new File(rootfs, "root").mkdirs() || new File(rootfs, "root").isDirectory());
    }

    private static void write(@NonNull File file, @NonNull String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) assertTrue(parent.mkdirs());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @NonNull
    private TerminowUbuntuSetup.State runSetup() {
        TerminowUbuntuSetup setup = setup();
        assertTrue(setup.start());
        setup.run();
        return setup.getState();
    }

    private boolean ran(@NonNull Function<List<String>, Boolean> matcher) {
        for (List<String> command : mCommands) if (matcher.apply(command)) return true;
        return false;
    }

    private boolean ranTool(@NonNull String tool, @NonNull String argument) {
        return ran(command -> new File(command.get(0)).getName().equals(tool) && command.contains(argument));
    }

    // ---------------------------------------------------------------------------------------

    @Test
    public void aFreshInstallGoesThroughEveryStageAndIsReadyOnlyAfterTheChecks() {
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(String.join("\n", mLog), TerminowUbuntuSetup.State.Status.READY, state.status);
        // The pinned release of the catalog, never ubuntu:latest
        TerminowLinuxCatalog.Entry ubuntu = TerminowLinuxCatalog.find("ubuntu");
        assertEquals(Arrays.asList("ubuntu " + ubuntu.version + " ubuntu"), mInstalls);
        assertFalse(ran(command -> String.join(" ", command).contains("ubuntu:latest")));
        assertEquals("ubuntu", state.alias);
        // The version comes from /etc/os-release of the installed system
        assertEquals("Ubuntu 24.04.5 LTS", state.version);
        assertTrue(ranTool("proot-distro", "login"));
        assertTrue(state.warnings.isEmpty());
    }

    @Test
    public void aRequiredDependencyThatFailsStopsTheSetup() {
        mInstalled.remove("proot-distro");
        mAptInstallExit = 100;
        mAptInstallOutput = "E: Unable to locate package proot-distro";
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.FAILED, state.status);
        assertEquals(TerminowUbuntuSetup.Stage.INSTALLING_DEPENDENCIES, state.failedStage);
        // The exit status of apt itself
        assertTrue(state.error, state.error.contains("exit 100"));
        assertTrue(state.error, state.error.contains("Unable to locate package"));
        assertTrue(mInstalls.isEmpty());
        assertFalse(ranTool("proot-distro", "login"));
    }

    @Test
    public void aLockOfAnotherInstallIsWaitedForAndNeverRemoved() {
        mInstalled.remove("proot-distro");
        answer("update", 100, "E: Could not get lock /data/data/com.terminow/files/usr/var/lib/dpkg/lock-frontend. It is held by process 4242 (apt)");
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.FAILED, state.status);
        assertTrue(state.error, state.error.contains("process 4242"));
        // apt waited for it
        assertTrue(ran(command -> command.contains("DPkg::Lock::Timeout=120")));
        assertFalse(ran(command -> String.join(" ", command).contains("lock-frontend")));
        assertFalse(ran(command -> new File(command.get(0)).getName().equals("rm")));
    }

    @Test
    public void ifPythonCanNotStartBashNothingIsInstalled() {
        answer("subprocess.run", 0, "PermissionError: [Errno 13] Permission denied: '" + mPrefix + "/bin/bash'");
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.FAILED, state.status);
        assertEquals(TerminowUbuntuSetup.Stage.CHECKING_PACKAGES, state.failedStage);
        assertTrue(state.error, state.error.contains("Python can not start bash"));
        assertTrue(state.error, state.error.contains("Permission denied"));
        assertTrue(mInstalls.isEmpty());
    }

    @Test
    public void anUbuntuThatIsInstalledIsKeptAndChecked() throws Exception {
        makeUbuntu("ubuntu");
        File userFile = new File(mPrefix, "var/lib/proot-distro/containers/ubuntu/rootfs/root/notes.txt");
        write(userFile, "mine");
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.READY, state.status);
        assertTrue("not installed again", mInstalls.isEmpty());
        assertEquals("ubuntu", state.alias);
        assertEquals("mine", new String(Files.readAllBytes(userFile.toPath()), StandardCharsets.UTF_8));
        assertTrue(ranTool("proot-distro", "login"));
    }

    @Test
    public void anIncompleteEnvironmentIsLeftAndANewOneHasItsOwnName() throws Exception {
        File incomplete = new File(mPrefix, "var/lib/proot-distro/containers/ubuntu/rootfs");
        write(new File(incomplete, "half.txt"), "left");
        mFreeAlias = "ubuntu2";
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.READY, state.status);
        assertEquals(1, mInstalls.size());
        assertTrue(mInstalls.get(0), mInstalls.get(0).endsWith(" ubuntu2"));
        assertEquals("ubuntu2", state.alias);
        assertTrue(new File(incomplete, "half.txt").isFile());
    }

    @Test
    public void aSetupThatTheAppDiedInCanBeRetried() {
        TerminowUbuntuSetup.State running = new TerminowUbuntuSetup.State();
        running.status = TerminowUbuntuSetup.State.Status.RUNNING;
        running.stage = TerminowUbuntuSetup.Stage.DOWNLOADING;
        mStored = running.toJson().toString();

        TerminowUbuntuSetup setup = setup();
        assertTrue(setup.recoverInterrupted());
        TerminowUbuntuSetup.State state = setup.getState();
        assertEquals(TerminowUbuntuSetup.State.Status.FAILED, state.status);
        assertEquals(TerminowUbuntuSetup.Stage.DOWNLOADING, state.failedStage);
        assertTrue(state.interrupted);
        // Only once
        assertFalse(setup.recoverInterrupted());

        assertTrue(setup.start());
        setup.run();
        assertEquals(TerminowUbuntuSetup.State.Status.READY, setup.getState().status);
        assertFalse(setup.getState().interrupted);
    }

    @Test
    public void oneSetupAtATime() {
        TerminowUbuntuSetup setup = setup();
        assertTrue(setup.start());
        assertTrue(setup.isRunning());
        assertFalse("a second tap does not start another", setup.start());
        // A running setup is not taken for one that the app died in
        assertFalse(setup.recoverInterrupted());
        setup.run();
        assertFalse(setup.isRunning());
        assertTrue(setup.start());
    }

    @Test
    public void theNetworkIsReportedApartFromUbuntu() {
        answer("getent", 0, TerminowUbuntuSetup.MARK + "DNS_FAIL");
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.READY, state.status);
        assertEquals(1, state.warnings.size());
        assertTrue(state.warnings.get(0), state.warnings.get(0).contains("DNS"));
    }

    @Test
    public void ubuntuThatDoesNotStartIsNotReady() {
        answer("os-release", 1, "proot error: execve(\"/bin/sh\"): Permission denied");
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.FAILED, state.status);
        assertEquals(TerminowUbuntuSetup.Stage.VERIFYING, state.failedStage);
        assertTrue(state.error, state.error.contains("Permission denied"));
    }

    @Test
    public void pythonFilesThatOlderPackageScriptsFailedToCompileAreCompiledAgain() throws Exception {
        String python = mPrefix + "/lib/python3.14/";
        write(new File(mPrefix, "var/lib/dpkg/info/python.postinst"), "#!/bin/sh\nif [ -f \"" + mPrefix + "/bin/py3compile\" ]; then\n" +
            "LD_PRELOAD='' \"" + mPrefix + "/bin/py3compile\" -p \"python\" \"" + python + "\"\nfi\nexit 0\n");
        // A fixed script, which already keeps termux-exec
        write(new File(mPrefix, "var/lib/dpkg/info/proot-distro.postinst"), "#!/bin/sh\n\"" + mPrefix + "/bin/py3compile\" -p \"proot-distro\" \"" + python + "\"\n");
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.READY, state.status);
        // The same command, without the empty LD_PRELOAD
        List<String> expected = Arrays.asList(mPrefix + "/bin/py3compile", "-p", "python", new File(python).getPath());
        assertTrue(mCommands.contains(expected));
        assertFalse(ran(command -> command.contains("proot-distro") && new File(command.get(0)).getName().equals("py3compile")));
        assertTrue(state.compiled.contains("python"));

        // Not again
        mCommands.clear();
        runSetup();
        assertFalse(mCommands.contains(expected));
    }

    @Test
    public void pythonFilesThatCanNotBeCompiledAreAWarning() throws Exception {
        write(new File(mPrefix, "var/lib/dpkg/info/python.postinst"),
            "LD_PRELOAD='' \"" + mPrefix + "/bin/py3compile\" -p \"python\" \"" + mPrefix + "/lib/python3.14/\"\n");
        answer("py3compile -p python", 1, "SyntaxError");
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.READY, state.status);
        assertEquals(1, state.warnings.size());
        assertTrue(state.warnings.get(0), state.warnings.get(0).contains("Host package configuration is incomplete"));
        assertFalse(state.compiled.contains("python"));
    }

    @Test
    public void theStateIsKeptForANewScreen() {
        runSetup();
        TerminowUbuntuSetup.State state = setup().getState();
        assertEquals(TerminowUbuntuSetup.State.Status.READY, state.status);
        assertEquals("ubuntu", state.alias);
        assertEquals("Ubuntu 24.04.5 LTS", state.version);
        assertNull(state.failedStage);
    }

    @Test
    public void theRuntimeReportListsOnlyKnownVariables() {
        runSetup();
        List<String> runtime = mCommands.get(0);
        String script = runtime.get(runtime.size() - 1);
        for (String key : TerminowUbuntuSetup.ENVIRONMENT_KEYS) assertTrue(key, script.contains(key));
        assertFalse(script.contains("printenv\n"));
        assertFalse(script.contains("\nenv\n"));
        assertFalse(script.contains("set\n"));
    }

    /**
     * The check itself, with the Python and bash of this machine: Python starts bash with
     * subprocess, like py3compile does, and the result is read from its output.
     */
    @Test
    public void thePythonCheckStartsBashWithSubprocess() throws Exception {
        Assume.assumeTrue(new File("/usr/bin/python3").canExecute() && new File("/bin/bash").canExecute());
        Files.delete(new File(mPrefix, "bin/bash").toPath());
        Files.createSymbolicLink(new File(mPrefix, "bin/bash").toPath(), new File("/bin/bash").toPath());
        ProcessBuilder builder = new ProcessBuilder("/usr/bin/python3", "-c", TerminowUbuntuSetup.PYTHON_SPAWN_CHECK);
        builder.environment().put("PREFIX", mPrefix.getPath());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertTrue(out, out.contains(TerminowUbuntuSetup.MARK + "SPAWN_OK"));

        // A bash that can not be executed: the error is printed, not hidden
        Files.delete(new File(mPrefix, "bin/bash").toPath());
        write(new File(mPrefix, "bin/bash"), "#!/bin/sh\n");
        assertTrue(new File(mPrefix, "bin/bash").setExecutable(false));
        process = builder.start();
        out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertFalse(out, out.contains(TerminowUbuntuSetup.MARK + "SPAWN_OK"));
        assertTrue(out, out.contains("PermissionError"));
    }

    @Test
    public void lockMessagesAreRecognized() {
        assertTrue(TerminowUbuntuSetup.LOCK_HELD.matcher("E: Could not get lock /x/lock-frontend. It is held by process 12 (apt)").find());
        assertTrue(TerminowUbuntuSetup.LOCK_HELD.matcher("dpkg: error: dpkg frontend lock was locked by another process with pid 7").find()
            || TerminowUbuntuSetup.LOCK_HELD.matcher("dpkg: error: dpkg status database is locked by another process").find());
        assertFalse(TerminowUbuntuSetup.LOCK_HELD.matcher("E: Unable to locate package nope").find());
    }


    @Test
    public void withoutTheEnvironmentOfTheAppItSaysSoAndRunsNothing() throws Exception {
        Files.delete(new File(mPrefix, "bin/bash").toPath());
        TerminowUbuntuSetup.State state = runSetup();
        assertEquals(TerminowUbuntuSetup.State.Status.FAILED, state.status);
        assertEquals(TerminowUbuntuSetup.Stage.CHECKING_RUNTIME, state.failedStage);
        assertTrue(state.error, state.error.contains("not installed yet"));
        assertTrue(mCommands.isEmpty());
    }

}
