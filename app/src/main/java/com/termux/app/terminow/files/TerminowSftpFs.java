package com.termux.app.terminow.files;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

/**
 * A saved SFTP host. It connects with the credentials of the host in TermiNow, and checks the
 * key of the host against `~/.ssh/known_hosts` of TermiNow, the same file that `ssh` uses: an
 * unknown host is only added after the user confirmed its fingerprint, and a changed key is
 * refused. Node ids are absolute remote paths.
 *
 * Each open stream has its own SFTP channel on the one connection, so that a copy within the
 * host and the listing never share a channel. Other SSH sessions of the app are not touched.
 */
public class TerminowSftpFs implements TerminowFs {

    /** Questions for the user, asked from the worker thread, which waits for the answer. */
    public interface Prompts {
        boolean confirmHostKey(@NonNull String host, @NonNull String keyType, @NonNull String fingerprint);

        /** {@code null} if the user cancelled. */
        @Nullable String askPassword(@NonNull String prompt);

        /** {@code null} if the user cancelled, then the key is skipped. */
        @Nullable String askPassphrase(@NonNull String keyName);
    }

    public static class HostKeyChangedException extends IOException {
        HostKeyChangedException(String message) {
            super(message);
        }
    }

    private static final String[] DEFAULT_KEYS = {"id_ed25519", "id_ecdsa", "id_rsa"};
    private static final int CONNECT_TIMEOUT_MS = 20000;

    private final File mHome;
    private final String mId;
    private final String mTitle;
    private final String mAddress;
    private final int mPort;
    private final String mUsername;
    private final String mPassword;
    private final String mKeyPath;
    private final Prompts mPrompts;

    private Session mSession;
    private ChannelSftp mChannel;
    private String mRootPath = "/";

    /**
     * @param keyPath Like "~/.ssh/id_ed25519", empty for the default keys.
     */
    public TerminowSftpFs(@NonNull File home, @NonNull String id, @NonNull String title, @NonNull String address, int port,
                          @NonNull String username, @NonNull String password, @NonNull String keyPath, @NonNull Prompts prompts) {
        mHome = home;
        mId = id;
        mTitle = title;
        mAddress = address;
        mPort = port;
        mUsername = username;
        mPassword = password;
        mKeyPath = keyPath;
        mPrompts = prompts;
    }

    @NonNull
    @Override
    public String getId() {
        return mId;
    }

    @NonNull
    @Override
    public String getTitle() {
        return mTitle;
    }

    @NonNull
    @Override
    public Capabilities getCapabilities() {
        return new Capabilities();
    }

    @NonNull
    File getKnownHostsFile() {
        return new File(mHome, ".ssh/known_hosts");
    }

    /** The host as known_hosts names it: "host", or "[host]:port" for another port than 22. */
    @NonNull
    String getKnownHostsName() {
        return mPort == 22 ? mAddress : "[" + mAddress + "]:" + mPort;
    }

    @NonNull
    private File resolveKey(@NonNull String path) {
        if (path.startsWith("~/")) return new File(mHome, path.substring(2));
        return new File(path);
    }

