package com.termux.app.terminow.files;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The transfers of the File Manager, run one after another on a worker, so that the user can
 * keep browsing. Each job has its own progress, cancellation and result.
 */
public class TerminowTransferQueue {

    public enum Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

    public interface Listener {
        /** A job changed, called from the worker or the thread that changed it. */
        void onChanged(@NonNull Job job);
    }

    public interface Conflicts {
        /** What to do with an existing file. Blocks the worker until answered. */
        @NonNull TerminowTransfer.Resolution resolve(@NonNull Job job, @NonNull String name);
    }

    public static class Job {
        public final int id;
        public final boolean move;
        public final String sourceTitle;
        public final String destinationTitle;
        public final int itemCount;
        public final TerminowFs.Cancel cancel = new TerminowFs.Cancel();
        public volatile Status status = Status.QUEUED;
        public volatile String currentName = "";
        public volatile long bytesDone;
        public volatile long bytesTotal;
        public volatile int filesDone;
        public volatile int filesTotal;
        public volatile TerminowTransfer.Result result;
        /** The file system that was written, to refresh what shows it. */
        public final TerminowFs target;
        public final TerminowFs source;

        Job(int id, boolean move, @NonNull TerminowFs source, @NonNull String sourceTitle, @NonNull TerminowFs target,
            @NonNull String destinationTitle, int itemCount) {
            this.id = id;
            this.move = move;
            this.source = source;
            this.sourceTitle = sourceTitle;
            this.target = target;
            this.destinationTitle = destinationTitle;
            this.itemCount = itemCount;
        }

        public boolean isFinished() {
            return status == Status.DONE || status == Status.FAILED || status == Status.CANCELLED;
        }
    }

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger mNextId = new AtomicInteger(1);
    private final List<Job> mJobs = Collections.synchronizedList(new ArrayList<>());
    private final Listener mListener;

    public TerminowTransferQueue(@NonNull Listener listener) {
        mListener = listener;
    }

    @NonNull
    public Job add(@NonNull TerminowFs source, @NonNull TerminowFs.Node sourceParent, @NonNull List<TerminowFs.Node> nodes,
                   @NonNull String sourceTitle, @NonNull TerminowFs target, @NonNull TerminowFs.Node targetDirectory,
                   @NonNull String destinationTitle, boolean move, @NonNull Conflicts conflicts) {
        Job job = new Job(mNextId.getAndIncrement(), move, source, sourceTitle, target, destinationTitle, nodes.size());
        mJobs.add(job);
        mListener.onChanged(job);
        List<TerminowFs.Node> items = new ArrayList<>(nodes);
        mExecutor.execute(() -> {
            if (job.cancel.isCancelled()) {
                job.status = Status.CANCELLED;
                mListener.onChanged(job);
                return;
            }
            job.status = Status.RUNNING;
            mListener.onChanged(job);
            TerminowTransfer.Result result = new TerminowTransfer(source, target, move, new TerminowTransfer.Listener() {
                private long mLastNotify;

                @Override
                public void onProgress(@NonNull String name, long bytesDone, long bytesTotal, int filesDone, int filesTotal) {
                    job.currentName = name;
                    job.bytesDone = bytesDone;
                    job.bytesTotal = bytesTotal;
                    job.filesDone = filesDone;
                    job.filesTotal = filesTotal;
                    long now = System.currentTimeMillis();
                    if (now - mLastNotify > 200) {
                        mLastNotify = now;
                        mListener.onChanged(job);
                    }
                }

                @NonNull
                @Override
                public TerminowTransfer.Resolution onConflict(@NonNull String name) {
                    return conflicts.resolve(job, name);
                }
            }, job.cancel).run(sourceParent, items, targetDirectory);
            job.result = result;
            job.status = result.cancelled ? Status.CANCELLED : result.errors.isEmpty() ? Status.DONE : Status.FAILED;
            mListener.onChanged(job);
        });
        return job;
    }

    /** Cancel a job: a queued one never starts, a running one stops and leaves no half file. */
    public void cancel(@NonNull Job job) {
        job.cancel.cancel();
        if (job.status == Status.QUEUED) {
            job.status = Status.CANCELLED;
            mListener.onChanged(job);
        }
    }

    @NonNull
    public List<Job> getJobs() {
        synchronized (mJobs) {
            return new ArrayList<>(mJobs);
        }
    }

    public int getActiveCount() {
        int count = 0;
        for (Job job : getJobs()) if (!job.isFinished()) count++;
        return count;
    }

    public void clearFinished() {
        synchronized (mJobs) {
            mJobs.removeIf(Job::isFinished);
        }
    }

}
