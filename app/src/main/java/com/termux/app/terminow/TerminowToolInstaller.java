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
 * Installs tools in a Linux environment as one of its users: Claude Code, Cursor CLI, gstack,
 * Git, curl and tmux, and plugins of the official Claude Code marketplace.
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
        CLAUDE("claude"), CURSOR("cursor"),
        // Other AI coding agents, from their official installers. Experimental: not verified on proot.
        CODEX("codex"), GEMINI("gemini"), GROK("grok"), OPENCODE("opencode"), AIDER("aider"),
        /** Hermes Agent by Nous Research, from its official installer; not verified on proot. */
        HERMES("hermes"),
        /** Google Antigravity CLI (agy), from its official installer; verified working on an Ubuntu proot (ARM64, glibc). */
        ANTIGRAVITY("antigravity"),
        GSTACK("gstack"), GIT("git"), CURL("curl"), TMUX("tmux"),
        /** Fish, the interactive shell with suggestions from its history, from apt. */
        FISH("fish"),
        /** Plugins of the official marketplace, the names given to {@link #install}. */
        PLUGINS("plugins");

        public final String id;

        Tool(@NonNull String id) {
            this.id = id;
        }
    }

    /** The stages of an install, in their order. */
    public enum Stage { CHECKING, DEPENDENCIES, INSTALLING, VERIFYING }

    /**
     * RUNNING while it installs, then SUCCEEDED or FAILED. UNKNOWN is a running install that ended
     * without reporting a result and with no real exit code — a dropped SSH channel is the usual
     * cause — so the outcome is genuinely not known and is re-checked on the next look, not a failure.
     */
    public enum State { RUNNING, SUCCEEDED, FAILED, UNKNOWN }

    /** Why an install failed, from its TERMINOW_FAILED line, or how the process ended. */
    public enum Failure {
        NO_APT, NO_SUDO, SUDO_PASSWORD, UNSUPPORTED_ARCH, NEEDS_CLAUDE, APT_UPDATE, APT_INSTALL, NETWORK, INSTALLER, PATH, VERIFY,
        CANCELLED, START, EXIT;

        @NonNull
        static Failure of(@NonNull String code) {
            switch (code) {
                case "no-apt": return NO_APT;
                case "no-sudo": return NO_SUDO;
                case "sudo-password": return SUDO_PASSWORD;
                case "unsupported-arch": return UNSUPPORTED_ARCH;
                case "needs-claude": return NEEDS_CLAUDE;
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
        /** The plugins of a {@link Tool#PLUGINS} job, empty for the others. */
        public final List<String> args;
        private volatile Stage mStage = Stage.CHECKING;
        private volatile State mState = State.RUNNING;
        @Nullable private volatile Failure mFailure;
        private volatile int mExitCode = -1;
        private volatile String mVersion = "";
        private volatile boolean mAlreadyInstalled;
        private final StringBuilder mLog = new StringBuilder();
        @Nullable private volatile Process mProcess;
        private volatile boolean mCancelled;

        Job(@NonNull String alias, @NonNull String user, @NonNull Tool tool, @NonNull String... args) {
            this.alias = alias;
            this.user = user;
            this.tool = tool;
            this.args = java.util.Collections.unmodifiableList(Arrays.asList(args));
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
            } else if (mFailure == null && exitCode < 0) {
                // Ended with no result and no real exit code (e.g. the SSH channel dropped): the
                // install may have finished on the host or not. Not a definite failure — re-check it.
                mState = State.UNKNOWN;
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
        /** The OS, like "Ubuntu 24.04 LTS", from /etc/os-release, empty if not known. */
        public String os = "";
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

    /** Where gstack is, in the home of the user: the folder of skills of Claude Code. */
    static final String GSTACK_DIR = ".claude/skills/gstack";

    /** The official marketplace of Claude Code plugins, its name and its GitHub repository. */
    public static final String MARKETPLACE = "claude-plugins-official";
    static final String MARKETPLACE_REPO = "anthropics/claude-plugins-official";

    /** Adds the official marketplace to Claude Code, if it is not there. */
    static final String ADD_MARKETPLACE =
        "{ claude plugin marketplace list 2>/dev/null | grep -q " + MARKETPLACE + " || claude plugin marketplace add " + MARKETPLACE_REPO + " </dev/null; }";

    /** The ports of curl that mean the network: it could not resolve, connect, finish TLS, or got nothing. */
    static final String NETWORK_EXIT_CODES = "6|7|28|35|52|56";

    /**
     * The PATH of the environment itself, with ~/.local/bin first. proot-distro adds the folders
     * of TermiNow (Android programs under /data) at the end, so without this, the git and curl of
     * TermiNow were reported as installed in Ubuntu, and were used, before Ubuntu had its own.
     */
    static final String OWN_PATH =
        "p=; IFS=:; for d in $PATH; do case $d in /data/*) ;; *) p=\"$p${p:+:}$d\";; esac; done; unset IFS\n" +
        "PATH=\"$HOME/.local/bin:$p\"; export PATH\n";

    /**
     * What the user has: its uid, the CPU, apt and sudo, and the version of each tool. Claude
     * Code and Cursor CLI may be in `~/.local/bin`, where their installers put them, which is not
     * always on the PATH. gstack is a folder of Claude Code skills, with its version in a file.
     */
    static final String DETECT_SCRIPT =
        OWN_PATH +
        "echo \"TERMINOW_UID=$(id -u)\"\n" +
        "echo \"TERMINOW_ARCH=$(uname -m)\"\n" +
        "( . /etc/os-release 2>/dev/null; [ -n \"$PRETTY_NAME\" ] && echo \"TERMINOW_OS=$PRETTY_NAME\" ) 2>/dev/null\n" +
        "command -v apt-get >/dev/null 2>&1 && echo TERMINOW_APT=yes\n" +
        "command -v sudo >/dev/null 2>&1 && echo TERMINOW_SUDO=yes\n" +
        "for t in claude cursor codex gemini grok opencode aider hermes antigravity gstack git curl tmux fish; do\n" +
        "  v=\n" +
        "  case $t in\n" +
        "    claude) v=$(claude --version 2>/dev/null);;\n" +
        "    cursor) v=$(cursor-agent --version 2>/dev/null);;\n" +
        "    hermes) command -v hermes >/dev/null 2>&1 && { v=$(hermes --version 2>/dev/null | head -n 1); [ -n \"$v\" ] || v=hermes; };;\n" +
        "    antigravity) command -v agy >/dev/null 2>&1 && { v=$(agy --version 2>/dev/null | head -n 1); [ -n \"$v\" ] || v=agy; };;\n" +
        "    gstack) [ -f \"$HOME/" + GSTACK_DIR + "/SKILL.md\" ] && v=\"gstack $(head -n 1 \"$HOME/" + GSTACK_DIR + "/VERSION\" 2>/dev/null)\";;\n" +
        "    tmux) v=$(tmux -V 2>/dev/null);;\n" +
        "    *) v=$($t --version 2>/dev/null);;\n" +
        "  esac\n" +
        "  [ -n \"$v\" ] && echo \"TERMINOW_HAS_$t=$(printf '%s\\n' \"$v\" | head -n 1)\"\n" +
        "done\n" +
        "exit 0\n";

    /**
     * Installs the tool {@code $1} as the user that runs it. apt is used as root directly, never
     * with sudo; as another user only with sudo that needs no password, which this process can
     * not type. sudo is never installed. apt waits for the lock of another apt instead of failing.
     * Claude Code and Cursor CLI come from their official installers, as the user, with curl
     * and Git from apt. gstack is cloned into the skills of Claude Code and set up with Bun, which
     * comes from its official installer. Plugins, the arguments after {@code plugins}, are
     * installed from the official marketplace with Claude Code, which is added if it is not there.
     */
    static final String INSTALL_SCRIPT =
        "stage() { echo \"TERMINOW_STAGE=$1\"; }\n" +
        "fail() { echo \"TERMINOW_FAILED=$1\"; exit \"${2:-1}\"; }\n" +
        "first() { printf '%s\\n' \"$1\" | head -n 1; }\n" +
        "tool=\"$1\"\n" +
        "stage checking\n" +
        OWN_PATH +
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
        // ~/.bun/bin on the PATH of bash for good, once, for the tools of gstack
        "persist_bun_path() {\n" +
        "  rc=\"$HOME/.bashrc\"\n" +
        "  grep -qs 'TermiNow: Bun PATH' \"$rc\" && return 0\n" +
        "  grep -Eqs '^[^#]*PATH=.*\\.bun/bin' \"$rc\" && return 0\n" +
        "  { echo; echo '# TermiNow: Bun PATH'; echo 'case \":$PATH:\" in *\":$HOME/.bun/bin:\"*) ;; *) export PATH=\"$HOME/.bun/bin:$PATH\" ;; esac'; } >> \"$rc\" || fail path 31\n" +
        "}\n" +
        // Run an official installer from the network, with bash; its exit code says why it failed
        "run_installer() {\n" +
        "  bash -o pipefail -c \"curl -fsSL $1 | bash\"\n" +
        "  s=$?\n" +
        "  if [ \"$s\" -ne 0 ]; then case \"$s\" in " + NETWORK_EXIT_CODES + ") fail network \"$s\";; *) fail installer \"$s\";; esac; fi\n" +
        "}\n" +
        // A Node.js >= 20 runtime for the npm tools. Ubuntu's apt node is 18, too old for current
        // Gemini CLI / Codex; prefer a usable node already present, else install a TermiNow-managed
        // Node 22 LTS into ~/.terminow/node, verified by SHA-256, without touching the system node
        // other tools may use. Sets NODE_HOME (empty means a suitable node is already on the PATH).
        "NODE_VER=v22.23.4\n" +
        "ensure_node() {\n" +
        "  case \"$(uname -m)\" in\n" +
        "    aarch64|arm64) narch=arm64; nsha=f7a1e486dbc8b286847815b477d4fb5e9fb963ed4489c3e1588853d685f589f9;;\n" +
        "    x86_64|amd64) narch=x64; nsha=b8492284c76a54baf58d61668dd0e41ee0fc98adabf9503d30f05d08f55b4406;;\n" +
        "    *) fail unsupported-arch 15;;\n" +
        "  esac\n" +
        "  md=\"$HOME/.terminow/node/node-$NODE_VER-linux-$narch\"\n" +
        "  if [ -x \"$md/bin/node\" ] && [ \"$(\"$md/bin/node\" --version 2>/dev/null)\" = \"$NODE_VER\" ]; then NODE_HOME=\"$md\"; return 0; fi\n" +
        "  if command -v node >/dev/null 2>&1; then\n" +
        "    m=$(node -e 'process.stdout.write(String(process.versions.node.split(\".\")[0]))' 2>/dev/null)\n" +
        "    case \"$m\" in ''|*[!0-9]*) m=0;; esac\n" +
        "    if [ \"$m\" -ge 20 ]; then NODE_HOME=; return 0; fi\n" +
        "  fi\n" +
        "  echo \"TermiNow: preparing Node.js $NODE_VER ($narch)\"\n" +
        "  apt_install curl ca-certificates\n" +
        "  mkdir -p \"$HOME/.terminow/node\" || fail path 31\n" +
        "  dl=\"$HOME/.terminow/node/.node.$$.tar.gz\"\n" +
        "  url=\"https://nodejs.org/dist/$NODE_VER/node-$NODE_VER-linux-$narch.tar.gz\"\n" +
        "  bash -o pipefail -c \"curl -fsSL '$url' -o '$dl'\" || { s=$?; rm -f \"$dl\"; case \"$s\" in " + NETWORK_EXIT_CODES + ") fail network \"$s\";; *) fail installer \"$s\";; esac; }\n" +
        "  echo \"$nsha  $dl\" | sha256sum -c - >/dev/null 2>&1 || { rm -f \"$dl\"; fail verify 30; }\n" +
        "  rm -rf \"$md\"; mkdir -p \"$md\"\n" +
        "  tar -xzf \"$dl\" -C \"$md\" --strip-components=1 || { rm -f \"$dl\"; rm -rf \"$md\"; fail installer 32; }\n" +
        "  rm -f \"$dl\"\n" +
        "  [ -x \"$md/bin/node\" ] || fail installer 32\n" +
        "  NODE_HOME=\"$md\"\n" +
        "}\n" +
        // Pin an installed npm bin's launcher to the managed Node, so opening the tool later does not
        // fall back to the system node on the PATH. A no-op when a suitable system node is used.
        "bind_node_bin() {\n" +
        "  [ -n \"$NODE_HOME\" ] || return 0\n" +
        "  b=\"$HOME/.local/bin/$1\"\n" +
        "  [ -e \"$b\" ] || return 0\n" +
        "  real=$(readlink -f \"$b\" 2>/dev/null) || real=\"$b\"\n" +
        "  if [ \"$real\" = \"$b\" ]; then mv \"$b\" \"$b.real\" || fail path 31; real=\"$b.real\"; fi\n" +
        "  { printf '#!/bin/sh\\n'; printf 'exec \"%s/bin/node\" \"%s\" \"$@\"\\n' \"$NODE_HOME\" \"$real\"; } > \"$b\" || fail path 31\n" +
        "  chmod +x \"$b\" || fail path 31\n" +
        "}\n" +
        // Install a global npm package into ~/.local (no sudo), using a Node >= 20 runtime (managed if needed)
        "npm_install() {\n" +
        "  ensure_node\n" +
        "  if [ -n \"$NODE_HOME\" ]; then PATH=\"$NODE_HOME/bin:$PATH\"; export PATH; fi\n" +
        "  command -v npm >/dev/null 2>&1 || fail installer 17\n" +
        "  mkdir -p \"$HOME/.local/bin\" \"$HOME/.local/lib\" 2>/dev/null || true\n" +
        "  npm config set prefix \"$HOME/.local\" >/dev/null 2>&1 || true\n" +
        "  npm install -g \"$1\" || fail installer 36\n" +
        "}\n" +
        "native_arch() { case \"$(uname -m)\" in aarch64|arm64|x86_64|amd64) ;; *) fail unsupported-arch 15;; esac; }\n" +
        "case \"$tool\" in\n" +
        "  claude)\n" +
        "    if v=$(claude --version 2>/dev/null) && [ -n \"$v\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    apt_install curl ca-certificates git\n" +
        "    command -v bash >/dev/null 2>&1 || apt_install bash\n" +
        "    stage installing\n" +
        "    run_installer https://claude.ai/install.sh\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    v=$(claude --version 2>&1) || { echo \"$v\"; fail verify 30; }\n" +
        "    [ -n \"$v\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        "  cursor)\n" +
        "    if v=$(cursor-agent --version 2>/dev/null) && [ -n \"$v\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    apt_install curl ca-certificates\n" +
        "    command -v bash >/dev/null 2>&1 || apt_install bash\n" +
        "    stage installing\n" +
        "    run_installer https://cursor.com/install\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    v=$(cursor-agent --version 2>&1) || { echo \"$v\"; fail verify 30; }\n" +
        "    [ -n \"$v\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        // Experimental AI agents from npm (@openai/codex, @google/gemini-cli, @vibe-kit/grok-cli)
        "  codex|gemini|grok)\n" +
        "    case \"$tool\" in\n" +
        "      codex) bin=codex; pkg='@openai/codex';;\n" +
        "      gemini) bin=gemini; pkg='@google/gemini-cli';;\n" +
        "      grok) bin=grok; pkg='@vibe-kit/grok-cli';;\n" +
        "    esac\n" +
        "    if v=$(\"$bin\" --version 2>/dev/null) && [ -n \"$v\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    ensure_node\n" +
        "    stage installing\n" +
        "    npm_install \"$pkg\"\n" +
        "    bind_node_bin \"$bin\"\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    v=$(\"$bin\" --version 2>&1) || { echo \"$v\"; fail verify 30; }\n" +
        "    [ -n \"$v\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        // OpenCode, from its official install script, into ~/.local/bin
        "  opencode)\n" +
        "    if v=$(opencode --version 2>/dev/null) && [ -n \"$v\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    apt_install curl ca-certificates\n" +
        "    stage installing\n" +
        "    export OPENCODE_INSTALL_DIR=\"$HOME/.local/bin\"\n" +
        "    run_installer https://opencode.ai/install\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    v=$(opencode --version 2>&1) || { echo \"$v\"; fail verify 30; }\n" +
        "    [ -n \"$v\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        // Aider, via its official aider-install (uv + its own Python), as the user into ~/.local
        "  aider)\n" +
        "    if v=$(aider --version 2>/dev/null) && [ -n \"$v\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    apt_install python3 python3-pip ca-certificates\n" +
        "    stage installing\n" +
        "    python3 -m pip install --user aider-install >/dev/null 2>&1 || python3 -m pip install --user --break-system-packages aider-install || fail installer 37\n" +
        "    \"$HOME/.local/bin/aider-install\" </dev/null || fail installer 38\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    v=$(aider --version 2>&1) || { echo \"$v\"; fail verify 30; }\n" +
        "    [ -n \"$v\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        // Hermes Agent by Nous Research, from its official install script. Experimental: not verified on proot.
        "  hermes)\n" +
        "    if command -v hermes >/dev/null 2>&1; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying\n" +
        "      v=$(hermes --version 2>/dev/null | head -n1); [ -n \"$v\" ] || v=hermes; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    apt_install curl ca-certificates\n" +
        "    stage installing\n" +
        "    run_installer https://hermes-agent.nousresearch.com/install.sh\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    command -v hermes >/dev/null 2>&1 || fail verify 30\n" +
        "    v=$(hermes --version 2>/dev/null | head -n1); [ -n \"$v\" ] || v=hermes\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        // Google Antigravity CLI (agy), from its official install script. Experimental: not verified on proot.
        "  antigravity)\n" +
        "    if command -v agy >/dev/null 2>&1; then\n" +
        "      echo TERMINOW_ALREADY=1; persist_path; stage verifying\n" +
        "      v=$(agy --version 2>/dev/null | head -n1); [ -n \"$v\" ] || v=agy; echo \"TERMINOW_VERSION=$(first \"$v\")\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    apt_install curl ca-certificates\n" +
        "    stage installing\n" +
        "    run_installer https://antigravity.google/cli/install.sh\n" +
        "    persist_path\n" +
        "    stage verifying\n" +
        "    command -v agy >/dev/null 2>&1 || fail verify 30\n" +
        "    v=$(agy --version 2>/dev/null | head -n1); [ -n \"$v\" ] || v=agy\n" +
        "    echo \"TERMINOW_VERSION=$(first \"$v\")\"\n" +
        "    ;;\n" +
        "  gstack)\n" +
        "    d=\"$HOME/" + GSTACK_DIR + "\"\n" +
        "    command -v claude >/dev/null 2>&1 || fail needs-claude 16\n" +
        "    if [ -f \"$d/SKILL.md\" ]; then\n" +
        "      echo TERMINOW_ALREADY=1; stage verifying; echo \"TERMINOW_VERSION=gstack $(head -n 1 \"$d/VERSION\" 2>/dev/null)\"; exit 0\n" +
        "    fi\n" +
        "    native_arch\n" +
        "    stage dependencies\n" +
        "    apt_install curl ca-certificates git unzip\n" +
        "    command -v bash >/dev/null 2>&1 || apt_install bash\n" +
        "    PATH=\"$HOME/.bun/bin:$PATH\"; export PATH\n" +
        "    command -v bun >/dev/null 2>&1 || run_installer https://bun.sh/install\n" +
        "    persist_bun_path\n" +
        "    stage installing\n" +
        "    mkdir -p \"$(dirname \"$d\")\" || fail installer 32\n" +
        "    rm -rf \"$d\"\n" +
        "    git clone --single-branch --depth 1 https://github.com/garrytan/gstack.git \"$d\" || fail network 33\n" +
        // A setup that failed leaves no gstack behind, so it is not taken for installed
        "    (cd \"$d\" && ./setup) || { s=$?; rm -rf \"$d\"; fail installer \"$s\"; }\n" +
        "    stage verifying\n" +
        "    [ -f \"$d/SKILL.md\" ] || fail verify 30\n" +
        "    echo \"TERMINOW_VERSION=gstack $(head -n 1 \"$d/VERSION\" 2>/dev/null)\"\n" +
        "    ;;\n" +
        "  plugins)\n" +
        "    shift\n" +
        "    command -v claude >/dev/null 2>&1 || fail needs-claude 16\n" +
        "    stage installing\n" +
        "    " + ADD_MARKETPLACE + " || fail installer 34\n" +
        "    n=0; bad=\n" +
        // -y: there is nobody to confirm at a prompt; the user chose each plugin in TermiNow
        "    for p in \"$@\"; do\n" +
        "      echo \"TermiNow: installing the plugin $p\"\n" +
        "      if claude plugin install -y \"$p@" + MARKETPLACE + "\" </dev/null; then n=$((n + 1)); else bad=\"$bad $p\"; fi\n" +
        "    done\n" +
        "    stage verifying\n" +
        "    [ -z \"$bad\" ] || { echo \"TermiNow: these plugins were not installed:$bad\"; fail installer 35; }\n" +
        "    echo \"TERMINOW_VERSION=$n\"\n" +
        "    ;;\n" +
        "  git|curl|tmux|fish)\n" +
        "    case \"$tool\" in git) pkgs=git; check='git --version';; curl) pkgs='curl ca-certificates'; check='curl --version';; tmux) pkgs=tmux; check='tmux -V';;\n" +
        // Fish is only taken as installed once the Fish of the environment runs
        "      fish) pkgs=fish; check='fish --version';; esac\n" +
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

    /**
     * The plugins of the official marketplace and which are installed, as the JSON of Claude Code
     * between two lines, after the marketplace was added or brought up to date.
     */
    static final String PLUGINS_SCRIPT =
        OWN_PATH +
        "command -v claude >/dev/null 2>&1 || { echo TERMINOW_NO_CLAUDE; exit 0; }\n" +
        ADD_MARKETPLACE + " >/dev/null 2>&1\n" +
        "claude plugin marketplace update " + MARKETPLACE + " </dev/null >/dev/null 2>&1\n" +
        "echo TERMINOW_JSON_BEGIN\n" +
        "claude plugin list --available --json </dev/null\n" +
        "echo\n" +
        "echo TERMINOW_JSON_END\n";

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
    public static Job install(@NonNull Runner runner, @NonNull String alias, @NonNull String user, @NonNull Tool tool,
                              @NonNull String... args) {
        Job job;
        synchronized (TerminowToolInstaller.class) {
            Job same = sJobs.get(keyOf(alias, user, tool));
            if (same != null && same.mState == State.RUNNING) return same;
            if (runningIn(alias) != null) return null;
            job = new Job(alias, user, tool, args);
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
            List<String> args = new ArrayList<>();
            args.add(job.tool.id);
            args.addAll(job.args);
            process = runner.start(job.alias, job.user, INSTALL_SCRIPT, args.toArray(new String[0]));
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
            } else if (line.startsWith("TERMINOW_OS=")) {
                detection.os = line.substring("TERMINOW_OS=".length());
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

    /** A plugin of the official marketplace. */
    public static final class Plugin {
        public final String name;
        public final String description;
        public final long installCount;

        Plugin(@NonNull String name, @NonNull String description, long installCount) {
            this.name = name;
            this.description = description;
            this.installCount = installCount;
        }
    }

    /** The plugins of the official marketplace, most installed first, and the names of those installed. */
    public static final class PluginCatalog {
        public final List<Plugin> plugins = new ArrayList<>();
        public final java.util.Set<String> installed = new java.util.HashSet<>();
        /** Claude Code is not in the environment, so it has no plugins. */
        public boolean noClaude;
        @Nullable public String error;
    }

    public static final long PLUGINS_TIMEOUT_MS = 180_000;

    /** Get the plugins, blocking: Claude Code adds or updates the marketplace first, over the network. */
    @NonNull
    public static PluginCatalog listPlugins(@NonNull Runner runner, @NonNull String alias, @NonNull String user) {
        PluginCatalog catalog = new PluginCatalog();
        Process process;
        try {
            process = runner.start(alias, user, PLUGINS_SCRIPT);
        } catch (IOException | RuntimeException e) {
            catalog.error = String.valueOf(e.getMessage());
            return catalog;
        }
        StringBuilder out = new StringBuilder();
        Thread errors = new Thread(() -> readLines(process.getErrorStream(), line -> { }));
        errors.setDaemon(true);
        errors.start();
        Thread reader = new Thread(() -> readLines(process.getInputStream(), line -> out.append(line).append('\n')));
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(PLUGINS_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroy();
                catalog.error = "Getting the plugins took too long";
                return catalog;
            }
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroy();
            catalog.error = "Interrupted";
            return catalog;
        }
        parsePlugins(catalog, out.toString());
        return catalog;
    }

    static void parsePlugins(@NonNull PluginCatalog catalog, @NonNull String stdout) {
        if (stdout.contains("TERMINOW_NO_CLAUDE")) {
            catalog.noClaude = true;
            return;
        }
        int begin = stdout.indexOf("TERMINOW_JSON_BEGIN");
        int end = stdout.indexOf("TERMINOW_JSON_END");
        if (begin < 0 || end < begin) {
            catalog.error = "Claude Code did not list the plugins";
            return;
        }
        try {
            org.json.JSONObject json = new org.json.JSONObject(stdout.substring(begin + "TERMINOW_JSON_BEGIN".length(), end).trim());
            org.json.JSONArray installed = json.optJSONArray("installed");
            for (int i = 0; installed != null && i < installed.length(); i++) {
                org.json.JSONObject plugin = installed.optJSONObject(i);
                if (plugin == null) continue;
                // "name@marketplace", or the name and the marketplace apart
                String id = plugin.optString("id", plugin.optString("pluginId"));
                if (!id.contains("@")) id = plugin.optString("name") + "@" + plugin.optString("marketplace", plugin.optString("marketplaceName"));
                if (id.endsWith("@" + MARKETPLACE)) catalog.installed.add(id.substring(0, id.length() - MARKETPLACE.length() - 1));
            }
            org.json.JSONArray available = json.optJSONArray("available");
            for (int i = 0; available != null && i < available.length(); i++) {
                org.json.JSONObject plugin = available.optJSONObject(i);
                if (plugin == null || !MARKETPLACE.equals(plugin.optString("marketplaceName"))) continue;
                String name = plugin.optString("name");
                if (name.isEmpty()) continue;
                catalog.plugins.add(new Plugin(name, plugin.optString("description"), plugin.optLong("installCount")));
            }
        } catch (org.json.JSONException e) {
            catalog.error = "The list of plugins could not be read: " + e.getMessage();
            return;
        }
        java.util.Collections.sort(catalog.plugins, (a, b) -> Long.compare(b.installCount, a.installCount));
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

    /** Experimental tools: added with official installers but not yet verified on an Ubuntu proot. */
    public static boolean experimental(@NonNull Tool tool) {
        switch (tool) {
            case CODEX: case GEMINI: case GROK: case OPENCODE: case AIDER: case HERMES: return true;
            default: return false;
        }
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
