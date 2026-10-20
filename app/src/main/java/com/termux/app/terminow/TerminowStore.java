package com.termux.app.terminow;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the hosts and snippets shown on the {@link TerminowHome} screen.
 */
public class TerminowStore {

    public static class Host {
        public String label = "";
        public String address = "";
        public int port = 22;
        public String username = "";
        public boolean mosh;
        /**
         * How mosh starts its server, like "mosh-server new -p 60000:60010": the program and the
         * UDP ports, see {@link #parseMoshServer}. Empty for the defaults of mosh.
         */
        public String moshServer = "";
        /** The password typed in when the host asks for it, empty to be asked in the terminal. */
        public String password = "";
        /** The path of the private key file to log in with, empty to use the default keys. */
        public String keyPath = "";
        /** The name of the group that the host is in, empty if it is not in a group. */
        public String group = "";
        /** The name of the snippet to run once the connection is made, empty for none. */
        public String startupSnippet = "";
        /** Words to find and tell hosts apart by, only shown and searched, never run. */
        public final List<String> tags = new ArrayList<>();
        /** Whether the Backspace key sends Ctrl-H (^H) instead of DEL (^?), for old systems. */
        public boolean backspaceSendsCtrlH;
        /** Connect with telnet instead of ssh, to {@link #telnetPort}. */
        public boolean telnet;
        public int telnetPort = 23;
        /** Forward the ssh agent of this device to the host, `ssh -A`. */
        public boolean agentForwarding;
        /**
         * Keep each terminal of the host in a tmux session on the server, which is attached to
         * again when the tab reconnects or is restored, see {@link #remoteTmuxCommand(String)}.
         */
        public boolean remoteTmux;
        /** The {@link #getKey()} of another host to connect through, empty for none. */
        public String jumpHost = "";
        /** {@link #PROXY_SOCKS5}, {@link #PROXY_HTTP}, or empty for a direct connection. */
        public String proxyType = "";
        public String proxyHost = "";
        public int proxyPort;
        /** Environment variables sent to the host with `SetEnv`, if its sshd accepts them. */
        public final Map<String, String> env = new LinkedHashMap<>();
        /** The name of a terminal theme for the sessions of the host, empty for the one of the settings. */
        public String colorScheme = "";
        /** The {@link Identity#id} whose username, password and key the host logs in with, empty for its own. */
        public String identity = "";
        /** {@link #PROTOCOL_SSH} (also Mosh and Telnet), {@link #PROTOCOL_RDP} or {@link #PROTOCOL_WINRM}. */
        public String protocol = PROTOCOL_SSH;
        /** RDP and WinRM: the Windows domain of the user, empty for a local account. */
        public String domain = "";
        /** RDP: Network Level Authentication, on unless the server can not do it. */
        public boolean rdpNla = true;
        /** RDP: share text of the clipboard with Windows, off unless the user turned it on. */
        public boolean rdpClipboard;
        /** RDP: the size of the desktop, 0 for the size of the screen. */
        public int rdpWidth;
        public int rdpHeight;
        /** WinRM: HTTPS (port 5986) rather than HTTP (5985), and its certificate verified. */
        public boolean winrmHttps = true;
        public boolean winrmVerify = true;

        public static final String PROTOCOL_SSH = "ssh";
        public static final String PROTOCOL_RDP = "rdp";
        public static final String PROTOCOL_WINRM = "winrm";

        public boolean isRdp() {
            return PROTOCOL_RDP.equals(protocol);
        }

        public boolean isWinrm() {
            return PROTOCOL_WINRM.equals(protocol);
        }

        /** Whether it opens a terminal with ssh, mosh or telnet, not a desktop or a PowerShell console. */
        public boolean isTerminal() {
            return !isRdp() && !isWinrm();
        }

        /**
         * The ssh of a tunnel through this host, without a forward of its own, like
         * `ssh -N -i key -p 22 user@host`. Its fields are validated like the ones of a connection.
         */
        @NonNull
        public String getTunnelCommand(@Nullable Host jump, @NonNull String proxyConnector, int keepAliveInterval, int keepAliveCountMax) {
            return "ssh -N " + getSshOptions(keepAliveInterval, keepAliveCountMax).trim() +
                getRouteOptions(jump, proxyConnector, keepAliveInterval, keepAliveCountMax) + " " + getTarget();
        }

        public static final String PROXY_SOCKS5 = "socks5";
        public static final String PROXY_HTTP = "http";

        /** An identifier of the host that does not change with its label, for the recent connections. */
        @NonNull
        public String getKey() {
            return username + "@" + address + ":" + port;
        }

        /** The ssh or sftp command of the host without its options, like `ssh root@host -p 2222`, to show it. */
        @NonNull
        public String getSummary(boolean sftp) {
            return (sftp ? "sftp " : "ssh ") + getTarget() + (port == 22 ? "" : (sftp ? " -P " : " -p ") + port);
        }

        /** The name shown for the host and used as the name of its sessions. */
        @NonNull
        public String getTitle() {
            return label.isEmpty() ? address : label;
        }

        @NonNull
        private String getTarget() {
            return (username.isEmpty() ? "" : username + "@") + address;
        }

        @NonNull
        private String getSshOptions(int keepAliveInterval, int keepAliveCountMax) {
            return getKeyOptions() + (port == 22 ? "" : " -p " + port) + ACCEPT_NEW_HOST_KEYS + getKeepAliveOptions(keepAliveInterval, keepAliveCountMax);
        }

        /**
         * The key of a host that was never connected to is saved without asking, like Termius
         * does; a host whose key changed is still refused, which is shown as why it failed.
         */
        static final String ACCEPT_NEW_HOST_KEYS = " -o StrictHostKeyChecking=accept-new";

        /**
         * The key: a key file, or the public key of a biometric or FIDO2 key, whose private key ssh uses
         * through the agent of the app and only that one.
         */
        @NonNull
        private String getKeyOptions() {
            if (keyPath.isEmpty()) return "";
            if (TerminowBiometricKeys.isAgentKeyPath(keyPath))
                return " -i " + keyPath + " -o IdentitiesOnly=yes -o IdentityAgent=" + TerminowBiometricKeys.AGENT_SOCKET;
            return " -i " + keyPath;
        }

        @NonNull
        private static String getKeepAliveOptions(int keepAliveInterval, int keepAliveCountMax) {
            return keepAliveInterval <= 0 ? "" : " -o ServerAliveInterval=" + keepAliveInterval + " -o ServerAliveCountMax=" + keepAliveCountMax;
        }

        /**
         * How the connection reaches the host: through another host with `ssh -W`, or through a
         * SOCKS5 or HTTP proxy with the proxy connector of the app. Empty for a direct connection.
         *
         * @param jump The host of {@link #jumpHost}, {@code null} if none.
         * @param proxyConnector The path of the proxy connector script, see {@link TerminowProxy}.
         */
        @NonNull
        public String getRouteOptions(@Nullable Host jump, @NonNull String proxyConnector, int keepAliveInterval, int keepAliveCountMax) {
            String command = null;
            if (jump != null) {
                // The jump host never has quotes or spaces, its fields are validated like this host
                command = "ssh" + jump.getSshOptions(keepAliveInterval, keepAliveCountMax) + " -W %h:%p " + jump.getTarget();
            } else if (!proxyType.isEmpty() && !proxyConnector.isEmpty()) {
                command = "bash " + proxyConnector + " " + proxyType + " " + proxyHost + " " + proxyPort + " %h %p";
            }
            return command == null ? "" : " -o " + shellQuote("ProxyCommand=" + command);
        }

        /** The options of the ssh session only: agent forwarding and the environment variables. */
        @NonNull
        private String getSessionOptions() {
            StringBuilder options = new StringBuilder();
            if (agentForwarding) options.append(" -A");
            for (Map.Entry<String, String> variable : env.entrySet())
                options.append(" -o ").append(shellQuote("SetEnv=" + variable.getKey() + "=" + quoteSetEnvValue(variable.getValue())));
            return options.toString();
        }

        /** The command run in a new session to transfer files to and from the host with sftp. */
        @NonNull
        public String getSftpCommand(int keepAliveInterval, int keepAliveCountMax) {
            return getSftpCommand(null, "", keepAliveInterval, keepAliveCountMax);
        }

        @NonNull
        public String getSftpCommand(@Nullable Host jump, @NonNull String proxyConnector, int keepAliveInterval, int keepAliveCountMax) {
            // The option for the port of sftp is upper case
            return "sftp" + getKeyOptions() + (port == 22 ? "" : " -P " + port) + ACCEPT_NEW_HOST_KEYS +
                getKeepAliveOptions(keepAliveInterval, keepAliveCountMax) +
                getRouteOptions(jump, proxyConnector, keepAliveInterval, keepAliveCountMax) + " " + getTarget();
        }

        /** The command run in a new session to only forward a port through the host, see {@link Forward#getOption()}. */
        @NonNull
        public String getForwardCommand(@NonNull Forward forward, int keepAliveInterval, int keepAliveCountMax) {
            return getForwardCommand(forward, null, "", keepAliveInterval, keepAliveCountMax);
        }

        @NonNull
        public String getForwardCommand(@NonNull Forward forward, @Nullable Host jump, @NonNull String proxyConnector,
                                        int keepAliveInterval, int keepAliveCountMax) {
            return "ssh -N " + forward.getOption() + getSshOptions(keepAliveInterval, keepAliveCountMax) +
                getRouteOptions(jump, proxyConnector, keepAliveInterval, keepAliveCountMax) + " " + getTarget();
        }

        /**
         * The command run in a new session to connect to the host.
         *
         * @param keepAliveInterval The seconds for `ServerAliveInterval` of ssh, 0 to not set it.
         * @param keepAliveCountMax The count for `ServerAliveCountMax` of ssh.
         */
        @NonNull
        public String getCommand(int keepAliveInterval, int keepAliveCountMax) {
            return getCommand(null, "", keepAliveInterval, keepAliveCountMax);
        }

        @NonNull
        public String getCommand(@Nullable Host jump, @NonNull String proxyConnector, int keepAliveInterval, int keepAliveCountMax) {
            return getCommand(jump, proxyConnector, keepAliveInterval, keepAliveCountMax, "");
        }

        /**
         * The command of a new session, in the tmux session {@code tmuxName} on the server when
         * it is not empty and the host keeps its terminals in tmux; telnet never does.
         */
        @NonNull
        public String getCommand(@Nullable Host jump, @NonNull String proxyConnector, int keepAliveInterval, int keepAliveCountMax,
                                 @NonNull String tmuxName) {
            if (telnet)
                return "telnet" + (username.isEmpty() ? "" : " -l " + username) + " " + address + " " + telnetPort;
            String target = getTarget();
            String sshOptions = getSshOptions(keepAliveInterval, keepAliveCountMax);
            String remote = remoteTmux && isValidRemoteTmuxName(tmuxName) ? shellQuote(remoteTmuxCommand(tmuxName)) : "";
            if (mosh)
                // Mosh talks to the host itself over UDP, so a jump host, proxy or SetEnv does not apply
                return "mosh" + getMoshServerOptions() + " --ssh='ssh" + (agentForwarding ? " -A" : "") + sshOptions + "'" + " " + target +
                    (remote.isEmpty() ? "" : " -- sh -c " + shellQuote(remoteTmuxScript(tmuxName)));
            // A command needs -t for a terminal on the server, which tmux needs
            return "ssh" + (remote.isEmpty() ? "" : " -t") + getSessionOptions() + sshOptions +
                getRouteOptions(jump, proxyConnector, keepAliveInterval, keepAliveCountMax) + " " + target + (remote.isEmpty() ? "" : " " + remote);
        }

