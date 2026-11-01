package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Integration of the three layers a backup file is made of — tar archive, gzip, and the optional
 * encryption — proving encryption is transparent to the format and that an unencrypted (v1) file and
 * an encrypted one inspect to the same thing. This is the closest to an end-to-end backup that runs
 * without a device: it builds a real folders archive (regular file, executable, hidden config, nested
 * project file) with the production {@link TerminowBackupArchive} writer, then reads it back through
 * {@link TerminowBackupArchive#inspect}. It does not run proot-distro, so it is not a device E2E.
 */
public class TerminowBackupArchiveCryptoTest {

    /** gzip(manifest + a few file entries + tar end), exactly what the backup writer produces. */
    private static byte[] foldersBackupGzip() throws IOException {
        long t = 1_767_000_000L;   // seconds
        TerminowBackupArchive.Manifest m = new TerminowBackupArchive.Manifest();
        m.type = TerminowBackupArchive.TYPE_FOLDERS;
        m.alias = "ubuntu";
        m.distro = "ubuntu 24.04";
        m.architecture = "aarch64";
        m.created = t * 1000;
        m.sources = Arrays.asList("/home/u/project", "/home/u/.config");
        m.app = "TermiNow test";

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        GZIPOutputStream gz = new GZIPOutputStream(raw);
        TerminowBackupArchive.writeFileEntry(gz, TerminowBackupArchive.MANIFEST,
            m.toJson().getBytes(StandardCharsets.UTF_8), 0644, t);
        TerminowBackupArchive.writeFileEntry(gz, "home/u/project/notes.txt", "hello world".getBytes(StandardCharsets.UTF_8), 0644, t);
        TerminowBackupArchive.writeFileEntry(gz, "home/u/project/run.sh", "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8), 0755, t);
        TerminowBackupArchive.writeFileEntry(gz, "home/u/project/src/main.c", "int main(){return 0;}".getBytes(StandardCharsets.UTF_8), 0644, t);
        TerminowBackupArchive.writeFileEntry(gz, "home/u/.config/app.conf", "token=secret".getBytes(StandardCharsets.UTF_8), 0600, t);
        gz.write(new byte[1024]);   // two zero blocks: the end of a tar archive
        gz.finish();
        gz.close();
        return raw.toByteArray();
    }

    private static TerminowBackupArchive.Inspection inspectGzip(InputStream rawBytes) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(rawBytes)) {
            return TerminowBackupArchive.inspect(gz, null);
        }
    }

    @Test
    public void plainV1BackupInspects() throws Exception {
        TerminowBackupArchive.Inspection in = inspectGzip(new ByteArrayInputStream(foldersBackupGzip()));
        assertNull("no problem", in.problem);
        assertTrue("manifest read", in.manifest != null);
        assertEquals(TerminowBackupArchive.TYPE_FOLDERS, in.manifest.type);
        assertEquals("ubuntu", in.manifest.alias);
        assertEquals("manifest + 4 files", 5, in.entries);
        assertTrue("archive end seen", in.complete);
    }

    @Test
    public void encryptedBackupInspectsIdenticallyAfterDecryption() throws Exception {
        byte[] plainGz = foldersBackupGzip();

        // Encrypt the whole gzip stream, exactly as TerminowPhoneBackup does at the file level.
        ByteArrayOutputStream container = new ByteArrayOutputStream();
        try (java.io.OutputStream enc = TerminowBackupCrypto.encryptingStream(container, "s3-backup-pw".toCharArray())) {
            enc.write(plainGz);
        }
        byte[] encrypted = container.toByteArray();
        assertTrue("has the encrypted magic", TerminowBackupCrypto.looksEncrypted(encrypted));
        assertTrue("encryption changed the bytes", !Arrays.equals(plainGz, encrypted));

        InputStream decrypted = TerminowBackupCrypto.decryptingStream(
            new ByteArrayInputStream(encrypted), "s3-backup-pw".toCharArray());
        TerminowBackupArchive.Inspection in = inspectGzip(decrypted);
        assertNull(in.problem);
        assertEquals(TerminowBackupArchive.TYPE_FOLDERS, in.manifest.type);
        assertEquals("ubuntu", in.manifest.alias);
        assertEquals(5, in.entries);
        assertTrue(in.complete);
    }

    @Test
    public void wrongPasswordCannotInspectAnEncryptedBackup() throws Exception {
        ByteArrayOutputStream container = new ByteArrayOutputStream();
        try (java.io.OutputStream enc = TerminowBackupCrypto.encryptingStream(container, "right".toCharArray())) {
            enc.write(foldersBackupGzip());
        }
        try {
            InputStream decrypted = TerminowBackupCrypto.decryptingStream(
                new ByteArrayInputStream(container.toByteArray()), "wrong".toCharArray());
            inspectGzip(decrypted);
            fail("a wrong password must not inspect");
        } catch (IOException expected) {
            // BadPasswordException (first chunk) or a gzip error from undecryptable bytes
        }
    }
}
