package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Turns a file path selected in a terminal into something the app can open, using only what is
 * known about the tab's environment. A path is resolved to a real file on the device for a local
 * environment (TermiNow, or a proot Linux distribution, through its rootfs and binds), or kept as
 * a remote path for a managed SSH host. A relative path needs the working directory, and a {@code
 * ~} needs the home; without them, or without knowing the environment, it is left unresolved and
 * the reason is given. A remote {@code /root/...} is never taken for a local file.
 */
public final class TerminowPathResolver {

    public enum Env { TERMUX, PROOT, SSH, UNKNOWN }

    /** A host folder bound into a proot guest, so a guest path under it maps back to the host. */
    public static final class Bind {
        public final String hostPath;
        public final String guestPath;

        public Bind(@NonNull String hostPath, @NonNull String guestPath) {
            this.hostPath = trimSlash(hostPath);
            this.guestPath = trimSlash(guestPath);
        }
    }

    /** What is known about the tab, filled by the app from the session and its environment. */
    public static final class Target {
        public Env env = Env.UNKNOWN;
        /** The rootfs of a proot distribution on the host, for {@link Env#PROOT}. */
        public String rootfs = "";
        /** The folders bound into the proot guest, for {@link Env#PROOT}. */
        public final List<Bind> binds = new ArrayList<>();
        /** The working directory in the environment, absolute, empty if not known. */
        public String cwd = "";
        /** The home directory in the environment, absolute, empty if not known. */
        public String home = "";
    }

    public enum Kind { LOCAL_FILE, REMOTE_SSH, UNRESOLVED }

    public static final class Resolved {
        public final Kind kind;
        /** A real path on the device, for {@link Kind#LOCAL_FILE}. */
        public final String localPath;
        /** The absolute path on the host, for {@link Kind#REMOTE_SSH}. */
        public final String remotePath;
        /** Why it could not be resolved, for {@link Kind#UNRESOLVED}. */
        public final String reason;

        private Resolved(Kind kind, @NonNull String localPath, @NonNull String remotePath, @NonNull String reason) {
            this.kind = kind;
            this.localPath = localPath;
            this.remotePath = remotePath;
            this.reason = reason;
        }

        static Resolved local(@NonNull String path) {
            return new Resolved(Kind.LOCAL_FILE, path, "", "");
        }

        static Resolved remote(@NonNull String path) {
            return new Resolved(Kind.REMOTE_SSH, "", path, "");
        }

        static Resolved unresolved(@NonNull String reason) {
            return new Resolved(Kind.UNRESOLVED, "", "", reason);
        }
    }

    private TerminowPathResolver() {}

    @NonNull
    public static Resolved resolve(@NonNull Target target, @NonNull String pathText) {
        String path = pathText.trim();
        if (path.isEmpty()) return Resolved.unresolved("No path was selected");
        if (target.env == Env.UNKNOWN) return Resolved.unresolved("The environment of this terminal is not known, so the path can not be resolved");

        // ~ needs the home of the environment
        if (path.equals("~") || path.startsWith("~/")) {
            if (target.home.isEmpty()) return Resolved.unresolved("The home directory of this terminal is not known");
            path = target.home + (path.length() > 1 ? path.substring(1) : "");
        }
        // A relative path needs the working directory
        if (!path.startsWith("/")) {
            if (target.cwd.isEmpty()) return Resolved.unresolved("The working directory of this terminal is not known, so a relative path can not be resolved");
            path = trimSlash(target.cwd) + "/" + path;
        }
        String absolute = normalize(path);

        switch (target.env) {
            case TERMUX:
                // A TermiNow path is already an Android path
                return Resolved.local(absolute);
            case SSH:
                // Never resolved to a local file
                return Resolved.remote(absolute);
            case PROOT:
                return Resolved.local(toHost(target, absolute));
            default:
                return Resolved.unresolved("The environment of this terminal is not known");
        }
    }

    /** The host path of a guest path: through a bind if one covers it, else under the rootfs. */
    @NonNull
    private static String toHost(@NonNull Target target, @NonNull String guestPath) {
        // The most specific bind wins, so /sdcard inside a bind is not taken under the rootfs
        Bind best = null;
        for (Bind bind : target.binds)
            if ((guestPath.equals(bind.guestPath) || guestPath.startsWith(bind.guestPath + "/"))
                && (best == null || bind.guestPath.length() > best.guestPath.length()))
                best = bind;
        if (best != null) return best.hostPath + guestPath.substring(best.guestPath.length());
        return trimSlash(target.rootfs) + guestPath;
    }

    /** Resolve {@code .} and {@code ..} in an absolute path, without touching the filesystem. */
    @NonNull
    static String normalize(@NonNull String absolute) {
        Deque<String> parts = new ArrayDeque<>();
        for (String part : absolute.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) out.append('/').append(part);
        return out.length() == 0 ? "/" : out.toString();
    }

    @NonNull
    private static String trimSlash(@NonNull String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }
}
