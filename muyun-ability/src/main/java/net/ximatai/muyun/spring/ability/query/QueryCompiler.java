package net.ximatai.muyun.spring.ability.query;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.time.BusinessTimeContext;
import net.ximatai.muyun.spring.common.time.BusinessTimeRange;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class QueryCompiler {
    private final QueryDescriptor descriptor;
    private final PlatformTimeService timeService;

    public QueryCompiler(QueryDescriptor descriptor) {
        this(descriptor, new PlatformTimeService());
    }

    public QueryCompiler(QueryDescriptor descriptor, PlatformTimeService timeService) {
        this.descriptor = descriptor;
        this.timeService = timeService == null ? new PlatformTimeService() : timeService;
    }

    public Criteria criteria(QueryRequest request) {
        Criteria criteria = Criteria.of();
        if (request == null) {
            return criteria;
        }
        rejectUnsupportedSurfaces(request);
        appendConditions(criteria, request.conditions(), QueryGroupOperator.AND);
        appendCriteria(criteria, request.criteria(), QueryGroupOperator.AND);
        appendQuickSearch(criteria, request);
        appendExternalCriteria(criteria, request.externalQueryValues());
        return criteria;
    }

    public Sort[] sorts(QueryRequest request) {
        if (request == null || request.sorts().isEmpty()) {
            return descriptor.defaultSorts();
        }
        return request.sorts().stream().map(this::sort).toArray(Sort[]::new);
    }

    public static Criteria compileCriteriaTree(QueryCriteria criteria,
                                               Function<QueryCondition, Criteria> conditionCompiler) {
        if (criteria == null || criteria.isEmpty()) {
            return Criteria.of();
        }
        Criteria compiled = Criteria.of();
        for (QueryCondition condition : criteria.conditions()) {
            appendGroup(compiled, criteria.operator(), conditionCompiler.apply(condition));
        }
        for (QueryCriteria group : criteria.groups()) {
            appendGroup(compiled, criteria.operator(), compileCriteriaTree(group, conditionCompiler));
        }
        return compiled;
    }

    private void rejectUnsupportedSurfaces(QueryRequest request) {
        if (!request.queryForm().isEmpty()) {
            throw new IllegalArgumentException("query form is not supported by " + descriptor.scopeName());
        }
        if (hasText(request.uiConfigId())) {
            throw new IllegalArgumentException("query ui config is not supported by " + descriptor.scopeName());
        }
        if (hasText(request.queryTemplateId())) {
            throw new IllegalArgumentException("query template is not supported by " + descriptor.scopeName());
        }
        if (request.navigationSession() || hasText(request.navigationQueryKey())) {
            throw new IllegalArgumentException("query navigation is not supported by " + descriptor.scopeName());
        }
    }

    private void appendConditions(Criteria target,
                                  Collection<QueryCondition> conditions,
                                  QueryGroupOperator operator) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        Criteria group = Criteria.of();
        for (QueryCondition condition : conditions) {
            appendGroup(group, operator, condition(condition));
        }
        if (!group.isEmpty()) {
            target.andGroup(group.getRoot());
        }
    }

    private void appendCriteria(Criteria target, QueryCriteria criteria, QueryGroupOperator parentOperator) {
        appendGroup(target, parentOperator, compileCriteriaTree(criteria, this::condition));
    }

    private void appendQuickSearch(Criteria target, QueryRequest request) {
        if (!hasText(request.quickSearch()) && request.quickSearchFields().isEmpty()) {
            return;
        }
        String keyword = requireText(request.quickSearch(), "quick search");
        List<String> fields = request.quickSearchFields().isEmpty()
                ? descriptor.quickSearchFields().stream().map(QueryField::fieldName).toList()
                : request.quickSearchFields();
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("quick search fields are not configured by " + descriptor.scopeName());
        }
        Criteria quick = Criteria.of();
        String pattern = QueryLikePattern.containsLiteral(keyword);
        for (String fieldName : fields) {
            QueryField field = requireField(fieldName, "quick search field");
            if (!field.quickSearch()) {
                throw new IllegalArgumentException("quick search field is not supported by "
                        + descriptor.scopeName() + ": " + fieldName);
            }
            quick.orLike(fieldName, pattern);
        }
        target.andGroup(quick.getRoot());
    }

    private void appendExternalCriteria(Criteria target, Map<String, Object> externalValues) {
        if (externalValues == null || externalValues.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : externalValues.entrySet()) {
            Function<Object, Criteria> resolver = descriptor.externalCriteriaResolver(entry.getKey());
            if (resolver == null) {
                throw new IllegalArgumentException("external query value is not supported by "
                        + descriptor.scopeName() + ": " + entry.getKey());
            }
            Criteria criteria = resolver.apply(entry.getValue());
            if (criteria != null && !criteria.isEmpty()) {
                target.andGroup(criteria.getRoot());
            }
        }
    }

    private Criteria condition(QueryCondition condition) {
        String fieldName = requireText(condition.fieldName(), "query field");
        QueryField field = requireField(fieldName, "query field");
        QueryOperator operator = condition.operator() == null ? field.defaultOperator() : condition.operator();
        if (!field.operators().contains(operator)) {
            throw new IllegalArgumentException("query operator is not supported by "
                    + descriptor.scopeName() + ": " + fieldName + "." + operator);
        }
        Criteria criteria = Criteria.of();
        appendLeaf(criteria, field, operator, condition);
        return criteria;
    }

    private void appendLeaf(Criteria criteria, QueryField field, QueryOperator operator, QueryCondition condition) {
        String fieldName = field.fieldName();
        List<Object> values = condition.values();
        switch (operator) {
            case EQ -> criteria.eq(fieldName, singleValue(field, operator, values));
            case NOT_EQUAL -> criteria.ne(fieldName, singleValue(field, operator, values));
            case LIKE -> criteria.like(fieldName, String.valueOf(singleValue(field, operator, values)));
            case IN -> criteria.in(fieldName, listValues(field, operator, values));
            case NOT_IN -> criteria.notIn(fieldName, listValues(field, operator, values));
            case GT -> criteria.gt(fieldName, singleValue(field, operator, values));
            case GTE -> criteria.gte(fieldName, singleValue(field, operator, values));
            case LT -> criteria.lt(fieldName, singleValue(field, operator, values));
            case LTE -> criteria.lte(fieldName, singleValue(field, operator, values));
            case BETWEEN -> appendBetween(criteria, field, operator, condition);
            case NULL -> criteria.isNull(fieldName);
            case NOT_NULL -> criteria.isNotNull(fieldName);
            case CONTAINS -> criteria.contains(fieldName, singleValue(field, operator, values));
            case CONTAINS_ANY -> criteria.containsAny(fieldName, listValues(field, operator, values));
            case CONTAINS_ALL -> criteria.containsAll(fieldName, listValues(field, operator, values));
            case EMPTY -> criteria.isEmpty(fieldName);
            case NOT_EMPTY -> criteria.isNotEmpty(fieldName);
        }
    }

    private void appendBetween(Criteria criteria, QueryField field, QueryOperator operator, QueryCondition condition) {
        List<Object> rawValues = condition.values();
        String fieldName = field.fieldName();
        if (rawValues.size() != 2) {
            throw new IllegalArgumentException("query operator requires exactly two values: "
                    + fieldName + "." + operator);
        }
        if (field.valueType() == QueryValueType.INSTANT
                && PlatformTimeService.isLocalDateValue(rawValues.get(0))
                && PlatformTimeService.isLocalDateValue(rawValues.get(1))) {
            BusinessTimeRange range = timeService.localDateClosedRangeToInstantRange(
                    rawValues.get(0),
                    rawValues.get(1),
                    timeContext(condition.timeZone())
            );
            criteria.gte(fieldName, range.startInclusive());
            criteria.lt(fieldName, range.endExclusive());
            return;
        }
        List<?> range = listValues(field, operator, rawValues);
        if (range.size() != 2) {
            throw new IllegalArgumentException("query operator requires exactly two values: "
                    + fieldName + "." + operator);
        }
        criteria.between(fieldName, range.get(0), range.get(1));
    }

    private BusinessTimeContext timeContext(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) {
            return BusinessTimeContext.empty();
        }
        return BusinessTimeContext.ofZone(PlatformTimeService.requireIanaZoneId(timeZone));
    }

    private Sort sort(QuerySort sort) {
        QueryField field = requireField(sort.field(), "query sort");
        if (!field.sortable()) {
            throw new IllegalArgumentException("query sort is not supported by "
                    + descriptor.scopeName() + ": " + sort.field());
        }
        return sort.desc() ? Sort.desc(sort.field()) : Sort.asc(sort.field());
    }

    private static void appendGroup(Criteria target, QueryGroupOperator operator, Criteria child) {
        if (child == null || child.isEmpty()) {
            return;
        }
        if (target.isEmpty() || operator != QueryGroupOperator.OR) {
            target.andGroup(child.getRoot());
            return;
        }
        target.orGroup(child.getRoot());
    }

    private QueryField requireField(String fieldName, String label) {
        String normalized = requireText(fieldName, label);
        QueryField field = descriptor.field(normalized);
        if (field == null) {
            throw new IllegalArgumentException(label + " is not supported by "
                    + descriptor.scopeName() + ": " + normalized);
        }
        return field;
    }

    private Object singleValue(QueryField field, QueryOperator operator, List<Object> values) {
        List<?> list = listValues(field, operator, values);
        if (list.size() != 1) {
            throw new IllegalArgumentException("query operator requires exactly one value: "
                    + field.fieldName() + "." + operator);
        }
        return list.getFirst();
    }

    private List<?> listValues(QueryField field, QueryOperator operator, List<Object> values) {
        List<?> list = values == null ? List.of() : values.stream()
                .filter(value -> value != null && (!(value instanceof String text) || !text.isBlank()))
                .map(field.valueType()::normalize)
                .toList();
        if (list.isEmpty()
                && operator != QueryOperator.NULL
                && operator != QueryOperator.NOT_NULL
                && operator != QueryOperator.EMPTY
                && operator != QueryOperator.NOT_EMPTY) {
            throw new IllegalArgumentException("query operator requires non-empty values: "
                    + field.fieldName() + "." + operator);
        }
        return list;
    }

    private String requireText(String value, String label) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
