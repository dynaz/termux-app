package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.termux.app.terminow.files.TerminowFileScan;
import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowLocalFs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TerminowFileScanTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static void write(File file, int bytes) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[bytes]);
    }

    @Test
    public void categories() {
        assertEquals(TerminowFileScan.Category.IMAGES, TerminowFileScan.Category.of("Photo.JPG"));
        assertEquals(TerminowFileScan.Category.VIDEOS, TerminowFileScan.Category.of("clip.mkv"));
        assertEquals(TerminowFileScan.Category.AUDIO, TerminowFileScan.Category.of("song.flac"));
        assertEquals(TerminowFileScan.Category.DOCUMENTS, TerminowFileScan.Category.of("report.pdf"));
        assertEquals(TerminowFileScan.Category.APK, TerminowFileScan.Category.of("TermiNow.apk"));
        assertEquals(TerminowFileScan.Category.ARCHIVES, TerminowFileScan.Category.of("backup.tar.gz"));
        assertNull(TerminowFileScan.Category.of("script.sh"));
        assertNull(TerminowFileScan.Category.of(".jpg"));
        assertNull(TerminowFileScan.Category.of("noextension"));
    }

    @Test
    public void findSkipsLinksAndHiddenFolders() throws Exception {
        File home = mFolder.newFolder("home");
        File outside = mFolder.newFolder("outside");
        write(new File(home, "photos/a.jpg"), 10);
        write(new File(home, "photos/deep/b.png"), 10);
        write(new File(home, ".cache/c.jpg"), 10);
        write(new File(home, "notes.txt"), 10);
        write(new File(outside, "private.jpg"), 10);
        Files.createSymbolicLink(new File(home, "linked").toPath(), outside.toPath());
        Files.createSymbolicLink(new File(home, "photos/loop").toPath(), new File(home, "photos").toPath());
        TerminowLocalFs fs = new TerminowLocalFs(home, "Home");

        List<TerminowFileScan.Match> results = new ArrayList<>();
        TerminowFileScan.find(fs, Collections.singletonList(fs.getRoot()), node -> TerminowFileScan.Category.IMAGES.matches(node.name),
            false, results, new TerminowFs.Cancel(), null);
        List<String> names = new ArrayList<>();
        for (TerminowFileScan.Match match : results) names.add(match.node.id);
        Collections.sort(names);
        assertEquals(java.util.Arrays.asList("photos/a.jpg", "photos/deep/b.png"), names);

        results.clear();
        TerminowFileScan.find(fs, Collections.singletonList(fs.getRoot()), node -> TerminowFileScan.Category.IMAGES.matches(node.name),
            true, results, new TerminowFs.Cancel(), null);
        assertEquals(3, results.size());
        // The folders down to a match are kept, to open its folder
        for (TerminowFileScan.Match match : results)
            if (match.node.name.equals("b.png")) assertEquals("deep", match.parents.get(match.parents.size() - 1).name);
    }

    @Test
    public void findCanBeCancelled() throws Exception {
        File home = mFolder.newFolder("home");
        for (int i = 0; i < 50; i++) write(new File(home, "d" + i + "/f.jpg"), 1);
        TerminowLocalFs fs = new TerminowLocalFs(home, "Home");
        TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        List<TerminowFileScan.Match> results = new ArrayList<>();
        try {
            TerminowFileScan.find(fs, Collections.singletonList(fs.getRoot()), node -> true, false, results, cancel,
                (name, found) -> {
                    if (found >= 5) cancel.cancel();
                });
            assertFalse("The search was not cancelled", true);
        } catch (TerminowFs.CancelledException expected) {
            assertTrue(results.size() < 50);
        }
    }

    @Test
    public void analyzeAddsUpWithoutFollowingLinks() throws Exception {
        File home = mFolder.newFolder("home");
        File outside = mFolder.newFolder("outside");
        write(new File(home, "big/video.mp4"), 5000);
        write(new File(home, "big/sub/more.bin"), 3000);
        write(new File(home, "small/a.txt"), 100);
        write(new File(home, "top.txt"), 50);
        write(new File(outside, "huge.bin"), 100000);
        Files.createSymbolicLink(new File(home, "outside").toPath(), outside.toPath());
        TerminowLocalFs fs = new TerminowLocalFs(home, "Home");

        TerminowFileScan.Space space = TerminowFileScan.analyze(fs, fs.getRoot(), new TerminowFs.Cancel(), null);
        assertEquals(8150, space.totalBytes);
        assertEquals(4, space.files);
        assertEquals("big", space.top.get(0).node.name);
        assertEquals(8000, space.top.get(0).bytes);
        assertEquals("video.mp4", space.largest.get(0).node.name);
        assertEquals("top.txt", space.largest.get(space.largest.size() - 1).node.name);
        for (TerminowFileScan.Entry entry : space.top) assertFalse(entry.node.name.equals("outside"));
    }

}
