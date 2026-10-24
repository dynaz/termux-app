package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The authorization and task-lifecycle core of the TermiNow MCP Bridge: it decides whether a
 * paired AI client may list the shared sessions, submit a task to one, and read or cancel it, and
 * it drives each task through its explicit state machine. It holds no transport and no Android
 * type, so the whole lifecycle — authorization, idempotency, the per-session queue, and
 * no-replay-after-death — is tested on the JVM with a fake {@link Executor}.
 *
 * <p>Every call names a {@code clientKey}, which the transport has already matched to a paired
 * client's token; the bridge still checks the client is paired, the session is shared, and the
 * capability is granted, so a revoked client or an unshared session is refused here too. A task is
 * bound once to the exact target it was submitted against and never retargeted: if that target is
 * gone or changed when the task runs, the task fails rather than running somewhere else.</p>
 */
public final class TerminowMcpBridge {

    /** Capabilities a share may grant. A share without a capability refuses that operation. */
    public static final String CAP_SUBMIT = "submit_task";
    public static final String CAP_READ = "read_output";
    public static final String CAP_CANCEL = "cancel_task";

    public static final int DEFAULT_MAX_QUEUE = 8;
    /** Terminal tasks beyond this many are forgotten, oldest first, so memory stays bounded. */
    public static final int MAX_TASKS = 200;

    /** Time, so timeouts and ordering can be tested without waiting. */
    public interface Clock {
        long now();
    }

    /** A session the user has explicitly shared for AI use, and how it may be used. */
    public static final class Share {
        public final String id;
        public final String label;
        /** A managed target can run tasks; an interactive one is listed but rejects submission. */
        public final boolean managed;
        public final String environmentKey;
        public final String user;
        public final String folder;
        public final List<String> capabilities;

        public Share(@NonNull String id, @NonNull String label, boolean managed, @NonNull String environmentKey,
                     @NonNull String user, @NonNull String folder, @NonNull List<String> capabilities) {
            this.id = id;
            this.label = label;
            this.managed = managed;
            this.environmentKey = environmentKey;
            this.user = user;
            this.folder = folder;
            this.capabilities = capabilities;
        }

        boolean allows(@NonNull String capability) {
            return capabilities.contains(capability);
        }

        /** A stable snapshot of the target; a task bound to an old one is refused when it changed. */
        @NonNull
        String fingerprint() {
            return sha256(id + "\u0000" + managed + "\u0000" + environmentKey + "\u0000" + user + "\u0000" + folder);
        }
    }

    /** What the bridge reads from the rest of the app; a test passes fakes. */
    public interface Deps {
        /** The sessions shared right now, in listing order. */
        @NonNull List<Share> shares();

        /** Whether {@code clientKey} is a currently paired client; a revoked one returns false. */
        boolean clientPaired(@NonNull String clientKey);

        /** How many queued tasks a session may hold before submission is refused. */
        int maxQueue();
    }

    /** Runs a managed task; injected so the lifecycle is tested without a real Claude process. */
    public interface Executor {
        /** Begin running {@code task} against {@code share}, reporting progress to {@code callback}. */
        void start(@NonNull TerminowMcpBridgeTask task, @NonNull Share share, @NonNull Callback callback);

        /** Ask the running task to stop; the executor confirms with {@link Callback#cancelled}. */
        void cancel(@NonNull String taskId);
    }

    /** The executor reports progress through this; the bridge drives state and the queue. */
    public interface Callback {
        void output(@NonNull String taskId, @NonNull String chunk);

        void waitingForUser(@NonNull String taskId, @NonNull String pendingAction);

        void resumed(@NonNull String taskId);

        void completed(@NonNull String taskId, int exitCode, @NonNull String result);

        void failed(@NonNull String taskId, @NonNull String reason);

        void cancelled(@NonNull String taskId);
    }

    /** Persists the tasks so they survive the process; the implementation is injected. */
    public interface Persistence {
        void save(@NonNull List<TerminowMcpBridgeTask> tasks);

        @NonNull List<TerminowMcpBridgeTask> load();
    }

    private final Deps mDeps;
    private final Executor mExecutor;
    private final Persistence mPersistence;
    private final Clock mClock;

    /** All known tasks, newest last, by id. */
    private final Map<String, TerminowMcpBridgeTask> mTasks = new LinkedHashMap<>();
    /** The active (non-queued, non-terminal) task of each session, by session id. */
    private final Map<String, String> mActiveBySession = new HashMap<>();
    /** The queued task ids of each session, in order. */
    private final Map<String, Deque<String>> mQueues = new HashMap<>();
    /** idempotency scope -> task id, so a repeated key returns the first task. */
    private final Map<String, String> mIdempotency = new HashMap<>();

