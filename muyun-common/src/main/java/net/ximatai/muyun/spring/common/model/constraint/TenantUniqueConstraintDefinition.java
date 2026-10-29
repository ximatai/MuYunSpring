package net.ximatai.muyun.spring.common.model.constraint;

import java.util.LinkedHashSet;
import java.util.List;

public record TenantUniqueConstraintDefinition(List<String> fieldNames, String message,
                                                String indexName, List<IndexCondition> conditions) {
    public TenantUniqueConstraintDefinition(List<String> fieldNames, String message) {
        this(fieldNames, message, null, List.of());
    }

    public record Identity(List<String> fields, List<IndexCondition> conditions) { }
    public Identity identity() { return new Identity(fieldNames, conditions); }

    public TenantUniqueConstraintDefinition {
        fieldNames = fieldNames == null ? List.of() : fieldNames.stream()
                .filter(fieldName -> fieldName != null && !fieldName.isBlank())
                .map(String::trim)
                .toList();
        if (fieldNames.isEmpty()) {
            throw new IllegalArgumentException("tenant unique constraint requires fields");
        }
        if (new LinkedHashSet<>(fieldNames).size() != fieldNames.size()) {
            throw new IllegalArgumentException("tenant unique constraint fields must be distinct");
        }
        message = message == null ? "" : message.trim();
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        if (!conditions.isEmpty()) {
            var validated = new ConditionalIndexDefinition(indexName, fieldNames, true, conditions, message);
            conditions = validated.conditions();
        } else if (indexName != null && !indexName.isBlank()) {
            throw new IllegalArgumentException("named unique constraints require conditions; use ConditionalIndexDefinition");
        }
    }

    public String violationMessage() {
        return message.isBlank()
                ? "tenant unique constraint violated: " + String.join(", ", fieldNames)
                : message;
    }
}
