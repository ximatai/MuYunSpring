package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The single catalog for metadata-level dynamic field-capability declarations.
 *
 * <p>A non-null {@link Metadata#getCapabilityDeclarations()} is authoritative. Null is a deliberately
 * bounded legacy state, read by the resolver only until that metadata is first governed.</p>
 */
public final class MetadataCapabilityCatalog {
    private static final Set<EntityCapability> DECLARABLE = Set.copyOf(EnumSet.of(
            EntityCapability.TREE, EntityCapability.SORT, EntityCapability.ENABLE));
    private static final Set<EntityCapability> MUTABLE_IN_FIRST_RELEASE = Set.copyOf(EnumSet.of(
            EntityCapability.TREE, EntityCapability.SORT, EntityCapability.ENABLE));

    private MetadataCapabilityCatalog() {
    }

    public static boolean isDeclarable(EntityCapability capability) {
        return capability != null && DECLARABLE.contains(capability);
    }

    public static boolean isMutableInFirstRelease(EntityCapability capability) {
        return capability != null && MUTABLE_IN_FIRST_RELEASE.contains(capability);
    }

    public static String requireDeclaration(String value) {
        return parse(value).name();
    }

    public static Set<EntityCapability> declarations(Metadata metadata) {
        if (metadata == null || metadata.getCapabilityDeclarations() == null) return null;
        EnumSet<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);
        for (String value : metadata.getCapabilityDeclarations()) capabilities.add(parse(value));
        return normalize(capabilities);
    }

    public static Set<String> declarationNames(Set<EntityCapability> capabilities) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        normalize(capabilities).stream().sorted().map(Enum::name).forEach(names::add);
        return names;
    }

    public static MetadataCapabilityResolution resolve(Metadata metadata, RelationRole relationRole,
                                                       List<MetadataField> savedFields) {
        Set<EntityCapability> declarations = declarations(metadata);
        boolean legacy = declarations == null;
        Set<EntityCapability> effective = legacy ? legacyCapabilities(metadata, savedFields) : declarations;
        if (!legacy) validateApplicability(effective, relationRole);
        return new MetadataCapabilityResolution(legacy, effective, plan(effective));
    }

    public static MetadataCapabilityPlan plan(Set<EntityCapability> capabilities) {
        Set<EntityCapability> normalized = normalize(capabilities);
        LinkedHashSet<ModuleMetadataCapabilityFieldContribution> metadataFields = new LinkedHashSet<>();
        LinkedHashSet<FieldDefinition> implicitFields = new LinkedHashSet<>();
        for (EntityCapability capability : normalized.stream().sorted().toList()) {
            switch (capability) {
                case TREE -> metadataFields.add(field(PlatformAbilityFields.TREE_PARENT_FIELD,
                        PlatformAbilityFields.TREE_PARENT_COLUMN, "string", "RUNTIME", "未填写 parentId 时，运行态写入根节点。"));
                case SORT -> metadataFields.add(field(PlatformAbilityFields.SORT_FIELD,
                        PlatformAbilityFields.SORT_COLUMN, "integer", "RUNTIME", "未填写 sortOrder 时，运行态按分区分配下一个排序值。"));
                case ENABLE -> metadataFields.add(field(PlatformAbilityFields.ENABLED_FIELD,
                        PlatformAbilityFields.ENABLED_COLUMN, "boolean", "STATIC", "未填写 enabled 时，默认写入 true。"));
                default -> { }
            }
        }
        return new MetadataCapabilityPlan(normalized, List.copyOf(metadataFields), List.copyOf(implicitFields));
    }

    /**
     * Adds only declared platform-managed fields missing from persisted metadata. Legacy inference
     * deliberately does not synthesize fields, so reading old metadata cannot alter its schema.
     * DATA_SCOPE and APPROVAL remain outside this declaration batch and keep their existing
     * runtime/configuration contracts.
     */
    public static List<FieldDefinition> mergeDeclaredMetadataFields(MetadataCapabilityResolution resolution,
                                                                     List<FieldDefinition> compiledFields) {
        LinkedHashMap<String, FieldDefinition> fields = new LinkedHashMap<>();
        if (compiledFields != null) {
            for (FieldDefinition field : compiledFields) fields.put(field.fieldName(), field);
        }
        if (!resolution.legacyFieldInference()) {
            for (ModuleMetadataCapabilityFieldContribution contribution : resolution.plan().metadataFields()) {
                // Catalog-owned fields are a runtime contract, not merely missing-field defaults.
                // Replace the compiled shape as well: for example TREE.parentId is string(32),
                // whereas a generic string field would inherit the FieldSpec default length.
                fields.put(contribution.fieldName(), managedField(contribution));
            }
        }
        return List.copyOf(fields.values());
    }

    /**
     * Returns the canonical runtime definition for a persisted platform-managed field.
     *
     * <p>The capability catalog owns this shape so DDL compilation and runtime activation
     * cannot drift from one another. Business fields with the same names are deliberately
     * not claimed by this method.</p>
     */
    static FieldDefinition managedDefinition(MetadataField field) {
        if (field == null || field.getFieldOwnership() != MetadataFieldOwnership.STANDARD
                || !Boolean.TRUE.equals(field.getSystemManaged())) {
            return null;
        }
        return switch (field.getFieldName()) {
            case PlatformAbilityFields.TREE_PARENT_FIELD -> matches(field,
                    PlatformAbilityFields.TREE_PARENT_COLUMN, "string") ? FieldDefinition.parentId() : null;
            case PlatformAbilityFields.SORT_FIELD -> matches(field,
                    PlatformAbilityFields.SORT_COLUMN, "integer") ? FieldDefinition.sortOrder() : null;
            case PlatformAbilityFields.ENABLED_FIELD -> matches(field,
                    PlatformAbilityFields.ENABLED_COLUMN, "boolean") ? FieldDefinition.enabled() : null;
            default -> null;
        };
    }

    private static Set<EntityCapability> legacyCapabilities(Metadata metadata, List<MetadataField> fields) {
        EnumSet<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);
        if (fields != null) {
            for (MetadataField field : fields) {
                if (PlatformAbilityFields.TREE_PARENT_FIELD.equals(field.getFieldName())) capabilities.add(EntityCapability.TREE);
                if (Boolean.TRUE.equals(field.getSortableField())) capabilities.add(EntityCapability.SORT);
                if (PlatformAbilityFields.ENABLED_FIELD.equals(field.getFieldName())
                        || PlatformAbilityFields.ENABLED_COLUMN.equals(field.getColumnName())) capabilities.add(EntityCapability.ENABLE);
            }
        }
        return normalize(capabilities);
    }

    private static void validateApplicability(Set<EntityCapability> capabilities, RelationRole relationRole) {
        if (relationRole != RelationRole.CHILD) return;
        if (!capabilities.isEmpty()) {
            throw new PlatformException("Child metadata cannot declare module capability: " + capabilities.iterator().next());
        }
    }

    private static Set<EntityCapability> normalize(Set<EntityCapability> capabilities) {
        EnumSet<EntityCapability> normalized = capabilities == null || capabilities.isEmpty()
                ? EnumSet.noneOf(EntityCapability.class) : EnumSet.copyOf(capabilities);
        normalized.retainAll(DECLARABLE);
        if (normalized.contains(EntityCapability.TREE)) normalized.add(EntityCapability.SORT);
        return Set.copyOf(normalized);
    }

    private static EntityCapability parse(String value) {
        try {
            EntityCapability capability = EntityCapability.valueOf(value == null ? "" : value.trim());
            if (!isDeclarable(capability)) throw new PlatformException("Dynamic metadata capability is not declarable: " + value);
            return capability;
        } catch (IllegalArgumentException exception) {
            throw new PlatformException("Unknown dynamic metadata capability: " + value);
        }
    }

    private static ModuleMetadataCapabilityFieldContribution field(String fieldName, String columnName,
                                                                    String fieldSpecAlias, String defaultKind,
                                                                    String defaultDescription) {
        return new ModuleMetadataCapabilityFieldContribution(fieldName, columnName, fieldSpecAlias,
                defaultKind, defaultDescription);
    }

    private static FieldDefinition managedField(ModuleMetadataCapabilityFieldContribution contribution) {
        return switch (contribution.fieldName()) {
            case PlatformAbilityFields.TREE_PARENT_FIELD -> FieldDefinition.parentId();
            case PlatformAbilityFields.SORT_FIELD -> FieldDefinition.sortOrder();
            case PlatformAbilityFields.ENABLED_FIELD -> FieldDefinition.enabled();
            default -> throw new IllegalArgumentException("Unsupported platform capability field: "
                    + contribution.fieldName());
        };
    }

    private static boolean matches(MetadataField field, String columnName, String fieldSpecAlias) {
        return columnName.equals(field.getColumnName()) && fieldSpecAlias.equals(field.getFieldSpecAlias());
    }
}
