package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Backing up a Linux environment to a saved host, as commands for the shell of TermiNow.
 *
 * The connection is always the ssh of TermiNow with the options of the host, so its key,
 * passphrase, port, jump host and known hosts are the ones TermiNow uses: inside the
 * environment too, where its ssh is reachable at the same path and keeps the home of TermiNow.
 *
 * - Folders: rsync in the environment sends only what changed since the last time.
 * - Whole system: tar in the environment streams one archive to the host, which Add
 *   environment → Import restores.
 */
public final class TerminowBackup {

    public static final String MODE_FOLDERS = "folders";
    public static final String MODE_SYSTEM = "system";

    /** What is not part of the system: the folders of Android that proot-distro binds, and what is only there while it runs. */
    static final List<String> SYSTEM_EXCLUDES = Arrays.asList("./proc", "./sys", "./dev", "./tmp/*", "./run/*",
        "./data", "./sdcard", "./storage", "./mnt/sdcard", "./apex", "./system", "./system_ext", "./vendor", "./product",
        "./odm", "./linkerconfig", "./var/cache/apt/archives/*.deb");

    private static final Pattern REMOTE_DIR = Pattern.compile("(~/|/)?[A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*/?");
    private static final Pattern LOCAL_FOLDER = Pattern.compile("/([A-Za-z0-9._@+-]+(/[A-Za-z0-9._@+-]+)*)?/?");

    private TerminowBackup() {}

    /** A folder on the host: relative to its home, under ~/ or absolute, without spaces, "..", or a leading "-". */
    public static boolean isValidRemoteDir(@NonNull String dir) {
        return REMOTE_DIR.matcher(dir).matches() && !hasDotDot(dir) && !dir.startsWith("-");
    }

    /** A folder of the environment to back up: absolute, without spaces or "..". */
    public static boolean isValidFolder(@NonNull String folder) {
        return LOCAL_FOLDER.matcher(folder).matches() && !hasDotDot(folder);
    }

    private static boolean hasDotDot(@NonNull String path) {
        for (String part : path.split("/")) if (part.equals("..") || part.equals(".")) return true;
        return false;
    }

    /** The folders of a list like "/root /home", {@code null} if one of them is not valid. */
    @androidx.annotation.Nullable
    public static List<String> parseFolders(@NonNull String text) {
        List<String> folders = new ArrayList<>();
        for (String folder : text.trim().split("\\s+")) {
            if (folder.isEmpty()) continue;
            if (!isValidFolder(folder)) return null;
            folders.add(folder);
        }
        return folders.isEmpty() ? null : folders;
    }

    /**
     * Sync folders of the environment to {@code dir} on the host with rsync, which keeps their
     * full paths, like dir/root/... and dir/home/...; nothing on the host is deleted.
     *
     * @param prefix The prefix of TermiNow, where its proot-distro, ssh and bash are.
     * @param home The home of TermiNow, which the ssh options and their ProxyCommand refer to with ~.
     * @param transport {@link TerminowStore.Host#getTransport}: the ssh options, quoted for bash, and user@host.
     * @param installRsync Whether apt installs rsync in the environment when it is not there, which the user agreed to.
     */
    @NonNull
    public static String foldersCommand(@NonNull String prefix, @NonNull String home, @NonNull String alias,
                                        @NonNull String[] transport, @NonNull String dir, @NonNull List<String> folders,
                                        boolean installRsync) {
        String ssh = prefix + "/bin/ssh " + withTermiNowPrograms(transport[0], prefix);
        String install = installRsync ?
            "command -v rsync >/dev/null 2>&1 || { echo \"TermiNow: installing rsync\"; apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y rsync; } || exit 1; " : "";
        String script = install +
            "command -v rsync >/dev/null 2>&1 || { echo \"TermiNow: rsync is not installed in this environment\"; exit 127; }; " +
            "[ -x \"" + prefix + "/bin/ssh\" ] || { echo \"TermiNow: the ssh of TermiNow is not reachable in this environment\"; exit 127; }; " +
            // The ProxyCommand of a jump host runs in a shell, whose ~ must be the home of TermiNow
            "HOME=\"$1\"; export HOME; shift; " +
            "exec rsync -aH --relative --human-readable --info=progress2,stats1 -e \"$0\" --rsync-path=\"mkdir -p " + dir + " && rsync\" \"$@\"";
        List<String> words = new ArrayList<>(Arrays.asList(prefix + "/bin/proot-distro", "login", alias, "--user", "root", "--",
            "/bin/sh", "-c", script, ssh, home));
        words.addAll(folders);
        words.add(transport[1] + ":" + (dir.endsWith("/") ? dir : dir + "/"));
        return join(words) + result();
    }

    /**
     * Stream the whole environment as one archive to {@code dir/fileName} on the host. It is
     * written as .part and only renamed once gzip on the host reads it whole; a failed tar adds
     * a line after the archive, which gzip does not read as one, so neither a broken connection
     * nor a failure here leaves an archive that looks whole.
     */
    @NonNull
    public static String systemCommand(@NonNull String prefix, @NonNull String alias, @NonNull String[] transport,
                                       @NonNull String dir, @NonNull String fileName) {
        StringBuilder tar = new StringBuilder("cd / || { echo " + FAILED_MARK + "; exit 1; }; tar -czpf - --warning=no-file-ignored");
        for (String exclude : SYSTEM_EXCLUDES) tar.append(" --exclude=").append(TerminowStore.shellQuote(exclude));
        // 1 is files that changed while they were read, which is not a failure of the backup
        tar.append(" .; s=$?; [ \"$s\" -le 1 ] || { echo " + FAILED_MARK + "; exit \"$s\"; }");
        String target = (dir.endsWith("/") ? dir : dir + "/") + fileName;
        String remote = "mkdir -p " + dir + " && cat > " + target + ".part && gzip -t " + target + ".part && mv " + target + ".part " + target +
            " || { rm -f " + target + ".part; exit 1; }";
        return "( set -o pipefail; " + join(Arrays.asList(prefix + "/bin/proot-distro", "login", alias, "--user", "root", "--",
            "/bin/sh", "-c", tar.toString())) + " | " + prefix + "/bin/ssh " + transport[0] + " " + transport[1] + " " +
            TerminowStore.shellQuote(remote) + " )" + result();
    }

    /** The name of a new archive of an environment, like ubuntu-20261018-1930.tar.gz. */
    @NonNull
    public static String archiveName(@NonNull String alias, long time) {
        return alias + "-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(new java.util.Date(time)) + ".tar.gz";
    }

    /** The ssh and bash of a ProxyCommand are the ones of TermiNow, also inside the environment. */
    @NonNull
    static String withTermiNowPrograms(@NonNull String options, @NonNull String prefix) {
        return options.replace("ProxyCommand=ssh ", "ProxyCommand=" + prefix + "/bin/ssh ")
            .replace("ProxyCommand=bash ", "ProxyCommand=" + prefix + "/bin/bash ");
    }

    /** Not gzip: after an archive, it makes it fail the check on the host. */
    private static final String FAILED_MARK = "TERMINOW-BACKUP-FAILED";

    @NonNull
    private static String result() {
        return " && echo \"TermiNow: backup done\" || echo \"TermiNow: backup failed, exit code $?\"";
    }

    @NonNull
    private static String join(@NonNull List<String> words) {
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() > 0) out.append(' ');
            out.append(TerminowStore.shellQuote(word));
        }
        return out.toString();
    }
}
