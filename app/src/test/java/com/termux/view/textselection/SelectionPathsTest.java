package com.termux.view.textselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SelectionPathsTest {

    @Test public void absoluteAndHomeAndRelativePaths() {
        assertEquals("/root/app.apk", SelectionPaths.find("/root/app.apk"));
        assertEquals("~/notes.txt", SelectionPaths.find("  ~/notes.txt \n"));
        assertEquals("src/main.c", SelectionPaths.find("src/main.c"));
        assertEquals("./build.sh", SelectionPaths.find("./build.sh"));
    }

    @Test public void bareFileNameWithExtensionIsAPath() {
        assertEquals("report.pdf", SelectionPaths.find("report.pdf"));
        assertEquals("photo.PNG", SelectionPaths.find("photo.PNG"));
    }

    @Test public void spacesAndUnicodeAreKept() {
        assertEquals("/sd/My Files/a b.pdf", SelectionPaths.find("/sd/My Files/a b.pdf"));
        assertEquals("/เอกสาร/ไฟล์.pdf", SelectionPaths.find("/เอกสาร/ไฟล์.pdf"));
    }

    @Test public void urlsAndProseAreNotPaths() {
        assertNull(SelectionPaths.find("https://example.com/a.pdf"));
        assertNull(SelectionPaths.find("hello world"));
        assertNull(SelectionPaths.find("just a sentence."));
        assertNull(SelectionPaths.find(""));
        assertNull(SelectionPaths.find(null));
    }

    @Test public void multiLineSelectionIsNotASinglePath() {
        assertNull(SelectionPaths.find("/a/b\n/c/d"));
    }
}