    /** Connect, asking the user what is needed. Runs on a worker thread. */
    public synchronized void connect() throws IOException {
        if (mSession != null && mSession.isConnected() && mChannel != null && mChannel.isConnected()) return;
        try {
            JSch jsch = new JSch();
            File knownHosts = getKnownHostsFile();
            if (knownHosts.isFile()) jsch.setKnownHosts(knownHosts.getPath());

            List<File> keys = new ArrayList<>();
            if (!mKeyPath.isEmpty()) {
                keys.add(resolveKey(mKeyPath));
            } else {
                for (String name : DEFAULT_KEYS) keys.add(new File(mHome, ".ssh/" + name));
            }
            for (File key : keys) addIdentity(jsch, key);

            for (int attempt = 0; ; attempt++) {
                Session session = jsch.getSession(mUsername, mAddress, mPort);
                session.setConfig("StrictHostKeyChecking", "yes");
                session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password");
                preferKnownKeyTypes(jsch, session);
                session.setUserInfo(new Credentials());
                session.setServerAliveInterval(30000);
                session.setTimeout(CONNECT_TIMEOUT_MS);
                try {
                    session.connect(CONNECT_TIMEOUT_MS);
                } catch (JSchException e) {
                    String message = String.valueOf(e.getMessage());
                    HostKey hostKey = session.getHostKey();
                    if (message.contains("HostKey has been changed") || message.contains("has been changed")) {
                        throw new HostKeyChangedException("The key of " + mAddress + " has CHANGED since it was saved in known hosts. " +
                            "This can mean that someone intercepts the connection. TermiNow did not connect. " +
                            "If the server was reinstalled, remove its old key in Known hosts first");
                    }
                    // JSch says "reject HostKey" for a host that is not in known_hosts
                    if ((message.contains("UnknownHostKey") || message.startsWith("reject HostKey")) && hostKey != null && attempt == 0) {
                        String fingerprint = hostKey.getFingerPrint(jsch);
                        if (!mPrompts.confirmHostKey(getKnownHostsName(), hostKey.getType(), fingerprint))
                            throw new IOException("The host key was not accepted");
                        appendKnownHost(hostKey);
                        jsch.setKnownHosts(getKnownHostsFile().getPath());
                        continue;
                    }
                    throw toIOException(e);
                }
                ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
                channel.connect(CONNECT_TIMEOUT_MS);
                mSession = session;
                mChannel = channel;
                try {
                    mRootPath = channel.getHome();
                } catch (SftpException e) {
                    mRootPath = "/";
                }
                return;
            }
        } catch (JSchException e) {
            throw toIOException(e);
        }
    }

    private void addIdentity(@NonNull JSch jsch, @NonNull File key) throws JSchException {
        if (!key.isFile()) return;
        KeyPair pair;
        try {
            pair = KeyPair.load(jsch, key.getPath());
        } catch (JSchException e) {
            // Not a key that JSch can read, like a PuTTY key that was not converted
            return;
        }
        if (pair.isEncrypted()) {
            String passphrase = mPrompts.askPassphrase(key.getName());
            if (passphrase == null) return;
            jsch.addIdentity(key.getPath(), passphrase.getBytes(StandardCharsets.UTF_8));
        } else {
            jsch.addIdentity(key.getPath());
        }
        pair.dispose();
    }

    /**
     * Ask for the key types that known_hosts has for the host first, so that a host saved by
     * `ssh` with one type is not shown as unknown because another type was negotiated.
     */
    private void preferKnownKeyTypes(@NonNull JSch jsch, @NonNull Session session) {
        HostKeyRepository repository = jsch.getHostKeyRepository();
        HostKey[] known = repository.getHostKey(getKnownHostsName(), null);
        if (known == null || known.length == 0) return;
        Set<String> types = new LinkedHashSet<>();
        for (HostKey key : known) types.add(key.getType());
        String defaults = JSch.getConfig("server_host_key");
        if (defaults != null) for (String type : defaults.split(",")) types.add(type);
        StringBuilder list = new StringBuilder();
        for (String type : types) list.append(list.length() > 0 ? "," : "").append(type);
        session.setConfig("server_host_key", list.toString());
    }

