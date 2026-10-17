package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Installs a distribution of the {@link TerminowLinuxCatalog}:
 *
 * 1. checks the architecture, the name and the free space,
 * 2. downloads the pinned archive, with its size and SHA-256 checked while it arrives,
 * 3. has proot-distro of the runtime install the checked file (`proot-distro install FILE
 *    --name NAME`), which unpacks it with its own checks of the paths in the archive and sets
 *    up resolv.conf, hosts and the Android ids,
 * 4. validates the result: os-release of the expected distribution, a shell, and a login that
 *    runs a command.
 *
 * Only then is it installed. A failure, a cancel, or an install that was killed with the app is
 * cleaned up: the download and the container folder that this install created are removed, never
 * one that existed before. Downloads are not resumed: a new install starts again.
 */
public class TerminowLinuxInstaller {

    /** Room kept free after an install, so that the phone is not filled up. */
    static final long SPACE_MARGIN = 64L * 1024 * 1024;

    public enum Phase { CHECKING, DOWNLOADING, VERIFYING, EXTRACTING, SETTING_UP, VALIDATING, DONE }

    public interface Listener {
        void onPhase(@NonNull Phase phase, @NonNull String detail);

        /** Bytes of the download, total -1 if not known. */
        void onProgress(long done, long total);
    }

    /** Runs commands of the TermiNow runtime, the app's terminal environment on the phone. */
    public interface Runner {
        @NonNull Process start(@NonNull List<String> command) throws IOException;

        /** Send SIGINT, like Ctrl-C, which proot-distro answers by cleaning up. */
        void interrupt(int pid);
    }

    /** Keeps which install is running, to clean it up after the app was killed. */
    public interface Marker {
        @Nullable String getAlias();

        void set(@Nullable String alias);
    }

    public static class InstallException extends IOException {
        public InstallException(@NonNull String message) {
            super(message);
        }
    }

    private final TerminowLinuxRegistry mRegistry;
    private final File mDownloadDir;
    private final Runner mRunner;
    private final Marker mMarker;
    private final TerminowLinuxFileOps mOps;
    private volatile boolean mCancelled;
    @Nullable private volatile Process mProcess;
    private volatile int mPid = -1;

    public TerminowLinuxInstaller(@NonNull TerminowLinuxRegistry registry, @NonNull File downloadDir, @NonNull Runner runner,
                                  @NonNull Marker marker, @NonNull TerminowLinuxFileOps ops) {
        mRegistry = registry;
        mDownloadDir = downloadDir;
        mRunner = runner;
        mMarker = marker;
        mOps = ops;
    }

    public void cancel() {
        mCancelled = true;
        int pid = mPid;
        if (pid > 0) mRunner.interrupt(pid);
    }

    public boolean isCancelled() {
        return mCancelled;
    }

    private void checkCancelled() throws InstallException {
        if (mCancelled) throw new InstallException("Cancelled");
    }

    /** A free container name for a distribution: "debian", then "debian2" and so on. */
    @NonNull
    public String freeAlias(@NonNull String distroId) {
        String base = distroId.replaceAll("[^a-z0-9_.-]", "");
        if (base.isEmpty()) base = "linux";
        for (int i = 1; i < 100; i++) {
            String alias = i == 1 ? base : base + i;
            if (mOps.lstat(new File(mRegistry.getContainersDir(), alias)) == null &&
                mOps.lstat(new File(mRegistry.getRuntimeDir(), "installed-rootfs/" + alias)) == null) return alias;
        }
        return base + System.currentTimeMillis();
    }

    /** Whether there is room: the download, the unpacked system, and a margin. */
    public static boolean hasSpace(long usableBytes, @NonNull TerminowLinuxCatalog.Entry entry) {
        return usableBytes >= entry.downloadBytes + entry.installedBytes + entry.installedBytes / 5 + SPACE_MARGIN;
    }

