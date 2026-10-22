package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;

import java.util.Map;

/** Structured diagnostics preserve machine identity and original text independently of presentation. */
public record MetadataChangeSetValidationIssue(Severity severity, String code, String subject, String message,
                                              TextReference textSource, Map<String, Object> messageArgs) {
    public enum Severity { WARNING, ERROR }

    public MetadataChangeSetValidationIssue {
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
    }

    /** Undeclared technical or external diagnostics retain their original text. */
    public MetadataChangeSetValidationIssue(Severity severity, String code, String subject, String message) {
        this(severity, code, subject, message, null, Map.of());
    }

    public static MetadataChangeSetValidationIssue declared(Severity severity, String code, String subject,
                                                            TextReference source, Map<String, Object> arguments) {
        return new MetadataChangeSetValidationIssue(severity, code, subject,
                TextResources.format(source.originalText(), arguments), source, arguments);
    }

    public static MetadataChangeSetValidationIssue failure(String code, String subject, RuntimeException failure) {
        if (failure instanceof PlatformException declared && declared.textSource() != null) {
            return new MetadataChangeSetValidationIssue(Severity.ERROR, code, subject,
                    declared.getMessage(), declared.textSource(), declared.messageArgs());
        }
        return new MetadataChangeSetValidationIssue(Severity.ERROR, code, subject, failure.getMessage());
    }
}
