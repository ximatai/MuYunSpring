package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Map;

public record LowCodeConfigHealthItem(
        LowCodeConfigHealthScope scope,
        String code,
        LowCodeConfigHealthSeverity severity,
        String message,
        String targetType,
        String targetId,
        String suggestion,
        TextReference textSource,
        Map<String, Object> messageArgs,
        TextReference suggestionSource,
        Map<String, Object> suggestionArgs
) {
    /** Undeclared extension diagnostics retain their original text. */
    public LowCodeConfigHealthItem(LowCodeConfigHealthScope scope, String code, LowCodeConfigHealthSeverity severity, String message, String targetType, String targetId, String suggestion) {
        this(scope, code, severity, message, targetType, targetId, suggestion, null, Map.of(), null, Map.of());
    }

    public static LowCodeConfigHealthItem error(LowCodeConfigHealthScope scope, String code,
                                                DiagnosticText message, String targetType, String targetId,
                                                DiagnosticText suggestion) {
        return declared(scope, code, LowCodeConfigHealthSeverity.ERROR, message, targetType, targetId, suggestion);
    }

    public static LowCodeConfigHealthItem warn(LowCodeConfigHealthScope scope, String code,
                                               DiagnosticText message, String targetType, String targetId,
                                               DiagnosticText suggestion) {
        return declared(scope, code, LowCodeConfigHealthSeverity.WARN, message, targetType, targetId, suggestion);
    }

    public static LowCodeConfigHealthItem declared(LowCodeConfigHealthScope scope, String code,
                                                   LowCodeConfigHealthSeverity severity, DiagnosticText message,
                                                   String targetType, String targetId, DiagnosticText suggestion) {
        return new LowCodeConfigHealthItem(scope, code, severity, message.message(), targetType, targetId,
                suggestion.message(), message.textSource(), message.messageArgs(),
                suggestion.textSource(), suggestion.messageArgs());
    }

    public LowCodeConfigHealthItem {
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
        suggestionArgs = suggestionArgs == null ? Map.of() : Map.copyOf(suggestionArgs);
        scope = scope == null ? LowCodeConfigHealthScope.PACKAGE : scope;
        code = requireText(code, "health item code");
        severity = severity == null ? LowCodeConfigHealthSeverity.ERROR : severity;
        message = requireText(message, "health item message");
        targetType = normalize(targetType);
        targetId = normalize(targetId);
        suggestion = normalize(suggestion);
    }

    public static LowCodeConfigHealthItem error(LowCodeConfigHealthScope scope,
                                                String code,
                                                String message,
                                                String targetType,
                                                String targetId,
                                                String suggestion) {
        return new LowCodeConfigHealthItem(scope, code, LowCodeConfigHealthSeverity.ERROR,
                message, targetType, targetId, suggestion);
    }

    public static LowCodeConfigHealthItem warn(LowCodeConfigHealthScope scope,
                                               String code,
                                               String message,
                                               String targetType,
                                               String targetId,
                                               String suggestion) {
        return new LowCodeConfigHealthItem(scope, code, LowCodeConfigHealthSeverity.WARN,
                message, targetType, targetId, suggestion);
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
