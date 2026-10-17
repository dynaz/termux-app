package com.termux.app.terminow;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tabs of TermiNow as they are, saved as they change, so that they come back after the app
 * was closed: by Android, a force stop or a restart of the phone. A tab is a local terminal, a
 * terminal of a Linux environment or a connection to a host, with its name, folder, place and
 * whether it was the one shown, and the text it showed last.
 *
 * Only tabs come back, never what ran in them: a restored tab starts a new shell or connects
 * again, and nothing that was typed before is typed again. A host that keeps its terminals in
 * tmux on the server is attached to the same tmux session again, by the id of the tab.
 */
public final class TerminowWorkspace {

    private static final String LOG_TAG = "TerminowWorkspace";

    public static final String KIND_LOCAL = "local";
    public static final String KIND_LINUX = "linux";
    public static final String KIND_HOST = "host";

    /** The previous output that is kept of a tab: its last lines, at most this many characters. */
    static final int OUTPUT_MAX_LINES = 2000;
    static final int OUTPUT_MAX_CHARS = 128 * 1024;
    /** The lines that TermiNow writes into a restored tab around its previous output start with this. */
    static final String MARK = "[TermiNow] ";

    /** A tab as it is saved. */
    public static class Tab {
        /** Lowercase letters and digits, the same as long as the tab is there and after it is restored. */
        public String id = "";
        public String kind = KIND_LOCAL;
        /** The name of the tab, empty for the default one. */
        public String title = "";
        /** The folder of its shell, empty if not known: on this device, or in the environment. */
        public String folder = "";
        /** For a host: its key; for a Linux environment: its alias. */
        public String hostKey = "";
        /** The vault the host is in, "" for the Personal one. */
        public String vault = "";
        public String environment = "";
        public String user = "";
        /** The tmux session of a local or Linux tab, empty for none. */
        public String localTmux = "";
        /** A connection to transfer files with sftp, which connects again as sftp. */
        public boolean sftp;

        /** The tmux session of the tab on the server, for a host that keeps its terminals in tmux. */
        @NonNull
        public String remoteTmuxName() {
            return "terminow-" + id;
        }

        @NonNull
        JSONObject toJson() throws JSONException {
            return new JSONObject().put("id", id).put("kind", kind).put("title", title).put("folder", folder)
                .put("hostKey", hostKey).put("vault", vault).put("environment", environment).put("user", user)
                .put("localTmux", localTmux).put("sftp", sftp);
        }

        @Nullable
        static Tab fromJson(@Nullable JSONObject object) {
            if (object == null) return null;
            Tab tab = new Tab();
            tab.id = object.optString("id");
            tab.kind = object.optString("kind", KIND_LOCAL);
            tab.title = object.optString("title");
            tab.folder = object.optString("folder");
            tab.hostKey = object.optString("hostKey");
            tab.vault = object.optString("vault");
            tab.environment = object.optString("environment");
            tab.user = object.optString("user");
            tab.localTmux = object.optString("localTmux");
            tab.sftp = object.optBoolean("sftp");
            if (!isValidId(tab.id)) return null;
            if (!KIND_LOCAL.equals(tab.kind) && !KIND_LINUX.equals(tab.kind) && !KIND_HOST.equals(tab.kind)) return null;
            if (tab.title.length() > 200) tab.title = tab.title.substring(0, 200);
            return tab;
        }
    }

    /** All tabs in their order, and the one that was shown. */
    public static class State {
        public final List<Tab> tabs = new ArrayList<>();
        public String activeId = "";
        /** When it was saved, in milliseconds. */
        public long saved;

        @NonNull
        String toJson() {
            try {
                JSONArray array = new JSONArray();
                for (Tab tab : tabs) array.put(tab.toJson());
                return new JSONObject().put("version", 1).put("tabs", array).put("active", activeId).put("saved", saved).toString();
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        @NonNull
        static State fromJson(@Nullable String text) {
            State state = new State();
            if (text == null || text.trim().isEmpty()) return state;
            try {
                JSONObject object = new JSONObject(text);
                if (object.optInt("version") != 1) return state;
                JSONArray array = object.optJSONArray("tabs");
                Set<String> ids = new HashSet<>();
                for (int i = 0; array != null && i < array.length() && state.tabs.size() < 100; i++) {
                    Tab tab = Tab.fromJson(array.optJSONObject(i));
                    if (tab != null && ids.add(tab.id)) state.tabs.add(tab);
                }
                state.activeId = object.optString("active");
                state.saved = object.optLong("saved");
            } catch (JSONException e) {
                // A damaged file: no tabs come back, which is what a first start does
            }
            return state;
        }

        @Nullable
        public Tab find(@NonNull String id) {
            for (Tab tab : tabs) if (tab.id.equals(id)) return tab;
            return null;
        }
    }

    public static boolean isValidId(@Nullable String id) {
        return id != null && id.matches("[a-z0-9]{8,32}");
    }

    @NonNull
    public static String newId() {
        byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte b : bytes) id.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return id.toString();
    }

    // ---------------------------------------------------------------------------------------
    // Previous output

