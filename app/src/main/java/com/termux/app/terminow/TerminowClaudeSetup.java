package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.environment.ShellEnvironmentUtils;
import com.termux.shared.termux.shell.TermuxShellUtils;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Run Claude Code commands in an environment, to find it and to check what it reads from its
 * configuration. Each command is its own process with the arguments as an array and its output
 * captured, never typed into a terminal: a terminal may be running Claude, an editor or anything
 * else, and its history would keep what was typed. Secrets are never passed to these commands.
 */
public class TerminowClaudeSetup {

    private static final String LOG_TAG = "TerminowClaudeSetup";
    private static final int OUTPUT_MAX = 256 * 1024;
    public static final long DETECT_TIMEOUT_MS = 120_000;
    public static final long VERIFY_TIMEOUT_MS = 120_000;

    /**
     * Prints what is known about Claude Code in the environment as `TERMINOW_*=` lines. Claude
     * Code may be installed in `~/.local/bin` by its native installer, which is not always on
     * the `PATH` of a non-interactive shell.
     */
    static final String DETECT_SCRIPT =
        "PATH=\"$HOME/.local/bin:$PATH\"; export PATH\n" +
        "echo \"TERMINOW_HOME=$HOME\"\n" +
        "echo \"TERMINOW_CONFIG_DIR=${CLAUDE_CONFIG_DIR:-}\"\n" +
        "c=$(command -v claude 2>/dev/null) || { echo TERMINOW_CLAUDE=; exit 0; }\n" +
        "echo \"TERMINOW_CLAUDE=$c\"\n" +
        "v=$(claude --version 2>&1 | head -n 1)\n" +
        "echo \"TERMINOW_VERSION=$v\"\n" +
        "h=$(claude mcp add --help 2>&1)\n" +
        "case \"$h\" in *--transport*) echo TERMINOW_HTTP=yes;; esac\n" +
        "case \"$h\" in *--scope*) echo TERMINOW_SCOPE=yes;; esac\n";

    /** `claude mcp get <name>` in the directory, `$1` is the directory, empty for home, `$2` the name. */
    static final String VERIFY_SCRIPT =
        "PATH=\"$HOME/.local/bin:$PATH\"; export PATH\n" +
        "if [ -n \"$1\" ]; then cd \"$1\" || { echo \"TermiNow: the project directory does not exist\"; exit 97; }; fi\n" +
        "exec claude mcp get \"$2\"\n";

    public static class ProcessResult {
        public int exitCode = -1;
        public String stdout = "";
        public String stderr = "";
        public boolean timedOut;
        @Nullable public String error;
    }

    public static class Detection {
        public boolean ran;
        @Nullable public String error;
        /** The home directory in the environment. */
        public String home = "";
        /** `$CLAUDE_CONFIG_DIR`, if set, where Claude keeps `.claude.json` instead of home. */
        public String configDir = "";
        /** The path of `claude`, empty if it is not installed. */
        public String claudePath = "";
        public String version = "";
        public boolean supportsHttp;
        public boolean supportsScope;

        public boolean isInstalled() {
            return !claudePath.isEmpty();
        }

        /** The directory of `.claude.json` in the environment. */
        @NonNull
        public String getUserConfigDir() {
            return !configDir.isEmpty() ? configDir : home;
        }
    }

    private final Context mContext;
    private final TerminowMcpTargets mTargets;

    public TerminowClaudeSetup(@NonNull Context context, @NonNull TerminowMcpTargets targets) {
        mContext = context;
        mTargets = targets;
    }

    /**
     * The command to run a shell script in an environment, with its arguments as separate
     * arguments of the shell, never as part of the script.
     */
    @NonNull
    static List<String> buildCommand(@NonNull File prefixDir, @NonNull String target, @NonNull String user,
                                     @NonNull String script, @NonNull String... args) {
        List<String> command = new ArrayList<>();
        if (TerminowMcpTargets.TARGET_TERMINOW.equals(target)) {
            command.add(new File(prefixDir, "bin/bash").getPath());
            command.add("-c");
        } else {
            if (!TerminowMcpTargets.isValidContainerName(target) || !TerminowMcpTargets.isValidUserName(user))
                throw new IllegalArgumentException("Invalid container or user");
            // proot-distro joins the command with shell quoting, so each argument stays one
            command.addAll(Arrays.asList(new File(prefixDir, "bin/proot-distro").getPath(), "login", target, "--user", user, "--", "/bin/sh", "-c"));
        }
        command.add(script);
        command.add("terminow-setup");
        command.addAll(Arrays.asList(args));
        return command;
    }

    @NonNull
    public Detection detect(@NonNull String target, @NonNull String user) {
        Detection detection = new Detection();
        ProcessResult result = run(buildCommand(mTargets.getPrefixDir(), target, user, DETECT_SCRIPT), DETECT_TIMEOUT_MS);
        if (result.error != null || result.timedOut) {
            detection.error = result.timedOut ? "Checking for Claude Code took too long" : result.error;
            return detection;
        }
        parseDetection(detection, result.stdout);
        if (!detection.ran)
            detection.error = "The environment did not start" + (result.stderr.trim().isEmpty() ? "" : ": " + lastLines(result.stderr, 4));
        return detection;
    }

