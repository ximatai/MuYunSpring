package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.List;

public final class DynamicAbilityFields {
    private DynamicAbilityFields() {
    }

    /** Shared field shape for DDL, record values and DAO mappings, including derived companions. */
    public static List<FieldDefinition> recordFields(EntityDefinition entity) {
        var fields = new java.util.ArrayList<FieldDefinition>();
        if (entity.supports(EntityCapability.DATA_SCOPE)) fields.addAll(dataScopeFields());
        if (entity.supports(EntityCapability.APPROVAL)) fields.addAll(approvalFields());
        List<FieldDefinition> managed = List.copyOf(fields);
        for (FieldDefinition field : FieldCompanionRules.recordFields(entity)) {
            var canonical = managed.stream().filter(candidate -> candidate.fieldName().equals(field.fieldName())
                    || candidate.columnName().equals(field.columnName())).findFirst();
            if (canonical.isEmpty()) {
                fields.add(field);
                continue;
            }
            var expected = canonical.get();
            if (!field.isPhysical() || !expected.fieldName().equals(field.fieldName())
                    || !expected.columnName().equals(field.columnName()) || expected.type() != field.type()) {
                throw new ModuleDefinitionException("Field conflicts with platform capability storage: " + field.fieldName());
            }
            // Authoring metadata can materialize capability fields; the canonical shape owns storage.
        }
        return List.copyOf(fields);
    }

    public static List<FieldDefinition> dataScopeFields() {
        return List.of(
                FieldDefinition.string(PlatformAbilityFields.AUTH_USER_FIELD, "Auth User")
                        .column(PlatformAbilityFields.AUTH_USER_COLUMN)
                        .length(PlatformAbilityFields.AUTH_USER_LENGTH),
                FieldDefinition.text(PlatformAbilityFields.AUTH_ASSIGNEE_FIELD, "Auth Assignees")
                        .column(PlatformAbilityFields.AUTH_ASSIGNEE_COLUMN),
                FieldDefinition.text(PlatformAbilityFields.AUTH_MEMBER_FIELD, "Auth Members")
                        .column(PlatformAbilityFields.AUTH_MEMBER_COLUMN),
                FieldDefinition.string(PlatformAbilityFields.AUTH_ORGANIZATION_FIELD, "Auth Organization")
                        .column(PlatformAbilityFields.AUTH_ORGANIZATION_COLUMN)
                        .length(PlatformAbilityFields.AUTH_ORGANIZATION_LENGTH),
                FieldDefinition.string(PlatformAbilityFields.AUTH_DEPARTMENT_FIELD, "Auth Department")
                        .column(PlatformAbilityFields.AUTH_DEPARTMENT_COLUMN)
                        .length(PlatformAbilityFields.AUTH_DEPARTMENT_LENGTH),
                FieldDefinition.string(PlatformAbilityFields.AUTH_MODULE_FIELD, "Auth Module")
                        .column(PlatformAbilityFields.AUTH_MODULE_COLUMN)
                        .length(PlatformAbilityFields.AUTH_MODULE_LENGTH)
        );
    }

    public static List<FieldDefinition> approvalFields() {
        return List.of(
                FieldDefinition.string(PlatformAbilityFields.APPROVAL_INSTANCE_FIELD, "Approval Instance")
                        .column(PlatformAbilityFields.APPROVAL_INSTANCE_COLUMN)
                        .length(PlatformAbilityFields.APPROVAL_INSTANCE_LENGTH),
                FieldDefinition.string(PlatformAbilityFields.APPROVAL_STATUS_FIELD, "Approval Status")
                        .column(PlatformAbilityFields.APPROVAL_STATUS_COLUMN)
                        .length(PlatformAbilityFields.APPROVAL_STATUS_LENGTH),
                FieldDefinition.string(PlatformAbilityFields.APPROVAL_SUBMITTED_BY_FIELD, "Approval Submitted By")
                        .column(PlatformAbilityFields.APPROVAL_SUBMITTED_BY_COLUMN)
                        .length(PlatformAbilityFields.APPROVAL_SUBMITTED_BY_LENGTH),
                FieldDefinition.timestamp(PlatformAbilityFields.APPROVAL_SUBMITTED_AT_FIELD, "Approval Submitted At")
                        .column(PlatformAbilityFields.APPROVAL_SUBMITTED_AT_COLUMN),
                FieldDefinition.timestamp(PlatformAbilityFields.APPROVAL_COMPLETED_AT_FIELD, "Approval Completed At")
                        .column(PlatformAbilityFields.APPROVAL_COMPLETED_AT_COLUMN)
        );
    }
}
