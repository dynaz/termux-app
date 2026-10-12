package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.app.terminow.files.TerminowDeviceFs;
import com.termux.app.terminow.files.TerminowFileScan;
import com.termux.app.terminow.files.TerminowFs;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Shared storage volumes: protected folders, the scan scope, and folders picked with SAF. */
public class TerminowDeviceFsTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    @Test
    public void otherAppsPrivateFoldersAreProtectedNotEmpty() throws Exception {
        File volume = mFolder.newFolder("emulated0");
        assertTrue(new File(volume, "Download").mkdirs());
        Files.write(new File(volume, "Download/report.pdf").toPath(), new byte[100]);
        assertTrue(new File(volume, "Android/data/com.example.app").mkdirs());
        Files.write(new File(volume, "Android/data/com.example.app/secret.db").toPath(), new byte[5000]);
        assertTrue(new File(volume, "Android/obb").mkdirs());
        assertTrue(new File(volume, "Android/media").mkdirs());
        TerminowDeviceFs fs = new TerminowDeviceFs(volume, "device:primary", "Internal Storage");

        TerminowFs.Node android = fs.find(fs.getRoot(), "Android");
        TerminowFs.Node data = fs.find(android, "data");
        try {
            fs.list(data, new TerminowFs.Cancel());
            fail("Android/data was listed");
        } catch (TerminowDeviceFs.ProtectedException expected) {
            assertTrue(expected.getMessage().contains("protected by Android"));
        }
        try {
            fs.list(fs.find(android, "obb"), new TerminowFs.Cancel());
            fail("Android/obb was listed");
        } catch (TerminowDeviceFs.ProtectedException expected) {
            // Expected
        }
        // Shared media folders are not protected
        assertEquals(0, fs.list(fs.find(android, "media"), new TerminowFs.Cancel()).size());
        assertTrue(TerminowDeviceFs.isProtected("ANDROID/Data/x"));
        assertFalse(TerminowDeviceFs.isProtected("Android/media"));
        assertFalse(TerminowDeviceFs.isProtected("Documents/Android/data"));
        assertEquals("Internal Storage/Download", fs.getDisplayPath(java.util.Arrays.asList(fs.getRoot(), fs.find(fs.getRoot(), "Download"))));
    }

    @Test
    public void analyzerReportsWhatItCouldNotScan() throws Exception {
        File volume = mFolder.newFolder("emulated0");
        assertTrue(new File(volume, "Android/data/com.example.app").mkdirs());
        Files.write(new File(volume, "Android/data/com.example.app/secret.db").toPath(), new byte[5000]);
        assertTrue(new File(volume, "DCIM").mkdirs());
        Files.write(new File(volume, "DCIM/photo.jpg").toPath(), new byte[300]);
        TerminowDeviceFs fs = new TerminowDeviceFs(volume, "device:primary", "Internal Storage");

        TerminowFileScan.Space space = TerminowFileScan.analyze(fs, fs.getRoot(), new TerminowFs.Cancel(), null);
        // The private files of the other app are neither counted nor claimed as scanned
        assertEquals(300, space.totalBytes);
        assertTrue(space.incomplete);
        assertEquals(Collections.singletonList("Internal Storage/Android/data"), space.unreadable);

        List<TerminowFileScan.Match> results = new ArrayList<>();
        TerminowFileScan.find(fs, Collections.singletonList(fs.getRoot()), node -> true, true, results, new TerminowFs.Cancel(), null);
        assertEquals(1, results.size());
        assertEquals("photo.jpg", results.get(0).node.name);
    }

    @Test
    public void safFoldersAreMatchedToVolumes() {
        assertEquals("Download/Work", TerminowDeviceFs.treeDocumentPath("primary:Download/Work", "primary"));
        assertEquals("", TerminowDeviceFs.treeDocumentPath("primary:", "primary"));
        assertEquals("Music", TerminowDeviceFs.treeDocumentPath("1A2B-3C4D:Music/", "1a2b-3c4d"));
        assertNull(TerminowDeviceFs.treeDocumentPath("1A2B-3C4D:Music", "primary"));
        assertNull(TerminowDeviceFs.treeDocumentPath("home:Documents", "primary"));
        assertNull(TerminowDeviceFs.treeDocumentPath("nocolon", "primary"));
    }

}
