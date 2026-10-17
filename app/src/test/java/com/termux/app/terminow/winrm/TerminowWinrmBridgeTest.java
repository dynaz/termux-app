package com.termux.app.terminow.winrm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * The WinRM program of the Linux environment, run with the python3 of this machine: it takes
 * the password only from its standard input, and says what is missing or failed as JSON.
 */
public class TerminowWinrmBridgeTest {

    private static String firstLine(String config) throws Exception {
        Process process = new ProcessBuilder("python3", "-I", "-c", TerminowWinrmSession.BRIDGE).start();
        try (OutputStream in = process.getOutputStream()) {
            in.write((config + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String line;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            line = out.readLine();
        }
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        return line;
    }

    @Test
    public void thePasswordIsNeverAnArgument() {
        // The program is given as is: the settings come later, on standard input
        assertFalse(TerminowWinrmSession.BRIDGE.contains("sys.argv"));
        assertTrue(TerminowWinrmSession.BRIDGE.contains("json.loads(sys.stdin.readline())"));
        assertTrue(TerminowWinrmSession.BRIDGE.contains("server_cert_validation='validate' if config['verify']"));
    }

    @Test
    public void saysWhatIsMissingOrWhyItCouldNotConnect() throws Exception {
        assumeTrue(new File("/usr/bin/python3").exists());
        String config = new JSONObject().put("address", "127.0.0.1").put("port", 1).put("https", true).put("verify", true)
            .put("username", "u").put("domain", "").put("password", "secret-not-shown").toString();
        JSONObject event = new JSONObject(firstLine(config));
        String kind = event.getString("event");
        if (kind.equals("missing")) {
            assertEquals("python3-winrm", event.getString("what"));
        } else {
            // pywinrm is there: nothing listens on port 1, and the password is not in the message
            assertEquals("failed", kind);
            assertFalse(event.getString("message").contains("secret-not-shown"));
        }
    }
}
