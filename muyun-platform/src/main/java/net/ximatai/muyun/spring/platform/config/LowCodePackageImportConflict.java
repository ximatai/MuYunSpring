package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Map;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

public record LowCodePackageImportConflict(
        LowCodePackageConflictType conflictType,
        LowCodePackageConflictSeverity severity,
        String moduleAlias,
        String existingVersionId,
        String message,
        TextReference textSource,
        Map<String, Object> messageArgs
) {
    /** Undeclared extension diagnostics retain their original text. */
    public LowCodePackageImportConflict(LowCodePackageConflictType conflictType, LowCodePackageConflictSeverity severity, String moduleAlias, String existingVersionId, String message) {
        this(conflictType, severity, moduleAlias, existingVersionId, message, null, Map.of());
    }

    public LowCodePackageImportConflict(LowCodePackageConflictType conflictType, LowCodePackageConflictSeverity severity,
                                        String moduleAlias, String existingVersionId, DiagnosticText message) {
        this(conflictType, severity, moduleAlias, existingVersionId,
                message.message(), message.textSource(), message.messageArgs());
    }

    public LowCodePackageImportConflict {
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
        if (conflictType == null) {
            throw new IllegalArgumentException("conflictType must not be null");
        }
        severity = severity == null ? LowCodePackageConflictSeverity.ERROR : severity;
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        existingVersionId = normalize(existingVersionId);
        message = requireText(message, "message");
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
