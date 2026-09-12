package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

public record WebQueryCriteria(WebQueryCriteriaNodeKind kind,
                               WebQueryGroupOperator operator,
                               @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
                                       property = "kind", visible = true)
                               @JsonSubTypes({
                                       @JsonSubTypes.Type(value = WebQueryCriteria.class, name = "GROUP"),
                                       @JsonSubTypes.Type(value = WebQueryCondition.class, name = "CONDITION")
                               })
                               List<WebQueryCriteriaNode> children) implements WebQueryCriteriaNode {
    public WebQueryCriteria {
        if (kind != WebQueryCriteriaNodeKind.GROUP) {
            throw new IllegalArgumentException("web query criteria group kind must be GROUP");
        }
        operator = operator == null ? WebQueryGroupOperator.AND : operator;
        children = children == null ? List.of() : List.copyOf(children);
        if (children.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("web query criteria children must not contain null");
        }
    }

    public boolean isEmpty() {
        return children.isEmpty();
    }

    public static WebQueryCriteria group(WebQueryGroupOperator operator,
                                         List<? extends WebQueryCriteriaNode> children) {
        return new WebQueryCriteria(WebQueryCriteriaNodeKind.GROUP, operator,
                children == null ? List.of() : List.copyOf(children));
    }
}
