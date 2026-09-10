package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** Stable editor help for functions already available to FORM_COMPUTE; this does not add syntax. */
public record BusinessRuleFunction(String name, String category, String title, String description,
                                   List<BusinessRuleFunctionParameter> parameters, String returnType, String example) {
    public BusinessRuleFunction {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }
}
