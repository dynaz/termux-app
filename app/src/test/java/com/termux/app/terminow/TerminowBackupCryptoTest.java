package com.termux.app.terminow;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password encryption for backups: round-trip, wrong password, tamper, truncation, PBKDF2 correctness. */
public class TerminowBackupCryptoTest {

    private static byte[] encrypt(byte[] plain, String password) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (OutputStream out = TerminowBackupCrypto.encryptingStream(sink, password.toCharArray())) {
            out.write(plain);
        }
        return sink.toByteArray();
    }

    private static byte[] decrypt(byte[] cipher, String password) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = TerminowBackupCrypto.decryptingStream(new ByteArrayInputStream(cipher), password.toCharArray())) {
            byte[] buf = new byte[7919];   // an odd buffer to exercise partial chunk reads
            int n;
            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    public void roundTripAtChunkBoundaries() throws Exception {
        int chunk = TerminowBackupCrypto.CHUNK;
        int[] sizes = {0, 1, 100, chunk - 1, chunk, chunk + 1, 3 * chunk, 3 * chunk + 123, 5 * 1024 * 1024 + 7};
        Random random = new Random(42);
        for (int size : sizes) {
            byte[] plain = new byte[size];
            random.nextBytes(plain);
            byte[] cipher = encrypt(plain, "correct horse battery staple");
            assertTrue("header magic for size " + size, TerminowBackupCrypto.looksEncrypted(cipher));
            assertArrayEquals("round trip for size " + size, plain, decrypt(cipher, "correct horse battery staple"));
        }
    }

    @Test
    public void theCiphertextDiffersFromThePlaintextAndFromRun() throws Exception {
        byte[] plain = "hidden configuration and secrets".getBytes();
        byte[] a = encrypt(plain, "pw");
        byte[] b = encrypt(plain, "pw");
        assertFalse(Arrays.equals(plain, a));
        // Random salt + nonce per run: two encryptions of the same input are different ciphertext.
        assertNotEquals(bytesToHex(a), bytesToHex(b));
        assertArrayEquals(plain, decrypt(a, "pw"));
        assertArrayEquals(plain, decrypt(b, "pw"));
    }

    @Test
    public void aWrongPasswordIsRejected() throws Exception {
        byte[] cipher = encrypt("top secret backup".getBytes(), "right-password");
        try {
            decrypt(cipher, "wrong-password");
            fail("a wrong password should not decrypt");
        } catch (TerminowBackupCrypto.BadPasswordException expected) {
            // the first chunk's GCM tag fails to verify
        }
    }

    @Test
    public void tamperedCiphertextIsRejected() throws Exception {
        byte[] cipher = encrypt(new byte[5000], "pw");
        cipher[cipher.length - 1] ^= 0x01;   // flip a bit in the last chunk's tag
        try {
            decrypt(cipher, "pw");
            fail("tampered ciphertext should not decrypt");
        } catch (TerminowBackupCrypto.BadPasswordException expected) {
            // authentication fails
        }
    }

    @Test
    public void aTruncatedStreamIsRejected() throws Exception {
        byte[] cipher = encrypt(new byte[200000], "pw");       // several chunks
        byte[] cut = Arrays.copyOf(cipher, cipher.length - 100); // drop the final chunk
        try {
            decrypt(cut, "pw");
            fail("a truncated stream should not be accepted");
        } catch (IOException expected) {
            // truncation (missing final chunk) is detected
        }
    }

    @Test
    public void looksEncryptedOnlyForOurMagic() {
        assertFalse(TerminowBackupCrypto.looksEncrypted(new byte[]{0x1f, (byte) 0x8b}));   // gzip
        assertFalse(TerminowBackupCrypto.looksEncrypted(new byte[0]));
        assertTrue(TerminowBackupCrypto.looksEncrypted(TerminowBackupCrypto.MAGIC.clone()));
    }

    @Test
    public void pbkdf2MatchesTheJdkImplementation() throws Exception {
        byte[] salt = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
        char[] password = "a-passphrase-éü".toCharArray();   // includes non-ASCII (UTF-8)
        int iterations = 5000;
        byte[] mine = TerminowBackupCrypto.deriveKey(password, salt, iterations);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] jdk = factory.generateSecret(new PBEKeySpec(password, salt, iterations, TerminowBackupCrypto.KEY_BITS)).getEncoded();
        assertArrayEquals("hand-rolled PBKDF2-HMAC-SHA256 must match the JDK", jdk, mine);
        assertEquals(32, mine.length);
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }
}
