package net.ximatai.muyun.spring.web.query;

import net.ximatai.muyun.spring.ability.query.QueryCondition;
import net.ximatai.muyun.spring.ability.query.QueryCriteria;
import net.ximatai.muyun.spring.ability.query.QueryCriteriaNode;
import net.ximatai.muyun.spring.ability.query.QueryGroupOperator;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QuerySort;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryCriteria;
import net.ximatai.muyun.spring.web.WebQueryCriteriaNode;
import net.ximatai.muyun.spring.web.WebQueryGroupOperator;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.WebSort;

import java.util.List;
public final class WebQueryRequests {
    private WebQueryRequests() {
    }

    public static QueryRequest from(WebQueryRequest request) {
        if (request == null) {
            return QueryRequest.empty();
        }
        return new QueryRequest(
                conditions(request.conditions()),
                criteria(request.criteria()),
                request.queryForm(),
                sorts(request.sorts()),
                request.uiConfigId(),
                request.queryTemplateId(),
                request.externalQueryValues(),
                request.quickSearch(),
                request.quickSearchFields(),
                request.navigationSessionEnabled(),
                request.navigationQueryKey()
        );
    }

    public static QueryCriteria criteria(WebQueryCriteria criteria) {
        if (criteria == null) {
            return null;
        }
        QueryCriteria mapped = new QueryCriteria(
                groupOperator(criteria.operator()),
                criteria.children().stream().map(WebQueryRequests::criteriaNode).toList()
        );
        QueryCriteria.validate(mapped);
        return mapped;
    }

    private static QueryCriteriaNode criteriaNode(WebQueryCriteriaNode node) {
        if (node == null) {
            throw new IllegalArgumentException("query criteria child must not be null");
        }
        return switch (node) {
            case WebQueryCondition condition -> condition(condition);
            case WebQueryCriteria group -> criteria(group);
        };
    }

    public static List<QueryCondition> conditions(List<WebQueryCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        return conditions.stream()
                .map(WebQueryRequests::condition)
                .toList();
    }

    public static QueryCondition condition(WebQueryCondition condition) {
        return new QueryCondition(condition.fieldName(),
                operator(condition.operator()),
                condition.values(),
                condition.timeZone());
    }

    public static List<QuerySort> sorts(List<WebSort> sorts) {
        if (sorts == null || sorts.isEmpty()) {
            return List.of();
        }
        return sorts.stream()
                .map(sort -> new QuerySort(sort.field(), sort.desc()))
                .toList();
    }

    private static QueryGroupOperator groupOperator(WebQueryGroupOperator operator) {
        return operator == WebQueryGroupOperator.OR ? QueryGroupOperator.OR : QueryGroupOperator.AND;
    }

    private static QueryOperator operator(String operator) {
        return operator == null || operator.isBlank() ? null : QueryOperator.from(operator);
    }
}
