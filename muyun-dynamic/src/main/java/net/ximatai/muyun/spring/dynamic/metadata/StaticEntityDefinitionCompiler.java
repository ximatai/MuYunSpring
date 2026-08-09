package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.schema.PlatformDataScopeSchema;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.model.constraint.StaticTenantUniqueConstraints;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.ability.SortPartitionBy;
import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class StaticEntityDefinitionCompiler {
    private static final Set<String> STANDARD_FIELDS = Set.copyOf(StandardEntitySchema.fieldNames());
    private static final Set<String> STANDARD_COLUMNS = Set.copyOf(StandardEntitySchema.columnNames());
    private static final Set<String> DATA_SCOPE_FIELDS = Set.copyOf(PlatformDataScopeSchema.fieldNames());
    private static final Set<String> DATA_SCOPE_COLUMNS = Set.copyOf(PlatformDataScopeSchema.columnNames());

    public EntityDefinition compile(String entityAlias, String entityName, Class<?> modelClass) {
        if (modelClass == null) {
            throw new IllegalArgumentException("static modelClass must not be null");
        }
        Table table = modelClass.getAnnotation(Table.class);
        if (table == null || table.name().isBlank()) {
            throw new IllegalArgumentException("static model requires @Table: " + modelClass.getName());
        }
        return new EntityDefinition(
                PlatformNameRules.requireIdentifier(entityAlias, "static entity alias"),
                EntityDefinition.DEFAULT_SCHEMA_NAME,
                table.name(),
                entityName == null || entityName.isBlank() ? tableName(table, modelClass) : entityName.trim(),
                fields(modelClass, hasSortPartition(modelClass)),
                capabilities(modelClass),
                List.of(),
                StaticTenantUniqueConstraints.resolve(modelClass),
                sortPartitionFields(modelClass),
                fileReferences(modelClass)
        );
    }

    private Map<String, FileReferenceDefinition> fileReferences(Class<?> modelClass) {
        Map<String, FileReferenceDefinition> references = new LinkedHashMap<>();
        for (Field field : declaredFields(modelClass)) {
            FileReference annotation = field.getAnnotation(FileReference.class);
            if (annotation == null) {
                continue;
            }
            Column column = field.getAnnotation(Column.class);
            if (column == null || field.getType() != String.class || fieldType(column.type()) != FieldType.STRING) {
                throw new IllegalArgumentException("static file reference requires @Column STRING field: "
                        + modelClass.getName() + "." + field.getName());
            }
            references.put(field.getName(), new FileReferenceDefinition(
                    Set.of(annotation.allowedMediaTypes()),
                    annotation.maxFileSizeBytes() > 0 ? annotation.maxFileSizeBytes() : null));
        }
        return Map.copyOf(references);
    }

    private List<String> sortPartitionFields(Class<?> modelClass) {
        SortPartitionBy partition = modelClass.getAnnotation(SortPartitionBy.class);
        return partition == null ? List.of() : List.of(partition.fields());
    }

    private boolean hasSortPartition(Class<?> modelClass) {
        return !sortPartitionFields(modelClass).isEmpty();
    }

    private Set<EntityCapability> capabilities(Class<?> modelClass) {
        return sortPartitionFields(modelClass).isEmpty()
                ? Set.of(EntityCapability.CRUD)
                : Set.of(EntityCapability.CRUD, EntityCapability.SORT);
    }

    private String tableName(Table table, Class<?> modelClass) {
        if (table.comment() != null && !table.comment().isBlank()) {
            return table.comment().trim();
        }
        return modelClass.getSimpleName();
    }

    private List<FieldDefinition> fields(Class<?> modelClass, boolean sortable) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (Field field : declaredFields(modelClass)) {
            Column column = field.getAnnotation(Column.class);
            if (column == null) {
                continue;
            }
            String columnName = columnName(field, column);
            if (isPlatformManagedField(field.getName(), columnName)) {
                continue;
            }
            if (PlatformAbilityFields.SORT_FIELD.equals(field.getName())) {
                fields.add(FieldDefinition.sortOrder());
                continue;
            }
            FieldType fieldType = fieldType(column.type());
            FieldDefinition definition = new FieldDefinition(
                    field.getName(),
                    columnName,
                    fieldType,
                    column.comment() == null || column.comment().isBlank() ? field.getName() : column.comment(),
                    !column.nullable(),
                    column.unique(),
                    false,
                    false,
                    false,
                    length(column, fieldType),
                    precision(column, fieldType),
                    scale(column, fieldType),
                    null,
                    null
            );
            definition = StaticMeasureUnitFieldDefinitionCompiler.compile(definition, field);
            definition = StaticMoneyFieldDefinitionCompiler.compile(definition, field);
            fields.add(definition);
        }
        if (sortable && fields.stream().noneMatch(field -> field.fieldName().equals("sortOrder"))) {
            fields.add(FieldDefinition.sortOrder());
        }
        return List.copyOf(fields);
    }

    private List<Field> declaredFields(Class<?> modelClass) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = modelClass;
        while (current != null && current != Object.class) {
            Collections.addAll(fields, current.getDeclaredFields());
            current = current.getSuperclass();
        }
        Collections.reverse(fields);
        return fields;
    }

    private boolean isPlatformManagedField(String fieldName, String columnName) {
        return STANDARD_FIELDS.contains(fieldName)
                || STANDARD_COLUMNS.contains(columnName)
                || DATA_SCOPE_FIELDS.contains(fieldName)
                || DATA_SCOPE_COLUMNS.contains(columnName);
    }

    private String columnName(Field field, Column column) {
        return column.name() == null || column.name().isBlank() ? field.getName() : column.name();
    }

    private FieldType fieldType(ColumnType type) {
        if (type == null) {
            return FieldType.STRING;
        }
        return switch (type) {
            case VARCHAR -> FieldType.STRING;
            case TEXT -> FieldType.TEXT;
            case INT -> FieldType.INTEGER;
            case BIGINT -> FieldType.LONG;
            case NUMERIC -> FieldType.DECIMAL;
            case BOOLEAN -> FieldType.BOOLEAN;
            case DATE -> FieldType.DATE;
            case TIMESTAMP -> FieldType.TIMESTAMP;
            case JSON, JSON_SET -> FieldType.JSON;
            default -> FieldType.STRING;
        };
    }

    private Integer length(Column column, FieldType fieldType) {
        if (fieldType != FieldType.STRING && fieldType != FieldType.TEXT) {
            return null;
        }
        return column.length() <= 0 ? null : column.length();
    }

    private Integer precision(Column column, FieldType fieldType) {
        if (fieldType != FieldType.DECIMAL) {
            return null;
        }
        return column.precision() <= 0 ? null : column.precision();
    }

    private Integer scale(Column column, FieldType fieldType) {
        if (fieldType != FieldType.DECIMAL) {
            return null;
        }
        return column.scale() < 0 ? null : column.scale();
    }
}