    private void appendKnownHost(@NonNull HostKey hostKey) throws IOException {
        File file = getKnownHostsFile();
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Failed to create ~/.ssh");
        String line = getKnownHostsName() + " " + hostKey.getType() + " " + hostKey.getKey() + "\n";
        boolean needsNewline = false;
        if (file.isFile() && file.length() > 0) {
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                raf.seek(file.length() - 1);
                needsNewline = raf.read() != '\n';
            }
        }
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            if (needsNewline) out.write('\n');
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
    }

    @NonNull
    private IOException toIOException(@NonNull JSchException e) {
        String message = String.valueOf(e.getMessage());
        if (message.contains("Auth fail") || message.contains("Auth cancel"))
            return new IOException("Authentication failed for " + mUsername + "@" + mAddress + ". Check the password or key of the host");
        if (message.contains("timeout") || message.contains("timed out"))
            return new IOException("The connection to " + mAddress + " timed out");
        if (e.getCause() instanceof java.net.UnknownHostException)
            return new IOException("Unknown host " + mAddress);
        if (e.getCause() instanceof java.net.ConnectException)
            return new IOException("Connection refused by " + mAddress + ":" + mPort);
        return new IOException(message, e);
    }

    /** Passwords for the server: the saved one first, then the user is asked. */
    private class Credentials implements UserInfo, UIKeyboardInteractive {
        private int mAsked;
        private String mCurrent;

        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return mCurrent;
        }

        @Override
        public boolean promptPassword(String message) {
            if (mAsked++ == 0 && !mPassword.isEmpty()) {
                mCurrent = mPassword;
                return true;
            }
            if (mAsked > 3) return false;
            mCurrent = mPrompts.askPassword("Password for " + mUsername + "@" + mAddress);
            return mCurrent != null;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            // Host keys are checked by StrictHostKeyChecking and confirmed in connect()
            return false;
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
            if (prompt.length != 1 || !prompt[0].toLowerCase(java.util.Locale.ROOT).contains("password")) return null;
            return promptPassword(prompt[0]) ? new String[]{mCurrent} : null;
        }
    }

    /** Whether the connection is open now, for the status in the File Manager. */
    public synchronized boolean isConnected() {
        return mSession != null && mSession.isConnected() && mChannel != null && mChannel.isConnected();
    }

    @NonNull
    private synchronized ChannelSftp channel() throws IOException {
        connect();
        return mChannel;
    }

    @NonNull
    private static String join(@NonNull String parent, @NonNull String name) {
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    @NonNull
    private Node toNode(@NonNull String path, @NonNull String name, @NonNull SftpATTRS attrs, @NonNull ChannelSftp channel) {
        boolean link = attrs.isLink();
        boolean directory = attrs.isDir();
        long size = attrs.getSize();
        if (link) {
            try {
                SftpATTRS target = channel.stat(path);
                directory = target.isDir();
                size = target.getSize();
            } catch (SftpException e) {
                // A dangling link
            }
        }
        return new Node(path, name, directory, link, directory ? -1 : size, attrs.getMTime() * 1000L, null);
    }

    @NonNull
    @Override
    public Node getRoot() throws IOException {
        channel();
        String name = mRootPath.equals("/") ? "/" : mRootPath.substring(mRootPath.lastIndexOf('/') + 1);
        return new Node(mRootPath, name.isEmpty() ? "/" : name, true, false, -1, 0, null);
    }

    /** A directory of the host by its absolute path, to open a bookmark. */
    @NonNull
    public synchronized Node getDirectory(@NonNull String path) throws IOException {
        ChannelSftp channel = channel();
        try {
            SftpATTRS attrs = channel.stat(path);
            if (!attrs.isDir()) throw new IOException(path + " is not a folder");
            String name = path.equals("/") ? "/" : path.substring(path.lastIndexOf('/') + 1);
            return new Node(path, name, true, false, -1, attrs.getMTime() * 1000L, null);
        } catch (SftpException e) {
            throw toIOException(e, path);
        }
    }

    @NonNull
    @Override
    public synchronized List<Node> list(@NonNull Node directory, @NonNull Cancel cancel) throws IOException {
        ChannelSftp channel = channel();
        List<Node> nodes = new ArrayList<>();
        try {
            Vector<?> entries = channel.ls(directory.id);
            for (Object object : entries) {
                cancel.check();
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                nodes.add(toNode(join(directory.id, name), name, entry.getAttrs(), channel));
            }
        } catch (SftpException e) {
            throw toIOException(e, directory.id);
        }
        return nodes;
    }

    @Nullable
    @Override
    public synchronized Node find(@NonNull Node directory, @NonNull String name) throws IOException {
        TerminowLocalFs.checkName(name);
        ChannelSftp channel = channel();
        String path = join(directory.id, name);
        try {
            return toNode(path, name, channel.lstat(path), channel);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw toIOException(e, path);
        }
    }

    @NonNull
    @Override
    public synchronized Node createDirectory(@NonNull Node parent, @NonNull String name) throws IOException {
        if (find(parent, name) != null) throw new IOException(name + " exists already");
        String path = join(parent.id, name);
        try {
            channel().mkdir(path);
            return toNode(path, name, channel().lstat(path), channel());
        } catch (SftpException e) {
            throw toIOException(e, path);
        }
    }

    @NonNull
    @Override
    public synchronized Node rename(@NonNull Node node, @NonNull Node parent, @NonNull String newName) throws IOException {
        if (find(parent, newName) != null) throw new IOException(newName + " exists already");
        String path = join(parent.id, newName);
        try {
            channel().rename(node.id, path);
            return toNode(path, newName, channel().lstat(path), channel());
        } catch (SftpException e) {
            throw toIOException(e, node.id);
        }
    }

    @Nullable
    @Override
    public synchronized Node move(@NonNull Node node, @NonNull Node fromParent, @NonNull Node toParent) throws IOException {
        if (node.directory && (toParent.id.equals(node.id) || toParent.id.startsWith(node.id + "/")))
            throw new IOException("A folder can not be moved into itself");
        if (find(toParent, node.name) != null) throw new IOException(node.name + " exists already");
        String path = join(toParent.id, node.name);
        try {
            channel().rename(node.id, path);
            return toNode(path, node.name, channel().lstat(path), channel());
        } catch (SftpException e) {
            return null;
        }
    }

    @Override
    public synchronized void delete(@NonNull Node node, @NonNull Node parent, @NonNull Cancel cancel) throws IOException {
        ChannelSftp channel = channel();
        try {
            deletePath(channel, node.id, channel.lstat(node.id), cancel);
        } catch (SftpException e) {
            throw toIOException(e, node.id);
        }
    }

    private void deletePath(@NonNull ChannelSftp channel, @NonNull String path, @NonNull SftpATTRS attrs, @NonNull Cancel cancel)
        throws SftpException, IOException {
        cancel.check();
        // lstat: a link is removed itself, never followed
        if (attrs.isDir() && !attrs.isLink()) {
            for (Object object : channel.ls(path)) {
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) object;
                if (entry.getFilename().equals(".") || entry.getFilename().equals("..")) continue;
                deletePath(channel, join(path, entry.getFilename()), entry.getAttrs(), cancel);
            }
            channel.rmdir(path);
        } else {
            channel.rm(path);
        }
    }

    /** A channel of its own, which is disconnected when the stream is closed. */
    @NonNull
    private synchronized ChannelSftp openStreamChannel() throws IOException {
        connect();
        try {
            ChannelSftp channel = (ChannelSftp) mSession.openChannel("sftp");
            channel.connect(CONNECT_TIMEOUT_MS);
            return channel;
        } catch (JSchException e) {
            throw toIOException(e);
        }
    }

    @NonNull
    @Override
    public InputStream openInput(@NonNull Node file) throws IOException {
        ChannelSftp channel = openStreamChannel();
        try {
            return new FilterInputStream(channel.get(file.id)) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        channel.disconnect();
                    }
                }
            };
        } catch (SftpException e) {
            channel.disconnect();
            throw toIOException(e, file.id);
        }
    }

    @NonNull
    @Override
    public OutputStream openOutput(@NonNull Node directory, @NonNull String name, boolean replace) throws IOException {
        Node existing = find(directory, name);
        if (existing != null) {
            if (!replace) throw new IOException(name + " exists already");
            if (existing.directory) throw new IOException(name + " is a folder");
        }
        ChannelSftp channel = openStreamChannel();
        try {
            OutputStream out = channel.put(join(directory.id, name), ChannelSftp.OVERWRITE);
            return new FilterOutputStream(out) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        channel.disconnect();
                    }
                }
            };
        } catch (SftpException e) {
            channel.disconnect();
            throw toIOException(e, name);
        }
    }

    @NonNull
    private static IOException toIOException(@NonNull SftpException e, @NonNull String path) {
        switch (e.id) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE: return new java.io.FileNotFoundException(path + " does not exist");
            case ChannelSftp.SSH_FX_PERMISSION_DENIED: return new IOException("Permission denied: " + path);
            default: return new IOException(String.valueOf(e.getMessage()) + ": " + path, e);
        }
    }

    @NonNull
    @Override
    public String getDisplayPath(@NonNull List<Node> breadcrumbs) {
        Node last = breadcrumbs.isEmpty() ? null : breadcrumbs.get(breadcrumbs.size() - 1);
        return mTitle + ":" + (last == null ? mRootPath : last.id);
    }

    @Override
    public synchronized void close() {
        if (mChannel != null) mChannel.disconnect();
        if (mSession != null) mSession.disconnect();
        mChannel = null;
        mSession = null;
    }

}