        /**
         * The options of ssh for another program that connects to the host, like a backup, and
         * the user@host to connect to: the key, port, keep alive and route, never mosh or a
         * terminal. The options are quoted for bash.
         */
        @NonNull
        public String[] getTransport(@Nullable Host jump, @NonNull String proxyConnector, int keepAliveInterval, int keepAliveCountMax) {
            return new String[]{(getSshOptions(keepAliveInterval, keepAliveCountMax) +
                getRouteOptions(jump, proxyConnector, keepAliveInterval, keepAliveCountMax)).trim(), getTarget()};
        }

        /** The program and ports of the mosh server as options of mosh, empty for its defaults. */
        @NonNull
        private String getMoshServerOptions() {
            String[] server = parseMoshServer(moshServer);
            if (server == null) return "";
            return (server[0].equals("mosh-server") ? "" : " --server=" + server[0]) + (server[1].isEmpty() ? "" : " --port=" + server[1]);
        }

        /**
         * Returns {@code null} if the options of the host can be used together, otherwise the
         * resource id of the message that says why not.
         */
        @Nullable
        public Integer validateOptions(@NonNull List<Host> hosts) {
            if (!tags.isEmpty()) for (String tag : tags) if (!isValidTag(tag)) return R.string.terminow_host_invalid_tag;
            for (Map.Entry<String, String> variable : env.entrySet())
                if (!isValidEnvName(variable.getKey()) || !isValidEnvValue(variable.getValue())) return R.string.terminow_host_invalid_env;
            if (telnet && !isValidPort(telnetPort)) return R.string.terminow_host_invalid_port;
            if (!proxyType.isEmpty()) {
                if (!PROXY_SOCKS5.equals(proxyType) && !PROXY_HTTP.equals(proxyType)) return R.string.terminow_host_invalid_proxy;
                if (!isValidAddress(proxyHost) || !isValidPort(proxyPort)) return R.string.terminow_host_invalid_proxy;
            }
            if (!jumpHost.isEmpty()) {
                if (jumpHost.equals(getKey())) return R.string.terminow_host_jump_self;
                Host jump = findHost(hosts, jumpHost);
                if (jump == null) return R.string.terminow_host_jump_missing;
                if (jump.telnet) return R.string.terminow_host_jump_telnet;
            }
            if (!jumpHost.isEmpty() && !proxyType.isEmpty()) return R.string.terminow_host_jump_and_proxy;
            if (mosh && (!jumpHost.isEmpty() || !proxyType.isEmpty())) return R.string.terminow_host_mosh_route;
            if (mosh && !env.isEmpty()) return R.string.terminow_host_mosh_env;
            if (mosh && !moshServer.trim().isEmpty() && parseMoshServer(moshServer) == null) return R.string.terminow_host_invalid_mosh_server;
            if (telnet && (mosh || agentForwarding || !jumpHost.isEmpty() || !proxyType.isEmpty() || !env.isEmpty() || !keyPath.isEmpty()))
                return R.string.terminow_host_telnet_options;
            return null;
        }
    }

    /** Printed on the server when it has no tmux, for the app to offer installing it. */
    public static final String REMOTE_TMUX_MISSING = "TERMINOW_NO_TMUX";

    /** A tmux session of a tab on a server: "terminow-" and the id of the tab. */
    public static boolean isValidRemoteTmuxName(@Nullable String name) {
        return name != null && name.matches("terminow-[a-z0-9]{8,32}");
    }

    /**
     * The POSIX sh script on the server: attach to the tmux session of the tab if it is there,
     * else create it. Without tmux, it says so and opens the login shell, so the tab still works.
     * It has no single quotes, so that it can be quoted for any login shell.
     */
    @NonNull
    static String remoteTmuxScript(@NonNull String name) {
        return "if command -v tmux >/dev/null 2>&1; then" +
            " if tmux has-session -t =" + name + " 2>/dev/null; then exec tmux attach-session -t =" + name + ";" +
            " else exec tmux new-session -s " + name + "; fi;" +
            " else echo \"TermiNow: tmux is not installed on this server [" + REMOTE_TMUX_MISSING + "]\";" +
            " exec \"${SHELL:-/bin/sh}\" -l; fi";
    }

    /** What ssh runs on the server for the tab, through sh whatever the login shell is. */
    @NonNull
    static String remoteTmuxCommand(@NonNull String name) {
        return "sh -c " + shellQuote(remoteTmuxScript(name));
    }

    /**
     * Install tmux with the package manager of the server, with sudo unless it is root, then
     * open the tmux session of the tab. Typed into the session only when the user asks.
     */
    @NonNull
    public static String installRemoteTmuxCommand(@NonNull String name) {
        String script = "S=; [ \"$(id -u)\" = 0 ] || S=sudo;" +
            " if command -v apt-get >/dev/null 2>&1; then $S apt-get install -y tmux;" +
            " elif command -v dnf >/dev/null 2>&1; then $S dnf install -y tmux;" +
            " elif command -v yum >/dev/null 2>&1; then $S yum install -y tmux;" +
            " elif command -v apk >/dev/null 2>&1; then $S apk add tmux;" +
            " elif command -v pacman >/dev/null 2>&1; then $S pacman -S --noconfirm tmux;" +
            " elif command -v zypper >/dev/null 2>&1; then $S zypper --non-interactive install tmux;" +
            " elif command -v brew >/dev/null 2>&1; then brew install tmux;" +
            " else echo \"TermiNow: no package manager found, install tmux yourself\"; false; fi" +
            " && command -v tmux >/dev/null 2>&1 && exec tmux new-session -A -s " + name;
        return "sh -c " + shellQuote(script);
    }

    /** The host with the {@link Host#getKey()}, {@code null} if none. */
    @Nullable
    public static Host findHost(@NonNull List<Host> hosts, @NonNull String key) {
        for (Host host : hosts) if (host.getKey().equals(key)) return host;
        return null;
    }

    /** A shell word for any text, in single quotes. */
    @NonNull
    static String shellQuote(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** A value of `SetEnv` in double quotes if it has spaces or quotes, as ssh_config parses it. */
    @NonNull
    static String quoteSetEnvValue(@NonNull String value) {
        if (!value.isEmpty() && !value.matches(".*[\\s\"'\\\\].*")) return value;
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static final Pattern TAG_PATTERN = Pattern.compile("[^\\p{Cntrl},]{1,32}");
    private static final Pattern ENV_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    public static boolean isValidTag(@Nullable String tag) {
        return tag != null && !tag.trim().isEmpty() && TAG_PATTERN.matcher(tag).matches();
    }

    public static boolean isValidEnvName(@Nullable String name) {
        return name != null && ENV_NAME_PATTERN.matcher(name).matches();
    }

    public static boolean isValidEnvValue(@Nullable String value) {
        if (value == null || value.length() > 1024) return false;
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return false;
        return true;
    }

    /** A port forwarded through a host with ssh. */
    /**
     * A username with a password and/or a key, that hosts log in with instead of their own, like
     * the identities of Termius. Kept on this device only, its password encrypted like the ones of
     * hosts.
     */
    public static class Identity {
        public String id = "";
        public String label = "";
        public String username = "";
        /** Typed in when the host asks for it, like {@link Host#password}; empty for none. */
        public String password = "";
        /** The key to log in with, like {@link Host#keyPath}; empty for none. */
        public String keyPath = "";
        public long created;

        @NonNull
        public String getTitle() {
            return !label.isEmpty() ? label : !username.isEmpty() ? username : id;
        }

        /** The checks of a host, for the fields that end up in its command. */
        public boolean isValid() {
            return isValidIdentityId(id) && isValidUsername(username) && isValidKeyPath(keyPath) && label.length() <= 200 &&
                !label.contains("\n");
        }
    }

    public static class Forward {
        public static final String TYPE_LOCAL = "local";
        public static final String TYPE_REMOTE = "remote";
        public static final String TYPE_DYNAMIC = "dynamic";

        public String label = "";
        public String type = TYPE_LOCAL;
        /** The title of the host to forward through, see {@link Host#getTitle()}. */
        public String host = "";
        /** The port that is listened on, on this device or on the host for {@link #TYPE_REMOTE}. */
        public int port;
        /** The address that {@link #port} is listened on, empty for the default of ssh. */
        public String bindAddress = "";
        /** The address and port that connections are forwarded to, not used for {@link #TYPE_DYNAMIC}. */
        public String destinationAddress = "";
        public int destinationPort;

        public boolean isDynamic() {
            return TYPE_DYNAMIC.equals(type);
        }

        /** The ssh option for the forwarding, like `-L 8080:localhost:80`. */
        @NonNull
        public String getOption() {
            String listen = (bindAddress.isEmpty() ? "" : bindAddress + ":") + port;
            if (isDynamic())
                return "-D " + listen;
            return (TYPE_REMOTE.equals(type) ? "-R " : "-L ") + listen + ":" + destinationAddress + ":" + destinationPort;
        }

        @NonNull
        public String getTitle() {
            return label.isEmpty() ? getOption() : label;
        }
    }

    /** A notification of the notifications panel. */
    public static class Notification {
        public long time;
        public String title = "";
        public String text = "";
    }

    /** A connection that was started from the home screen. */
    public static class Log {
        public long time;
        public String title = "";
        public String command = "";
    }

    public static class Snippet {
        public String name = "";
        public String command = "";

        @NonNull
        public String getTitle() {
            return name.isEmpty() ? command : name;
        }
    }

    /** The address and username are typed into a shell, so only allow characters that are safe unquoted. */
    private static final Pattern ADDRESS_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:%-]*");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]*");
    private static final Pattern KEY_PATH_PATTERN = Pattern.compile("[A-Za-z0-9._~/][A-Za-z0-9._~/+@-]*");

    /** The alias of the key in the Android keystore that the host passwords are encrypted with. */
    private static final String PASSWORD_KEY_ALIAS = "terminow_host_passwords";
    private static final String PASSWORD_CIPHER = "AES/GCM/NoPadding";
    private static final int PASSWORD_IV_LENGTH = 12;

    private static final String PREFERENCES_NAME = "terminow_home";
    private static final String KEY_HOSTS = "hosts";
    private static final String KEY_SNIPPETS = "snippets";
    private static final String KEY_GROUPS = "groups";
    private static final String KEY_KEY_LABELS = "key_labels";
    private static final String KEY_KEY_PASSPHRASES = "key_passphrases";
    private static final String KEY_BACKUPS = "environment_backups";
    private static final String KEY_IDENTITIES = "identities";
    private static final String KEY_KEYCHAIN_SORT = "keychain_sort";
    private static final String KEY_MCP_PROFILES = "mcp_profiles";
    private static final String KEY_MCP_LOCAL_HTTP = "mcp_local_http";
    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_EXPLORER = "explorer";
    private static final String KEY_LAUNCHER = "launcher";
    private static final String KEY_LAUNCH_PROFILES = "launch_profiles";
    private static final String KEY_DEFAULT_LAUNCH_PROFILE = "default_launch_profile";
    private static final int EXPLORER_RECENTS_MAX = 30;
    private static final String KEY_PREMIUM = "premium";
    private static final String KEY_FORWARDS = "forwards";
    private static final String KEY_ACTIVE_VAULT = "active_vault";
    private static final String KEY_VAULTS = "vaults";
    private static final String KEY_VAULT_ACCOUNT = "vault_account";
    private static final String KEY_KEY_GROUPS = "key_groups";

