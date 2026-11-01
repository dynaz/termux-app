package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
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
    private final AtomicInteger mPutAttempts = new AtomicInteger();
    private volatile int mFailFirstPuts = 0;
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
                else { mStore.put(path, body); code = 200; }
            } else if ("GET".equals(method) && query.contains("list-type=2")) {
                boolean page2 = query.contains("continuation-token=");
                code = 200;
                responseBody = (page2 ? mListXmlPage2 : mListXmlPage1).getBytes(StandardCharsets.UTF_8);
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