    /**
     * Install an entry as a container with the alias. Blocks; call {@link #cancel()} from another
     * thread to stop it.
     */
    @NonNull
    public TerminowLinuxEnv install(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull String alias, @NonNull String deviceArch,
                                    long usableBytes, @NonNull Listener listener) throws IOException {
        listener.onPhase(Phase.CHECKING, "");
        if (entry.supportOn(deviceArch) != TerminowLinuxCatalog.Support.AVAILABLE)
            throw new InstallException(entry.name + " can not be installed on " + deviceArch + " with TermiNow yet");
        if (!TerminowLinuxRegistry.isValidAlias(alias)) throw new InstallException("Not a valid name: " + alias);
        File container = new File(mRegistry.getContainersDir(), alias);
        if (mOps.lstat(container) != null) throw new InstallException("An environment called " + alias + " exists already");
        if (!hasSpace(usableBytes, entry)) throw new InstallException("Not enough free space: " + entry.name + " needs about " +
            megabytes(entry.downloadBytes + entry.installedBytes + entry.installedBytes / 5 + SPACE_MARGIN) + " free");
        if (!mDownloadDir.isDirectory() && !mDownloadDir.mkdirs()) throw new InstallException("Could not create " + mDownloadDir);

        mMarker.set(alias);
        File partial = new File(mDownloadDir, alias + ".download");
        File archive = new File(mDownloadDir, alias + archiveExtension(entry.url));
        boolean ok = false;
        try {
            download(entry, partial, listener);
            checkCancelled();
            if (!partial.renameTo(archive)) throw new InstallException("Could not keep the download");
            listener.onPhase(Phase.VERIFYING, "paths");
            checkArchivePaths(archive);
            checkCancelled();

            listener.onPhase(Phase.EXTRACTING, "");
            runInstall(archive, alias, listener);
            checkCancelled();

            listener.onPhase(Phase.VALIDATING, "");
            TerminowLinuxEnv env = validate(entry, alias);
            ok = true;
            listener.onPhase(Phase.DONE, env.getTitle());
            return env;
        } finally {
            mPid = -1;
            mProcess = null;
            deleteQuietly(partial);
            deleteQuietly(archive);
            if (!ok) removeContainer(alias);
            mMarker.set(null);
        }
    }

    @NonNull
    static String archiveExtension(@NonNull String url) {
        String path = url.toLowerCase(Locale.ROOT);
        for (String ext : new String[]{".tar.gz", ".tar.xz", ".tar.zst", ".tar.bz2", ".tgz", ".txz"})
            if (path.endsWith(ext)) return ext;
        return ".tar.gz";
    }

    // ---------------------------------------------------------------------------------------

