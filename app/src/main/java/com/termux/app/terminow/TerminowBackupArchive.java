package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The backups of Linux environments that TermiNow writes and reads, without Android: a tar
 * archive, gzipped, whose first entry is a manifest. A full backup is the archive of
 * {@code proot-distro backup} with the manifest as "&lt;alias&gt;/terminow-backup.json" in front,
 * which proot-distro restore ignores; a folders backup has "terminow-backup.json" and then the
 * folders, relative to "/".
 *
 * <p>Every archive is read through to its end before it is called good or restored: its gzip
 * checksum, every tar header checksum, and names that could write outside of where they are
 * restored to.
 */
final class TerminowBackupArchive {

    static final String FORMAT = "terminow-backup";
    static final int VERSION = 1;
    static final String TYPE_FULL = "full";
    static final String TYPE_FOLDERS = "folders";
    static final String MANIFEST = "terminow-backup.json";

    /** Folders that are no files of the environment, or are Android's, bound into it. */
    static final List<String> NOT_BACKED_UP = Collections.unmodifiableList(java.util.Arrays.asList(
        "/proc", "/sys", "/dev", "/run", "/tmp", "/storage", "/sdcard", "/mnt", "/data", "/apex", "/system", "/vendor",
        "/product", "/odm", "/system_ext", "/linkerconfig"));

    private TerminowBackupArchive() {}

    // ---------------------------------------------------------------------------------------
    // Manifest

    static final class Manifest {
        String type = TYPE_FULL;
        int version = VERSION;
        String alias = "";
        String distro = "";
        String architecture = "";
        long created;
        List<String> sources = new ArrayList<>();
        String app = "";

        @NonNull
        String toJson() {
            try {
                JSONObject object = new JSONObject();
                object.put("format", FORMAT);
                object.put("version", version);
                object.put("type", type);
                object.put("alias", alias);
                object.put("distro", distro);
                object.put("architecture", architecture);
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
                iso.setTimeZone(TimeZone.getTimeZone("UTC"));
                object.put("created", iso.format(new Date(created)));
                object.put("createdMillis", created);
                object.put("sources", new JSONArray(sources));
                object.put("app", app);
                return object.toString(2) + "\n";
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The manifest of a file, {@code null} if it is not one of TermiNow. */
        @Nullable
        static Manifest parse(@NonNull String json) {
            try {
                JSONObject object = new JSONObject(json);
                if (!FORMAT.equals(object.optString("format"))) return null;
                Manifest manifest = new Manifest();
                manifest.version = object.optInt("version", 0);
                manifest.type = object.optString("type");
                if (!TYPE_FULL.equals(manifest.type) && !TYPE_FOLDERS.equals(manifest.type)) return null;
                manifest.alias = object.optString("alias");
                manifest.distro = object.optString("distro");
                manifest.architecture = object.optString("architecture");
                manifest.created = object.optLong("createdMillis");
                manifest.app = object.optString("app");
                JSONArray sources = object.optJSONArray("sources");
                if (sources != null) for (int i = 0; i < sources.length(); i++) manifest.sources.add(sources.optString(i));
                return manifest;
            } catch (JSONException e) {
                return null;
            }
        }
    }

    /** The name a new backup file gets, like ubuntu-folders-20261019-1317.tar.gz. */
    @NonNull
    static String fileName(@NonNull String alias, @NonNull String type, long now) {
        return alias + "-" + (TYPE_FOLDERS.equals(type) ? "folders" : "backup") + "-" +
            new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(now)) + ".tar.gz";
    }

    // ---------------------------------------------------------------------------------------
    // Folders

    /**
     * The folders to back up, as absolute paths of the environment: each one normalized, the
     * ones inside another chosen one left out, sorted. {@code null} when one is not allowed:
     * not absolute, with "..", "/" itself, or what is not a file of the environment, like /proc.
     */
    @Nullable
    static List<String> normalizeSources(@NonNull List<String> paths) {
        List<String> normalized = new ArrayList<>();
        for (String path : paths) {
            String clean = normalize(path);
            if (clean == null || clean.equals("/") || isNotBackedUp(clean)) return null;
            if (!normalized.contains(clean)) normalized.add(clean);
        }
        Collections.sort(normalized);
        List<String> result = new ArrayList<>();
        for (String path : normalized) {
            boolean inside = false;
            for (String kept : result) if (path.startsWith(kept + "/")) inside = true;
            if (!inside) result.add(path);
        }
        return result;
    }

