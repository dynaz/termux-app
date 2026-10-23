package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumSet;
import java.util.Set;

/**
 * One unit of work the MCP Bridge runs for an external AI client: a request submitted to a shared
 * TermiNow session, with an explicit lifecycle. A task is created {@link State#QUEUED}, bound once
 * to the exact target it was submitted against ({@link #targetFingerprint}), and never retargeted;
 * if that target is gone or changed when the task would run, the task fails instead of running
 * somewhere else.
 *
 * <p>The state machine is explicit and one-way towards a terminal state. Output is accumulated
 * with a hard cap and read back through a cursor, so a client pages through it without the bridge
 * holding unbounded memory. The task is plain data: it carries no process handle and serializes to
 * JSON, so it survives being persisted. After the app's process dies, a task that was not finished
 * is marked {@link State#INTERRUPTED} on reload and is never silently replayed, because managed
 * work may already have changed files or a remote system.</p>
 */
public final class TerminowMcpBridgeTask {

    /** Output kept in memory for one task; more than this is dropped and {@link #truncated} set. */
    public static final int OUTPUT_MAX = 256 * 1024;

    public enum State {
        QUEUED,
        RUNNING,
        WAITING_FOR_USER,
        CANCELLATION_REQUESTED,
        COMPLETED,
        FAILED,
        CANCELLED,
        INTERRUPTED;

        public boolean isTerminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
        }

        public boolean isActive() {
            return !isTerminal();
        }

