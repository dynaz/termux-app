package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.TerminowMcpRedactor;

import org.json.JSONObject;

/** Why talking to an MCP server failed. The kind decides what the user is told to do next. */
public class McpException extends Exception {

    public enum Kind {
        NETWORK,
        TLS,
        TIMEOUT,
        CANCELLED,
        /** The server needs authentication, and says how to get it with OAuth. */
        OAUTH_REQUIRED,
        /** The server rejected the credentials, or needs some that were not given. */
        AUTH_FAILED,
        FORBIDDEN,
        REDIRECT,
        HTTP_ERROR,
        /** The server answered, but not with valid MCP messages. */
        INVALID_RESPONSE,
        UNSUPPORTED_VERSION,
        /** The server only speaks the deprecated HTTP+SSE transport of revision 2024-11-05. */
        OLD_SSE_TRANSPORT,
        /** The server answered with a JSON-RPC error. */
        SERVER_ERROR,
        /** The local server process ended. */
        PROCESS_EXITED,
        PROCESS_START_FAILED
    }

    @NonNull public final Kind kind;
    /** The HTTP status of the response, 0 if there was none. */
    public final int httpStatus;
    /** For {@link Kind#OAUTH_REQUIRED}, the protected resource metadata URL, if given. */
    @NonNull public final String resourceMetadata;
    /** The JSON-RPC error of the server, if it answered with one. */
    @Nullable public final JSONObject rpcError;

    public McpException(@NonNull Kind kind, @NonNull String message) {
        this(kind, message, 0, "", null);
    }

    public McpException(@NonNull Kind kind, @NonNull String message, int httpStatus) {
        this(kind, message, httpStatus, "", null);
    }

    public McpException(@NonNull Kind kind, @NonNull String message, int httpStatus, @NonNull String resourceMetadata,
                        @Nullable JSONObject rpcError) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.resourceMetadata = resourceMetadata;
        this.rpcError = rpcError;
    }

    /** The same failure with the secrets removed from its message. */
    @NonNull
    public McpException redact(@NonNull TerminowMcpRedactor redactor) {
        String message = String.valueOf(getMessage());
        String redacted = redactor.redact(message);
        if (redacted.equals(message)) return this;
        return new McpException(kind, redacted, httpStatus, resourceMetadata, rpcError);
    }

}
