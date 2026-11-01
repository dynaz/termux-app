package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** OneDrive provider against a fake Microsoft Graph (approot). Not verified against real OneDrive. */
public class TerminowOneDriveProviderTest {

    private FakeHttpServer mServer;
    private final Map<String, byte[]> mStore = new ConcurrentHashMap<>();   // rel path -> bytes
    private final Map<String, Integer> mOrder = new ConcurrentHashMap<>();
    private final AtomicInteger mClock = new AtomicInteger();

    @Before public void start() throws Exception { mServer = new FakeHttpServer(this::handle); }
    @After public void stop() throws Exception { if (mServer != null) mServer.close(); }

    private TerminowOneDriveProvider provider() {
        return new TerminowOneDriveProvider(() -> "test-token", mServer.baseUrl());
    }

    private FakeHttpServer.Response handle(FakeHttpServer.Request r) {
        try {
            final String marker = "/me/drive/special/approot:/";
            if (!r.path.startsWith(marker)) return FakeHttpServer.Response.json(404, "{\"error\":\"no\"}");
            String rest = r.path.substring(marker.length());
            if (rest.endsWith(":/content")) {
                String rel = rest.substring(0, rest.length() - ":/content".length());
                if (r.method.equals("PUT")) {
                    mStore.put(rel, r.body);
                    mOrder.put(rel, mClock.incrementAndGet());
                    return FakeHttpServer.Response.json(201, "{\"id\":\"x\"}");
                }
                byte[] b = mStore.get(rel);
                return b == null ? FakeHttpServer.Response.json(404, "{\"error\":\"itemNotFound\"}")
                                 : FakeHttpServer.Response.bytes(200, b);
            }
            if (rest.endsWith(":/children")) {
                String folder = rest.substring(0, rest.length() - ":/children".length());
                return childrenOf(folder);
            }
            if (r.method.equals("DELETE")) {
                mStore.remove(rest);
                mOrder.remove(rest);
                return FakeHttpServer.Response.json(204, "");
            }
            return FakeHttpServer.Response.json(404, "{\"error\":\"no\"}");
        } catch (Exception e) {
            return FakeHttpServer.Response.json(400, "{\"error\":\"" + e + "\"}");
        }
    }

    private FakeHttpServer.Response childrenOf(String folder) {
        String prefix = folder + "/";
        Set<String> folders = new LinkedHashSet<>();
        StringBuilder files = new StringBuilder();
        boolean any = false;
        for (Map.Entry<String, byte[]> e : mStore.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            any = true;
            String remainder = e.getKey().substring(prefix.length());
            int slash = remainder.indexOf('/');
            if (slash >= 0) {
                folders.add(remainder.substring(0, slash));
            } else {
                if (files.length() > 0) files.append(',');
                String modified = String.format("2026-01-02T03:%02d:05Z", mOrder.getOrDefault(e.getKey(), 0));
                files.append("{\"name\":\"").append(remainder).append("\",\"size\":").append(e.getValue().length)
                     .append(",\"lastModifiedDateTime\":\"").append(modified).append("\",\"file\":{}}");
            }
        }
        if (!any) return FakeHttpServer.Response.json(404, "{\"error\":{\"code\":\"itemNotFound\"}}");
        StringBuilder value = new StringBuilder("{\"value\":[");
        boolean first = true;
        for (String f : folders) {
            if (!first) value.append(','); first = false;
            value.append("{\"name\":\"").append(f).append("\",\"folder\":{\"childCount\":1}}");
        }
        if (files.length() > 0) { if (!first) value.append(','); value.append(files); }
        return FakeHttpServer.Response.json(200, value.append("]}").toString());
    }

    private File tempFile(String content) throws IOException {
        File f = File.createTempFile("onedrive", ".bin");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void uploadListDownloadDelete() throws Exception {
        TerminowOneDriveProvider p = provider();
        String key = TerminowCloudBackup.keyFor("ubuntu", "backup-1.tar.gz");
        p.upload(key, tempFile("hello onedrive"), TerminowBackupProvider.Progress.NONE);

        List<TerminowBackupProvider.Remote> listed = p.list();
        assertEquals(1, listed.size());
        assertEquals(key, listed.get(0).key);
        assertEquals("hello onedrive".length(), listed.get(0).size);
        assertTrue(listed.get(0).modifiedMillis > 0);

        try (InputStream in = p.open(key)) {
            assertEquals("hello onedrive", new String(readAll(in), StandardCharsets.UTF_8));
        }
        p.delete(key);
        assertTrue(p.list().isEmpty());
    }

    @Test
    public void listWalksSubfoldersOfSeveralEnvironments() throws Exception {
        TerminowOneDriveProvider p = provider();
        p.upload(TerminowCloudBackup.keyFor("ubuntu", "a.tar.gz"), tempFile("a"), TerminowBackupProvider.Progress.NONE);
        p.upload(TerminowCloudBackup.keyFor("ubuntu", "b.tar.gz"), tempFile("b"), TerminowBackupProvider.Progress.NONE);
        p.upload(TerminowCloudBackup.keyFor("debian", "c.tar.gz"), tempFile("c"), TerminowBackupProvider.Progress.NONE);
        assertEquals(3, p.list().size());
    }

    @Test
    public void listIsEmptyWhenNothingStored() throws Exception {
        assertTrue(provider().list().isEmpty());
    }

    @Test
    public void refusesKeysOutsidePrefix() throws Exception {
        try { provider().open("nope/x"); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("outside")); }
    }

    @Test
    public void pipelineWorksThroughOneDrive() throws Exception {
        TerminowOneDriveProvider p = provider();
        for (String n : new String[]{"b1.tar.gz", "b2.tar.gz"})
            p.upload(TerminowCloudBackup.keyFor("ubuntu", n), tempFile(n), TerminowBackupProvider.Progress.NONE);
        File fresh = tempFile("fresh backup");
        String key = TerminowCloudBackup.keyFor("ubuntu", "b3.tar.gz");
        List<String> deleted = TerminowCloudBackup.uploadVerifyTrim(p, key, fresh, 2, TerminowBackupProvider.Progress.NONE);
        assertEquals(1, deleted.size());
        assertEquals(2, p.list().size());
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
