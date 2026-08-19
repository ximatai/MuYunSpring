package net.ximatai.muyun.spring.platform.web;

import java.util.List;

/** Source-neutral declaration of one browser-local form calculation. */
public record FormComputeRuleDefinition(String code,
                                        String expression,
                                        String targetField,
                                        List<String> triggerFields,
                                        FormComputeWritePolicy writePolicy) {
    public FormComputeRuleDefinition {
        code = requireCode(code, "form compute rule code");
        expression = expression == null || expression.isBlank() ? null : expression.trim();
        if (expression == null) {
            throw new IllegalArgumentException("form compute rule expression must not be blank: " + code);
        }
        targetField = requireField(targetField, "form compute target field");
        triggerFields = triggerFields == null ? List.of() : triggerFields.stream()
                .map(value -> requireField(value, "form compute trigger field"))
                .distinct()
                .toList();
        writePolicy = writePolicy == null ? FormComputeWritePolicy.ALWAYS : writePolicy;
    }

    public FormComputeRuleDefinition(String code, String targetField, List<String> triggerFields, String expression) {
        this(code, expression, targetField, triggerFields, FormComputeWritePolicy.ALWAYS);
    }

    private static String requireCode(String value, String name) {
        if (value == null || !value.matches("[a-z][A-Za-z0-9]{0,63}")) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    private static String requireField(String value, String name) {
        if (value == null || !value.matches("[a-z][A-Za-z0-9]{0,62}")) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }
}
