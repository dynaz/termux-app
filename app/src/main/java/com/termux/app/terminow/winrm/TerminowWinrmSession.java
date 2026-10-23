package com.termux.app.terminow.winrm;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.app.terminow.TerminowRdpBridge;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A WinRM connection: pywinrm of the Linux environment, in a process of its own, that runs the
 * PowerShell commands of the console on Windows.
 *
 * The program gets its settings, the password among them, as one JSON line on its standard
 * input: never as an argument, in the environment, in a file or in a log. It answers with JSON
 * lines. Each command runs in a new PowerShell of the same WinRM shell, so variables do not stay
 * from one command to the next; the working folder of the shell does.
 */
public final class TerminowWinrmSession {

    public enum State { STARTING, CONNECTED, DISCONNECTED, FAILED }

    /** Where and how to connect. The password is only kept until it was given to the program. */
    public static final class Target {
        public String title = "";
        public String hostKey = "";
        public String address = "";
        public int port = 5986;
        public boolean https = true;
        public boolean verify = true;
        public String username = "";
        public String domain = "";
        public String password = "";
        public String container = "";
    }

    public interface Listener {
        void onState(@NonNull TerminowWinrmSession session);

        /** Output of a command: its standard output, its errors, and its exit code. */
        void onResult(int id, @NonNull String stdout, @NonNull String stderr, int code);

        /** A command could not run, like when the connection broke. */
        void onCommandError(int id, @NonNull String message);
    }

    /** The program in the Linux environment, run with python3 -I. It reads only standard input. */
    static final String BRIDGE = String.join("\n",
        "import base64, json, re, sys",
        "def emit(**event):",
        "    sys.stdout.write(json.dumps(event) + '\\n')",
        "    sys.stdout.flush()",
        "try:",
        "    import winrm",
        "except ImportError:",
        "    emit(event='missing', what='python3-winrm')",
        "    sys.exit(3)",
        "def clixml(text):",
        "    if not text.startswith('#< CLIXML'):",
        "        return text",
        "    parts = re.findall(r'<S S=\"Error\">(.*?)</S>', text, re.S)",
        "    out = ''.join(parts).replace('_x000D__x000A_', '\\n')",
        "    return out.replace('&lt;', '<').replace('&gt;', '>').replace('&quot;', '\"').replace('&amp;', '&')",
        "config = json.loads(sys.stdin.readline())",
        "host = config['address']",
        "if ':' in host and not host.startswith('['):",
        "    host = '[' + host + ']'",
        "endpoint = '%s://%s:%d/wsman' % ('https' if config['https'] else 'http', host, config['port'])",
        "user = config['username'] if not config['domain'] else config['domain'] + '\\\\' + config['username']",
        "try:",
        "    protocol = winrm.Protocol(endpoint, transport='ntlm', username=user, password=config['password'],",
        "        server_cert_validation='validate' if config['verify'] else 'ignore', message_encryption='auto',",
        "        read_timeout_sec=130, operation_timeout_sec=120)",
        "    config['password'] = ''",
        "    shell = protocol.open_shell(codepage=65001)",
        "except Exception as e:",
        "    emit(event='failed', message='%s: %s' % (type(e).__name__, e))",
        "    sys.exit(2)",
        "emit(event='connected')",
        "for line in sys.stdin:",
        "    try:",
        "        request = json.loads(line)",
        "    except ValueError:",
        "        continue",
        "    if request.get('cmd') == 'exit':",
        "        break",
        "    script = request.get('ps', '')",
        "    encoded = base64.b64encode(script.encode('utf-16-le')).decode('ascii')",
        "    try:",
        "        command = protocol.run_command(shell, 'powershell', ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded])",
        "        stdout, stderr, code = protocol.get_command_output(shell, command)",
        "        protocol.cleanup_command(shell, command)",
        "        emit(event='result', id=request.get('id'), stdout=stdout.decode('utf-8', 'replace'),",
        "             stderr=clixml(stderr.decode('utf-8', 'replace')), code=code)",
        "    except Exception as e:",
        "        emit(event='error', id=request.get('id'), message='%s: %s' % (type(e).__name__, e))",
        "try:",
        "    protocol.close_shell(shell)",
        "except Exception:",
        "    pass",
        "");