    /**
     * The text that a tab showed, to keep: without what TermiNow wrote before it when it was
     * restored, its last lines only, and without trailing empty lines.
     */
    @NonNull
    static String trimOutput(@NonNull String transcript) {
        // A restored tab starts with its earlier output: only what came after it is new
        String text = afterMark(transcript);
        text = text.replaceAll("[\\s]+$", "");
        String[] lines = text.split("\n", -1);
        if (lines.length > OUTPUT_MAX_LINES) {
            StringBuilder kept = new StringBuilder();
            for (int i = lines.length - OUTPUT_MAX_LINES; i < lines.length; i++) {
                if (kept.length() > 0) kept.append('\n');
                kept.append(lines[i]);
            }
            text = kept.toString();
        }
        if (text.length() > OUTPUT_MAX_CHARS) {
            text = text.substring(text.length() - OUTPUT_MAX_CHARS);
            int newline = text.indexOf('\n');
            if (newline >= 0) text = text.substring(newline + 1);
        }
        return text;
    }

    /**
     * The text after the last line that TermiNow wrote into a tab, like the end of its previous
     * output or "Reconnecting": what a check of a new connection must look at, so that an old
     * error is not taken as a new one.
     */
    @NonNull
    public static String afterMark(@NonNull String transcript) {
        int end = transcript.lastIndexOf("\n" + MARK + "──");
        if (end < 0) {
            if (!transcript.startsWith(MARK + "──")) return transcript;
            end = -1;
        }
        int lineEnd = transcript.indexOf('\n', end + 1);
        return lineEnd < 0 ? "" : transcript.substring(lineEnd + 1);
    }

