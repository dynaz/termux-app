package com.termux.app.terminow;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Remove secrets from text that is shown, logged or kept, like error messages and the output of
 * Claude Code commands. The known secret values are replaced, and so are values that look like
 * credentials even if they are not known, like the token of an `Authorization` header.
 */
public class TerminowMcpRedactor {

    public static final String REDACTED = "[redacted]";

    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile("(?i)(authorization\\s*[:=]\\s*\"?\\s*)(bearer\\s+|basic\\s+|token\\s+)?[^\\s\",}]+");
    private static final Pattern BEARER_PATTERN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern SECRET_PARAMETER_PATTERN = Pattern.compile("(?i)([?&](access_token|token|api_key|apikey|key|secret)=)[^&\\s\"]+");

    private final List<String> mSecrets = new ArrayList<>();

    public TerminowMcpRedactor(@Nullable Collection<String> secrets) {
        if (secrets == null) return;
        for (String secret : secrets) {
            // Short values like "1" would remove unrelated text, and say little about the secret
            if (secret != null && secret.trim().length() >= 4) mSecrets.add(secret.trim());
        }
        // The longest first, so that a secret that contains another is removed whole
        mSecrets.sort((a, b) -> b.length() - a.length());
    }

    @NonNull
    public String redact(@Nullable String text) {
        if (text == null) return "";
        for (String secret : mSecrets)
            text = text.replace(secret, REDACTED);
        text = AUTHORIZATION_PATTERN.matcher(text).replaceAll("$1$2" + REDACTED);
        text = BEARER_PATTERN.matcher(text).replaceAll("$1" + REDACTED);
        text = SECRET_PARAMETER_PATTERN.matcher(text).replaceAll("$1" + REDACTED);
        // A replacement can repeat the marker, like "[redacted][redacted]"
        return text.replace(REDACTED + REDACTED, REDACTED);
    }

    /** A value to show instead of a secret, which tells that it is set without showing it. */
    @NonNull
    public static String mask(@Nullable String secret) {
        if (secret == null || secret.isEmpty()) return "";
        return "••••••••";
    }

}
