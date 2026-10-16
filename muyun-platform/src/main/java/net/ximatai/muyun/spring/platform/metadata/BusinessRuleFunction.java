package net.ximatai.muyun.spring.platform.metadata;

import com.fasterxml.jackson.annotation.JsonIgnore;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import java.util.List;
import java.util.Map;

/** Stable editor help for functions already available to FORM_COMPUTE; this does not add syntax. */
public record BusinessRuleFunction(String name, String category, String title, String description,
        List<BusinessRuleFunctionParameter> parameters, String returnType, String example,
        @JsonIgnore Map<String, TextReference> textSources) {
    public BusinessRuleFunction {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        textSources = textSources == null ? Map.of() : Map.copyOf(textSources);
    }

    public BusinessRuleFunction(String name, String category, String title, String description,
            List<BusinessRuleFunctionParameter> parameters, String returnType, String example) {
        this(name, category, title, description, parameters, returnType, example, Map.of());
    }

    /** Only the platform-owned catalogue producer declares deployed CODE resources. */
    public static BusinessRuleFunction declared(String sourceKey, String name, String category,
            String title, String description, List<BusinessRuleFunctionParameter> parameters,
            String returnType, String example) {
        return new BusinessRuleFunction(name, category, title, description,
                parameters.stream().map(parameter -> new BusinessRuleFunctionParameter(parameter.name(),
                        parameter.description(), TextReference.code(sourceKey + ".parameter." + parameter.name(),
                                parameter.description()))).toList(),
                returnType, example, Map.of(
                        "category", TextReference.code(sourceKey + ".category", category),
                        "title", TextReference.code(sourceKey + ".title", title),
                        "description", TextReference.code(sourceKey + ".description", description)));
    }
}
