package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Dropbox provider against a fake Dropbox API. Not verified against real Dropbox (needs an app token). */
public class TerminowDropboxProviderTest {

    private FakeHttpServer mServer;
    private final Map<String, byte[]> mStore = new ConcurrentHashMap<>();
    private final Map<String, Integer> mOrder = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger mClock = new java.util.concurrent.atomic.AtomicInteger();

    @Before
    public void start() throws Exception {
        mServer = new FakeHttpServer(this::handle);
    }

    @After
    public void stop() throws Exception {
        if (mServer != null) mServer.close();
    }

    private TerminowDropboxProvider provider() {
        return new TerminowDropboxProvider(() -> "test-token", mServer.baseUrl(), mServer.baseUrl());
    }

    private FakeHttpServer.Response handle(FakeHttpServer.Request r) throws IOException {
        try {
            switch (r.path) {
                case "/2/files/upload": {
                    String path = new JSONObject(r.header("Dropbox-API-Arg")).getString("path");
                    mStore.put(path, r.body);
                    mOrder.put(path, mClock.incrementAndGet());
                    return FakeHttpServer.Response.json(200, "{\"name\":\"x\",\"size\":" + r.body.length + "}");
                }
                case "/2/files/download": {
                    String path = new JSONObject(r.header("Dropbox-API-Arg")).getString("path");
                    byte[] b = mStore.get(path);
                    return b == null ? FakeHttpServer.Response.json(409, "{\"error_summary\":\"path/not_found/\"}")
                                     : FakeHttpServer.Response.bytes(200, b);
                }
                case "/2/files/delete_v2": {
                    String path = new JSONObject(r.bodyText()).getString("path");
                    mStore.remove(path);
                    return FakeHttpServer.Response.json(200, "{\"metadata\":{}}");
                }
                case "/2/files/list_folder": {
                    String folder = new JSONObject(r.bodyText()).getString("path");
                    if (mStore.keySet().stream().noneMatch(k -> k.startsWith(folder)))
                        return FakeHttpServer.Response.json(409, "{\"error_summary\":\"path/not_found/\"}");
                    return FakeHttpServer.Response.json(200, listing());
                }
                default:
                    return FakeHttpServer.Response.json(404, "{\"error\":\"no\"}");
            }
        } catch (Exception e) {
            return FakeHttpServer.Response.json(400, "{\"error\":\"" + e + "\"}");
        }
    }

    private String listing() {
        StringBuilder b = new StringBuilder("{\"entries\":[");
        boolean first = true;
        for (Map.Entry<String, byte[]> e : mStore.entrySet()) {
            if (!first) b.append(',');
            first = false;
            int order = mOrder.getOrDefault(e.getKey(), 0);
            String modified = String.format("2026-01-02T03:%02d:05Z", order);   // increasing with upload order
            b.append("{\".tag\":\"file\",\"path_display\":\"").append(e.getKey())
             .append("\",\"size\":").append(e.getValue().length)
             .append(",\"server_modified\":\"").append(modified).append("\"}");
        }
        return b.append("],\"has_more\":false,\"cursor\":\"\"}").toString();
    }

    private File tempFile(String content) throws IOException {
        File f = File.createTempFile("dbx", ".bin");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void uploadListDownloadDelete() throws Exception {
        TerminowDropboxProvider p = provider();
        String key = TerminowCloudBackup.keyFor("ubuntu", "backup-1.tar.gz");
        p.upload(key, tempFile("hello dropbox"), TerminowBackupProvider.Progress.NONE);

        List<TerminowBackupProvider.Remote> listed = p.list();
        assertEquals(1, listed.size());
        assertEquals(key, listed.get(0).key);
        assertEquals("hello dropbox".length(), listed.get(0).size);
        assertTrue(listed.get(0).modifiedMillis > 0);

        try (InputStream in = p.open(key)) {
            assertEquals("hello dropbox", new String(readAll(in), StandardCharsets.UTF_8));
        }
        p.delete(key);
        assertTrue(p.list().isEmpty());
    }

    @Test
    public void listIsEmptyWhenTheFolderDoesNotExist() throws Exception {
        assertTrue(provider().list().isEmpty());
    }

    @Test
    public void refusesKeysOutsidePrefix() throws Exception {
        try { provider().delete("somewhere/else"); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("outside")); }
    }

    @Test
    public void pipelineWorksThroughDropbox() throws Exception {
        TerminowDropboxProvider p = provider();
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
