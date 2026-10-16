package net.ximatai.muyun.spring.dynamic.runtime.mapping;

import net.ximatai.muyun.database.core.orm.RuntimeColumnMapper;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldCompanionRules;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class DynamicRecordMapping implements RuntimeColumnMapper {
    private final Map<String, String> columns = new HashMap<>();
    private final Map<String, String> fields = new HashMap<>();
    private final Set<String> protectedStorageFields = new HashSet<>();

    public DynamicRecordMapping(EntityDefinition entity) {
        put(StandardEntitySchema.ID_FIELD, StandardEntitySchema.ID_COLUMN);
        put(StandardEntitySchema.TENANT_ID_FIELD, StandardEntitySchema.TENANT_ID_COLUMN);
        put(StandardEntitySchema.VERSION_FIELD, StandardEntitySchema.VERSION_COLUMN);
        put(StandardEntitySchema.DELETED_FIELD, StandardEntitySchema.DELETED_COLUMN);
        put(StandardEntitySchema.DELETED_AT_FIELD, StandardEntitySchema.DELETED_AT_COLUMN);
        put(StandardEntitySchema.DELETED_BY_FIELD, StandardEntitySchema.DELETED_BY_COLUMN);
        put(StandardEntitySchema.CREATED_BY_FIELD, StandardEntitySchema.CREATED_BY_COLUMN);
        put(StandardEntitySchema.CREATED_AT_FIELD, StandardEntitySchema.CREATED_AT_COLUMN);
        put(StandardEntitySchema.UPDATED_BY_FIELD, StandardEntitySchema.UPDATED_BY_COLUMN);
        put(StandardEntitySchema.UPDATED_AT_FIELD, StandardEntitySchema.UPDATED_AT_COLUMN);
        for (FieldDefinition field : DynamicAbilityFields.recordFields(entity)) {
            if (field.isPhysical()) {
                put(field.code(), field.columnName());
            }
            if (field.protection().hasStorageProtection()) {
                protectedStorageFields.add(field.code());
                protectedStorageFields.add(field.columnName());
                if (field.protection().signatureMode().enabled()) {
                    protectedStorageFields.add(FieldCompanionRules.signatureFieldName(field.fieldName()));
                    protectedStorageFields.add(FieldCompanionRules.signatureColumnName(field.columnName()));
                }
            }
        }
    }

    @Override
    public String resolveColumnName(String fieldName) {
        return columns.get(fieldName);
    }

    @Override
    public String resolveFieldName(String columnName) {
        return fields.getOrDefault(columnName, columnName);
    }

    public String resolveColumn(String fieldOrColumn) {
        String column = resolveColumnName(fieldOrColumn);
        if (column == null) {
            throw new IllegalArgumentException("unknown dynamic field or column: " + fieldOrColumn);
        }
        return column;
    }

    public String resolveQueryableColumn(String fieldOrColumn) {
        requireQueryAllowed(fieldOrColumn);
        return resolveColumn(fieldOrColumn);
    }

    public void requireQueryAllowed(String fieldOrColumn) {
        if (protectedStorageFields.contains(fieldOrColumn)) {
            throw new IllegalArgumentException("protected storage field cannot be used in dynamic query or sort: "
                    + fieldOrColumn);
        }
    }

    public boolean hasProtectedStorageFields() {
        return !protectedStorageFields.isEmpty();
    }

    private void put(String field, String column) {
        columns.put(field, column);
        columns.put(column, column);
        fields.putIfAbsent(column, field);
    }
}
