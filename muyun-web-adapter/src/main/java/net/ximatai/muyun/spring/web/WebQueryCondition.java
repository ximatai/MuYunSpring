package net.ximatai.muyun.spring.web;

import java.util.List;

public record WebQueryCondition(WebQueryCriteriaNodeKind kind,
                                String fieldName,
                                String operator,
                                List<Object> values,
                                String timeZone) implements WebQueryCriteriaNode {
    public WebQueryCondition(String fieldName, String operator, List<Object> values) {
        this(fieldName, operator, values, null);
    }

    public WebQueryCondition(String fieldName, String operator, List<Object> values, String timeZone) {
        this(WebQueryCriteriaNodeKind.CONDITION, fieldName, operator, values, timeZone);
    }

    public WebQueryCondition {
        kind = kind == null ? WebQueryCriteriaNodeKind.CONDITION : kind;
        if (kind != WebQueryCriteriaNodeKind.CONDITION) {
            throw new IllegalArgumentException("web query condition kind must be CONDITION");
        }
        values = values == null ? List.of() : List.copyOf(values);
        timeZone = timeZone == null || timeZone.isBlank() ? null : timeZone.trim();
    }
}
