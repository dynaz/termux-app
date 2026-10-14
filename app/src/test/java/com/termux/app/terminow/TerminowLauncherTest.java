package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The scripts of the launcher, run with bash like on the phone: what it finds out about Claude
 * Code, and how it starts it. A fake `claude` shows exactly what it receives.
 */
public class TerminowLauncherTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static String run(File home, File directory, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put("HOME", home.getPath());
        // Only the system tools, so that a real claude on this machine is not found
        environment.put("PATH", "/usr/bin:/bin");
        Process process = builder.start();
        byte[] output = readAll(process.getInputStream());
        process.waitFor();
        return new String(output, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    /** A claude in ~/.local/bin, where the native installer puts it. */
    private File fakeClaude(String loggedIn, boolean withAuth) throws Exception {
        File home = mFolder.newFolder("home");
        File bin = new File(home, ".local/bin");
        assertTrue(bin.mkdirs());
        File claude = new File(bin, "claude");
        String auth = withAuth ?
            "if [ \"$1\" = auth ] && [ \"$2\" = --help ]; then echo 'Commands: login  logout  status'; exit 0; fi\n" +
            "if [ \"$1\" = auth ] && [ \"$2\" = status ]; then " + loggedIn + "; exit 0; fi\n" : "";
        String script = "#!/bin/sh\n" +
            "if [ \"$1\" = --version ]; then echo '9.9.9 (Claude Code)'; exit 0; fi\n" +
            "if [ \"$1\" = --help ]; then echo '  -c, --continue  Continue\n  -r, --resume [value]  Resume'; exit 0; fi\n" +
            auth +
            "printf 'PWD=%s\\n' \"$PWD\"; for a in \"$@\"; do printf 'ARG=[%s]\\n' \"$a\"; done\n";
        Files.write(claude.toPath(), script.getBytes(StandardCharsets.UTF_8));
        assertTrue(claude.setExecutable(true));
        return home;
    }

    private static TerminowLauncher.ClaudeState detect(File home) throws Exception {
        TerminowLauncher.ClaudeState state = new TerminowLauncher.ClaudeState();
        TerminowLauncher.parse(state, run(home, home, Arrays.asList("bash", "-c", TerminowLauncher.DETECT_SCRIPT, "terminow-setup")));
        return state;
    }

    @Test
    public void detectsTheInstallationAndWhatItSupports() throws Exception {
        File home = fakeClaude("echo '{\n  \"loggedIn\": true,\n  \"email\": \"someone@example.com\"\n}'", true);
        TerminowLauncher.ClaudeState state = detect(home);
        assertTrue(state.checked);
        assertTrue(state.isInstalled());
        assertTrue(state.path.endsWith("/.local/bin/claude"));
        assertEquals("9.9.9 (Claude Code)", state.version);
        assertTrue(state.supportsContinue);
        assertTrue(state.supportsResume);
        assertTrue(state.supportsLogin);
        assertEquals("yes", state.loggedIn);
    }

    @Test
    public void loginStateComesOnlyFromTheCli() throws Exception {
        assertEquals("no", detect(fakeClaude("echo '{\"loggedIn\": false, \"authMethod\": \"none\"}'", true)).loggedIn);
    }

    @Test
    public void unknownWhenTheCliCanNotTell() throws Exception {
        // An executable alone never means logged in
        TerminowLauncher.ClaudeState state = detect(fakeClaude("echo 'something else'", true));
        assertTrue(state.isInstalled());
        assertEquals("unknown", state.loggedIn);
    }

    @Test
    public void olderCliWithoutAuthCommands() throws Exception {
        TerminowLauncher.ClaudeState state = detect(fakeClaude("", false));
        assertTrue(state.isInstalled());
        assertFalse(state.supportsLogin);
        assertEquals("unknown", state.loggedIn);
    }

    @Test
    public void notInstalled() throws Exception {
        File home = mFolder.newFolder("empty");
        TerminowLauncher.ClaudeState state = detect(home);
        assertTrue(state.checked);
        assertFalse(state.isInstalled());
    }

    @Test
    public void claudeStartsInTheFolderWithArgumentsAsTheyAre() throws Exception {
        File home = fakeClaude("echo '{}'", true);
        File project = new File(mFolder.getRoot(), "my project's $HOME `x` (1)");
        assertTrue(project.mkdirs());
        List<String> command = new ArrayList<>(Arrays.asList("bash", "-c", TerminowLauncher.CLAUDE_SCRIPT, "claude"));
        command.addAll(Arrays.asList("--resume", "a b; rm -rf ~", "$(id)"));
        String output = run(home, project, command);
        assertTrue(output, output.contains("PWD=" + project.getPath() + "\n"));
        assertTrue(output, output.contains("ARG=[--resume]\nARG=[a b; rm -rf ~]\nARG=[$(id)]\n"));
    }

    @Test
    public void realClaudeCodeIfInstalledHere() throws Exception {
        File real = new File(System.getProperty("user.home"), ".local/bin/claude");
        assumeTrue(real.canExecute());
        File home = mFolder.newFolder("fresh");
        File bin = new File(home, ".local/bin");
        assertTrue(bin.mkdirs());
        Files.createSymbolicLink(new File(bin, "claude").toPath(), real.toPath());
        TerminowLauncher.ClaudeState state = detect(home);
        assertTrue(state.isInstalled());
        assertTrue(state.version, state.version.contains("Claude Code"));
        assertTrue(state.supportsContinue);
        assertTrue(state.supportsResume);
        assertTrue(state.supportsLogin);
        // A home that never logged in
        assertEquals("no", state.loggedIn);
    }


    @Test
    public void ubuntuOpensAsItsUserInItsFolderWithItsSharedFolders() {
        List<String> args = TerminowLauncher.ubuntuLoginArgs("ubuntu2", "dev", "/home/dev/project",
            Arrays.asList("--bind", "/storage/emulated/0/Projects:/mnt/projects"));
        assertEquals(Arrays.asList("login", "ubuntu2", "--user", "dev", "--work-dir", "/home/dev/project",
            "--bind", "/storage/emulated/0/Projects:/mnt/projects"), args);
        // Separate arguments: a folder with a space is never split or run as a command
        assertEquals("/home/dev/my project", TerminowLauncher.ubuntuLoginArgs("ubuntu", "root", "/home/dev/my project",
            new ArrayList<>()).get(5));
    }

}
