package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Per-environment install queue: enqueue instead of reject, no duplicates, next starts when one ends. */
public class TerminowToolInstallerQueueTest {

    /** A process that produces no output and blocks in waitFor until released, so a job stays RUNNING. */
    private static final class FakeProcess extends Process {
        final CountDownLatch done = new CountDownLatch(1);
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() throws InterruptedException { done.await(); return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { done.countDown(); }
    }

    private final Map<String, FakeProcess> started = new ConcurrentHashMap<>();
    private final TerminowToolInstaller.Runner runner = (alias, user, script, args) -> {
        FakeProcess p = new FakeProcess();
        started.put(alias + "|" + (args.length > 0 ? args[0] : "plan") + "|" + System.nanoTime(), p);
        return p;
    };

    @Before public void setUp() { TerminowToolInstaller.reset(); }
    @After public void tearDown() {
        for (FakeProcess p : started.values()) p.done.countDown();
        TerminowToolInstaller.reset();
    }

    private static boolean waitState(TerminowToolInstaller.Job job, TerminowToolInstaller.State state, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (job.getState() == state) return true;
            Thread.sleep(20);
        }
        return job.getState() == state;
    }

    @Test public void secondInstallInSameEnvironmentQueues() {
        TerminowToolInstaller.Job a = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.GIT);
        TerminowToolInstaller.Job b = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.CURL);
        assertEquals(TerminowToolInstaller.State.RUNNING, a.getState());
        assertEquals(TerminowToolInstaller.State.QUEUED, b.getState());
        assertEquals(1, TerminowToolInstaller.queuedCount());
    }

    @Test public void noDuplicateEnqueue() {
        TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.GIT);
        TerminowToolInstaller.Job b1 = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.CURL);
        TerminowToolInstaller.Job b2 = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.CURL);
        assertSame(b1, b2);
        assertEquals(1, TerminowToolInstaller.queuedCount());
    }

    @Test public void differentEnvironmentsRunInParallel() {
        TerminowToolInstaller.Job a = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.GIT);
        TerminowToolInstaller.Job b = TerminowToolInstaller.install(runner, "env2", "root", TerminowToolInstaller.Tool.GIT);
        assertEquals(TerminowToolInstaller.State.RUNNING, a.getState());
        assertEquals(TerminowToolInstaller.State.RUNNING, b.getState());
        assertNotSame(a, b);
    }

    @Test public void nextStartsWhenRunningEnds() throws Exception {
        TerminowToolInstaller.Job a = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.GIT);
        TerminowToolInstaller.Job b = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.CURL);
        assertTrue(waitState(a, TerminowToolInstaller.State.RUNNING, 2000));
        // Wait until the running job's process actually exists (its thread called the runner), so the
        // release below is not a no-op on an empty map.
        long end = System.currentTimeMillis() + 3000;
        while (started.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20);
        assertTrue("the running job should have started a process", !started.isEmpty());
        // Release the running process; its thread ends and the queued one must start.
        for (FakeProcess p : started.values()) p.done.countDown();
        assertTrue("queued job should start after the running one ends", waitState(b, TerminowToolInstaller.State.RUNNING, 5000));
    }

    @Test public void cancellingAQueuedJobLeavesTheQueue() {
        TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.GIT);
        TerminowToolInstaller.Job b = TerminowToolInstaller.install(runner, "env1", "root", TerminowToolInstaller.Tool.CURL);
        assertEquals(TerminowToolInstaller.State.QUEUED, b.getState());
        b.cancel();
        assertEquals(TerminowToolInstaller.State.FAILED, b.getState());
        assertEquals(0, TerminowToolInstaller.queuedCount());
    }
}
