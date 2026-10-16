package net.ximatai.muyun.spring.dynamic.schema;

import net.ximatai.muyun.database.core.builder.Column;
import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.schema.PlatformTableValidator;
import net.ximatai.muyun.spring.common.schema.PlatformUniqueIndexes;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicFieldColumnMetadata;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DynamicTableMapper {
    private final ModuleDefinitionValidator validator = new ModuleDefinitionValidator();
    private final PlatformTableValidator tableValidator = new PlatformTableValidator();

    public TableWrapper toTable(EntityDefinition entity) {
        validator.validateEntity(entity);
        TableWrapper table = TableWrapper.withName(entity.tableName())
                .setSchema(entity.schemaName())
                .setComment(entity.name())
                .setPrimaryKey(StandardEntitySchema.idColumn());
        StandardEntitySchema.auditColumns().forEach(table::addColumn);
        for (FieldDefinition field : tableFields(entity)) {
            table.addColumn(toColumn(field));
        }
        entity.resolvedTenantUniqueConstraints().forEach(constraint -> PlatformUniqueIndexes.addTenantUniqueIndex(table,
                constraint.fieldNames().stream()
                        .map(fieldName -> field(entity, fieldName).columnName())
                        .toList()));
        tableValidator.requireStandardEntityTable(table, entity.alias());
        return table;
    }

    private FieldDefinition field(EntityDefinition entity, String fieldName) {
        return entity.fields().stream()
                .filter(field -> field.fieldName().equals(fieldName) && field.isPhysical())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown physical tenant unique constraint field: "
                        + entity.alias() + "." + fieldName));
    }

    public TableWrapper toTable(EntityDefinition entity, EntityDefinition previousEntity) {
        TableWrapper table = toTable(entity);
        if (previousEntity == null
                || !java.util.Objects.equals(entity.schemaName(), previousEntity.schemaName())
                || !entity.tableName().equals(previousEntity.tableName())) {
            return table;
        }
        validator.validateEntity(previousEntity);
        Set<String> targetColumns = tableFields(entity).stream()
                .map(FieldDefinition::columnName)
                .collect(Collectors.toSet());
        tableFields(previousEntity).stream()
                .map(FieldDefinition::columnName)
                .filter(column -> !targetColumns.contains(column))
                .forEach(table::dropColumn);
        return table;
    }

    private List<FieldDefinition> tableFields(EntityDefinition entity) {
        return DynamicAbilityFields.recordFields(entity).stream().filter(FieldDefinition::isPhysical).toList();
    }

    private Column toColumn(FieldDefinition field) {
        Column column = Column.of(field.columnName())
                .setType(DynamicFieldColumnMetadata.columnType(field))
                .setComment(field.name())
                .setNullable(!field.isRequired())
                .setIndexed(field.isIndexed() || field.isSortable());
        if (field.length() != null) {
            column.setLength(field.length());
        }
        if (field.precision() != null) {
            column.setPrecision(field.precision());
        }
        if (field.scale() != null) {
            column.setScale(field.scale());
        }
        return column;
    }
}