    private static final Map<String, TerminowWinrmSession> SESSIONS = Collections.synchronizedMap(new LinkedHashMap<>());

    public final String id = UUID.randomUUID().toString();
    public final Target target;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile State mState = State.STARTING;
    private volatile String mError = "";
    @Nullable private volatile Listener mListener;
    @Nullable private Process mProcess;
    @Nullable private OutputStream mInput;
    private int mNextId = 1;
    private volatile long mLastUsed = System.currentTimeMillis();
    /** What was shown in the console, for the screen when it is opened again. */
    private final StringBuilder mTranscript = new StringBuilder();

    private TerminowWinrmSession(@NonNull Context context, @NonNull Target target) {
        mContext = context.getApplicationContext();
        this.target = target;
    }

    @NonNull
    public static TerminowWinrmSession start(@NonNull Context context, @NonNull Target target) {
        TerminowWinrmSession session = new TerminowWinrmSession(context, target);
        SESSIONS.put(session.id, session);
        new Thread(session::run, "TerminowWinrm").start();
        return session;
    }

    @Nullable
    public static TerminowWinrmSession get(@NonNull String id) {
        return SESSIONS.get(id);
    }

    /**
     * Connections of this process that ended, newest first, one per saved host and none of a
     * host that has an open one: they are connected to again, never resumed.
     */
    @NonNull
    public static List<TerminowWinrmSession> ended() {
        List<TerminowWinrmSession> out = new ArrayList<>();
        java.util.Set<String> hosts = new java.util.HashSet<>();
        synchronized (SESSIONS) {
            for (TerminowWinrmSession session : SESSIONS.values()) if (session.isOpen()) hosts.add(session.target.hostKey);
            List<TerminowWinrmSession> all = new ArrayList<>(SESSIONS.values());
            java.util.Collections.reverse(all);
            for (TerminowWinrmSession session : all)
                if (!session.isOpen() && hosts.add(session.target.hostKey)) out.add(session);
        }
        return out;
    }

    @NonNull
    public static List<TerminowWinrmSession> open() {
        List<TerminowWinrmSession> out = new ArrayList<>();
        synchronized (SESSIONS) {
            for (TerminowWinrmSession session : SESSIONS.values()) if (session.isOpen()) out.add(0, session);
        }
        return out;
    }

    @NonNull
    public State getState() {
        return mState;
    }

    @NonNull
    public String getError() {
        return mError;
    }

    public boolean isOpen() {
        return mState == State.STARTING || mState == State.CONNECTED;
    }

    public long getLastUsed() {
        return mLastUsed;
    }

    @NonNull
    public synchronized String getTranscript() {
        return mTranscript.toString();
    }

    public synchronized void appendTranscript(@NonNull String text) {
        mTranscript.append(text);
        if (mTranscript.length() > 200_000) mTranscript.delete(0, mTranscript.length() - 150_000);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
        if (listener != null) {
            mLastUsed = System.currentTimeMillis();
            listener.onState(this);
        }
    }