    private final Callback mCallback = new BridgeCallback();

    /** Output persistence is throttled while a task streams; state changes and the end flush. */
    private static final long PERSIST_THROTTLE_MS = 2000;
    private long mLastPersistMs;

    public TerminowMcpBridge(@NonNull Deps deps, @NonNull Executor executor, @NonNull Persistence persistence,
                             @NonNull Clock clock) {
        mDeps = deps;
        mExecutor = executor;
        mPersistence = persistence;
        mClock = clock;
        restore();
    }

    public TerminowMcpBridge(@NonNull Deps deps, @NonNull Executor executor, @NonNull Persistence persistence) {
        this(deps, executor, persistence, System::currentTimeMillis);
    }

    /**
     * Load persisted tasks. Any task that was not finished when the process ended is marked
     * {@link TerminowMcpBridgeTask.State#INTERRUPTED} and never resumed or replayed: managed work
     * may already have changed files or a remote system, so it is not safe to repeat.
     */
    private synchronized void restore() {
        long now = mClock.now();
        for (TerminowMcpBridgeTask task : mPersistence.load()) {
            if (task.state().isActive())
                task.markInterrupted("The app restarted while this task was in progress; it was not resumed or repeated", now);
            mTasks.put(task.id, task);
            if (!task.idempotencyKey.isEmpty())
                mIdempotency.put(idempotencyScope(task.clientKey, task.sessionId, task.idempotencyKey), task.id);
        }
        trim();
    }

    // ---------------------------------------------------------------------------------------
    // Authorization helpers
    // ---------------------------------------------------------------------------------------

    private void requireClient(@NonNull String clientKey) throws TerminowAiError.Exception {
        if (clientKey.isEmpty() || !mDeps.clientPaired(clientKey))
            throw TerminowAiError.CLIENT_NOT_AUTHORIZED.of("This client is not paired, or its access was revoked");
    }

    @NonNull
    private Share requireShare(@NonNull String sessionId) throws TerminowAiError.Exception {
        for (Share share : mDeps.shares()) if (share.id.equals(sessionId)) return share;
        // Not revealing more than that it is not available to this client
        throw TerminowAiError.SESSION_NOT_SHARED.of("The session is not shared, or does not exist");
    }

    // ---------------------------------------------------------------------------------------
    // Tools
    // ---------------------------------------------------------------------------------------

    /** The shared sessions this client may see, with non-secret facts and the live queue count. */
    @NonNull
    public synchronized JSONArray sessionsList(@NonNull String clientKey) throws TerminowAiError.Exception {
        requireClient(clientKey);
        JSONArray array = new JSONArray();
        for (Share share : mDeps.shares()) {
            JSONObject json = new JSONObject();
            try {
                json.put("session_id", share.id);
                json.put("label", share.label);
                json.put("mode", share.managed ? "managed" : "interactive");
                json.put("supported", share.managed);
                json.put("environment", share.environmentKey);
                json.put("folder", share.folder);
                json.put("capabilities", new JSONArray(share.capabilities));
                json.put("queued", queueOf(share.id).size());
                json.put("busy", mActiveBySession.containsKey(share.id));
            } catch (JSONException ignored) {
                continue;
            }
            array.put(json);
        }
        return array;
    }

