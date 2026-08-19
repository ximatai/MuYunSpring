package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.model.contract.CodeTitleEnum;

import java.util.EnumSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public enum DynamicQueryOperator implements CodeTitleEnum {
    EQ,
    NOT_EQUAL,
    LIKE,
    IN,
    NOT_IN,
    BETWEEN,
    GT,
    GTE,
    LT,
    LTE,
    NULL,
    NOT_NULL,
    CONTAINS,
    CONTAINS_ANY,
    CONTAINS_ALL,
    EMPTY,
    NOT_EMPTY;

    @Override
    public String getCode() {
        return name();
    }

    @Override
    public String getTitle() {
        return name();
    }

    public static Set<DynamicQueryOperator> defaultOperators(FieldType fieldType) {
        return switch (fieldType) {
            case STRING, TEXT -> EnumSet.of(EQ, NOT_EQUAL, LIKE, IN, NOT_IN, NULL, NOT_NULL);
            case BOOLEAN -> EnumSet.of(EQ, NOT_EQUAL, NULL, NOT_NULL);
            case INTEGER, LONG, DECIMAL, TIMESTAMP, ZONED_TIMESTAMP, DATE ->
                    EnumSet.of(EQ, NOT_EQUAL, IN, NOT_IN, BETWEEN, GT, GTE, LT, LTE, NULL, NOT_NULL);
            case JSON -> EnumSet.of(EQ, NOT_EQUAL, NULL, NOT_NULL, CONTAINS, CONTAINS_ANY, CONTAINS_ALL,
                    EMPTY, NOT_EMPTY);
        };
    }

    public static DynamicQueryOperator defaultOperator(FieldType fieldType) {
        return switch (fieldType) {
            case STRING, TEXT -> LIKE;
            default -> EQ;
        };
    }

    public boolean supports(FieldType fieldType) {
        return switch (this) {
            case CONTAINS, CONTAINS_ANY, CONTAINS_ALL, EMPTY, NOT_EMPTY -> fieldType == FieldType.JSON;
            default -> defaultOperators(fieldType).contains(this);
        };
    }

    public static List<DynamicQueryOperator> ordered(Set<DynamicQueryOperator> operators) {
        if (operators == null || operators.isEmpty()) {
            return List.of();
        }
        return List.of(values()).stream()
                .filter(operators::contains)
                .toList();
    }

    public static String format(Set<DynamicQueryOperator> operators) {
        return String.join(",", ordered(operators).stream().map(DynamicQueryOperator::name).toList());
    }

    public static Set<String> names(Set<DynamicQueryOperator> operators) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        ordered(operators).forEach(operator -> names.add(operator.name()));
        return Collections.unmodifiableSet(names);
    }

    public static Set<DynamicQueryOperator> parseNames(Set<String> operators) {
        if (operators == null || operators.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<DynamicQueryOperator> parsed = new LinkedHashSet<>();
        for (String value : operators) {
            if (value != null && !value.isBlank()) {
                parsed.add(DynamicQueryOperator.valueOf(value.trim()));
            }
        }
        return Collections.unmodifiableSet(parsed);
    }
}
