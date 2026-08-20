package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.time.BusinessTimeContext;
import net.ximatai.muyun.spring.common.time.PlatformTimeService;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicFieldValueSupport;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DynamicQueryCriteriaBuilder {
    private final EntityDefinition entity;
    private final Map<String, FieldDefinition> fields;
    private final PlatformTimeService timeService;
    private final BusinessTimeContext timeContext;

    public DynamicQueryCriteriaBuilder(EntityDefinition entity) {
        this(entity, new PlatformTimeService(), BusinessTimeContext.empty());
    }

    public DynamicQueryCriteriaBuilder(EntityDefinition entity,
                                       PlatformTimeService timeService,
                                       BusinessTimeContext timeContext) {
        this.entity = entity;
        this.fields = entity.fields().stream()
                .collect(Collectors.toUnmodifiableMap(FieldDefinition::fieldName, Function.identity()));
        this.timeService = timeService == null ? new PlatformTimeService() : timeService;
        this.timeContext = timeContext == null ? BusinessTimeContext.empty() : timeContext;
    }

    public Criteria build(Collection<DynamicQueryCondition> conditions) {
        Criteria criteria = Criteria.of();
        if (conditions == null || conditions.isEmpty()) {
            return criteria;
        }
        for (DynamicQueryCondition condition : conditions) {
            append(criteria, condition);
        }
        return criteria;
    }

    private void append(Criteria criteria, DynamicQueryCondition condition) {
        FieldDefinition field = field(condition.fieldName());
        if (!field.queryDefinition().queryable()) {
            throw new ModuleDefinitionException("field is not queryable: " + entity.alias() + "." + condition.fieldName());
        }
        DynamicQueryOperator operator = condition.operator() == null
                ? field.queryDefinition().defaultOperator()
                : condition.operator();
        if (!field.queryDefinition().operators().contains(operator)) {
            throw new ModuleDefinitionException("query operator is not allowed: " + condition.fieldName() + "." + operator);
        }
        List<?> values = condition.values();
        switch (operator) {
            case EQ -> criteria.eq(field.fieldName(), singleValue(field, condition, values));
            case NOT_EQUAL -> criteria.ne(field.fieldName(), singleValue(field, condition, values));
            case LIKE -> criteria.like(field.fieldName(), String.valueOf(singleValue(field, condition, values)));
            case IN -> criteria.in(field.fieldName(), listValues(field, condition, values));
            case NOT_IN -> criteria.notIn(field.fieldName(), listValues(field, condition, values));
            case BETWEEN -> appendBetween(criteria, field, condition, values);
            case GT -> criteria.gt(field.fieldName(), singleValue(field, condition, values));
            case GTE -> criteria.gte(field.fieldName(), singleValue(field, condition, values));
            case LT -> criteria.lt(field.fieldName(), singleValue(field, condition, values));
            case LTE -> criteria.lte(field.fieldName(), singleValue(field, condition, values));
            case NULL -> criteria.isNull(field.fieldName());
            case NOT_NULL -> criteria.isNotNull(field.fieldName());
            case CONTAINS -> criteria.contains(field.fieldName(), singleValue(field, condition, values));
            case CONTAINS_ANY -> criteria.containsAny(field.fieldName(), listValues(field, condition, values));
            case CONTAINS_ALL -> criteria.containsAll(field.fieldName(), listValues(field, condition, values));
            case EMPTY -> criteria.isEmpty(field.fieldName());
            case NOT_EMPTY -> criteria.isNotEmpty(field.fieldName());
        }
    }

    private FieldDefinition field(String fieldName) {
        FieldDefinition field = fields.get(fieldName);
        if (field == null) {
            throw new ModuleDefinitionException("unknown query field: " + entity.alias() + "." + fieldName);
        }
        return field;
    }

    private void appendBetween(Criteria criteria,
                               FieldDefinition field,
                               DynamicQueryCondition condition,
                               List<?> values) {
        if (values.size() != 2) {
            throw new ModuleDefinitionException("query operator requires exactly two values: "
                    + condition.fieldName() + "." + condition.operator());
        }
        try {
            if (DynamicTemporalRangeCriteriaSupport.appendInstantLocalDateRange(
                    criteria,
                    field.fieldName(),
                    field.type(),
                    values.get(0),
                    values.get(1),
                    condition.timeZone(),
                    timeService,
                    timeContext
            )) {
                return;
            }
        } catch (RuntimeException e) {
            if (condition.timeZone() != null && !condition.timeZone().isBlank()) {
                try {
                    PlatformTimeService.requireIanaZoneId(condition.timeZone());
                } catch (RuntimeException ignored) {
                    throw new ModuleDefinitionException("invalid query timeZone: "
                            + condition.fieldName() + "." + condition.timeZone(), e);
                }
            }
            try {
                PlatformTimeService.requireLocalDate(values.get(0), "startInclusive");
                PlatformTimeService.requireLocalDate(values.get(1), "endInclusive");
            } catch (RuntimeException ignored) {
                throw new ModuleDefinitionException("invalid query value type: "
                        + condition.fieldName() + "." + condition.operator(), e);
            }
            throw new ModuleDefinitionException("invalid query date range: "
                    + condition.fieldName() + "." + condition.operator(), e);
        }
        criteria.between(field.fieldName(),
                rangeValue(field, condition, values, 0),
                rangeValue(field, condition, values, 1));
    }

    private Object singleValue(FieldDefinition field, DynamicQueryCondition condition, List<?> values) {
        if (values.size() != 1) {
            throw new ModuleDefinitionException("query operator requires exactly one value: "
                    + condition.fieldName() + "." + condition.operator());
        }
        return normalizedValue(field, condition, values.getFirst());
    }

    private List<?> listValues(FieldDefinition field, DynamicQueryCondition condition, List<?> values) {
        if (values.isEmpty()) {
            throw new ModuleDefinitionException("query operator requires at least one value: "
                    + condition.fieldName() + "." + condition.operator());
        }
        return values.stream()
                .map(value -> normalizedValue(field, condition, value))
                .toList();
    }

    private Object rangeValue(FieldDefinition field, DynamicQueryCondition condition, List<?> values, int index) {
        if (values.size() != 2) {
            throw new ModuleDefinitionException("query operator requires exactly two values: "
                    + condition.fieldName() + "." + condition.operator());
        }
        return normalizedValue(field, condition, values.get(index));
    }

    private Object normalizedValue(FieldDefinition field, DynamicQueryCondition condition, Object value) {
        if (value == null) {
            throw new ModuleDefinitionException("null query value is not supported: "
                    + condition.fieldName() + "." + condition.operator());
        }
        try {
            return DynamicFieldValueSupport.normalize(field.type(), queryValue(field.type(), value));
        } catch (RuntimeException e) {
            throw new ModuleDefinitionException("invalid query value type: "
                    + condition.fieldName() + "." + condition.operator(), e);
        }
    }

    /** Query conditions are adapter inputs, rather than DynamicRecord value assignments. */
    private Object queryValue(FieldType type, Object value) {
        if (type == FieldType.LONG && value instanceof String text) {
            return Long.valueOf(text);
        }
        if (type == FieldType.LONG && value instanceof Number number && !(number instanceof Long)) {
            return new BigDecimal(number.toString()).longValueExact();
        }
        if (type == FieldType.DECIMAL && value instanceof String text) {
            return new BigDecimal(text);
        }
        if (type == FieldType.DECIMAL && value instanceof Number number && !(number instanceof BigDecimal)) {
            return new BigDecimal(number.toString());
        }
        return value;
    }

}