        /** The states this one may move to; a terminal state moves nowhere. */
        @NonNull
        Set<State> allowedNext() {
            switch (this) {
                case QUEUED:
                    return EnumSet.of(RUNNING, CANCELLATION_REQUESTED, CANCELLED, FAILED, INTERRUPTED);
                case RUNNING:
                    return EnumSet.of(WAITING_FOR_USER, CANCELLATION_REQUESTED, COMPLETED, FAILED, INTERRUPTED);
                case WAITING_FOR_USER:
                    return EnumSet.of(RUNNING, CANCELLATION_REQUESTED, COMPLETED, FAILED, INTERRUPTED);
                case CANCELLATION_REQUESTED:
                    return EnumSet.of(CANCELLED, COMPLETED, FAILED, INTERRUPTED);
                default:
                    return EnumSet.noneOf(State.class);
            }
        }
    }

    public final String id;
    /** The paired client that submitted the task; only it (and the device UI) may see or control it. */
    public final String clientKey;
    /** The shared session id the task was submitted to. */
    public final String sessionId;
    /** A snapshot of the exact target at submit time; the task is bound to this and never retargeted. */
    public final String targetFingerprint;
    /** The client's idempotency key, empty if none. */
    public final String idempotencyKey;
    /** The request text (the prompt for the managed Claude run). */
    public final String request;
    public final int timeoutSeconds;
    public final long createdAt;

    private State mState = State.QUEUED;
    private long mStartedAt;
    private long mEndedAt;
    @Nullable private Integer mExitCode;
    private String mResult = "";
    private String mFailureReason = "";
    /** Set while {@link State#WAITING_FOR_USER}: what the run is waiting for. */
    private String mPendingAction = "";
    private final StringBuilder mOutput = new StringBuilder();
    private boolean mTruncated;

    public TerminowMcpBridgeTask(@NonNull String id, @NonNull String clientKey, @NonNull String sessionId,
                                 @NonNull String targetFingerprint, @NonNull String idempotencyKey,
                                 @NonNull String request, int timeoutSeconds, long createdAt) {
        this.id = id;
        this.clientKey = clientKey;
        this.sessionId = sessionId;
        this.targetFingerprint = targetFingerprint;
        this.idempotencyKey = idempotencyKey;
        this.request = request;
        this.timeoutSeconds = timeoutSeconds;
        this.createdAt = createdAt;
    }

    public synchronized State state() {
        return mState;
    }

    public synchronized long startedAt() {
        return mStartedAt;
    }

    public synchronized long endedAt() {
        return mEndedAt;
    }

    @Nullable
    public synchronized Integer exitCode() {
        return mExitCode;
    }

    @NonNull
    public synchronized String result() {
        return mResult;
    }

    @NonNull
    public synchronized String failureReason() {
        return mFailureReason;
    }

    @NonNull
    public synchronized String pendingAction() {
        return mPendingAction;
    }

    public synchronized int outputLength() {
        return mOutput.length();
    }

    public synchronized boolean truncated() {
        return mTruncated;
    }

    /** Append run output, up to the cap; the rest is dropped and {@link #truncated()} becomes true. */
    public synchronized void appendOutput(@NonNull String chunk) {
        int room = OUTPUT_MAX - mOutput.length();
        if (room <= 0) {
            mTruncated = true;
            return;
        }
        if (chunk.length() > room) {
            mOutput.append(chunk, 0, room);
            mTruncated = true;
        } else {
            mOutput.append(chunk);
        }
    }

    /**
     * A page of output from {@code cursor} (a character offset), at most {@code maxChars}. A cursor
     * past the end returns nothing; a negative cursor is treated as 0.
     */
    @NonNull
    public synchronized Page outputFrom(int cursor, int maxChars) {
        int from = Math.max(0, Math.min(cursor, mOutput.length()));
        int to = maxChars <= 0 ? mOutput.length() : Math.min(mOutput.length(), from + maxChars);
        String text = mOutput.substring(from, to);
        boolean eof = to >= mOutput.length() && mState.isTerminal();
        return new Page(text, to, eof);
    }

    public static final class Page {
        public final String text;
        public final int nextCursor;
        /** No more output will ever follow this cursor (the task has finished). */
        public final boolean eof;

        Page(@NonNull String text, int nextCursor, boolean eof) {
            this.text = text;
            this.nextCursor = nextCursor;
            this.eof = eof;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transitions
    // ---------------------------------------------------------------------------------------

    public synchronized boolean canTransitionTo(@NonNull State next) {
        return mState.allowedNext().contains(next);
    }

    private synchronized void require(@NonNull State next) {
        if (!canTransitionTo(next))
            throw new IllegalStateException("Cannot move task " + id + " from " + mState + " to " + next);
        mState = next;
    }

    public synchronized void markRunning(long now) {
        require(State.RUNNING);
        if (mStartedAt == 0) mStartedAt = now;
        mPendingAction = "";
    }

    public synchronized void markWaitingForUser(@NonNull String pendingAction) {
        require(State.WAITING_FOR_USER);
        mPendingAction = pendingAction;
    }

    public synchronized void markCancellationRequested() {
        require(State.CANCELLATION_REQUESTED);
    }

    public synchronized void markCompleted(int exitCode, @NonNull String result, long now) {
        require(State.COMPLETED);
        mExitCode = exitCode;
        mResult = result;
        mEndedAt = now;
        mPendingAction = "";
    }

    public synchronized void markFailed(@NonNull String reason, long now) {
        require(State.FAILED);
        mFailureReason = reason;
        mEndedAt = now;
        mPendingAction = "";
    }

    public synchronized void markCancelled(long now) {
        require(State.CANCELLED);
        mEndedAt = now;
        mPendingAction = "";
    }

    /** Only from a non-terminal state, on process death or a target that vanished. */
    public synchronized void markInterrupted(@NonNull String reason, long now) {
        if (mState.isTerminal()) return;
        mState = State.INTERRUPTED;
        mFailureReason = reason;
        mEndedAt = now;
        mPendingAction = "";
    }

    // ---------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------

    @NonNull
    public synchronized JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("client", clientKey);
        json.put("session", sessionId);
        json.put("target", targetFingerprint);
        json.put("idempotency", idempotencyKey);
        json.put("request", request);
        json.put("timeout", timeoutSeconds);
        json.put("createdAt", createdAt);
        json.put("state", mState.name());
        json.put("startedAt", mStartedAt);
        json.put("endedAt", mEndedAt);
        if (mExitCode != null) json.put("exitCode", (int) mExitCode);
        json.put("result", mResult);
        json.put("failureReason", mFailureReason);
        json.put("pendingAction", mPendingAction);
        json.put("output", mOutput.toString());
        json.put("truncated", mTruncated);
        return json;
    }

    @NonNull
    public static TerminowMcpBridgeTask fromJson(@NonNull JSONObject json) {
        TerminowMcpBridgeTask task = new TerminowMcpBridgeTask(
            json.optString("id"), json.optString("client"), json.optString("session"),
            json.optString("target"), json.optString("idempotency"), json.optString("request"),
            json.optInt("timeout"), json.optLong("createdAt"));
        task.mState = parseState(json.optString("state"));
        task.mStartedAt = json.optLong("startedAt");
        task.mEndedAt = json.optLong("endedAt");
        if (json.has("exitCode") && !json.isNull("exitCode")) task.mExitCode = json.optInt("exitCode");
        task.mResult = json.optString("result");
        task.mFailureReason = json.optString("failureReason");
        task.mPendingAction = json.optString("pendingAction");
        task.mOutput.append(json.optString("output"));
        task.mTruncated = json.optBoolean("truncated");
        return task;
    }

    @NonNull
    private static State parseState(@NonNull String name) {
        try {
            return State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return State.INTERRUPTED;
        }
    }
}
