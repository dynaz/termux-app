package com.termux.app.terminow;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * A job that runs once at a time: who asks for it while it runs waits for the same run and gets
 * its result, instead of starting a second one. Like installing the bootstrap, which the start of
 * the app and the Ubuntu setup can ask for at the same time.
 */
public final class TerminowSharedJob<T> {

    public interface Callback<T> {
        /** Called on the thread of the job. */
        void onDone(T result);
    }

    private final String mName;
    private final Object mLock = new Object();
    private List<Callback<T>> mWaiting;

    public TerminowSharedJob(@NonNull String name) {
        mName = name;
    }

    /** Whether it runs now. */
    public boolean isRunning() {
        synchronized (mLock) {
            return mWaiting != null;
        }
    }

    /**
     * Run the job on a new thread, or wait for the one that runs. Returns {@code true} if this
     * call started it.
     */
    public boolean run(@NonNull Callable<T> job, @NonNull Callback<T> callback) {
        synchronized (mLock) {
            if (mWaiting != null) {
                mWaiting.add(callback);
                return false;
            }
            mWaiting = new ArrayList<>();
            mWaiting.add(callback);
        }
        Thread thread = new Thread(() -> {
            T result = null;
            try {
                result = job.call();
            } catch (Exception e) {
                // The callers get no result; the job may run again
            }
            List<Callback<T>> waiting;
            synchronized (mLock) {
                waiting = mWaiting;
                mWaiting = null;
            }
            for (Callback<T> waiter : waiting) waiter.onDone(result);
        }, mName);
        thread.start();
        return true;
    }

}
