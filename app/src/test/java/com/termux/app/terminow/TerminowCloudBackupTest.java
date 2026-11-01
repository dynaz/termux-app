package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Content-integrity verification + preserve-on-failure in the cloud upload pipeline. */
public class TerminowCloudBackupTest {

    /** In-memory provider that can tamper with what it stores, to exercise verification. */
    private static final class MemProvider implements TerminowBackupProvider {
        final Map<String, byte[]> store = new LinkedHashMap<>();
        final Map<String, Long> modified = new LinkedHashMap<>();
        int clock;
        /** 0 = store faithfully, 1 = flip a byte (same size), 2 = append a byte (size differs). */
        int tamper = 0;

        @Override public String id() { return "mem"; }
        @Override public String displayName() { return "Memory"; }

        @Override public void upload(String key, File file, Progress p) throws IOException {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (tamper == 1 && bytes.length > 0) bytes[0] ^= 0xFF;
            else if (tamper == 2) { byte[] b2 = new byte[bytes.length + 1]; System.arraycopy(bytes, 0, b2, 0, bytes.length); bytes = b2; }
            store.put(key, bytes);
            modified.put(key, (long) (++clock));
        }

        @Override public InputStream open(String key) throws IOException {
            byte[] b = store.get(key);
            if (b == null) throw new IOException("not found: " + key);
            return new ByteArrayInputStream(b);
        }

        @Override public void delete(String key) { store.remove(key); modified.remove(key); }

        @Override public List<Remote> list() {
            List<Remote> out = new ArrayList<>();
            for (Map.Entry<String, byte[]> e : store.entrySet())
                out.add(new Remote(e.getKey(), e.getValue().length, modified.getOrDefault(e.getKey(), 0L)));
            return out;
        }
    }

    private static File tempFile(String content) throws IOException {
        File f = File.createTempFile("cloudbk", ".bin");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void verifiesContentAndTrimsOnSuccess() throws Exception {
        MemProvider p = new MemProvider();
        p.upload(TerminowCloudBackup.keyFor("ubuntu", "b1.tar.gz"), tempFile("one"), TerminowBackupProvider.Progress.NONE);
        p.upload(TerminowCloudBackup.keyFor("ubuntu", "b2.tar.gz"), tempFile("two"), TerminowBackupProvider.Progress.NONE);
        String key = TerminowCloudBackup.keyFor("ubuntu", "b3.tar.gz");
        List<String> deleted = TerminowCloudBackup.uploadVerifyTrim(p, key, tempFile("three"), 2, TerminowBackupProvider.Progress.NONE);
        assertEquals(1, deleted.size());
        assertEquals(2, p.list().size());
        assertTrue(p.store.containsKey(key));   // the newest is kept
    }

    @Test
    public void rejectsCorruptedUploadAndPreservesEarlierBackups() throws Exception {
        MemProvider p = new MemProvider();
        String good1 = TerminowCloudBackup.keyFor("ubuntu", "b1.tar.gz");
        String good2 = TerminowCloudBackup.keyFor("ubuntu", "b2.tar.gz");
        p.upload(good1, tempFile("one"), TerminowBackupProvider.Progress.NONE);
        p.upload(good2, tempFile("two"), TerminowBackupProvider.Progress.NONE);

        p.tamper = 1;   // flip a byte on the next upload — same length, different content
        String key = TerminowCloudBackup.keyFor("ubuntu", "b3.tar.gz");
        try {
            TerminowCloudBackup.uploadVerifyTrim(p, key, tempFile("three"), 1, TerminowBackupProvider.Progress.NONE);
            fail("expected a checksum failure");
        } catch (IOException e) {
            assertTrue(e.getMessage().toLowerCase().contains("checksum") || e.getMessage().toLowerCase().contains("match"));
        }
        // Earlier good backups survive, retention did NOT run, and the corrupt object is gone.
        assertTrue(p.store.containsKey(good1));
        assertTrue(p.store.containsKey(good2));
        assertFalse(p.store.containsKey(key));
        assertEquals(2, p.list().size());
    }

    @Test
    public void rejectsWrongSizeAndPreservesEarlierBackups() throws Exception {
        MemProvider p = new MemProvider();
        String good = TerminowCloudBackup.keyFor("ubuntu", "b1.tar.gz");
        p.upload(good, tempFile("one"), TerminowBackupProvider.Progress.NONE);

        p.tamper = 2;   // append a byte — size differs
        String key = TerminowCloudBackup.keyFor("ubuntu", "b2.tar.gz");
        try {
            TerminowCloudBackup.uploadVerifyTrim(p, key, tempFile("two"), 1, TerminowBackupProvider.Progress.NONE);
            fail("expected a size failure");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("bytes"));
        }
        assertTrue(p.store.containsKey(good));
        assertFalse(p.store.containsKey(key));
    }
}