    /** An absolute path without "." and repeated slashes; {@code null} if it is relative or has "..". */
    @Nullable
    static String normalize(@NonNull String path) {
        if (!path.startsWith("/") || path.indexOf('\0') >= 0 || path.contains("\n")) return null;
        StringBuilder result = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            result.append('/').append(part);
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    static boolean isNotBackedUp(@NonNull String path) {
        for (String excluded : NOT_BACKED_UP)
            if (path.equals(excluded) || path.startsWith(excluded + "/")) return true;
        return false;
    }

    /**
     * The arguments of GNU tar in the environment that write the folders to stdout: each one an
     * argument of its own, after "--", never part of a command line. Links are kept as links and
     * never followed, other file systems are not entered, and the bound folders are left out.
     *
     * @param sources From {@link #normalizeSources}.
     * @param binds Where Android's folders are bound into the environment, left out when inside a source.
     */
    @NonNull
    static List<String> folderTarArguments(@NonNull List<String> sources, @NonNull List<String> binds) {
        List<String> args = new ArrayList<>(java.util.Arrays.asList("tar", "--create", "--file=-", "--directory=/",
            "--preserve-permissions", "--numeric-owner", "--one-file-system", "--no-wildcards", "--anchored"));
        for (String bind : binds) {
            String clean = normalize(bind);
            if (clean == null || clean.equals("/")) continue;
            for (String source : sources)
                if (clean.startsWith(source + "/")) args.add("--exclude=" + clean.substring(1));
        }
        args.add("--");
        for (String source : sources) args.add(source.substring(1));
        return args;
    }

    /**
     * The arguments of GNU tar in the environment that restore a folders backup from stdin into
     * a folder, "/" for where the folders were. Existing files are kept, or replaced.
     */
    @NonNull
    static List<String> folderRestoreArguments(@NonNull String destination, boolean overwrite) {
        List<String> args = new ArrayList<>(java.util.Arrays.asList("tar", "--extract", "--file=-", "--directory=" + destination,
            "--preserve-permissions", "--numeric-owner"));
        // GNU tar 1.35 takes neither with --no-overwrite-dir
        args.add(overwrite ? "--overwrite" : "--skip-old-files");
        args.add("--exclude=" + MANIFEST);
        return args;
    }

    // ---------------------------------------------------------------------------------------
    // Tar

    /** One file entry of a tar archive: its ustar header and its data, padded to 512 bytes. */
    static void writeFileEntry(@NonNull OutputStream out, @NonNull String name, @NonNull byte[] data, int mode, long modifiedSeconds)
        throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 100) throw new IOException("Name too long: " + name);
        byte[] header = new byte[512];
        System.arraycopy(nameBytes, 0, header, 0, nameBytes.length);
        octal(header, 100, 8, mode);
        octal(header, 108, 8, 0);
        octal(header, 116, 8, 0);
        octal(header, 124, 12, data.length);
        octal(header, 136, 12, modifiedSeconds);
        header[156] = '0';
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 6);
        header[263] = '0';
        header[264] = '0';
        // The checksum is taken with its own field as spaces
        for (int i = 148; i < 156; i++) header[i] = ' ';
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        byte[] checksum = String.format(Locale.ROOT, "%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(checksum, 0, header, 148, 8);
        out.write(header);
        out.write(data);
        int padding = (512 - data.length % 512) % 512;
        out.write(new byte[padding]);
    }

    private static void octal(@NonNull byte[] header, int offset, int length, long value) {
        String text = String.format(Locale.ROOT, "%0" + (length - 1) + "o", value);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
        header[offset + length - 1] = 0;
    }

    /** What reading an archive through found. */
    static final class Inspection {
        /** Why it can not be used, {@code null} when it can. */
        @Nullable String problem;
        @Nullable Manifest manifest;
        /** For a full backup: the environment it is of. */
        @Nullable String container;
        /** Whether it is a backup of proot-distro without the manifest of TermiNow, like one made in Termux. */
        boolean plainProotDistro;
        int entries;
        /** The bytes of all files in it, unpacked. */
        long contentBytes;
        boolean complete;

