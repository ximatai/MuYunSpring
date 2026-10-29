package net.ximatai.muyun.spring.common.model.constraint;

import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.common.schema.PlatformDatabaseValueConverter;

import java.util.List;

/** A typed AND predicate shared by schema generation and runtime uniqueness checks. */
public record IndexCondition(String field, Operator operator, List<String> values) {
    public enum Operator { IS_NULL, IS_NOT_NULL, IS_TRUE, IS_FALSE, IN_CODES }

    public IndexCondition {
        if (!SchemaBuildRules.isSafeIdentifier(field)) throw new IllegalArgumentException("invalid index condition field");
        java.util.Objects.requireNonNull(operator, "index condition operator");
        values = values == null ? List.of() : List.copyOf(values);
        if (operator == Operator.IN_CODES) {
            if (values.isEmpty() || values.size() > 64 || values.stream().anyMatch(value ->
                    value == null || !value.matches("[\\p{L}\\p{N}_.:-]{1,128}"))) {
                throw new IllegalArgumentException("index condition requires 1–64 literal codes (letters, digits, _, ., :, -)");
            }
            values = values.stream().distinct().sorted().toList();
        } else if (!values.isEmpty()) throw new IllegalArgumentException("only IN_CODES accepts values");
    }

    public IndexCondition(String field, Operator operator) { this(field, operator, List.of()); }

    public void apply(Criteria criteria) {
        switch (operator) {
            case IS_NULL -> criteria.isNull(field);
            case IS_NOT_NULL -> criteria.isNotNull(field);
            case IS_TRUE -> criteria.eq(field, true);
            case IS_FALSE -> criteria.eq(field, false);
            case IN_CODES -> criteria.in(field, values);
        }
    }

    public boolean matches(Object value) {
        return switch (operator) {
            case IS_NULL -> value == null;
            case IS_NOT_NULL -> value != null;
            case IS_TRUE -> Boolean.TRUE.equals(value);
            case IS_FALSE -> Boolean.FALSE.equals(value);
            case IN_CODES -> value != null && values.contains(String.valueOf(new PlatformDatabaseValueConverter().toDatabaseValue(value)));
        };
    }
}
