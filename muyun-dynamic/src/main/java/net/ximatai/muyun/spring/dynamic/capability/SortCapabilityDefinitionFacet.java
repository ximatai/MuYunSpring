package net.ximatai.muyun.spring.dynamic.capability;

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
            throw new ModuleDefinitionException("sortable field requires SORT capability: " + entity.alias());
        }
        if (!entity.supports(EntityCapability.SORT) && !entity.sortPartitionFields().isEmpty()) {
            throw new ModuleDefinitionException("sort partition requires SORT capability: " + entity.alias());
        }
    }

    public void validate(EntityDefinition entity) {
        FieldDefinition sortField = entity.fields().stream()
                .filter(FieldDefinition::isSortable)
                .findFirst()
                .orElse(null);
        if (sortField == null) {
            throw new ModuleDefinitionException("SORT capability requires standard field sortOrder: " + entity.alias());
        }
        if (!PlatformAbilityFields.SORT_FIELD.equals(sortField.fieldName())
                || !PlatformAbilityFields.SORT_COLUMN.equals(sortField.columnName())
                || sortField.type() != FieldType.INTEGER) {
            throw new ModuleDefinitionException("SORT capability requires standard field sortOrder/sort_order: "
                    + entity.alias());
        }
        Set<String> declared = new HashSet<>();
        for (String fieldName : entity.sortPartitionFields()) {
            if (fieldName == null || fieldName.isBlank()) {
                throw new ModuleDefinitionException("sort partition field must not be blank: " + entity.alias());
            }
            if (!declared.add(fieldName)) {
                throw new ModuleDefinitionException("duplicate sort partition field: " + entity.alias() + "." + fieldName);
            }
            boolean physical = entity.fields().stream().anyMatch(field -> field.fieldName().equals(fieldName)
                    && field.isPhysical());
            if (!physical) {
                throw new ModuleDefinitionException("sort partition requires physical field: "
                        + entity.alias() + "." + fieldName);
            }
        }
    }
}