    void download(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull File partial, @NonNull Listener listener) throws IOException {
        listener.onPhase(Phase.DOWNLOADING, entry.url);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new InstallException("No SHA-256");
        }
        HttpURLConnection connection = null;
        long done = 0;
        try {
            URL url = new URL(entry.url);
            if (!"https".equals(url.getProtocol()) && !"127.0.0.1".equals(url.getHost()))
                throw new InstallException("Only https downloads are used");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            int status = connection.getResponseCode();
            if (status != 200) throw new InstallException("The download failed with HTTP " + status);
            long total = entry.downloadBytes > 0 ? entry.downloadBytes : connection.getContentLength();
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                long lastReport = 0;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled();
                    done += read;
                    // Never more than the pinned file
                    if (entry.downloadBytes > 0 && done > entry.downloadBytes) throw new InstallException("The download is larger than expected");
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                    if (done - lastReport > 256 * 1024 || done == total) {
                        lastReport = done;
                        listener.onProgress(done, total);
                    }
                }
            }
        } catch (InstallException e) {
            throw e;
        } catch (IOException e) {
            throw new InstallException("The download failed: " + e.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
        listener.onPhase(Phase.VERIFYING, "");
        if (entry.downloadBytes > 0 && done != entry.downloadBytes)
            throw new InstallException("The download is incomplete: " + done + " of " + entry.downloadBytes + " bytes");
        String actual = hex(digest.digest());
        if (!actual.equalsIgnoreCase(entry.sha256))
            throw new InstallException("The download does not match its checksum, it was not used");
    }

    /**
     * Read the names in a .tar.gz before it is unpacked: none may leave the rootfs, with ".." or
     * an absolute path, and a hard link may only point to a name in the archive. proot-distro
     * checks this again while it unpacks.
     */
    void checkArchivePaths(@NonNull File archive) throws IOException {
        if (!archive.getName().endsWith(".tar.gz") && !archive.getName().endsWith(".tgz")) return;
        scanArchive(archive, null, false);
    }

    /**
     * Read every entry of a .tar.gz: the checks of {@link #checkArchivePaths}, and the names of
     * its files and links for {@code names}, without the leading "./", if it is not {@code null}.
     * With {@code checkLinks}, for an archive of the user, a symbolic link may not point out of
     * the rootfs with ".."; the archives of the catalog are checked by their checksum instead.
     */
    void scanArchive(@NonNull File archive, @Nullable java.util.Set<String> names, boolean checkLinks) throws IOException {
        try (InputStream in = new java.util.zip.GZIPInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(archive)), 65536)) {
            byte[] header = new byte[512];
            String longName = null;
            String paxPath = null;
            String longLink = null;
            String paxLink = null;
            int entries = 0;
            while (true) {
                checkCancelled();
                if (!readFully(in, header)) break;
                boolean empty = true;
                for (byte b : header) if (b != 0) { empty = false; break; }
                if (empty) break;
                char type = (char) header[156];
                long size = parseOctal(header, 124, 12);
                String name = longName != null ? longName : paxPath != null ? paxPath : headerName(header);
                String link = longLink != null ? longLink : paxLink != null ? paxLink : cString(header, 157, 100);
                if (type == 'g') {
                    // A global pax header, which names nothing itself
                    skip(in, size + padding(size));
                    continue;
                }
                if (type == 'L' || type == 'x' || type == 'K') {
                    byte[] data = new byte[(int) Math.min(size, 1024 * 1024)];
                    if (!readFully(in, data)) throw new InstallException("The archive is damaged");
                    skip(in, size - data.length + padding(size));
                    if (type == 'L') longName = cString(data, 0, data.length);
                    else if (type == 'K') longLink = cString(data, 0, data.length);
                    else {
                        String records = new String(data, StandardCharsets.UTF_8);
                        paxPath = paxValue(records, "path");
                        paxLink = paxValue(records, "linkpath");
                    }
                    continue;
                }
                longName = null;
                paxPath = null;
                longLink = null;
                paxLink = null;
                checkEntryName(name);
                if (type == '1') checkEntryName(link);
                if (type == '2' && checkLinks) checkSymlink(name, link);
                if (names != null) names.add(normalizeEntry(name));
                skip(in, size + padding(size));
                if (++entries > 2_000_000) throw new InstallException("The archive has too many entries");
            }
            if (entries == 0) throw new InstallException("The archive is empty");
        }
    }

    /** "./etc/os-release" and "etc/os-release/" are "etc/os-release". */
    @NonNull
    static String normalizeEntry(@NonNull String name) {
        String out = name;
        while (out.startsWith("./")) out = out.substring(2);
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }

    /**
     * A link with an absolute target points into the rootfs once it is the root of the
     * environment; a relative one may not climb above the root with "..".
     */
    static void checkSymlink(@NonNull String name, @NonNull String target) throws InstallException {
        if (target.isEmpty() || target.contains("\0")) throw new InstallException("The archive has an invalid link: " + name);
        if (target.startsWith("/")) return;
        String[] parts = normalizeEntry(name).split("/");
        int depth = parts.length - 1;
        for (String part : target.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (--depth < 0) throw new InstallException("The archive has a link that leaves it: " + name + " -> " + target);
            } else {
                depth++;
            }
        }
    }

    /** Whether the names of an archive are the root of a Linux system: os-release and a shell, at the top. */
    static boolean looksLikeRootfs(@NonNull java.util.Set<String> names) {
        boolean osRelease = names.contains("etc/os-release") || names.contains("usr/lib/os-release");
        boolean shell = names.contains("bin/sh") || names.contains("usr/bin/sh") || names.contains("bin/busybox");
        return osRelease && shell;
    }

    /** The archives that can be imported: gzip-compressed tar, whose names are all checked before anything is unpacked. */
    public static boolean isImportable(@NonNull String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".tar.gz") || name.endsWith(".tgz");
    }

    /**
     * Import a root filesystem archive of the user as a container: its names are checked
     * (paths, hard and symbolic links, and the layout of a Linux root), then proot-distro unpacks
     * it, then it is only installed if its shell is for this phone and a login runs a command.
     * Anything else removes what was unpacked. Blocks.
     */
    @NonNull
    public TerminowLinuxEnv importArchive(@NonNull File archive, @NonNull String alias, @NonNull String deviceArch, long usableBytes,
                                          @NonNull Listener listener) throws IOException {
        listener.onPhase(Phase.CHECKING, "");
        if (!isImportable(archive.getName())) throw new InstallException("Only .tar.gz and .tgz archives can be imported");
        if (!TerminowLinuxRegistry.isValidAlias(alias)) throw new InstallException("Not a valid name: " + alias);
        File container = new File(mRegistry.getContainersDir(), alias);
        if (mOps.lstat(container) != null) throw new InstallException("An environment called " + alias + " exists already");
        // Unpacked, a rootfs takes a few times its compressed size
        long needed = archive.length() * 4 + SPACE_MARGIN;
        if (usableBytes < needed) throw new InstallException("Not enough free space: about " + megabytes(needed) + " is needed");
        mMarker.set(alias);
        boolean ok = false;
        try {
            listener.onPhase(Phase.VERIFYING, "paths");
            java.util.Set<String> names = new java.util.HashSet<>();
            scanArchive(archive, names, true);
            if (!looksLikeRootfs(names))
                throw new InstallException("This is not the root of a Linux system: etc/os-release and bin/sh must be at the top of the archive");
            checkCancelled();

            listener.onPhase(Phase.EXTRACTING, "");
            runInstall(archive, alias, listener);
            checkCancelled();

            listener.onPhase(Phase.VALIDATING, "");
            TerminowLinuxEnv env = mRegistry.get(alias);
            if (env == null || env.rootfs == null) throw new InstallException("The environment was not created");
            if (env.status != TerminowLinuxEnv.Status.INSTALLED) throw new InstallException("The imported system has no shell");
            if (env.arch.isEmpty()) throw new InstallException("The shell of the imported system is not a program TermiNow can run");
            if (!env.arch.equals(deviceArch)) throw new InstallException("The system is for " + env.arch + ", this phone is " + deviceArch);
            checkLogin(alias);
            ok = true;
            listener.onPhase(Phase.DONE, env.getTitle());
            return env;
        } finally {
            mPid = -1;
            mProcess = null;
            if (!ok) removeContainer(alias);
            mMarker.set(null);
        }
    }

    static void checkEntryName(@NonNull String name) throws InstallException {
        if (name.startsWith("/")) throw new InstallException("The archive has an absolute path: " + name);
        for (String part : name.split("/"))
            if (part.equals("..")) throw new InstallException("The archive has a path that leaves it: " + name);
        if (name.contains("\0")) throw new InstallException("The archive has an invalid name");
    }

    private static long padding(long size) {
        return (512 - size % 512) % 512;
    }

    private static boolean readFully(@NonNull InputStream in, @NonNull byte[] buffer) throws IOException {
        int off = 0;
        while (off < buffer.length) {
            int read = in.read(buffer, off, buffer.length - off);
            if (read < 0) {
                if (off == 0) return false;
                throw new InstallException("The archive is damaged");
            }
            off += read;
        }
        return true;
    }

    private static void skip(@NonNull InputStream in, long count) throws IOException {
        byte[] buffer = new byte[8192];
        while (count > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, count));
            if (read < 0) throw new InstallException("The archive is damaged");
            count -= read;
        }
    }

    @NonNull
    private static String headerName(@NonNull byte[] header) {
        String name = cString(header, 0, 100);
        String magic = cString(header, 257, 6);
        if (magic.startsWith("ustar")) {
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
        }
        return name;
    }

    @NonNull
    private static String cString(@NonNull byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long parseOctal(@NonNull byte[] header, int offset, int length) throws InstallException {
        // Base-256 for large sizes
        if ((header[offset] & 0x80) != 0) {
            long value = 0;
            for (int i = offset + 1; i < offset + length; i++) value = value << 8 | (header[i] & 0xff);
            return value;
        }
        String text = cString(header, offset, length).trim();
        if (text.isEmpty()) return 0;
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new InstallException("The archive is damaged");
        }
    }

    @Nullable
    private static String paxValue(@NonNull String records, @NonNull String key) {
        for (String record : records.split("\n")) {
            int space = record.indexOf(' ');
            int equals = record.indexOf('=');
            if (space > 0 && equals > space && record.substring(space + 1, equals).equals(key)) return record.substring(equals + 1);
        }
        return null;
    }

    void runInstall(@NonNull File archive, @NonNull String alias, @NonNull Listener listener) throws IOException {
        // The shell reports its pid, which exec keeps for proot-distro, so that it can be interrupted
        List<String> command = Arrays.asList(new File(mRegistry.getPrefix(), "bin/bash").getPath(), "-c",
            "echo TERMINOW_PID=$$; exec \"$@\"", "terminow", new File(mRegistry.getPrefix(), "bin/proot-distro").getPath(),
            "install", archive.getAbsolutePath(), "--name", alias);
        Process process = mRunner.start(command);
        mProcess = process;
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // Nothing is written to it
        }
        StringBuilder log = new StringBuilder();
        Thread errors = new Thread(() -> collect(process.getErrorStream(), log, listener));
        errors.start();
        collect(process.getInputStream(), log, listener);
        int exit;
        try {
            errors.join(60000);
            exit = process.waitFor();
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new InstallException("Interrupted");
        }
        checkCancelled();
        if (exit != 0) throw new InstallException("proot-distro could not install it (exit " + exit + "):\n" + lastLines(log.toString(), 6));
    }

    private void collect(@NonNull InputStream stream, @NonNull StringBuilder log, @NonNull Listener listener) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("TERMINOW_PID=")) {
                    try {
                        mPid = Integer.parseInt(line.substring("TERMINOW_PID=".length()).trim());
                        if (mCancelled) mRunner.interrupt(mPid);
                    } catch (NumberFormatException e) {
                        // No pid, cancel only stops the process
                    }
                    continue;
                }
                String clean = line.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").replace("\r", "").trim();
                if (clean.isEmpty()) continue;
                synchronized (log) {
                    log.append(clean).append('\n');
                }
                if (clean.contains("Extracting")) listener.onPhase(Phase.EXTRACTING, clean);
                else if (clean.contains("Updating") || clean.contains("Registering")) listener.onPhase(Phase.SETTING_UP, clean);
            }
        } catch (IOException e) {
            // The process ended
        }
    }

    /** Installed means: the expected os-release, a shell, and a login that runs a command. */
    @NonNull
    TerminowLinuxEnv validate(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull String alias) throws IOException {
        TerminowLinuxEnv env = mRegistry.get(alias);
        if (env == null || env.rootfs == null) throw new InstallException("The environment was not created");
        if (!entry.distroId.equals(env.distroId)) throw new InstallException("The installed system is not " + entry.name);
        if (env.status == TerminowLinuxEnv.Status.INCOMPLETE) throw new InstallException("The installed system has no shell");
        checkLogin(alias);
        return env;
    }

    /** A login of the environment runs a command, or it is not installed. */
    private void checkLogin(@NonNull String alias) throws IOException {
        Process process = mRunner.start(Arrays.asList(new File(mRegistry.getPrefix(), "bin/proot-distro").getPath(),
            "login", alias, "--", "/bin/sh", "-c", "echo terminow-ok"));
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // Nothing is written to it
        }
        StringBuilder log = new StringBuilder();
        Thread errors = new Thread(() -> drain(process.getErrorStream(), log));
        errors.start();
        drain(process.getInputStream(), log);
        try {
            errors.join(60000);
            int exit = process.waitFor();
            if (exit != 0 || !log.toString().contains("terminow-ok"))
                throw new InstallException("The environment did not start (exit " + exit + "):\n" + lastLines(log.toString(), 6));
        } catch (InterruptedException e) {
            process.destroy();
            Thread.currentThread().interrupt();
            throw new InstallException("Interrupted");
        }
    }

    private static void drain(@NonNull InputStream stream, @NonNull StringBuilder log) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (log) {
                    log.append(line).append('\n');
                }
            }
        } catch (IOException e) {
            // The process ended
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cleaning up
    // ---------------------------------------------------------------------------------------

    /**
     * Clean up an install that was killed with the app: its download and its container folder.
     * Returns the alias that was cleaned up, {@code null} if there was nothing.
     */
    @Nullable
    public String cleanUpInterrupted() {
        String alias = mMarker.getAlias();
        if (alias == null) return null;
        deleteQuietly(new File(mDownloadDir, alias + ".download"));
        File[] downloads = mDownloadDir.listFiles();
        if (downloads != null)
            for (File file : downloads) if (file.getName().startsWith(alias + ".tar")) deleteQuietly(file);
        removeContainer(alias);
        mMarker.set(null);
        return alias;
    }

    /** Remove the container folder of this install. Links in it are removed, never followed. */
    private void removeContainer(@NonNull String alias) {
        if (!TerminowLinuxRegistry.isValidAlias(alias)) return;
        File container = new File(mRegistry.getContainersDir(), alias);
        TerminowLinuxFileOps.Stat stat = mOps.lstat(container);
        if (stat == null) return;
        if (stat.link) {
            // Not what an install makes: only the link goes
            deleteQuietly(container);
            return;
        }
        deleteTree(container);
    }

    void deleteTree(@NonNull File file) {
        TerminowLinuxFileOps.Stat stat = mOps.lstat(file);
        if (stat == null) return;
        if (stat.directory && !stat.link) {
            // Unpacked folders can be read-only, like /usr/lib/... of some packages
            if ((stat.mode & 0700) != 0700) {
                try {
                    mOps.chmod(file, stat.mode | 0700);
                } catch (IOException e) {
                    // Then the delete below fails for its children
                }
            }
            String[] children = file.list();
            if (children != null) for (String child : children) deleteTree(new File(file, child));
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void deleteQuietly(@NonNull File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    static String hex(@NonNull byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    @NonNull
    static String megabytes(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    @NonNull
    static String lastLines(@NonNull String text, int count) {
        String[] lines = text.trim().split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) out.append(lines[i]).append('\n');
        return out.toString().trim();
    }

}
