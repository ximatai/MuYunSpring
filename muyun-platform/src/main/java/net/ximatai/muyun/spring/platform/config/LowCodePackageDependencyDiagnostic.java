package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.Map;

public record LowCodePackageDependencyDiagnostic(
        LowCodePackageDependency dependency,
        LowCodePackageConflictType conflictType,
        boolean blocking,
        String targetId,
        String message,
        TextReference textSource,
        Map<String, Object> messageArgs
) {
    /** Undeclared extension diagnostics retain their original text. */
    public LowCodePackageDependencyDiagnostic(LowCodePackageDependency dependency, LowCodePackageConflictType conflictType, boolean blocking, String targetId, String message) {
        this(dependency, conflictType, blocking, targetId, message, null, Map.of());
    }

    public LowCodePackageDependencyDiagnostic(LowCodePackageDependency dependency, LowCodePackageConflictType conflictType,
                                             boolean blocking, String targetId, DiagnosticText message) {
        this(dependency, conflictType, blocking, targetId, message.message(), message.textSource(), message.messageArgs());
    }

    public LowCodePackageDependencyDiagnostic {
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
    }
}
