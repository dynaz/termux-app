package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Managing tmux sessions of an environment: list, rename, detach and end. tmux manages terminal
 * sessions; it is not an environment. Every session belongs to an environment, a user, a tmux
 * server (socket) and a name, and the commands run in that environment as that user, with its
 * own tmux binary. Sessions live as long as the tmux server: Android can end it, and a restart
 * of the phone does.
 */
public final class TerminowTmux {

    /**
     * $1 "local" or "guest", $2 the server name (empty for the default one), $3 the operation:
     * list, rename OLD NEW, kill NAME, detach NAME. In an environment only its own tmux is used.
     */
    static final String SCRIPT =
        "mode=\"$1\"; sock=\"$2\"; op=\"$3\"; shift 3\n" +
        "echo TERMINOW_CHECKED=yes\n" +
        "t=\n" +
        "if [ \"$mode\" = local ]; then t=$(command -v tmux 2>/dev/null)\n" +
        "else for d in ${TERMINOW_TMUX_DIRS:-/usr/local/bin /usr/bin /bin}; do if [ -x \"$d/tmux\" ]; then t=\"$d/tmux\"; break; fi; done; fi\n" +
        "if [ -z \"$t\" ]; then echo TERMINOW_TMUX=; exit 0; fi\n" +
        "echo \"TERMINOW_TMUX=$t\"\n" +
        "echo \"TERMINOW_SOCKET=${TMUX_TMPDIR:-/tmp}/tmux-$(id -u)/${sock:-default}\"\n" +
        "T() { if [ -n \"$sock\" ]; then \"$t\" -L \"$sock\" \"$@\"; else \"$t\" \"$@\"; fi; }\n" +
        "case \"$op\" in\n" +
        "  list) T list-sessions -F 'TERMINOW_SESSION=#{session_name}|#{session_attached}|#{session_windows}|#{session_created}' 2>/dev/null ;;\n" +
        "  rename) T rename-session -t \"=$1\" \"$2\" && echo TERMINOW_DONE=yes ;;\n" +
        "  kill) T kill-session -t \"=$1\" && echo TERMINOW_DONE=yes ;;\n" +
        "  detach) T detach-client -s \"=$1\" && echo TERMINOW_DONE=yes ;;\n" +
        "esac\n" +
        "exit 0\n";

    public static class Session {
        public final String name;
        public final int attached;
        public final int windows;
        /** Seconds since 1970, 0 if not known. */
        public final long created;

        Session(@NonNull String name, int attached, int windows, long created) {
            this.name = name;
            this.attached = attached;
            this.windows = windows;
            this.created = created;
        }
    }

    /** What a list said, for one environment, user and server. */
    public static class State {
        public boolean checked;
        @Nullable public String error;
        /** The tmux binary in the environment, empty if there is none. */
        public String binary = "";
        public String socket = "";
        public boolean done;
        public final List<Session> sessions = new ArrayList<>();

        public boolean isInstalled() {
            return !binary.isEmpty();
        }
    }

    private TerminowTmux() {}

    @NonNull
    public static State parse(@NonNull String stdout) {
        State state = new State();
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.equals("TERMINOW_CHECKED=yes")) state.checked = true;
            else if (line.startsWith("TERMINOW_TMUX=")) state.binary = line.substring("TERMINOW_TMUX=".length());
            else if (line.startsWith("TERMINOW_SOCKET=")) state.socket = line.substring("TERMINOW_SOCKET=".length());
            else if (line.equals("TERMINOW_DONE=yes")) state.done = true;
            else if (line.startsWith("TERMINOW_SESSION=")) {
                String[] parts = line.substring("TERMINOW_SESSION=".length()).split("\\|", -1);
                if (parts.length < 3 || parts[0].isEmpty()) continue;
                state.sessions.add(new Session(parts[0], toInt(parts[1]), toInt(parts[2]), parts.length > 3 ? toLong(parts[3]) : 0));
            }
        }
        return state;
    }

    /** The key of an environment and user, for the states and the recent sessions. */
    @NonNull
    public static String key(@NonNull String environment, @NonNull String user) {
        return environment + "|" + user;
    }

    private static int toInt(@NonNull String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long toLong(@NonNull String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

}
