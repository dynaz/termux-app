package com.termux.app.terminow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AWS Signature Version 4, checked against AWS's own published "get-vanilla" example from the SigV4
 * test suite, so the signing is known-correct without a live AWS call.
 */
public class TerminowAwsSigV4Test {

    @Test
    public void matchesAwsGetVanillaExample() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", "example.amazonaws.com");
        headers.put("X-Amz-Date", "20150830T123600Z");

        String auth = TerminowAwsSigV4.authorization(
            "GET", "service", "us-east-1", "/", "",
            headers, "",
            "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "20150830T123600Z", "20150830");

        assertEquals("AWS4-HMAC-SHA256 "
                + "Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                + "SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
            auth);
    }

    @Test
    public void sha256HexOfEmptyIsKnownConstant() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            TerminowAwsSigV4.sha256Hex(""));
    }
}
