package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.List;

/** Canonical descriptors for runtime-owned fields that remain addressable by metadata/UI. */
public final class MetadataSystemFieldCatalog {
    private static final List<MetadataSystemFieldDescriptor> BASELINE = List.of(
            field(StandardEntitySchema.ID_FIELD, StandardEntitySchema.ID_COLUMN, "string", "ID"),
            field(StandardEntitySchema.TENANT_ID_FIELD, StandardEntitySchema.TENANT_ID_COLUMN, "string", "租户"),
            field(StandardEntitySchema.VERSION_FIELD, StandardEntitySchema.VERSION_COLUMN, "integer", "版本"),
            field(StandardEntitySchema.DELETED_FIELD, StandardEntitySchema.DELETED_COLUMN, "boolean", "已删除"),
            field(StandardEntitySchema.DELETED_AT_FIELD, StandardEntitySchema.DELETED_AT_COLUMN, "datetime", "删除时间"),
            field(StandardEntitySchema.DELETED_BY_FIELD, StandardEntitySchema.DELETED_BY_COLUMN, "string", "删除人"),
            field(StandardEntitySchema.CREATED_BY_FIELD, StandardEntitySchema.CREATED_BY_COLUMN, "string", "创建人"),
            field(StandardEntitySchema.CREATED_AT_FIELD, StandardEntitySchema.CREATED_AT_COLUMN, "datetime", "创建时间"),
            field(StandardEntitySchema.UPDATED_BY_FIELD, StandardEntitySchema.UPDATED_BY_COLUMN, "string", "更新人"),
            field(StandardEntitySchema.UPDATED_AT_FIELD, StandardEntitySchema.UPDATED_AT_COLUMN, "datetime", "更新时间")
    );
    private static final List<MetadataSystemFieldDescriptor> DATA_SCOPE = List.of(
            field(PlatformAbilityFields.AUTH_USER_FIELD, PlatformAbilityFields.AUTH_USER_COLUMN, "string", "权限归属用户"),
            field(PlatformAbilityFields.AUTH_ASSIGNEE_FIELD, PlatformAbilityFields.AUTH_ASSIGNEE_COLUMN, "text", "权限被分配用户"),
            field(PlatformAbilityFields.AUTH_MEMBER_FIELD, PlatformAbilityFields.AUTH_MEMBER_COLUMN, "text", "权限成员用户"),
            field(PlatformAbilityFields.AUTH_ORGANIZATION_FIELD, PlatformAbilityFields.AUTH_ORGANIZATION_COLUMN, "string", "权限归属组织"),
            field(PlatformAbilityFields.AUTH_DEPARTMENT_FIELD, PlatformAbilityFields.AUTH_DEPARTMENT_COLUMN, "string", "权限归属部门"),
            field(PlatformAbilityFields.AUTH_MODULE_FIELD, PlatformAbilityFields.AUTH_MODULE_COLUMN, "string", "权限模块")
    );

    private MetadataSystemFieldCatalog() { }

    static List<MetadataSystemFieldDescriptor> baselineFields() { return BASELINE; }
    static List<MetadataSystemFieldDescriptor> dataScopeFields() { return DATA_SCOPE; }

    public static boolean isRuntimeReserved(MetadataField field) {
        if (field == null || !Boolean.TRUE.equals(field.getSystemManaged())) return false;
        return BASELINE.stream().anyMatch(item -> item.fieldName().equals(field.getFieldName()))
                || DATA_SCOPE.stream().anyMatch(item -> item.fieldName().equals(field.getFieldName()));
    }

    private static MetadataSystemFieldDescriptor field(String fieldName, String columnName,
                                                       String fieldSpecAlias, String title) {
        return new MetadataSystemFieldDescriptor(fieldName, columnName, fieldSpecAlias, title);
    }

    record MetadataSystemFieldDescriptor(String fieldName, String columnName, String fieldSpecAlias, String title) { }
}
