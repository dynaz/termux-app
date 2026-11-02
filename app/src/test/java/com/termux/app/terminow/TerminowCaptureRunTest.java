package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Regression tests for the Packet-Capture freeze: {@link TerminowCapture#runBounded} must drain stdout
 * and stderr concurrently (so a child that fills stderr while stdout is being read cannot deadlock) and
 * must bound a process that never exits (so a stuck probe can never wedge the capture worker thread).
 */
public class TerminowCaptureRunTest {

    /** A process that has already produced output and exits immediately; waitFor returns true at once. */
    private static final class FinishedProcess extends Process {
        private final InputStream out, err;
        private final int code;
        FinishedProcess(String stdout, String stderr, int code) {
            this.out = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.err = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.code = code;
        }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return out; }
        @Override public InputStream getErrorStream() { return err; }
        @Override public int waitFor() { return code; }
        @Override public boolean waitFor(long t, TimeUnit u) { return true; }
        @Override public int exitValue() { return code; }
        @Override public boolean isAlive() { return false; }
        @Override public void destroy() { }
    }

    /** A stream whose read blocks until the process is destroyed, then reports EOF — a hung child. */
    private static final class BlockingStream extends InputStream {
        private final CountDownLatch released = new CountDownLatch(1);
        @Override public int read() {
            try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return -1;
        }
        void release() { released.countDown(); }
    }

    /** A process that never finishes on its own: waitFor(timeout) times out; destroy() ends it. */
    private static final class HangingProcess extends Process {
        private final BlockingStream out = new BlockingStream(), err = new BlockingStream();
        private volatile boolean alive = true;
        volatile boolean destroyed = false;
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return out; }
        @Override public InputStream getErrorStream() { return err; }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long t, TimeUnit u) throws InterruptedException {
            Thread.sleep(u.toMillis(t));   // never ready within the bound
            return false;
        }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 137;   // SIGKILL-style exit after destroy
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { destroyed = true; alive = false; out.release(); err.release(); }
    }

    @Test
    public void drainsStdoutAndStderrConcurrentlyWithoutDeadlock() {
        // stderr far larger than any pipe buffer: the old read-stdout-first ordering would deadlock here.
        StringBuilder bigErr = new StringBuilder();
        for (int i = 0; i < 20_000; i++) bigErr.append("stderr noise line ").append(i).append('\n');
        FinishedProcess p = new FinishedProcess("ONE\nTWO\nTHREE\n", bigErr.toString(), 0);
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        int code = TerminowCapture.runBounded(p, 5_000, lines::add);
        assertEquals(0, code);
        assertEquals(List.of("ONE", "TWO", "THREE"), new ArrayList<>(lines));
    }

    @Test(timeout = 5_000)
    public void boundsAProcessThatNeverExitsAndDestroysIt() {
        HangingProcess p = new HangingProcess();
        long start = System.currentTimeMillis();
        int code = TerminowCapture.runBounded(p, 300, null);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("runBounded must have destroyed the stuck process", p.destroyed);
        assertTrue("must return promptly after the timeout, not hang", elapsed < 4_000);
        assertEquals(137, code);   // returned the post-destroy exit code, did not hang
    }

    @Test
    public void capabilityStateReflectsTheProbe() {
        assertEquals(TerminowCapture.CaptureState.CHECKING, TerminowCapture.capabilityState(null, null));
        assertEquals(TerminowCapture.CaptureState.UNSUPPORTED, TerminowCapture.capabilityState(false, null));
        assertEquals(TerminowCapture.CaptureState.CHECKING, TerminowCapture.capabilityState(true, null));
        assertEquals(TerminowCapture.CaptureState.UNSUPPORTED,
            TerminowCapture.capabilityState(true, TerminowCapture.CapturePrivilege.DENIED));
        assertEquals(TerminowCapture.CaptureState.READY,
            TerminowCapture.capabilityState(true, TerminowCapture.CapturePrivilege.OK));
        // NO_DEVICE / UNKNOWN stay READY: another interface may work, so Start is left enabled.
        assertEquals(TerminowCapture.CaptureState.READY,
            TerminowCapture.capabilityState(true, TerminowCapture.CapturePrivilege.NO_DEVICE));
        assertEquals(TerminowCapture.CaptureState.READY,
            TerminowCapture.capabilityState(true, TerminowCapture.CapturePrivilege.UNKNOWN));
    }

    @Test
    public void captureTimeoutExitCountsAsSuccess() {
        assertTrue(TerminowCapture.isCaptureSuccess(0));     // clean exit (size cap hit)
        assertTrue(TerminowCapture.isCaptureSuccess(124));   // timeout stopped tcpdump at the duration cap
        assertFalse(TerminowCapture.isCaptureSuccess(1));
        assertFalse(TerminowCapture.isCaptureSuccess(-1));
    }

    @Test
    public void nullOnLineDrainsWithoutError() {
        FinishedProcess p = new FinishedProcess("ignored stdout\n", "ignored stderr\n", 3);
        int code = TerminowCapture.runBounded(p, 2_000, null);
        assertEquals(3, code);
        assertFalse(Thread.currentThread().isInterrupted());
    }
}
