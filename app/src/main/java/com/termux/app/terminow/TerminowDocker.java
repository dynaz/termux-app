package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Docker management over an existing SSH connection: the commands that are run on the remote host and
 * the parser for their output. Every container reference is single-quote escaped before it goes into a
 * command, so a name with spaces or shell metacharacters cannot inject anything. Nothing here opens a
 * Docker TCP socket or changes the host; it only runs {@code docker} over the SSH account the user
 * already configured. The command builders and parser are unit-tested; running them needs a real host.
 */
public final class TerminowDocker {

    private TerminowDocker() {}

    /** A container from {@code docker ps}. */
    public static final class Container {
        public String id = "";
        public String name = "";
        public String image = "";
        public String status = "";

        public boolean running() {
            return status.startsWith("Up");
        }
    }

    /** Single-quote escape an argument for a POSIX shell: wrap in '...' and escape embedded quotes. */
    @NonNull
    public static String sqEscape(@NonNull String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /** A plausible container id or name (safe charset) — defence in depth on top of escaping. */
    public static boolean isValidRef(@NonNull String ref) {
        return ref.matches("[A-Za-z0-9][A-Za-z0-9_.-]*");
    }

    /** Check Docker is present and the account may use it. */
    @NonNull
    public static String versionCommand() {
        return "docker version --format '{{.Server.Version}}'";
    }

    /** List containers; {@code all} includes stopped ones. Tab-separated for a stable parse. */
    @NonNull
    public static String psCommand(boolean all) {
        return "docker ps" + (all ? " -a" : "")
            + " --no-trunc --format '{{.ID}}\\t{{.Names}}\\t{{.Image}}\\t{{.Status}}'";
    }

    @NonNull
    public static List<Container> parsePs(@NonNull String output) {
        List<Container> out = new ArrayList<>();
        for (String raw : output.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            if (p.length < 4) continue;
            Container c = new Container();
            c.id = p[0].trim();
            c.name = p[1].trim();
            c.image = p[2].trim();
            c.status = p[3].trim();
            if (!c.id.isEmpty()) out.add(c);
        }
        return out;
    }

    /** A power action: start, stop or restart, on an escaped reference. */
    @NonNull
    public static String actionCommand(@NonNull String action, @NonNull String ref) {
        return "docker " + action + " " + sqEscape(ref);
    }

    @NonNull
    public static String logsCommand(@NonNull String ref, int tail) {
        return "docker logs --tail " + Math.max(1, tail) + " " + sqEscape(ref);
    }

    /** An interactive shell in a container, trying the given shell (the caller falls back to /bin/sh). */
    @NonNull
    public static String execCommand(@NonNull String ref, @NonNull String shell) {
        return "docker exec -it " + sqEscape(ref) + " " + sqEscape(shell);
    }

    /** List Docker Compose projects, if Compose is available (quietly does nothing if not). */
    @NonNull
    public static String composeLsCommand() {
        return "docker compose ls 2>/dev/null";
    }
}
