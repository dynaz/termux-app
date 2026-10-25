package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The authorization and lifecycle of {@link TerminowMcpBridge}, covering the required scenarios. */
public class TerminowMcpBridgeTest {

    private static final String CLIENT = "client-a";
    private static final String OTHER = "client-b";

    private final List<TerminowMcpBridge.Share> shares = new ArrayList<>();
    private final Set<String> paired = new HashSet<>(Arrays.asList(CLIENT, OTHER));
    private int maxQueue = TerminowMcpBridge.DEFAULT_MAX_QUEUE;
    private long now = 1_000L;

    private FakeExecutor executor;
    private JsonPersistence persistence;
    private TerminowMcpBridge bridge;

    @Before
    public void setUp() {
        executor = new FakeExecutor();
        persistence = new JsonPersistence();
        bridge = newBridge(executor, persistence);
    }

    private TerminowMcpBridge newBridge(FakeExecutor exec, JsonPersistence store) {
        return new TerminowMcpBridge(new TerminowMcpBridge.Deps() {
            @NonNull @Override public List<TerminowMcpBridge.Share> shares() { return shares; }
            @Override public boolean clientPaired(@NonNull String clientKey) { return paired.contains(clientKey); }
            @Override public int maxQueue() { return maxQueue; }
        }, exec, store, () -> now);
    }

    private TerminowMcpBridge.Share managed(String id, String... caps) {
        List<String> capList = caps.length == 0
            ? Arrays.asList(TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_READ, TerminowMcpBridge.CAP_CANCEL)
            : Arrays.asList(caps);
        return new TerminowMcpBridge.Share(id, "Session " + id, true, "ubuntu", "root", "/home/root/" + id, capList);
    }

    private String submit(String sessionId) throws Exception {
        return bridge.taskSubmit(CLIENT, sessionId, "do the thing", "", 0).getString("task_id");
    }

    private String state(String clientKey, String taskId) throws Exception {
        return bridge.taskStatus(clientKey, taskId).getString("state");
    }