    /**
     * The vault that the hosts, groups, snippets and forwards are read from and written to on
     * this thread, instead of the active one, while a vault is synced in the background.
     */
    private static final ThreadLocal<String> sVaultScope = new ThreadLocal<>();
    /** Set while the data of a vault is replaced by a sync, which is not a change to sync again. */
    private static final ThreadLocal<Boolean> sImporting = new ThreadLocal<>();
    private static final String KEY_LOGS = "logs";
    private static final String KEY_NOTIFICATIONS = "notifications";
    private static final String KEY_NOTIFICATIONS_READ_TIME = "notifications_read_time";
    private static final String KEY_LAST_VERSION_CODE = "last_version_code";

    /** The number of notifications that are kept. */
    private static final int NOTIFICATIONS_MAX = 50;

    /** The number of connections that are kept in the logs. */
    private static final int LOGS_MAX = 200;
    private static final String KEY_KEEP_ALIVE_INTERVAL = "keep_alive_interval";
    private static final String KEY_KEEP_ALIVE_COUNT_MAX = "keep_alive_count_max";
    private static final String KEY_SCREENSHOTS_BLOCKED = "screenshots_blocked";
    private static final String KEY_SHOW_FOLDER_ON_STARTUP = "show_folder_on_startup";
    private static final String KEY_RECENT_CONNECTIONS = "recent_connections";

    /** The number of recent connections that are kept. */
    private static final int RECENT_CONNECTIONS_MAX = 30;
    private static final String KEY_START_IN_TMUX = "start_in_tmux";
    private static final String KEY_UBUNTU_LAUNCH = "ubuntu_launch_behavior";
    private static final String KEY_UBUNTU_START_TIMEOUT = "ubuntu_start_timeout";

    /** Open Ubuntu: ask each time, in the tab that is open, or in a new tab. */
    public static final String UBUNTU_LAUNCH_ASK = "ask";
    public static final String UBUNTU_LAUNCH_CURRENT = "current";
    public static final String UBUNTU_LAUNCH_NEW = "new";
    /** The seconds that a Linux environment may take to start, that can be chosen. */
    public static final int[] UBUNTU_START_TIMEOUTS = {30, 60, 120, 300};
    private static final String KEY_TERMINAL_THEME = "terminal_theme";
    private static final String KEY_TERMINAL_FONT = "terminal_font";

    private static final String LOG_TAG = "TerminowStore";

    private final SharedPreferences mPreferences;

    public TerminowStore(@NonNull Context context) {
        this(context, PREFERENCES_NAME);
    }

    TerminowStore(@NonNull Context context, @NonNull String preferencesName) {
        this(context.getApplicationContext().getSharedPreferences(preferencesName, Context.MODE_PRIVATE));
    }

    /** For the tests, the store of another phone, without Android. */
    TerminowStore(@NonNull SharedPreferences preferences) {
        mPreferences = preferences;
    }

    /**
     * Only for the unit tests, whose Android has no keystore: secrets are kept with a marker
     * instead of encrypted. Never set by the app.
     */
    static boolean sTestsWithoutKeystore;

    public static boolean isValidAddress(String address) {
        return address != null && ADDRESS_PATTERN.matcher(address).matches();
    }

    public static boolean isValidUsername(String username) {
        return username != null && (username.isEmpty() || USERNAME_PATTERN.matcher(username).matches());
    }

    public static boolean isValidKeyPath(String keyPath) {
        return keyPath != null && (keyPath.isEmpty() || KEY_PATH_PATTERN.matcher(keyPath).matches());
    }

    public static boolean isValidPort(int port) {
        return port >= 1 && port <= 65535;
    }

    @NonNull
    public List<Host> getHosts() {
        List<Host> hosts = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(ns(KEY_HOSTS), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Host host = hostFromJson(object);
                host.password = decryptPassword(object.optString("password"));
                // Never run anything from storage that would not have passed validation when saved
                if (isRunnable(host))
                    hosts.add(host);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load hosts", e);
        }
        return hosts;
    }

    public void setHosts(@NonNull List<Host> hosts) {
        JSONArray array = new JSONArray();
        try {
            for (Host host : hosts) {
                JSONObject object = hostToJson(host);
                object.put("password", encryptPassword(host.password));
                array.put(object);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save hosts", e);
            return;
        }
        putVaultList(KEY_HOSTS, array.toString());
    }

    /** The hosts of a vault, which may not be the one that is shown. "" is the personal vault. */
    @NonNull
    public List<Host> getHostsIn(@NonNull String scope) {
        String previous = sVaultScope.get();
        sVaultScope.set(scope);
        try {
            return getHosts();
        } finally {
            sVaultScope.set(previous);
        }
    }

    public void setHostsIn(@NonNull String scope, @NonNull List<Host> hosts) {
        String previous = sVaultScope.get();
        sVaultScope.set(scope);
        try {
            setHosts(hosts);
        } finally {
            sVaultScope.set(previous);
        }
    }

    /** A host without its password, which is stored encrypted or synced in clear by the caller. */
    @NonNull
    static JSONObject hostToJson(@NonNull Host host) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("label", host.label);
        object.put("address", host.address);
        object.put("port", host.port);
        object.put("username", host.username);
        object.put("mosh", host.mosh);
        if (!host.moshServer.isEmpty()) object.put("moshServer", host.moshServer);
        object.put("keyPath", host.keyPath);
        object.put("group", host.group);
        object.put("startupSnippet", host.startupSnippet);
        object.put("tags", new JSONArray(host.tags));
        object.put("backspaceSendsCtrlH", host.backspaceSendsCtrlH);
        object.put("telnet", host.telnet);
        object.put("telnetPort", host.telnetPort);
        object.put("agentForwarding", host.agentForwarding);
        object.put("remoteTmux", host.remoteTmux);
        object.put("jumpHost", host.jumpHost);
        object.put("proxyType", host.proxyType);
        object.put("proxyHost", host.proxyHost);
        object.put("proxyPort", host.proxyPort);
        JSONObject env = new JSONObject();
        for (Map.Entry<String, String> variable : host.env.entrySet()) env.put(variable.getKey(), variable.getValue());
        object.put("env", env);
        object.put("colorScheme", host.colorScheme);
        object.put("identity", host.identity);
        object.put("protocol", host.protocol);
        object.put("domain", host.domain);
        object.put("rdpNla", host.rdpNla);
        object.put("rdpClipboard", host.rdpClipboard);
        object.put("rdpWidth", host.rdpWidth);
        object.put("rdpHeight", host.rdpHeight);
        object.put("winrmHttps", host.winrmHttps);
        object.put("winrmVerify", host.winrmVerify);
        return object;
    }

