package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.termux.app.terminow.files.TerminowFs;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TerminowSftpBrowserTest {

    private static TerminowFs.Node dir(String name, long modified) {
        return new TerminowFs.Node("/root/" + name, name, true, false, -1, modified, null);
    }

    private static TerminowFs.Node file(String name, long size, long modified) {
        return new TerminowFs.Node("/root/" + name, name, false, false, size, modified, null);
    }

    private static List<String> names(List<TerminowFs.Node> nodes) {
        List<String> names = new ArrayList<>();
        for (TerminowFs.Node node : nodes) names.add(node.name);
        return names;
    }

    private final List<TerminowFs.Node> entries = Arrays.asList(
        file("sshd_config.bak", 3400, 300), dir("snap", 100), file("00-installer.yaml.bak", 117, 50),
        dir("Downloads", 500), file(".bashrc", 3800, 400), dir(".cache", 600), file("archive.tar.gz", 580100, 200));

    @Test
    public void foldersComeFirstThenByNameIgnoringCase() {
        assertEquals(Arrays.asList("Downloads", "snap", "00-installer.yaml.bak", "archive.tar.gz", "sshd_config.bak"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_NAME, false, false, "")));
    }

    @Test
    public void hiddenFilesOnlyWhenShown() {
        assertEquals(Arrays.asList(".cache", "Downloads", "snap", ".bashrc", "00-installer.yaml.bak", "archive.tar.gz", "sshd_config.bak"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_NAME, false, true, "")));
    }

    @Test
    public void descendingKeepsFoldersFirst() {
        assertEquals(Arrays.asList("snap", "Downloads", "sshd_config.bak", "archive.tar.gz", "00-installer.yaml.bak"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_NAME, true, false, "")));
    }

    @Test
    public void sortsByDateSizeAndKind() {
        assertEquals(Arrays.asList("Downloads", "snap", "sshd_config.bak", "archive.tar.gz", "00-installer.yaml.bak"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_DATE, true, false, "")));
        assertEquals(Arrays.asList("Downloads", "snap", "00-installer.yaml.bak", "sshd_config.bak", "archive.tar.gz"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_SIZE, false, false, "")));
        // By extension: .bak, .bak, .gz
        assertEquals(Arrays.asList("Downloads", "snap", "00-installer.yaml.bak", "sshd_config.bak", "archive.tar.gz"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_KIND, false, false, "")));
    }

    @Test
    public void searchMatchesPartOfTheNameIgnoringCase() {
        assertEquals(Arrays.asList("00-installer.yaml.bak", "sshd_config.bak"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_NAME, false, false, " BAK ")));
        assertEquals(Arrays.asList("Downloads"),
            names(TerminowSftpBrowser.arrange(entries, TerminowStore.SFTP_SORT_NAME, false, false, "down")));
    }

    @Test
    public void sizesLikeTermius() {
        assertEquals("117 bytes", TerminowSftpBrowser.formatSize(117, "%1$d bytes"));
        assertEquals("999 bytes", TerminowSftpBrowser.formatSize(999, "%1$d bytes"));
        assertEquals("3.4 kB", TerminowSftpBrowser.formatSize(3400, "%1$d bytes"));
        assertEquals("580.1 kB", TerminowSftpBrowser.formatSize(580100, "%1$d bytes"));
        assertEquals("2.0 GB", TerminowSftpBrowser.formatSize(2_000_000_000L, "%1$d bytes"));
    }

    @Test
    public void parentPaths() {
        assertEquals("/", TerminowSftpBrowser.parentPath("/root"));
        assertEquals("/home/user", TerminowSftpBrowser.parentPath("/home/user/www/"));
        assertNull(TerminowSftpBrowser.parentPath("/"));
    }
}
