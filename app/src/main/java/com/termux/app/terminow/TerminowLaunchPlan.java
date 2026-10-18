package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a launch runs: the program, its arguments and its folder, for an environment, a tool and
 * a tmux behaviour. The folder, names and arguments are always separate arguments, never put into
 * a shell command line, so paths with spaces or quotes are safe. Only a custom command, which the
 * user wrote and saved, is given to a shell as it is.
 */
public final class TerminowLaunchPlan {

    public static final String TOOL_SHELL = "shell";
    public static final String TOOL_CLAUDE = "claude";
    public static final String TOOL_CLAUDE_CONTINUE = "claude-continue";
    public static final String TOOL_CLAUDE_RESUME = "claude-resume";
    public static final String TOOL_CLAUDE_LOGIN = "claude-login";
    public static final String TOOL_CUSTOM = "custom";
    /** A program with its arguments, run directly, without a shell. */
    public static final String TOOL_PROGRAM = "program";

    /** Runs the user's startup command ($1), then the login shell, which the command can not replace. */
    static final String STARTUP_SCRIPT = "eval \"$1\"; shift; exec \"$@\"";

    /**
     * The login shell of a Linux environment, with Ctrl+S and Ctrl+Q given to programs instead
     * of pausing and resuming the terminal, which looks like it froze.
     */
    static final String NO_XOFF_SHELL = "stty -ixon 2>/dev/null; exec \"${SHELL:-/bin/bash}\" -l";

    /** The arguments that start {@link #NO_XOFF_SHELL}. */
    @NonNull
    public static List<String> interactiveShell() {
        return Arrays.asList("/bin/sh", "-c", NO_XOFF_SHELL);
    }

    public static final String TMUX_OFF = "off";
    public static final String TMUX_CREATE = "create";
    public static final String TMUX_ATTACH = "attach";

    /** Sets SHELL, which tmux needs for its windows, then runs the rest of the arguments. */
    static final String LOCAL_SHELL_SCRIPT = "SHELL=\"$1\"; export SHELL; shift; exec \"$@\"";

    /**
     * Runs the arguments in tmux in a proot-distro container: attaches to the session or creates
     * it, on the tmux server of the user ($3, like "terminow-root"). Only the tmux of the
     * environment itself is used, never the one of TermiNow that proot-distro puts at the end of
     * PATH. Without tmux, or inside tmux already, it runs the arguments directly, so there is
     * always a usable shell and tmux is never nested.
     */
    static final String CONTAINER_TMUX_SCRIPT =
        "mode=\"$1\"; name=\"$2\"; socket=\"$3\"; shift 3\n" +
        "t=\n" +
        "for d in ${TERMINOW_TMUX_DIRS:-/usr/local/bin /usr/bin /bin}; do if [ -x \"$d/tmux\" ]; then t=\"$d/tmux\"; break; fi; done\n" +
        "if [ -z \"$t\" ]; then\n" +
        "  echo \"TermiNow: tmux is not installed in this environment. Install it from Open tools.\"\n" +
        "  echo \"TermiNow: starting without tmux\"\n" +
        "  exec \"$@\"\n" +
        "fi\n" +
        "if [ -n \"$TMUX\" ]; then exec \"$@\"; fi\n" +
        "SHELL=$(command -v bash || echo /bin/sh); export SHELL\n" +
        "if [ \"$mode\" = attach ]; then exec \"$t\" -L \"$socket\" new-session -A -s \"$name\" -c \"$PWD\" \"$@\"; fi\n" +
        "exec \"$t\" -L \"$socket\" new-session -s \"$name\" -c \"$PWD\" \"$@\"\n";

    /**
     * The tmux server of a user in an environment: each environment has its own /tmp, and each
     * user gets a server of their own, so sessions of different users and environments never
     * mix. TermiNow's own (local) tmux keeps its default server, where Local Terminals are.
     */
    @NonNull
    public static String tmuxSocket(boolean local, @NonNull String user) {
        return local ? "" : "terminow-" + user;
    }

