package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.query.QueryCompiler;
import net.ximatai.muyun.spring.ability.query.QueryCondition;
import net.ximatai.muyun.spring.ability.query.QueryCriteria;
import net.ximatai.muyun.spring.web.WebPageRequest;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryCriteria;
import net.ximatai.muyun.spring.web.WebSort;
import net.ximatai.muyun.spring.web.query.WebQueryRequests;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

final class DynamicWebQueryMapper {
    private DynamicWebQueryMapper() {
    }

    static List<DynamicQueryCondition> queryConditions(Collection<WebQueryCondition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return List.of();
        }
        List<QueryCondition> mapped = WebQueryRequests.conditions(List.copyOf(conditions));
        QueryCriteria.validateConditions(mapped);
        return mapped.stream()
                .map(DynamicWebQueryMapper::queryConditionFromQuery)
                .toList();
    }

    static PageRequest page(WebPageRequest request) {
        WebPageRequest normalized = request == null ? WebPageRequest.DEFAULT : request;
        return PageRequest.of(normalized.pageNum(), normalized.pageSize());
    }

    static Sort[] sorts(List<WebSort> sorts) {
        if (sorts == null || sorts.isEmpty()) {
            return new Sort[0];
        }
        return sorts.stream()
                .map(sort -> sort.desc() ? Sort.desc(sort.field()) : Sort.asc(sort.field()))
                .toArray(Sort[]::new);
    }

    static Criteria queryCriteria(WebQueryCriteria criteria,
                                  Function<List<DynamicQueryCondition>, Criteria> conditionCompiler) {
        if (criteria == null || criteria.isEmpty()) {
            return Criteria.of();
        }
        return QueryCompiler.compileCriteriaTree(WebQueryRequests.criteria(criteria),
                condition -> conditionCompiler.apply(List.of(queryConditionFromQuery(condition))));
    }

    private static DynamicQueryCondition queryConditionFromQuery(QueryCondition condition) {
        DynamicQueryOperator operator = condition.operator() == null
                ? null
                : DynamicQueryOperator.valueOf(condition.operator().name());
        return new DynamicQueryCondition(condition.fieldName(), operator, condition.values(), condition.timeZone());
    }
}
