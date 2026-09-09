package net.ximatai.muyun.spring.common.schema;

import net.ximatai.muyun.database.core.annotation.Id;
import net.ximatai.muyun.database.core.builder.Column;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

public final class StandardEntitySchema {
    public static final String ID_FIELD = "id";
    public static final String TENANT_ID_FIELD = "tenantId";
    public static final String VERSION_FIELD = "version";
    public static final String DELETED_FIELD = "deleted";
    public static final String DELETED_AT_FIELD = "deletedAt";
    public static final String DELETED_BY_FIELD = "deletedBy";
    public static final String CREATED_BY_FIELD = "createdBy";
    public static final String CREATED_AT_FIELD = "createdAt";
    public static final String UPDATED_BY_FIELD = "updatedBy";
    public static final String UPDATED_AT_FIELD = "updatedAt";

    public static final String ID_COLUMN = columnName(field(ID_FIELD));
    public static final String TENANT_ID_COLUMN = columnName(field(TENANT_ID_FIELD));
    public static final String VERSION_COLUMN = columnName(field(VERSION_FIELD));
    public static final String DELETED_COLUMN = columnName(field(DELETED_FIELD));
    public static final String DELETED_AT_COLUMN = columnName(field(DELETED_AT_FIELD));
    public static final String DELETED_BY_COLUMN = columnName(field(DELETED_BY_FIELD));
    public static final String CREATED_BY_COLUMN = columnName(field(CREATED_BY_FIELD));
    public static final String CREATED_AT_COLUMN = columnName(field(CREATED_AT_FIELD));
    public static final String UPDATED_BY_COLUMN = columnName(field(UPDATED_BY_FIELD));
    public static final String UPDATED_AT_COLUMN = columnName(field(UPDATED_AT_FIELD));

    private StandardEntitySchema() {
    }

    public static Column idColumn() {
        return columnFrom(field(ID_FIELD));
    }

    public static List<Column> auditColumns() {
        return Arrays.stream(StandardEntity.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(net.ximatai.muyun.database.core.annotation.Column.class))
                .filter(field -> field.getAnnotation(Id.class) == null)
                .map(StandardEntitySchema::columnFrom)
                .toList();
    }

    public static List<String> columnNames() {
        return Arrays.stream(StandardEntity.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(net.ximatai.muyun.database.core.annotation.Column.class))
                .map(StandardEntitySchema::columnName)
                .toList();
    }

    public static List<String> fieldNames() {
        return Arrays.stream(StandardEntity.class.getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(net.ximatai.muyun.database.core.annotation.Column.class))
                .map(Field::getName)
                .toList();
    }

    public static String columnName(String fieldName) {
        return columnName(field(fieldName));
    }

    private static Field field(String name) {
        try {
            return StandardEntity.class.getDeclaredField(name);
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("standard entity field missing: " + name, exception);
        }
    }

    private static Column columnFrom(Field field) {
        net.ximatai.muyun.database.core.annotation.Column annotation =
                field.getAnnotation(net.ximatai.muyun.database.core.annotation.Column.class);
        if (annotation == null) {
            throw new IllegalStateException("standard entity field has no @Column: " + field.getName());
        }
        Column column = Column.of(columnName(field))
                .setType(annotation.type())
                .setNullable(annotation.nullable());
        if (annotation.length() > 0) {
            column.setLength(annotation.length());
        }
        if (annotation.precision() > 0) {
            column.setPrecision(annotation.precision());
        }
        if (annotation.scale() > 0) {
            column.setScale(annotation.scale());
        }
        if (!annotation.comment().isEmpty()) {
            column.setComment(annotation.comment());
        }
        if (field.getAnnotation(Id.class) != null) {
            column.setPrimaryKey();
        }
        return column;
    }

    private static String columnName(Field field) {
        net.ximatai.muyun.database.core.annotation.Column annotation =
                field.getAnnotation(net.ximatai.muyun.database.core.annotation.Column.class);
        return annotation.name();
    }
}
