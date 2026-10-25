package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * Shell integration that makes a shell report its working directory with OSC 7, so the folder bar can
 * follow {@code cd} without ever injecting a command into the running terminal. The snippet is added to
 * the shell's rc file once, between markers, so installing it again changes nothing (idempotent). It is
 * an explicit, opt-in action; it is never written to a remote shell silently.
 *
 * <p>The snippet only prints an OSC 7 escape on each prompt. It changes no other behaviour and reads no
 * secrets. For a remote host the caller writes the rc file over a separate SSH exec, not by typing into
 * the interactive terminal.
 */
public final class TerminowShellIntegration {

    public enum Shell { BASH, ZSH, FISH }

    private static final String BEGIN = "# >>> TermiNow folder tracking (OSC 7) >>>";
    private static final String END = "# <<< TermiNow folder tracking (OSC 7) <<<";

    private TerminowShellIntegration() {}

    /** The rc file of the shell, relative to the home directory (no leading {@code ~/}). */
    @NonNull
    public static String rcFile(@NonNull Shell shell) {
        switch (shell) {
            case ZSH: return ".zshrc";
            case FISH: return ".config/fish/config.fish";
            case BASH:
            default: return ".bashrc";
        }
    }

    /** The shell from a login shell path or name like {@code /bin/bash}, {@code zsh}, {@code fish}. */
    @NonNull
    public static Shell shellOf(String shellPathOrName) {
        String s = shellPathOrName == null ? "" : shellPathOrName;
        int slash = s.lastIndexOf('/');
        String name = (slash >= 0 ? s.substring(slash + 1) : s).trim().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("zsh")) return Shell.ZSH;
        if (name.contains("fish")) return Shell.FISH;
        return Shell.BASH;
    }

    /** The emitter body (no markers) for the shell. */
    @NonNull
    private static String body(@NonNull Shell shell) {
        switch (shell) {
            case ZSH:
                return "__terminow_osc7() { printf '\\033]7;file://%s%s\\a' \"${HOST:-}\" \"$PWD\"; }\n"
                    + "typeset -ag precmd_functions\n"
                    + "(( ${precmd_functions[(Ie)__terminow_osc7]} )) || precmd_functions+=(__terminow_osc7)";
            case FISH:
                return "function __terminow_osc7 --on-variable PWD\n"
                    + "    printf '\\033]7;file://%s%s\\a' (hostname) \"$PWD\"\n"
                    + "end\n"
                    + "__terminow_osc7";
            case BASH:
            default:
                return "__terminow_osc7() { printf '\\033]7;file://%s%s\\a' \"${HOSTNAME:-}\" \"$PWD\"; }\n"
                    + "case \";${PROMPT_COMMAND};\" in *\";__terminow_osc7;\"*) ;; *) PROMPT_COMMAND=\"__terminow_osc7;${PROMPT_COMMAND}\";; esac";
        }
    }

    /** The full block, between markers, that is written to the rc file. */
    @NonNull
    public static String block(@NonNull Shell shell) {
        return BEGIN + "\n" + body(shell) + "\n" + END + "\n";
    }

    /** Whether the integration block is already present in the rc content. */
    public static boolean isInstalled(String rcContent) {
        return rcContent != null && rcContent.contains(BEGIN);
    }

    /**
     * The rc content with the integration installed exactly once. If it is already present the content
     * is returned unchanged, so applying this repeatedly is a no-op (idempotent).
     */
    @NonNull
    public static String ensureInstalled(@NonNull Shell shell, String rcContent) {
        String content = rcContent == null ? "" : rcContent;
        if (isInstalled(content)) return content;
        String prefix = content.isEmpty() || content.endsWith("\n") ? content : content + "\n";
        return prefix + block(shell);
    }
}
