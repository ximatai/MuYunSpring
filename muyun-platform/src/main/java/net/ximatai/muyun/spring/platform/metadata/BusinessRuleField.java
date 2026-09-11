package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

/** One formula-readable field and the aggregate operations that its value type permits. */
public record BusinessRuleField(String fieldName, String title, String fieldSpecAlias, String valueType,
                                List<String> aggregateFunctions) {
    public BusinessRuleField {
        aggregateFunctions = aggregateFunctions == null ? List.of() : List.copyOf(aggregateFunctions);
    }

    public BusinessRuleField(String fieldName, String title, String fieldSpecAlias, String valueType) {
        this(fieldName, title, fieldSpecAlias, valueType, List.of());
    }
}