    /** A host from {@link #hostToJson}, also from an older version without the newer fields. */
    @NonNull
    static Host hostFromJson(@NonNull JSONObject object) {
        Host host = new Host();
        host.label = object.optString("label");
        host.address = object.optString("address");
        host.port = object.optInt("port", 22);
        host.username = object.optString("username");
        host.mosh = object.optBoolean("mosh");
        host.moshServer = object.optString("moshServer");
        host.keyPath = object.optString("keyPath");
        host.group = object.optString("group");
        host.startupSnippet = object.optString("startupSnippet");
        JSONArray tags = object.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length(); i++) host.tags.add(tags.optString(i));
        host.backspaceSendsCtrlH = object.optBoolean("backspaceSendsCtrlH");
        host.telnet = object.optBoolean("telnet");
        host.telnetPort = object.optInt("telnetPort", 23);
        host.agentForwarding = object.optBoolean("agentForwarding");
        host.remoteTmux = object.optBoolean("remoteTmux");
        host.jumpHost = object.optString("jumpHost");
        host.proxyType = object.optString("proxyType");
        host.proxyHost = object.optString("proxyHost");
        host.proxyPort = object.optInt("proxyPort");
        JSONObject env = object.optJSONObject("env");
        JSONArray names = env != null ? env.names() : null;
        for (int i = 0; names != null && i < names.length(); i++) host.env.put(names.optString(i), env.optString(names.optString(i)));
        host.colorScheme = object.optString("colorScheme");
        host.identity = object.optString("identity");
        host.protocol = object.optString("protocol", Host.PROTOCOL_SSH);
        host.domain = object.optString("domain");
        host.rdpNla = object.optBoolean("rdpNla", true);
        host.rdpClipboard = object.optBoolean("rdpClipboard");
        host.rdpWidth = object.optInt("rdpWidth");
        host.rdpHeight = object.optInt("rdpHeight");
        host.winrmHttps = object.optBoolean("winrmHttps", true);
        host.winrmVerify = object.optBoolean("winrmVerify", true);
        return host;
    }

    /** A Windows domain: a NetBIOS or DNS name, empty for none. */
    public static boolean isValidDomain(@Nullable String domain) {
        return domain != null && domain.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}|");
    }

    /** The desktop sizes that RDP hosts can ask for, 0 x 0 for the size of the screen. */
    public static boolean isValidDesktopSize(int width, int height) {
        return (width == 0 && height == 0) || (width >= 640 && width <= 8192 && height >= 480 && height <= 8192);
    }

    /**
     * Whether a stored or synced host would have passed validation when it was saved. Anything
     * else is never run, since its fields end up in a command.
     */
    static boolean isRunnable(@NonNull Host host) {
        if (!isValidAddress(host.address) || !isValidUsername(host.username) || !isValidPort(host.port) || !isValidKeyPath(host.keyPath))
            return false;
        for (Map.Entry<String, String> variable : host.env.entrySet())
            if (!isValidEnvName(variable.getKey()) || !isValidEnvValue(variable.getValue())) return false;
        if (host.telnet && !isValidPort(host.telnetPort)) return false;
        if (!host.proxyType.isEmpty() && (!(Host.PROXY_SOCKS5.equals(host.proxyType) || Host.PROXY_HTTP.equals(host.proxyType)) ||
            !isValidAddress(host.proxyHost) || !isValidPort(host.proxyPort))) return false;
        if (!host.identity.isEmpty() && !isValidIdentityId(host.identity)) return false;
        if (!Host.PROTOCOL_SSH.equals(host.protocol) && !host.isRdp() && !host.isWinrm()) return false;
        if (!isValidDomain(host.domain) || !isValidDesktopSize(host.rdpWidth, host.rdpHeight)) return false;
        // The jump host is looked up by key among the saved hosts, which are validated themselves
        return host.jumpHost.isEmpty() || host.jumpHost.length() <= 300;
    }

    @NonNull
    private static SecretKey getPasswordKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(PASSWORD_KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry)
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();

        KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        keyGenerator.init(new KeyGenParameterSpec.Builder(PASSWORD_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return keyGenerator.generateKey();
    }

    /** Encrypt a password with a key that never leaves the Android keystore of the device. */
    @NonNull
    private static String encryptPassword(@NonNull String password) {
        if (password.isEmpty()) return "";
        if (sTestsWithoutKeystore) return "test:" + password;
        try {
            Cipher cipher = Cipher.getInstance(PASSWORD_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, getPasswordKey());
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
            byte[] data = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, data, 0, iv.length);
            System.arraycopy(encrypted, 0, data, iv.length, encrypted.length);
            return Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (GeneralSecurityException | IOException e) {
            // Do not fall back to storing the password unencrypted
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to encrypt password, it is not saved", e);
            return "";
        }
    }

    @NonNull
    private static String decryptPassword(@NonNull String encryptedPassword) {
        if (encryptedPassword.isEmpty()) return "";
        if (sTestsWithoutKeystore) return encryptedPassword.startsWith("test:") ? encryptedPassword.substring(5) : "";
        try {
            byte[] data = Base64.decode(encryptedPassword, Base64.NO_WRAP);
            if (data.length <= PASSWORD_IV_LENGTH) return "";
            Cipher cipher = Cipher.getInstance(PASSWORD_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, getPasswordKey(), new GCMParameterSpec(128, data, 0, PASSWORD_IV_LENGTH));
            return new String(cipher.doFinal(data, PASSWORD_IV_LENGTH, data.length - PASSWORD_IV_LENGTH), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to decrypt password", e);
            return "";
        }
    }

    /**
     * The MCP server profiles. Their secrets are decrypted with the keystore key. A profile whose
     * secrets could not be decrypted, for example after a restore to another device, is loaded
     * without them.
     */
    @NonNull
    public List<TerminowMcpProfile> getMcpProfiles() {
        List<TerminowMcpProfile> profiles = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_MCP_PROFILES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                TerminowMcpProfile profile = TerminowMcpProfile.fromJson(object);
                String secrets = decryptPassword(object.optString("secrets"));
                if (!secrets.isEmpty()) {
                    JSONObject secretsObject = new JSONObject(secrets);
                    profile.bearerToken = secretsObject.optString("bearer");
                    JSONArray values = secretsObject.optJSONArray("headers");
                    for (int j = 0; values != null && j < values.length() && j < profile.headers.size(); j++)
                        profile.headers.get(j).value = values.optString(j);
                    JSONArray envValues = secretsObject.optJSONArray("env");
                    List<String> envNames = new ArrayList<>(profile.env.keySet());
                    for (int j = 0; envValues != null && j < envValues.length() && j < envNames.size(); j++)
                        profile.env.put(envNames.get(j), envValues.optString(j));
                }
                profiles.add(profile);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load MCP profiles", e);
        }
        return profiles;
    }

    public void setMcpProfiles(@NonNull List<TerminowMcpProfile> profiles) {
        JSONArray array = new JSONArray();
        try {
            for (TerminowMcpProfile profile : profiles) {
                JSONObject object = profile.toJson();
                if (!profile.getSecrets().isEmpty()) {
                    JSONArray values = new JSONArray();
                    for (TerminowMcpProfile.Header header : profile.headers) values.put(header.value);
                    // In the order of envNames in the profile
                    JSONArray envValues = new JSONArray(new ArrayList<>(profile.env.values()));
                    object.put("secrets", encryptPassword(new JSONObject().put("bearer", profile.bearerToken).put("headers", values)
                        .put("env", envValues).toString()));
                }
                array.put(object);
            }
        } catch (JSONException e) {
            // Never log the profile, it has secrets
            Logger.logError(LOG_TAG, "Failed to save MCP profiles");
            return;
        }
        mPreferences.edit().putString(KEY_MCP_PROFILES, array.toString()).apply();
    }

    /** The signed in account, {@code null} if none. The refresh token is encrypted. */
    @Nullable
    public TerminowAccount.Session getAccount() {
        try {
            String text = mPreferences.getString(KEY_ACCOUNT, "");
            if (text.isEmpty()) return null;
            JSONObject object = new JSONObject(text);
            TerminowAccount.Session session = new TerminowAccount.Session();
            session.uid = object.optString("uid");
            session.email = object.optString("email");
            session.emailVerified = object.optBoolean("emailVerified");
            session.displayName = object.optString("displayName");
            session.photoUrl = object.optString("photoUrl");
            session.refreshToken = decryptPassword(object.optString("refreshToken"));
            // A token that could not be decrypted, like after a restore, needs a new sign in
            return session.refreshToken.isEmpty() ? null : session;
        } catch (JSONException e) {
            return null;
        }
    }

    public void setAccount(@Nullable TerminowAccount.Session session) {
        if (session == null) {
            mPreferences.edit().remove(KEY_ACCOUNT).remove(KEY_PREMIUM).remove(KEY_PLAY_ENTITLEMENT).remove(KEY_PLAY_PURCHASE_STATUS)
                .remove(KEY_ACCOUNT_DEVICE).apply();
            return;
        }
        // Premium that was checked for another account is not this one's
        TerminowAccount.Session previous = getAccount();
        if (previous != null && !previous.uid.equals(session.uid))
            mPreferences.edit().remove(KEY_PREMIUM).remove(KEY_PLAY_ENTITLEMENT).remove(KEY_PLAY_PURCHASE_STATUS).apply();
        // Each sign in is a new device of the account, so a device signed out from another one stays signed out
        if (mPreferences.getString(KEY_ACCOUNT_DEVICE, "").isEmpty())
            mPreferences.edit().putString(KEY_ACCOUNT_DEVICE, newDeviceId()).apply();
        try {
            JSONObject object = new JSONObject()
                .put("uid", session.uid)
                .put("email", session.email)
                .put("emailVerified", session.emailVerified)
                .put("displayName", session.displayName)
                .put("photoUrl", session.photoUrl)
                .put("refreshToken", encryptPassword(session.refreshToken));
            mPreferences.edit().putString(KEY_ACCOUNT, object.toString()).apply();
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to save the account");
        }
    }

    private static final String KEY_ACCOUNT_DEVICE = "account_device_id";
    private static final String KEY_INSTALL_ID = "install_id";

    /** The id of this sign in in the devices of the account, empty when signed out. */
    @NonNull
    public String getAccountDeviceId() {
        return mPreferences.getString(KEY_ACCOUNT_DEVICE, "");
    }

    /** The id of the app on this device, the same for every sign in. */
    @NonNull
    public String getInstallId() {
        String id = mPreferences.getString(KEY_INSTALL_ID, "");
        if (id.isEmpty()) {
            id = newDeviceId();
            mPreferences.edit().putString(KEY_INSTALL_ID, id).apply();
        }
        return id;
    }

    @NonNull
    private static String newDeviceId() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder id = new StringBuilder();
        for (byte b : bytes) id.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return id.toString();
    }

    /** The last known Premium status, {@code null} if never checked. */
    @Nullable
    public JSONObject getPremium() {
        try {
            String text = mPreferences.getString(KEY_PREMIUM, "");
            return text.isEmpty() ? null : new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    private static final String KEY_PLAY_ENTITLEMENT = "play_entitlement";
    private static final String KEY_PLAY_PURCHASE_STATUS = "play_purchase_status";
    private static final String KEY_PLAY_VERIFIED = "play_verified";

    /**
     * The Google Play entitlement that the server last gave, with "checked" the time it was
     * read. Only a copy for offline use, see {@link TerminowEntitlement}: Firebase decides it.
     */
    @Nullable
    public JSONObject getPlayEntitlement() {
        return readJson(KEY_PLAY_ENTITLEMENT);
    }

    public void setPlayEntitlement(@Nullable JSONObject entitlement) {
        try {
            // Kept with the account it was read for, see TerminowEntitlement.decidePlay
            TerminowAccount.Session session = getAccount();
            mPreferences.edit().putString(KEY_PLAY_ENTITLEMENT, entitlement == null ? "" :
                new JSONObject(entitlement.toString()).put("checked", System.currentTimeMillis())
                    .put("uid", session != null ? session.uid : JSONObject.NULL).toString()).apply();
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to save the Play entitlement");
        }
    }

    /** What happened to the last Google Play purchase, see {@link TerminowPlayPurchases.Status}. */
    @Nullable
    public JSONObject getPlayPurchaseStatus() {
        return readJson(KEY_PLAY_PURCHASE_STATUS);
    }

    public void setPlayPurchaseStatus(@NonNull String status, @Nullable String code, boolean test) {
        try {
            mPreferences.edit().putString(KEY_PLAY_PURCHASE_STATUS, new JSONObject().put("status", status)
                .put("code", code == null ? JSONObject.NULL : code).put("test", test).put("time", System.currentTimeMillis()).toString()).apply();
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to save the purchase status");
        }
    }

    /** When the server last verified a purchase, by the hash of its token, 0 if never. */
    public long getPlayVerifiedTime(@NonNull String tokenHash) {
        JSONObject verified = readJson(KEY_PLAY_VERIFIED);
        return verified != null ? verified.optLong(tokenHash) : 0;
    }

    public void setPlayVerifiedTime(@NonNull String tokenHash, long time) {
        try {
            JSONObject verified = readJson(KEY_PLAY_VERIFIED);
            if (verified == null) verified = new JSONObject();
            verified.put(tokenHash, time);
            mPreferences.edit().putString(KEY_PLAY_VERIFIED, verified.toString()).apply();
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to save the verification time");
        }
    }

    @Nullable
    private JSONObject readJson(@NonNull String key) {
        try {
            String text = mPreferences.getString(key, "");
            return text.isEmpty() ? null : new JSONObject(text);
        } catch (JSONException e) {
            return null;
        }
    }

    public void setPremium(@NonNull TerminowAccount.Premium premium) {
        try {
            JSONObject object = new JSONObject()
                .put("premium", premium.premium)
                .put("plan", premium.plan)
                .put("nextDueDate", premium.nextDueDate == null ? JSONObject.NULL : premium.nextDueDate)
                .put("source", premium.source)
                .put("checked", System.currentTimeMillis());
            mPreferences.edit().putString(KEY_PREMIUM, object.toString()).apply();
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to save the Premium status");
        }
    }

    /**
     * The state of the File Explorer: the device folders that the user picked ("folders": uri,
     * name), "bookmarks" and "recents" (fs, fsTitle, id, name, path, time), and view settings.
     * Only names, URIs and paths, never file contents.
     */
    @NonNull
    public JSONObject getExplorer() {
        try {
            return new JSONObject(mPreferences.getString(KEY_EXPLORER, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public void setExplorer(@NonNull JSONObject explorer) {
        mPreferences.edit().putString(KEY_EXPLORER, explorer.toString()).apply();
    }

    @NonNull
    public List<TerminowLaunchProfile> getLaunchProfiles() {
        return TerminowLaunchProfile.listFromJson(mPreferences.getString(KEY_LAUNCH_PROFILES, "[]"));
    }

    public void setLaunchProfiles(@NonNull List<TerminowLaunchProfile> profiles) {
        mPreferences.edit().putString(KEY_LAUNCH_PROFILES, TerminowLaunchProfile.listToJson(profiles)).apply();
    }

    /**
     * What a new Local Terminal opens: "local" for the TermiNow shell, "ubuntu:<container>" for a
     * container, or the id of a saved launch profile.
     */
    @NonNull
    public String getDefaultLaunchProfile() {
        return mPreferences.getString(KEY_DEFAULT_LAUNCH_PROFILE, "local");
    }

    public void setDefaultLaunchProfile(@NonNull String value) {
        mPreferences.edit().putString(KEY_DEFAULT_LAUNCH_PROFILE, value).apply();
    }

    private static final String KEY_QUICK_PINS = "quick_pins";
    private static final String KEY_QUICK_NAMES = "quick_names";

    /**
     * The cards pinned to Quick Launch, by id, in their order, see {@link TerminowQuickModel}.
     * Saved the first time from the launch profiles there were, which were all in the + menu.
     */
    @NonNull
    public List<String> getQuickPins() {
        List<String> stored = null;
        String text = mPreferences.getString(KEY_QUICK_PINS, null);
        if (text != null) {
            stored = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(text);
                for (int i = 0; i < array.length(); i++) {
                    String id = array.optString(i);
                    if (!id.isEmpty() && id.length() <= 120 && !stored.contains(id)) stored.add(id);
                }
            } catch (JSONException e) {
                stored = new ArrayList<>();
            }
        }
        if (stored != null) return stored;
        List<String> profileIds = new ArrayList<>();
        for (TerminowLaunchProfile profile : getLaunchProfiles()) profileIds.add(TerminowQuickModel.profileId(profile.id));
        List<String> migrated = TerminowQuickModel.migratePins(null, profileIds);
        setQuickPins(migrated);
        return migrated;
    }

    public void setQuickPins(@NonNull List<String> pins) {
        mPreferences.edit().putString(KEY_QUICK_PINS, new JSONArray(pins).toString()).apply();
    }

    /** The name that the user gave a card, empty for its own name. */
    @NonNull
    public String getQuickName(@NonNull String id) {
        try {
            return new JSONObject(mPreferences.getString(KEY_QUICK_NAMES, "{}")).optString(id);
        } catch (JSONException e) {
            return "";
        }
    }

    public void setQuickName(@NonNull String id, @NonNull String name) {
        try {
            JSONObject names = new JSONObject(mPreferences.getString(KEY_QUICK_NAMES, "{}"));
            String clean = name.replaceAll("\\p{Cntrl}", " ").trim();
            if (clean.length() > 60) clean = clean.substring(0, 60);
            if (clean.isEmpty()) names.remove(id);
            else names.put(id, clean);
            mPreferences.edit().putString(KEY_QUICK_NAMES, names.toString()).apply();
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to save a name of Quick Launch");
        }
    }

    /** The choices of the terminal launcher: environment, and user and folder per environment. */
    @NonNull
    public JSONObject getLauncher() {
        try {
            return new JSONObject(mPreferences.getString(KEY_LAUNCHER, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public void setLauncher(@NonNull JSONObject launcher) {
        mPreferences.edit().putString(KEY_LAUNCHER, launcher.toString()).apply();
    }

    /** Add a recent file, newest first, without duplicates. */
    public void addExplorerRecent(@NonNull JSONObject recent) {
        JSONObject explorer = getExplorer();
        JSONArray recents = explorer.optJSONArray("recents");
        JSONArray updated = new JSONArray();
        updated.put(recent);
        for (int i = 0; recents != null && i < recents.length() && updated.length() < EXPLORER_RECENTS_MAX; i++) {
            JSONObject item = recents.optJSONObject(i);
            if (item == null) continue;
            if (item.optString("fs").equals(recent.optString("fs")) && item.optString("id").equals(recent.optString("id"))) continue;
            updated.put(item);
        }
        try {
            explorer.put("recents", updated);
        } catch (JSONException e) {
            return;
        }
        setExplorer(explorer);
    }

    /** Whether MCP servers on this device may be used over plain HTTP, for local development. */
    public boolean isMcpLocalHttpAllowed() {
        return mPreferences.getBoolean(KEY_MCP_LOCAL_HTTP, false);
    }

    public void setMcpLocalHttpAllowed(boolean allowed) {
        mPreferences.edit().putBoolean(KEY_MCP_LOCAL_HTTP, allowed).apply();
    }

    /** The seconds after which ssh checks that a host that sent nothing is still connected, 0 to not check. */
    public int getKeepAliveInterval() {
        return Math.max(0, mPreferences.getInt(KEY_KEEP_ALIVE_INTERVAL, 60));
    }

    public void setKeepAliveInterval(int seconds) {
        mPreferences.edit().putInt(KEY_KEEP_ALIVE_INTERVAL, seconds).apply();
    }

    /** The number of unanswered checks after which ssh disconnects from a host. */
    public int getKeepAliveCountMax() {
        return Math.max(1, mPreferences.getInt(KEY_KEEP_ALIVE_COUNT_MAX, 10));
    }

    public void setKeepAliveCountMax(int count) {
        mPreferences.edit().putInt(KEY_KEEP_ALIVE_COUNT_MAX, count).apply();
    }

    /** Whether local terminals are started in the home folder instead of the root folder. */
    /** Whether a fresh local shell shows its folder and the files in it, with pwd and ls. */
    public boolean shouldShowFolderOnStartup() {
        return mPreferences.getBoolean(KEY_SHOW_FOLDER_ON_STARTUP, false);
    }

    public void setShowFolderOnStartup(boolean showFolderOnStartup) {
        mPreferences.edit().putBoolean(KEY_SHOW_FOLDER_ON_STARTUP, showFolderOnStartup).apply();
    }

    /** A connection to a host that was made, for the recent connections of the connection picker. */
    public static class RecentConnection {
        /** The {@link Host#getKey()} of the host. */
        public String hostKey = "";
        public boolean sftp;
        public long time;
    }

    /** Get the recent connections, the newest first, at most one for each host and connection mode. */
    @NonNull
    public List<RecentConnection> getRecentConnections() {
        List<RecentConnection> recents = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_RECENT_CONNECTIONS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                RecentConnection recent = new RecentConnection();
                recent.hostKey = object.optString("host");
                recent.sftp = object.optBoolean("sftp");
                recent.time = object.optLong("time");
                if (!recent.hostKey.isEmpty())
                    recents.add(recent);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load recent connections", e);
        }
        return recents;
    }

    /** Add a connection to a host to the recent connections, replacing the one of the same host and mode. */
    public void addRecentConnection(@NonNull Host host, boolean sftp) {
        String hostKey = host.getKey();
        try {
            JSONArray array = new JSONArray();
            JSONObject object = new JSONObject();
            object.put("host", hostKey);
            object.put("sftp", sftp);
            object.put("time", System.currentTimeMillis());
            array.put(object);
            for (RecentConnection recent : getRecentConnections()) {
                if (array.length() >= RECENT_CONNECTIONS_MAX) break;
                if (recent.hostKey.equals(hostKey) && recent.sftp == sftp) continue;
                JSONObject old = new JSONObject();
                old.put("host", recent.hostKey);
                old.put("sftp", recent.sftp);
                old.put("time", recent.time);
                array.put(old);
            }
            mPreferences.edit().putString(KEY_RECENT_CONNECTIONS, array.toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save recent connection", e);
        }
    }

    /** The name of the theme whose colors were set for the terminal, empty for the default colors. */
    @NonNull
    public String getTerminalTheme() {
        return mPreferences.getString(KEY_TERMINAL_THEME, "");
    }

    public void setTerminalTheme(@NonNull String name) {
        mPreferences.edit().putString(KEY_TERMINAL_THEME, name).apply();
    }

    /** The name of the font that was set for the terminal, empty for the default font. */
    @NonNull
    public String getTerminalFont() {
        return mPreferences.getString(KEY_TERMINAL_FONT, "");
    }

    public void setTerminalFont(@NonNull String name) {
        mPreferences.edit().putString(KEY_TERMINAL_FONT, name).apply();
    }

    /** Whether local terminals are started in a tmux session. */
    @NonNull
    public String getUbuntuLaunchBehavior() {
        String value = mPreferences.getString(KEY_UBUNTU_LAUNCH, UBUNTU_LAUNCH_ASK);
        return UBUNTU_LAUNCH_CURRENT.equals(value) || UBUNTU_LAUNCH_NEW.equals(value) ? value : UBUNTU_LAUNCH_ASK;
    }

    public void setUbuntuLaunchBehavior(@NonNull String behavior) {
        mPreferences.edit().putString(KEY_UBUNTU_LAUNCH, behavior).apply();
    }

    /** How long a Linux environment may take to start in a tab before it is stopped as failed. */
    public int getUbuntuStartTimeoutSeconds() {
        int value = mPreferences.getInt(KEY_UBUNTU_START_TIMEOUT, 60);
        for (int allowed : UBUNTU_START_TIMEOUTS) if (allowed == value) return value;
        return 60;
    }

    public void setUbuntuStartTimeoutSeconds(int seconds) {
        mPreferences.edit().putInt(KEY_UBUNTU_START_TIMEOUT, seconds).apply();
    }

    public boolean shouldStartInTmux() {
        return mPreferences.getBoolean(KEY_START_IN_TMUX, true);
    }

    public void setStartInTmux(boolean startInTmux) {
        mPreferences.edit().putBoolean(KEY_START_IN_TMUX, startInTmux).apply();
    }

    public boolean areScreenshotsBlocked() {
        return mPreferences.getBoolean(KEY_SCREENSHOTS_BLOCKED, false);
    }

    public void setScreenshotsBlocked(boolean blocked) {
        mPreferences.edit().putBoolean(KEY_SCREENSHOTS_BLOCKED, blocked).apply();
    }

    public static boolean isValidForward(@NonNull Forward forward) {
        if (!isValidPort(forward.port)) return false;
        if (!forward.bindAddress.isEmpty() && !isValidAddress(forward.bindAddress)) return false;
        if (forward.isDynamic()) return true;
        return (Forward.TYPE_LOCAL.equals(forward.type) || Forward.TYPE_REMOTE.equals(forward.type)) &&
            isValidAddress(forward.destinationAddress) && isValidPort(forward.destinationPort);
    }

    @NonNull
    public List<Forward> getForwards() {
        List<Forward> forwards = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(ns(KEY_FORWARDS), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Forward forward = new Forward();
                forward.label = object.optString("label");
                forward.type = object.optString("type", Forward.TYPE_LOCAL);
                forward.host = object.optString("host");
                forward.port = object.optInt("port");
                forward.bindAddress = object.optString("bindAddress");
                forward.destinationAddress = object.optString("destinationAddress");
                forward.destinationPort = object.optInt("destinationPort");
                // Never run anything from storage that would not have passed validation when saved
                if (isValidForward(forward))
                    forwards.add(forward);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load forwards", e);
        }
        return forwards;
    }

    public void setForwards(@NonNull List<Forward> forwards) {
        JSONArray array = new JSONArray();
        try {
            for (Forward forward : forwards) {
                JSONObject object = new JSONObject();
                object.put("label", forward.label);
                object.put("type", forward.type);
                object.put("host", forward.host);
                object.put("port", forward.port);
                object.put("bindAddress", forward.bindAddress);
                object.put("destinationAddress", forward.destinationAddress);
                object.put("destinationPort", forward.destinationPort);
                array.put(object);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save forwards", e);
            return;
        }
        putVaultList(KEY_FORWARDS, array.toString());
    }

    /** Get the logs of the connections that were started, the newest first. */
    @NonNull
    public List<Log> getLogs() {
        List<Log> logs = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_LOGS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Log log = new Log();
                log.time = object.optLong("time");
                log.title = object.optString("title");
                log.command = object.optString("command");
                logs.add(log);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load logs", e);
        }
        return logs;
    }

    /** Add a connection to the logs. The command must not contain a password. */
    public void addLog(@NonNull String title, @NonNull String command) {
        try {
            JSONArray oldArray = new JSONArray(mPreferences.getString(KEY_LOGS, "[]"));
            JSONArray array = new JSONArray();
            JSONObject object = new JSONObject();
            object.put("time", System.currentTimeMillis());
            object.put("title", title);
            object.put("command", command);
            array.put(object);
            for (int i = 0; i < oldArray.length() && array.length() < LOGS_MAX; i++)
                array.put(oldArray.get(i));
            mPreferences.edit().putString(KEY_LOGS, array.toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save log", e);
        }
    }

    /** Get the notifications, the newest first. */
    @NonNull
    public List<Notification> getNotifications() {
        List<Notification> notifications = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_NOTIFICATIONS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Notification notification = new Notification();
                notification.time = object.optLong("time");
                notification.title = object.optString("title");
                notification.text = object.optString("text");
                notifications.add(notification);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load notifications", e);
        }
        return notifications;
    }

    public void addNotification(@NonNull String title, @NonNull String text) {
        try {
            JSONArray oldArray = new JSONArray(mPreferences.getString(KEY_NOTIFICATIONS, "[]"));
            JSONArray array = new JSONArray();
            JSONObject object = new JSONObject();
            object.put("time", System.currentTimeMillis());
            object.put("title", title);
            object.put("text", text);
            array.put(object);
            for (int i = 0; i < oldArray.length() && array.length() < NOTIFICATIONS_MAX; i++)
                array.put(oldArray.get(i));
            mPreferences.edit().putString(KEY_NOTIFICATIONS, array.toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save notification", e);
        }
    }

    /** Whether there are notifications newer than when the notifications panel was last opened. */
    public boolean hasUnreadNotifications() {
        List<Notification> notifications = getNotifications();
        return !notifications.isEmpty() && notifications.get(0).time > mPreferences.getLong(KEY_NOTIFICATIONS_READ_TIME, 0);
    }

    public void markNotificationsRead() {
        mPreferences.edit().putLong(KEY_NOTIFICATIONS_READ_TIME, System.currentTimeMillis()).apply();
    }

    /**
     * Save the version code of the app and return the one that was saved before, 0 if none was,
     * like on the first start after the app was installed.
     */
    public int updateLastVersionCode(int versionCode) {
        int lastVersionCode = mPreferences.getInt(KEY_LAST_VERSION_CODE, 0);
        if (lastVersionCode != versionCode)
            mPreferences.edit().putInt(KEY_LAST_VERSION_CODE, versionCode).apply();
        return lastVersionCode;
    }

    public void clearLogs() {
        mPreferences.edit().remove(KEY_LOGS).apply();
    }

    /** Get the names of the groups that hosts can be put in. */
    @NonNull
    public List<String> getGroups() {
        List<String> groups = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(ns(KEY_GROUPS), "[]"));
            for (int i = 0; i < array.length(); i++) {
                String group = array.optString(i).trim();
                if (!group.isEmpty() && !groups.contains(group))
                    groups.add(group);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load groups", e);
        }
        return groups;
    }

    public void setGroups(@NonNull List<String> groups) {
        putVaultList(KEY_GROUPS, new JSONArray(groups).toString());
    }

    /**
     * Read the hosts of an ssh config file, like `~/.ssh/config`. The `Host` entries with a
     * pattern and the options of hosts other than `HostName`, `User`, `Port` and `IdentityFile`
     * are skipped.
     */
    @NonNull
    public static List<Host> readSshConfigHosts(@NonNull File configFile) throws IOException {
        try (java.io.Reader in = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            return readSshConfigHosts(in);
        }
    }

    /**
     * Read the hosts of an ssh config, from any source like a file picked on the phone. Like
     * {@link #readSshConfigHosts(File)}, and a `ProxyJump` to another host of the same config
     * makes that host the jump host.
     */
    @NonNull
    public static List<Host> readSshConfigHosts(@NonNull java.io.Reader in) throws IOException {
        return readSshConfigHosts(in, null);
    }

    /**
     * Like {@link #readSshConfigHosts(java.io.Reader)}, also giving the `IdentityFile` of each
     * host as it is written, like a path of another computer that is not used here.
     */
    @NonNull
    public static List<Host> readSshConfigHosts(@NonNull java.io.Reader in, @Nullable Map<Host, String> identityFiles) throws IOException {
        List<Host> hosts = new ArrayList<>();
        List<Host> currentHosts = new ArrayList<>();
        // The ProxyJump of each host, resolved against the aliases once all are read
        Map<Host, String> jumps = new java.util.IdentityHashMap<>();
        try (BufferedReader reader = new BufferedReader(in)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                // The keyword and its arguments are separated by whitespace or an equals sign
                String[] parts = line.split("\\s*=\\s*|\\s+", 2);
                if (parts.length < 2) continue;
                String keyword = parts[0].toLowerCase(Locale.ROOT);
                String value = parts[1].trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                    value = value.substring(1, value.length() - 1);

                if (keyword.equals("host")) {
                    currentHosts = new ArrayList<>();
                    for (String alias : value.split("\\s+")) {
                        // A pattern does not name a host to connect to
                        if (alias.isEmpty() || alias.contains("*") || alias.contains("?") || alias.startsWith("!")) continue;
                        Host host = new Host();
                        host.label = alias;
                        host.address = alias;
                        currentHosts.add(host);
                        hosts.add(host);
                    }
                } else if (keyword.equals("match")) {
                    currentHosts = new ArrayList<>();
                } else {
                    for (Host host : currentHosts) {
                        // The first value of an option is the one that ssh uses
                        if (keyword.equals("hostname") && host.address.equals(host.label)) {
                            host.address = value;
                        } else if (keyword.equals("user") && host.username.isEmpty()) {
                            host.username = value;
                        } else if (keyword.equals("port") && host.port == 22) {
                            try {
                                host.port = Integer.parseInt(value);
                            } catch (NumberFormatException e) {
                                host.port = -1;
                            }
                        } else if (keyword.equals("identityfile") && host.keyPath.isEmpty()) {
                            host.keyPath = value;
                        } else if (keyword.equals("proxyjump") && !jumps.containsKey(host)) {
                            // The first hop, as user@host:port or an alias of this config
                            jumps.put(host, value.split(",")[0].trim());
                        }
                    }
                }
            }
        }

        // Only return the hosts that could also have been saved from the host form
        List<Host> validHosts = new ArrayList<>();
        for (Host host : hosts) {
            if (identityFiles != null && !host.keyPath.isEmpty()) identityFiles.put(host, host.keyPath);
            if (!isValidKeyPath(host.keyPath))
                host.keyPath = "";
            if (isValidAddress(host.address) && isValidUsername(host.username) && isValidPort(host.port))
                validHosts.add(host);
        }
        for (Map.Entry<Host, String> jump : jumps.entrySet()) {
            String alias = jump.getValue();
            if (alias.equalsIgnoreCase("none")) continue;
            for (Host other : validHosts) {
                if (other != jump.getKey() && (other.label.equals(alias) || alias.equals(other.username + "@" + other.address) ||
                    alias.equals(other.address))) {
                    jump.getKey().jumpHost = other.getKey();
                    break;
                }
            }
        }
        return validHosts;
    }

    @NonNull
    public List<Snippet> getSnippets() {
        List<Snippet> snippets = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(ns(KEY_SNIPPETS), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Snippet snippet = new Snippet();
                snippet.name = object.optString("name");
                snippet.command = object.optString("command");
                if (!snippet.command.isEmpty())
                    snippets.add(snippet);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load snippets", e);
        }
        return snippets;
    }

    /** The snippet with the name, {@code null} if there is none. */
    @Nullable
    public Snippet getSnippet(@NonNull String name) {
        if (name.isEmpty()) return null;
        for (Snippet snippet : getSnippets())
            if (snippet.name.equals(name)) return snippet;
        return null;
    }

    public static boolean isValidIdentityId(@Nullable String id) {
        return id != null && id.matches("[a-z0-9]{1,40}");
    }

    @NonNull
    public List<Identity> getIdentities() {
        List<Identity> identities = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(mPreferences.getString(KEY_IDENTITIES, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                Identity identity = new Identity();
                identity.id = object.optString("id");
                identity.label = object.optString("label");
                identity.username = object.optString("username");
                identity.password = decryptPassword(object.optString("password"));
                identity.keyPath = object.optString("keyPath");
                identity.created = object.optLong("created");
                // Never use anything from storage that would not have passed validation when saved
                if (identity.isValid()) identities.add(identity);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load identities", e);
        }
        return identities;
    }

    public void setIdentities(@NonNull List<Identity> identities) {
        JSONArray array = new JSONArray();
        try {
            for (Identity identity : identities) {
                JSONObject object = new JSONObject();
                object.put("id", identity.id);
                object.put("label", identity.label);
                object.put("username", identity.username);
                object.put("password", encryptPassword(identity.password));
                object.put("keyPath", identity.keyPath);
                object.put("created", identity.created);
                array.put(object);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save identities", e);
            return;
        }
        mPreferences.edit().putString(KEY_IDENTITIES, array.toString()).apply();
        if (!Boolean.TRUE.equals(sImporting.get())) onKeysChanged();
    }

    /** An identity as it is synced, with its password in clear: the vault is encrypted as a whole. */
    @NonNull
    private static JSONObject identityToJson(@NonNull Identity identity) throws JSONException {
        return new JSONObject().put("id", identity.id).put("label", identity.label).put("username", identity.username)
            .put("password", identity.password).put("keyPath", identity.keyPath).put("created", identity.created);
    }

    @NonNull
    private static Identity identityFromJson(@NonNull JSONObject object) {
        Identity identity = new Identity();
        identity.id = object.optString("id");
        identity.label = object.optString("label");
        identity.username = object.optString("username");
        identity.password = object.optString("password");
        identity.keyPath = object.optString("keyPath");
        identity.created = object.optLong("created");
        return identity;
    }

    @Nullable
    public static Identity findIdentity(@NonNull List<Identity> identities, @NonNull String id) {
        for (Identity identity : identities) if (identity.id.equals(id)) return identity;
        return null;
    }

    /**
     * The host as it connects: with the username, password and key of its identity, where the
     * identity has them. A host whose identity was deleted, or is on another device, uses its own.
     */
    @NonNull
    public static Host resolve(@NonNull Host host, @NonNull List<Identity> identities) {
        Identity identity = host.identity.isEmpty() ? null : findIdentity(identities, host.identity);
        if (identity == null) return host;
        Host resolved;
        try {
            resolved = hostFromJson(hostToJson(host));
        } catch (JSONException e) {
            return host;
        }
        resolved.password = host.password;
        if (!identity.username.isEmpty()) resolved.username = identity.username;
        if (!identity.password.isEmpty()) resolved.password = identity.password;
        if (!identity.keyPath.isEmpty()) resolved.keyPath = identity.keyPath;
        return resolved;
    }

    /** How the keychain is sorted: {@code "az"}, {@code "za"}, {@code "new"} or {@code "old"}. */
    @NonNull
    public String getKeychainSort() {
        return mPreferences.getString(KEY_KEYCHAIN_SORT, "az");
    }

    public void setKeychainSort(@NonNull String sort) {
        mPreferences.edit().putString(KEY_KEYCHAIN_SORT, sort).apply();
    }

    /** The label that the user gave a key file of `~/.ssh`, empty if none. */
    @NonNull
    public String getKeyLabel(@NonNull String fileName) {
        try {
            return new JSONObject(mPreferences.getString(KEY_KEY_LABELS, "{}")).optString(fileName);
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * The command of the mosh server of a host, like "mosh-server new -p 60000:60010", as the
     * program and the ports for the options of mosh: mosh itself adds "new" and its other
     * options. {@code null} if it is not one that mosh can run, {"mosh-server", ""} if empty.
     */
    @Nullable
    public static String[] parseMoshServer(@NonNull String command) {
        String[] words = command.trim().split("\\s+");
        if (command.trim().isEmpty()) return new String[]{"mosh-server", ""};
        String program = words[0];
        if (!program.matches("[A-Za-z0-9_./~+-]+")) return null;
        String ports = "";
        for (int i = 1; i < words.length; i++) {
            String word = words[i];
            if (word.equals("new") && i == 1) continue;
            String value;
            if ((word.equals("-p") || word.equals("--port")) && i + 1 < words.length) value = words[++i];
            else if (word.startsWith("--port=")) value = word.substring("--port=".length());
            else if (word.startsWith("-p") && word.length() > 2) value = word.substring(2);
            else return null;
            if (!isValidMoshPorts(value)) return null;
            ports = value;
        }
        return new String[]{program, ports};
    }

    /** A UDP port, or a range of them like "60000:60010". */
    static boolean isValidMoshPorts(@NonNull String value) {
        String[] range = value.split(":", -1);
        if (range.length > 2) return false;
        try {
            int low = Integer.parseInt(range[0]);
            int high = range.length == 2 ? Integer.parseInt(range[1]) : low;
            return low >= 1 && high <= 65535 && low <= high;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** How a Linux environment was last backed up to a server, as JSON, empty if never. */
    @NonNull
    public String getBackupSettings(@NonNull String alias) {
        try {
            return new JSONObject(mPreferences.getString(KEY_BACKUPS, "{}")).optString(alias);
        } catch (JSONException e) {
            return "";
        }
    }

    public void setBackupSettings(@NonNull String alias, @NonNull String settings) {
        try {
            JSONObject backups = new JSONObject(mPreferences.getString(KEY_BACKUPS, "{}"));
            backups.put(alias, settings);
            mPreferences.edit().putString(KEY_BACKUPS, backups.toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save backup settings", e);
        }
    }

    /**
     * The passphrase of a key file of `~/.ssh`, typed in when ssh asks for it, encrypted like
     * the passwords of hosts; empty if none was set. It stays on this phone.
     */
    @NonNull
    public String getKeyPassphrase(@NonNull String fileName) {
        try {
            String encrypted = new JSONObject(mPreferences.getString(KEY_KEY_PASSPHRASES, "{}")).optString(fileName);
            return encrypted.isEmpty() ? "" : decryptPassword(encrypted);
        } catch (JSONException e) {
            return "";
        }
    }

    public void setKeyPassphrase(@NonNull String fileName, @NonNull String passphrase) {
        try {
            JSONObject passphrases = new JSONObject(mPreferences.getString(KEY_KEY_PASSPHRASES, "{}"));
            if (passphrase.isEmpty()) passphrases.remove(fileName);
            else passphrases.put(fileName, encryptPassword(passphrase));
            mPreferences.edit().putString(KEY_KEY_PASSPHRASES, passphrases.toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save key passphrase", e);
        }
    }

    public void setKeyLabel(@NonNull String fileName, @NonNull String label) {
        try {
            JSONObject labels = new JSONObject(mPreferences.getString(KEY_KEY_LABELS, "{}"));
            if (label.isEmpty()) labels.remove(fileName);
            else labels.put(fileName, label);
            mPreferences.edit().putString(KEY_KEY_LABELS, labels.toString()).apply();
            if (!Boolean.TRUE.equals(sImporting.get())) onKeysChanged();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save key label", e);
        }
    }

    public void setSnippets(@NonNull List<Snippet> snippets) {
        JSONArray array = new JSONArray();
        try {
            for (Snippet snippet : snippets) {
                JSONObject object = new JSONObject();
                object.put("name", snippet.name);
                object.put("command", snippet.command);
                array.put(object);
            }
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save snippets", e);
            return;
        }
        putVaultList(KEY_SNIPPETS, array.toString());
    }


    // ---------------------------------------------------------------------------------------
    // Vaults: the personal one keeps its data where TermiNow always kept it, each team vault
    // has its own copy of the lists, under "v:<id>:".
    // ---------------------------------------------------------------------------------------

    /** The id of the team vault that is shown, "" for the personal vault. */
    @NonNull
    public String getActiveVault() {
        return mPreferences.getString(KEY_ACTIVE_VAULT, "");
    }

    public void setActiveVault(@NonNull String id) {
        mPreferences.edit().putString(KEY_ACTIVE_VAULT, id).apply();
    }

    @NonNull
    private String vaultScope() {
        String scope = sVaultScope.get();
        return scope != null ? scope : getActiveVault();
    }

    @NonNull
    private String ns(@NonNull String key) {
        String scope = vaultScope();
        return scope.isEmpty() ? key : "v:" + scope + ":" + key;
    }

    private void putVaultList(@NonNull String key, @NonNull String value) {
        SharedPreferences.Editor editor = mPreferences.edit().putString(ns(key), value);
        if (!Boolean.TRUE.equals(sImporting.get()))
            editor.putInt("vault_change:" + vaultScope(), mPreferences.getInt("vault_change:" + vaultScope(), 0) + 1);
        editor.apply();
    }

    /** A counter of the changes to the lists of a vault ("" personal) on this phone. */
    public synchronized int getVaultChange(@NonNull String scope) {
        return mPreferences.getInt("vault_change:" + scope, 0);
    }

    /** Whether the lists of a vault changed since they were last synced. */
    public boolean isVaultDirty(@NonNull String scope) {
        return getVaultChange(scope) != mPreferences.getInt("vault_synced:" + scope, 0);
    }

    /**
     * The changes up to the counter were synced. A change made while syncing has a higher
     * counter, so it is still synced the next time.
     */
    public void setVaultSynced(@NonNull String scope, int change) {
        mPreferences.edit().putInt("vault_synced:" + scope, change).apply();
    }

    /** Count everything of a vault as not synced, to merge it into the server the first time. */
    public void setVaultUnsynced(@NonNull String scope) {
        mPreferences.edit().putInt("vault_synced:" + scope, getVaultChange(scope) - 1).apply();
    }

    /** The lists of a vault as {@link TerminowVaultData}, with the passwords of the hosts in clear. */
    @NonNull
    public JSONObject exportVault(@NonNull String scope) throws JSONException {
        String previous = sVaultScope.get();
        sVaultScope.set(scope);
        try {
            JSONObject data = TerminowVaultData.empty();
            JSONArray hosts = data.getJSONArray("hosts");
            for (Host host : getHosts()) {
                hosts.put(hostToJson(host).put("password", host.password));
            }
            data.put("groups", new JSONArray(getGroups()));
            JSONArray snippets = data.getJSONArray("snippets");
            for (Snippet snippet : getSnippets())
                snippets.put(new JSONObject().put("name", snippet.name).put("command", snippet.command));
            JSONArray forwards = data.getJSONArray("forwards");
            for (Forward forward : getForwards()) {
                forwards.put(new JSONObject().put("label", forward.label).put("type", forward.type).put("host", forward.host)
                    .put("port", forward.port).put("bindAddress", forward.bindAddress)
                    .put("destinationAddress", forward.destinationAddress).put("destinationPort", forward.destinationPort));
            }
            if (scope.isEmpty()) {
                data.put("syncKeys", isSyncKeys()).put("syncKeysChanged", mPreferences.getLong(KEY_SYNC_KEYS_CHANGED, 0));
                if (isSyncKeys()) {
                    JSONArray keys = data.getJSONArray("keys");
                    for (SyncedKey key : sKeyFiles.list()) {
                        keys.put(new JSONObject().put("name", key.name).put("label", getKeyLabel(key.name))
                            .put("private", key.privateKey).put("public", key.publicKey).put("certificate", key.certificate));
                    }
                    JSONArray identities = data.getJSONArray("identities");
                    for (Identity identity : getIdentities()) identities.put(identityToJson(identity));
                }
            }
            return data;
        } finally {
            sVaultScope.set(previous);
        }
    }

    /**
     * Replace the lists of a vault with synced data. Each item is checked again when it is read,
     * like everything that is stored, so an invalid host from another phone is never run.
     */
    public void importVault(@NonNull String scope, @NonNull JSONObject data) throws JSONException {
        String previous = sVaultScope.get();
        sVaultScope.set(scope);
        sImporting.set(true);
        try {
            List<Host> hosts = new ArrayList<>();
            JSONArray hostArray = data.optJSONArray("hosts");
            for (int i = 0; hostArray != null && i < hostArray.length(); i++) {
                JSONObject object = hostArray.optJSONObject(i);
                if (object == null) continue;
                Host host = hostFromJson(object);
                host.password = object.optString("password");
                hosts.add(host);
            }
            setHosts(hosts);
            JSONArray groups = data.optJSONArray("groups");
            setGroups(groups == null ? new ArrayList<>() : TerminowVaultData.strings(groups));
            List<Snippet> snippets = new ArrayList<>();
            JSONArray snippetArray = data.optJSONArray("snippets");
            for (int i = 0; snippetArray != null && i < snippetArray.length(); i++) {
                JSONObject object = snippetArray.optJSONObject(i);
                if (object == null) continue;
                Snippet snippet = new Snippet();
                snippet.name = object.optString("name");
                snippet.command = object.optString("command");
                if (!snippet.command.isEmpty()) snippets.add(snippet);
            }
            setSnippets(snippets);
            List<Forward> forwards = new ArrayList<>();
            JSONArray forwardArray = data.optJSONArray("forwards");
            for (int i = 0; forwardArray != null && i < forwardArray.length(); i++) {
                JSONObject object = forwardArray.optJSONObject(i);
                if (object == null) continue;
                Forward forward = new Forward();
                forward.label = object.optString("label");
                forward.type = object.optString("type", Forward.TYPE_LOCAL);
                forward.host = object.optString("host");
                forward.port = object.optInt("port");
                forward.bindAddress = object.optString("bindAddress");
                forward.destinationAddress = object.optString("destinationAddress");
                forward.destinationPort = object.optInt("destinationPort");
                forwards.add(forward);
            }
            setForwards(forwards);
            if (scope.isEmpty()) importKeys(data);
        } finally {
            sImporting.set(false);
            sVaultScope.set(previous);
        }
    }

    /**
     * Take the keys and identities of the Personal vault, and whether they are synced. A key file
     * is only ever added: one of this phone with the same name is kept, and a key that another
     * phone deleted stays here. Identities of this phone that the vault does not have yet are
     * kept, and synced the next time, when another phone just turned syncing on.
     */
    private void importKeys(@NonNull JSONObject data) throws JSONException {
        boolean wasOn = isSyncKeys();
        if (!data.has("syncKeys")) {
            // Saved by a TermiNow that does not sync keys: they are put back the next time
            if (wasOn) markPersonalChanged();
            return;
        }
        boolean on = data.optBoolean("syncKeys");
        long changed = data.optLong("syncKeysChanged");
        if (on != wasOn || changed > mPreferences.getLong(KEY_SYNC_KEYS_CHANGED, 0))
            mPreferences.edit().putBoolean(KEY_SYNC_KEYS, on).putLong(KEY_SYNC_KEYS_CHANGED, changed).apply();
        if (!on) return;
        JSONArray keys = data.optJSONArray("keys");
        for (int i = 0; keys != null && i < keys.length(); i++) {
            JSONObject object = keys.optJSONObject(i);
            if (object == null) continue;
            SyncedKey key = new SyncedKey();
            key.name = object.optString("name");
            key.privateKey = object.optString("private");
            key.publicKey = object.optString("public");
            key.certificate = object.optString("certificate");
            if (!isValidSyncedKey(key)) continue;
            if (sKeyFiles.add(key) && getKeyLabel(key.name).isEmpty() && !object.optString("label").isEmpty())
                setKeyLabel(key.name, object.optString("label"));
        }
        List<Identity> identities = new ArrayList<>();
        JSONArray array = data.optJSONArray("identities");
        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            Identity identity = object != null ? identityFromJson(object) : null;
            if (identity != null && identity.isValid()) identities.add(identity);
        }
        boolean extra = false;
        if (!wasOn) {
            for (Identity local : getIdentities()) {
                if (findIdentity(identities, local.id) == null) {
                    identities.add(local);
                    extra = true;
                }
            }
        }
        setIdentities(identities);
        if (extra) markPersonalChanged();
        // The keys that were just added are not a change of this phone
        mPreferences.edit().putString(KEY_SYNC_KEYS_HASH, keyFilesHash()).apply();
    }

    private static final String KEY_SYNC_KEYS = "sync_keys";
    private static final String KEY_SYNC_KEYS_CHANGED = "sync_keys_changed";

    /** Whether the private keys and identities are synced in the Personal vault, end-to-end encrypted. */
    public boolean isSyncKeys() {
        return mPreferences.getBoolean(KEY_SYNC_KEYS, false);
    }

    /** Turn syncing keys on or off for the account: the Personal vault takes it to the other phones. */
    public void setSyncKeys(boolean on) {
        mPreferences.edit().putBoolean(KEY_SYNC_KEYS, on).putLong(KEY_SYNC_KEYS_CHANGED, System.currentTimeMillis()).apply();
        markPersonalChanged();
    }

    /** Count a change of the Personal vault, like a key that was added, so that it is synced. */
    public synchronized void markPersonalChanged() {
        mPreferences.edit().putInt("vault_change:", mPreferences.getInt("vault_change:", 0) + 1).apply();
    }

    /** A key label or an identity changed: synced the next time if keys are synced. */
    public void onKeysChanged() {
        if (isSyncKeys()) markPersonalChanged();
    }

    private static final String KEY_SYNC_KEYS_HASH = "sync_keys_hash";

    /**
     * Before the Personal vault is synced: count a change if the key files changed since the last
     * time, however they changed, like a key made with ssh-keygen in a terminal.
     */
    public void checkKeyFiles() {
        if (!isSyncKeys()) return;
        String hash = keyFilesHash();
        if (hash.equals(mPreferences.getString(KEY_SYNC_KEYS_HASH, ""))) return;
        mPreferences.edit().putString(KEY_SYNC_KEYS_HASH, hash).apply();
        markPersonalChanged();
    }

    @NonNull
    private static String keyFilesHash() {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (SyncedKey key : sKeyFiles.list()) {
                for (String part : new String[]{key.name, key.privateKey, key.publicKey, key.certificate}) {
                    digest.update(part.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A private key of ~/.ssh as it is synced, with its public key and certificate if there are. */
    public static class SyncedKey {
        public String name = "";
        public String privateKey = "";
        public String publicKey = "";
        public String certificate = "";
    }

    /** The private key files that are synced. Tests use their own. */
    public interface KeyFiles {
        @NonNull List<SyncedKey> list();

        /** Add a key that is not on this phone; {@code false} if a file of that name is there already. */
        boolean add(@NonNull SyncedKey key);
    }

    static final int SYNCED_KEY_MAX_SIZE = 64 * 1024;

    static boolean isValidSyncedKey(@NonNull SyncedKey key) {
        String text = key.privateKey.trim();
        return key.name.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,99}") && !key.name.endsWith(".pub") &&
            !key.name.equals("config") && !key.name.equals("authorized_keys") && !key.name.equals("environment") &&
            !key.name.startsWith("known_hosts") && key.privateKey.length() <= SYNCED_KEY_MAX_SIZE &&
            key.publicKey.length() <= SYNCED_KEY_MAX_SIZE && key.certificate.length() <= SYNCED_KEY_MAX_SIZE &&
            ((text.startsWith("-----BEGIN ") && text.contains("PRIVATE KEY-----")) || text.startsWith("PuTTY-User-Key-File-"));
    }

    /** The key files of ~/.ssh of TermiNow. */
    static KeyFiles sKeyFiles = new KeyFiles() {
        private File dir() {
            return new File(com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH, ".ssh");
        }

        @NonNull
        @Override
        public List<SyncedKey> list() {
            List<SyncedKey> keys = new ArrayList<>();
            File[] files = dir().listFiles();
            if (files == null) return keys;
            for (File file : files) {
                if (!file.isFile() || file.length() > SYNCED_KEY_MAX_SIZE) continue;
                SyncedKey key = new SyncedKey();
                key.name = file.getName();
                key.privateKey = read(file);
                key.publicKey = read(new File(file.getPath() + ".pub"));
                key.certificate = read(new File(file.getPath() + "-cert.pub"));
                // Only private keys: biometric and FIDO2 keys are bound to their device and have none
                if (isValidSyncedKey(key)) keys.add(key);
            }
            return keys;
        }

        @Override
        public boolean add(@NonNull SyncedKey key) {
            File dir = dir();
            File file = new File(dir, key.name);
            if (file.exists()) return false;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) return false;
                android.system.Os.chmod(dir.getPath(), 0700);
                write(file, key.privateKey, 0600);
                if (!key.publicKey.isEmpty() && !new File(file.getPath() + ".pub").exists())
                    write(new File(file.getPath() + ".pub"), key.publicKey, 0644);
                if (!key.certificate.isEmpty() && !new File(file.getPath() + "-cert.pub").exists())
                    write(new File(file.getPath() + "-cert.pub"), key.certificate, 0644);
                return true;
            } catch (IOException | android.system.ErrnoException e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save the synced key " + key.name, e);
                return false;
            }
        }

        private void write(@NonNull File file, @NonNull String content, int mode) throws IOException, android.system.ErrnoException {
            // Created with the mode before anything is written, so a private key is never readable by others
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                android.system.Os.chmod(file.getPath(), mode);
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }

        @NonNull
        private String read(@NonNull File file) {
            if (!file.isFile() || file.length() > SYNCED_KEY_MAX_SIZE) return "";
            try (FileInputStream in = new FileInputStream(file)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
    };

    /** Remove the copy of a team vault from this phone, after leaving it or when it was deleted. */
    public void deleteVaultData(@NonNull String id) {
        if (id.isEmpty()) return;
        SharedPreferences.Editor editor = mPreferences.edit();
        for (String key : new String[]{KEY_HOSTS, KEY_GROUPS, KEY_SNIPPETS, KEY_FORWARDS})
            editor.remove("v:" + id + ":" + key);
        editor.remove("vault_change:" + id).remove("vault_synced:" + id).remove("vault_base:" + id).remove("vault_key:" + id);
        if (getActiveVault().equals(id)) editor.putString(KEY_ACTIVE_VAULT, "");
        editor.apply();
    }

    /** The vaults that the server listed last, see {@link TerminowVaults}. */
    @NonNull
    public JSONArray getVaults() {
        try {
            return new JSONArray(mPreferences.getString(KEY_VAULTS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public void setVaults(@NonNull JSONArray vaults) {
        mPreferences.edit().putString(KEY_VAULTS, vaults.toString()).apply();
    }

    /** The version of a vault on the server that this phone last synced with, 0 for never. */
    public int getVaultBase(@NonNull String id) {
        return mPreferences.getInt("vault_base:" + id, 0);
    }

    public void setVaultBase(@NonNull String id, int version) {
        mPreferences.edit().putInt("vault_base:" + id, version).apply();
    }

    /** The key of a vault, kept encrypted with the Android keystore, {@code null} if not known here. */
    @Nullable
    public byte[] getVaultKey(@NonNull String id) {
        String stored = decryptPassword(mPreferences.getString("vault_key:" + id, ""));
        if (stored.isEmpty()) return null;
        try {
            return TerminowPpk.decodeBase64(stored);
        } catch (TerminowPpk.PpkException e) {
            return null;
        }
    }

    public void setVaultKey(@NonNull String id, @NonNull byte[] key) {
        mPreferences.edit().putString("vault_key:" + id, encryptPassword(TerminowPpk.encodeBase64(key))).apply();
    }

    /**
     * The vault account of this phone: email, public key, and the private key encrypted with
     * the Android keystore, so that the vault password is only asked once on each phone.
     */
    @Nullable
    public JSONObject getVaultAccount() {
        String text = mPreferences.getString(KEY_VAULT_ACCOUNT, "");
        if (text.isEmpty()) return null;
        try {
            JSONObject account = new JSONObject(text);
            String privateKey = decryptPassword(account.optString("privateKey"));
            if (privateKey.isEmpty()) return null;
            account.put("privateKey", privateKey);
            return account;
        } catch (JSONException e) {
            return null;
        }
    }

    public void setVaultAccount(@Nullable String email, @Nullable String publicKey, @Nullable byte[] privateKey) {
        if (email == null || publicKey == null || privateKey == null) {
            // Locked: the vault keys of this phone go too, they are useless without the private key
            SharedPreferences.Editor editor = mPreferences.edit().remove(KEY_VAULT_ACCOUNT);
            for (String key : mPreferences.getAll().keySet())
                if (key.startsWith("vault_key:")) editor.remove(key);
            editor.apply();
            return;
        }
        try {
            mPreferences.edit().putString(KEY_VAULT_ACCOUNT, new JSONObject().put("email", email).put("publicKey", publicKey)
                .put("privateKey", encryptPassword(TerminowPpk.encodeBase64(privateKey))).toString()).apply();
        } catch (JSONException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to save the vault account", e);
        }
    }

    // ---------------------------------------------------------------------------------------
    // The key panel of the terminal
    // ---------------------------------------------------------------------------------------

    /** When the vaults last synced without an error, 0 for never. */
    /** Whether syncing the Personal vault was offered to the account already, so it is asked once. */
    public boolean isPersonalSyncOffered(@NonNull String uid) {
        return mPreferences.getBoolean("personal_sync_offered_" + uid, false);
    }

    public void setPersonalSyncOffered(@NonNull String uid) {
        mPreferences.edit().putBoolean("personal_sync_offered_" + uid, true).apply();
    }

    public long getVaultLastSync() {
        return mPreferences.getLong("vault_last_sync", 0);
    }

    public void setVaultLastSync(long time) {
        mPreferences.edit().putLong("vault_last_sync", time).apply();
    }

    /** The settings of the Linux environments by alias: default user and folder, shared folders. */
    @NonNull
    public JSONObject getLinuxSettings() {
        try {
            return new JSONObject(mPreferences.getString("linux_settings", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public void setLinuxSettings(@NonNull JSONObject settings) {
        mPreferences.edit().putString("linux_settings", settings.toString()).apply();
    }

    /** The alias of an install that was started and not finished, to clean up after the app was killed. */
    @Nullable
    public String getLinuxInstalling() {
        String alias = mPreferences.getString("linux_installing", "");
        return alias.isEmpty() ? null : alias;
    }

    public void setLinuxInstalling(@Nullable String alias) {
        mPreferences.edit().putString("linux_installing", alias == null ? "" : alias).apply();
    }

    /** The tmux sessions that were opened recently, by "environment|user", newest first. */
    @NonNull
    public List<String> getTmuxRecent(@NonNull String key) {
        List<String> names = new ArrayList<>();
        try {
            JSONArray array = new JSONObject(mPreferences.getString("tmux_recent", "{}")).optJSONArray(key);
            for (int i = 0; array != null && i < array.length(); i++) names.add(array.optString(i));
        } catch (JSONException e) {
            // None
        }
        return names;
    }

    public void addTmuxRecent(@NonNull String key, @NonNull String name) {
        try {
            JSONObject all = new JSONObject(mPreferences.getString("tmux_recent", "{}"));
            List<String> names = getTmuxRecent(key);
            names.remove(name);
            names.add(0, name);
            while (names.size() > 8) names.remove(names.size() - 1);
            all.put(key, new JSONArray(names));
            mPreferences.edit().putString("tmux_recent", all.toString()).apply();
        } catch (JSONException e) {
            // Not kept
        }
    }

    /** Whether the key panel is shown beside the terminal on a wide screen, closed until it is opened. */
    public boolean isKeyPanelShown() {
        return mPreferences.getBoolean("key_panel_shown", false);
    }

    public void setKeyPanelShown(boolean shown) {
        mPreferences.edit().putBoolean("key_panel_shown", shown).apply();
    }

    /** The groups of keys of the key panel, {@code null} for the default ones. */
    @Nullable
    public JSONArray getKeyGroups() {
        String text = mPreferences.getString(KEY_KEY_GROUPS, "");
        if (text.isEmpty()) return null;
        try {
            return new JSONArray(text);
        } catch (JSONException e) {
            return null;
        }
    }

    public void setKeyGroups(@Nullable JSONArray groups) {
        if (groups == null) mPreferences.edit().remove(KEY_KEY_GROUPS).apply();
        else mPreferences.edit().putString(KEY_KEY_GROUPS, groups.toString()).apply();
    }

}
