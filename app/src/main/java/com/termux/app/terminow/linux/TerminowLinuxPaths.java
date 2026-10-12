package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Paths of a Linux environment mapped to the files of the app, the way the environment itself
 * sees them: the rootfs is "/", a symbolic link with an absolute target points into the rootfs
 * (like chroot), ".." never leaves "/", and the folders that proot binds in (shared storage,
 * folders the user shared) are their host folders. /proc, /sys and /dev only exist while the
 * environment runs, and are reported as virtual instead of showing the folders of the rootfs.
 */
public final class TerminowLinuxPaths {

    /** At most this many links are followed for one path, like Linux. */
    static final int MAX_LINKS = 40;

    /** The folders that exist only while the environment runs. /dev/shm is bound to a folder. */
    public static final List<String> VIRTUAL = Arrays.asList("/proc", "/sys", "/dev");

    /** A folder of the host that is bound into the environment. */
    public static class Mount {
        public final String guest;
        public final File host;
        /** Shared by the user, not one of the bindings that every environment has. */
        public final boolean userShared;

        public Mount(@NonNull String guest, @NonNull File host, boolean userShared) {
            this.guest = normalize(guest);
            this.host = host;
            this.userShared = userShared;
        }
    }

    /** What the resolver needs from the file system, so that it can be tested on a computer. */
    public interface Links {
        boolean isLink(@NonNull File file);

        @NonNull String readLink(@NonNull File file) throws IOException;
    }

    public enum Kind { ROOTFS, MOUNT, VIRTUAL }

    public static class Resolved {
        /** The path in the environment after the links, like "/usr/lib/os-release". */
        public final String path;
        /** The file of the app, {@code null} for {@link Kind#VIRTUAL}. */
        @Nullable public final File file;
        public final Kind kind;
        @Nullable public final Mount mount;

        Resolved(@NonNull String path, @Nullable File file, @NonNull Kind kind, @Nullable Mount mount) {
            this.path = path;
            this.file = file;
            this.kind = kind;
            this.mount = mount;
        }
    }

    private TerminowLinuxPaths() {}

    /** "/a/./b//c/" → "/a/b/c", ".." is kept for the resolver to apply. */
    @NonNull
    public static String normalize(@NonNull String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            out.append('/').append(part);
        }
        return out.length() == 0 ? "/" : out.toString();
    }

    @NonNull
    public static String parent(@NonNull String path) {
        String normalized = normalize(path);
        int slash = normalized.lastIndexOf('/');
        return slash <= 0 ? "/" : normalized.substring(0, slash);
    }

    @NonNull
    public static String child(@NonNull String directory, @NonNull String name) {
        return directory.equals("/") ? "/" + name : directory + "/" + name;
    }

    public static boolean isWithin(@NonNull String path, @NonNull String folder) {
        return folder.equals("/") || path.equals(folder) || path.startsWith(folder + "/");
    }

    @Nullable
    static String virtualRoot(@NonNull String path) {
        for (String root : VIRTUAL) if (isWithin(path, root)) return root;
        return null;
    }

    /** The mount that a path is in, the deepest one if they are nested. */
    @Nullable
    static Mount mountOf(@NonNull String path, @NonNull List<Mount> mounts) {
        Mount best = null;
        for (Mount mount : mounts)
            if (isWithin(path, mount.guest) && (best == null || mount.guest.length() > best.guest.length())) best = mount;
        return best;
    }

    /**
     * Resolve a path of the environment.
     *
     * @param followLast Whether a link at the end is followed, false to act on the link itself,
     *                   like for delete or rename.
     */
    @NonNull
    public static Resolved resolve(@NonNull File rootfs, @NonNull List<Mount> mounts, @NonNull String path,
                                   boolean followLast, @NonNull Links links) throws IOException {
        if (!path.startsWith("/") || path.contains("\0")) throw new IOException("Not an absolute path: " + path);
        Deque<String> todo = new ArrayDeque<>(parts(path));
        List<String> done = new ArrayList<>();
        int hops = 0;
        while (!todo.isEmpty()) {
            String part = todo.removeFirst();
            if (part.equals("..")) {
                if (!done.isEmpty()) done.remove(done.size() - 1);
                continue;
            }
            done.add(part);
            String current = join(done);
            if (virtualRoot(current) != null && mountOf(current, mounts) == null) {
                // The rest is inside a virtual file system, which is not followed further
                StringBuilder rest = new StringBuilder(current);
                for (String remaining : todo) rest.append('/').append(remaining);
                return new Resolved(normalize(rest.toString()), null, Kind.VIRTUAL, null);
            }
            Mount mount = mountOf(current, mounts);
            File file = mount != null ? hostFile(mount, current) : new File(rootfs, current.substring(1));
            boolean last = todo.isEmpty();
            if ((!last || followLast) && links.isLink(file)) {
                if (++hops > MAX_LINKS) throw new IOException("Too many levels of symbolic links: " + path);
                String target = links.readLink(file);
                if (target.isEmpty()) throw new IOException("Empty symbolic link: " + current);
                done.remove(done.size() - 1);
                List<String> targetParts = parts(target);
                if (target.startsWith("/")) done.clear();
                // The target is resolved in the environment, before the rest of the path
                for (int i = targetParts.size() - 1; i >= 0; i--) todo.addFirst(targetParts.get(i));
            }
        }
        String result = join(done);
        Mount mount = mountOf(result, mounts);
        if (mount != null) return new Resolved(result, hostFile(mount, result), Kind.MOUNT, mount);
        return new Resolved(result, result.equals("/") ? rootfs : new File(rootfs, result.substring(1)), Kind.ROOTFS, null);
    }

    @NonNull
    private static File hostFile(@NonNull Mount mount, @NonNull String guestPath) {
        String rest = guestPath.length() > mount.guest.length() ? guestPath.substring(mount.guest.length() + (mount.guest.equals("/") ? 0 : 1)) : "";
        return rest.isEmpty() ? mount.host : new File(mount.host, rest);
    }

    @NonNull
    private static List<String> parts(@NonNull String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) if (!part.isEmpty() && !part.equals(".")) parts.add(part);
        return parts;
    }

    @NonNull
    private static String join(@NonNull List<String> parts) {
        if (parts.isEmpty()) return "/";
        StringBuilder out = new StringBuilder();
        for (String part : parts) out.append('/').append(part);
        return out.toString();
    }

}
