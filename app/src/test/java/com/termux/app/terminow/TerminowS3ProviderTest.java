package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * S3 provider: SigV4 against AWS's published vector, and the HTTP/retry/list/delete/download flow
 * against a minimal in-process HTTP server (pure {@link ServerSocket}, since Android's unit-test
 * classpath has no {@code com.sun.net.httpserver}). This does not prove compatibility with a real
 * S3 account.
 */
public class TerminowS3ProviderTest {

    private ServerSocket mSocket;
    private Thread mAccept;
    private final ConcurrentHashMap<String, byte[]> mStore = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> mModified = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong mClock = new java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L);
    private final AtomicInteger mPutAttempts = new AtomicInteger();
    private volatile int mFailFirstPuts = 0;
    private volatile int mListSizeDelta = 0;   // when non-zero, list reports a wrong Size (to test verify)
    private volatile String mListXmlPage1, mListXmlPage2;

    @Before
    public void startServer() throws Exception {
        mSocket = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"));
        mAccept = new Thread(this::acceptLoop);
        mAccept.setDaemon(true);
        mAccept.start();
    }

    @After
    public void stopServer() throws Exception {
        if (mSocket != null) mSocket.close();
    }

    private TerminowS3Provider provider() {
        TerminowS3Provider.Config config = new TerminowS3Provider.Config(
            "127.0.0.1:" + mSocket.getLocalPort(), false, "us-east-1", "mybucket",
            "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", true);
        return new TerminowS3Provider(config);
    }

    // ---------------------------------------------------------------------------------------
    // A tiny HTTP/1.1 server that behaves enough like S3 for the provider.

    private void acceptLoop() {
        while (!mSocket.isClosed()) {
            try {
                Socket s = mSocket.accept();
                new Thread(() -> handle(s)).start();
            } catch (IOException e) {
                return;   // socket closed
            }
        }
    }

    private void handle(Socket s) {
        try (Socket socket = s) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts[1];
            String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
            String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";

            int contentLength = 0;
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length"))
                    contentLength = Integer.parseInt(header.substring(colon + 1).trim());
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n < 0) break;
                read += n;
            }

            int code;
            byte[] responseBody = new byte[0];
            if ("PUT".equals(method)) {
                int attempt = mPutAttempts.incrementAndGet();
                if (attempt <= mFailFirstPuts) { code = 500; responseBody = "<Error>slow down</Error>".getBytes(); }
                else { mStore.put(path, body); mModified.put(path, mClock.addAndGet(1000)); code = 200; }
            } else if ("GET".equals(method) && query.contains("list-type=2")) {
                boolean page2 = query.contains("continuation-token=");
                code = 200;
                // Canned XML drives the pagination test; otherwise list reflects what is actually stored.
                String xml = mListXmlPage1 != null ? (page2 ? mListXmlPage2 : mListXmlPage1) : listXmlFromStore();
                responseBody = xml.getBytes(StandardCharsets.UTF_8);
            } else if ("GET".equals(method)) {
                byte[] stored = mStore.get(path);
                if (stored == null) { code = 404; responseBody = "<Error>NoSuchKey</Error>".getBytes(); }
                else { code = 200; responseBody = stored; }
            } else if ("DELETE".equals(method)) {
                mStore.remove(path);
                code = 204;
            } else {
                code = 405;
            }
            writeResponse(out, code, responseBody);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Build a ListObjectsV2 body from what is actually stored under /mybucket/terminow-backups/. */
    private String listXmlFromStore() {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><ListBucketResult><IsTruncated>false</IsTruncated>");
        String prefix = "/mybucket/" + TerminowBackupProvider.PREFIX;
        java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        for (java.util.Map.Entry<String, byte[]> e : mStore.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String key = e.getKey().substring("/mybucket/".length());
            long modified = mModified.getOrDefault(e.getKey(), 0L);
            xml.append("<Contents><Key>").append(key).append("</Key><Size>").append(e.getValue().length + mListSizeDelta)
               .append("</Size><LastModified>").append(iso.format(new java.util.Date(modified)))
               .append("</LastModified></Contents>");
        }
        return xml.append("</ListBucketResult>").toString();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
        }
        if (c == -1 && out.size() == 0) return null;
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeResponse(OutputStream out, int code, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " X\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        if (body.length > 0) out.write(body);
        out.flush();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    // ---------------------------------------------------------------------------------------

    @Test
    public void sigV4MatchesTheAwsDocumentedVector() {
        // AWS SigV4 docs, "GET Object" single-chunk example.
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", "examplebucket.s3.amazonaws.com");
        headers.put("range", "bytes=0-9");
        headers.put("x-amz-content-sha256", TerminowAwsSigV4.EMPTY_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");
        String auth = TerminowAwsSigV4.authorizationWithHash("GET", "s3", "us-east-1", "/test.txt", "",
            headers, TerminowAwsSigV4.EMPTY_SHA256, "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "20130524T000000Z", "20130524");
        assertTrue("SignedHeaders", auth.contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date"));
        assertTrue("signature\n" + auth,
            auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"));
    }

    @Test
    public void uploadThenDownloadRoundTrips() throws Exception {
        File file = File.createTempFile("backup", ".bin");
        byte[] content = "a gzipped+encrypted backup".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), content);
        String key = TerminowBackupProvider.PREFIX + "ubuntu/backup-20260101-0101.tar.gz";

        List<Long> progress = new ArrayList<>();
        provider().upload(key, file, new TerminowBackupProvider.Progress() {
            @Override public void onProgress(long sent, long total) { progress.add(sent); }
            @Override public boolean cancelled() { return false; }
        });
        assertEquals(content.length, (long) progress.get(progress.size() - 1));

        try (InputStream in = provider().open(key)) {
            assertEquals(new String(content), new String(readAll(in)));
        }
    }

    @Test
    public void uploadRetriesAfterAServerError() throws Exception {
        mFailFirstPuts = 1;          // first PUT returns 500, retry succeeds
        File file = File.createTempFile("backup", ".bin");
        Files.write(file.toPath(), new byte[1234]);
        String key = TerminowBackupProvider.PREFIX + "ubuntu/b.tar.gz";
        provider().upload(key, file, TerminowBackupProvider.Progress.NONE);
        assertEquals(2, mPutAttempts.get());
        assertTrue(mStore.containsKey("/mybucket/" + key));
    }

    @Test
    public void listReturnsOnlyOurPrefixAcrossPages() throws Exception {
        mListXmlPage1 = "<?xml version=\"1.0\"?><ListBucketResult>"
            + "<IsTruncated>true</IsTruncated><NextContinuationToken>TOKEN2</NextContinuationToken>"
            + "<Contents><Key>terminow-backups/ubuntu/a.tar.gz</Key><Size>100</Size>"
            + "<LastModified>2026-01-02T03:04:05.000Z</LastModified></Contents>"
            + "<Contents><Key>someone-elses-file.txt</Key><Size>9</Size>"
            + "<LastModified>2026-01-02T03:04:05.000Z</LastModified></Contents>"
            + "</ListBucketResult>";
        mListXmlPage2 = "<?xml version=\"1.0\"?><ListBucketResult>"
            + "<IsTruncated>false</IsTruncated>"
            + "<Contents><Key>terminow-backups/ubuntu/b.tar.gz.enc</Key><Size>200</Size>"
            + "<LastModified>2026-01-03T06:07:08Z</LastModified></Contents>"
            + "</ListBucketResult>";

        List<TerminowBackupProvider.Remote> all = provider().list();
        assertEquals("only our-prefix keys, both pages", 2, all.size());
        assertEquals("terminow-backups/ubuntu/a.tar.gz", all.get(0).key);
        assertEquals(100, all.get(0).size);
        assertTrue(all.get(0).modifiedMillis > 0);
        assertEquals("terminow-backups/ubuntu/b.tar.gz.enc", all.get(1).key);
        assertTrue("LastModified without millis parses", all.get(1).modifiedMillis > 0);
    }

    @Test
    public void deleteRemovesTheObject() throws Exception {
        String key = TerminowBackupProvider.PREFIX + "ubuntu/old.tar.gz";
        mStore.put("/mybucket/" + key, new byte[10]);
        provider().delete(key);
        assertTrue(!mStore.containsKey("/mybucket/" + key));
    }

    @Test
    public void cloudPipelineUploadsVerifiesAndTrimsToRetain() throws Exception {
        TerminowS3Provider provider = provider();
        // Three earlier backups already in the bucket (oldest to newest by upload order).
        for (String n : new String[]{"backup-1.tar.gz", "backup-2.tar.gz", "backup-3.tar.gz"}) {
            File f = File.createTempFile(n, "");
            Files.write(f.toPath(), ("content of " + n).getBytes());
            provider.upload(TerminowCloudBackup.keyFor("ubuntu", n), f, TerminowBackupProvider.Progress.NONE);
        }
        // A fresh backup goes up through the real pipeline, keeping only the newest 2.
        File fresh = File.createTempFile("backup-4.tar.gz", "");
        Files.write(fresh.toPath(), "the newest backup".getBytes());
        String freshKey = TerminowCloudBackup.keyFor("ubuntu", "backup-4.tar.gz");
        List<String> deleted = TerminowCloudBackup.uploadVerifyTrim(provider, freshKey, fresh, 2, TerminowBackupProvider.Progress.NONE);

        assertEquals("two oldest trimmed", 2, deleted.size());
        assertTrue(deleted.contains(TerminowCloudBackup.keyFor("ubuntu", "backup-1.tar.gz")));
        assertTrue(deleted.contains(TerminowCloudBackup.keyFor("ubuntu", "backup-2.tar.gz")));
        // The fresh one and the next-newest survive; the fresh one is never a deletion candidate.
        assertTrue(mStore.containsKey("/mybucket/" + freshKey));
        assertTrue(mStore.containsKey("/mybucket/" + TerminowCloudBackup.keyFor("ubuntu", "backup-3.tar.gz")));
        assertTrue(!deleted.contains(freshKey));
    }

    @Test
    public void cloudPipelineFailsVerifyWhenTheStoredSizeDisagrees() throws Exception {
        mListSizeDelta = 1;   // the storage reports a size one byte off from what we sent
        File f = File.createTempFile("backup", ".bin");
        Files.write(f.toPath(), new byte[2048]);
        String key = TerminowCloudBackup.keyFor("ubuntu", "only.tar.gz");
        try {
            TerminowCloudBackup.uploadVerifyTrim(provider(), key, f, 5, TerminowBackupProvider.Progress.NONE);
            fail("verify should reject a size mismatch");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("size")
                || expected.getMessage().toLowerCase(Locale.ROOT).contains("bytes"));
        }
        // Verify failed, so the unverified object is removed (preserve-on-failure) and nothing was trimmed.
        assertFalse(mStore.containsKey("/mybucket/" + key));
    }

    @Test
    public void refusesAnyKeyOutsideOurPrefix() throws Exception {
        File file = File.createTempFile("key", ".bin");
        Files.write(file.toPath(), new byte[1]);
        for (String bad : new String[]{"not-ours/evil", "../escape", ""}) {
            try { provider().upload(bad, file, TerminowBackupProvider.Progress.NONE); fail("upload " + bad); }
            catch (IOException expected) { assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("outside")); }
            try { provider().delete(bad); fail("delete " + bad); }
            catch (IOException expected) { assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("outside")); }
            try { provider().open(bad); fail("open " + bad); }
            catch (IOException expected) { assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("outside")); }
        }
    }
}
