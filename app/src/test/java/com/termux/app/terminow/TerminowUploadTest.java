package com.termux.app.terminow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The names of the files that the paperclip uploads to /tmp, which are typed at the prompt. */
public class TerminowUploadTest {

    @Test
    public void namesAreSafeInAShell() {
        assertEquals("report.pdf", TerminowHome.safeUploadName("report.pdf"));
        assertEquals("My_photo_1_.jpg", TerminowHome.safeUploadName("My photo (1).jpg"));
        // Nothing that a shell or a path would read as more than a name
        assertEquals("_rm_-rf_.txt", TerminowHome.safeUploadName("$(rm -rf ~).txt"));
        assertEquals("passwd", TerminowHome.safeUploadName("../../etc/passwd"));
        assertEquals("bashrc", TerminowHome.safeUploadName(".bashrc"));
        assertEquals("rf", TerminowHome.safeUploadName("-rf"));
        assertEquals("upload", TerminowHome.safeUploadName(null));
        assertEquals("upload", TerminowHome.safeUploadName("..."));
        String name = TerminowHome.safeUploadName("ไฟล์.txt");
        assertTrue(name, name.matches("[A-Za-z0-9._-]+"));
        assertTrue(TerminowHome.safeUploadName(new String(new char[300]).replace('\0', 'a') + ".txt").length() <= 120);
    }

    @Test
    public void aNameThatIsTakenGetsANumber() {
        assertEquals("report.pdf", TerminowHome.numberedName("report.pdf", 0));
        assertEquals("report-1.pdf", TerminowHome.numberedName("report.pdf", 1));
        assertEquals("archive.tar-2.gz", TerminowHome.numberedName("archive.tar.gz", 2));
        assertEquals("Makefile-3", TerminowHome.numberedName("Makefile", 3));
    }

}
