package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * The readiness of an SSH host for running a managed Claude on it (Mode B): can TermiNow connect
 * with the host's Vault credentials and a trusted host key, is a shell and the Claude CLI present,
 * and does the working folder exist. The checks are read-only — they never install or log in
 * automatically; the device UI turns the result into guided setup (trust the host key, or install
 * Claude) that the person confirms.
 *
 * <p>The command and the parsing are pure so they can be tested without a host; the connection is
 * run by {@link TerminowMcpBridgeManager}.</p>
 */
final class TerminowMcpPreflight {

    private TerminowMcpPreflight() { }

    /** How much preflight output to read; the checks print a few short lines. */
    static final int OUTPUT_CAP = 4096;

    static final class Result {
        boolean connected;
        boolean hostKeyTrusted = true;
        /** "host · keytype fingerprint" when the host key is not trusted yet; empty otherwise. */
        String hostKeyInfo = "";
        boolean shellOk;
        boolean claudeInstalled;
        boolean folderExists;
        String os = "";
        /** A connection or run error; empty when the checks actually ran. */
        String error = "";

        /** The host is ready to run a task now. */
        boolean ready() {
            return connected && hostKeyTrusted && shellOk && claudeInstalled && folderExists && error.isEmpty();
        }
    }

    /** The host key is unknown/untrusted, so nothing could be checked yet; the UI offers to trust it. */
    @NonNull
    static Result untrustedHostKey(@NonNull String host, @NonNull String keyInfo) {
        Result r = new Result();
        r.connected = false;
        r.hostKeyTrusted = false;
        r.hostKeyInfo = (host.isEmpty() ? "" : host + " · ") + keyInfo;
        return r;
    }

    @NonNull
    static Result error(@NonNull String message) {
        Result r = new Result();
        r.error = message.isEmpty() ? "The host could not be reached" : message;
        return r;
    }

    /**
     * A read-only shell command that reports the host's OS, whether a shell and the Claude CLI are
     * present, and whether the folder exists. The folder is passed as {@code $1} (shell-quoted), so
     * it is data and never parsed as shell.
     */
    @NonNull
    static String buildCommand(@NonNull String folder) {
        String script =
            "echo OS=$(uname -s 2>/dev/null); "
            + "command -v bash >/dev/null 2>&1 && echo SHELL=yes || echo SHELL=no; "
            + "PATH=\"$HOME/.local/bin:$PATH\"; command -v claude >/dev/null 2>&1 && echo CLAUDE=yes || echo CLAUDE=no; "
            + "[ -d \"$1\" ] && echo FOLDER=yes || echo FOLDER=no";
        return "bash -lc " + TerminowStore.shellQuote(script) + " preflight " + TerminowStore.shellQuote(folder);
    }

    /** Parse the lines printed by {@link #buildCommand}; a connected host always sets {@code connected}. */
    @NonNull
    static Result parse(@NonNull String output) {
        Result r = new Result();
        r.connected = true;
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("OS=")) r.os = line.substring(3).trim();
            else if (line.startsWith("SHELL=")) r.shellOk = line.substring(6).trim().equals("yes");
            else if (line.startsWith("CLAUDE=")) r.claudeInstalled = line.substring(7).trim().equals("yes");
            else if (line.startsWith("FOLDER=")) r.folderExists = line.substring(7).trim().equals("yes");
        }
        return r;
    }
}
