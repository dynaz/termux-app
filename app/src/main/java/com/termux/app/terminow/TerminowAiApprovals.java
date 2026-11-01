package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The pending-approval registry of the Local AI Bridge: the piece that turns an
 * {@code APPROVAL_REQUIRED} dead end into an actionable prompt. When {@link TerminowAiBridge}
 * reaches a host that needs the user's agreement, it parks the request here and blocks on
 * {@link #await}; the UI lists {@link #list()} and resolves it with {@link #decide}, and the parked
 * call resumes with the decision — no "type Retry" step.
 *
 * <p>Isolation and safety are the point:</p>
 * <ul>
 *   <li>A request is bound to the authenticated client/session, the host UUID, the capability and,
 *       for command execution, the exact command. A decision in one session never authorizes
 *       another: the binding key carries the client id.</li>
 *   <li>Identical, still-open requests are de-duplicated onto one prompt, so a double tap or a
 *       duplicate call raises a single popup and one decision resolves them together.</li>
 *   <li>Deny, dismissal, timeout, and cancellation (a closed connection or a revoked grant) each
 *       resolve to a non-allowing decision, so the action is never carried out by accident.</li>
 *   <li>A decision is applied at most once: {@link #decide} is idempotent and a resolved request is
 *       never re-opened.</li>
 * </ul>
 *
 * <p>No secret is held here: only non-secret facts a prompt must show (alias, address, port,
 * capability, the command the user is approving). It is pure enough to test without a device.</p>
 */
public final class TerminowAiApprovals {

    /** How a parked request ended. Only {@link #ALLOW_ONCE} and {@link #ALLOW_SESSION} proceed. */
    public enum Decision {
        /** Allow just this one parked request; grant nothing that outlives it. */
        ALLOW_ONCE,
        /** Allow and remember for this client/session until it expires or the session ends. */
        ALLOW_SESSION,
        /** The user said no. */
        DENY,
        /** No decision within the window. */
        TIMEOUT,
        /** The request could not stand any more: the connection closed, or access was revoked. */
        CANCELLED
    }

    /** A request to park: the non-secret facts a prompt needs, plus how it is bound and how long it waits. */
    public static final class Spec {
        public final String envKey;
        @Nullable public final String clientId;
        public final String clientName;
        public final String sessionLabel;
        public final String hostUuid;
        public final String hostAlias;
        public final String hostAddress;
        public final int hostPort;
        public final String capability;
        @Nullable public final String command;
        public final String fingerprint;
        public final boolean sessionBindable;
        public final long timeoutMs;

        public Spec(@NonNull String envKey, @Nullable String clientId, @NonNull String clientName,
                    @NonNull String sessionLabel, @NonNull String hostUuid, @NonNull String hostAlias,
                    @NonNull String hostAddress, int hostPort, @NonNull String capability,
                    @Nullable String command, @NonNull String fingerprint, boolean sessionBindable, long timeoutMs) {
            this.envKey = envKey;
            this.clientId = clientId;
            this.clientName = clientName;
            this.sessionLabel = sessionLabel;
            this.hostUuid = hostUuid;
            this.hostAlias = hostAlias;
            this.hostAddress = hostAddress;
            this.hostPort = hostPort;
            this.capability = capability;
            this.command = command;
            this.fingerprint = fingerprint;
            this.sessionBindable = sessionBindable;
            this.timeoutMs = timeoutMs;
        }

        /** What makes two requests "the same" for de-duplication: same session, host, capability and command. */
        @NonNull
        String dedupKey() {
            return envKey + "\u0000" + (clientId == null ? "" : clientId) + "\u0000" + hostUuid + "\u0000"
                + capability + "\u0000" + (command == null ? "" : command);
        }
    }

    /** A parked request as the UI sees it: identity, the facts to show, and timing. Immutable facts; live state via the registry. */
    public static final class Request {
        public final String id;
        public final Spec spec;
        public final long createdAt;
        public final long expiresAt;

        private Decision decision;      // null until resolved
        private boolean resolved;
        private int waiters;

        Request(@NonNull String id, @NonNull Spec spec, long now) {
            this.id = id;
            this.spec = spec;
            this.createdAt = now;
            this.expiresAt = now + spec.timeoutMs;
        }
    }

    /** Told when the pending set changes, so the UI can show, refresh, or dismiss a prompt. Must not block. */
    public interface Listener {
        void onApprovalsChanged();
    }

    private final Object mLock = new Object();
    private final Map<String, Request> mById = new LinkedHashMap<>();
    private final Map<String, String> mLiveKeyToId = new LinkedHashMap<>();
    private final AtomicLong mSeq = new AtomicLong(1);
    @Nullable private volatile Listener mListener;

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    private void fireChanged() {
        Listener listener = mListener;
        if (listener != null) listener.onApprovalsChanged();
    }

    /**
     * Park a request and block the calling thread until it is resolved, by a user decision, the
     * timeout, or a cancellation. An identical still-open request is joined rather than duplicated.
     * Returns the decision; never {@code null}.
     */
    @NonNull
    public Decision await(@NonNull Spec spec) {
        Request request;
        boolean created = false;
        synchronized (mLock) {
            long now = System.currentTimeMillis();
            String existingId = mLiveKeyToId.get(spec.dedupKey());
            Request existing = existingId == null ? null : mById.get(existingId);
            if (existing != null && !existing.resolved && existing.expiresAt > now) {
                request = existing;
            } else {
                String id = "req-" + mSeq.getAndIncrement();
                request = new Request(id, spec, now);
                mById.put(id, request);
                mLiveKeyToId.put(spec.dedupKey(), id);
                created = true;
            }
            request.waiters++;
        }
        if (created) fireChanged();

        Decision decision;
        boolean resolvedHere = false;
        synchronized (mLock) {
            while (!request.resolved) {
                long remaining = request.expiresAt - System.currentTimeMillis();
                if (remaining <= 0) {
                    resolveLocked(request, Decision.TIMEOUT);
                    resolvedHere = true;
                    break;
                }
                try {
                    mLock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    resolveLocked(request, Decision.CANCELLED);
                    resolvedHere = true;
                    break;
                }
            }
            request.waiters--;
            if (request.waiters <= 0) {
                mById.remove(request.id);
                // Only unmap the live key if it still points at this request (a newer one may own it).
                if (request.id.equals(mLiveKeyToId.get(spec.dedupKey()))) mLiveKeyToId.remove(spec.dedupKey());
            }
            decision = request.decision;
        }
        // A timeout/cancel resolved inside this call still changes the pending set the UI shows.
        if (resolvedHere) fireChanged();
        return decision == null ? Decision.CANCELLED : decision;
    }

    /** Resolve a parked request. Idempotent: a second call, or one for an unknown id, changes nothing and returns false. */
    public boolean decide(@NonNull String id, @NonNull Decision decision) {
        if (decision != Decision.ALLOW_ONCE && decision != Decision.ALLOW_SESSION && decision != Decision.DENY)
            throw new IllegalArgumentException("decide takes ALLOW_ONCE, ALLOW_SESSION or DENY");
        boolean resolved;
        synchronized (mLock) {
            Request request = mById.get(id);
            if (request == null || request.resolved) return false;
            // A session grant can only be offered where the session can be bound; guard it here too.
            Decision effective = decision == Decision.ALLOW_SESSION && !request.spec.sessionBindable
                ? Decision.ALLOW_ONCE : decision;
            resolveLocked(request, effective);
            resolved = true;
        }
        if (resolved) fireChanged();
        return true;
    }

    private void resolveLocked(@NonNull Request request, @NonNull Decision decision) {
        request.decision = decision;
        request.resolved = true;
        mLiveKeyToId.remove(request.spec.dedupKey());
        mLock.notifyAll();
    }

    /** Cancel every open request of a client/session (e.g. that session disconnected). */
    public void cancelForClient(@NonNull String envKey, @Nullable String clientId) {
        cancelMatching(r -> r.spec.envKey.equals(envKey)
            && java.util.Objects.equals(r.spec.clientId, clientId));
    }

    /** Cancel every open request of an environment (its token was revoked). */
    public void cancelForEnv(@NonNull String envKey) {
        cancelMatching(r -> r.spec.envKey.equals(envKey));
    }

    /** Cancel every open request for a host (access disabled, credentials changed, or removed). */
    public void cancelForHost(@NonNull String hostUuid) {
        cancelMatching(r -> r.spec.hostUuid.equals(hostUuid));
    }

    /** Cancel every open request (the bridge was turned off, or all access revoked). */
    public void cancelAll() {
        cancelMatching(r -> true);
    }

    private interface Match {
        boolean test(@NonNull Request request);
    }

    private void cancelMatching(@NonNull Match match) {
        boolean any = false;
        synchronized (mLock) {
            for (Request request : mById.values()) {
                if (!request.resolved && match.test(request)) {
                    resolveLocked(request, Decision.CANCELLED);
                    any = true;
                }
            }
        }
        if (any) fireChanged();
    }

    /** A snapshot of the open requests, newest last, for the prompt and the pending-request list. */
    @NonNull
    public List<Request> list() {
        synchronized (mLock) {
            long now = System.currentTimeMillis();
            List<Request> open = new ArrayList<>();
            for (Request request : mById.values()) {
                if (!request.resolved && request.expiresAt > now) open.add(request);
            }
            return open;
        }
    }

    /** The open request with this id, or {@code null} if it is gone or resolved. For the prompt to re-read. */
    @Nullable
    public Request get(@NonNull String id) {
        synchronized (mLock) {
            Request request = mById.get(id);
            return request != null && !request.resolved ? request : null;
        }
    }

    /** How many requests are open now, for status. */
    public int pendingCount() {
        return list().size();
    }
}
