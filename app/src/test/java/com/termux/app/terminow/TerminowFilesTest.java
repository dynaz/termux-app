package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TerminowFilesTest {

    @Test
    public void importedFileNamesNeedNoQuoting() {
        assertEquals("my_photo__1_.jpg", TerminowFiles.getSafeName("my photo (1).jpg"));
        assertEquals("__rm_-rf_x_", TerminowFiles.getSafeName("$(rm -rf x)"));
        assertEquals("file", TerminowFiles.getSafeName(null));
        assertEquals("file", TerminowFiles.getSafeName("..."));
        assertEquals("passwd", TerminowFiles.getSafeName("../passwd").replaceAll("^_", ""));
        assertEquals(120, TerminowFiles.getSafeName(new String(new char[300]).replace('\0', 'a')).length());
    }

}
