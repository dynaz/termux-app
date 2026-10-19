package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class TerminowPhoneBackupTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aPercentageIsOnlyGivenWithATotalAndNeverAHundredBeforeTheEnd() {
        assertEquals(-1, TerminowPhoneBackup.percent(500, 0));
        assertEquals(50, TerminowPhoneBackup.percent(500, 1000));
        assertEquals(99, TerminowPhoneBackup.percent(1000, 1000));
        assertEquals(99, TerminowPhoneBackup.percent(5000, 1000));
    }

    @Test
    public void sizesAndTimesAreReadable() {
        assertEquals("512 B", TerminowPhoneBackup.human(512));
        assertEquals("1.5 KB", TerminowPhoneBackup.human(1536));
        assertEquals("120 MB", TerminowPhoneBackup.human(120L << 20));
        assertEquals("0:05", TerminowPhoneBackup.elapsed(5_400));
        assertEquals("2:03", TerminowPhoneBackup.elapsed(123_000));
        assertEquals("1:00:01", TerminowPhoneBackup.elapsed(3_601_000));
    }

    @Test
    public void theEnvironmentThatIsThereIsKeptAsideUnderANameThatIsFree() throws Exception {
        File containers = temp.newFolder("containers");
        String first = TerminowPhoneBackup.asideName("ubuntu", containers);
        assertTrue(first, first.startsWith("ubuntu-before-restore-"));
        assertTrue(com.termux.app.terminow.linux.TerminowLinuxRegistry.isValidAlias(first));
        assertTrue(new File(containers, first).mkdirs());
        String second = TerminowPhoneBackup.asideName("ubuntu", containers);
        assertTrue(second, !second.equals(first) && second.startsWith(first));
        assertTrue(com.termux.app.terminow.linux.TerminowLinuxRegistry.isValidAlias(
            TerminowPhoneBackup.asideName("a-very-long-name-of-an-environment-that-is-sixty-four-chars-ok", containers)));
    }

    @Test
    public void theSuggestedNameSaysWhatAndWhen() {
        String full = TerminowBackupArchive.fileName("ubuntu", TerminowBackupArchive.TYPE_FULL, System.currentTimeMillis());
        assertTrue(full, full.matches("ubuntu-backup-\\d{8}-\\d{4}\\.tar\\.gz"));
        String folders = TerminowBackupArchive.fileName("ubuntu", TerminowBackupArchive.TYPE_FOLDERS, System.currentTimeMillis());
        assertTrue(folders, folders.matches("ubuntu-folders-\\d{8}-\\d{4}\\.tar\\.gz"));
    }

    @Test
    public void aFileNameTheUserTypedGetsTarGzAndNoSlash() {
        assertEquals("mine.tar.gz", TerminowBackupUi.fileNameOf("mine"));
        assertEquals("mine.tar.gz", TerminowBackupUi.fileNameOf(" mine.tar "));
        assertEquals("mine.tar.gz", TerminowBackupUi.fileNameOf("mine.tar.gz"));
        assertNull(TerminowBackupUi.fileNameOf("a/b"));
        assertNull(TerminowBackupUi.fileNameOf(""));
        assertNull(TerminowBackupUi.fileNameOf(".hidden"));
    }
}
