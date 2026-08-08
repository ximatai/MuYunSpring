package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition;

import java.util.EnumSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record EntityDefinition(
        String alias,
        String schemaName,
        String tableName,
        String name,
        List<FieldDefinition> fields,
        Set<EntityCapability> capabilities,
        List<EntityFormulaRuleDefinition> formulaRules,
        List<TenantUniqueConstraintDefinition> tenantUniqueConstraints,
        List<String> sortPartitionFields,
        Map<String, FileReferenceDefinition> fileReferences
) {
    public static final String DEFAULT_SCHEMA_NAME = "public";

    public EntityDefinition(String alias, String tableName, String name, List<FieldDefinition> fields) {
        this(alias, DEFAULT_SCHEMA_NAME, tableName, name, fields, Set.of(EntityCapability.CRUD), List.of(), List.of(), List.of(), Map.of());
    }

    public EntityDefinition(String alias,
                            String tableName,
                            String name,
                            List<FieldDefinition> fields,
                            Set<EntityCapability> capabilities) {
        this(alias, DEFAULT_SCHEMA_NAME, tableName, name, fields, capabilities, List.of(), List.of(), List.of(), Map.of());
    }

    public EntityDefinition(String alias,
                            String schemaName,
                            String tableName,
                            String name,
                            List<FieldDefinition> fields,
                            Set<EntityCapability> capabilities) {
        this(alias, schemaName, tableName, name, fields, capabilities, List.of(), List.of(), List.of(), Map.of());
    }

    public EntityDefinition(String alias,
                            String schemaName,
                            String tableName,
                            String name,
                            List<FieldDefinition> fields,
                            Set<EntityCapability> capabilities,
                            List<EntityFormulaRuleDefinition> formulaRules) {
        this(alias, schemaName, tableName, name, fields, capabilities, formulaRules, List.of(), List.of(), Map.of());
    }

    public EntityDefinition(String alias,
                            String schemaName,
                            String tableName,
                            String name,
                            List<FieldDefinition> fields,
                            Set<EntityCapability> capabilities,
                            List<EntityFormulaRuleDefinition> formulaRules,
                            List<TenantUniqueConstraintDefinition> tenantUniqueConstraints) {
        this(alias, schemaName, tableName, name, fields, capabilities, formulaRules, tenantUniqueConstraints, List.of(), Map.of());
    }

    /** Source-compatible constructor for entity definitions before file-reference field facts existed. */
    public EntityDefinition(String alias,
                            String schemaName,
                            String tableName,
                            String name,
                            List<FieldDefinition> fields,
                            Set<EntityCapability> capabilities,
                            List<EntityFormulaRuleDefinition> formulaRules,
                            List<TenantUniqueConstraintDefinition> tenantUniqueConstraints,
                            List<String> sortPartitionFields) {
        this(alias, schemaName, tableName, name, fields, capabilities, formulaRules, tenantUniqueConstraints,
                sortPartitionFields, Map.of());
    }

    public EntityDefinition {
        schemaName = schemaName == null || schemaName.isBlank() ? DEFAULT_SCHEMA_NAME : schemaName;
        fields = fields == null ? List.of() : List.copyOf(fields);
        capabilities = normalizeCapabilities(capabilities);
        formulaRules = formulaRules == null ? List.of() : List.copyOf(formulaRules);
        tenantUniqueConstraints = tenantUniqueConstraints == null ? List.of() : List.copyOf(tenantUniqueConstraints);
        sortPartitionFields = sortPartitionFields == null ? List.of() : List.copyOf(sortPartitionFields);
        fileReferences = fileReferences == null ? Map.of() : Map.copyOf(fileReferences);
    }

    public EntityDefinition withCapabilities(EntityCapability... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, Set.of(values), formulaRules,
                tenantUniqueConstraints, sortPartitionFields, fileReferences);
    }

    public EntityDefinition withFormulaRules(EntityFormulaRuleDefinition... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities,
                values == null ? List.of() : List.of(values), tenantUniqueConstraints, sortPartitionFields, fileReferences);
    }

    public EntityDefinition withTenantUniqueConstraints(TenantUniqueConstraintDefinition... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities, formulaRules,
                values == null ? List.of() : List.of(values), sortPartitionFields, fileReferences);
    }

    public EntityDefinition withSortPartitionFields(String... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities, formulaRules,
                tenantUniqueConstraints, values == null ? List.of() : List.of(values), fileReferences);
    }

    public EntityDefinition withFileReferences(Map<String, FileReferenceDefinition> values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities, formulaRules,
                tenantUniqueConstraints, sortPartitionFields, values);
    }

    /**
     * Normalizes explicit composite declarations and existing single-field metadata unique flags
     * into the tenant-scoped unique facts consumed by schema and runtime validation.
     */
    public List<TenantUniqueConstraintDefinition> resolvedTenantUniqueConstraints() {
        Map<List<String>, TenantUniqueConstraintDefinition> constraints = new LinkedHashMap<>();
        tenantUniqueConstraints.forEach(constraint -> constraints.put(constraint.fieldNames(), constraint));
        fields.stream()
                .filter(FieldDefinition::isPhysical)
                .filter(FieldDefinition::isUnique)
                .map(field -> new TenantUniqueConstraintDefinition(List.of(field.fieldName()), ""))
                .forEach(constraint -> constraints.putIfAbsent(constraint.fieldNames(), constraint));
        return List.copyOf(constraints.values());
    }

    public List<EntityFormulaRuleDefinition> orderedFormulaRules() {
        return formulaRules.stream()
                .sorted(Comparator.<EntityFormulaRuleDefinition>comparingInt(EntityFormulaRuleDefinition::sortOrder)
                        .thenComparing(EntityFormulaRuleDefinition::code))
                .toList();
    }

    public boolean supports(EntityCapability capability) {
        return capabilities.contains(capability);
    }

    private static Set<EntityCapability> normalizeCapabilities(Set<EntityCapability> capabilities) {
        EnumSet<EntityCapability> normalized = capabilities == null || capabilities.isEmpty()
                ? baselineCapabilities()
                : EnumSet.copyOf(capabilities);
        normalized.addAll(baselineCapabilities());
        if (normalized.contains(EntityCapability.TREE)) {
            normalized.add(EntityCapability.SORT);
        }
        if (normalized.contains(EntityCapability.APPROVAL)) {
            normalized.add(EntityCapability.WORKFLOW);
        }
        return Set.copyOf(normalized);
    }

    private static EnumSet<EntityCapability> baselineCapabilities() {
        EnumSet<EntityCapability> values = EnumSet.noneOf(EntityCapability.class);
        for (EntityCapability capability : EntityCapability.values()) {
            if (capability.isBaseline()) {
                values.add(capability);
            }
        }
        return values;
    }
}