    private static final Pattern TMUX_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_-]{0,39}");

    /** The program, empty for the default login shell of the app, and its arguments. */
    public final String executable;
    public final List<String> arguments;
    public final String workingDirectory;
    /** Set when tmux was asked for but is not installed, so the launch is without it. */
    public final boolean tmuxMissing;

    private TerminowLaunchPlan(String executable, List<String> arguments, String workingDirectory, boolean tmuxMissing) {
        this.executable = executable;
        this.arguments = Collections.unmodifiableList(arguments);
        this.workingDirectory = workingDirectory;
        this.tmuxMissing = tmuxMissing;
    }

    /** Whether a tmux session name is one that tmux and the launcher accept. */
    public static boolean isValidTmuxName(@NonNull String name) {
        return TMUX_NAME.matcher(name).matches();
    }

    /** A tmux session name from a profile name, like "my-project". */
    @NonNull
    public static String tmuxNameFor(@NonNull String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 30) slug = slug.substring(0, 30);
        return slug.isEmpty() ? "terminow" : slug;
    }

    /** The arguments of Claude Code for a tool. */
    @NonNull
    static List<String> claudeArguments(@NonNull String tool) {
        switch (tool) {
            case TOOL_CLAUDE_CONTINUE: return Collections.singletonList("--continue");
            case TOOL_CLAUDE_RESUME: return Collections.singletonList("--resume");
            case TOOL_CLAUDE_LOGIN: return Arrays.asList("auth", "login");
            default: return Collections.emptyList();
        }
    }

    public static boolean isClaude(@NonNull String tool) {
        return tool.startsWith("claude");
    }

    /**
     * @param prefix The prefix of TermiNow, like /data/data/com.terminow/files/usr.
     * @param container The proot-distro container, empty for TermiNow itself.
     * @param folder The folder in the environment, checked by the caller.
     * @param localTmuxInstalled Whether TermiNow has tmux, which is checked for containers in the container.
     */
    @NonNull
    public static TerminowLaunchPlan build(@NonNull String prefix, @NonNull String container, @NonNull String user,
                                           @NonNull String folder, @NonNull String tool, @NonNull String customCommand,
                                           @NonNull String tmuxMode, @NonNull String tmuxName, boolean localTmuxInstalled) {
        return build(prefix, container, user, folder, tool, customCommand, tmuxMode, tmuxName, localTmuxInstalled,
            Collections.emptyList());
    }

    /**
     * @param bindArguments More arguments of `proot-distro login`, the bindings of the folders
     *                      that the user shared into the environment.
     */
    @NonNull
    public static TerminowLaunchPlan build(@NonNull String prefix, @NonNull String container, @NonNull String user,
                                           @NonNull String folder, @NonNull String tool, @NonNull String customCommand,
                                           @NonNull String tmuxMode, @NonNull String tmuxName, boolean localTmuxInstalled,
                                           @NonNull List<String> bindArguments) {
        return build(prefix, container, user, folder, tool, customCommand, tmuxMode, tmuxName, localTmuxInstalled, bindArguments,
            "", Collections.emptyList(), "");
    }

    /**
     * @param executable For {@link #TOOL_PROGRAM}: the program, run directly with {@code programArguments}.
     * @param startup For {@link #TOOL_SHELL}: the user's command that runs first, given to the shell as an
     *                argument, never put into a command line; empty for none.
     */
    @NonNull
    public static TerminowLaunchPlan build(@NonNull String prefix, @NonNull String container, @NonNull String user,
                                           @NonNull String folder, @NonNull String tool, @NonNull String customCommand,
                                           @NonNull String tmuxMode, @NonNull String tmuxName, boolean localTmuxInstalled,
                                           @NonNull List<String> bindArguments, @NonNull String executable,
                                           @NonNull List<String> programArguments, @NonNull String startup) {
        if (TOOL_PROGRAM.equals(tool) && !TerminowLaunchProfile.isValidExecutable(executable))
            throw new IllegalArgumentException("Invalid program");
        boolean withStartup = TOOL_SHELL.equals(tool) && !startup.trim().isEmpty();
        if (!TerminowMcpTargets.TARGET_TERMINOW.equals(container) && !container.isEmpty()) {
            if (!TerminowMcpTargets.isValidContainerName(container) || !TerminowMcpTargets.isValidUserName(user))
                throw new IllegalArgumentException("Invalid container or user");
        }
        if (!TMUX_OFF.equals(tmuxMode) && !isValidTmuxName(tmuxName)) throw new IllegalArgumentException("Invalid tmux session name");
        if (TOOL_CUSTOM.equals(tool) && customCommand.trim().isEmpty()) throw new IllegalArgumentException("No command");
        boolean local = container.isEmpty() || TerminowMcpTargets.TARGET_TERMINOW.equals(container);
        // A login gets its own session, which is never kept in tmux
        if (TOOL_CLAUDE_LOGIN.equals(tool)) tmuxMode = TMUX_OFF;

        if (local) {
            String bash = prefix + "/bin/bash";
            List<String> program = new ArrayList<>();
            if (withStartup) {
                program.addAll(Arrays.asList(bash, "-c", STARTUP_SCRIPT, "terminow", startup, bash, "-l"));
            } else if (TOOL_SHELL.equals(tool)) {
                program.addAll(Arrays.asList(bash, "-l"));
            } else if (TOOL_PROGRAM.equals(tool)) {
                // A name without a path is found in the PATH of the TermiNow shell
                if (executable.startsWith("/")) program.add(executable);
                else program.addAll(Arrays.asList("/system/bin/env", executable));
                program.addAll(programArguments);
            } else if (TOOL_CUSTOM.equals(tool)) {
                program.addAll(Arrays.asList(bash, "-lc", customCommand));
            } else {
                program.addAll(Arrays.asList(bash, "-c", TerminowLauncher.CLAUDE_SCRIPT, "claude"));
                program.addAll(claudeArguments(tool));
            }
            boolean tmux = !TMUX_OFF.equals(tmuxMode);
            String plainTool = withStartup ? TOOL_CUSTOM : tool;
            if (tmux && !localTmuxInstalled) return withoutTmux(program, folder, plainTool, true);
            if (!tmux) return withoutTmux(program, folder, plainTool, false);
            List<String> args = new ArrayList<>(Arrays.asList("-c", LOCAL_SHELL_SCRIPT, "terminow", bash, prefix + "/bin/tmux", "new-session"));
            if (TMUX_ATTACH.equals(tmuxMode)) args.add("-A");
            args.addAll(Arrays.asList("-s", tmuxName, "-c", folder));
            args.addAll(program);
            return new TerminowLaunchPlan(bash, args, folder, false);
        }

        List<String> args = new ArrayList<>(Arrays.asList("login", container, "--user", user, "--work-dir", folder));
        args.addAll(bindArguments);
        List<String> program = new ArrayList<>();
        if (withStartup) {
            program.addAll(Arrays.asList("/bin/sh", "-c", STARTUP_SCRIPT, "terminow", startup));
            program.addAll(interactiveShell());
        } else if (TOOL_PROGRAM.equals(tool)) {
            if (executable.startsWith("/")) program.add(executable);
            else program.addAll(Arrays.asList("/usr/bin/env", executable));
            program.addAll(programArguments);
        } else if (TOOL_CUSTOM.equals(tool)) {
            program.addAll(Arrays.asList("/bin/bash", "-lc", customCommand));
        } else if (isClaude(tool)) {
            program.addAll(Arrays.asList("/bin/sh", "-c", TerminowLauncher.CLAUDE_SCRIPT, "claude"));
            program.addAll(claudeArguments(tool));
        } else {
            // A shell, in tmux or not
            program.addAll(interactiveShell());
        }
        if (!TMUX_OFF.equals(tmuxMode)) {
            args.addAll(Arrays.asList("--", "/bin/sh", "-c", CONTAINER_TMUX_SCRIPT, "terminow", tmuxMode, tmuxName, tmuxSocket(false, user)));
            args.addAll(program);
        } else if (!program.isEmpty()) {
            args.add("--");
            args.addAll(program);
        }
        return new TerminowLaunchPlan(prefix + "/bin/proot-distro", args, prefix.replaceAll("/usr$", "") + "/home", false);
    }

    @NonNull
    private static TerminowLaunchPlan withoutTmux(@NonNull List<String> program, @NonNull String folder, @NonNull String tool, boolean tmuxMissing) {
        // A plain shell is the default login shell of the app, which also sets SHELL
        if (TOOL_SHELL.equals(tool)) return new TerminowLaunchPlan("", new ArrayList<>(), folder, tmuxMissing);
        return new TerminowLaunchPlan(program.get(0), new ArrayList<>(program.subList(1, program.size())), folder, tmuxMissing);
    }

}
