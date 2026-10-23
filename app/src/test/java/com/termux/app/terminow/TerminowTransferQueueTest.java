package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.termux.app.terminow.files.TerminowFs;
import com.termux.app.terminow.files.TerminowLocalFs;
import com.termux.app.terminow.files.TerminowTransfer;
import com.termux.app.terminow.files.TerminowTransferQueue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TerminowTransferQueueTest {

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();

    private static TerminowLocalFs other(File dir) throws Exception {
        return new TerminowLocalFs(dir, "device") {
            @NonNull
            @Override
            public String getId() {
                return "device";
            }
        };
    }

    @Test
    public void jobsRunInOrderAndQueuedJobsCanBeCancelled() throws Exception {
        File home = mFolder.newFolder("home");
        File device = mFolder.newFolder("device");
        Files.write(new File(home, "one.bin").toPath(), new byte[2 * 1024 * 1024]);
        Files.write(new File(home, "two.txt").toPath(), "two".getBytes());
        Files.write(new File(device, "two.txt").toPath(), "old".getBytes());
        TerminowLocalFs source = new TerminowLocalFs(home, "Home");
        TerminowLocalFs target = other(device);

        CountDownLatch finished = new CountDownLatch(2);
        TerminowTransferQueue queue = new TerminowTransferQueue(job -> {
            if (job.isFinished()) finished.countDown();
        });
        // The first job waits in its conflict question, so the third is still queued
        CountDownLatch answer = new CountDownLatch(1);
        TerminowTransferQueue.Job first = queue.add(source, source.getRoot(),
            Collections.singletonList(source.find(source.getRoot(), "two.txt")), "Home", target, target.getRoot(), "Device", false,
            (job, name) -> {
                try {
                    answer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return TerminowTransfer.Resolution.KEEP_BOTH;
            });
        TerminowTransferQueue.Job second = queue.add(source, source.getRoot(),
            Collections.singletonList(source.find(source.getRoot(), "one.bin")), "Home", target, target.getRoot(), "Device", true,
            (job, name) -> TerminowTransfer.Resolution.SKIP);
        assertEquals(TerminowTransferQueue.Status.QUEUED, second.status);
        queue.cancel(second);
        assertEquals(TerminowTransferQueue.Status.CANCELLED, second.status);
        answer.countDown();

        assertTrue(finished.await(10, TimeUnit.SECONDS));
        assertEquals(TerminowTransferQueue.Status.DONE, first.status);
        assertTrue(new File(device, "two (2).txt").isFile());
        // The cancelled move never started: its source is there and nothing was copied
        assertTrue(new File(home, "one.bin").isFile());
        assertFalse(new File(device, "one.bin").exists());
        assertEquals(0, queue.getActiveCount());
        queue.clearFinished();
        assertTrue(queue.getJobs().isEmpty());
    }

}
