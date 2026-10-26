package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lists EC2 instances in one region by calling {@code DescribeInstances} with the person's own
 * credentials, signed with {@link TerminowAwsSigV4}. Temporary credentials are supported through a
 * session token. The call needs only {@code ec2:DescribeInstances}; it never starts, stops, modifies
 * or deletes anything, and the credentials are used for this one request and never logged or stored.
 */
public final class TerminowEc2Client {

    private static final String API_VERSION = "2016-11-15";
    private static final int MAX_INSTANCES = 5000;

    private TerminowEc2Client() {}

    @NonNull
    public static List<TerminowCloudInstance> describeInstances(@NonNull String accessKey, @NonNull String secretKey,
                                                                @Nullable String sessionToken, @NonNull String region,
                                                                @Nullable AtomicBoolean cancel) throws IOException {
        String host = "ec2." + region.trim() + ".amazonaws.com";
        String endpoint = "https://" + host + "/";

        List<TerminowCloudInstance> result = new ArrayList<>();
        String nextToken = "";
        do {
            if (cancel != null && cancel.get()) break;

            String body = "Action=DescribeInstances&Version=" + API_VERSION;
            if (!nextToken.isEmpty()) body += "&NextToken=" + URLEncoder.encode(nextToken, StandardCharsets.UTF_8.name());

            SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            String amzDate = format.format(new Date());
            String dateStamp = amzDate.substring(0, 8);

            // Headers that are signed; Host is added by the connection and matches the signed value.
            Map<String, String> signed = new LinkedHashMap<>();
            signed.put("host", host);
            signed.put("x-amz-date", amzDate);
            signed.put("content-type", "application/x-www-form-urlencoded; charset=utf-8");
            boolean temporary = sessionToken != null && !sessionToken.trim().isEmpty();
            if (temporary) signed.put("x-amz-security-token", sessionToken.trim());

            String authorization = TerminowAwsSigV4.authorization("POST", "ec2", region.trim(), "/", "",
                signed, body, accessKey.trim(), secretKey.trim(), amzDate, dateStamp);

            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-Amz-Date", amzDate);
            headers.put("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            if (temporary) headers.put("X-Amz-Security-Token", sessionToken.trim());
            headers.put("Authorization", authorization);
            headers.put("Accept", "application/xml");

            TerminowCloudHttp.Response response = TerminowCloudHttp.request("POST", endpoint, headers, body);
            if (response.code == 401 || response.code == 403)
                throw new IOException("AWS rejected the request. Check the credentials and that the IAM policy allows ec2:DescribeInstances.");
            if (!response.ok())
                throw new IOException("AWS returned HTTP " + response.code + ". " + shortError(response.body));

            result.addAll(TerminowEc2Import.parseDescribeInstances(response.body));
            if (result.size() >= MAX_INSTANCES) break;
            nextToken = TerminowEc2Import.nextToken(response.body);
        } while (!nextToken.isEmpty() && (cancel == null || !cancel.get()));

        return result;
    }

    /** A short, non-sensitive snippet of an AWS XML error message for the UI. */
    @NonNull
    private static String shortError(@NonNull String body) {
        int start = body.indexOf("<Message>");
        int end = body.indexOf("</Message>");
        if (start >= 0 && end > start) {
            String message = body.substring(start + "<Message>".length(), end).trim();
            return message.length() > 200 ? message.substring(0, 200) : message;
        }
        return "";
    }
}
