package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What the launch profiles run: the arguments of each environment, tool and tmux behaviour,
 * the scripts run with a real shell, and with the real tmux of this machine if it has one.
 */
public class TerminowLaunchPlanTest {

    private static final String PREFIX = "/data/data/com.terminow/files/usr";
    private static final String ODD_FOLDER = "/data/data/com.terminow/files/home/my project's $HOME `x`";

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static String run(File directory, Map<String, String> extra, List<String> command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory).redirectErrorStream(true);
        builder.environment().remove("TMUX");
        builder.environment().putAll(extra);
        Process process = builder.start();
        assertTrue("timed out", process.waitFor(20, TimeUnit.SECONDS));
        return new String(readAll(process.getInputStream()), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    // -------------------------------------------------------------------------------------------
    // Arguments
    // -------------------------------------------------------------------------------------------

    @Test
    public void localShellIsTheDefaultLoginShellInTheFolder() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "", "root", ODD_FOLDER, TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_OFF, "", true);
        assertEquals("", plan.executable);
        assertTrue(plan.arguments.isEmpty());
        assertEquals(ODD_FOLDER, plan.workingDirectory);
        assertFalse(plan.tmuxMissing);
    }

    @Test
    public void localTmuxAttachKeepsEveryArgumentSeparate() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, TerminowMcpTargets.TARGET_TERMINOW, "root", ODD_FOLDER,
            TerminowLaunchPlan.TOOL_SHELL, "", TerminowLaunchPlan.TMUX_ATTACH, "my-project", true);
        assertEquals(PREFIX + "/bin/bash", plan.executable);
        assertEquals(Arrays.asList("-c", TerminowLaunchPlan.LOCAL_SHELL_SCRIPT, "terminow", PREFIX + "/bin/bash", PREFIX + "/bin/tmux",
            "new-session", "-A", "-s", "my-project", "-c", ODD_FOLDER, PREFIX + "/bin/bash", "-l"), plan.arguments);
        assertEquals(ODD_FOLDER, plan.workingDirectory);
    }

    @Test
    public void localTmuxCreateHasNoAttach() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "", "root", ODD_FOLDER, TerminowLaunchPlan.TOOL_CLAUDE, "",
            TerminowLaunchPlan.TMUX_CREATE, "work", true);
        assertFalse(plan.arguments.contains("-A"));
        assertEquals(Arrays.asList(PREFIX + "/bin/bash", "-c", TerminowLauncher.CLAUDE_SCRIPT, "claude"),
            plan.arguments.subList(plan.arguments.size() - 4, plan.arguments.size()));
    }

    @Test
    public void localTmuxMissingStartsWithoutItAndSaysSo() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "", "root", ODD_FOLDER, TerminowLaunchPlan.TOOL_CLAUDE_CONTINUE, "",
            TerminowLaunchPlan.TMUX_ATTACH, "work", false);
        assertTrue(plan.tmuxMissing);
        assertEquals(PREFIX + "/bin/bash", plan.executable);
        assertEquals(Arrays.asList("-c", TerminowLauncher.CLAUDE_SCRIPT, "claude", "--continue"), plan.arguments);
    }

    @Test
    public void containerShellIsTheLoginShellOfTheUser() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "ubuntu", "dev", "/home/dev/my project", TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_OFF, "", true);
        assertEquals(PREFIX + "/bin/proot-distro", plan.executable);
        // The login shell of the user, which $SHELL is, without Ctrl+S pausing it
        assertEquals(Arrays.asList("login", "ubuntu", "--user", "dev", "--work-dir", "/home/dev/my project",
            "--", "/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL), plan.arguments);
        assertEquals("/data/data/com.terminow/files/home", plan.workingDirectory);
    }

    @Test
    public void containerClaudeInTmux() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "ubuntu", "root", "/root/app", TerminowLaunchPlan.TOOL_CLAUDE_RESUME, "",
            TerminowLaunchPlan.TMUX_ATTACH, "app", true);
        assertEquals(Arrays.asList("login", "ubuntu", "--user", "root", "--work-dir", "/root/app", "--", "/bin/sh", "-c",
            TerminowLaunchPlan.CONTAINER_TMUX_SCRIPT, "terminow", "attach", "app", "terminow-root", "/bin/sh", "-c", TerminowLauncher.CLAUDE_SCRIPT,
            "claude", "--resume"), plan.arguments);
    }

    @Test
    public void customCommandIsOneArgumentOfALoginShell() {
        String command = "npm run dev -- --port 3000 && echo \"done\"";
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "ubuntu", "root", "/root/app", TerminowLaunchPlan.TOOL_CUSTOM, command,
            TerminowLaunchPlan.TMUX_OFF, "", true);
        assertEquals(Arrays.asList("login", "ubuntu", "--user", "root", "--work-dir", "/root/app", "--", "/bin/bash", "-lc", command),
            plan.arguments);
    }

    @Test
    public void loginIsNeverInTmux() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_CLAUDE_LOGIN, "",
            TerminowLaunchPlan.TMUX_ATTACH, "x", true);
        assertFalse(plan.arguments.contains(TerminowLaunchPlan.CONTAINER_TMUX_SCRIPT));
        assertEquals(Arrays.asList("auth", "login"), plan.arguments.subList(plan.arguments.size() - 2, plan.arguments.size()));
    }

    @Test
    public void refusesWhatCouldBeReadAsOptions() {
        for (String[] bad : new String[][]{{"ubuntu;id", "root", "x"}, {"ubuntu", "--help", "x"}, {"ubuntu", "root", "a b"},
            {"ubuntu", "root", "-t"}, {"ubuntu", "root", ""}}) {
            try {
                TerminowLaunchPlan.build(PREFIX, bad[0], bad[1], "/root", TerminowLaunchPlan.TOOL_SHELL, "", TerminowLaunchPlan.TMUX_ATTACH, bad[2], true);
                throw new AssertionError("accepted " + Arrays.toString(bad));
            } catch (IllegalArgumentException expected) {
                // Refused
            }
        }
        assertEquals("my-project-2", TerminowLaunchPlan.tmuxNameFor("My Project #2"));
        assertEquals("terminow", TerminowLaunchPlan.tmuxNameFor("✳️"));
        assertTrue(TerminowLaunchPlan.isValidTmuxName(TerminowLaunchPlan.tmuxNameFor("-- weird; $(name) --")));
    }

    // -------------------------------------------------------------------------------------------
    // The scripts, run with a real shell
    // -------------------------------------------------------------------------------------------

    /** A program that prints its folder and arguments. */
    private static List<String> printer() {
        return Arrays.asList("/bin/sh", "-c", "printf 'PWD=%s\\n' \"$PWD\"; printf 'SHELL=%s\\n' \"$SHELL\"; for a; do printf 'ARG=[%s]\\n' \"$a\"; done", "p");
    }

    private List<String> containerScript(String mode, String name, String... args) {
        List<String> command = new ArrayList<>(Arrays.asList("/bin/sh", "-c", TerminowLaunchPlan.CONTAINER_TMUX_SCRIPT, "terminow", mode, name, "terminow-dev"));
        command.addAll(printer());
        command.addAll(Arrays.asList(args));
        return command;
    }

    @Test
    public void containerScriptWithoutTmuxRunsTheProgramAndSaysWhy() throws Exception {
        File bin = mFolder.newFolder("empty-bin");
        File dir = mFolder.newFolder("work dir");
        // Only a folder without tmux, and the shell by its full path
        String output = run(dir, mapOf("PATH", bin.getPath(), "TERMINOW_TMUX_DIRS", bin.getPath()), containerScript("attach", "x", "a b", "$(id)"));
        assertTrue(output, output.contains("tmux is not installed in this environment"));
        assertTrue(output, output.contains("PWD=" + dir.getPath() + "\n"));
        assertTrue(output, output.contains("ARG=[a b]\nARG=[$(id)]\n"));
    }

    @Test
    public void containerScriptInsideTmuxDoesNotNestIt() throws Exception {
        File bin = fakeTmux();
        String output = run(mFolder.getRoot(), mapOf("PATH", "/usr/bin:/bin", "TERMINOW_TMUX_DIRS", bin.getPath(), "TMUX", "/tmp/sock,1,0"),
            containerScript("attach", "x", "plain"));
        assertFalse(output, output.contains("TMUX-ARG"));
        assertTrue(output, output.contains("ARG=[plain]"));
    }

    @Test
    public void containerScriptAttachesOrCreatesInTheFolder() throws Exception {
        File bin = fakeTmux();
        File dir = mFolder.newFolder("pro ject");
        Map<String, String> env = mapOf("PATH", "/usr/bin:/bin", "TERMINOW_TMUX_DIRS", bin.getPath());
        String output = run(dir, env, containerScript("attach", "work", "x y"));
        assertTrue(output, output.contains("TMUX-ARG=[-L]\nTMUX-ARG=[terminow-dev]\nTMUX-ARG=[new-session]\nTMUX-ARG=[-A]\nTMUX-ARG=[-s]\nTMUX-ARG=[work]\nTMUX-ARG=[-c]\nTMUX-ARG=[" + dir.getPath() + "]\n"));
        assertTrue(output, output.contains("TMUX-ARG=[x y]\n"));
        output = run(dir, env, containerScript("create", "work"));
        assertFalse(output, output.contains("TMUX-ARG=[-A]"));
    }

    @Test
    public void localShellScriptSetsShellAndRunsTheRest() throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("bash", "-c", TerminowLaunchPlan.LOCAL_SHELL_SCRIPT, "terminow", "/the/bash"));
        command.addAll(printer());
        command.add("it's");
        String output = run(mFolder.getRoot(), Collections.emptyMap(), command);
        assertTrue(output, output.contains("SHELL=/the/bash\n"));
        assertTrue(output, output.contains("ARG=[it's]\n"));
    }

    /**
     * The local plan, exactly as built, with the real tmux and bash of this machine in a pseudo
     * terminal: the program runs in a tmux session with the name, in the folder.
     */
    @Test
    public void localPlanWithRealTmux() throws Exception {
        assumeTrue(new File("/usr/bin/tmux").canExecute() && new File("/usr/bin/script").canExecute());
        File prefix = mFolder.newFolder("usr");
        File bin = new File(prefix, "bin");
        assertTrue(bin.mkdirs());
        Files.createSymbolicLink(new File(bin, "bash").toPath(), new File("/usr/bin/bash").toPath());
        Files.createSymbolicLink(new File(bin, "tmux").toPath(), new File("/usr/bin/tmux").toPath());
        File dir = mFolder.newFolder("my project's");
        File out = new File(mFolder.getRoot(), "out.txt");
        String program = "printf '%s|%s|%s\\n' \"$PWD\" \"$(tmux display-message -p '#S')\" \"$SHELL\" > " + out.getPath().replace(" ", "\\ ");
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(prefix.getPath(), "", "root", dir.getPath(), TerminowLaunchPlan.TOOL_CUSTOM, program,
            TerminowLaunchPlan.TMUX_ATTACH, "profile-test", true);
        File tmuxDir = mFolder.newFolder("tmux");
        List<String> argv = new ArrayList<>();
        argv.add(plan.executable);
        argv.addAll(plan.arguments);
        // script needs one command line, so each argument is quoted for it
        StringBuilder line = new StringBuilder();
        for (String arg : argv) line.append('\'').append(arg.replace("'", "'\\''")).append("' ");
        run(new File(plan.workingDirectory), mapOf("TMUX_TMPDIR", tmuxDir.getPath(), "TERM", "xterm-256color"),
            Arrays.asList("/usr/bin/script", "-qec", line.toString(), "/dev/null"));
        String result = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8).trim();
        assertEquals(dir.getPath() + "|profile-test|" + bin.getPath() + "/bash", result);
    }

    @Test
    public void tmuxListUsesTheEnvironmentsOwnTmuxAndServer() throws Exception {
        File guest = fakeTmux();
        // A "host" tmux on PATH, like the one of TermiNow that proot-distro adds, which must not be used
        File host = mFolder.newFolder("host-bin");
        File hostTmux = new File(host, "tmux");
        Files.write(hostTmux.toPath(), "#!/bin/sh\necho HOST-TMUX-USED\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(hostTmux.setExecutable(true));
        String output = run(mFolder.getRoot(), mapOf("PATH", host.getPath() + ":/usr/bin:/bin", "TERMINOW_TMUX_DIRS", guest.getPath()),
            Arrays.asList("/bin/sh", "-c", TerminowTmux.SCRIPT, "terminow-setup", "guest", "terminow-dev", "list"));
        assertFalse(output, output.contains("HOST-TMUX-USED"));
        TerminowTmux.State state = TerminowTmux.parse(output);
        assertTrue(state.checked);
        assertTrue(state.isInstalled());
        assertEquals(new File(guest, "tmux").getPath(), state.binary);
        assertTrue(state.socket, state.socket.endsWith("/terminow-dev"));
        assertEquals(2, state.sessions.size());
        assertEquals("main", state.sessions.get(0).name);
        assertEquals(1, state.sessions.get(0).attached);
        assertEquals("my work", state.sessions.get(1).name);
        assertTrue(output, output.contains("SERVER=[terminow-dev]"));

        // Another user has another server
        String other = run(mFolder.getRoot(), mapOf("PATH", "/usr/bin:/bin", "TERMINOW_TMUX_DIRS", guest.getPath()),
            Arrays.asList("/bin/sh", "-c", TerminowTmux.SCRIPT, "terminow-setup", "guest", TerminowLaunchPlan.tmuxSocket(false, "alice"), "list"));
        assertTrue(other, other.contains("SERVER=[terminow-alice]"));
        assertEquals("", TerminowLaunchPlan.tmuxSocket(true, "root"));

        // No tmux in the environment: not installed, even though the host has one
        TerminowTmux.State none = TerminowTmux.parse(run(mFolder.getRoot(), mapOf("PATH", host.getPath() + ":/usr/bin:/bin",
            "TERMINOW_TMUX_DIRS", mFolder.newFolder("empty-guest").getPath()),
            Arrays.asList("/bin/sh", "-c", TerminowTmux.SCRIPT, "terminow-setup", "guest", "terminow-root", "list")));
        assertTrue(none.checked);
        assertFalse(none.isInstalled());
    }

    @Test
    public void tmuxOperationsTargetTheExactSession() throws Exception {
        File guest = fakeTmux();
        Map<String, String> env = mapOf("PATH", "/usr/bin:/bin", "TERMINOW_TMUX_DIRS", guest.getPath());
        String rename = run(mFolder.getRoot(), env, Arrays.asList("/bin/sh", "-c", TerminowTmux.SCRIPT, "s", "guest", "terminow-root", "rename", "old", "new"));
        assertTrue(rename, rename.contains("TMUX-ARG=[rename-session]\nTMUX-ARG=[-t]\nTMUX-ARG=[=old]\nTMUX-ARG=[new]"));
        assertTrue(TerminowTmux.parse(rename).done);
        String kill = run(mFolder.getRoot(), env, Arrays.asList("/bin/sh", "-c", TerminowTmux.SCRIPT, "s", "guest", "terminow-root", "kill", "work"));
        assertTrue(kill, kill.contains("TMUX-ARG=[kill-session]\nTMUX-ARG=[-t]\nTMUX-ARG=[=work]"));
        String detach = run(mFolder.getRoot(), env, Arrays.asList("/bin/sh", "-c", TerminowTmux.SCRIPT, "s", "guest", "terminow-root", "detach", "work"));
        assertTrue(detach, detach.contains("TMUX-ARG=[detach-client]\nTMUX-ARG=[-s]\nTMUX-ARG=[=work]"));
    }

    @Test
    public void containerLaunchHasTheUserFolderAndSharedFolders() {
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "debian", "alice", "/home/alice/src", TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_ATTACH, "work", true, Arrays.asList("--bind", "/storage/emulated/0/Projects:/mnt/projects"));
        assertEquals(Arrays.asList("login", "debian", "--user", "alice", "--work-dir", "/home/alice/src", "--bind",
            "/storage/emulated/0/Projects:/mnt/projects", "--", "/bin/sh", "-c", TerminowLaunchPlan.CONTAINER_TMUX_SCRIPT, "terminow",
            "attach", "work", "terminow-alice", "/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL), plan.arguments);
        assertEquals(PREFIX + "/bin/proot-distro", plan.executable);
    }

    /** A tmux that prints its arguments, and lists two sessions. */
    private File fakeTmux() throws Exception {
        File bin = mFolder.newFolder();
        File tmux = new File(bin, "tmux");
        Files.write(tmux.toPath(), ("#!/bin/sh\n" +
            "if [ \"$1\" = -L ] && [ \"$3\" = list-sessions ]; then echo \"SERVER=[$2]\"; echo 'TERMINOW_SESSION=main|1|3|1700000000'; echo 'TERMINOW_SESSION=my work|0|1|1700000001'; exit 0; fi\n" +
            "for a; do printf 'TMUX-ARG=[%s]\\n' \"$a\"; done\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(tmux.setExecutable(true));
        return bin;
    }

    private static Map<String, String> mapOf(String... pairs) {
        Map<String, String> map = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    // -------------------------------------------------------------------------------------------
    // Profiles and the sign-in link
    // -------------------------------------------------------------------------------------------

    @Test
    public void profilesRefuseSecrets() {
        for (String secret : new String[]{"export ANTHROPIC_API_KEY=sk-ant-api03-abc", "mysql --password=hunter2", "curl -H 'Authorization: Bearer abcdefghijklmnop123'",
            "git clone https://ghp_abcdefghijklmnopqrstuvwxyz0123@github.com/x", "TOKEN=abc ./run", "AKIAABCDEFGHIJKLMNOP"}) {
            assertTrue(secret, TerminowLaunchProfile.looksSecret(secret));
            TerminowLaunchProfile profile = new TerminowLaunchProfile();
            profile.name = "x";
            profile.tool = TerminowLaunchPlan.TOOL_CUSTOM;
            profile.command = secret;
            assertEquals("secret", profile.validate());
        }
        for (String fine : new String[]{"npm run dev", "python3 manage.py runserver", "ssh-agent bash", "cat tokens.txt | wc -l"})
            assertFalse(fine, TerminowLaunchProfile.looksSecret(fine));
    }

    @Test
    public void profilesRoundTripAndAskAgainAfterAChange() throws Exception {
        TerminowLaunchProfile profile = new TerminowLaunchProfile();
        profile.name = "Dev server";
        profile.environment = "ubuntu";
        profile.user = "root";
        profile.folder = "/root/app";
        profile.tool = TerminowLaunchPlan.TOOL_CUSTOM;
        profile.command = "npm run dev";
        profile.tmux = TerminowLaunchPlan.TMUX_ATTACH;
        assertNull(profile.validate());
        assertTrue(profile.needsConfirmation());
        profile.confirmedCommand = profile.command;
        assertFalse(profile.needsConfirmation());

        List<TerminowLaunchProfile> loaded = TerminowLaunchProfile.listFromJson(TerminowLaunchProfile.listToJson(Collections.singletonList(profile)));
        assertEquals(1, loaded.size());
        TerminowLaunchProfile copy = loaded.get(0);
        assertEquals(profile.id, copy.id);
        assertEquals("/root/app", copy.folder);
        assertEquals("dev-server", copy.getTmuxName());
        assertFalse(copy.needsConfirmation());
        copy.command = "npm run dev; rm -rf /";
        assertTrue(copy.needsConfirmation());

        // A profile changed outside the app to hold a secret, or broken, is not loaded
        String tampered = "[{\"name\":\"x\",\"tool\":\"custom\",\"command\":\"--password=1\"},{\"name\":\"\"},5,{\"name\":\"ok\"}]";
        List<TerminowLaunchProfile> kept = TerminowLaunchProfile.listFromJson(tampered);
        assertEquals(1, kept.size());
        assertEquals("ok", kept.get(0).name);
        assertTrue(TerminowLaunchProfile.listFromJson("not json").isEmpty());
    }

    @Test
    public void profileFolderMustBeAPath() {
        TerminowLaunchProfile profile = new TerminowLaunchProfile();
        profile.name = "x";
        profile.folder = "content://com.android.externalstorage.documents/tree/primary%3AProjects";
        assertEquals("folder", profile.validate());
        profile.folder = "/sdcard/Projects";
        assertNull(profile.validate());
    }

    @Test
    public void findsOnlyTheSignInLinkOfClaude() {
        String url = "https://claude.ai/oauth/authorize?code=true&client_id=abc&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback&state=xyz";
        assertEquals(url, TerminowLauncher.findLoginUrl("Browser didn't open? Use the url below to sign in:\n\n" + url + "\n\nPaste code here if prompted >"));
        assertEquals(url, TerminowLauncher.findLoginUrl("Open " + url + "."));
        assertEquals("https://console.anthropic.com/oauth/x", TerminowLauncher.findLoginUrl("see https://example.com/a and https://console.anthropic.com/oauth/x"));
        assertNull(TerminowLauncher.findLoginUrl("https://evil.example/claude.ai/oauth"));
        assertNull(TerminowLauncher.findLoginUrl("https://claude.ai.evil.example/oauth"));
        assertNull(TerminowLauncher.findLoginUrl("no link"));
    }


    // -------------------------------------------------------------------------------------------
    // Programs with arguments and startup commands of Quick Launch profiles
    // -------------------------------------------------------------------------------------------

    @Test
    public void aProgramGetsItsArgumentsAsTheyAreWithoutAShell() throws Exception {
        File folder = mFolder.newFolder();
        List<String> arguments = Arrays.asList("$(touch pwned)", "two words", "it's; rm -rf ~");
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "", "root", folder.getPath(), TerminowLaunchPlan.TOOL_PROGRAM, "",
            TerminowLaunchPlan.TMUX_OFF, "", false, Collections.emptyList(), "/bin/echo", arguments, "");
        assertEquals("/bin/echo", plan.executable);
        assertEquals(arguments, plan.arguments);
        List<String> command = new ArrayList<>();
        command.add(plan.executable);
        command.addAll(plan.arguments);
        assertEquals("$(touch pwned) two words it's; rm -rf ~", run(folder, Collections.emptyMap(), command).trim());
        assertFalse(new File(folder, "pwned").exists());

        // In an environment: after the arguments of proot-distro, a name is found with env in its PATH
        TerminowLaunchPlan guest = TerminowLaunchPlan.build(PREFIX, "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_PROGRAM, "",
            TerminowLaunchPlan.TMUX_OFF, "", false, Collections.emptyList(), "htop", Collections.singletonList("-d 10"), "");
        List<String> args = guest.arguments;
        assertEquals(Arrays.asList("--", "/usr/bin/env", "htop", "-d 10"), args.subList(args.indexOf("--"), args.size()));
    }

    @Test
    public void programsThatAreNotAPathOrANameAreRefused() {
        for (String bad : new String[]{"", "rm -rf /", "a;b", "$(x)", "~/bin/x", "a b"}) {
            try {
                TerminowLaunchPlan.build(PREFIX, "", "root", "/", TerminowLaunchPlan.TOOL_PROGRAM, "", TerminowLaunchPlan.TMUX_OFF, "", false,
                    Collections.emptyList(), bad, Collections.emptyList(), "");
                throw new AssertionError("accepted " + bad);
            } catch (IllegalArgumentException expected) {
                // Refused
            }
        }
        assertTrue(TerminowLaunchProfile.isValidExecutable("/usr/bin/python3.12"));
        assertTrue(TerminowLaunchProfile.isValidExecutable("node"));
    }

    @Test
    public void theStartupCommandRunsFirstAsAnArgumentThenTheShell() throws Exception {
        File folder = mFolder.newFolder();
        String startup = "echo \"start in $(basename \"$PWD\")\"; X=1";
        TerminowLaunchPlan plan = TerminowLaunchPlan.build(PREFIX, "", "root", folder.getPath(), TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_OFF, "", false, Collections.emptyList(), "", Collections.emptyList(), startup);
        // The command is one argument of its own, never put into the script
        assertTrue(plan.arguments.contains(startup));
        assertFalse(plan.arguments.get(1).contains("basename"));
        // The script with a real shell: the startup command, then what follows it, here instead of a login shell
        String out = run(folder, Collections.emptyMap(), Arrays.asList("sh", "-c", TerminowLaunchPlan.STARTUP_SCRIPT, "terminow", startup,
            "sh", "-c", "echo shell"));
        assertEquals("start in " + folder.getName() + "\nshell", out.trim());

        TerminowLaunchPlan guest = TerminowLaunchPlan.build(PREFIX, "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_OFF, "", false, Collections.emptyList(), "", Collections.emptyList(), "cd /srv");
        assertTrue(guest.arguments.contains("cd /srv"));
    }

    @Test
    public void profilesOfBeforeLoadAndKeepTheirConfirmation() throws Exception {
        String old = "[{\"id\":\"p1\",\"name\":\"Build\",\"icon\":\"🛠️\",\"environment\":\"terminow\",\"user\":\"root\"," +
            "\"folder\":\"\",\"tool\":\"custom\",\"command\":\"make\",\"tmux\":\"off\",\"confirmedCommand\":\"make\"}]";
        List<TerminowLaunchProfile> profiles = TerminowLaunchProfile.listFromJson(old);
        assertEquals(1, profiles.size());
        TerminowLaunchProfile profile = profiles.get(0);
        assertEquals("p1", profile.id);
        assertEquals("", profile.executable);
        assertTrue(profile.arguments.isEmpty());
        assertEquals(TerminowLaunchProfile.COLORS[0], profile.color);
        // What was confirmed before is still what runs
        assertFalse(profile.needsConfirmation());

        // A program, saved and read again, and confirmed once
        TerminowLaunchProfile program = new TerminowLaunchProfile();
        program.name = "Top";
        program.tool = TerminowLaunchPlan.TOOL_PROGRAM;
        program.executable = "htop";
        program.arguments = new ArrayList<>(Arrays.asList("-d", "10"));
        program.color = TerminowLaunchProfile.COLORS[2];
        assertEquals(null, program.validate());
        assertTrue(program.needsConfirmation());
        program.confirmedCommand = program.whatRuns();
        TerminowLaunchProfile read = TerminowLaunchProfile.listFromJson(TerminowLaunchProfile.listToJson(Collections.singletonList(program))).get(0);
        assertEquals(Arrays.asList("-d", "10"), read.arguments);
        assertEquals(TerminowLaunchProfile.COLORS[2], read.color);
        assertFalse(read.needsConfirmation());
        // A changed argument asks again
        read.arguments.add("--tree");
        assertTrue(read.needsConfirmation());
        // A startup command is never a secret store either
        read.tool = TerminowLaunchPlan.TOOL_SHELL;
        read.startup = "export ANTHROPIC_API_KEY=sk-ant-x";
        assertEquals("secret", read.validate());
    }


    @Test
    public void ubuntuShellsDoNotPauseOnCtrlS() {
        // Ctrl+S goes to the program, it does not stop the output until Ctrl+Q
        // After the sign that it runs
        assertTrue(TerminowLaunchPlan.NO_XOFF_SHELL.startsWith(TerminowLaunchPlan.READY_SIGN + "stty -ixon 2>/dev/null; "));
        TerminowLaunchPlan plain = TerminowLaunchPlan.build(PREFIX, "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_OFF, "", true);
        List<String> args = plain.arguments;
        assertEquals(Arrays.asList("--", "/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL), args.subList(args.indexOf("--"), args.size()));
    }
}
