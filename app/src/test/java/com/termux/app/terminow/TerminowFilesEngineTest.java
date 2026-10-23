package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowLocalFs;
import com.termux.app.terminow.files.TerminowTransfer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** TermiNow HOME as a file system, and copying and moving between file systems. */
public class TerminowFilesEngineTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static void write(File file, String text) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** A second file system on another folder, like a device folder or a host. */
    private static TerminowLocalFs other(File dir, String id) throws IOException {
        return new TerminowLocalFs(dir, id) {
            @NonNull
            @Override
            public String getId() {
                return id;
            }
        };
    }

    private static TerminowFs.Node node(TerminowFs fs, String name) throws IOException {
        TerminowFs.Node node = fs.find(fs.getRoot(), name);
        assertNotNull(name, node);
        return node;
    }

    /** Answers every conflict the same way and records them. */
    private static class Answers implements TerminowTransfer.Listener {
        final TerminowTransfer.Resolution resolution;
        final List<String> conflicts = new ArrayList<>();
        long lastBytes;
        TerminowFs.Cancel cancelAfterBytes;
        long cancelAt = Long.MAX_VALUE;

        Answers(TerminowTransfer.Resolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public void onProgress(@NonNull String name, long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
            lastBytes = bytesDone;
            if (cancelAfterBytes != null && bytesDone >= cancelAt) cancelAfterBytes.cancel();
        }

        @NonNull
        @Override
        public TerminowTransfer.Resolution onConflict(@NonNull String name) {
            conflicts.add(name);
            return resolution;
        }
    }

    @Test
    public void homeCanNotBeLeft() throws Exception {
        File home = mFolder.newFolder("home");
        File outside = mFolder.newFolder("outside");
        write(new File(outside, "secret.txt"), "secret");
        write(new File(home, "a.txt"), "a");
        Files.createSymbolicLink(new File(home, "out").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(home, "outfile").toPath(), new File(outside, "secret.txt").toPath());
        TerminowLocalFs fs = new TerminowLocalFs(home, "Home");

        for (String bad : new String[]{"..", "../outside", "a/../../outside", "/etc"}) {
            try {
                fs.toFile(bad);
                fail("Accepted " + bad);
            } catch (IOException expected) {
                // Expected
            }
        }
        try {
            fs.createDirectory(fs.getRoot(), "x/y");
            fail("A name with / was accepted");
        } catch (IOException expected) {
            // Expected
        }

        TerminowFs.Node out = node(fs, "out");
        assertTrue(out.link);
        // A link to a folder outside HOME is not a folder to open
        assertFalse(out.directory);
        try {
            fs.list(new TerminowFs.Node("out", "out", true, true, -1, 0, null), new TerminowFs.Cancel());
            fail("Listed a folder outside HOME through a link");
        } catch (IOException expected) {
            // Expected
        }
        try {
            fs.openInput(node(fs, "outfile"));
            fail("Read a file outside HOME through a link");
        } catch (IOException expected) {
            // Expected
        }
    }

    @Test
    public void deletingALinkNeverDeletesItsTarget() throws Exception {
        File home = mFolder.newFolder("home");
        write(new File(home, "keep/important.txt"), "keep me");
        File folder = new File(home, "folder");
        assertTrue(folder.mkdirs());
        Files.createSymbolicLink(new File(folder, "link-to-keep").toPath(), new File(home, "keep").toPath());
        // A loop
        Files.createSymbolicLink(new File(folder, "loop").toPath(), folder.toPath());
        TerminowLocalFs fs = new TerminowLocalFs(home, "Home");

        fs.delete(node(fs, "folder"), fs.getRoot(), new TerminowFs.Cancel());
        assertFalse(folder.exists());
        assertEquals("keep me", read(new File(home, "keep/important.txt")));
    }

    @Test
    public void copyAcrossFileSystemsWithConflicts() throws Exception {
        File home = mFolder.newFolder("home");
        File device = mFolder.newFolder("device");
        write(new File(home, "project/src/main.c"), "int main() {}");
        write(new File(home, "project/README"), "new readme");
        write(new File(home, "notes.txt"), "notes v2");
        write(new File(device, "notes.txt"), "notes v1");
        write(new File(device, "project/README"), "old readme");
        Files.createSymbolicLink(new File(home, "project/loop").toPath(), new File(home, "project").toPath());

        TerminowLocalFs source = new TerminowLocalFs(home, "Home");
        TerminowLocalFs target = other(device, "device");
        List<TerminowFs.Node> nodes = Arrays.asList(node(source, "project"), node(source, "notes.txt"));

        // Skip keeps what is there
        Answers skip = new Answers(TerminowTransfer.Resolution.SKIP);
        TerminowTransfer.Result result = new TerminowTransfer(source, target, false, skip, new TerminowFs.Cancel())
            .run(source.getRoot(), nodes, target.getRoot());
        assertEquals(Arrays.asList("README", "notes.txt"), sortedCopy(skip.conflicts));
        assertEquals("notes v1", read(new File(device, "notes.txt")));
        assertEquals("old readme", read(new File(device, "project/README")));
        assertEquals("int main() {}", read(new File(device, "project/src/main.c")));
        // The link loop was not followed
        assertEquals(Collections.singletonList("loop"), result.skippedLinks);
        assertFalse(new File(device, "project/loop").exists());

        // Keep both adds a numbered copy
        new TerminowTransfer(source, target, false, new Answers(TerminowTransfer.Resolution.KEEP_BOTH), new TerminowFs.Cancel())
            .run(source.getRoot(), Collections.singletonList(node(source, "notes.txt")), target.getRoot());
        assertEquals("notes v1", read(new File(device, "notes.txt")));
        assertEquals("notes v2", read(new File(device, "notes (2).txt")));

        // Replace
        new TerminowTransfer(source, target, false, new Answers(TerminowTransfer.Resolution.REPLACE), new TerminowFs.Cancel())
            .run(source.getRoot(), Collections.singletonList(node(source, "notes.txt")), target.getRoot());
        assertEquals("notes v2", read(new File(device, "notes.txt")));

        // No temporary files are left
        for (File file : Files.walk(device.toPath()).map(java.nio.file.Path::toFile).toArray(File[]::new))
            assertFalse(file.getName(), file.getName().endsWith(".terminow-partial"));
    }

    private static List<String> sortedCopy(List<String> list) {
        List<String> copy = new ArrayList<>(list);
        Collections.sort(copy);
        return copy;
    }

    @Test
    public void cancelLeavesNoHalfFile() throws Exception {
        File home = mFolder.newFolder("home");
        File device = mFolder.newFolder("device");
        byte[] big = new byte[3 * 1024 * 1024];
        Files.write(new File(home, "big.bin").toPath(), big);
        TerminowLocalFs source = new TerminowLocalFs(home, "Home");
        TerminowLocalFs target = other(device, "device");

        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        Answers answers = new Answers(TerminowTransfer.Resolution.SKIP);
        answers.cancelAfterBytes = cancel;
        answers.cancelAt = 1024 * 1024;
        TerminowTransfer.Result result = new TerminowTransfer(source, target, true, answers, cancel)
            .run(source.getRoot(), Collections.singletonList(node(source, "big.bin")), target.getRoot());
        assertTrue(result.cancelled);
        String[] left = device.list();
        assertNotNull(left);
        assertEquals(0, left.length);
        // A cancelled move keeps its source
        assertEquals(big.length, new File(home, "big.bin").length());
    }

    @Test
    public void moveDeletesTheSourceOnlyAfterACompleteCopy() throws Exception {
        File home = mFolder.newFolder("home");
        File device = mFolder.newFolder("device");
        write(new File(home, "docs/a.txt"), "a");
        write(new File(home, "docs/b.txt"), "b");
        TerminowLocalFs source = new TerminowLocalFs(home, "Home");
        TerminowLocalFs target = other(device, "device");

        TerminowTransfer.Result result = new TerminowTransfer(source, target, true, new Answers(TerminowTransfer.Resolution.SKIP),
            new TerminowFs.Cancel()).run(source.getRoot(), Collections.singletonList(node(source, "docs")), target.getRoot());
        assertTrue(result.errors.toString(), result.errors.isEmpty());
        assertEquals(2, result.filesCopied);
        assertFalse(new File(home, "docs").exists());
        assertEquals("b", read(new File(device, "docs/b.txt")));

        // A move whose copy fails keeps the source
        write(new File(home, "keep.txt"), "keep");
        TerminowLocalFs failing = new TerminowLocalFs(device, "device") {
            @NonNull
            @Override
            public String getId() {
                return "failing";
            }

            @NonNull
            @Override
            public OutputStream openOutput(@NonNull Node directory, @NonNull String name, boolean replace) throws IOException {
                throw new IOException("Disk full");
            }
        };
        result = new TerminowTransfer(source, failing, true, new Answers(TerminowTransfer.Resolution.SKIP), new TerminowFs.Cancel())
            .run(source.getRoot(), Collections.singletonList(node(source, "keep.txt")), failing.getRoot());
        assertFalse(result.errors.isEmpty());
        assertEquals("keep", read(new File(home, "keep.txt")));
    }

    @Test
    public void moveWithinHomeAndIntoItself() throws Exception {
        File home = mFolder.newFolder("home");
        write(new File(home, "a/file.txt"), "x");
        assertTrue(new File(home, "b").mkdirs());
        TerminowLocalFs fs = new TerminowLocalFs(home, "Home");

        TerminowTransfer.Result result = new TerminowTransfer(fs, fs, true, new Answers(TerminowTransfer.Resolution.SKIP),
            new TerminowFs.Cancel()).run(fs.getRoot(), Collections.singletonList(node(fs, "a")), node(fs, "b"));
        assertTrue(result.errors.toString(), result.errors.isEmpty());
        assertEquals("x", read(new File(home, "b/a/file.txt")));

        TerminowFs.Node b = node(fs, "b");
        TerminowFs.Node ba = fs.find(b, "a");
        result = new TerminowTransfer(fs, fs, false, new Answers(TerminowTransfer.Resolution.SKIP), new TerminowFs.Cancel())
            .run(fs.getRoot(), Collections.singletonList(b), ba);
        assertEquals(1, result.errors.size());
        assertTrue(result.errors.get(0).contains("into itself"));
    }

    @Test
    public void streamsAreReadable() throws Exception {
        File home = mFolder.newFolder("home");
        write(new File(home, "x.txt"), "hello");
        TerminowLocalFs fs = new TerminowLocalFs(home, "Home");
        try (InputStream in = fs.openInput(node(fs, "x.txt"))) {
            byte[] buffer = new byte[16];
            assertEquals(5, in.read(buffer));
        }
        try {
            fs.openOutput(fs.getRoot(), "x.txt", false).close();
            fail("Overwrote an existing file without replace");
        } catch (IOException expected) {
            // Expected
        }
        assertEquals("hello", read(new File(home, "x.txt")));
        assertNull(fs.find(fs.getRoot(), "missing"));
    }

}