    /**
     * Submit a request to a shared, managed session. One task runs per session; more are queued up
     * to the bound. A repeated idempotency key returns the first task without running again. The
     * task is bound to the session's target as it is now and is never retargeted.
     */
    @NonNull
    public synchronized JSONObject taskSubmit(@NonNull String clientKey, @NonNull String sessionId,
                                              @NonNull String request, @NonNull String idempotencyKey,
                                              int timeoutSeconds) throws TerminowAiError.Exception {
        requireClient(clientKey);
        if (request.trim().isEmpty()) throw TerminowAiError.BAD_REQUEST.of("The request is empty");
        Share share = requireShare(sessionId);
        if (!share.allows(CAP_SUBMIT))
            throw TerminowAiError.CAPABILITY_DENIED.of("This session does not allow submitting tasks");
        if (!share.managed)
            throw TerminowAiError.SESSION_UNSUPPORTED.of("This session is interactive; only managed sessions accept tasks");

        if (!idempotencyKey.isEmpty()) {
            String existingId = mIdempotency.get(idempotencyScope(clientKey, sessionId, idempotencyKey));
            if (existingId != null) {
                TerminowMcpBridgeTask existing = mTasks.get(existingId);
                if (existing != null) return taskJson(existing, true);
            }
        }

        if (mActiveBySession.containsKey(sessionId) && queueOf(sessionId).size() >= mDeps.maxQueue())
            throw TerminowAiError.QUEUE_FULL.of("The session already has " + mDeps.maxQueue() + " tasks waiting");

        TerminowMcpBridgeTask task = new TerminowMcpBridgeTask(UUID.randomUUID().toString(), clientKey, sessionId,
            share.fingerprint(), idempotencyKey, request, timeoutSeconds, mClock.now());
        mTasks.put(task.id, task);
        if (!idempotencyKey.isEmpty())
            mIdempotency.put(idempotencyScope(clientKey, sessionId, idempotencyKey), task.id);

        if (mActiveBySession.containsKey(sessionId)) {
            queueOf(sessionId).addLast(task.id);
        } else {
            startTask(task, share);
        }
        persist();
        trim();
        return taskJson(task, false);
    }

    @NonNull
    public synchronized JSONObject taskStatus(@NonNull String clientKey, @NonNull String taskId)
            throws TerminowAiError.Exception {
        requireClient(clientKey);
        return taskJson(ownTask(clientKey, taskId), false);
    }

    /** A page of a task's output from {@code cursor}, with the next cursor and whether it is the end. */
    @NonNull
    public synchronized JSONObject taskOutput(@NonNull String clientKey, @NonNull String taskId, int cursor, int maxChars)
            throws TerminowAiError.Exception {
        requireClient(clientKey);
        TerminowMcpBridgeTask task = ownTask(clientKey, taskId);
        Share share = shareFor(task.sessionId);
        if (share == null || !share.allows(CAP_READ))
            throw TerminowAiError.CAPABILITY_DENIED.of("This session does not allow reading task output");
        TerminowMcpBridgeTask.Page page = task.outputFrom(cursor, maxChars <= 0 ? 32 * 1024 : Math.min(maxChars, TerminowMcpBridgeTask.OUTPUT_MAX));
        try {
            return new JSONObject()
                .put("task_id", task.id)
                .put("state", task.state().name().toLowerCase(Locale.ROOT))
                .put("output", page.text)
                .put("cursor", page.nextCursor)
                .put("eof", page.eof)
                .put("truncated", task.truncated());
        } catch (JSONException e) {
            throw TerminowAiError.INTERNAL.of("Could not build the result");
        }
    }

    /**
     * Ask a task to stop. A queued task is cancelled at once (confirmed); a running one moves to
     * {@code cancellation_requested} and the executor confirms it. The caller is told which.
     */
    @NonNull
    public synchronized JSONObject taskCancel(@NonNull String clientKey, @NonNull String taskId)
            throws TerminowAiError.Exception {
        requireClient(clientKey);
        TerminowMcpBridgeTask task = ownTask(clientKey, taskId);
        Share share = shareFor(task.sessionId);
        if (share != null && !share.allows(CAP_CANCEL))
            throw TerminowAiError.CAPABILITY_DENIED.of("This session does not allow cancelling tasks");

        if (task.state().isTerminal()) return taskJson(task, false);
        if (task.state() == TerminowMcpBridgeTask.State.QUEUED) {
            queueOf(task.sessionId).remove(task.id);
            task.markCancelled(mClock.now());
            persist();
            return taskJson(task, false);
        }
        if (task.state() != TerminowMcpBridgeTask.State.CANCELLATION_REQUESTED) task.markCancellationRequested();
        persist();
        mExecutor.cancel(task.id);
        return taskJson(task, false);
    }

    /** The tasks visible to this client, newest first, optionally only for one session. */
    @NonNull
    public synchronized JSONArray taskList(@NonNull String clientKey, @Nullable String sessionId)
            throws TerminowAiError.Exception {
        requireClient(clientKey);
        List<TerminowMcpBridgeTask> ordered = new ArrayList<>(mTasks.values());
        Collections.reverse(ordered);
        JSONArray array = new JSONArray();
        for (TerminowMcpBridgeTask task : ordered) {
            if (!task.clientKey.equals(clientKey)) continue;
            if (sessionId != null && !sessionId.isEmpty() && !task.sessionId.equals(sessionId)) continue;
            array.put(taskJson(task, false));
        }
        return array;
    }

