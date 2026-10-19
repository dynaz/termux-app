package com.termux.app.terminow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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

    /** A tar entry: its header, then its data in blocks of 512 bytes. */
    private static void entry(ByteArrayOutputStream tar, String name, char type, byte[] data) {
        byte[] header = new byte[512];
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(nameBytes, 0, header, 0, Math.min(100, nameBytes.length));
        byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(size, 0, header, 124, size.length);
        header[156] = (byte) type;
        System.arraycopy("ustar".getBytes(StandardCharsets.US_ASCII), 0, header, 257, 5);
        tar.write(header, 0, 512);
        tar.write(data, 0, data.length);
        tar.write(new byte[(512 - data.length % 512) % 512], 0, (512 - data.length % 512) % 512);
    }

    private static byte[] gzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(bytes);
        }
        return gz.toByteArray();
    }

    @Test
    public void theEnvironmentOfABackupIsItsFirstFolder() throws Exception {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "ubuntu/manifest.json", '0', "{}".getBytes(StandardCharsets.UTF_8));
        entry(tar, "ubuntu/rootfs/", '5', new byte[0]);
        assertEquals("ubuntu", TerminowPhoneBackup.containerOf(new ByteArrayInputStream(gzip(tar.toByteArray()))));
        assertEquals("ubuntu", TerminowPhoneBackup.containerOf(new ByteArrayInputStream(tar.toByteArray())));
    }

    @Test
    public void paxAndLongNameHeadersAreSkipped() throws Exception {
        ByteArrayOutputStream pax = new ByteArrayOutputStream();
        entry(pax, "././@PaxHeader", 'x', "30 mtime=1792399990.123456789\n".getBytes(StandardCharsets.UTF_8));
        entry(pax, "debian/manifest.json", '0', new byte[3]);
        assertEquals("debian", TerminowPhoneBackup.containerOf(new ByteArrayInputStream(gzip(pax.toByteArray()))));
        ByteArrayOutputStream gnu = new ByteArrayOutputStream();
        entry(gnu, "././@LongLink", 'L', "my-ubuntu/rootfs/a/very/long/name\0".getBytes(StandardCharsets.UTF_8));
        entry(gnu, "my-ubuntu/rootfs/a/very/lo", '0', new byte[0]);
        assertEquals("my-ubuntu", TerminowPhoneBackup.containerOf(new ByteArrayInputStream(gnu.toByteArray())));
    }

    @Test
    public void whatIsNotABackupHasNoEnvironment() throws Exception {
        assertNull(TerminowPhoneBackup.containerOf(new ByteArrayInputStream("not a backup".getBytes(StandardCharsets.UTF_8))));
        assertNull(TerminowPhoneBackup.containerOf(new ByteArrayInputStream(gzip("short".getBytes(StandardCharsets.UTF_8)))));
        ByteArrayOutputStream bad = new ByteArrayOutputStream();
        entry(bad, "../etc/passwd", '0', new byte[0]);
        assertNull(TerminowPhoneBackup.containerOf(new ByteArrayInputStream(bad.toByteArray())));
    }
}
