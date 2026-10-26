package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.termux.app.terminow.TerminowToolInstaller.Failure;
import com.termux.app.terminow.TerminowToolInstaller.Job;
import com.termux.app.terminow.TerminowToolInstaller.State;
import com.termux.app.terminow.TerminowToolInstaller.Tool;

/**
 * The install job lifecycle, apart from any screen so an install keeps running when the Tools Library
 * is hidden: the outcome states (success, the two kinds of failure, cancellation, and the UNKNOWN
 * state for a dropped connection), and that {@link TerminowToolInstaller#install} de-duplicates a
 * repeated Install, queues a second tool behind the one running in a host, and keeps different hosts
 * independent.
 */
public class TerminowInstallJobTest {

    // --- Outcome state machine -----------------------------------------------------------------

    @Test
    public void verifiedVersionWithExitZeroSucceeds() {
        Job job = new Job("env", "root", Tool.CLAUDE);
        job.onLine("TERMINOW_VERSION=2.1.3");
        job.onExit(0);
        assertEquals(State.SUCCEEDED, job.getState());
        assertEquals("2.1.3", job.getVersion());
    }

    @Test
    public void exitZeroWithoutVersionIsVerifyFailure() {
        Job job = new Job("env", "root", Tool.CLAUDE);
        job.onExit(0);
        assertEquals(State.FAILED, job.getState());
        assertEquals(Failure.VERIFY, job.getFailure());
    }

    @Test
    public void nonZeroExitFails() {
        Job job = new Job("env", "root", Tool.CLAUDE);
        job.onExit(1);
        assertEquals(State.FAILED, job.getState());
        assertEquals(Failure.EXIT, job.getFailure());
    }

    @Test
    public void scriptReportedFailureKeepsItsReason() {
        Job job = new Job("env", "root", Tool.CLAUDE);
        job.onLine("TERMINOW_FAILED=apt-install");
        job.onExit(100);
        assertEquals(State.FAILED, job.getState());
        assertEquals(Failure.APT_INSTALL, job.getFailure());
    }

    @Test
    public void cancellationFails() {
        Job job = new Job("env", "root", Tool.CLAUDE);
        job.cancel();
        job.onExit(-1);
        assertEquals(State.FAILED, job.getState());
        assertEquals(Failure.CANCELLED, job.getFailure());
    }

    @Test
    public void droppedConnectionIsUnknownNotFailed() {
        // No markers, not cancelled, no real exit code: the outcome can not be known.
        Job job = new Job("env", "root", Tool.CLAUDE);
        job.onExit(-1);
        assertEquals(State.UNKNOWN, job.getState());
        assertNull(job.getFailure());
    }

    @Test
    public void aReportedFailureIsNotTurnedIntoUnknown() {
        Job job = new Job("env", "root", Tool.CLAUDE);
        job.onLine("TERMINOW_FAILED=installer");
        job.onExit(-1);
        assertEquals(State.FAILED, job.getState());
        assertEquals(Failure.INSTALLER, job.getFailure());
    }

    // --- Registry: de-dup, queue, per-host isolation -------------------------------------------

    @Test
    public void installDedupsQueuesAndIsolatesPerHost() throws Exception {
        FakeRunner runner = new FakeRunner();
        String a = "ssh:test-host-A-" + System.nanoTime();
        String b = "ssh:test-host-B-" + System.nanoTime();
        try {
            Job j1 = TerminowToolInstaller.install(runner, a, "root", Tool.CLAUDE);
            assertNotNull(j1);
            // Pressing Install again on the same tool/host does not start a second job.
            assertSame(j1, TerminowToolInstaller.install(runner, a, "root", Tool.CLAUDE));
            // A different tool on the same host queues behind the running one (apt runs once at a time).
            assertNull(TerminowToolInstaller.install(runner, a, "root", Tool.GIT));
            // A different host runs independently and does not mix with the first.
            Job j2 = TerminowToolInstaller.install(runner, b, "root", Tool.CLAUDE);
            assertNotNull(j2);
            assertNotSame(j1, j2);
            assertSame(j1, TerminowToolInstaller.runningIn(a));
            assertSame(j2, TerminowToolInstaller.runningIn(b));
        } finally {
            runner.releaseAll();
            awaitNotRunning(a, "root", Tool.CLAUDE);
            awaitNotRunning(b, "root", Tool.CLAUDE);
            TerminowToolInstaller.forget(a, "root", Tool.CLAUDE);
            TerminowToolInstaller.forget(b, "root", Tool.CLAUDE);
        }
    }

    private static void awaitNotRunning(String alias, String user, Tool tool) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Job job = TerminowToolInstaller.jobOf(alias, user, tool);
            if (job == null || job.getState() != State.RUNNING) return;
            Thread.sleep(10);
        }
        assertTrue("the job should have stopped running after release", false);
    }

    /** A runner whose processes block until released, so a job stays RUNNING for the registry checks. */
    private static final class FakeRunner implements TerminowToolInstaller.Runner {
        private final List<FakeProcess> processes = new ArrayList<>();

        @Override
        public synchronized Process start(@NonNull String alias, @NonNull String user, @NonNull String script, @NonNull String... args) {
            FakeProcess process = new FakeProcess();
            processes.add(process);
            return process;
        }

        synchronized void releaseAll() {
            for (FakeProcess process : processes) process.destroy();
        }
    }

    private static final class FakeProcess extends Process {
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }

        @Override public int waitFor() throws InterruptedException {
            release.await();
            return 143;
        }

        @Override public int exitValue() {
            if (release.getCount() > 0) throw new IllegalThreadStateException();
            return 143;
        }

        @Override public void destroy() {
            release.countDown();
        }

        @Override public boolean isAlive() {
            return release.getCount() > 0;
        }
    }
}
