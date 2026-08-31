package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class DynamicRelationProjectionReadService {
    private final RelationProjectionReadService relationProjectionReadService;

    DynamicRelationProjectionReadService() {
        this((RelationProjectionReadService) null);
    }

    @Autowired
    public DynamicRelationProjectionReadService(ObjectProvider<RelationProjectionReadService> relationProjectionReadService) {
        this(relationProjectionReadService == null ? null : relationProjectionReadService.getIfAvailable());
    }

    DynamicRelationProjectionReadService(RelationProjectionReadService relationProjectionReadService) {
        this.relationProjectionReadService = relationProjectionReadService == null
                ? new RelationProjectionReadService()
                : relationProjectionReadService;
    }

    public boolean supportsListQuery(String moduleAlias,
                                     DynamicRecordService recordService,
                                     Set<String> outputFields) {
        return describeListQuery(moduleAlias, recordService, outputFields).supported();
    }

    /**
     * Resolves the complete web read contract for selected dynamic fields, including declared
     * reference presentation companions. Both SQL and ordinary record reads must use this set.
     */
    public Set<String> resolveListOutputFields(String moduleAlias,
                                               DynamicRecordService recordService,
                                               Set<String> outputFields) {
        if (recordService == null || outputFields == null || outputFields.isEmpty()) {
            return outputFields == null ? Set.of() : Set.copyOf(outputFields);
        }
        return withReferencePresentationFields(recordService.moduleDefinitions(), moduleAlias, outputFields);
    }

    public ProjectionQueryDescriptor describeListQuery(String moduleAlias,
                                                       DynamicRecordService recordService,
                                                       Set<String> outputFields) {
        if (moduleAlias == null || moduleAlias.isBlank()
                || recordService == null
                || outputFields == null
                || outputFields.isEmpty()) {
            return ProjectionQueryDescriptor.unsupported(moduleAlias, "dynamic_ui_config_list",
                    outputFields, ProjectionQueryFallbackReason.MISSING_PROJECTION);
        }
        List<ModuleDefinition> dynamicDefinitions = recordService.moduleDefinitions();
        Set<String> resolvedOutputFields = resolveListOutputFields(moduleAlias, recordService, outputFields);
        RecordReadProjection projection = projection(moduleAlias, resolvedOutputFields);
        if (hasNonIdReferenceKey(dynamicDefinitions, moduleAlias)) {
            return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.NON_ID_REFERENCE_KEY);
        }
        if (hasProtectedProjectionFields(dynamicDefinitions, moduleAlias, resolvedOutputFields)) {
            return ProjectionQueryDescriptor.unsupported(projection, ProjectionQueryFallbackReason.PROTECTED_FIELD);
        }
        if (!supportsOutputFields(dynamicDefinitions, moduleAlias, resolvedOutputFields)) {
            return ProjectionQueryDescriptor.unsupported(projection,
                    ProjectionQueryFallbackReason.UNSUPPORTED_OUTPUT_FIELD);
        }
        List<StaticModuleDefinition> definitions = DynamicRelationProjectionDefinitionAdapter.adapt(dynamicDefinitions);
        StaticModuleDefinition definition = staticDefinition(definitions, moduleAlias);
        if (definition == null) {
            return ProjectionQueryDescriptor.unsupported(projection,
                    ProjectionQueryFallbackReason.MISSING_DEFINITION);
        }
        return relationProjectionReadService.describeListQuery(definitions, definition, projection);
    }

    public Optional<PageResult<DynamicRecord>> queryList(String moduleAlias,
                                                         DynamicRecordService recordService,
                                                         Set<String> outputFields,
                                                         Criteria criteria,
                                                         PageRequest pageRequest,
                                                         Sort... sorts) {
        if (moduleAlias == null || moduleAlias.isBlank()
                || recordService == null
                || outputFields == null
                || outputFields.isEmpty()) {
            return Optional.empty();
        }
        List<ModuleDefinition> dynamicDefinitions = recordService.moduleDefinitions();
        Set<String> resolvedOutputFields = resolveListOutputFields(moduleAlias, recordService, outputFields);
        if (hasNonIdReferenceKey(dynamicDefinitions, moduleAlias)) {
            return Optional.empty();
        }
        if (hasProtectedProjectionFields(dynamicDefinitions, moduleAlias, resolvedOutputFields)) {
            return Optional.empty();
        }
        if (!supportsOutputFields(dynamicDefinitions, moduleAlias, resolvedOutputFields)) {
            return Optional.empty();
        }
        List<StaticModuleDefinition> definitions = DynamicRelationProjectionDefinitionAdapter.adapt(dynamicDefinitions);
        StaticModuleDefinition definition = staticDefinition(definitions, moduleAlias);
        if (definition == null) {
            return Optional.empty();
        }
        RecordReadProjection projection = projection(moduleAlias, resolvedOutputFields);
        PageResult<Map<String, Object>> page = recordService.withQueryReadScope(moduleAlias, criteria,
                scopedCriteria -> relationProjectionReadService.queryListWithInternalFields(
                        definitions,
                        definition,
                        projection,
                        scopedCriteria,
                        pageRequest,
                        sorts
                ).orElse(null));
        if (page == null) {
            return Optional.empty();
        }
        EntityDefinition entity = mainEntity(dynamicDefinitions, moduleAlias);
        List<DynamicRecord> records = page.getRecords().stream()
                .map(row -> record(entity, row))
                .toList();
        return Optional.of(PageResult.of(records, page.getTotal(), pageRequest));
    }

    private boolean supportsOutputFields(List<ModuleDefinition> definitions,
                                         String moduleAlias,
                                         Set<String> outputFields) {
        ModuleDefinition definition = dynamicDefinition(definitions, moduleAlias);
        if (definition == null) {
            return false;
        }
        EntityDefinition mainEntity = mainEntity(definition);
        Set<String> supportedFields = new java.util.LinkedHashSet<>();
        fields(mainEntity).values().stream()
                .filter(FieldDefinition::isPhysical)
                .map(FieldDefinition::fieldName)
                .forEach(supportedFields::add);
        definition.references().stream()
                .filter(reference -> mainEntity.alias().equals(reference.sourceEntityAlias()))
                .filter(reference -> reference.cardinality() == ReferenceCardinality.ONE)
                .flatMap(reference -> reference.projections().stream())
                .map(ReferenceProjection::outputField)
                .forEach(supportedFields::add);
        return supportedFields.containsAll(outputFields);
    }

    /**
     * The SQL relation-projection planner joins source values to target primary keys.  A
     * configured alternate reference key is valid at runtime, but must use the generic record
     * read path until that planner has an explicit target-key join contract.
     */
    private boolean hasNonIdReferenceKey(List<ModuleDefinition> definitions, String moduleAlias) {
        ModuleDefinition definition = dynamicDefinition(definitions, moduleAlias);
        if (definition == null) return false;
        EntityDefinition mainEntity = mainEntity(definition);
        return definition.references().stream()
                .filter(reference -> mainEntity.alias().equals(reference.sourceEntityAlias()))
                .filter(reference -> reference.cardinality() == ReferenceCardinality.ONE)
                .anyMatch(reference -> !"id".equals(reference.plan().targetKeyField()));
    }

    /**
     * A list selects its business field, while the read model must also return the presentation
     * companion declared by that field's reference. This keeps the SQL projection path aligned
     * with the generic web cells instead of degrading a reference into its stored ID.
     */
    private Set<String> withReferencePresentationFields(List<ModuleDefinition> definitions,
                                                        String moduleAlias,
                                                        Set<String> outputFields) {
        LinkedHashSet<String> fields = new LinkedHashSet<>(outputFields);
        ModuleDefinition definition = dynamicDefinition(definitions, moduleAlias);
        if (definition == null) {
            return java.util.Collections.unmodifiableSet(fields);
        }
        EntityDefinition mainEntity = mainEntity(definition);
        definition.references().stream()
                .filter(reference -> mainEntity.alias().equals(reference.sourceEntityAlias()))
                .filter(reference -> reference.cardinality() == ReferenceCardinality.ONE)
                .filter(reference -> fields.contains(reference.sourceField()))
                .flatMap(reference -> reference.projections().stream())
                .map(ReferenceProjection::outputField)
                .forEach(fields::add);
        return java.util.Collections.unmodifiableSet(fields);
    }

    private StaticModuleDefinition staticDefinition(List<StaticModuleDefinition> definitions, String moduleAlias) {
        return definitions.stream()
                .filter(item -> item.moduleAlias().equals(moduleAlias))
                .findFirst()
                .orElse(null);
    }

    private boolean hasProtectedProjectionFields(List<ModuleDefinition> definitions,
                                                 String moduleAlias,
                                                 Set<String> outputFields) {
        ModuleDefinition definition = dynamicDefinition(definitions, moduleAlias);
        if (definition == null) {
            return false;
        }
        EntityDefinition mainEntity = mainEntity(definition);
        Map<String, FieldDefinition> mainFields = fields(mainEntity);
        Map<String, ModuleDefinition> definitionsByAlias = definitionsByAlias(definitions);
        for (String outputField : outputFields) {
            FieldDefinition mainField = mainFields.get(outputField);
            if (protectedField(mainField)) {
                return true;
            }
            if (protectedReferenceProjection(definition, mainEntity, mainFields, definitionsByAlias, outputField)) {
                return true;
            }
        }
        return false;
    }

    private boolean protectedReferenceProjection(ModuleDefinition definition,
                                                 EntityDefinition mainEntity,
                                                 Map<String, FieldDefinition> mainFields,
                                                 Map<String, ModuleDefinition> definitionsByAlias,
                                                 String outputField) {
        for (EntityReferenceDefinition reference : definition.references()) {
            if (!mainEntity.alias().equals(reference.sourceEntityAlias())
                    || reference.cardinality() != ReferenceCardinality.ONE) {
                continue;
            }
            FieldDefinition sourceField = mainFields.get(reference.sourceField());
            if (storageProtectedField(sourceField)) {
                return true;
            }
            ReferenceProjection projection = reference.projections().stream()
                    .filter(item -> item.outputField().equals(outputField))
                    .findFirst()
                    .orElse(null);
            if (projection == null) {
                continue;
            }
            ModuleDefinition targetDefinition = definitionsByAlias.get(reference.target().moduleAlias());
            if (targetDefinition == null || !reference.target().entityAlias().equals(mainEntity(targetDefinition).alias())) {
                continue;
            }
            FieldDefinition targetField = fields(mainEntity(targetDefinition)).get(projection.targetField());
            if (storageProtectedField(targetField)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, ModuleDefinition> definitionsByAlias(List<ModuleDefinition> definitions) {
        return definitions.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ModuleDefinition::moduleAlias,
                        item -> item,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
    }

    private boolean protectedField(FieldDefinition field) {
        return field != null && field.protection().enabled();
    }

    private boolean storageProtectedField(FieldDefinition field) {
        return field != null && field.protection().hasStorageProtection();
    }

    private Map<String, FieldDefinition> fields(EntityDefinition entity) {
        return entity.fields().stream()
                .collect(java.util.stream.Collectors.toMap(
                        FieldDefinition::fieldName,
                        field -> field,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ));
    }

    private RecordReadProjection projection(String moduleAlias, Set<String> outputFields) {
        return new RecordReadProjection(
                moduleAlias,
                "dynamic_ui_config_list",
                outputFields.stream().map(ViewFieldRef::main).toList(),
                List.of(
                        StandardEntitySchema.ID_FIELD,
                        StandardEntitySchema.TENANT_ID_FIELD,
                        StandardEntitySchema.VERSION_FIELD
                ),
                List.of()
        );
    }

    private EntityDefinition mainEntity(List<ModuleDefinition> definitions, String moduleAlias) {
        ModuleDefinition definition = dynamicDefinition(definitions, moduleAlias);
        if (definition == null) {
            throw new IllegalArgumentException("dynamic module definition not found: " + moduleAlias);
        }
        return mainEntity(definition);
    }

    private ModuleDefinition dynamicDefinition(List<ModuleDefinition> definitions, String moduleAlias) {
        return definitions.stream()
                .filter(item -> item.moduleAlias().equals(moduleAlias))
                .findFirst()
                .orElse(null);
    }

    private EntityDefinition mainEntity(ModuleDefinition definition) {
        if (definition.mainEntityAlias() == null || definition.mainEntityAlias().isBlank()) {
            return definition.entities().getFirst();
        }
        return definition.entities().stream()
                .filter(entity -> definition.mainEntityAlias().equals(entity.alias()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dynamic module main entity is not declared: "
                        + definition.moduleAlias() + "." + definition.mainEntityAlias()));
    }

    private DynamicRecord record(EntityDefinition entity, Map<String, Object> row) {
        DynamicRecord record = new DynamicRecord(entity);
        record.setId(value(row, "id"));
        record.setTenantId(value(row, "tenantId"));
        Object version = row.get("version");
        if (version instanceof Number number) {
            record.setVersion(number.intValue());
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String fieldName = entry.getKey();
            if ("id".equals(fieldName) || "tenantId".equals(fieldName) || "version".equals(fieldName)) {
                continue;
            }
            record.putProjectedValue(fieldName, entry.getValue());
        }
        return record;
    }

    private String value(Map<String, Object> row, String field) {
        Object value = row.get(field);
        return value == null ? null : String.valueOf(value);
    }
}
