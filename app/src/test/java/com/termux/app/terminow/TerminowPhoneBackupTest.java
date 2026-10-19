package com.termux.app.terminow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class TerminowPhoneBackupTest {

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static byte[] archive() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20000; i++) text.append("ubuntu/rootfs/etc/file").append(i).append('\n');
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void aPlainArchiveIsGzipped() throws Exception {
        byte[] tar = archive();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        AtomicLong counted = new AtomicLong();
        TerminowPhoneBackup.copyGzipped(new ByteArrayInputStream(tar), file, counted::addAndGet);
        byte[] written = file.toByteArray();
        assertEquals(0x1f, written[0] & 0xff);
        assertEquals(0x8b, written[1] & 0xff);
        assertArrayEquals(tar, readAll(new GZIPInputStream(new ByteArrayInputStream(written))));
        assertEquals(tar.length, counted.get());
    }

    @Test
    public void aGzippedArchiveIsNotGzippedTwice() throws Exception {
        byte[] tar = archive();
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar);
        }
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        TerminowPhoneBackup.copyGzipped(new ByteArrayInputStream(gz.toByteArray()), file, n -> { });
        assertArrayEquals(gz.toByteArray(), file.toByteArray());
    }

    @Test
    public void anEmptyArchiveIsStillAGzipFile() throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        TerminowPhoneBackup.copyGzipped(new ByteArrayInputStream(new byte[0]), file, n -> { });
        assertEquals(0, readAll(new GZIPInputStream(new ByteArrayInputStream(file.toByteArray()))).length);
    }

    @Test
    public void theSuggestedNameSaysWhatAndWhen() {
        String name = TerminowPhoneBackup.fileName("ubuntu", System.currentTimeMillis());
        assertTrue(name, name.matches("ubuntu-backup-\\d{8}-\\d{4}\\.tar\\.gz"));
    }
}
