package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.formula.FormulaExecutionProfile;
import net.ximatai.muyun.spring.common.formula.FormulaProgram;

import java.util.List;

/**
 * Server-issued, browser-local pre-save validation. It improves form feedback only; the matching
 * server rule remains the persistence authority.
 */
public record ResolvedFormValidationRuleDescriptor(String code,
                                                   FormulaProgram program,
                                                   List<String> inputFields,
                                                   String targetField,
                                                   String message) {
    public ResolvedFormValidationRuleDescriptor {
        code = requireCode(code, "form validation rule code");
        if (program == null || program.profile() != FormulaExecutionProfile.FORM_VALIDATION) {
            throw new IllegalArgumentException("form validation rule must contain a FORM_VALIDATION program: " + code);
        }
        inputFields = inputFields == null ? List.of() : List.copyOf(inputFields);
        if (!inputFields.stream().allMatch(ResolvedFormValidationRuleDescriptor::isField)) {
            throw new IllegalArgumentException("form validation inputs require direct form fields: " + code);
        }
        if (targetField != null && !isField(targetField)) {
            throw new IllegalArgumentException("invalid form validation target field: " + targetField);
        }
        message = message == null || message.isBlank() ? "表单校验未通过" : message.trim();
    }

    private static String requireCode(String value, String name) {
        if (value == null || !value.matches("[a-z][A-Za-z0-9]{0,63}")) {
            throw new IllegalArgumentException("invalid " + name + ": " + value);
        }
        return value;
    }

    private static boolean isField(String value) {
        return value != null && value.matches("[a-z][A-Za-z0-9_]{0,62}");
    }
}
