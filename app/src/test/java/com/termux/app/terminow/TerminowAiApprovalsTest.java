package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The pending-approval registry: parking a request, resuming it on a decision, de-duplication,
 * timeout, cancellation and idempotency. Uses real threads, since the point is that a parked caller
 * resumes; the waits are short so the suite stays fast.
 */
public class TerminowAiApprovalsTest {

    private static TerminowAiApprovals.Spec spec(String client, String host, String cap, String command,
                                                 boolean bindable, long timeoutMs) {
        return new TerminowAiApprovals.Spec("ubuntu", client, "Claude Code", "ubuntu · session",
            host, "Alias", "10.0.0.1", 22, cap, command, "fp", bindable, timeoutMs);
    }

    /** Run await on a background thread, returning a holder that fills in when it resolves. */
    private static AtomicReference<TerminowAiApprovals.Decision> awaitAsync(TerminowAiApprovals approvals,
                                                                            TerminowAiApprovals.Spec spec) {
        AtomicReference<TerminowAiApprovals.Decision> result = new AtomicReference<>();
        Thread thread = new Thread(() -> result.set(approvals.await(spec)), "await-test");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    /** Wait until exactly {@code count} requests are parked, or fail. */
    private static void waitForPending(TerminowAiApprovals approvals, int count) throws InterruptedException {
        for (int i = 0; i < 200 && approvals.pendingCount() != count; i++) Thread.sleep(5);
        assertEquals(count, approvals.pendingCount());
    }

    private static void waitResolved(AtomicReference<TerminowAiApprovals.Decision> ref) throws InterruptedException {
        for (int i = 0; i < 200 && ref.get() == null; i++) Thread.sleep(5);
        assertNotNull("the parked caller did not resume", ref.get());
    }

    @Test
    public void decideResumesTheParkedCaller() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        AtomicReference<TerminowAiApprovals.Decision> result =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null, true, 5000));
        waitForPending(approvals, 1);
        String id = approvals.list().get(0).id;
        assertTrue(approvals.decide(id, TerminowAiApprovals.Decision.ALLOW_SESSION));
        waitResolved(result);
        assertEquals(TerminowAiApprovals.Decision.ALLOW_SESSION, result.get());
        assertEquals(0, approvals.pendingCount());
    }

    @Test
    public void denyResumesWithDeny() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        AtomicReference<TerminowAiApprovals.Decision> result =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null, true, 5000));
        waitForPending(approvals, 1);
        approvals.decide(approvals.list().get(0).id, TerminowAiApprovals.Decision.DENY);
        waitResolved(result);
        assertEquals(TerminowAiApprovals.Decision.DENY, result.get());
    }

    @Test
    public void timeoutResumesWithTimeout() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        AtomicReference<TerminowAiApprovals.Decision> result =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null, true, 100));
        waitResolved(result);
        assertEquals(TerminowAiApprovals.Decision.TIMEOUT, result.get());
        assertEquals(0, approvals.pendingCount());
    }

    @Test
    public void identicalRequestsShareOnePromptAndOneDecision() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        AtomicReference<TerminowAiApprovals.Decision> a =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_RUN_COMMAND, "ls", true, 5000));
        // Give the first one time to register before the duplicate joins it.
        waitForPending(approvals, 1);
        AtomicReference<TerminowAiApprovals.Decision> b =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_RUN_COMMAND, "ls", true, 5000));
        Thread.sleep(50);
        assertEquals("a duplicate must not raise a second prompt", 1, approvals.pendingCount());
        approvals.decide(approvals.list().get(0).id, TerminowAiApprovals.Decision.ALLOW_ONCE);
        waitResolved(a);
        waitResolved(b);
        assertEquals(TerminowAiApprovals.Decision.ALLOW_ONCE, a.get());
        assertEquals(TerminowAiApprovals.Decision.ALLOW_ONCE, b.get());
    }

    @Test
    public void differentCommandsAreNotDeduplicated() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_RUN_COMMAND, "ls", true, 5000));
        awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_RUN_COMMAND, "rm -rf x", true, 5000));
        waitForPending(approvals, 2);
    }

    @Test
    public void cancelForClientWakesTheWaiter() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        AtomicReference<TerminowAiApprovals.Decision> result =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null, true, 5000));
        waitForPending(approvals, 1);
        approvals.cancelForClient("ubuntu", "c1");
        waitResolved(result);
        assertEquals(TerminowAiApprovals.Decision.CANCELLED, result.get());
        assertEquals(0, approvals.pendingCount());
    }

    @Test
    public void cancelForHostWakesTheWaiter() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        AtomicReference<TerminowAiApprovals.Decision> result =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null, true, 5000));
        waitForPending(approvals, 1);
        approvals.cancelForHost("h1");
        waitResolved(result);
        assertEquals(TerminowAiApprovals.Decision.CANCELLED, result.get());
    }

    @Test
    public void decideIsIdempotent() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        AtomicReference<TerminowAiApprovals.Decision> result =
            awaitAsync(approvals, spec("c1", "h1", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null, true, 5000));
        waitForPending(approvals, 1);
        String id = approvals.list().get(0).id;
        assertTrue(approvals.decide(id, TerminowAiApprovals.Decision.ALLOW_ONCE));
        // A second tap (or a double tap) changes nothing.
        assertFalse(approvals.decide(id, TerminowAiApprovals.Decision.DENY));
        waitResolved(result);
        assertEquals(TerminowAiApprovals.Decision.ALLOW_ONCE, result.get());
    }

    @Test
    public void allowSessionDowngradesToAllowOnceWhenSessionCannotBeBound() throws Exception {
        TerminowAiApprovals approvals = new TerminowAiApprovals();
        // sessionBindable = false: no reliable session identity.
        AtomicReference<TerminowAiApprovals.Decision> result =
            awaitAsync(approvals, spec(null, "h1", TerminowStore.AiAccess.CAP_TEST_CONNECTION, null, false, 5000));
        waitForPending(approvals, 1);
        approvals.decide(approvals.list().get(0).id, TerminowAiApprovals.Decision.ALLOW_SESSION);
        waitResolved(result);
        assertEquals(TerminowAiApprovals.Decision.ALLOW_ONCE, result.get());
    }
}