    // ---------------------------------------------------------------------------------------
    // Running and the queue
    // ---------------------------------------------------------------------------------------

    private void startTask(@NonNull TerminowMcpBridgeTask task, @NonNull Share share) {
        // The target must still be the one the task was bound to; a changed share never runs it
        if (!share.fingerprint().equals(task.targetFingerprint)) {
            task.markFailed("The session's target changed after the task was submitted; it was not run", mClock.now());
            return;
        }
        task.markRunning(mClock.now());
        mActiveBySession.put(task.sessionId, task.id);
        mExecutor.start(task, share, mCallback);
    }

    /** When a session's active task ends, start the next queued one that is still valid. */
    private void onSessionFreed(@NonNull String sessionId) {
        mActiveBySession.remove(sessionId);
        Deque<String> queue = queueOf(sessionId);
        while (!queue.isEmpty()) {
            TerminowMcpBridgeTask next = mTasks.get(queue.pollFirst());
            if (next == null || next.state() != TerminowMcpBridgeTask.State.QUEUED) continue;
            Share share = shareFor(sessionId);
            if (share == null) {
                next.markFailed("The session is no longer shared; the task was not run", mClock.now());
                continue;
            }
            startTask(next, share);
            return;
        }
    }

    @NonNull
    private Deque<String> queueOf(@NonNull String sessionId) {
        Deque<String> queue = mQueues.get(sessionId);
        if (queue == null) {
            queue = new ArrayDeque<>();
            mQueues.put(sessionId, queue);
        }
        return queue;
    }

    @Nullable
    private Share shareFor(@NonNull String sessionId) {
        for (Share share : mDeps.shares()) if (share.id.equals(sessionId)) return share;
        return null;
    }

    private final class BridgeCallback implements Callback {
        @Override
        public void output(@NonNull String taskId, @NonNull String chunk) {
            synchronized (TerminowMcpBridge.this) {
                TerminowMcpBridgeTask task = mTasks.get(taskId);
                if (task != null) task.appendOutput(chunk);
                maybePersist();
            }
        }

        @Override
        public void waitingForUser(@NonNull String taskId, @NonNull String pendingAction) {
            synchronized (TerminowMcpBridge.this) {
                TerminowMcpBridgeTask task = mTasks.get(taskId);
                if (task != null && task.state() == TerminowMcpBridgeTask.State.RUNNING)
                    task.markWaitingForUser(pendingAction);
                persist();
            }
        }

        @Override
        public void resumed(@NonNull String taskId) {
            synchronized (TerminowMcpBridge.this) {
                TerminowMcpBridgeTask task = mTasks.get(taskId);
                if (task != null && task.state() == TerminowMcpBridgeTask.State.WAITING_FOR_USER)
                    task.markRunning(mClock.now());
                persist();
            }
        }

        @Override
        public void completed(@NonNull String taskId, int exitCode, @NonNull String result) {
            finish(taskId, task -> task.markCompleted(exitCode, result, mClock.now()));
        }

        @Override
        public void failed(@NonNull String taskId, @NonNull String reason) {
            finish(taskId, task -> task.markFailed(reason, mClock.now()));
        }

        @Override
        public void cancelled(@NonNull String taskId) {
            finish(taskId, task -> task.markCancelled(mClock.now()));
        }

