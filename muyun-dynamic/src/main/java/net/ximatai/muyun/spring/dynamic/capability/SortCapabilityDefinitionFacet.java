package net.ximatai.muyun.spring.dynamic.capability;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;

import java.util.HashSet;
import java.util.Set;

/** Dynamic field and partition contract owned by SORT. */
public final class SortCapabilityDefinitionFacet {
    public void validateReferences(EntityDefinition entity) {
        if (!entity.supports(EntityCapability.SORT) && entity.fields().stream().anyMatch(FieldDefinition::isSortable)) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.sortable-field-requires-sort-capability",
                    "sortable field requires SORT capability: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (!entity.supports(EntityCapability.SORT) && !entity.sortPartitionFields().isEmpty()) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.sort-partition-requires-sort-capability",
                    "sort partition requires SORT capability: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
    }

    public void validate(EntityDefinition entity) {
        FieldDefinition sortField = entity.fields().stream()
                .filter(FieldDefinition::isSortable)
                .findFirst()
                .orElse(null);
        if (sortField == null) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.sort-capability-requires-standard-field-sortorder",
                    "SORT capability requires standard field sortOrder: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        if (!PlatformAbilityFields.SORT_FIELD.equals(sortField.fieldName())
                || !PlatformAbilityFields.SORT_COLUMN.equals(sortField.columnName())
                || sortField.type() != FieldType.INTEGER) {
            throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.sort-capability-requires-standard-field-sortorder-sort-order",
                    "SORT capability requires standard field sortOrder/sort_order: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
        }
        Set<String> declared = new HashSet<>();
        for (String fieldName : entity.sortPartitionFields()) {
            if (fieldName == null || fieldName.isBlank()) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.sort-partition-field-must-not-be-blank",
                    "sort partition field must not be blank: {alias}", Map.of("alias", String.valueOf(entity.alias()))));
            }
            if (!declared.add(fieldName)) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.duplicate-sort-partition-field",
                    "duplicate sort partition field: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName))));
            }
            boolean physical = entity.fields().stream().anyMatch(field -> field.fieldName().equals(fieldName)
                    && field.isPhysical());
            if (!physical) {
                throw new ModuleDefinitionException(DiagnosticText.code("dynamic.declaration.diagnostic.sort-partition-requires-physical-field",
                    "sort partition requires physical field: {alias}.{fieldName}", Map.of("alias", String.valueOf(entity.alias()), "fieldName", String.valueOf(fieldName))));
            }
        }
    }
}
