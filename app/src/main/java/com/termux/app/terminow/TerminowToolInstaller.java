package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Installs tools in a Linux environment as one of its users: Claude Code, Git, curl and tmux.
 *
 * <p>Each install is a process of its own, {@code proot-distro login <alias> --user <user> --
 * /bin/sh -c SCRIPT}, whose output is read line by line and whose exit code decides the result:
 * nothing is typed into a terminal, and nothing runs in TermiNow itself. The script says which
 * stage it is in and why it failed with {@code TERMINOW_*} lines, see {@link #INSTALL_SCRIPT}.</p>
 *
 * <p>Jobs are kept here, outside of any screen, so they go on and are shown again when the tool
 * sheet is closed, the screen rotates or the activity is made again. One environment runs one
 * job at a time, since they all may use apt, and a tool being installed is never started twice.</p>
 */
public final class TerminowToolInstaller {

    public enum Tool {
        CLAUDE("claude"), GIT("git"), CURL("curl"), TMUX("tmux");

        public final String id;

        Tool(@NonNull String id) {
            this.id = id;
        }
    }

    /** The stages of an install, in their order. */
    public enum Stage { CHECKING, DEPENDENCIES, INSTALLING, VERIFYING }

    public enum State { RUNNING, SUCCEEDED, FAILED }

    /** Why an install failed, from its TERMINOW_FAILED line, or how the process ended. */
    public enum Failure {
        NO_APT, NO_SUDO, SUDO_PASSWORD, UNSUPPORTED_ARCH, APT_UPDATE, APT_INSTALL, NETWORK, INSTALLER, PATH, VERIFY,
        CANCELLED, START, EXIT;

        @NonNull
        static Failure of(@NonNull String code) {
            switch (code) {
                case "no-apt": return NO_APT;
                case "no-sudo": return NO_SUDO;
                case "sudo-password": return SUDO_PASSWORD;
                case "unsupported-arch": return UNSUPPORTED_ARCH;
                case "apt-update": return APT_UPDATE;
                case "apt-install": return APT_INSTALL;
                case "network": return NETWORK;
                case "installer": return INSTALLER;
                case "path": return PATH;
                case "verify": return VERIFY;
                default: return EXIT;
            }
        }
    }

    /** An install of a tool in an environment as a user, while it runs and after it ended. */
    public static final class Job {
        public final String alias;
        public final String user;
        public final Tool tool;
        private volatile Stage mStage = Stage.CHECKING;
        private volatile State mState = State.RUNNING;
        @Nullable private volatile Failure mFailure;
        private volatile int mExitCode = -1;
        private volatile String mVersion = "";
        private volatile boolean mAlreadyInstalled;
        private final StringBuilder mLog = new StringBuilder();
        @Nullable private volatile Process mProcess;
        private volatile boolean mCancelled;

        Job(@NonNull String alias, @NonNull String user, @NonNull Tool tool) {
            this.alias = alias;
            this.user = user;
            this.tool = tool;
        }

        @NonNull public Stage getStage() { return mStage; }
        @NonNull public State getState() { return mState; }
        @Nullable public Failure getFailure() { return mFailure; }
        public int getExitCode() { return mExitCode; }
        /** What `--version` of the tool printed, once it was verified. */
        @NonNull public String getVersion() { return mVersion; }
        /** It was there already: nothing was installed. */
        public boolean wasAlreadyInstalled() { return mAlreadyInstalled; }

        /** The output of the install, its last {@link #LOG_MAX} characters. */
        @NonNull
        public String getLog() {
            synchronized (mLog) {
                return mLog.toString();
            }
        }

        /** The last lines of the output, for a failure. */
        @NonNull
        public String getLogTail(int lines) {
            String log = getLog().trim();
            int at = log.length();
            for (int i = 0; i < lines && at > 0; i++) at = log.lastIndexOf('\n', at - 1);
            return at <= 0 ? log : log.substring(at + 1);
        }

        void append(@NonNull String line) {
            synchronized (mLog) {
                mLog.append(line).append('\n');
                if (mLog.length() > LOG_MAX) mLog.delete(0, mLog.length() - LOG_MAX);
            }
        }

        /** Read a line of the script: a stage, a failure, the version, or output for the log. */
        void onLine(@NonNull String line) {
            if (line.startsWith("TERMINOW_STAGE=")) {
                Stage stage = stageOf(line.substring("TERMINOW_STAGE=".length()));
                if (stage != null) mStage = stage;
            } else if (line.startsWith("TERMINOW_FAILED=")) {
                mFailure = Failure.of(line.substring("TERMINOW_FAILED=".length()).trim());
            } else if (line.startsWith("TERMINOW_VERSION=")) {
                mVersion = line.substring("TERMINOW_VERSION=".length()).trim();
            } else if (line.equals("TERMINOW_ALREADY=1")) {
                mAlreadyInstalled = true;
            } else {
                append(line);
            }
        }

        /** The end of the process: only an exit code of 0 with a verified version is a success. */
        void onExit(int exitCode) {
            mExitCode = exitCode;
            if (mCancelled) {
                mFailure = Failure.CANCELLED;
                mState = State.FAILED;
            } else if (exitCode == 0 && !mVersion.isEmpty()) {
                mState = State.SUCCEEDED;
            } else {
                if (mFailure == null) mFailure = exitCode == 0 ? Failure.VERIFY : Failure.EXIT;
                mState = State.FAILED;
            }
        }

        void onStartFailed(@NonNull String message) {
            append(message);
            mFailure = Failure.START;
            mState = State.FAILED;
        }

        /** Stop the install; it ends as failed and can be retried. */
        public void cancel() {
            mCancelled = true;
            Process process = mProcess;
            if (process != null) process.destroy();
        }
    }

    /** What is installed in an environment as a user, from {@link #DETECT_SCRIPT}. */
    public static final class Detection {
        public boolean ran;
        public String uid = "";
        public String arch = "";
        public boolean hasApt;
        public boolean hasSudo;
        /** The first line of `--version` of each tool that is there. */
        public final Map<Tool, String> versions = new EnumMap<>(Tool.class);
        @Nullable public String error;

        public boolean isRoot() {
            return "0".equals(uid);
        }
    }

    /** Starts the script of a job in its environment as its user. */
    public interface Runner {
        @NonNull
        Process start(@NonNull String alias, @NonNull String user, @NonNull String script, @NonNull String... args) throws IOException;
    }

    static final int LOG_MAX = 64 * 1024;
    public static final long DETECT_TIMEOUT_MS = 90_000;

    /** The ports of curl that mean the network: it could not resolve, connect, finish TLS, or got nothing. */
    static final String NETWORK_EXIT_CODES = "6|7|28|35|52|56";

    /**
     * What the user has: its uid, the CPU, apt and sudo, and the version of each tool. Claude
     * Code may be in `~/.local/bin`, where its installer puts it, which is not always on the PATH.
     */
    static final String DETECT_SCRIPT =
        "PATH=\"$HOME/.local/bin:$PATH\"; export PATH\n" +
        "echo \"TERMINOW_UID=$(id -u)\"\n" +
        "echo \"TERMINOW_ARCH=$(uname -m)\"\n" +
        "command -v apt-get >/dev/null 2>&1 && echo TERMINOW_APT=yes\n" +
        "command -v sudo >/dev/null 2>&1 && echo TERMINOW_SUDO=yes\n" +
        "for t in claude git curl tmux; do\n" +
        "  case $t in claude) v=$(claude --version 2>/dev/null);; tmux) v=$(tmux -V 2>/dev/null);; *) v=$($t --version 2>/dev/null);; esac\n" +
        "  [ -n \"$v\" ] && echo \"TERMINOW_HAS_$t=$(printf '%s\\n' \"$v\" | head -n 1)\"\n" +
        "done\n" +
        "exit 0\n";

    /**
     * Installs the tool {@code $1} as the user that runs it. apt is used as root directly, never
     * with sudo; as another user only with sudo that needs no password, which this process can
     * not type. sudo is never installed. apt waits for the lock of another apt instead of failing.
     * Claude Code comes from its official installer, as the user, with curl and Git from apt.
     */
    static final String INSTALL_SCRIPT =
        "stage() { echo \"TERMINOW_STAGE=$1\"; }\n" +
        "fail() { echo \"TERMINOW_FAILED=$1\"; exit \"${2:-1}\"; }\n" +
        "first() { printf '%s\\n' \"$1\" | head -n 1; }\n" +
        "tool=\"$1\"\n" +
        "stage checking\n" +
        "PATH=\"$HOME/.local/bin:$PATH\"; export PATH\n" +
        "uid=$(id -u)\n" +
        "echo \"TermiNow: installing $tool as $(id -un 2>/dev/null || echo \"$uid\") (uid $uid) on $(uname -m)\"\n" +
        "missing() { m=; for p in \"$@\"; do dpkg-query -W -f='${Status}' \"$p\" 2>/dev/null | grep -q 'install ok installed' || m=\"$m $p\"; done; echo \"$m\"; }\n" +
        "apt_install() {\n" +
        "  command -v apt-get >/dev/null 2>&1 && command -v dpkg-query >/dev/null 2>&1 || fail no-apt 14\n" +
        "  m=$(missing \"$@\")\n" +
        "  [ -z \"$m\" ] && return 0\n" +
        "  if [ \"$uid\" = 0 ]; then as=\n" +
        "  elif command -v sudo >/dev/null 2>&1; then sudo -n true >/dev/null 2>&1 || fail sudo-password 13; as='sudo -n'\n" +
        "  else fail no-sudo 13; fi\n" +
        "  echo \"TermiNow: apt-get install$m\"\n" +
        "  $as env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=300 update || fail apt-update 20\n" +
        "  $as env DEBIAN_FRONTEND=noninteractive apt-get -o DPkg::Lock::Timeout=300 install -y --no-install-recommends $m || fail apt-install 21\n" +
        "}\n" +
        // ~/.local/bin on the PATH of bash for good, once, and never twice on the PATH
        "persist_path() {\n" +
        "  rc=\"$HOME/.bashrc\"\n" +
        "  grep -qs 'TermiNow: Claude Code PATH' \"$rc\" && return 0\n" +
        "  grep -Eqs '^[^#]*PATH=.*(\\$HOME|~|'\"$HOME\"')/\\.local/bin' \"$rc\" && return 0\n" +
        "  { echo; echo '# TermiNow: Claude Code PATH'; echo 'case \":$PATH:\" in *\":$HOME/.local/bin:\"*) ;; *) export PATH=\"$HOME/.local/bin:$PATH\" ;; esac'; } >> \"$rc\" || fail path 31\n" +
        "}\n" +
        "case \"$tool\" in\n" +
        "  claude)\n" +
        "    if v=$(claude --version 2>/dev/null) && [ -n \"$v\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    case \"$(uname -m)\" in aarch64|arm64|x86_64|amd64) ;; *) fail unsupported-arch 15;; esac\n" +
        "    stage dependencies\n" +
        "    apt_install curl ca-certificates git\n" +
        "    command -v bash >/dev/null 2>&1 || apt_install bash\n" +
        "    stage installing\n" +
        "    bash -o pipefail -c 'curl -fsSL https://claude.ai/install.sh | bash'\n" +
        "    s=$?\n" +
        "    if [ \"$s\" -ne 0 ]; then case \"$s\" in " + NETWORK_EXIT_CODES + ") fail network \"$s\";; *) fail installer \"$s\";; esac; fi\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    v=$(claude --version 2>&1) || { echo \"$v\"; fail verify 30; }\n" +
        "    [ -n \"$v\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        "  git|curl|tmux)\n" +
        "    case \"$tool\" in git) pkgs=git; check='git --version';; curl) pkgs='curl ca-certificates'; check='curl --version';; tmux) pkgs=tmux; check='tmux -V';; esac\n" +
        "    if v=$($check 2>/dev/null) && [ -n \"$v\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; stage verifying; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    stage installing\n" +
        "    apt_install $pkgs\n" +
        "    stage verifying\n" +
        "    v=$($check 2>&1) || { echo \"$v\"; fail verify 30; }\n" +
        "    [ -n \"$v\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        "  *) echo \"TermiNow: unknown tool $tool\"; exit 2;;\n" +
        "esac\n";

    private static final Map<String, Job> sJobs = new HashMap<>();
    private static final List<Runnable> sListeners = new CopyOnWriteArrayList<>();

    private TerminowToolInstaller() {}

    @NonNull
    private static String keyOf(@NonNull String alias, @NonNull String user, @NonNull Tool tool) {
        return alias + "|" + user + "|" + tool.id;
    }

    /** The last install of the tool in the environment as the user, running or ended, {@code null} if none. */
    @Nullable
    public static synchronized Job jobOf(@NonNull String alias, @NonNull String user, @NonNull Tool tool) {
        return sJobs.get(keyOf(alias, user, tool));
    }

    /** The install that runs in the environment, as any user, {@code null} if none: they share apt. */
    @Nullable
    public static synchronized Job runningIn(@NonNull String alias) {
        for (Job job : sJobs.values()) if (job.alias.equals(alias) && job.mState == State.RUNNING) return job;
        return null;
    }

    /** Forget an ended job, like after its state was checked again. */
    public static synchronized void forget(@NonNull String alias, @NonNull String user, @NonNull Tool tool) {
        Job job = sJobs.get(keyOf(alias, user, tool));
        if (job != null && job.mState != State.RUNNING) sJobs.remove(keyOf(alias, user, tool));
    }

    /** Called on any thread when a job changed; post to the main thread to show it. */
    public static void addListener(@NonNull Runnable listener) {
        sListeners.add(listener);
    }

    public static void removeListener(@NonNull Runnable listener) {
        sListeners.remove(listener);
    }

    private static void changed() {
        for (Runnable listener : sListeners) listener.run();
    }

    /**
     * Start installing the tool, or get the install of it that runs already. {@code null} when
     * another install runs in the environment: it has to end first.
     */
    @Nullable
    public static Job install(@NonNull Runner runner, @NonNull String alias, @NonNull String user, @NonNull Tool tool) {
        Job job;
        synchronized (TerminowToolInstaller.class) {
            Job same = sJobs.get(keyOf(alias, user, tool));
            if (same != null && same.mState == State.RUNNING) return same;
            if (runningIn(alias) != null) return null;
            job = new Job(alias, user, tool);
            sJobs.put(keyOf(alias, user, tool), job);
        }
        Job started = job;
        Thread thread = new Thread(() -> run(runner, started), "TermiNow install " + tool.id);
        thread.setDaemon(true);
        thread.start();
        changed();
        return job;
    }

    private static void run(@NonNull Runner runner, @NonNull Job job) {
        Process process;
        try {
            process = runner.start(job.alias, job.user, INSTALL_SCRIPT, job.tool.id);
        } catch (IOException | RuntimeException e) {
            job.onStartFailed("TermiNow: the install did not start: " + e.getMessage());
            changed();
            return;
        }
        job.mProcess = process;
        if (job.mCancelled) process.destroy();
        Thread errors = new Thread(() -> readLines(process.getErrorStream(), job::append), "TermiNow install stderr");
        errors.setDaemon(true);
        errors.start();
        readLines(process.getInputStream(), line -> {
            Stage before = job.mStage;
            job.onLine(line);
            if (job.mStage != before) changed();
        });
        int exitCode;
        try {
            exitCode = process.waitFor();
            errors.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            exitCode = -1;
        }
        job.mProcess = null;
        job.onExit(exitCode);
        changed();
    }

    private interface LineSink {
        void line(@NonNull String line);
    }

    private static void readLines(@NonNull InputStream stream, @NonNull LineSink sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            // apt and curl draw progress with carriage returns: only the last state of a line is kept
            while ((line = reader.readLine()) != null) {
                int cr = line.lastIndexOf('\r', line.length() - 2);
                sink.line(cr >= 0 ? line.substring(cr + 1).replace("\r", "") : line.replace("\r", ""));
            }
        } catch (IOException ignored) {
            // The process ended or was stopped
        }
    }

    /** Find out what is installed, blocking, for the sheet when it opens and after an install. */
    @NonNull
    public static Detection detect(@NonNull Runner runner, @NonNull String alias, @NonNull String user) {
        Detection detection = new Detection();
        Process process;
        try {
            process = runner.start(alias, user, DETECT_SCRIPT);
        } catch (IOException | RuntimeException e) {
            detection.error = String.valueOf(e.getMessage());
            return detection;
        }
        StringBuilder out = new StringBuilder();
        Thread errors = new Thread(() -> readLines(process.getErrorStream(), line -> { }));
        errors.setDaemon(true);
        errors.start();
        Thread reader = new Thread(() -> readLines(process.getInputStream(), line -> out.append(line).append('\n')));
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(DETECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy();
                detection.error = "Checking took too long";
                return detection;
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            detection.error = "Interrupted";
            return detection;
        }
        parseDetection(detection, out.toString());
        if (!detection.ran && detection.error == null) detection.error = "The environment did not start";
        return detection;
    }

    static void parseDetection(@NonNull Detection detection, @NonNull String stdout) {
        for (String line : stdout.split("\n")) {
            line = line.trim();
            if (line.startsWith("TERMINOW_UID=")) {
                detection.ran = true;
                detection.uid = line.substring("TERMINOW_UID=".length());
            } else if (line.startsWith("TERMINOW_ARCH=")) {
                detection.arch = line.substring("TERMINOW_ARCH=".length());
            } else if (line.equals("TERMINOW_APT=yes")) {
                detection.hasApt = true;
            } else if (line.equals("TERMINOW_SUDO=yes")) {
                detection.hasSudo = true;
            } else if (line.startsWith("TERMINOW_HAS_")) {
                int equals = line.indexOf('=');
                if (equals < 0) continue;
                String id = line.substring("TERMINOW_HAS_".length(), equals);
                for (Tool tool : Tool.values())
                    if (tool.id.equals(id)) detection.versions.put(tool, line.substring(equals + 1).trim());
            }
        }
    }

    @Nullable
    static Stage stageOf(@NonNull String name) {
        switch (name.trim()) {
            case "checking": return Stage.CHECKING;
            case "dependencies": return Stage.DEPENDENCIES;
            case "installing": return Stage.INSTALLING;
            case "verifying": return Stage.VERIFYING;
            default: return null;
        }
    }

    /** Whether Claude Code has a native build for the CPU, like `uname -m` names it. */
    public static boolean claudeSupports(@NonNull String arch) {
        return Arrays.asList("aarch64", "arm64", "x86_64", "amd64").contains(arch);
    }

    /** Only for tests: no jobs and no listeners. */
    static synchronized void reset() {
        sJobs.clear();
        sListeners.clear();
    }

    /** The arguments of `proot-distro login` that run the script as the user, see {@link TerminowClaudeSetup#buildCommand}. */
    @NonNull
    static List<String> command(@NonNull java.io.File prefixDir, @NonNull String alias, @NonNull String user,
                                @NonNull String script, @NonNull String... args) {
        return new ArrayList<>(TerminowClaudeSetup.buildCommand(prefixDir, alias, user, script, args));
    }
}