    // 1. An unauthorized client cannot list or control sessions.
    @Test
    public void unauthorizedClientIsRefused() {
        shares.add(managed("s1"));
        try {
            bridge.sessionsList("stranger");
            fail("expected refusal");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.CLIENT_NOT_AUTHORIZED, e.error);
        }
        try {
            bridge.taskSubmit("stranger", "s1", "x", "", 0);
            fail("expected refusal");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.CLIENT_NOT_AUTHORIZED, e.error);
        }
    }

    // 2. A client cannot access an unshared session.
    @Test
    public void unsharedSessionIsRefused() {
        shares.add(managed("s1"));
        try {
            bridge.taskSubmit(CLIENT, "not-shared", "x", "", 0);
            fail("expected refusal");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.SESSION_NOT_SHARED, e.error);
        }
    }

    // 3. A task remains bound to its original host: a changed target never runs the queued task.
    @Test
    public void taskStaysBoundToItsOriginalTarget() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");           // runs
        String b = submit("s1");           // queued
        assertEquals("running", state(CLIENT, a));
        assertEquals("queued", state(CLIENT, b));

        // The session's target changes while b waits
        shares.set(0, new TerminowMcpBridge.Share("s1", "Session s1", true, "ubuntu", "root", "/a/different/folder",
            Arrays.asList(TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_READ, TerminowMcpBridge.CAP_CANCEL)));

        executor.complete(a, 0, "done");   // frees the session, dequeues b
        assertEquals("b must not run against a changed target", "failed", state(CLIENT, b));
        assertFalse("b was never started", executor.started.containsKey(b));
        assertTrue(bridge.taskStatus(CLIENT, b).getString("failure_reason").toLowerCase().contains("changed"));
    }

    // 4. A closed or changed session never receives a task.
    @Test
    public void unsharedSessionNeverReceivesQueuedTask() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        String b = submit("s1");
        shares.clear();                    // the session is closed / unshared
        executor.complete(a, 0, "done");
        assertEquals("failed", state(CLIENT, b));
        assertFalse(executor.started.containsKey(b));
    }

    // 5. An unsupported interactive session rejects task submission.
    @Test
    public void interactiveSessionRejectsSubmission() {
        shares.add(new TerminowMcpBridge.Share("si", "Interactive", false, "ubuntu", "root", "/home",
            Arrays.asList(TerminowMcpBridge.CAP_SUBMIT)));
        try {
            bridge.taskSubmit(CLIENT, "si", "x", "", 0);
            fail("expected refusal");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.SESSION_UNSUPPORTED, e.error);
        }
    }

    // 6. A duplicate idempotency key does not execute twice.
    @Test
    public void duplicateIdempotencyKeyDoesNotRunTwice() throws Exception {
        shares.add(managed("s1"));
        JSONObject first = bridge.taskSubmit(CLIENT, "s1", "x", "key-1", 0);
        JSONObject second = bridge.taskSubmit(CLIENT, "s1", "x", "key-1", 0);
        assertEquals(first.getString("task_id"), second.getString("task_id"));
        assertTrue(second.optBoolean("idempotent_replay"));
        assertEquals("the task ran once", 1, executor.started.size());
    }

    // 7. Queue order and queue bounds.
    @Test
    public void queueKeepsOrderAndBound() throws Exception {
        maxQueue = 2;
        shares.add(managed("s1"));
        String a = submit("s1");           // runs
        String b = submit("s1");           // queued 1
        String c = submit("s1");           // queued 2
        try {
            submit("s1");                  // over the bound
            fail("expected a full queue");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.QUEUE_FULL, e.error);
        }
        executor.complete(a, 0, "a");
        assertEquals("b runs first", "running", state(CLIENT, b));
        assertEquals("queued", state(CLIENT, c));
        executor.complete(b, 0, "b");
        assertEquals("c runs next", "running", state(CLIENT, c));
    }

    // 8. Waiting for user approval.
    @Test
    public void waitingForUserThenResumes() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        executor.waiting(a, "Approve editing /etc/hosts");
        assertEquals("waiting_for_user", state(CLIENT, a));
        assertEquals("Approve editing /etc/hosts", bridge.taskStatus(CLIENT, a).getString("pending_action"));
        executor.resumed(a);
        assertEquals("running", state(CLIENT, a));
        executor.complete(a, 0, "ok");
        assertEquals("completed", state(CLIENT, a));
    }

    // 9. Cancellation request versus confirmed cancellation.
    @Test
    public void cancellationRequestedThenConfirmed() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");           // running
        String b = submit("s1");           // queued
        // A queued task cancels at once (confirmed)
        bridge.taskCancel(CLIENT, b);
        assertEquals("cancelled", state(CLIENT, b));
        // A running task is only requested until the executor confirms
        bridge.taskCancel(CLIENT, a);
        assertEquals("cancellation_requested", state(CLIENT, a));
        assertTrue(executor.cancelled.contains(a));
        executor.confirmCancel(a);
        assertEquals("cancelled", state(CLIENT, a));
    }

    // 10. Process death does not replay work.
    @Test
    public void processDeathInterruptsAndDoesNotReplay() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        assertEquals("running", state(CLIENT, a));

        // A fresh bridge loads the persisted tasks, as after the app process was killed
        FakeExecutor executor2 = new FakeExecutor();
        TerminowMcpBridge bridge2 = newBridge(executor2, persistence);
        assertEquals("interrupted", bridge2.taskStatus(CLIENT, a).getString("state"));
        assertTrue("no task is replayed on restart", executor2.started.isEmpty());
    }

    // 11. Output pagination and access control.
    @Test
    public void outputPaginatesAndIsPrivateToTheClient() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        executor.output(a, "hello");
        executor.output(a, "world");

        JSONObject page1 = bridge.taskOutput(CLIENT, a, 0, 5);
        assertEquals("hello", page1.getString("output"));
        assertEquals(5, page1.getInt("cursor"));
        assertFalse(page1.getBoolean("eof"));

        JSONObject page2 = bridge.taskOutput(CLIENT, a, page1.getInt("cursor"), 100);
        assertEquals("world", page2.getString("output"));
        assertEquals(10, page2.getInt("cursor"));

        executor.complete(a, 0, "done");
        JSONObject page3 = bridge.taskOutput(CLIENT, a, 10, 100);
        assertEquals("", page3.getString("output"));
        assertTrue("end once the task is finished", page3.getBoolean("eof"));

        // Another paired client cannot read this client's task
        try {
            bridge.taskOutput(OTHER, a, 0, 100);
            fail("expected not found");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.TASK_NOT_FOUND, e.error);
        }
    }

    // 12. Client revocation blocks subsequent calls.
    @Test
    public void revokedClientIsBlocked() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        assertNotEquals("", a);
        paired.remove(CLIENT);             // the client's pairing is revoked
        for (Runnable call : Arrays.<Runnable>asList(
            () -> swallow(() -> bridge.sessionsList(CLIENT)),
            () -> swallow(() -> bridge.taskStatus(CLIENT, a)),
            () -> swallow(() -> bridge.taskCancel(CLIENT, a)))) {
            lastError = null;
            call.run();
            assertEquals(TerminowAiError.CLIENT_NOT_AUTHORIZED, lastError);
        }
    }

    // Reading output requires the read_output capability (CAP_READ).
    @Test
    public void readingOutputNeedsReadCapability() throws Exception {
        shares.add(managed("s1", TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_CANCEL)); // no read
        String a = submit("s1");
        executor.output(a, "secret output");
        try {
            bridge.taskOutput(CLIENT, a, 0, 100);
            fail("expected capability denied");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.CAPABILITY_DENIED, e.error);
        }
        // Granting read_output then allows it
        shares.set(0, managed("s1", TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_READ, TerminowMcpBridge.CAP_CANCEL));
        assertEquals("secret output", bridge.taskOutput(CLIENT, a, 0, 100).getString("output"));
    }

    // A retry is a fresh attempt to the same session, owned by the same client, linked by lineage.
    @Test
    public void retryCreatesLinkedAttemptToSameSession() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        executor.complete(a, 0, "done");               // a is terminal
        TerminowMcpBridgeTask retry = bridge.retryFromDevice(a);
        assertEquals("same session", "s1", retry.sessionId);
        assertEquals("same owner", CLIENT, retry.clientKey);
        assertEquals("linked to the original", a, retry.parentId());
        assertNotEquals("a new task id", a, retry.id);
        assertEquals("the retry runs once the session is free", "running", state(CLIENT, retry.id));
        assertTrue(executor.started.containsKey(retry.id));
    }

    // Only a finished task can be retried.
    @Test
    public void retryRefusedForUnfinishedTask() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");                        // running
        try {
            bridge.retryFromDevice(a);
            fail("expected refusal for an unfinished task");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.BAD_REQUEST, e.error);
        }
    }

    // A retry never retargets: if the session is gone, it fails instead of running elsewhere.
    @Test
    public void retryRefusedWhenSessionUnshared() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        executor.complete(a, 0, "done");
        shares.clear();                                 // the session was unshared
        try {
            bridge.retryFromDevice(a);
            fail("expected refusal when the session is gone");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.SESSION_NOT_SHARED, e.error);
        }
    }

    // The per-session task list shows only that session's tasks; the global list shows all.
    @Test
    public void perSessionListFiltersBySession() throws Exception {
        shares.add(managed("s1"));
        shares.add(managed("s2"));
        String a = submit("s1");
        String b = bridge.taskSubmit(CLIENT, "s2", "x", "", 0).getString("task_id");
        JSONArray onlyS1 = bridge.taskList(CLIENT, "s1");
        assertEquals(1, onlyS1.length());
        assertEquals(a, onlyS1.getJSONObject(0).getString("task_id"));
        assertEquals("the global list has both", 2, bridge.taskList(CLIENT, "").length());
        assertNotEquals(a, b);
    }

    // A task keeps a human target snapshot (environment/folder) that survives an unshare and a reload.
    @Test
    public void taskKeepsTargetMetadataForDisplay() throws Exception {
        shares.add(managed("s1"));                      // env "ubuntu", folder "/home/root/s1"
        String a = submit("s1");
        TerminowMcpBridgeTask task = findTask(bridge, a);
        assertEquals("ubuntu", task.environmentKey);
        assertEquals("/home/root/s1", task.folder);
        shares.clear();                                 // even after unshare, the snapshot remains on the task
        TerminowMcpBridge reloaded = newBridge(new FakeExecutor(), persistence);
        TerminowMcpBridgeTask back = findTask(reloaded, a);
        assertEquals("ubuntu", back.environmentKey);
        assertEquals("/home/root/s1", back.folder);
    }

    // Attempt lineage survives a restart.
    @Test
    public void retryLineageSurvivesReload() throws Exception {
        shares.add(managed("s1"));
        String a = submit("s1");
        executor.complete(a, 0, "done");
        String retryId = bridge.retryFromDevice(a).id;
        TerminowMcpBridge reloaded = newBridge(new FakeExecutor(), persistence);
        assertEquals(a, findTask(reloaded, retryId).parentId());
    }

    @NonNull
    private TerminowMcpBridgeTask findTask(@NonNull TerminowMcpBridge b, @NonNull String id) {
        for (TerminowMcpBridgeTask t : b.allTasks()) if (t.id.equals(id)) return t;
        throw new AssertionError("no task " + id);
    }

    // An SSH-host share in Mode A (local tools) accepts tasks and runs its local Claude environment.
    @Test
    public void sshHostLocalToolsShareAcceptsTasks() throws Exception {
        shares.add(sshHost("s1", TerminowMcpBridge.MODE_LOCAL_TOOLS));
        String a = submit("s1");
        assertEquals("running", state(CLIENT, a));
        assertEquals("ubuntu", findTask(bridge, a).environmentKey);
    }

    // Mode B (remote Claude on the host) is refused for now, not silently run elsewhere.
    @Test
    public void remoteHostModeIsRefusedForNow() {
        shares.add(sshHost("s1", TerminowMcpBridge.MODE_REMOTE_HOST));
        try {
            bridge.taskSubmit(CLIENT, "s1", "x", "", 0);
            fail("expected Mode B to be refused in Phase 1");
        } catch (TerminowAiError.Exception e) {
            assertEquals(TerminowAiError.SESSION_UNSUPPORTED, e.error);
        }
    }

    // A local share's fingerprint ignores the SSH fields (migration-safe); an SSH-host share binds to its host.
    @Test
    public void localFingerprintStaysStableSshHostBindsToHost() {
        List<String> caps = Arrays.asList(TerminowMcpBridge.CAP_SUBMIT);
        TerminowMcpBridge.Share local7 = new TerminowMcpBridge.Share("s1", "L", true, "ubuntu", "root", "/f", caps);
        TerminowMcpBridge.Share local11 = new TerminowMcpBridge.Share("s1", "L", true, "ubuntu", "root", "/f", caps,
            TerminowMcpBridge.SHARE_LOCAL, TerminowMcpBridge.MODE_LOCAL_TOOLS, "ignored", "ignored");
        assertEquals("a local fingerprint ignores ssh fields", local7.fingerprint(), local11.fingerprint());
        TerminowMcpBridge.Share ssh = new TerminowMcpBridge.Share("s1", "L", true, "ubuntu", "root", "/f", caps,
            TerminowMcpBridge.SHARE_SSH_HOST, TerminowMcpBridge.MODE_LOCAL_TOOLS, "host-1", "root@1.1.1.1:22");
        assertNotEquals("an ssh-host share binds to its host", local7.fingerprint(), ssh.fingerprint());
        TerminowMcpBridge.Share moved = new TerminowMcpBridge.Share("s1", "L", true, "ubuntu", "root", "/f", caps,
            TerminowMcpBridge.SHARE_SSH_HOST, TerminowMcpBridge.MODE_LOCAL_TOOLS, "host-1", "root@2.2.2.2:22");
        assertNotEquals("a changed host identity is a changed target", ssh.fingerprint(), moved.fingerprint());
    }

    private TerminowMcpBridge.Share sshHost(String id, String mode) {
        return new TerminowMcpBridge.Share(id, "Session " + id, true, "ubuntu", "root", "/home/root/" + id,
            Arrays.asList(TerminowMcpBridge.CAP_SUBMIT, TerminowMcpBridge.CAP_READ, TerminowMcpBridge.CAP_CANCEL),
            TerminowMcpBridge.SHARE_SSH_HOST, mode, "host-" + id, "root@" + id + ".example:22");
    }

    // ---------------------------------------------------------------------------------------

    private TerminowAiError lastError;

    private interface Call { void run() throws TerminowAiError.Exception; }

    private void swallow(Call call) {
        try {
            call.run();
            fail("expected a refusal");
        } catch (TerminowAiError.Exception e) {
            lastError = e.error;
        }
    }

    /** Records started/cancelled tasks and lets the test drive the executor callbacks. */
    private static final class FakeExecutor implements TerminowMcpBridge.Executor {
        final Map<String, TerminowMcpBridge.Callback> started = new LinkedHashMap<>();
        final List<String> cancelled = new ArrayList<>();

        @Override
        public void start(@NonNull TerminowMcpBridgeTask task, @NonNull TerminowMcpBridge.Share share,
                          @NonNull TerminowMcpBridge.Callback callback) {
            started.put(task.id, callback);
        }

        @Override
        public void cancel(@NonNull String taskId) {
            cancelled.add(taskId);
        }

        void output(String id, String chunk) { started.get(id).output(id, chunk); }
        void waiting(String id, String action) { started.get(id).waitingForUser(id, action); }
        void resumed(String id) { started.get(id).resumed(id); }
        void complete(String id, int code, String result) { started.get(id).completed(id, code, result); }
        void confirmCancel(String id) { started.get(id).cancelled(id); }
    }

    /** Persistence backed by a JSON snapshot, so a second bridge can reload it like after a restart. */
    private static final class JsonPersistence implements TerminowMcpBridge.Persistence {
        private String snapshot = "[]";

        @Override
        public void save(@NonNull List<TerminowMcpBridgeTask> tasks) {
            JSONArray array = new JSONArray();
            try {
                for (TerminowMcpBridgeTask task : tasks) array.put(task.toJson());
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            snapshot = array.toString();
        }

        @NonNull
        @Override
        public List<TerminowMcpBridgeTask> load() {
            List<TerminowMcpBridgeTask> out = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(snapshot);
                for (int i = 0; i < array.length(); i++) out.add(TerminowMcpBridgeTask.fromJson(array.getJSONObject(i)));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
            return out;
        }
    }
}
