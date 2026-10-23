package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.view.KeyEvent;

import com.termux.terminal.KeyHandler;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The shell of a new tab of local Ubuntu, chosen in one place, the start script of the tab:
 * Fish when it runs, else Bash with a notice, and the shell that Ubuntu gives when nothing was
 * chosen. Run here with a stand-in Fish that says how it was started.
 */
public class TerminowFishShellTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** A stand-in for fish that prints its arguments and PATH, or one that does not run. */
    private File fish(boolean works) throws Exception {
        File file = new File(temp.getRoot(), works ? "fish" : "broken-fish");
        String script = works ?
            "#!/bin/sh\nif [ \"$1\" = --version ]; then echo 'fish, version 3.7.0'; exit 0; fi\necho \"FISH_RAN $*\"; echo \"FISH_PATH $PATH\"\n" :
            "#!/bin/sh\nexit 127\n";
        Files.write(file.toPath(), script.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setExecutable(true));
        return file;
    }

    /** Run the start script of a tab as proot-distro would, with no terminal. */
    private String start(String shell, String fishPaths, String loginShell, String path) throws Exception {
        assumeTrue(new File("/bin/sh").exists());
        List<String> command = new ArrayList<>(TerminowLaunchPlan.interactiveShell(shell));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().put("HOME", temp.getRoot().getPath());
        builder.environment().put("PATH", path);
        builder.environment().put("TERMINOW_FISH_PATHS", fishPaths);
        if (loginShell != null) builder.environment().put("SHELL", loginShell);
        Process process = builder.start();
        process.getOutputStream().close();
        String out = read(process.getInputStream());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        return out;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }

    @Test
    public void fishThatRunsOpensAsALoginShellWithLocalBinOnThePath() throws Exception {
        String home = temp.getRoot().getPath();
        String out = start(TerminowStore.UBUNTU_SHELL_FISH, fish(true).getPath(), null, "/usr/bin:/bin");
        assertTrue(out, out.contains("FISH_RAN -l"));
        assertTrue(out, out.contains("FISH_PATH " + home + "/.local/bin:/usr/bin:/bin"));
        assertFalse("no notice when Fish runs", out.contains("Bash opened"));
        // Not twice on the PATH, when it is there already
        out = start(TerminowStore.UBUNTU_SHELL_FISH, fish(true).getPath(), null, home + "/.local/bin:/usr/bin:/bin");
        assertTrue(out, out.contains("FISH_PATH " + home + "/.local/bin:/usr/bin:/bin\n"));
    }

    @Test
    public void withoutAFishThatRunsBashOpensAndSaysSo() throws Exception {
        assumeTrue(new File("/bin/bash").exists());
        String missing = new File(temp.getRoot(), "no-fish").getPath();
        for (String paths : Arrays.asList(missing, fish(false).getPath())) {
            String out = start(TerminowStore.UBUNTU_SHELL_FISH, paths, null, "/usr/bin:/bin");
            assertTrue(out, out.contains("Fish is not installed in this Ubuntu, so Bash opened"));
            assertFalse(out, out.contains("FISH_RAN"));
        }
    }

    @Test
    public void bashAndNothingChosenNeverStartFish() throws Exception {
        File loginShell = new File(temp.getRoot(), "login-shell");
        Files.write(loginShell.toPath(), "#!/bin/sh\necho \"LOGIN_SHELL $*\"\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(loginShell.setExecutable(true));
        // Not chosen: the login shell that Ubuntu gives, as before the setting
        String out = start(TerminowStore.UBUNTU_SHELL_DEFAULT, fish(true).getPath(), loginShell.getPath(), "/usr/bin:/bin");
        assertTrue(out, out.contains("LOGIN_SHELL -l"));
        assertFalse(out, out.contains("FISH_RAN"));
        assumeTrue(new File("/bin/bash").exists());
        out = start(TerminowStore.UBUNTU_SHELL_BASH, fish(true).getPath(), loginShell.getPath(), "/usr/bin:/bin");
        assertFalse(out, out.contains("FISH_RAN"));
        assertFalse("Bash itself, not the login shell", out.contains("LOGIN_SHELL"));
    }

    @Test
    public void theShellIsAnArgumentOfTheOneStartScript() {
        assertEquals(Arrays.asList("/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL), TerminowLaunchPlan.interactiveShell());
        assertEquals(Arrays.asList("/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL, "terminow", "fish"),
            TerminowLaunchPlan.interactiveShell(TerminowStore.UBUNTU_SHELL_FISH));
        // One line, so that it can be typed into a TermiNow shell, and nothing is added to startup files
        assertFalse(TerminowLaunchPlan.NO_XOFF_SHELL.contains("\n"));
        assertFalse(TerminowLaunchPlan.NO_XOFF_SHELL.contains(".bashrc"));
        assertFalse(TerminowLaunchPlan.NO_XOFF_SHELL.contains("chsh"));
        // SHELL is left as it is, for programs like Claude Code that run commands with it
        assertFalse(TerminowLaunchPlan.NO_XOFF_SHELL.contains("SHELL="));
    }

    @Test
    public void onlyShellTabsGetTheShell() {
        TerminowLaunchPlan shell = TerminowLaunchPlan.build("/p/usr", "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_OFF, "", false, Collections.emptyList(), "", Collections.emptyList(), "", TerminowStore.UBUNTU_SHELL_FISH);
        assertEquals(Arrays.asList("/bin/sh", "-c", TerminowLaunchPlan.NO_XOFF_SHELL, "terminow", "fish"),
            shell.arguments.subList(shell.arguments.indexOf("--") + 1, shell.arguments.size()));
        // Claude Code and custom commands run with /bin/sh and Bash, never in Fish
        TerminowLaunchPlan claude = TerminowLaunchPlan.build("/p/usr", "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_CLAUDE, "",
            TerminowLaunchPlan.TMUX_OFF, "", false, Collections.emptyList(), "", Collections.emptyList(), "", TerminowStore.UBUNTU_SHELL_FISH);
        assertFalse(claude.arguments.contains("fish"));
        TerminowLaunchPlan custom = TerminowLaunchPlan.build("/p/usr", "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_CUSTOM, "make",
            TerminowLaunchPlan.TMUX_OFF, "", false, Collections.emptyList(), "", Collections.emptyList(), "", TerminowStore.UBUNTU_SHELL_FISH);
        assertTrue(custom.arguments.contains("/bin/bash"));
        assertFalse(custom.arguments.contains("fish"));
        // In tmux, the first window of a new session is the start script with the shell
        TerminowLaunchPlan tmux = TerminowLaunchPlan.build("/p/usr", "ubuntu", "root", "/root", TerminowLaunchPlan.TOOL_SHELL, "",
            TerminowLaunchPlan.TMUX_ATTACH, "work", false, Collections.emptyList(), "", Collections.emptyList(), "", TerminowStore.UBUNTU_SHELL_FISH);
        assertTrue(tmux.arguments.contains(TerminowLaunchPlan.CONTAINER_TMUX_SCRIPT));
        assertEquals(Arrays.asList(TerminowLaunchPlan.NO_XOFF_SHELL, "terminow", "fish"),
            tmux.arguments.subList(tmux.arguments.size() - 3, tmux.arguments.size()));
    }

    @Test
    public void typedIntoATermiNowShellTheShellIsOneQuotedWord() {
        String line = TerminowLauncher.enterCommand("/p/usr", "ubuntu", "root", "/root", Collections.emptyList(), TerminowStore.UBUNTU_SHELL_FISH);
        assertFalse(line.contains("\n"));
        assertTrue(line, line.endsWith(" 'terminow' 'fish'"));
    }

    @Test
    public void theShellIsRememberedAndNotChosenUntilItIs() {
        TerminowStore.sTestsWithoutKeystore = true;
        MemoryPreferences preferences = new MemoryPreferences();
        TerminowStore store = new TerminowStore(preferences);
        // An Ubuntu from before the setting keeps its shell
        assertEquals(TerminowStore.UBUNTU_SHELL_DEFAULT, store.getUbuntuShell());
        store.setUbuntuShell(TerminowStore.UBUNTU_SHELL_FISH);
        assertEquals(TerminowStore.UBUNTU_SHELL_FISH, new TerminowStore(preferences).getUbuntuShell());
        store.setUbuntuShell(TerminowStore.UBUNTU_SHELL_BASH);
        assertEquals(TerminowStore.UBUNTU_SHELL_BASH, new TerminowStore(preferences).getUbuntuShell());
        store.setUbuntuShell("zsh");
        assertEquals(TerminowStore.UBUNTU_SHELL_DEFAULT, store.getUbuntuShell());
    }

    @Test
    public void rightArrowOfTheKeyBarIsTheKeyFishAcceptsASuggestionWith() {
        // The key bar sends the key, as a key event, which the terminal turns into these bytes
        assertEquals("\033[C", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0, false, false));
        assertEquals("\033OC", KeyHandler.getCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0, true, false));
        assertTrue(TerminowKeyBarModel.NAVIGATION_KEYS.contains("RIGHT"));
    }

    @Test
    public void aSuggestionColorOfTheThemeIsOnlyForADefaultColor() throws Exception {
        String out = start("fish:8a8a8a", fish(true).getPath(), null, "/usr/bin:/bin");
        assertTrue(out, out.contains("FISH_RAN -l -C if not set -q fish_color_autosuggestion[1]; or contains -- \"$fish_color_autosuggestion\" brblack '555 brblack'; "
            + "set -g fish_color_autosuggestion 8a8a8a; end"));
        // What is no color is left out, never given to Fish
        out = start("fish:8a8a8a; rm -rf /", fish(true).getPath(), null, "/usr/bin:/bin");
        assertTrue(out, out.contains("FISH_RAN -l\n"));
    }

    /** The name of a tab says fish only when the start script finds a Fish to run. */
    @Test
    public void theTabIsNamedFishOnlyWhenFishIsInTheRootfs() throws Exception {
        File rootfs = temp.newFolder("rootfs");
        assertFalse(TerminowLauncher.hasFish(rootfs));
        File local = new File(rootfs, "usr/local/bin/fish");
        assertTrue(local.getParentFile().mkdirs());
        Files.write(local.toPath(), new byte[0]);
        assertTrue(TerminowLauncher.hasFish(rootfs));
        assertTrue(local.delete());
        assertTrue(new File(rootfs, "usr/bin/fish").mkdirs());
        // A folder is not a Fish
        assertFalse(TerminowLauncher.hasFish(rootfs));
    }

    @Test
    public void suggestionsAreReadableInEveryTheme() {
        List<TerminowThemes.Theme> themes = new ArrayList<>(Arrays.asList(TerminowThemes.THEMES));
        themes.add(TerminowThemes.DEFAULT_THEME);
        for (TerminowThemes.Theme theme : themes) {
            int color = Integer.parseInt(TerminowThemes.suggestionColor(theme), 16);
            double contrast = TerminowThemes.contrast(color, theme.background);
            assertTrue(theme.name + " " + contrast, contrast >= 3 || contrast >= TerminowThemes.contrast(theme.foreground, theme.background) - 0.01);
            // Dimmer than the text, so a suggestion never looks typed
            assertTrue(theme.name, color != theme.foreground || TerminowThemes.contrast(theme.foreground, theme.background) < 3);
        }
    }
}
