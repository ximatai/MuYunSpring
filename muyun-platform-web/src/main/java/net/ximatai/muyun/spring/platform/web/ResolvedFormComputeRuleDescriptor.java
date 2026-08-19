package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.formula.FormulaExecutionProfile;
import net.ximatai.muyun.spring.common.formula.FormulaProgram;
import java.util.List;

/** Server-issued, executable form-computation contract. */
public record ResolvedFormComputeRuleDescriptor(String code,
                                                FormulaProgram program,
                                                String targetField,
                                                List<String> triggerFields,
                                                FormComputeWritePolicy writePolicy) {
    public ResolvedFormComputeRuleDescriptor {
        code = requireCode(code, "form compute rule code");
        if (program == null || program.profile() != FormulaExecutionProfile.FORM_COMPUTE) {
            throw new IllegalArgumentException("form compute rule must contain a FORM_COMPUTE program: " + code);
        }
        targetField = requireField(targetField, "form compute target field");
        triggerFields = triggerFields == null ? List.of() : List.copyOf(triggerFields);
        writePolicy = writePolicy == null ? FormComputeWritePolicy.ALWAYS : writePolicy;
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
