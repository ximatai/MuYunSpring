package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition;
import net.ximatai.muyun.spring.common.model.constraint.ConditionalIndexDefinition;

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
        Map<String, FileReferenceDefinition> fileReferences,
        Map<String, net.ximatai.muyun.spring.ability.code.CodeFieldDefinition> businessCodes,
        List<ConditionalIndexDefinition> conditionalIndexes
) {
    public static final String DEFAULT_SCHEMA_NAME = "public";

    /** Source-compatible constructor before conditional index declarations. */
    public EntityDefinition(String alias, String schemaName, String tableName, String name,
                            List<FieldDefinition> fields, Set<EntityCapability> capabilities,
                            List<EntityFormulaRuleDefinition> formulaRules,
                            List<TenantUniqueConstraintDefinition> tenantUniqueConstraints,
                            List<String> sortPartitionFields, Map<String, FileReferenceDefinition> fileReferences,
                            Map<String, net.ximatai.muyun.spring.ability.code.CodeFieldDefinition> businessCodes) {
        this(alias, schemaName, tableName, name, fields, capabilities, formulaRules, tenantUniqueConstraints,
                sortPartitionFields, fileReferences, businessCodes, List.of());
    }

    public EntityDefinition(String alias, String schemaName, String tableName, String name,
                            List<FieldDefinition> fields, Set<EntityCapability> capabilities,
                            List<EntityFormulaRuleDefinition> formulaRules,
                            List<TenantUniqueConstraintDefinition> tenantUniqueConstraints,
                            List<String> sortPartitionFields, Map<String, FileReferenceDefinition> fileReferences) {
        this(alias, schemaName, tableName, name, fields, capabilities, formulaRules, tenantUniqueConstraints,
                sortPartitionFields, fileReferences, Map.of());
    }

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
        conditionalIndexes = ConditionalIndexDefinition.normalize(conditionalIndexes);
        businessCodes = businessCodes == null ? Map.of() : Map.copyOf(businessCodes);
        if (businessCodes.values().stream().filter(net.ximatai.muyun.spring.ability.code.CodeFieldDefinition::primary).count() > 1)
            throw new IllegalArgumentException("Only one PRIMARY business code field is allowed: " + alias);
        schemaName = schemaName == null || schemaName.isBlank() ? DEFAULT_SCHEMA_NAME : schemaName;
        fields = fields == null ? List.of() : List.copyOf(fields);
        capabilities = normalizeCapabilities(capabilities);
        if (fields.stream().anyMatch(field -> field.content().enabled())) {
            var declared = EnumSet.copyOf(capabilities);
            declared.add(EntityCapability.BUSINESS_CONTENT);
            capabilities = Set.copyOf(declared);
        }
        if (!businessCodes.isEmpty()) {
            var declared = EnumSet.copyOf(capabilities);
            declared.add(EntityCapability.BUSINESS_CODE);
            capabilities = Set.copyOf(declared);
        }
        formulaRules = formulaRules == null ? List.of() : List.copyOf(formulaRules);
        tenantUniqueConstraints = tenantUniqueConstraints == null ? List.of() : List.copyOf(tenantUniqueConstraints);
        sortPartitionFields = sortPartitionFields == null ? List.of() : List.copyOf(sortPartitionFields);
        fileReferences = fileReferences == null ? Map.of() : Map.copyOf(fileReferences);
    }

    public EntityDefinition withCapabilities(EntityCapability... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, Set.of(values), formulaRules,
                tenantUniqueConstraints, sortPartitionFields, fileReferences, businessCodes, conditionalIndexes);
    }

    public EntityDefinition withFormulaRules(EntityFormulaRuleDefinition... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities,
                values == null ? List.of() : List.of(values), tenantUniqueConstraints, sortPartitionFields, fileReferences, businessCodes, conditionalIndexes);
    }

    public EntityDefinition withTenantUniqueConstraints(TenantUniqueConstraintDefinition... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities, formulaRules,
                values == null ? List.of() : List.of(values), sortPartitionFields, fileReferences, businessCodes, conditionalIndexes);
    }

    public EntityDefinition withSortPartitionFields(String... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities, formulaRules,
                tenantUniqueConstraints, values == null ? List.of() : List.of(values), fileReferences, businessCodes, conditionalIndexes);
    }

    public EntityDefinition withFileReferences(Map<String, FileReferenceDefinition> values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities, formulaRules,
                tenantUniqueConstraints, sortPartitionFields, values, businessCodes, conditionalIndexes);
    }

    public EntityDefinition withBusinessCodes(Map<String, net.ximatai.muyun.spring.ability.code.CodeFieldDefinition> values) {
        var declared = EnumSet.copyOf(capabilities); declared.remove(EntityCapability.BUSINESS_CODE);
        return new EntityDefinition(alias, schemaName, tableName, name, fields, declared, formulaRules,
                tenantUniqueConstraints, sortPartitionFields, fileReferences, values, conditionalIndexes);
    }

    public EntityDefinition withConditionalIndexes(ConditionalIndexDefinition... values) {
        return new EntityDefinition(alias, schemaName, tableName, name, fields, capabilities, formulaRules,
                tenantUniqueConstraints, sortPartitionFields, fileReferences, businessCodes,
                values == null ? List.of() : List.of(values));
    }

    /**
     * Normalizes explicit composite declarations and existing single-field metadata unique flags
     * into the tenant-scoped unique facts consumed by schema and runtime validation.
     */
    public List<TenantUniqueConstraintDefinition> resolvedTenantUniqueConstraints() {
        Map<TenantUniqueConstraintDefinition.Identity, TenantUniqueConstraintDefinition> constraints = new LinkedHashMap<>();
        tenantUniqueConstraints.forEach(constraint -> constraints.put(constraint.identity(), constraint));
        conditionalIndexes.stream().filter(ConditionalIndexDefinition::unique)
                .map(ConditionalIndexDefinition::uniqueConstraint)
                .forEach(constraint -> {
                    var previous = constraints.put(constraint.identity(), constraint);
                    if (previous != null && !java.util.Objects.equals(previous.indexName(), constraint.indexName())) {
                        throw new IllegalArgumentException("conflicting conditional unique index names: " + constraint.indexName());
                    }
                });
        fields.stream()
                .filter(FieldDefinition::isPhysical)
                .filter(FieldDefinition::isUnique)
                .map(field -> new TenantUniqueConstraintDefinition(List.of(field.fieldName()),
                        (field.name() == null || field.name().isBlank() ? field.fieldName() : field.name()) + "不能重复，请修改后再保存。"))
                .forEach(constraint -> constraints.putIfAbsent(constraint.identity(), constraint));
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
