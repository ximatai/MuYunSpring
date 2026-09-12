package net.ximatai.muyun.spring.ability.query;

import java.util.List;

public record QueryCondition(String fieldName,
                             QueryOperator operator,
                             List<Object> values,
                             String timeZone) implements QueryCriteriaNode {
    public QueryCondition {
        values = values == null ? List.of() : List.copyOf(values);
        timeZone = timeZone == null || timeZone.isBlank() ? null : timeZone.trim();
    }
}
