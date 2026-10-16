package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.List;

/** Capability boundary between a module's MAIN entity and its aggregate children. */
public final class ModuleMetadataCapabilityPolicy {
    private ModuleMetadataCapabilityPolicy() {
    }

    public static void validateChildMetadata(Metadata metadata, List<MetadataField> fields) {
        validateChildMetadataConfiguration(metadata);
        for (MetadataField field : fields) {
            rejectReservedChildCapability(field.getFieldName(), field.getColumnName(),
                    Boolean.TRUE.equals(field.getSortableField()));
        }
    }

    public static void validateChildField(MetadataField field) {
        rejectReservedChildCapability(field.getFieldName(), field.getColumnName(),
                Boolean.TRUE.equals(field.getSortableField()));
    }

    public static void validateChildForeignKey(MetadataField field) {
        if (field.getFieldForm() != MetadataFieldForm.PHYSICAL) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-relation-foreign-key-must-be-a-physical-field",
                    "Child relation foreign key must be a physical field: {value1}"), Map.of("value1", String.valueOf(field.getFieldName())));
        }
    }

    public static void validateChildForeignKeyReference(ModuleMetadataRelation relation,
                                                        MetadataField sourceField,
                                                        MetadataFieldReferenceConfig config) {
        if (!relation.getMetadataId().equals(sourceField.getMetadataId())
                || !relation.getForeignKey().equals(sourceField.getFieldName())) {
            return;
        }
        if (!relation.getParentMetadataId().equals(config.getTargetMetadataId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-relation-foreign-key-must-reference-its-parent-metadata",
                    "Child relation foreign key must reference its parent metadata: {value1}"), Map.of("value1", String.valueOf(relation.getRelationAlias())));
        }
        if (config.getTargetModuleAlias() != null && !config.getTargetModuleAlias().isBlank()
                && !relation.getModuleAlias().equals(config.getTargetModuleAlias())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-relation-foreign-key-cannot-reference-another-module",
                    "Child relation foreign key cannot reference another module: {value1}"), Map.of("value1", String.valueOf(relation.getRelationAlias())));
        }
        if (config.getCardinality() != ReferenceCardinality.ONE) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-relation-foreign-key-must-use-one-cardinality",
                    "Child relation foreign key must use ONE cardinality: {value1}"), Map.of("value1", String.valueOf(relation.getRelationAlias())));
        }
    }

    public static void validateChildDefinition(Metadata metadata, List<FieldDefinition> fields) {
        validateChildMetadataConfiguration(metadata);
        for (FieldDefinition field : fields) {
            rejectReservedChildCapability(field.fieldName(), field.columnName(), field.isSortable());
        }
    }

    public static void validateChildMetadataConfiguration(Metadata metadata) {
        if (metadata.getCapabilityDeclarations() != null && !metadata.getCapabilityDeclarations().isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-metadata-cannot-declare-module-capability",
                    "Child metadata cannot declare module capability: {value1}"), Map.of("value1", String.valueOf(metadata.getAlias())));
        }
        if (Boolean.TRUE.equals(metadata.getDataScopeEnabled())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-metadata-cannot-enable-module-data-scope",
                    "Child metadata cannot enable module data scope: {value1}"), Map.of("value1", String.valueOf(metadata.getAlias())));
        }
        if (metadata.getSortPartitionFields() != null && !metadata.getSortPartitionFields().isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-metadata-cannot-configure-sort-partition-fields",
                    "Child metadata cannot configure sort partition fields: {value1}"), Map.of("value1", String.valueOf(metadata.getAlias())));
        }
    }

    private static void rejectReservedChildCapability(String fieldName, String columnName, boolean sortable) {
        if (sortable
                || PlatformAbilityFields.TREE_PARENT_FIELD.equals(fieldName)
                || PlatformAbilityFields.ENABLED_FIELD.equals(fieldName)
                || PlatformAbilityFields.ENABLED_COLUMN.equals(columnName)
                || isApprovalField(fieldName, columnName)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.module-metadata-capability-policy.child-metadata-cannot-enable-reserved-module-capability-field",
                    "Child metadata cannot enable reserved module capability field: {value1}"), Map.of("value1", String.valueOf(fieldName)));
        }
    }

    private static boolean isApprovalField(String fieldName, String columnName) {
        return PlatformAbilityFields.APPROVAL_INSTANCE_FIELD.equals(fieldName)
                || PlatformAbilityFields.APPROVAL_STATUS_FIELD.equals(fieldName)
                || PlatformAbilityFields.APPROVAL_SUBMITTED_BY_FIELD.equals(fieldName)
                || PlatformAbilityFields.APPROVAL_SUBMITTED_AT_FIELD.equals(fieldName)
                || PlatformAbilityFields.APPROVAL_COMPLETED_AT_FIELD.equals(fieldName)
                || PlatformAbilityFields.APPROVAL_INSTANCE_COLUMN.equals(columnName)
                || PlatformAbilityFields.APPROVAL_STATUS_COLUMN.equals(columnName)
                || PlatformAbilityFields.APPROVAL_SUBMITTED_BY_COLUMN.equals(columnName)
                || PlatformAbilityFields.APPROVAL_SUBMITTED_AT_COLUMN.equals(columnName)
                || PlatformAbilityFields.APPROVAL_COMPLETED_AT_COLUMN.equals(columnName);
    }
}
