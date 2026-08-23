package net.ximatai.muyun.spring.platform.ui;

import net.ximatai.muyun.spring.common.formula.FormulaExecutionProfile;
import net.ximatai.muyun.spring.common.formula.FormulaProgram;

import java.util.List;

/** Server-compiled calculation to apply between rows of one embedded relation draft. */
public record ResolvedRelationFormComputeRuleDescriptor(String code, FormulaProgram program,
                                                        String targetField, List<String> triggerFields) {
    public ResolvedRelationFormComputeRuleDescriptor {
        if (code == null || !code.matches("[a-z][A-Za-z0-9]{0,63}"))
            throw new IllegalArgumentException("invalid relation form compute rule code: " + code);
        if (program == null || program.profile() != FormulaExecutionProfile.FORM_COMPUTE)
            throw new IllegalArgumentException("relation form compute rule must contain a FORM_COMPUTE program: " + code);
        if (targetField == null || !targetField.matches("[a-z][A-Za-z0-9]{0,62}"))
            throw new IllegalArgumentException("invalid relation form compute target field: " + targetField);
        triggerFields = triggerFields == null ? List.of() : List.copyOf(triggerFields);
    }
}
