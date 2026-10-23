package com.termux.app.terminow;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.mcp.McpException;
import com.termux.app.terminow.mcp.McpStdioTransport;
import com.termux.app.terminow.mcp.McpTransport;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a local MCP server starts: in TermiNow with the environment of the terminal, or in a
 * proot-distro container as one of its users. The command and its arguments stay a list of
 * arguments, never a line for a shell to parse.
 */
final class TerminowMcpStdio {

    /** A command line, the variables to add to the environment, and the directory to run in. */
    static final class Launch {
        final List<String> command;
        final Map<String, String> env;
        @Nullable final String workingDir;

        Launch(@NonNull List<String> command, @NonNull Map<String, String> env, @Nullable String workingDir) {
            this.command = Collections.unmodifiableList(command);
            this.env = Collections.unmodifiableMap(env);
            this.workingDir = workingDir;
        }
    }

    private TerminowMcpStdio() {}

    /**
     * In a container, the variables go to `env` inside it, since proot-distro does not pass the
     * environment of the app on.
     */
    @NonNull
    static Launch plan(@NonNull File prefixDir, @NonNull TerminowMcpProfile profile) {
        List<String> command = new ArrayList<>();
        String workingDir = profile.workingDir.isEmpty() ? null : profile.workingDir;
        if (TerminowMcpTargets.TARGET_TERMINOW.equals(profile.target)) {
            command.add(profile.command.trim());
            command.addAll(profile.args);
            return new Launch(command, new LinkedHashMap<>(profile.env), workingDir);
        }
        if (!TerminowMcpTargets.isValidContainerName(profile.target) || !TerminowMcpTargets.isValidUserName(profile.containerUser))
            throw new IllegalArgumentException("Invalid container or user");
        command.addAll(Arrays.asList(new File(prefixDir, "bin/proot-distro").getPath(), "login", profile.target, "--user", profile.containerUser));
        if (workingDir != null) command.addAll(Arrays.asList("--work-dir", workingDir));
        command.add("--");
        command.add("/usr/bin/env");
        for (Map.Entry<String, String> variable : profile.env.entrySet()) command.add(variable.getKey() + "=" + variable.getValue());
        command.add(profile.command.trim());
        command.addAll(profile.args);
        return new Launch(command, new LinkedHashMap<>(), null);
    }

    /** New transports for the profile as it is now. Later edits need a new factory. */
    @NonNull
    static McpTransport.Factory transportFactory(@NonNull Context context, @NonNull TerminowMcpTargets targets,
                                                 @NonNull TerminowMcpProfile profile) {
        Launch launch;
        try {
            launch = plan(targets.getPrefixDir(), profile);
        } catch (IllegalArgumentException e) {
            return () -> {
                throw new McpException(McpException.Kind.PROCESS_START_FAILED, String.valueOf(e.getMessage()));
            };
        }
        File home = TerminowClaudeSetup.workingDirectoryFor(targets.getTerminowHome(),
            new File(com.termux.shared.termux.TermuxConstants.TERMUX_FILES_DIR_PATH));
        McpStdioTransport.ProcessStarter starter = (command, env, dir) ->
            TerminowClaudeSetup.startInTerminal(context, command, env, dir != null ? new File(dir) : home);
        return () -> new McpStdioTransport(starter, launch.command, launch.env, launch.workingDir);
    }

}
