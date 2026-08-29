package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformException;
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
            throw new PlatformException("Child relation foreign key must be a physical field: " + field.getFieldName());
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
            throw new PlatformException("Child relation foreign key must reference its parent metadata: "
                    + relation.getRelationAlias());
        }
        if (config.getTargetModuleAlias() != null && !config.getTargetModuleAlias().isBlank()
                && !relation.getModuleAlias().equals(config.getTargetModuleAlias())) {
            throw new PlatformException("Child relation foreign key cannot reference another module: "
                    + relation.getRelationAlias());
        }
        if (config.getCardinality() != ReferenceCardinality.ONE) {
            throw new PlatformException("Child relation foreign key must use ONE cardinality: "
                    + relation.getRelationAlias());
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
            throw new PlatformException("Child metadata cannot declare module capability: " + metadata.getAlias());
        }
        if (Boolean.TRUE.equals(metadata.getDataScopeEnabled())) {
            throw new PlatformException("Child metadata cannot enable module data scope: " + metadata.getAlias());
        }
        if (metadata.getSortPartitionFields() != null && !metadata.getSortPartitionFields().isEmpty()) {
            throw new PlatformException("Child metadata cannot configure sort partition fields: " + metadata.getAlias());
        }
    }

    private static void rejectReservedChildCapability(String fieldName, String columnName, boolean sortable) {
        if (sortable
                || PlatformAbilityFields.TREE_PARENT_FIELD.equals(fieldName)
                || PlatformAbilityFields.ENABLED_FIELD.equals(fieldName)
                || PlatformAbilityFields.ENABLED_COLUMN.equals(columnName)
                || isApprovalField(fieldName, columnName)) {
            throw new PlatformException("Child metadata cannot enable reserved module capability field: " + fieldName);
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
