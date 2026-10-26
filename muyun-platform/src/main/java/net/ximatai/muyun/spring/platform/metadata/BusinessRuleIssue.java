package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.DeclarationValidationException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import java.util.Map;

/** Original diagnostics and stable rule/field identities remain separate from display resources. */
public record BusinessRuleIssue(String code, String ruleCode, String field, String message,
                                TextReference textSource, Map<String, Object> messageArgs) {
    public BusinessRuleIssue {
        messageArgs = messageArgs == null ? Map.of() : Map.copyOf(messageArgs);
    }

    public BusinessRuleIssue(String code, String ruleCode, String field, String message) {
        this(code, ruleCode, field, message, null, Map.of());
    }

    public static BusinessRuleIssue declared(String code, String ruleCode, String field,
                                              TextReference source, Map<String, Object> arguments) {
        return new BusinessRuleIssue(code, ruleCode, field, TextResources.format(source.originalText(), arguments),
                source, arguments);
    }

    public static BusinessRuleIssue failure(String code, String ruleCode, String field, RuntimeException failure) {
        if (failure instanceof DeclarationValidationException declared)
            return new BusinessRuleIssue(code, ruleCode, field, failure.getMessage(), declared.textSource(), declared.messageArgs());
        if (failure instanceof PlatformException declared && declared.textSource() != null)
            return new BusinessRuleIssue(code, ruleCode, field, failure.getMessage(), declared.textSource(), declared.messageArgs());
        return new BusinessRuleIssue(code, ruleCode, field, failure.getMessage());
    }
}
