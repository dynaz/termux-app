package com.termux.app.terminow;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the Ubuntu MCP helper lives in a Linux environment and how Claude Code is told to run it.
 * The registration carries the bridge's transport and the path of a token file, never the token
 * itself, so no secret is written into {@code ~/.claude.json}. Registration is idempotent through
 * {@link TerminowClaudeConfig}: the same helper is left unchanged, a changed one is updated, and
 * other servers are kept.
 *
 * <p>The transport is chosen after the on-device probe: a private Unix socket if proot carries it,
 * otherwise an authenticated loopback port. Only this descriptor changes between the two; the
 * helper and the registration are the same.</p>
 */
public final class TerminowAiHelper {

    /** The name of the MCP server entry, the same the bridge answers as. */
    public static final String SERVER_NAME = TerminowAiMcpServer.SERVER_NAME;

    /** The helper asset inside the APK. */
    public static final String HELPER_ASSET = "terminow-ai/terminow-ai-bridge.py";

    /** The `terminow` CLI wrapper asset and where it installs in the guest (on ~/.local/bin). */
    public static final String CLI_ASSET = "terminow-ai/terminow";
    public static final String GUEST_CLI_REL = ".local/bin/terminow";
    /** Where the helper is installed in the guest home. */
    public static final String GUEST_HELPER_REL = ".local/share/terminow-ai/terminow-ai-bridge.py";
    /** Where the token file is kept in the guest home, with 0600 permissions. */
    public static final String GUEST_TOKEN_REL = ".config/terminow-ai/token";
    /** Where the bridge's current loopback port is kept, refreshed on every bind, read fresh by the helper. */
    public static final String GUEST_PORT_REL = ".config/terminow-ai/port";

    private TerminowAiHelper() {}

    /** How the helper reaches the bridge. The probe decides which is used; the helper supports both. */
    public static final class Transport {
        public enum Kind { UNIX, TCP }

        public final Kind kind;
        public final String socketPath;
        public final String host;
        public final int port;

        private Transport(Kind kind, @NonNull String socketPath, @NonNull String host, int port) {
            this.kind = kind;
            this.socketPath = socketPath;
            this.host = host;
            this.port = port;
        }

        @NonNull
        public static Transport unix(@NonNull String socketPath) {
            return new Transport(Kind.UNIX, socketPath, "", 0);
        }

        @NonNull
        public static Transport loopback(int port) {
            return new Transport(Kind.TCP, "", "127.0.0.1", port);
        }
    }

    @NonNull
    public static String guestHelperPath(@NonNull String guestHome) {
        return join(guestHome, GUEST_HELPER_REL);
    }

    @NonNull
    public static String guestTokenPath(@NonNull String guestHome) {
        return join(guestHome, GUEST_TOKEN_REL);
    }

    @NonNull
    public static String guestPortPath(@NonNull String guestHome) {
        return join(guestHome, GUEST_PORT_REL);
    }

    @NonNull
    private static String join(@NonNull String home, @NonNull String rel) {
        return (home.endsWith("/") ? home.substring(0, home.length() - 1) : home) + "/" + rel;
    }

    /** The environment Claude Code gives the helper: the transport and the token file, no secret. */
    @NonNull
    public static Map<String, String> registrationEnv(@NonNull Transport transport, @NonNull String guestTokenFile,
                                                      @NonNull String guestPortFile) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("TERMINOW_AI_TOKEN_FILE", guestTokenFile);
        if (transport.kind == Transport.Kind.UNIX) {
            env.put("TERMINOW_AI_TRANSPORT", "unix");
            env.put("TERMINOW_AI_SOCK", transport.socketPath);
        } else {
            env.put("TERMINOW_AI_TRANSPORT", "tcp");
            env.put("TERMINOW_AI_HOST", transport.host);
            // The port at registration is a starting value; the helper reads the live port from the
            // port file each time it connects, so a bridge restart on a different port still works.
            env.put("TERMINOW_AI_PORT", String.valueOf(transport.port));
            env.put("TERMINOW_AI_PORT_FILE", guestPortFile);
        }
        return env;
    }

    /** The {@code mcpServers} entry that runs the helper with python3, carrying no secret. */
    @NonNull
    public static JSONObject registrationEntry(@NonNull String guestHelperPath, @NonNull Transport transport,
                                               @NonNull String guestTokenFile, @NonNull String guestPortFile) throws JSONException {
        return TerminowClaudeConfig.buildStdioEntry("python3", Collections.singletonList(guestHelperPath),
            registrationEnv(transport, guestTokenFile, guestPortFile));
    }
}
