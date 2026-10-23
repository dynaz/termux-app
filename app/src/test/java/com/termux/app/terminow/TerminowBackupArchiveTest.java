package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class TerminowBackupArchiveTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    // ---------------------------------------------------------------------------------------
    // Folders

    @Test
    public void foldersInsideAChosenFolderAreLeftOutAndTheRestSorted() {
        assertEquals(Arrays.asList("/home", "/root"),
            TerminowBackupArchive.normalizeSources(Arrays.asList("/root/projects", "/root", "/home/", "/root//.ssh", "/home")));
        assertEquals(Arrays.asList("/root/a", "/root/ab"), TerminowBackupArchive.normalizeSources(Arrays.asList("/root/ab", "/root/a")));
        assertEquals(Collections.singletonList("/root"), TerminowBackupArchive.normalizeSources(Arrays.asList("/root/./x/..x", "/root")));
    }

    @Test
    public void whatIsNoFolderOfTheEnvironmentIsNotAllowed() {
        assertNull(TerminowBackupArchive.normalizeSources(Collections.singletonList("/")));
        assertNull(TerminowBackupArchive.normalizeSources(Collections.singletonList("root")));
        assertNull(TerminowBackupArchive.normalizeSources(Collections.singletonList("/root/../etc")));
        assertNull(TerminowBackupArchive.normalizeSources(Collections.singletonList("/proc")));
        assertNull(TerminowBackupArchive.normalizeSources(Collections.singletonList("/sys/kernel")));
        assertNull(TerminowBackupArchive.normalizeSources(Collections.singletonList("/sdcard/Download")));
        assertNull(TerminowBackupArchive.normalizeSources(Collections.singletonList("/data/data/com.terminow/files/usr")));
    }

    @Test
    public void tarGetsEachFolderAsAnArgumentAfterTheOptionsAndBoundFoldersAreLeftOut() {
        List<String> args = TerminowBackupArchive.folderTarArguments(Arrays.asList("/home", "/root"),
            Arrays.asList("/root/shared", "/sdcard", "/proc"));
        int end = args.indexOf("--");
        assertTrue(end > 0);
        assertEquals(Arrays.asList("home", "root"), args.subList(end + 1, args.size()));
        assertTrue(args.contains("--exclude=root/shared"));
        assertFalse("not inside a chosen folder", args.contains("--exclude=sdcard"));
        assertTrue(args.contains("--one-file-system"));
        assertTrue(args.contains("--no-wildcards"));
        assertFalse("links are never followed", args.contains("--dereference") || args.contains("-h"));
    }

    // ---------------------------------------------------------------------------------------
    // Manifest

    @Test
    public void theManifestSaysWhatTheBackupIs() {
        TerminowBackupArchive.Manifest manifest = new TerminowBackupArchive.Manifest();
        manifest.type = TerminowBackupArchive.TYPE_FOLDERS;
        manifest.alias = "ubuntu";
        manifest.distro = "ubuntu 24.04";
        manifest.architecture = "aarch64";
        manifest.created = 1792399990000L;
        manifest.sources = Arrays.asList("/home", "/root");
        manifest.app = "TermiNow 0.119.0.1 (190)";
        String json = manifest.toJson();
        assertTrue(json, json.contains("\"format\": \"terminow-backup\""));
        assertTrue(json, json.contains("\"created\": \"2026-10-19T08:53:10Z\""));
        TerminowBackupArchive.Manifest read = TerminowBackupArchive.Manifest.parse(json);
        assertNotNull(read);
        assertEquals(1, read.version);
        assertEquals(TerminowBackupArchive.TYPE_FOLDERS, read.type);
        assertEquals("aarch64", read.architecture);
        assertEquals(Arrays.asList("/home", "/root"), read.sources);
        assertNull(TerminowBackupArchive.Manifest.parse("{\"format\":\"other\"}"));
        assertNull(TerminowBackupArchive.Manifest.parse("not json"));
    }

    // ---------------------------------------------------------------------------------------
    // Archives that GNU tar wrote

    private static boolean hasGnuTar() {
        try {
            Process process = new ProcessBuilder("tar", "--version").redirectErrorStream(true).start();
            String version = read(process.getInputStream());
            return process.waitFor(10, TimeUnit.SECONDS) && version.contains("GNU tar");
        } catch (Exception e) {
            return false;
        }
    }

    private static String read(InputStream in) throws Exception {
        return new String(readAll(in), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    /** The arguments of the environment, run here with a folder of the test as its "/". */
    private byte[] runTar(List<String> args, File root, byte[] stdin) throws Exception {
        List<String> command = new ArrayList<>();
        for (String arg : args) command.add(arg.equals("--directory=/") ? "--directory=" + root.getPath() :
            arg.startsWith("--directory=/") ? "--directory=" + root.getPath() + arg.substring("--directory=".length()) : arg);
        Process process = new ProcessBuilder(command).start();
        Thread errors = new Thread(() -> {
            try {
                readAll(process.getErrorStream());
            } catch (Exception ignored) {
                // Not looked at
            }
        });
        errors.start();
        try (OutputStream out = process.getOutputStream()) {
            if (stdin != null) out.write(stdin);
        }
        byte[] output = readAll(process.getInputStream());
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        errors.join();
        assertEquals("tar " + command, 0, process.exitValue());
        return output;
    }

    /** A folders backup of a tree like the home of root, made as the engine makes it. */
    private byte[] folderBackup(File root) throws Exception {
        File home = new File(root, "root");
        assertTrue(new File(home, ".ssh").mkdirs());
        Files.write(new File(home, ".ssh/id_ed25519").toPath(), "key".getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(home, ".claude").mkdirs());
        Files.write(new File(home, ".claude/settings.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(new File(home, "projects/app/.git").mkdirs());
        Files.write(new File(home, "projects/app/main.c").toPath(), "int main;".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(new File(home, "outside").toPath(), new File("/etc").toPath());
        assertTrue(new File(home, "shared").mkdirs());
        Files.write(new File(home, "shared/android.txt").toPath(), "bound".getBytes(StandardCharsets.UTF_8));

        TerminowBackupArchive.Manifest manifest = new TerminowBackupArchive.Manifest();
        manifest.type = TerminowBackupArchive.TYPE_FOLDERS;
        manifest.alias = "ubuntu";
        manifest.sources = Collections.singletonList("/root");
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(file)) {
            TerminowBackupArchive.writeFileEntry(out, TerminowBackupArchive.MANIFEST, manifest.toJson().getBytes(StandardCharsets.UTF_8), 0644, 0);
            out.write(runTar(TerminowBackupArchive.folderTarArguments(Collections.singletonList("/root"),
                Collections.singletonList("/root/shared")), root, null));
        }
        return file.toByteArray();
    }

    private static TerminowBackupArchive.Inspection inspectGz(byte[] gz) throws Exception {
        return TerminowBackupArchive.inspect(new GZIPInputStream(new ByteArrayInputStream(gz)), null);
    }

    @Test
    public void aFoldersBackupHasItsManifestHiddenFilesAndLinksButNoBoundFolder() throws Exception {
        assumeTrue(hasGnuTar());
        byte[] backup = folderBackup(temp.newFolder("rootfs"));
        TerminowBackupArchive.Inspection inspection = inspectGz(backup);
        assertNull(inspection.problem, inspection.problem);
        assertTrue(inspection.complete);
        assertTrue(inspection.isFolders());
        assertFalse(inspection.isFull());
        assertNotNull(inspection.manifest);
        assertEquals(Collections.singletonList("/root"), inspection.manifest.sources);

        // GNU tar reads it, manifest and all: the header that TermiNow writes is a good one
        File file = temp.newFile("backup.tar.gz");
        Files.write(file.toPath(), backup);
        Process list = new ProcessBuilder("tar", "--list", "--verbose", "--gzip", "--file=" + file.getPath()).redirectErrorStream(true).start();
        String listing = read(list.getInputStream());
        assertTrue(list.waitFor(30, TimeUnit.SECONDS));
        assertEquals(listing, 0, list.exitValue());
        assertTrue(listing, listing.contains(TerminowBackupArchive.MANIFEST));
        assertTrue(listing, listing.contains("root/.ssh/id_ed25519"));
        assertTrue(listing, listing.contains("root/.claude/settings.json"));
        assertTrue(listing, listing.contains("root/projects/app/.git"));
        assertTrue("a link stays a link", listing.contains("root/outside -> /etc"));
        assertFalse("the bound folder is left out", listing.contains("android.txt"));
    }

    @Test
    public void aFoldersBackupRestoresKeepingOrReplacingWhatIsThere() throws Exception {
        assumeTrue(hasGnuTar());
        byte[] backup = folderBackup(temp.newFolder("rootfs"));
        byte[] tar = readAll(new GZIPInputStream(new ByteArrayInputStream(backup)));

        File keep = temp.newFolder("keep");
        assertTrue(new File(keep, "root/.ssh").mkdirs());
        Files.write(new File(keep, "root/.ssh/id_ed25519").toPath(), "newer".getBytes(StandardCharsets.UTF_8));
        runTar(TerminowBackupArchive.folderRestoreArguments("/", false), keep, tar);
        assertEquals("newer", new String(Files.readAllBytes(new File(keep, "root/.ssh/id_ed25519").toPath()), StandardCharsets.UTF_8));
        assertTrue(new File(keep, "root/projects/app/main.c").isFile());
        assertTrue(Files.isSymbolicLink(new File(keep, "root/outside").toPath()));
        assertFalse("the manifest is not unpacked", new File(keep, TerminowBackupArchive.MANIFEST).exists());

        File replace = temp.newFolder("replace");
        assertTrue(new File(replace, "root/.ssh").mkdirs());
        Files.write(new File(replace, "root/.ssh/id_ed25519").toPath(), "newer".getBytes(StandardCharsets.UTF_8));
        runTar(TerminowBackupArchive.folderRestoreArguments("/", true), replace, tar);
        assertEquals("key", new String(Files.readAllBytes(new File(replace, "root/.ssh/id_ed25519").toPath()), StandardCharsets.UTF_8));

        // Into a folder of its own
        File into = temp.newFolder("into");
        assertTrue(new File(into, "restored").mkdirs());
        runTar(TerminowBackupArchive.folderRestoreArguments("/restored", false), into, tar);
        assertTrue(new File(into, "restored/root/.claude/settings.json").isFile());
    }

    // ---------------------------------------------------------------------------------------
    // Archives that are not good

    private static byte[] archive(Entry... entries) throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Entry entry : entries) entry.write(tar);
        tar.write(new byte[1024]);
        return tar.toByteArray();
    }

    /** A tar entry, with a link name for links. */
    private static final class Entry {
        final String name;
        final char type;
        final String link;
        final byte[] data;

        Entry(String name, char type, String link, byte[] data) {
            this.name = name;
            this.type = type;
            this.link = link;
            this.data = data;
        }

        static Entry file(String name, String text) {
            return new Entry(name, '0', "", text.getBytes(StandardCharsets.UTF_8));
        }

        void write(ByteArrayOutputStream tar) throws Exception {
            ByteArrayOutputStream one = new ByteArrayOutputStream();
            TerminowBackupArchive.writeFileEntry(one, name, data, 0644, 0);
            byte[] bytes = one.toByteArray();
            bytes[156] = (byte) type;
            byte[] linkBytes = link.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(linkBytes, 0, bytes, 157, linkBytes.length);
            // The checksum again, for the type and the link
            for (int i = 148; i < 156; i++) bytes[i] = ' ';
            long sum = 0;
            for (int i = 0; i < 512; i++) sum += bytes[i] & 0xff;
            byte[] checksum = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(checksum, 0, bytes, 148, 8);
            tar.write(bytes);
        }
    }

    private static String folders() {
        TerminowBackupArchive.Manifest manifest = new TerminowBackupArchive.Manifest();
        manifest.type = TerminowBackupArchive.TYPE_FOLDERS;
        manifest.alias = "ubuntu";
        return manifest.toJson();
    }

    private static String full() {
        TerminowBackupArchive.Manifest manifest = new TerminowBackupArchive.Manifest();
        manifest.type = TerminowBackupArchive.TYPE_FULL;
        manifest.alias = "ubuntu";
        return manifest.toJson();
    }

    private static TerminowBackupArchive.Inspection inspect(byte[] tar) throws Exception {
        return TerminowBackupArchive.inspect(new ByteArrayInputStream(tar), null);
    }

    @Test
    public void namesThatGoOutOfWhereTheyAreRestoredAreRejected() throws Exception {
        assertNotNull(inspect(archive(Entry.file(TerminowBackupArchive.MANIFEST, folders()), Entry.file("root/../../etc/passwd", "x"))).problem);
        assertNotNull(inspect(archive(Entry.file(TerminowBackupArchive.MANIFEST, folders()), Entry.file("/etc/passwd", "x"))).problem);
        // A link, then a file through it
        assertNotNull(inspect(archive(Entry.file(TerminowBackupArchive.MANIFEST, folders()),
            new Entry("root/escape", '2', "/etc", new byte[0]), Entry.file("root/escape/passwd", "x"))).problem);
        // A hard link to a name out of it
        assertNotNull(inspect(archive(Entry.file(TerminowBackupArchive.MANIFEST, folders()),
            new Entry("root/hard", '1', "../../etc/passwd", new byte[0]))).problem);
        // A link alone is kept as a link
        assertNull(inspect(archive(Entry.file(TerminowBackupArchive.MANIFEST, folders()),
            new Entry("root/link", '2', "/etc", new byte[0]), Entry.file("root/file", "x"))).problem);
    }

    @Test
    public void aFullBackupIsOfOneEnvironmentAndHasItsManifestInside() throws Exception {
        TerminowBackupArchive.Inspection good = inspect(archive(Entry.file("ubuntu/" + TerminowBackupArchive.MANIFEST, full()),
            Entry.file("ubuntu/manifest.json", "{}"), new Entry("ubuntu/rootfs/", '5', "", new byte[0]),
            Entry.file("ubuntu/rootfs/etc/hostname", "localhost")));
        assertNull(good.problem, good.problem);
        assertTrue(good.isFull());
        assertEquals("ubuntu", good.container);
        assertEquals("localhost".length() + 2, good.contentBytes);

        assertNotNull("two environments", inspect(archive(Entry.file("ubuntu/" + TerminowBackupArchive.MANIFEST, full()),
            Entry.file("debian/rootfs/etc/hostname", "x"))).problem);
        assertNotNull("a folders manifest inside an environment", inspect(archive(Entry.file("ubuntu/" + TerminowBackupArchive.MANIFEST, folders()),
            Entry.file("ubuntu/rootfs/x", "x"))).problem);
        assertNotNull("a full manifest at the top", inspect(archive(Entry.file(TerminowBackupArchive.MANIFEST, full()),
            Entry.file("root/x", "x"))).problem);
    }

    @Test
    public void aBackupOfProotDistroWithoutTheManifestIsAFullBackup() throws Exception {
        TerminowBackupArchive.Inspection plain = inspect(archive(Entry.file("debian/manifest.json", "{}"),
            Entry.file("debian/rootfs/etc/hostname", "x")));
        assertNull(plain.problem, plain.problem);
        assertTrue(plain.plainProotDistro);
        assertTrue(plain.isFull());
        assertEquals("debian", plain.container);
    }

    @Test
    public void whatIsNoBackupOrNotWholeIsSaidSo() throws Exception {
        // Like a backup of Termux itself, with home and usr
        TerminowBackupArchive.Inspection other = inspect(archive(Entry.file("home/.bashrc", "x"), Entry.file("usr/bin/bash", "x")));
        assertNotNull(other.problem);
        assertFalse(other.isFull() || other.isFolders());

        byte[] whole = archive(Entry.file(TerminowBackupArchive.MANIFEST, folders()), Entry.file("root/file", "data"));
        TerminowBackupArchive.Inspection cut = inspect(Arrays.copyOf(whole, 1024));
        assertFalse(cut.complete);
        assertNotNull(cut.problem);

        // A byte of the header of the second entry
        byte[] damaged = whole.clone();
        damaged[1024 + 5] ^= 1;
        assertNotNull(inspect(damaged).problem);
    }

    @Test
    public void aGzipFileThatIsCutIsFoundOutByReadingItToTheEnd() throws Exception {
        assumeTrue(hasGnuTar());
        byte[] backup = folderBackup(temp.newFolder("rootfs"));
        byte[] cut = Arrays.copyOf(backup, backup.length - 4);
        boolean failed;
        try {
            GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(cut));
            TerminowBackupArchive.inspect(in, null);
            readAll(in);
            failed = false;
        } catch (java.io.IOException e) {
            failed = true;
        }
        assertTrue("the checksum of gzip at its end is checked", failed);
    }
}