    static void parseDetection(@NonNull Detection detection, @NonNull String stdout) {
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.startsWith("TERMINOW_HOME=")) {
                detection.ran = true;
                detection.home = line.substring("TERMINOW_HOME=".length());
            } else if (line.startsWith("TERMINOW_CONFIG_DIR=")) {
                detection.configDir = line.substring("TERMINOW_CONFIG_DIR=".length());
            } else if (line.startsWith("TERMINOW_CLAUDE=")) {
                detection.claudePath = line.substring("TERMINOW_CLAUDE=".length());
            } else if (line.startsWith("TERMINOW_VERSION=")) {
                detection.version = line.substring("TERMINOW_VERSION=".length());
            } else if (line.equals("TERMINOW_HTTP=yes")) {
                detection.supportsHttp = true;
            } else if (line.equals("TERMINOW_SCOPE=yes")) {
                detection.supportsScope = true;
            }
        }
    }

    /** Run `claude mcp get <name>`, in the project directory for a project scope. */
    @NonNull
    public ProcessResult verify(@NonNull String target, @NonNull String user, @NonNull String directory, @NonNull String name) {
        return run(buildCommand(mTargets.getPrefixDir(), target, user, VERIFY_SCRIPT, directory, name), VERIFY_TIMEOUT_MS);
    }

    /** Run a command with the environment of the terminal and capture its output. */
    @NonNull
    ProcessResult run(@NonNull List<String> command, long timeoutMs) {
        ProcessResult result = new ProcessResult();
        Process process;
        try {
            TermuxShellEnvironment shellEnvironment = new TermuxShellEnvironment();
            HashMap<String, String> environment = shellEnvironment.getEnvironment(mContext, false);
            TermuxShellUtils.setupSystemLinkerExecLdPreloadLib();
            String[] args = shellEnvironment.setupShellCommandArguments(command.get(0),
                command.subList(1, command.size()).toArray(new String[0]));
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.environment().clear();
            for (String entry : ShellEnvironmentUtils.convertEnvironmentToEnviron(environment)) {
                int equals = entry.indexOf('=');
                if (equals > 0) builder.environment().put(entry.substring(0, equals), entry.substring(equals + 1));
            }
            builder.directory(mTargets.getTerminowHome());
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start the setup process", e);
            result.error = "Failed to start: " + e.getMessage();
            return result;
        }

        try {
            // Nothing is ever read from the input, so a command that asks for input ends at once
            process.getOutputStream().close();
        } catch (IOException e) {
            // Ignore
        }
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread stdoutThread = drain(process.getInputStream(), stdout);
        Thread stderrThread = drain(process.getErrorStream(), stderr);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try {
                result.exitCode = process.exitValue();
                break;
            } catch (IllegalThreadStateException e) {
                if (System.currentTimeMillis() > deadline) {
                    result.timedOut = true;
                    process.destroy();
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    process.destroy();
                    Thread.currentThread().interrupt();
                    result.error = "Interrupted";
                    break;
                }
            }
        }
        try {
            stdoutThread.join(2000);
            stderrThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (stdout) {
            result.stdout = new String(stdout.toByteArray(), StandardCharsets.UTF_8);
        }
        synchronized (stderr) {
            result.stderr = new String(stderr.toByteArray(), StandardCharsets.UTF_8);
        }
        return result;
    }

    @NonNull
    private static Thread drain(@NonNull InputStream in, @NonNull ByteArrayOutputStream out) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            int read;
            try (InputStream input = in) {
                while ((read = input.read(buffer)) != -1) {
                    synchronized (out) {
                        if (out.size() < OUTPUT_MAX) out.write(buffer, 0, Math.min(read, OUTPUT_MAX - out.size()));
                    }
                }
            } catch (IOException e) {
                // The process ended
            }
        });
        thread.start();
        return thread;
    }

    @NonNull
    static String lastLines(@NonNull String text, int count) {
        String[] lines = text.trim().split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++)
            out.append(out.length() > 0 ? "\n" : "").append(lines[i]);
        return out.toString();
    }

    /**
     * The number of Claude Code processes of the app, in any environment. Claude Code rewrites
     * `~/.claude.json` while it runs, which could undo a change made at the same time.
     */
    public static int countRunningClaude() {
        int count = 0;
        File[] processes = new File("/proc").listFiles();
        if (processes == null) return 0;
        int self = android.os.Process.myPid();
        for (File process : processes) {
            String name = process.getName();
            if (!name.matches("[0-9]+") || name.equals(String.valueOf(self))) continue;
            String cmdline = readCmdline(new File(process, "cmdline"));
            if (isClaudeCmdline(cmdline)) count++;
        }
        return count;
    }

    static boolean isClaudeCmdline(@Nullable String cmdline) {
        if (cmdline == null || cmdline.isEmpty()) return false;
        String[] args = cmdline.split("\0");
        // The setup commands of TermiNow run `claude` too, but only while TermiNow waits for them
        for (String arg : args) if (arg.equals("terminow-setup")) return false;
        for (int i = 0; i < Math.min(args.length, 3); i++) {
            String arg = args[i];
            String base = arg.substring(arg.lastIndexOf('/') + 1);
            if (base.equals("claude") || arg.contains("@anthropic-ai/claude-code") || arg.contains("/claude/versions/"))
                return true;
        }
        return false;
    }

    @Nullable
    private static String readCmdline(@NonNull File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read = in.read(buffer);
            return read > 0 ? new String(buffer, 0, read, StandardCharsets.UTF_8) : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

}
