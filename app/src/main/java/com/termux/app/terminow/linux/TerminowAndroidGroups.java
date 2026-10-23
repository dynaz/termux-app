package com.termux.app.terminow.linux;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The supplementary groups of Android, which every process of a Linux environment has, as names
 * in its /etc/group. proot-distro writes them once, at the install, for the app as it was then.
 * A rootfs that the app later runs with other groups, like after it was installed again with
 * another app ID or a backup was restored into it, has groups without a name, and `groups`, which
 * the /etc/bash.bashrc of Ubuntu runs at every start, warns about each of them.
 * <p>
 * Before a start, the groups of the app that /etc/group does not have are added, named as
 * proot-distro names them (aid_ and the Android name), after the entries that are there. Nothing
 * is changed while every group has an entry, so a start reads two small files and is done.
 */
public final class TerminowAndroidGroups {

    private static final Pattern ID_GROUP = Pattern.compile("(\\d+)(?:\\(([^)]*)\\))?");
    private static final Pattern GROUP_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]*");

    private TerminowAndroidGroups() {
    }

    /** The supplementary groups in /proc/self/status, in their order. */
    @NonNull
    static List<Integer> supplementaryGroups(@NonNull String status) {
        List<Integer> gids = new ArrayList<>();
        for (String line : status.split("\n")) {
            if (!line.startsWith("Groups:")) continue;
            for (String word : line.substring("Groups:".length()).trim().split("\\s+")) {
                try {
                    if (!word.isEmpty()) gids.add(Integer.parseInt(word));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return gids;
    }

    /** The groups that have an entry in an /etc/group, and the names that are taken. */
    @NonNull
    static Set<String> namesAndIds(@NonNull String group) {
        Set<String> taken = new HashSet<>();
        for (String line : group.split("\n")) {
            String[] fields = line.split(":", -1);
            if (fields.length < 3 || fields[0].isEmpty()) continue;
            taken.add(fields[0]);
            taken.add("#" + fields[2].trim());
        }
        return taken;
    }

    /** The names of the entries of an /etc/group or /etc/gshadow. */
    @NonNull
    static Set<String> names(@NonNull String text) {
        Set<String> names = new HashSet<>();
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) names.add(line.substring(0, colon));
        }
        return names;
    }

    /** The groups that an /etc/group has no entry for. */
    @NonNull
    static List<Integer> missing(@NonNull String group, @NonNull List<Integer> gids) {
        Set<String> taken = namesAndIds(group);
        List<Integer> missing = new ArrayList<>();
        for (int gid : gids) if (!taken.contains("#" + gid) && !missing.contains(gid)) missing.add(gid);
        return missing;
    }

    /**
     * The Android names of the groups in the output of `id` of Android, like
     * "uid=10519(u0_a519) gid=10519(u0_a519) groups=10519(u0_a519),3003(inet)", and of the user
     * under "#user".
     */
    @NonNull
    static Map<Integer, String> androidNames(@NonNull String id) {
        Map<Integer, String> names = new HashMap<>();
        Matcher user = Pattern.compile("uid=\\d+\\(([^)]*)\\)").matcher(id);
        if (user.find()) names.put(-1, user.group(1));
        int start = id.indexOf("groups=");
        if (start < 0) return names;
        String groups = id.substring(start + "groups=".length()).split("\\s", 2)[0];
        for (String part : groups.split(",")) {
            Matcher m = ID_GROUP.matcher(part.trim());
            if (!m.matches() || m.group(2) == null) continue;
            try {
                names.put(Integer.parseInt(m.group(1)), m.group(2));
            } catch (NumberFormatException ignored) {
            }
        }
        return names;
    }

    /**
     * The lines that give the missing groups a name, for /etc/group or, with {@code gshadow},
     * for /etc/gshadow. A name that Android does not give, or that is taken already, is the
     * number of the group after aid_.
     */
    @NonNull
    static String entries(@NonNull String group, @NonNull List<Integer> missing, @NonNull Map<Integer, String> androidNames,
                          boolean gshadow) {
        Set<String> taken = namesAndIds(group);
        String user = androidNames.get(-1);
        String members = "root" + (user != null && GROUP_NAME.matcher(user).matches() ? ",aid_" + user : "");
        StringBuilder lines = new StringBuilder();
        for (int gid : missing) {
            String android = androidNames.get(gid);
            String name = android != null && GROUP_NAME.matcher(android).matches() ? "aid_" + android : "aid_" + gid;
            if (taken.contains(name)) name = "aid_" + gid;
            if (taken.contains(name)) continue;
            taken.add(name);
            lines.append(name).append(gshadow ? ":*::" : ":x:" + gid + ":").append(members).append('\n');
        }
        return lines.toString();
    }

    /**
     * Gives each supplementary group of the app a name in the /etc/group of the rootfs, and in its
     * /etc/gshadow when it has one. Files that are links or not regular are left alone, like
     * proot-distro does. Errors are never thrown: the start goes on as before.
     *
     * @return The number of groups that were added.
     */
    public static int ensure(@NonNull File rootfs) {
        try {
            File etc = new File(rootfs, "etc");
            if (!OsConstants.S_ISDIR(Os.lstat(etc.getPath()).st_mode)) return 0;
            File groupFile = new File(etc, "group");
            String group = read(groupFile);
            if (group == null) return 0;
            List<Integer> missing = missing(group, supplementaryGroups(readProcStatus()));
            if (missing.isEmpty()) return 0;
            String id = run("/system/bin/id");
            Map<Integer, String> names = androidNames(id != null ? id : "");
            String groupLines = entries(group, missing, names, false);
            if (groupLines.isEmpty()) return 0;
            if (!append(groupFile, group, groupLines)) return 0;
            File gshadowFile = new File(etc, "gshadow");
            String gshadow = read(gshadowFile);
            if (gshadow != null) {
                // Only the names that gshadow does not have yet, so a second start adds none
                StringBuilder shadowLines = new StringBuilder();
                Set<String> taken = names(gshadow);
                for (String line : entries(group, missing, names, true).split("\n"))
                    if (!line.isEmpty() && !taken.contains(line.substring(0, line.indexOf(':')))) shadowLines.append(line).append('\n');
                if (shadowLines.length() > 0) append(gshadowFile, gshadow, shadowLines.toString());
            }
            return groupLines.split("\n").length;
        } catch (ErrnoException | IOException | RuntimeException e) {
            return 0;
        }
    }

    @NonNull
    private static String readProcStatus() throws IOException {
        try (InputStream in = new FileInputStream("/proc/self/status")) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    /** The text of a regular file that is not a link, {@code null} if there is none. */
    @Nullable
    private static String read(@NonNull File file) throws IOException {
        FileDescriptor fd = open(file, OsConstants.O_RDONLY);
        if (fd == null) return null;
        // The stream closes the descriptor
        try (InputStream in = new FileInputStream(fd)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        }
    }

    /** Adds the lines at the end, on a line of their own. */
    private static boolean append(@NonNull File file, @NonNull String text, @NonNull String lines) throws IOException {
        FileDescriptor fd = open(file, OsConstants.O_WRONLY | OsConstants.O_APPEND);
        if (fd == null) return false;
        try (FileOutputStream out = new FileOutputStream(fd)) {
            out.write(((text.isEmpty() || text.endsWith("\n") ? "" : "\n") + lines).getBytes(StandardCharsets.UTF_8));
            return true;
        }
    }

    /** Opens without following a link, and only a regular file. */
    @Nullable
    private static FileDescriptor open(@NonNull File file, int flags) {
        FileDescriptor fd;
        try {
            fd = Os.open(file.getPath(), flags | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0);
        } catch (ErrnoException e) {
            return null;
        }
        try {
            if (OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return fd;
        } catch (ErrnoException ignored) {
        }
        close(fd);
        return null;
    }

    private static void close(@NonNull FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (ErrnoException ignored) {
        }
    }

    /** The output of a program of Android, {@code null} if it did not run within a few seconds. */
    @Nullable
    private static String run(@NonNull String program) {
        try {
            Process process = new ProcessBuilder(program).redirectErrorStream(true).start();
            process.getOutputStream().close();
            byte[] out = readAll(process.getInputStream());
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy();
                return null;
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    @NonNull
    private static byte[] readAll(@NonNull InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
