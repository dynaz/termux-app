package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.terminal.TerminalSession;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where each start of a Linux environment in a tab is: starting, running, failed or exited. A
 * start is running only once the shell inside the environment said so, with the title
 * {@link TerminowLaunchPlan#READY_TITLE}, never after a fixed time; one that does not say so in
 * time failed. One target, like "ubuntu|root|new", starts at most once at a time, so another tap
 * shows the tab that is starting instead of starting another Linux.
 *
 * <p>The diagnostics keep only when, where, which state and how long: never what a tab shows,
 * what was typed, or a password.
 */
final class TerminowLaunchState {

    enum State { STARTING, RUNNING, FAILED, EXITED }

    /** Why a start failed. */
    enum Failure { NONE, TIMEOUT, EXITED_EARLY }

    static final class Launch {
        final String target;
        final TerminalSession session;
        /** When its process started: a tab starts its process only when it is first shown. */
        long startedAt;
        boolean processStarted;
        final long timeoutMs;
        /** Whether the tab was there before, and Linux was started in it: it is never closed for a failure. */
        final boolean inExistingTab;
        State state = State.STARTING;
        Failure failure = Failure.NONE;
        long endedAt;
        int exitStatus;
        /** Whether the empty title that the sign is followed by is still to come. */
        boolean resetPending;
        /** Starts again what failed, in the same way. */
        @Nullable Runnable retry;

        Launch(@NonNull String target, @NonNull TerminalSession session, long startedAt, long timeoutMs, boolean inExistingTab) {
            this.target = target;
            this.session = session;
            this.startedAt = startedAt;
            this.timeoutMs = timeoutMs;
            this.inExistingTab = inExistingTab;
            // Typed into a shell that runs
            this.processStarted = inExistingTab;
        }

        long elapsed(long now) {
            return (endedAt > 0 ? endedAt : now) - startedAt;
        }
    }

    interface Clock {
        long now();
    }

    private static final int DIAGNOSTICS = 40;

    private final Clock mClock;
    private final Map<TerminalSession, Launch> mBySession = new IdentityHashMap<>();
    private final ArrayDeque<String> mDiagnostics = new ArrayDeque<>();

    TerminowLaunchState(@NonNull Clock clock) {
        mClock = clock;
    }

    /** The start of the target that is still starting, {@code null} if there is none. */
    @Nullable
    synchronized Launch starting(@NonNull String target) {
        for (Launch launch : mBySession.values())
            if (launch.target.equals(target) && launch.state == State.STARTING) return launch;
        return null;
    }

    @Nullable
    synchronized Launch of(@NonNull TerminalSession session) {
        return mBySession.get(session);
    }

    /** A start begins, in a new tab or in the tab that was there. */
    @NonNull
    synchronized Launch begin(@NonNull String target, @NonNull TerminalSession session, long timeoutMs, boolean inExistingTab) {
        Launch launch = new Launch(target, session, mClock.now(), timeoutMs, inExistingTab);
        mBySession.put(session, launch);
        note(launch, "starting" + (inExistingTab ? " in the tab that was open" : " in a new tab") + ", timeout " + timeoutMs / 1000 + " s");
        return launch;
    }

    /** The process of the tab started, from when its time runs. */
    synchronized void processStarted(@NonNull TerminalSession session) {
        Launch launch = mBySession.get(session);
        if (launch == null || launch.processStarted) return;
        launch.processStarted = true;
        launch.startedAt = mClock.now();
        note(launch, "process started");
    }

    /**
     * The title of a session changed. Returns whether it was the sign of a start, which is not
     * shown to the user as a title.
     */
    synchronized boolean onTitle(@NonNull TerminalSession session, @Nullable String title) {
        Launch launch = mBySession.get(session);
        if (launch == null) return false;
        if (TerminowLaunchPlan.READY_TITLE.equals(title)) {
            launch.resetPending = true;
            if (launch.state == State.STARTING) {
                launch.state = State.RUNNING;
                note(launch, "running after " + launch.elapsed(mClock.now()) + " ms");
            }
            return true;
        }
        // The empty title that follows the sign, right after it
        if (launch.resetPending && (title == null || title.isEmpty())) {
            launch.resetPending = false;
            return true;
        }
        launch.resetPending = false;
        return false;
    }

    /** The session ended. A start that had not said it runs failed. */
    synchronized void onExited(@NonNull TerminalSession session, int exitStatus) {
        Launch launch = mBySession.get(session);
        if (launch == null) return;
        launch.exitStatus = exitStatus;
        launch.endedAt = mClock.now();
        if (launch.state == State.STARTING) {
            launch.state = State.FAILED;
            launch.failure = Failure.EXITED_EARLY;
            note(launch, "failed: ended with " + exitStatus + " before it was running");
        } else if (launch.state == State.RUNNING) {
            launch.state = State.EXITED;
            note(launch, "exited with " + exitStatus);
        }
    }

    /** Linux that was started in a tab that was there ended, and the tab is its shell again. */
    synchronized void onLeft(@NonNull TerminalSession session) {
        Launch launch = mBySession.remove(session);
        if (launch == null) return;
        if (launch.state == State.STARTING) {
            launch.state = State.FAILED;
            launch.failure = Failure.EXITED_EARLY;
            note(launch, "failed: left before it was running");
        } else {
            launch.state = State.EXITED;
            note(launch, "left after " + launch.elapsed(mClock.now()) + " ms");
        }
    }

    /**
     * A start that is still starting after its timeout failed. Returns it, to stop what it
     * started, or {@code null} when it is not starting anymore.
     */
    @Nullable
    synchronized Launch timeout(@NonNull TerminalSession session) {
        Launch launch = mBySession.get(session);
        if (launch == null || launch.state != State.STARTING || !launch.processStarted) return null;
        if (mClock.now() - launch.startedAt < launch.timeoutMs) return null;
        launch.state = State.FAILED;
        launch.failure = Failure.TIMEOUT;
        launch.endedAt = mClock.now();
        note(launch, "failed: not running after " + launch.timeoutMs / 1000 + " s, stopped");
        return launch;
    }

    /** A closed tab is forgotten. */
    synchronized void forget(@NonNull TerminalSession session) {
        mBySession.remove(session);
    }

    /** What happened to the starts, oldest first. */
    @NonNull
    synchronized List<String> diagnostics() {
        return new ArrayList<>(mDiagnostics);
    }

    private void note(@NonNull Launch launch, @NonNull String what) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date(mClock.now()));
        mDiagnostics.addLast(time + "  " + launch.target + "  " + what);
        while (mDiagnostics.size() > DIAGNOSTICS) mDiagnostics.removeFirst();
    }
}
