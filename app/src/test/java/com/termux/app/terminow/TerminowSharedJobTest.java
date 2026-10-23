package com.termux.app.terminow;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The bootstrap is installed once at a time, and commands get a working directory that exists. */
public class TerminowSharedJobTest {

    @Test
    public void callersWhileItRunsWaitForTheSameRun() throws Exception {
        TerminowSharedJob<String> job = new TerminowSharedJob<>("test");
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        List<String> results = new CopyOnWriteArrayList<>();
        // The start of the app installs it, the Ubuntu setup and another tap ask meanwhile
        assertTrue(job.run(() -> {
            runs.incrementAndGet();
            release.await();
            return "installed";
        }, result -> {
            results.add(result);
            done.countDown();
        }));
        assertTrue(job.isRunning());
        for (int i = 0; i < 2; i++) {
            assertFalse("joins, does not start another", job.run(() -> {
                runs.incrementAndGet();
                return "second";
            }, result -> {
                results.add(result);
                done.countDown();
            }));
        }
        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(1, runs.get());
        assertEquals(3, results.size());
        for (String result : results) assertEquals("installed", result);
        assertFalse(job.isRunning());
    }

    @Test
    public void aJobThatFailsCanRunAgain() throws Exception {
        TerminowSharedJob<String> job = new TerminowSharedJob<>("test");
        CountDownLatch first = new CountDownLatch(1);
        String[] result = {"not called"};
        job.run(() -> {
            throw new IllegalStateException("boom");
        }, value -> {
            result[0] = value;
            first.countDown();
        });
        assertTrue(first.await(10, TimeUnit.SECONDS));
        assertNull(result[0]);
        CountDownLatch second = new CountDownLatch(1);
        assertTrue(job.run(() -> "ok", value -> {
            result[0] = value;
            second.countDown();
        }));
        assertTrue(second.await(10, TimeUnit.SECONDS));
        assertEquals("ok", result[0]);
    }

    @Test
    public void commandsStartInAHomeThatIsMadeWhenNoTerminalOpenedYet() throws Exception {
        File files = Files.createTempDirectory("terminow-files").toFile();
        File home = new File(files, "home");
        assertFalse(home.exists());
        // As on a phone where no terminal tab was ever opened
        assertEquals(home, TerminowClaudeSetup.workingDirectoryFor(home, files));
        assertTrue(home.isDirectory());
        // A home that can not be made: the files of the app, never a folder that is not there
        File blocker = new File(files, "file");
        assertTrue(blocker.createNewFile());
        assertEquals(files, TerminowClaudeSetup.workingDirectoryFor(new File(blocker, "home"), files));
    }

}
