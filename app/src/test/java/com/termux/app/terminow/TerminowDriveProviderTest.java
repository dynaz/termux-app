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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Google Drive provider against a fake Drive API (appDataFolder). Not verified against real Drive. */
public class TerminowDriveProviderTest {

    private FakeHttpServer mServer;
    private final Map<String, byte[]> mByName = new ConcurrentHashMap<>();   // name -> bytes
    private final Map<String, String> mIdByName = new ConcurrentHashMap<>(); // name -> id
    private final Map<String, String> mNameById = new ConcurrentHashMap<>(); // id -> name
    private final Map<String, Integer> mOrder = new ConcurrentHashMap<>();
    private final AtomicInteger mClock = new AtomicInteger();

    @Before public void start() throws Exception { mServer = new FakeHttpServer(this::handle); }
    @After public void stop() throws Exception { if (mServer != null) mServer.close(); }

    private TerminowDriveProvider provider() {
        return new TerminowDriveProvider(() -> "test-token", mServer.baseUrl());
    }

    private FakeHttpServer.Response handle(FakeHttpServer.Request r) {
        try {
            if (r.path.equals("/upload/drive/v3/files")) {
                String ct = r.header("Content-Type");
                String boundary = ct.substring(ct.indexOf("boundary=") + "boundary=".length());
                String body = r.bodyText();
                String[] parts = body.split("--" + java.util.regex.Pattern.quote(boundary));
                // parts[1] = metadata, parts[2] = media
                String meta = parts[1].substring(parts[1].indexOf("\r\n\r\n") + 4);
                String name = new org.json.JSONObject(meta.trim()).getString("name");
                String media = parts[2].substring(parts[2].indexOf("\r\n\r\n") + 4);
                if (media.endsWith("\r\n")) media = media.substring(0, media.length() - 2);
                String id = "id" + mClock.incrementAndGet();
                mByName.put(name, media.getBytes(StandardCharsets.UTF_8));
                mIdByName.put(name, id);
                mNameById.put(id, name);
                mOrder.put(name, mClock.get());
                return FakeHttpServer.Response.json(200, "{\"id\":\"" + id + "\"}");
            }
            if (r.path.equals("/drive/v3/files")) {
                String q = URLDecoder.decode(r.query, "UTF-8");
                if (q.contains("name contains")) return FakeHttpServer.Response.json(200, listing());
                // lookup: name = '<name>'
                String name = q.substring(q.indexOf("name = '") + 8, q.lastIndexOf("'"));
                String id = mIdByName.get(name);
                return FakeHttpServer.Response.json(200, id == null ? "{\"files\":[]}" : "{\"files\":[{\"id\":\"" + id + "\"}]}");
            }
            if (r.path.startsWith("/drive/v3/files/")) {
                String id = r.path.substring("/drive/v3/files/".length());
                if (r.method.equals("DELETE")) {
                    String name = mNameById.remove(id);
                    if (name != null) { mByName.remove(name); mIdByName.remove(name); }
                    return FakeHttpServer.Response.json(204, "");
                }
                String name = mNameById.get(id);
                byte[] b = name == null ? null : mByName.get(name);
                return b == null ? FakeHttpServer.Response.json(404, "{\"error\":\"no\"}")
                                 : FakeHttpServer.Response.bytes(200, b);
            }
            return FakeHttpServer.Response.json(404, "{\"error\":\"no\"}");
        } catch (Exception e) {
            return FakeHttpServer.Response.json(400, "{\"error\":\"" + e + "\"}");
        }
    }

    private String listing() {
        StringBuilder b = new StringBuilder("{\"files\":[");
        boolean first = true;
        for (Map.Entry<String, byte[]> e : mByName.entrySet()) {
            if (!first) b.append(',');
            first = false;
            String modified = String.format("2026-01-02T03:%02d:05.000Z", mOrder.getOrDefault(e.getKey(), 0));
            b.append("{\"id\":\"").append(mIdByName.get(e.getKey())).append("\",\"name\":\"").append(e.getKey())
             .append("\",\"size\":\"").append(e.getValue().length).append("\",\"modifiedTime\":\"").append(modified).append("\"}");
        }
        return b.append("]}").toString();
    }

    private File tempFile(String content) throws IOException {
        File f = File.createTempFile("gdrive", ".bin");
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void uploadListDownloadDelete() throws Exception {
        TerminowDriveProvider p = provider();
        String key = TerminowCloudBackup.keyFor("ubuntu", "backup-1.tar.gz");
        p.upload(key, tempFile("hello drive"), TerminowBackupProvider.Progress.NONE);

        List<TerminowBackupProvider.Remote> listed = p.list();
        assertEquals(1, listed.size());
        assertEquals(key, listed.get(0).key);
        assertEquals("hello drive".length(), listed.get(0).size);
        assertTrue(listed.get(0).modifiedMillis > 0);

        try (InputStream in = p.open(key)) {
            assertEquals("hello drive", new String(readAll(in), StandardCharsets.UTF_8));
        }
        p.delete(key);
        assertTrue(p.list().isEmpty());
    }

    @Test
    public void refusesKeysOutsidePrefix() throws Exception {
        try { provider().upload("nope/x", tempFile("x"), TerminowBackupProvider.Progress.NONE); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("outside")); }
    }

    @Test
    public void pipelineWorksThroughDrive() throws Exception {
        TerminowDriveProvider p = provider();
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
