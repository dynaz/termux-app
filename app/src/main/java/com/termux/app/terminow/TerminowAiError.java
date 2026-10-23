package com.termux.app.terminow;

import androidx.annotation.NonNull;

/**
 * The failures the Local AI Bridge returns to Claude, kept apart so network, authentication,
 * policy and filesystem problems are never confused for one another. The message is safe to show:
 * it carries no secret. Secrets are redacted before anything leaves the bridge.
 */
public enum TerminowAiError {
    HOST_NOT_AUTHORIZED,
    CAPABILITY_DENIED,
    KEYCHAIN_LOCKED,
    APPROVAL_REQUIRED,
    HOST_KEY_UNKNOWN,
    HOST_KEY_CHANGED,
    AUTH_FAILED,
    NETWORK_UNREACHABLE,
    REMOTE_PATH_DENIED,
    LOCAL_PATH_DENIED,
    TIMEOUT,
    CANCELLED,
    BRIDGE_DISCONNECTED,
    BAD_REQUEST,
    // The MCP Bridge (sessions and managed tasks) adds these; the reasons never mix up an
    // unknown client, an unshared or changed session, a full queue and a missing task.
    CLIENT_NOT_AUTHORIZED,
    SESSION_NOT_FOUND,
    SESSION_NOT_SHARED,
    SESSION_UNSUPPORTED,
    SESSION_CHANGED,
    QUEUE_FULL,
    TASK_NOT_FOUND,
    PREMIUM_REQUIRED,
    INTERNAL;

    /** Thrown inside the bridge; its {@link #error} is the code returned to Claude. */
    public static final class Exception extends java.lang.Exception {
        public final TerminowAiError error;

        public Exception(@NonNull TerminowAiError error, @NonNull String message) {
            super(message);
            this.error = error;
        }
    }

    @NonNull
    public TerminowAiError.Exception of(@NonNull String message) {
        return new TerminowAiError.Exception(this, message);
    }
}