        private void finish(@NonNull String taskId, @NonNull java.util.function.Consumer<TerminowMcpBridgeTask> end) {
            synchronized (TerminowMcpBridge.this) {
                TerminowMcpBridgeTask task = mTasks.get(taskId);
                if (task == null || task.state().isTerminal()) return;
                end.accept(task);
                onSessionFreed(task.sessionId);
                persist();
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Device-side access (the UI), and revocation
    // ---------------------------------------------------------------------------------------

    /** All tasks for the on-device UI, newest first; the UI is not a remote client. */
    @NonNull
    public synchronized List<TerminowMcpBridgeTask> allTasks() {
        List<TerminowMcpBridgeTask> ordered = new ArrayList<>(mTasks.values());
        Collections.reverse(ordered);
        return ordered;
    }

    /** From the device UI: ask a task to stop regardless of which client owns it. */
    public synchronized void cancelFromDevice(@NonNull String taskId) {
        TerminowMcpBridgeTask task = mTasks.get(taskId);
        if (task == null || task.state().isTerminal()) return;
        if (task.state() == TerminowMcpBridgeTask.State.QUEUED) {
            queueOf(task.sessionId).remove(task.id);
            task.markCancelled(mClock.now());
            persist();
            return;
        }
        if (task.state() != TerminowMcpBridgeTask.State.CANCELLATION_REQUESTED) task.markCancellationRequested();
        persist();
        mExecutor.cancel(task.id);
    }

    /** A share's target changed or it was unshared: interrupt its active and queued tasks, no replay. */
    public synchronized void onShareRemoved(@NonNull String sessionId) {
        long now = mClock.now();
        String activeId = mActiveBySession.get(sessionId);
        if (activeId != null) {
            TerminowMcpBridgeTask active = mTasks.get(activeId);
            if (active != null && active.state().isActive()) {
                mExecutor.cancel(activeId);
                active.markInterrupted("The session was unshared or changed; the task was stopped and not repeated", now);
            }
            mActiveBySession.remove(sessionId);
        }
        Deque<String> queue = queueOf(sessionId);
        while (!queue.isEmpty()) {
            TerminowMcpBridgeTask queued = mTasks.get(queue.pollFirst());
            if (queued != null && queued.state() == TerminowMcpBridgeTask.State.QUEUED)
                queued.markCancelled(now);
        }
        persist();
    }

    public synchronized int activeTaskCount() {
        int count = 0;
        for (TerminowMcpBridgeTask task : mTasks.values()) if (task.state().isActive()) count++;
        return count;
    }

    // ---------------------------------------------------------------------------------------

    @NonNull
    private TerminowMcpBridgeTask ownTask(@NonNull String clientKey, @NonNull String taskId) throws TerminowAiError.Exception {
        TerminowMcpBridgeTask task = mTasks.get(taskId);
        // A task of another client is reported as not found, so its existence is not disclosed
        if (task == null || !task.clientKey.equals(clientKey))
            throw TerminowAiError.TASK_NOT_FOUND.of("No such task for this client");
        return task;
    }

    @NonNull
    private JSONObject taskJson(@NonNull TerminowMcpBridgeTask task, boolean idempotentReplay) throws TerminowAiError.Exception {
        try {
            JSONObject json = new JSONObject();
            json.put("task_id", task.id);
            json.put("session_id", task.sessionId);
            json.put("state", task.state().name().toLowerCase(Locale.ROOT));
            json.put("request", task.request);
            json.put("created_at", task.createdAt);
            if (task.startedAt() > 0) json.put("started_at", task.startedAt());
            if (task.endedAt() > 0) json.put("ended_at", task.endedAt());
            if (task.exitCode() != null) json.put("exit_code", (int) task.exitCode());
            if (!task.result().isEmpty()) json.put("result", task.result());
            if (!task.failureReason().isEmpty()) json.put("failure_reason", task.failureReason());
            if (!task.pendingAction().isEmpty()) json.put("pending_action", task.pendingAction());
            json.put("output_length", task.outputLength());
            json.put("truncated", task.truncated());
            if (idempotentReplay) json.put("idempotent_replay", true);
            return json;
        } catch (JSONException e) {
            throw TerminowAiError.INTERNAL.of("Could not build the result");
        }
    }

    /** Persist at most once per throttle window; used while a task streams output. */
    private void maybePersist() {
        if (mClock.now() - mLastPersistMs >= PERSIST_THROTTLE_MS) persist();
    }

    private void persist() {
        mLastPersistMs = mClock.now();
        mPersistence.save(new ArrayList<>(mTasks.values()));
    }

    /** Forget the oldest terminal tasks beyond {@link #MAX_TASKS}, keeping active ones. */
    private void trim() {
        if (mTasks.size() <= MAX_TASKS) return;
        int toRemove = mTasks.size() - MAX_TASKS;
        List<String> ids = new ArrayList<>(mTasks.keySet());
        for (String id : ids) {
            if (toRemove <= 0) break;
            TerminowMcpBridgeTask task = mTasks.get(id);
            if (task != null && task.state().isTerminal()) {
                mTasks.remove(id);
                if (!task.idempotencyKey.isEmpty())
                    mIdempotency.remove(idempotencyScope(task.clientKey, task.sessionId, task.idempotencyKey));
                toRemove--;
            }
        }
    }

    @NonNull
    private static String idempotencyScope(@NonNull String clientKey, @NonNull String sessionId, @NonNull String key) {
        return clientKey + "|" + sessionId + "|" + key;
    }

    @NonNull
    private static String sha256(@NonNull String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(text.hashCode());
        }
    }
}
