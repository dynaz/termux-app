package com.termux.app.terminow.mcp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Moves JSON-RPC messages between TermiNow and one MCP server. A transport knows nothing of the
 * protocol on top: {@link McpSession} builds the messages, and the transport delivers them and
 * the response with the same id. Several requests can wait at the same time.
 */
public interface McpTransport {

    /** The largest message that is read from a server, which can carry images. */
    int MAX_MESSAGE_BYTES = 8 * 1024 * 1024;

    interface Listener {
        /** A request of the server, answered with {@link #respond(JSONObject)}. */
        void onServerRequest(@NonNull JSONObject request);

        void onNotification(@NonNull JSONObject notification);

        /** The transport stopped by itself, not because {@link #close()} was called. */
        void onClosed(@NonNull McpException reason);
    }

    @FunctionalInterface
    interface Factory {
        @NonNull
        McpTransport create() throws McpException;
    }

    void start(@NonNull Listener listener) throws McpException;

    /**
     * The protocol version that the requests declare, {@code null} before one is agreed on.
     * Only the HTTP transport sends it, as a header.
     */
    void setProtocolVersion(@Nullable String version);

    /** Send a request and wait for the response with its id, which has a result or an error. */
    @NonNull
    JSONObject request(@NonNull JSONObject message, @NonNull String method, long timeoutMs) throws McpException;

    void notify(@NonNull JSONObject message, @NonNull String method) throws McpException;

    /** Send the response to a request of the server. */
    void respond(@NonNull JSONObject response) throws McpException;

    /** Stop waiting for the response to a request, which then fails with {@link McpException.Kind#CANCELLED}. */
    void cancel(@NonNull Object id);

    /** Stop the transport and everything that waits on it. Calling it again does nothing. */
    void close();

}
