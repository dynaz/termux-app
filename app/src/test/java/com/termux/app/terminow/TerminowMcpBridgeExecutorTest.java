package com.termux.app.terminow;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The race-free cancel of a managed run ({@link TerminowMcpBridgeExecutor.Run}): a cancel and the
 * process handle meet under one lock, so a cancel can never be missed whether it arrives before or
 * after the process started.
 */
public class TerminowMcpBridgeExecutorTest {

    /** A process that only records whether it was destroyed. */
    private static final class FakeProcess extends Process {
        volatile boolean destroyed;
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { destroyed = true; }
    }

    @Test
    public void cancelBeforeAttachStopsTheProcess() {
        TerminowMcpBridgeExecutor.Run run = new TerminowMcpBridgeExecutor.Run();
        run.cancel();                               // cancel arrives before the process exists
        assertTrue(run.isCancelRequested());
        FakeProcess process = new FakeProcess();
        assertFalse("attach must report the cancel", run.attach(process));
        assertTrue("the just-started process is stopped", process.destroyed);
    }

    @Test
    public void cancelAfterAttachStopsTheProcess() {
        TerminowMcpBridgeExecutor.Run run = new TerminowMcpBridgeExecutor.Run();
        FakeProcess process = new FakeProcess();
        assertTrue("attach succeeds when no cancel came first", run.attach(process));
        run.cancel();                               // cancel arrives after the process started
        assertTrue(run.isCancelRequested());
        assertTrue("the running process is stopped", process.destroyed);
    }
}