    /** One line of TermiNow in a tab, like "Reconnecting to web", which later checks start after. */
    @NonNull
    public static byte[] markLine(@NonNull String text) {
        return ("\r\n\033[1;2m" + MARK + "── " + text.replaceAll("\\p{Cntrl}", " ") + " ──\033[0m\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * What a restored tab shows before its new shell: its previous output, dimmed, between two
     * lines that say it is not live. Control characters of the saved text are removed, so it can
     * never change the terminal or answer anything.
     */
    @NonNull
    static byte[] prelude(@NonNull String output, @NonNull String header, @NonNull String footer) {
        StringBuilder text = new StringBuilder();
        String dim = "\033[2m", bold = "\033[1;2m", reset = "\033[0m";
        text.append(bold).append(MARK).append("── ").append(header).append(" ──").append(reset).append("\r\n");
        if (!output.isEmpty()) {
            String safe = output.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "").replace("\n", "\r\n");
            text.append(dim).append(safe).append(reset).append("\r\n");
        }
        text.append(bold).append(MARK).append("── ").append(footer).append(" ──").append(reset).append("\r\n");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------
    // Files

    private final File mDir;
    private final ExecutorService mWriter = Executors.newSingleThreadExecutor(r -> new Thread(r, "TerminowWorkspace"));
    @Nullable private String mLastJson;
    private final java.util.Map<String, Integer> mOutputHashes = new java.util.HashMap<>();

    public TerminowWorkspace(@NonNull File dir) {
        mDir = dir;
    }

    @NonNull
    public static TerminowWorkspace of(@NonNull Context context) {
        synchronized (TerminowWorkspace.class) {
            if (sInstance == null) sInstance = new TerminowWorkspace(new File(context.getFilesDir(), "workspace"));
            return sInstance;
        }
    }

    @Nullable private static TerminowWorkspace sInstance;

    @NonNull
    public State load() {
        try {
            return State.fromJson(new String(new AtomicFile(new File(mDir, "tabs.json")).readFully(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new State();
        }
    }

    /** Save the tabs, in the background, if they changed; the previous file stays whole until the new one is written. */
    public void save(@NonNull State state) {
        String json = state.toJson();
        // The time of saving alone is not a change
        String compare = json.replaceAll("\"saved\":[0-9]+", "");
        if (compare.equals(mLastJson)) return;
        mLastJson = compare;
        Set<String> ids = new HashSet<>();
        for (Tab tab : state.tabs) ids.add(tab.id);
        mWriter.execute(() -> {
            writeAtomically(new File(mDir, "tabs.json"), json);
            // The output of tabs that are closed is deleted with them
            File[] files = mDir.listFiles();
            if (files != null) for (File file : files) {
                String name = file.getName();
                if (name.endsWith(".txt") && !ids.contains(name.substring(0, name.length() - 4))) //noinspection ResultOfMethodCallIgnored
                    file.delete();
            }
        });
    }

    /** Save the text a tab shows, in the background, if it changed. */
    public void saveOutput(@NonNull String id, @NonNull String transcript) {
        if (!isValidId(id)) return;
        String output = trimOutput(transcript);
        int hash = output.hashCode();
        Integer last = mOutputHashes.get(id);
        if (last != null && last == hash) return;
        mOutputHashes.put(id, hash);
        mWriter.execute(() -> writeAtomically(new File(mDir, id + ".txt"), output));
    }

    /** The text a tab showed when it was last saved, empty if none. */
    @NonNull
    public String loadOutput(@NonNull String id) {
        if (!isValidId(id)) return "";
        try {
            String output = new String(new AtomicFile(new File(mDir, id + ".txt")).readFully(), StandardCharsets.UTF_8);
            mOutputHashes.put(id, output.hashCode());
            return output;
        } catch (IOException e) {
            return "";
        }
    }

    /** Forget all tabs, when the user exits TermiNow on purpose. Runs now, the process may end right after. */
    public void clear() {
        mLastJson = null;
        mOutputHashes.clear();
        File[] files = mDir.listFiles();
        if (files != null) for (File file : files) //noinspection ResultOfMethodCallIgnored
            file.delete();
    }

    /** Wait for what is being written, like before the process may end. */
    public void flush() {
        try {
            mWriter.submit(() -> { }).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // What was written is whole, by AtomicFile
        }
    }

    private void writeAtomically(@NonNull File file, @NonNull String text) {
        if (!mDir.isDirectory() && !mDir.mkdirs()) return;
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(text.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (IOException e) {
            if (out != null) atomic.failWrite(out);
            Logger.logError(LOG_TAG, "Failed to save " + file.getName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------
    // Tracking the tabs while the app runs

    /** Asks the parts of the app that know what a tab is: a new tab that TermiNow did not start is a local terminal. */
    public interface Source {
        /** The tabs in their order, with the one shown; called on the main thread. */
        @NonNull List<com.termux.terminal.TerminalSession> sessions();

        @Nullable com.termux.terminal.TerminalSession current();

        /** A tab for a session that TermiNow did not register, like one of the + button. */
        @NonNull Tab describe(@NonNull com.termux.terminal.TerminalSession session);

        /** Whether the tabs must not be saved now, like while the app shuts down. */
        boolean paused();
    }

    /** The tabs of this process, by the handle of their session. */
    private static final java.util.Map<String, Tab> sTabs = new java.util.HashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @Nullable private Source mSource;
    private boolean mStarted;
    private int mPolls;
    private static final long POLL_MS = 3000;
    /** The output is saved every few polls, and when the app goes to the background. */
    private static final int OUTPUT_EVERY_POLLS = 3;

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            capture(++mPolls % OUTPUT_EVERY_POLLS == 0);
            mHandler.postDelayed(this, POLL_MS);
        }
    };
    private final Runnable mCaptureSoon = () -> capture(false);

    /** The tab of a session, registered when TermiNow started it or found by {@link #capture}. */
    @Nullable
    public static Tab tabOf(@NonNull com.termux.terminal.TerminalSession session) {
        synchronized (sTabs) {
            return sTabs.get(session.mHandle);
        }
    }

    public static void register(@NonNull com.termux.terminal.TerminalSession session, @NonNull Tab tab) {
        synchronized (sTabs) {
            sTabs.put(session.mHandle, tab);
        }
    }

    /** Start saving the tabs as they change. Only after the saved ones were restored, or there were none. */
    public void start(@NonNull Source source) {
        mSource = source;
        if (mStarted) return;
        mStarted = true;
        mHandler.postDelayed(mPoll, POLL_MS);
        changed();
    }

    public boolean isStarted() {
        return mStarted;
    }

    /** A tab was added, closed, renamed or shown: save soon. */
    public void changed() {
        if (!mStarted) return;
        mHandler.removeCallbacks(mCaptureSoon);
        mHandler.postDelayed(mCaptureSoon, 300);
    }

    /** The app goes to the background: save everything now, with the output. */
    public void saveNow() {
        if (!mStarted) return;
        capture(true);
    }

    private void capture(boolean withOutput) {
        Source source = mSource;
        if (source == null || source.paused()) return;
        State state = new State();
        state.saved = System.currentTimeMillis();
        com.termux.terminal.TerminalSession current = source.current();
        Set<String> handles = new HashSet<>();
        for (com.termux.terminal.TerminalSession session : source.sessions()) {
            Tab tab = tabOf(session);
            if (tab == null) {
                tab = source.describe(session);
                register(session, tab);
            }
            handles.add(session.mHandle);
            // A shell that ended keeps its tab until it is closed, like in Termux; a new one starts when restored
            if (session.getPid() > 0) {
                if (session.mSessionName != null && !session.mSessionName.isEmpty() && !KIND_HOST.equals(tab.kind)) tab.title = session.mSessionName;
                if (KIND_LOCAL.equals(tab.kind) && session.isRunning()) {
                    String folder = folderOf(session.getPid());
                    if (folder != null) tab.folder = folder;
                }
                if (withOutput) saveOutput(tab.id, com.termux.shared.shell.ShellUtils.getTerminalSessionTranscriptText(session, false, true) + "");
            }
            state.tabs.add(tab);
            if (session == current) state.activeId = tab.id;
        }
        synchronized (sTabs) {
            sTabs.keySet().retainAll(handles);
        }
        save(state);
    }

    /** The folder of a process of this app, {@code null} if it can not be read. */
    @Nullable
    static String folderOf(int pid) {
        try {
            return android.system.Os.readlink("/proc/" + pid + "/cwd");
        } catch (Exception e) {
            return null;
        }
    }

}
