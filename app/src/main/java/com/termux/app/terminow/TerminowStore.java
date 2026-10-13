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
            return (keyPath.isEmpty() ? "" : " -i " + keyPath) + (port == 22 ? "" : " -p " + port) +
                getKeepAliveOptions(keepAliveInterval, keepAliveCountMax);
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
            return "sftp" + (keyPath.isEmpty() ? "" : " -i " + keyPath) + (port == 22 ? "" : " -P " + port) +
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
            if (telnet)
                return "telnet" + (username.isEmpty() ? "" : " -l " + username) + " " + address + " " + telnetPort;
            String target = getTarget();
            String sshOptions = getSshOptions(keepAliveInterval, keepAliveCountMax);
            if (mosh)
                // Mosh talks to the host itself over UDP, so a jump host, proxy or SetEnv does not apply
                return "mosh" + (sshOptions.isEmpty() && !agentForwarding ? "" : " --ssh='ssh" + (agentForwarding ? " -A" : "") + sshOptions + "'") + " " + target;
            return "ssh" + getSessionOptions() + sshOptions + getRouteOptions(jump, proxyConnector, keepAliveInterval, keepAliveCountMax) + " " + target;
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
            if (telnet && (mosh || agentForwarding || !jumpHost.isEmpty() || !proxyType.isEmpty() || !env.isEmpty() || !keyPath.isEmpty()))
                return R.string.terminow_host_telnet_options;
            return null;
        }
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
        object.put("keyPath", host.keyPath);
        object.put("group", host.group);
        object.put("startupSnippet", host.startupSnippet);
        object.put("tags", new JSONArray(host.tags));
        object.put("backspaceSendsCtrlH", host.backspaceSendsCtrlH);
        object.put("telnet", host.telnet);
        object.put("telnetPort", host.telnetPort);
        object.put("agentForwarding", host.agentForwarding);
        object.put("jumpHost", host.jumpHost);
        object.put("proxyType", host.proxyType);
        object.put("proxyHost", host.proxyHost);
        object.put("proxyPort", host.proxyPort);
        JSONObject env = new JSONObject();
        for (Map.Entry<String, String> variable : host.env.entrySet()) env.put(variable.getKey(), variable.getValue());
        object.put("env", env);
        object.put("colorScheme", host.colorScheme);
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
        host.keyPath = object.optString("keyPath");
        host.group = object.optString("group");
        host.startupSnippet = object.optString("startupSnippet");
        JSONArray tags = object.optJSONArray("tags");
        for (int i = 0; tags != null && i < tags.length(); i++) host.tags.add(tags.optString(i));
        host.backspaceSendsCtrlH = object.optBoolean("backspaceSendsCtrlH");
        host.telnet = object.optBoolean("telnet");
        host.telnetPort = object.optInt("telnetPort", 23);
        host.agentForwarding = object.optBoolean("agentForwarding");
        host.jumpHost = object.optString("jumpHost");
        host.proxyType = object.optString("proxyType");
        host.proxyHost = object.optString("proxyHost");
        host.proxyPort = object.optInt("proxyPort");
        JSONObject env = object.optJSONObject("env");
        JSONArray names = env != null ? env.names() : null;
        for (int i = 0; names != null && i < names.length(); i++) host.env.put(names.optString(i), env.optString(names.optString(i)));
        host.colorScheme = object.optString("colorScheme");
        return host;
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
                if (profile.hasSecrets() || !profile.bearerToken.isEmpty()) {
                    JSONArray values = new JSONArray();
                    for (TerminowMcpProfile.Header header : profile.headers) values.put(header.value);
                    object.put("secrets", encryptPassword(new JSONObject().put("bearer", profile.bearerToken).put("headers", values).toString()));
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
            session.refreshToken = decryptPassword(object.optString("refreshToken"));
            // A token that could not be decrypted, like after a restore, needs a new sign in
            return session.refreshToken.isEmpty() ? null : session;
        } catch (JSONException e) {
            return null;
        }
    }

    public void setAccount(@Nullable TerminowAccount.Session session) {
        if (session == null) {
            mPreferences.edit().remove(KEY_ACCOUNT).remove(KEY_PREMIUM).remove(KEY_PLAY_ENTITLEMENT).remove(KEY_PLAY_PURCHASE_STATUS).apply();
            return;
        }
        try {
            JSONObject object = new JSONObject()
                .put("uid", session.uid)
                .put("email", session.email)
                .put("emailVerified", session.emailVerified)
                .put("refreshToken", encryptPassword(session.refreshToken));
            mPreferences.edit().putString(KEY_ACCOUNT, object.toString()).apply();
        } catch (JSONException e) {
            Logger.logError(LOG_TAG, "Failed to save the account");
        }
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
            mPreferences.edit().putString(KEY_PLAY_ENTITLEMENT, entitlement == null ? "" :
                new JSONObject(entitlement.toString()).put("checked", System.currentTimeMillis()).toString()).apply();
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
        List<Host> hosts = new ArrayList<>();
        List<Host> currentHosts = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8))) {
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
                        }
                    }
                }
            }
        }

        // Only return the hosts that could also have been saved from the host form
        List<Host> validHosts = new ArrayList<>();
        for (Host host : hosts) {
            if (!isValidKeyPath(host.keyPath))
                host.keyPath = "";
            if (isValidAddress(host.address) && isValidUsername(host.username) && isValidPort(host.port))
                validHosts.add(host);
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

    /** The label that the user gave a key file of `~/.ssh`, empty if none. */
    @NonNull
    public String getKeyLabel(@NonNull String fileName) {
        try {
            return new JSONObject(mPreferences.getString(KEY_KEY_LABELS, "{}")).optString(fileName);
        } catch (JSONException e) {
            return "";
        }
    }

    public void setKeyLabel(@NonNull String fileName, @NonNull String label) {
        try {
            JSONObject labels = new JSONObject(mPreferences.getString(KEY_KEY_LABELS, "{}"));
            if (label.isEmpty()) labels.remove(fileName);
            else labels.put(fileName, label);
            mPreferences.edit().putString(KEY_KEY_LABELS, labels.toString()).apply();
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
        } finally {
            sImporting.set(false);
            sVaultScope.set(previous);
        }
    }

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
