package net.ximatai.muyun.spring.common.schema;

import net.ximatai.muyun.database.core.annotation.AnnotationProcessor;
import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.spring.common.model.constraint.StaticTenantUniqueConstraints;
import net.ximatai.muyun.spring.common.model.constraint.ConditionalIndexDefinition;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.lang.reflect.Field;
import java.util.Objects;

public class StaticEntityTableMapper {
    private final PlatformTableValidator tableValidator;

    public StaticEntityTableMapper() {
        this(new PlatformTableValidator());
    }

    public StaticEntityTableMapper(PlatformTableValidator tableValidator) {
        this.tableValidator = Objects.requireNonNull(tableValidator, "tableValidator must not be null");
    }

    public TableWrapper toTable(Class<?> modelClass) {
        requirePlatformEntity(modelClass);
        TableWrapper table = AnnotationProcessor.fromEntityClass(modelClass);
        PlatformUniqueIndexes.normalizeTenantUniqueIndexes(table);
        addDeclaredTenantUniqueConstraints(table, modelClass);
        ConditionalIndexDefinition.from(modelClass).stream().filter(index -> !index.unique())
                .forEach(index -> PlatformConditionalIndexes.add(table, index, field -> conditionalColumnName(modelClass, field)));
        tableValidator.requireStandardEntityTable(table, modelClass.getName());
        return table;
    }

    private void requirePlatformEntity(Class<?> modelClass) {
        Objects.requireNonNull(modelClass, "modelClass must not be null");
        if (!StandardEntity.class.isAssignableFrom(modelClass)) {
            throw new IllegalArgumentException("static entity must extend StandardEntity: " + modelClass.getName());
        }
    }

    private void addDeclaredTenantUniqueConstraints(TableWrapper table, Class<?> modelClass) {
        StaticTenantUniqueConstraints.resolve(modelClass).forEach(constraint -> {
            if (constraint.conditions().isEmpty()) {
                PlatformUniqueIndexes.addTenantUniqueIndex(table, constraint.fieldNames().stream()
                        .map(field -> columnName(modelClass, field)).toList());
            } else {
                PlatformConditionalIndexes.add(table, new ConditionalIndexDefinition(constraint.indexName(),
                        constraint.fieldNames(), true, constraint.conditions(), constraint.message()),
                        field -> conditionalColumnName(modelClass, field));
            }
        });
    }

    private String conditionalColumnName(Class<?> modelClass, String fieldName) {
        for (Class<?> type = modelClass; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                if (field.isAnnotationPresent(net.ximatai.muyun.spring.common.security.EncryptedField.class)
                        || field.isAnnotationPresent(net.ximatai.muyun.spring.common.security.SignedField.class)) {
                    throw new IllegalArgumentException("conditional index cannot use a protected storage field: " + fieldName);
                }
                break;
            } catch (NoSuchFieldException ignored) { }
        }
        return columnName(modelClass, fieldName);
    }

    private String columnName(Class<?> modelClass, String fieldName) {
        Class<?> current = modelClass;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                net.ximatai.muyun.database.core.annotation.Column column =
                        field.getAnnotation(net.ximatai.muyun.database.core.annotation.Column.class);
                if (column == null) {
                    throw new IllegalArgumentException("tenant unique constraint field must declare @Column: "
                            + modelClass.getName() + "." + fieldName);
                }
                return columnName(field, column);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new IllegalArgumentException("unknown tenant unique constraint field: "
                + modelClass.getName() + "." + fieldName);
    }

    private String columnName(Field field, net.ximatai.muyun.database.core.annotation.Column column) {
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        return field.getName();
    }
}
