package net.ximatai.muyun.spring.ability.logging;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded diagnostic text with minimal credential redaction.
 *
 * <p>Use this type for untrusted request, response, exception and stack text. It never stores
 * password, token, authorization, secret or cookie values verbatim.</p>
 */
public record LogText(String value, boolean truncated, boolean redacted) {
    public static final int MAX_LENGTH = 4_096;
    private static final String REDACTED = "[REDACTED]";
    private static final Pattern HEADER_SECRET = Pattern.compile(
            "(?i)(\\b(?:authorization|cookie)\\b\\s*[:=]\\s*)(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\r\\n]*)");
    private static final Pattern NAMED_SECRET = Pattern.compile(
            "(?i)((?:[\\\"']?(?:password|passwd|pwd|token|authorization|secret|credential|cookie)[\\\"']?)\\s*[:=]\\s*)"
                    + "(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,;}&\\]]+)");
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bbearer\\s+(?:\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|[A-Za-z0-9._~+/=-]+)");

    public LogText {
        value = Objects.requireNonNull(value, "value must not be null");
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("value must not exceed " + MAX_LENGTH + " characters");
        }
    }

    public static LogText of(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        String redactedValue = HEADER_SECRET.matcher(rawValue).replaceAll("$1" + REDACTED);
        redactedValue = NAMED_SECRET.matcher(redactedValue).replaceAll("$1" + REDACTED);
        redactedValue = BEARER_TOKEN.matcher(redactedValue).replaceAll("Bearer " + REDACTED);
        boolean wasRedacted = !redactedValue.equals(rawValue);
        boolean wasTruncated = redactedValue.length() > MAX_LENGTH;
        if (wasTruncated) {
            redactedValue = redactedValue.substring(0, MAX_LENGTH);
        }
        return new LogText(redactedValue, wasTruncated, wasRedacted);
    }
}