        boolean isFull() {
            return manifest != null ? TYPE_FULL.equals(manifest.type) : plainProotDistro;
        }

        boolean isFolders() {
            return manifest != null && TYPE_FOLDERS.equals(manifest.type);
        }
    }

    interface Progress {
        void read(long bytes);
    }

    /**
     * Read an unpacked tar archive to its end and say what it is. Names that are absolute, go up
     * with "..", or go through a link that the archive made, are a problem; so is an archive that
     * ends before its end marker.
     */
    @NonNull
    static Inspection inspect(@NonNull InputStream in, @Nullable Progress progress) throws IOException {
        Inspection result = new Inspection();
        byte[] header = new byte[512];
        Set<String> links = new HashSet<>();
        String longName = null;
        String paxPath = null;
        String paxLinkPath = null;
        boolean first = true;
        int zeroBlocks = 0;
        while (true) {
            if (!readFully(in, header, progress)) break;
            if (isZero(header)) {
                zeroBlocks++;
                if (zeroBlocks == 2) {
                    result.complete = true;
                    break;
                }
                continue;
            }
            zeroBlocks = 0;
            if (!checksumMatches(header)) {
                result.problem = "A header of the archive is damaged";
                return result;
            }
            char type = (char) header[156];
            long size = parseNumber(header, 124, 12);
            if (size < 0) {
                result.problem = "A size in the archive is damaged";
                return result;
            }
            if (type == 'x' || type == 'g' || type == 'L' || type == 'K') {
                if (size > 1 << 20) {
                    result.problem = "A header of the archive is too long";
                    return result;
                }
                byte[] data = readData(in, size, progress);
                if (data == null) return result;
                if (type == 'L') longName = cString(data, 0, (int) size);
                if (type == 'x') {
                    String path = paxValue(data, "path");
                    if (path != null) paxPath = path;
                    String linkPath = paxValue(data, "linkpath");
                    if (linkPath != null) paxLinkPath = linkPath;
                }
                continue;
            }
            String name = paxPath != null ? paxPath : longName != null ? longName : ustarName(header);
            String linkName = paxLinkPath != null ? paxLinkPath : cString(header, 157, 100);
            paxPath = null;
            paxLinkPath = null;
            longName = null;
            while (name.startsWith("./")) name = name.substring(2);
            String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            if (first) {
                first = false;
                boolean isManifest = clean.equals(MANIFEST) || clean.endsWith("/" + MANIFEST) && clean.indexOf('/') == clean.lastIndexOf('/');
                if (isManifest) {
                    byte[] data = readData(in, size, progress);
                    if (data == null) return result;
                    result.manifest = Manifest.parse(new String(data, StandardCharsets.UTF_8));
                    if (result.manifest == null) {
                        result.problem = "The manifest of the backup can not be read";
                        return result;
                    }
                    result.entries++;
                    // A full backup has it inside its environment, a folders backup at the top
                    boolean nested = clean.contains("/");
                    if (TYPE_FULL.equals(result.manifest.type) != nested) {
                        result.problem = "The manifest of the backup is not where its type has it";
                        return result;
                    }
                    if (nested) result.container = clean.substring(0, clean.indexOf('/'));
                    continue;
                }
                int slash = clean.indexOf('/');
                String top = slash > 0 ? clean.substring(0, slash) : clean;
                String rest = slash > 0 ? clean.substring(slash + 1) : "";
                if (rest.equals("manifest.json") || rest.equals("rootfs") || rest.startsWith("rootfs/")) {
                    result.plainProotDistro = true;
                    result.container = top;
                }
            }
            String problem = unsafe(clean, type, linkName, links, result);
            if (problem != null) {
                result.problem = problem;
                return result;
            }
            if (type == '2') links.add(clean);
            if (type == '1' && result.container != null && !linkName.equals(result.container) && !linkName.startsWith(result.container + "/")) {
                result.problem = "The archive has a hard link out of its environment: " + clean;
                return result;
            }
            if (type == '0' || type == '\0' || type == '7') result.contentBytes += size;
            result.entries++;
            if (type != '1' && type != '2' && type != '5') {
                if (!skip(in, size, progress)) return result;
            }
        }
        if (!result.complete) result.problem = "The archive ends before its end: it is not complete";
        else if (result.manifest == null && !result.plainProotDistro) result.problem = "This is no backup of TermiNow or proot-distro";
        else if (result.isFull() && (result.container == null || !com.termux.app.terminow.linux.TerminowLinuxRegistry.isValidAlias(result.container)))
            result.problem = "The backup does not say which environment it is of";
        return result;
    }

