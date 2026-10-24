package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The .ppk writer {@link TerminowPpk#toPpk}: a file it writes is read back by {@link
 * TerminowPpk#convert} (the reader), encrypted and not, which proves the MAC, padding and crypto
 * match. Uses a fixed Ed25519 seed so the round-trip is deterministic.
 */
public class TerminowPpkWriteTest {

    /** Build the SSH public blob and the PuTTY private blob for an Ed25519 key from its 32-byte seed. */
    private byte[][] ed25519Blobs(byte[] seed) {
        Ed25519PrivateKeyParameters prv = new Ed25519PrivateKeyParameters(seed, 0);
        byte[] pub = prv.generatePublicKey().getEncoded();
        byte[] publicBlob = sshString("ssh-ed25519".getBytes(StandardCharsets.US_ASCII), pub);
        byte[] privateBlob = sshString(seed);
        return new byte[][]{publicBlob, privateBlob, pub};
    }

    private static byte[] sshString(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p.length >>> 24); out.write(p.length >>> 16); out.write(p.length >>> 8); out.write(p.length);
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    private byte[] seed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < 32; i++) seed[i] = (byte) (i * 7 + 3);
        return seed;
    }

    @Test
    public void unencryptedRoundTrips() throws Exception {
        byte[][] b = ed25519Blobs(seed());
        String ppk = TerminowPpk.toPpk("ssh-ed25519", b[0], b[1], "zoom32", null);
        assertTrue(TerminowPpk.isPpk(ppk));
        assertTrue(ppk.contains("Encryption: none"));
        TerminowPpk.Result result = TerminowPpk.convert(ppk, null);
        assertTrue(result.publicKey.startsWith("ssh-ed25519 "));
        assertEquals("zoom32", result.comment);
        assertTrue(result.privateKey.contains("BEGIN OPENSSH PRIVATE KEY"));
    }

    @Test
    public void encryptedRoundTripsWithPassphrase() throws Exception {
        byte[][] b = ed25519Blobs(seed());
        String ppk = TerminowPpk.toPpk("ssh-ed25519", b[0], b[1], "zoom32", "test-passphrase");
        assertTrue(ppk.contains("Encryption: aes256-cbc"));
        assertTrue(ppk.contains("Key-Derivation: Argon2id"));
        // The right passphrase reads it back
        TerminowPpk.Result ok = TerminowPpk.convert(ppk, "test-passphrase");
        assertTrue(ok.publicKey.startsWith("ssh-ed25519 "));
        // A wrong passphrase is rejected by the MAC
        try {
            TerminowPpk.convert(ppk, "wrong");
            org.junit.Assert.fail("wrong passphrase must fail");
        } catch (TerminowPpk.PassphraseException expected) {
            // good
        }
    }

    @Test
    public void publicBlobSurvives() throws Exception {
        byte[][] b = ed25519Blobs(seed());
        String ppk = TerminowPpk.toPpk("ssh-ed25519", b[0], b[1], "k", null);
        TerminowPpk.Result result = TerminowPpk.convert(ppk, null);
        // The public key line encodes the same public blob we put in
        String expected = "ssh-ed25519 " + TerminowPpk.encodeBase64(b[0]);
        assertTrue(result.publicKey.startsWith(expected));
    }
}
