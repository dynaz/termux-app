package com.termux.app.terminow.linux;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The automatic Ubuntu setup: makes the runtime of the app ready for proot-distro, then finds an
 * Ubuntu that is installed already or installs the pinned Ubuntu of the {@link TerminowLinuxCatalog},
 * and checks that it works before it is Ready.
 *
 * The stages and their result are kept with a {@link StateStore}, and what every step ran and
 * printed with a {@link LogSink}, so that a new screen shows them again. One job runs at a time:
 * {@link #start()} returns false while one runs. The job runs in the process of the app; if
 * Android ends that process, {@link #recoverInterrupted()} finds a state that says running and
 * turns it into a failure that can be retried. Nothing continues after the process ended.
 *
 * Nothing that exists is deleted or installed again: an Ubuntu that is there is used and checked,
 * an incomplete environment that this setup did not create is left for the user, and the locks
 * of the package manager are waited for, never removed.
 */
public class TerminowUbuntuSetup {

    public enum Stage {
        CHECKING_RUNTIME, INSTALLING_DEPENDENCIES, CHECKING_PACKAGES, DOWNLOADING, CONFIGURING, VERIFYING, READY, FAILED, CANCELLED
    }

    /** The stages that a setup goes through, in order, for the list of the screen. */
    public static final List<Stage> STEPS = Collections.unmodifiableList(Arrays.asList(Stage.CHECKING_RUNTIME,
        Stage.INSTALLING_DEPENDENCIES, Stage.CHECKING_PACKAGES, Stage.DOWNLOADING, Stage.CONFIGURING, Stage.VERIFYING));

    /** Runs a command of the TermiNow runtime: stdout and stderr line by line, and its exit status. */
    public interface Shell {
        int run(@NonNull List<String> command, @NonNull LineSink output) throws IOException;
    }

    public interface LineSink {
        void line(@NonNull String line);
    }

    /** Installs the pinned catalog entry as a new container, like Browse Linux does. */
    public interface Installer {
        @NonNull TerminowLinuxEnv install(@NonNull TerminowLinuxCatalog.Entry entry, @NonNull String alias,
                                          @NonNull TerminowLinuxInstaller.Listener listener) throws IOException;

        @NonNull String freeAlias(@NonNull String distroId);

        void cancel();
    }

    public interface StateStore {
        @Nullable String load();

        void save(@NonNull String state);
    }

    public interface LogSink {
        void append(@NonNull String line);

        void clear();
    }

    /** Told about every change, on the thread of the job. */
    public interface Listener {
        void onChanged(@NonNull State state);

        void onLog(@NonNull String line);
    }

    /** What the screen shows: the stage, the result, and what to tell the user. */
    public static class State {
        public enum Status { IDLE, RUNNING, READY, FAILED, CANCELLED }

        public Status status = Status.IDLE;
        public Stage stage = Stage.CHECKING_RUNTIME;
        /** For a failure, the stage that failed. */
        @Nullable public Stage failedStage;
        /** A short summary of the failure. */
        public String error = "";
        /** Whether the setup was running when the process of the app ended. */
        public boolean interrupted;
        /** The container of Ubuntu, once found or installed. */
        public String alias = "";
        /** PRETTY_NAME of its /etc/os-release, read after the install. */
        public String version = "";
        /** Things that are not right but do not stop Ubuntu, like the network or .pyc files. */
        public List<String> warnings = new ArrayList<>();
        /** Bytes of the download. */
        public long done = -1;
        public long total = -1;
        /** Packages whose Python files were compiled again, so it is not done twice. */
        public Set<String> compiled = new LinkedHashSet<>();

        @NonNull
        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("status", status.name());
                object.put("stage", stage.name());
                if (failedStage != null) object.put("failedStage", failedStage.name());
                object.put("error", error);
                object.put("interrupted", interrupted);
                object.put("alias", alias);
                object.put("version", version);
                object.put("warnings", new JSONArray(warnings));
                object.put("compiled", new JSONArray(compiled));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
            return object;
        }

        @NonNull
        static State fromJson(@Nullable String text) {
            State state = new State();
            if (text == null || text.isEmpty()) return state;
            try {
                JSONObject object = new JSONObject(text);
                state.status = Status.valueOf(object.optString("status", "IDLE"));
                state.stage = Stage.valueOf(object.optString("stage", Stage.CHECKING_RUNTIME.name()));
                String failed = object.optString("failedStage");
                state.failedStage = failed.isEmpty() ? null : Stage.valueOf(failed);
                state.error = object.optString("error");
                state.interrupted = object.optBoolean("interrupted");
                state.alias = object.optString("alias");
                state.version = object.optString("version");
                JSONArray warnings = object.optJSONArray("warnings");
                for (int i = 0; warnings != null && i < warnings.length(); i++) state.warnings.add(warnings.optString(i));
                JSONArray compiled = object.optJSONArray("compiled");
                for (int i = 0; compiled != null && i < compiled.length(); i++) state.compiled.add(compiled.optString(i));
            } catch (JSONException | IllegalArgumentException e) {
                // A state of another version: start again
                return new State();
            }
            return state;
        }

        @NonNull
        State copy() {
            State copy = fromJson(toJson().toString());
            copy.done = done;
            copy.total = total;
            return copy;
        }
    }

    /** A step that failed, with the summary for the user. */
    static class StepFailure extends Exception {
        StepFailure(@NonNull String summary) {
            super(summary);
        }
    }

    /** Thrown when the user cancelled, between steps. */
    static class Cancelled extends Exception {
    }

    static final String MARK = "TERMINOW_";
    /** The bash and Python check: Python starts the bash of the runtime, like py3compile does. */
    static final String PYTHON_SPAWN_CHECK = "import os, subprocess\n" +
        "try:\n" +
        "    r = subprocess.run([os.environ['PREFIX'] + '/bin/bash', '-c', 'echo " + MARK + "SPAWN_OK'], capture_output=True, text=True, timeout=60)\n" +
        "    print(r.stdout.strip() if r.returncode == 0 else 'exit %d: %s' % (r.returncode, r.stderr.strip()[-300:]))\n" +
        "except Exception as e:\n" +
        "    print('%s: %s' % (type(e).__name__, e))\n";
    /** What the setup tells about the runtime, only these variables, never the whole environment. */
    static final List<String> ENVIRONMENT_KEYS = Collections.unmodifiableList(Arrays.asList("PREFIX", "PATH", "HOME", "TMPDIR",
        "LD_PRELOAD", "TERMUX__PREFIX", "TERMUX_APP__DATA_DIR", "TERMUX__SE_PROCESS_CONTEXT",
        "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE", "ANDROID__BUILD_VERSION_SDK"));
    /** A line of a maintainer script of an older build, that compiles Python files without termux-exec. */
    static final Pattern UNLOADED_PY3COMPILE = Pattern.compile("LD_PRELOAD=(?:''|\"\")\\s+\"?(\\S*?/bin/py3compile)\"?\\s+-p\\s+\"?([a-z0-9][a-z0-9+.-]*)\"?\\s+\"?([^\"\\s]+)\"?");
    static final Pattern LOCK_HELD = Pattern.compile("Could not get lock|Unable to acquire the dpkg frontend lock|dpkg status database is locked|" +
        "locked by another process", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCK_PID = Pattern.compile("(?:held by process|with pid) (\\d+)");
    private static final int LOCK_TIMEOUT_SECONDS = 120;

    private final File mPrefix;
    private final TerminowLinuxRegistry mRegistry;
    private final Shell mShell;
    private final Installer mInstaller;
    private final StateStore mStore;
    private final LogSink mLog;
    private final List<Listener> mListeners = new ArrayList<>();
    private State mState;
    private volatile boolean mRunning;
    private volatile boolean mCancelled;
    @Nullable private Stage mLastStage;

    public TerminowUbuntuSetup(@NonNull File prefix, @NonNull TerminowLinuxRegistry registry, @NonNull Shell shell,
                               @NonNull Installer installer, @NonNull StateStore store, @NonNull LogSink log) {
        mPrefix = prefix;
        mRegistry = registry;
        mShell = shell;
        mInstaller = installer;
        mStore = store;
        mLog = log;
        mState = State.fromJson(store.load());
    }

    public synchronized void addListener(@NonNull Listener listener) {
        if (!mListeners.contains(listener)) mListeners.add(listener);
    }

    public synchronized void removeListener(@NonNull Listener listener) {
        mListeners.remove(listener);
    }

    @NonNull
    public synchronized State getState() {
        return mState.copy();
    }

    public boolean isRunning() {
        return mRunning;
    }

    /**
     * After the process of the app was started again: a setup that was running then did not end,
     * so it failed at its stage, and can be retried. Returns whether there was one.
     */
    public synchronized boolean recoverInterrupted() {
        if (mRunning || mState.status != State.Status.RUNNING) return false;
        mState.status = State.Status.FAILED;
        mState.failedStage = mState.stage;
        mState.interrupted = true;
        mState.error = "The setup stopped when Android closed the app. Nothing that was there is lost, Retry continues it.";
        save();
        return true;
    }

    /** Mark the setup as running, false if one runs already. Then call {@link #run()} on a worker thread. */
    public synchronized boolean start() {
        if (mRunning) return false;
        mRunning = true;
        mCancelled = false;
        Set<String> compiled = mState.compiled;
        mState = new State();
        mState.compiled = compiled;
        mState.status = State.Status.RUNNING;
        mLog.clear();
        save();
        return true;
    }

    /** Stops after the command that runs now, or stops the download or unpacking at once. */
    public void cancel() {
        mCancelled = true;
        mInstaller.cancel();
    }

    /** The whole setup, blocking. Only after a successful {@link #start()}. */
    public void run() {
        try {
            checkRuntime();
            installDependencies();
            checkPackages();
            TerminowLinuxEnv env = findOrInstallUbuntu();
            verify(env);
            synchronized (this) {
                mState.status = State.Status.READY;
                mState.stage = Stage.READY;
                mState.error = "";
            }
            log("Ubuntu is ready" + (mState.warnings.isEmpty() ? "" : ", with " + mState.warnings.size() + " warning(s)"));
            save();
        } catch (Cancelled e) {
            synchronized (this) {
                mState.status = State.Status.CANCELLED;
                mState.failedStage = mState.stage;
                mState.stage = Stage.CANCELLED;
                mState.error = "Cancelled";
            }
            log("Cancelled");
            save();
        } catch (StepFailure e) {
            fail(e.getMessage());
        } catch (IOException | RuntimeException e) {
            fail(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } finally {
            mRunning = false;
            notifyChanged();
        }
    }

    private void fail(@NonNull String summary) {
        synchronized (this) {
            mState.status = State.Status.FAILED;
            mState.failedStage = mState.stage;
            mState.stage = Stage.FAILED;
            mState.error = summary;
        }
        log("Failed: " + summary);
        save();
    }

    // ---------------------------------------------------------------------------------------
    // 1. The runtime
    // ---------------------------------------------------------------------------------------

    void checkRuntime() throws StepFailure, Cancelled, IOException {
        stage(Stage.CHECKING_RUNTIME);
        // The environment of the app itself, which the first terminal tab installs
        if (!new File(mPrefix, "bin/bash").isFile())
            throw new StepFailure("The TermiNow environment is not installed yet. Open a terminal tab, wait until it shows a prompt, then Retry.");
        // What the runtime looks like, for the log: ids, the permissions of bash and its folders,
        // the variables that decide how programs are started, and termux-exec
        StringBuilder script = new StringBuilder("echo \"uid=$(id -u) gid=$(id -g) groups=$(id -G)\"\n" +
            "ls -ld \"$PREFIX\" \"$PREFIX/bin\" \"$PREFIX/bin/bash\" \"$PREFIX/lib/libtermux-exec-ld-preload.so\" 2>&1\n");
        for (String key : ENVIRONMENT_KEYS)
            script.append("if [ -n \"${").append(key).append("+x}\" ]; then printf '%s=%s\\n' ").append(key).append(" \"$").append(key)
                .append("\"; else echo ").append(key).append("=unset; fi\n");
        script.append("echo \"system linker exec: $(termux-exec-system-linker-exec is-enabled 2>&1)\"\n");
        run("runtime", Arrays.asList(bash(), "-c", script.toString()));

        // The runtime starts bash, and bash starts bash: both go through termux-exec
        String out = capture(Arrays.asList(bash(), "-c", "\"$PREFIX/bin/bash\" -c 'echo " + MARK + "BASH_OK'"));
        if (!out.contains(MARK + "BASH_OK"))
            throw new StepFailure("The runtime can not start bash: " + lastLine(out));
        log("bash starts bash: ok");
    }

    // ---------------------------------------------------------------------------------------
    // 2. proot-distro and what it needs
    // ---------------------------------------------------------------------------------------

    void installDependencies() throws StepFailure, Cancelled, IOException {
        stage(Stage.INSTALLING_DEPENDENCIES);
        // Bootstraps up to 2026.10.11-r7 have dpkg-perl, which depends on clang that the repository
        // does not have, so apt refuses to install anything until it and dpkg-scanpackages are gone
        if (installed("dpkg-perl") && !installed("clang")) {
            log("dpkg-perl needs clang, which is not available: removing dpkg-scanpackages and dpkg-perl, tools to build packages");
            packageManager(Arrays.asList(prefixed("bin/dpkg"), "--remove", "dpkg-scanpackages", "dpkg-perl"), "Removing dpkg-perl");
        }
        // Packages that were left half set up, like by an install that was stopped
        String audit = capture(Arrays.asList(prefixed("bin/dpkg"), "--audit")).trim();
        if (!audit.isEmpty()) {
            log("dpkg --audit reports unfinished packages, finishing them");
            packageManager(Arrays.asList(prefixed("bin/dpkg"), "--configure", "-a"), "Finishing the setup of packages");
        }
        if (installed("proot-distro") && installed("proot") && installed("python")) {
            log("proot-distro, proot and python are installed");
            return;
        }
        packageManager(apt("update"), "Updating the package lists");
        packageManager(apt("install", "-y", "proot-distro"), "Installing proot-distro");
        for (String name : new String[]{"proot-distro", "proot", "python"})
            if (!installed(name)) throw new StepFailure(name + " is not installed after apt said it was");
    }

    @NonNull
    private List<String> apt(@NonNull String... args) {
        List<String> command = new ArrayList<>(Arrays.asList(prefixed("bin/apt-get"),
            // Wait for another package install instead of failing at once
            "-o", "DPkg::Lock::Timeout=" + LOCK_TIMEOUT_SECONDS,
            // Keep the configuration files that are there
            "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold"));
        command.addAll(Arrays.asList(args));
        return command;
    }

    /** Run a command of apt or dpkg: its exit status is the one of that process, not of a pipe. */
    private void packageManager(@NonNull List<String> command, @NonNull String what) throws StepFailure, Cancelled, IOException {
        StringBuilder output = new StringBuilder();
        int exit = run(what, command, output);
        if (exit == 0) return;
        if (LOCK_HELD.matcher(output).find()) {
            Matcher holder = LOCK_PID.matcher(output);
            String pid = holder.find() ? holder.group(1) : null;
            throw new StepFailure("Another package install is running" + (pid != null ? " (process " + pid + ")" : "") +
                ". It was not stopped and its lock was kept. Let it finish, then Retry.");
        }
        throw new StepFailure(what + " failed (exit " + exit + "): " + lastLine(output.toString()));
    }

    private boolean installed(@NonNull String name) throws IOException, Cancelled {
        String out = capture(Arrays.asList(prefixed("bin/dpkg-query"), "-W", "-f=${Status}", name));
        return out.trim().endsWith("install ok installed");
    }

    // ---------------------------------------------------------------------------------------
    // 3. Health of the packages
    // ---------------------------------------------------------------------------------------

    void checkPackages() throws StepFailure, Cancelled, IOException {
        stage(Stage.CHECKING_PACKAGES);
        String audit = capture(Arrays.asList(prefixed("bin/dpkg"), "--audit")).trim();
        if (!audit.isEmpty()) throw new StepFailure("Some packages are not set up: " + lastLine(audit));

        // proot-distro is a Python program that starts proot: Python must be able to start the
        // programs of the runtime, through termux-exec in its LD_PRELOAD
        String spawn = capture(Arrays.asList(prefixed("bin/python3"), "-c", PYTHON_SPAWN_CHECK));
        if (!spawn.contains(MARK + "SPAWN_OK"))
            throw new StepFailure("Python can not start bash: " + lastLine(spawn));
        log("Python starts bash: ok");
        // How package scripts of an older build start Python: without termux-exec. Only for the log.
        String unloaded = capture(Arrays.asList(bash(), "-c", "LD_PRELOAD= exec \"$PREFIX/bin/python3\" -c \"$1\"", "check", PYTHON_SPAWN_CHECK));
        log("Python started with an empty LD_PRELOAD, as package scripts of older builds do: " +
            (unloaded.contains(MARK + "SPAWN_OK") ? "starts bash" : "can not start bash (" + lastLine(unloaded) + ")"));

        repairPythonFiles();

        String help = capture(Arrays.asList(prefixed("bin/proot-distro"), "--help"));
        if (!help.toLowerCase(java.util.Locale.ROOT).contains("install") || !help.toLowerCase(java.util.Locale.ROOT).contains("login"))
            throw new StepFailure("proot-distro does not start: " + lastLine(help));
        log("proot-distro starts: ok");
    }

    /**
     * Package scripts of older builds compiled the Python files of their package with termux-exec
     * unloaded, which failed, and their scripts ignored that. Compile them again, the same command
     * with the runtime's environment, and check that it works.
     */
    void repairPythonFiles() throws Cancelled, IOException {
        File[] scripts = new File(mPrefix, "var/lib/dpkg/info").listFiles((dir, name) -> name.endsWith(".postinst"));
        if (scripts == null) return;
        Arrays.sort(scripts);
        List<String> failed = new ArrayList<>();
        for (File script : scripts) {
            String text;
            try {
                text = new String(Files.readAllBytes(script.toPath()), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            Matcher matcher = UNLOADED_PY3COMPILE.matcher(text);
            if (!matcher.find()) continue;
            String pkg = matcher.group(2);
            File directory = new File(matcher.group(3));
            if (mState.compiled.contains(pkg)) continue;
            // Only the compiler and the Python folder of this runtime
            if (!new File(matcher.group(1)).equals(new File(mPrefix, "bin/py3compile")) || !isUnder(directory, mPrefix)) continue;
            if (!installed(pkg)) continue;
            int exit = run("Compiling the Python files of " + pkg, Arrays.asList(prefixed("bin/py3compile"), "-p", pkg, directory.getPath()), null);
            if (exit == 0) {
                synchronized (this) {
                    mState.compiled.add(pkg);
                }
                save();
            } else {
                failed.add(pkg + " (exit " + exit + ")");
            }
        }
        if (!failed.isEmpty()) {
            warn("Host package configuration is incomplete: the Python files of " + String.join(", ", failed) +
                " could not be compiled. Python still works, it compiles them when they are used.");
        }
    }

    private static boolean isUnder(@NonNull File file, @NonNull File parent) {
        String path = file.getAbsolutePath();
        String base = parent.getAbsolutePath();
        return path.startsWith(base + "/") && !path.contains("/../") && !path.endsWith("/..");
    }

    // ---------------------------------------------------------------------------------------
    // 4. Ubuntu
    // ---------------------------------------------------------------------------------------

    @NonNull
    TerminowLinuxEnv findOrInstallUbuntu() throws StepFailure, Cancelled, IOException {
        stage(Stage.DOWNLOADING);
        TerminowLinuxEnv incomplete = null;
        for (TerminowLinuxEnv env : mRegistry.list()) {
            if (!env.distroId.equals("ubuntu") && !env.idLike.contains("ubuntu") && !(env.distroId.isEmpty() && env.alias.equals("ubuntu")))
                continue;
            if (env.status == TerminowLinuxEnv.Status.INSTALLED) {
                log("Ubuntu is installed already as '" + env.alias + "' (" + env.getTitle() + "): it is kept and checked, not installed again");
                synchronized (this) {
                    mState.alias = env.alias;
                }
                return env;
            }
            if (incomplete == null && env.status == TerminowLinuxEnv.Status.INCOMPLETE) incomplete = env;
        }
        if (incomplete != null && incomplete.alias.equals("ubuntu"))
            log("There is an incomplete environment 'ubuntu' that this setup did not finish; it is left as it is, " +
                "remove it in Browse Linux if you do not need it");

        TerminowLinuxCatalog.Entry entry = TerminowLinuxCatalog.find("ubuntu");
        if (entry == null) throw new StepFailure("No pinned Ubuntu in the catalog");
        String alias = mInstaller.freeAlias("ubuntu");
        log("Installing " + entry.name + " " + entry.version + " as '" + alias + "' from " + entry.url + " (SHA-256 " + entry.sha256 + ")");
        synchronized (this) {
            mState.alias = alias;
        }
        save();
        TerminowLinuxEnv env;
        try {
            env = mInstaller.install(entry, alias, new TerminowLinuxInstaller.Listener() {
                @Override
                public void onPhase(@NonNull TerminowLinuxInstaller.Phase phase, @NonNull String detail) {
                    Stage stage = phase == TerminowLinuxInstaller.Phase.CHECKING || phase == TerminowLinuxInstaller.Phase.DOWNLOADING ||
                        phase == TerminowLinuxInstaller.Phase.VERIFYING ? Stage.DOWNLOADING :
                        phase == TerminowLinuxInstaller.Phase.VALIDATING || phase == TerminowLinuxInstaller.Phase.DONE ? Stage.VERIFYING : Stage.CONFIGURING;
                    synchronized (TerminowUbuntuSetup.this) {
                        mState.stage = stage;
                    }
                    if (!detail.isEmpty() && !detail.startsWith("http")) log(detail);
                    else if (stage != mLastStage) log(phase.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
                    mLastStage = stage;
                    saveQuietly();
                }

                @Override
                public void onProgress(long done, long total) {
                    synchronized (TerminowUbuntuSetup.this) {
                        mState.done = done;
                        mState.total = total;
                    }
                    notifyChanged();
                }
            });
        } catch (IOException e) {
            checkCancelled();
            throw new StepFailure(e.getMessage() != null ? e.getMessage() : "The install failed");
        }
        checkCancelled();
        return env;
    }

    // ---------------------------------------------------------------------------------------
    // 5. Checks before Ready
    // ---------------------------------------------------------------------------------------

    void verify(@NonNull TerminowLinuxEnv env) throws StepFailure, Cancelled, IOException {
        stage(Stage.VERIFYING);
        String user = env.defaultUser.isEmpty() ? "root" : env.defaultUser;
        String out = capture(Arrays.asList(prefixed("bin/proot-distro"), "login", env.alias, "--user", user, "--",
            "/bin/sh", "-c", "cat /etc/os-release && echo " + MARK + "OS_OK; cd \"$HOME\" && ls -a >/dev/null && echo " + MARK +
                "HOME_OK \"$HOME\"; echo " + MARK + "CMD_OK $(uname -m)"));
        if (!out.contains(MARK + "CMD_OK")) throw new StepFailure("Ubuntu does not start: " + lastLine(out));
        if (!out.contains(MARK + "OS_OK")) throw new StepFailure("/etc/os-release of Ubuntu can not be read");
        Map<String, String> os = TerminowLinuxRegistry.parseOsRelease(out.substring(0, out.indexOf(MARK + "OS_OK")));
        if (!"ubuntu".equals(os.get("ID")) && !String.valueOf(os.get("ID_LIKE")).contains("ubuntu"))
            throw new StepFailure("The environment is not Ubuntu: " + os.getOrDefault("ID", "no ID"));
        if (!out.contains(MARK + "HOME_OK")) throw new StepFailure("The home folder of " + user + " can not be opened");
        synchronized (this) {
            mState.version = os.getOrDefault("PRETTY_NAME", os.getOrDefault("VERSION", ""));
            mState.alias = env.alias;
        }
        log("Ubuntu starts as " + user + ", reads /etc/os-release (" + mState.version + "), opens its home and runs commands: ok");

        // The network separately: Ubuntu works without it, apt in it does not
        String dns = capture(Arrays.asList(prefixed("bin/proot-distro"), "login", env.alias, "--",
            "/bin/sh", "-c", "getent hosts archive.ubuntu.com >/dev/null && echo " + MARK + "DNS_OK || echo " + MARK + "DNS_FAIL"));
        if (dns.contains(MARK + "DNS_OK")) {
            log("The network works in Ubuntu: archive.ubuntu.com resolves");
        } else {
            warn("The network or DNS does not work in Ubuntu: archive.ubuntu.com does not resolve, so apt in Ubuntu can not download " +
                "until it does. Check the connection of the phone.");
        }
    }

    // ---------------------------------------------------------------------------------------

    private void stage(@NonNull Stage stage) throws Cancelled {
        checkCancelled();
        synchronized (this) {
            mState.stage = stage;
        }
        log("== " + stage.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        save();
    }

    private void checkCancelled() throws Cancelled {
        if (mCancelled) throw new Cancelled();
    }

    private void warn(@NonNull String warning) {
        synchronized (this) {
            mState.warnings.add(warning);
        }
        log("Warning: " + warning);
        saveQuietly();
    }

    /** Run a command for the log, return its exit status. */
    private int run(@NonNull String what, @NonNull List<String> command, @Nullable StringBuilder output) throws IOException, Cancelled {
        checkCancelled();
        log("$ " + what);
        int exit = mShell.run(command, line -> {
            if (output != null) output.append(line).append('\n');
            log("  " + line);
        });
        log("  exit " + exit);
        return exit;
    }

    private void run(@NonNull String what, @NonNull List<String> command) throws IOException, Cancelled {
        run(what, command, null);
    }

    /** Run a command whose output is a result, which is logged too. */
    @NonNull
    private String capture(@NonNull List<String> command) throws IOException, Cancelled {
        checkCancelled();
        StringBuilder output = new StringBuilder();
        int exit = mShell.run(command, line -> output.append(line).append('\n'));
        if (exit != 0) output.append("exit ").append(exit).append('\n');
        return output.toString();
    }

    private void log(@NonNull String line) {
        mLog.append(line);
        List<Listener> listeners;
        synchronized (this) {
            listeners = new ArrayList<>(mListeners);
        }
        for (Listener listener : listeners) listener.onLog(line);
    }

    private void save() {
        String text;
        synchronized (this) {
            text = mState.toJson().toString();
        }
        mStore.save(text);
        notifyChanged();
    }

    private void saveQuietly() {
        save();
    }

    private void notifyChanged() {
        State state;
        List<Listener> listeners;
        synchronized (this) {
            state = mState.copy();
            listeners = new ArrayList<>(mListeners);
        }
        for (Listener listener : listeners) listener.onChanged(state);
    }

    @NonNull
    private String bash() {
        return prefixed("bin/bash");
    }

    @NonNull
    private String prefixed(@NonNull String path) {
        return new File(mPrefix, path).getPath();
    }

    @NonNull
    static String lastLine(@NonNull String text) {
        String[] lines = text.trim().split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.isEmpty() && !line.startsWith("exit ")) return line.length() > 300 ? line.substring(0, 300) + "…" : line;
        }
        return lines.length > 0 ? lines[lines.length - 1].trim() : "";
    }

}