    /** Why a name can not be restored safely, {@code null} if it can. */
    @Nullable
    private static String unsafe(@NonNull String name, char type, @NonNull String linkName, @NonNull Set<String> links,
                                 @NonNull Inspection result) {
        if (name.startsWith("/")) return "The archive has an absolute name: " + name;
        for (String part : name.split("/")) if (part.equals("..")) return "The archive has a name with ..: " + name;
        // In a full backup everything is inside the one environment
        if (result.container != null && !name.equals(result.container) && !name.startsWith(result.container + "/"))
            return "The archive has more than one environment";
        // Nothing may be written through a link that the archive made itself
        String parent = name;
        while (parent.contains("/")) {
            parent = parent.substring(0, parent.lastIndexOf('/'));
            if (links.contains(parent)) return "The archive writes through a link: " + name;
        }
        if (type == '1') {
            if (linkName.startsWith("/")) return "The archive has a hard link to an absolute name: " + name;
            for (String part : linkName.split("/")) if (part.equals("..")) return "The archive has a hard link with ..: " + name;
        }
        return null;
    }

    private static boolean checksumMatches(@NonNull byte[] header) {
        long stored = parseNumber(header, 148, 8);
        long sum = 0;
        for (int i = 0; i < 512; i++) sum += (i >= 148 && i < 156) ? ' ' : header[i] & 0xff;
        return stored == sum;
    }

    @NonNull
    private static String ustarName(@NonNull byte[] header) {
        String name = cString(header, 0, 100);
        if ("ustar".equals(cString(header, 257, 5))) {
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
        }
        return name;
    }

    @Nullable
    private static String paxValue(@NonNull byte[] data, @NonNull String key) {
        String text = new String(data, StandardCharsets.UTF_8);
        int position = 0;
        while (position < text.length()) {
            int space = text.indexOf(' ', position);
            if (space < 0) return null;
            int length;
            try {
                length = Integer.parseInt(text.substring(position, space));
            } catch (NumberFormatException e) {
                return null;
            }
            if (length <= 0 || position + length > text.length()) return null;
            String record = text.substring(space + 1, position + length - 1);
            int equals = record.indexOf('=');
            if (equals > 0 && record.substring(0, equals).equals(key)) return record.substring(equals + 1);
            position += length;
        }
        return null;
    }

    @Nullable
    private static byte[] readData(@NonNull InputStream in, long size, @Nullable Progress progress) throws IOException {
        byte[] data = new byte[(int) ((size + 511) / 512 * 512)];
        if (!readFully(in, data, progress)) return null;
        return data;
    }

    private static boolean skip(@NonNull InputStream in, long size, @Nullable Progress progress) throws IOException {
        long left = (size + 511) / 512 * 512;
        byte[] buffer = new byte[1 << 16];
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n <= 0) return false;
            left -= n;
            if (progress != null) progress.read(n);
        }
        return true;
    }

    private static boolean readFully(@NonNull InputStream in, @NonNull byte[] buffer, @Nullable Progress progress) throws IOException {
        int done = 0;
        while (done < buffer.length) {
            int n = in.read(buffer, done, buffer.length - done);
            if (n <= 0) return false;
            done += n;
        }
        if (progress != null) progress.read(buffer.length);
        return true;
    }

    private static boolean isZero(@NonNull byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    /** An octal number of a header, or base-256 for big ones; -1 if it is not a number. */
    static long parseNumber(@NonNull byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = header[offset] & 0x7f;
            for (int i = 1; i < length; i++) value = (value << 8) | (header[offset + i] & 0xff);
            return value;
        }
        String text = cString(header, offset, length).trim();
        if (text.isEmpty()) return 0;
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @NonNull
    static String cString(@NonNull byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }
}