    /** Run PowerShell on Windows; the result comes with the id that this returns. */
    public synchronized int run(@NonNull String script) {
        int requestId = mNextId++;
        mLastUsed = System.currentTimeMillis();
        OutputStream input = mInput;
        if (input == null || mState != State.CONNECTED) {
            int failed = requestId;
            mMain.post(() -> {
                Listener listener = mListener;
                if (listener != null) listener.onCommandError(failed, "not connected");
            });
            return requestId;
        }
        try {
            JSONObject request = new JSONObject().put("cmd", "run").put("id", requestId).put("ps", script);
            input.write((request.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
        } catch (IOException | JSONException e) {
            int failed = requestId;
            mMain.post(() -> {
                Listener listener = mListener;
                if (listener != null) listener.onCommandError(failed, String.valueOf(e.getMessage()));
            });
        }
        return requestId;
    }

    public void disconnect() {
        Process process;
        synchronized (this) {
            process = mProcess;
            if (mInput != null) {
                try {
                    mInput.write("{\"cmd\":\"exit\"}\n".getBytes(StandardCharsets.UTF_8));
                    mInput.flush();
                } catch (IOException e) {
                    // It ended already
                }
            }
        }
        if (process != null) {
            Process ending = process;
            new Thread(() -> {
                try {
                    if (!ending.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) ending.destroy();
                } catch (InterruptedException e) {
                    ending.destroy();
                }
            }, "TerminowWinrmEnd").start();
        }
        setState(State.DISCONNECTED, "");
    }

    private void setState(@NonNull State state, @NonNull String error) {
        if (mState == State.DISCONNECTED || mState == State.FAILED) return;
        mState = state;
        if (!error.isEmpty()) mError = error;
        mMain.post(() -> {
            Listener listener = mListener;
            if (listener != null) listener.onState(this);
        });
    }

    private void run() {
        Process process;
        try {
            process = TerminowRdpBridge.startInContainer(mContext, target.container, Arrays.asList("/usr/bin/python3", "-I", "-c", BRIDGE));
        } catch (IOException e) {
            setState(State.FAILED, "START|" + e.getMessage());
            return;
        }
        synchronized (this) {
            mProcess = process;
            mInput = process.getOutputStream();
        }
        StringBuilder errors = new StringBuilder();
        Thread errorReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (errors) {
                        if (errors.length() < 4000) errors.append(line).append('\n');
                    }
                }
            } catch (IOException e) {
                // Ended
            }
        }, "TerminowWinrmErrors");
        errorReader.setDaemon(true);
        errorReader.start();
        try {
            JSONObject config = new JSONObject().put("address", target.address).put("port", target.port).put("https", target.https)
                .put("verify", target.verify).put("username", target.username).put("domain", target.domain).put("password", target.password);
            OutputStream input = process.getOutputStream();
            input.write((config.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            input.flush();
        } catch (IOException | JSONException e) {
            setState(State.FAILED, "START|" + e.getMessage());
            process.destroy();
            return;
        } finally {
            target.password = "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) handle(line);
        } catch (IOException e) {
            // Ended
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            exit = -1;
        }
        String text;
        synchronized (errors) {
            text = errors.toString().trim();
        }
        if (mState == State.STARTING) setState(State.FAILED, (exit == 3 ? "MISSING|" : "ENDED|") + text);
        else setState(State.DISCONNECTED, text.isEmpty() ? "" : "ENDED|" + text);
    }

    private void handle(@NonNull String line) {
        JSONObject event;
        try {
            event = new JSONObject(line);
        } catch (JSONException e) {
            return;
        }
        switch (event.optString("event")) {
            case "connected":
                setState(State.CONNECTED, "");
                break;
            case "missing":
                setState(State.FAILED, "MISSING|" + event.optString("what"));
                break;
            case "failed":
                setState(State.FAILED, "CONNECT|" + event.optString("message"));
                break;
            case "result": {
                int id = event.optInt("id");
                String stdout = event.optString("stdout");
                String stderr = event.optString("stderr");
                int code = event.optInt("code");
                mMain.post(() -> {
                    Listener listener = mListener;
                    if (listener != null) listener.onResult(id, stdout, stderr, code);
                });
                break;
            }
            case "error": {
                int id = event.optInt("id");
                String message = event.optString("message");
                mMain.post(() -> {
                    Listener listener = mListener;
                    if (listener != null) listener.onCommandError(id, message);
                });
                break;
            }
            default:
                break;
        }
    }

    /**
     * Check that pywinrm is in the environment, without connecting: 0 when it is, 3 when it is
     * missing, anything else when the environment did not run.
     */
    public static int checkDependencies(@NonNull Context context, @NonNull String container) {
        try {
            Process process = TerminowRdpBridge.startInContainer(context, container, Arrays.asList("/usr/bin/python3", "-I", "-c",
                "import sys\ntry:\n    import winrm\nexcept ImportError:\n    sys.exit(3)\n"));
            process.getOutputStream().close();
            if (!process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy();
                return -1;
            }
            return process.exitValue();
        } catch (IOException | InterruptedException e) {
            return -1;
        }
    }
}
