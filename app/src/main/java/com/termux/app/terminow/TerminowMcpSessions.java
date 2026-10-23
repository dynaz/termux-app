package com.termux.app.terminow;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.BuildConfig;
import com.termux.app.terminow.mcp.McpException;
import com.termux.app.terminow.mcp.McpHttpTransport;
import com.termux.app.terminow.mcp.McpSession;
import com.termux.app.terminow.mcp.McpTransport;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The live MCP sessions of the MCP page, by profile id. They last as long as the activity, which
 * closes them all when it is destroyed. Listeners hear about changes on the main thread.
 */
final class TerminowMcpSessions {

    interface Listener {
        void onChanged(@NonNull String profileId, @NonNull McpSession.Snapshot snapshot);
    }

    private static final long HTTP_REQUEST_TIMEOUT_MS = 30_000;
    /** A local server may first download its package, like npx -y does. */
    private static final long STDIO_REQUEST_TIMEOUT_MS = 180_000;

    private final Context mContext;
    private final TerminowMcpTargets mTargets;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Map<String, McpSession> mSessions = new HashMap<>();
    private final List<Listener> mListeners = new ArrayList<>();

    TerminowMcpSessions(@NonNull Context context, @NonNull TerminowMcpTargets targets) {
        mContext = context;
        mTargets = targets;
    }

    @Nullable
    McpSession get(@NonNull String profileId) {
        return mSessions.get(profileId);
    }

    /** The state of the session of a profile, {@code null} if it has none. */
    @Nullable
    McpSession.Snapshot getSnapshot(@NonNull String profileId) {
        McpSession session = mSessions.get(profileId);
        return session != null ? session.getSnapshot() : null;
    }

    /** The session of the profile, made for the profile as it is now if there is none yet. */
    @NonNull
    McpSession open(@NonNull TerminowMcpProfile profile) {
        McpSession session = mSessions.get(profile.id);
        if (session != null) return session;
        McpTransport.Factory factory;
        McpSession.Handshake handshake;
        if (profile.isStdio()) {
            factory = TerminowMcpStdio.transportFactory(mContext, mTargets, profile);
            handshake = McpSession.Handshake.LEGACY_FIRST;
        } else {
            factory = httpFactory(profile.url, profile.getRequestHeaders());
            handshake = McpSession.Handshake.MODERN_FIRST;
        }
        session = new McpSession(factory, handshake, new TerminowMcpRedactor(profile.getSecrets()), BuildConfig.VERSION_NAME);
        session.setRequestTimeout(profile.isStdio() ? STDIO_REQUEST_TIMEOUT_MS : HTTP_REQUEST_TIMEOUT_MS);
        String id = profile.id;
        session.setListener(snapshot -> mHandler.post(() -> {
            for (Listener listener : new ArrayList<>(mListeners)) listener.onChanged(id, snapshot);
        }));
        mSessions.put(id, session);
        return session;
    }

    @NonNull
    private static McpTransport.Factory httpFactory(@NonNull String url, @NonNull Map<String, String> headers) {
        URL endpoint;
        try {
            endpoint = new URL(url);
        } catch (MalformedURLException e) {
            return () -> {
                throw new McpException(McpException.Kind.NETWORK, "This is not a valid URL");
            };
        }
        return () -> new McpHttpTransport(endpoint, headers, BuildConfig.VERSION_NAME);
    }

    /** Close and forget the session of a profile, for example after the profile changed. */
    void remove(@NonNull String profileId) {
        McpSession session = mSessions.remove(profileId);
        if (session != null) new Thread(session::close).start();
    }

    void closeAll() {
        List<McpSession> sessions = new ArrayList<>(mSessions.values());
        mSessions.clear();
        if (!sessions.isEmpty()) new Thread(() -> {
            for (McpSession session : sessions) session.close();
        }).start();
    }

    void addListener(@NonNull Listener listener) {
        mListeners.add(listener);
    }

    void removeListener(@NonNull Listener listener) {
        mListeners.remove(listener);
    }

}
