package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/**
 * The ssh-agent protocol (draft-miller-ssh-agent) for the keys that never leave the device, like
 * the biometric keys of the Android keystore: it lists them and signs with them. Nothing can be
 * added, removed or exported through it. Every signature is done by the {@link Key}, which asks
 * the user first.
 */
public final class TerminowSshAgent {

    static final int SSH_AGENT_FAILURE = 5;
    static final int SSH2_AGENTC_REQUEST_IDENTITIES = 11;
    static final int SSH2_AGENT_IDENTITIES_ANSWER = 12;
    static final int SSH2_AGENTC_SIGN_REQUEST = 13;
    static final int SSH2_AGENT_SIGN_RESPONSE = 14;
    /** Larger messages are not what ssh sends to sign in, and are refused. */
    static final int MAX_MESSAGE = 256 * 1024;

    /** A key of the agent. */
    public interface Key {
        @NonNull byte[] publicBlob();

        @NonNull String comment();

        /**
         * The SSH signature blob of the data, {@code null} if the user did not allow it.
         * Called on the thread of the connection, which it may block.
         */
        @Nullable byte[] sign(@NonNull byte[] data, int flags) throws Exception;
    }

    public interface Keys {
        @NonNull List<Key> list();
    }

    private TerminowSshAgent() {}

    /** Answer the requests of one connection until it is closed. */
    public static void serve(@NonNull InputStream input, @NonNull OutputStream output, @NonNull Keys keys) throws IOException {
        DataInputStream in = new DataInputStream(input);
        while (true) {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException e) {
                return;
            }
            if (length <= 0 || length > MAX_MESSAGE) return;
            byte[] message = new byte[length];
            in.readFully(message);
            byte[] reply = handle(message, keys);
            output.write(new TerminowSshWire.Writer().string(reply).toBytes());
            output.flush();
        }
    }

    @NonNull
    static byte[] handle(@NonNull byte[] message, @NonNull Keys keys) {
        try {
            TerminowSshWire.Reader reader = new TerminowSshWire.Reader(message);
            int type = reader.byte1();
            if (type == SSH2_AGENTC_REQUEST_IDENTITIES) {
                List<Key> list = keys.list();
                TerminowSshWire.Writer writer = new TerminowSshWire.Writer().byte1(SSH2_AGENT_IDENTITIES_ANSWER).uint32(list.size());
                for (Key key : list) writer.string(key.publicBlob()).text(key.comment());
                return writer.toBytes();
            }
            if (type == SSH2_AGENTC_SIGN_REQUEST) {
                byte[] blob = reader.string();
                byte[] data = reader.string();
                int flags = (int) reader.uint32();
                Key key = find(keys.list(), blob);
                if (key == null) return failure();
                byte[] signature = key.sign(data, flags);
                if (signature == null) return failure();
                return new TerminowSshWire.Writer().byte1(SSH2_AGENT_SIGN_RESPONSE).string(signature).toBytes();
            }
        } catch (Exception e) {
            // A request that can not be read, or a key that failed: a failure, as ssh-agent answers
        }
        return failure();
    }

    /**
     * The key of a blob: the key itself, or the key that a certificate certifies, since ssh asks
     * to sign with the certificate when it logs in with one.
     */
    @Nullable
    static Key find(@NonNull List<Key> keys, @NonNull byte[] blob) {
        TerminowSshWire.Certificate certificate = TerminowSshWire.parseCertificate(blob);
        byte[] wanted = certificate != null ? certificate.keyBlob : blob;
        for (Key key : keys) if (Arrays.equals(key.publicBlob(), wanted)) return key;
        return null;
    }

    @NonNull
    private static byte[] failure() {
        return new byte[]{SSH_AGENT_FAILURE};
    }

}
