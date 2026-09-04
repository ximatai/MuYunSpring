package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityAssociationViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceAffectDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceFilterDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.platform.metadata.Metadata;
import net.ximatai.muyun.spring.platform.metadata.MetadataCapabilityCatalog;
import net.ximatai.muyun.spring.platform.metadata.MetadataCapabilityResolution;
import net.ximatai.muyun.spring.platform.metadata.MetadataField;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldDefinitionCompiler;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfig;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldReferenceConfigService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.MetadataSystemFieldCatalog;
import net.ximatai.muyun.spring.platform.metadata.MetadataView;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataViewService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFormulaRuleService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataField;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffect;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldAffectService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilter;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldFilterService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilityPolicy;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.workflow.DynamicWorkflowActionExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PlatformModuleDefinitionCompiler {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformModuleService moduleService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final MetadataFieldDefinitionCompiler fieldDefinitionCompiler;
    private final MetadataFieldReferenceConfigService referenceConfigService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataViewService viewService;
    private final MetadataViewFieldService viewFieldService;
    private final PlatformModuleActionService actionService;
    private final ModuleMetadataFormulaRuleService formulaRuleService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final ModuleMetadataFieldFilterService moduleFieldFilterService;
    private final ModuleMetadataFieldAffectService moduleFieldAffectService;
    private final ModuleDefinitionValidator validator;

    public PlatformModuleDefinitionCompiler(PlatformModuleService moduleService,
                                            MetadataService metadataService,
                                            MetadataFieldService fieldService,
                                            MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                            MetadataFieldReferenceConfigService referenceConfigService,
                                            ModuleMetadataRelationService relationService,
                                            MetadataViewService viewService,
                                            MetadataViewFieldService viewFieldService,
                                            PlatformModuleActionService actionService,
                                            ModuleMetadataFormulaRuleService formulaRuleService) {
        this(moduleService, metadataService, fieldService, fieldDefinitionCompiler, referenceConfigService, relationService,
                viewService, viewFieldService, actionService, formulaRuleService, null, null, null,
                new ModuleDefinitionValidator());
    }

    public PlatformModuleDefinitionCompiler(PlatformModuleService moduleService,
                                            MetadataService metadataService,
                                            MetadataFieldService fieldService,
                                            MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                            MetadataFieldReferenceConfigService referenceConfigService,
                                            ModuleMetadataRelationService relationService,
                                            MetadataViewService viewService,
                                            MetadataViewFieldService viewFieldService,
                                            PlatformModuleActionService actionService,
                                            ModuleMetadataFormulaRuleService formulaRuleService,
                                            ModuleMetadataFieldService moduleFieldService) {
        this(moduleService, metadataService, fieldService, fieldDefinitionCompiler, referenceConfigService, relationService,
                viewService, viewFieldService, actionService, formulaRuleService, moduleFieldService, null, null,
                new ModuleDefinitionValidator());
    }

    public PlatformModuleDefinitionCompiler(PlatformModuleService moduleService,
                                            MetadataService metadataService,
                                            MetadataFieldService fieldService,
                                            MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                            MetadataFieldReferenceConfigService referenceConfigService,
                                            ModuleMetadataRelationService relationService,
                                            MetadataViewService viewService,
                                            MetadataViewFieldService viewFieldService,
                                            PlatformModuleActionService actionService,
                                            ModuleMetadataFormulaRuleService formulaRuleService,
                                            ModuleDefinitionValidator validator) {
        this(moduleService, metadataService, fieldService, fieldDefinitionCompiler, referenceConfigService, relationService,
                viewService, viewFieldService, actionService, formulaRuleService, null, null, null, validator);
    }

    @Autowired
    public PlatformModuleDefinitionCompiler(PlatformModuleService moduleService,
                                            MetadataService metadataService,
                                            MetadataFieldService fieldService,
                                            MetadataFieldDefinitionCompiler fieldDefinitionCompiler,
                                            MetadataFieldReferenceConfigService referenceConfigService,
                                            ModuleMetadataRelationService relationService,
                                            MetadataViewService viewService,
                                            MetadataViewFieldService viewFieldService,
                                            PlatformModuleActionService actionService,
                                            ModuleMetadataFormulaRuleService formulaRuleService,
                                            ModuleMetadataFieldService moduleFieldService,
                                            ModuleMetadataFieldFilterService moduleFieldFilterService,
                                            ModuleMetadataFieldAffectService moduleFieldAffectService,
                                            ModuleDefinitionValidator validator) {
        this.moduleService = moduleService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
        this.fieldDefinitionCompiler = fieldDefinitionCompiler;
        this.referenceConfigService = referenceConfigService;
        this.relationService = relationService;
        this.viewService = viewService;
        this.viewFieldService = viewFieldService;
        this.actionService = actionService;
        this.formulaRuleService = formulaRuleService;
        this.moduleFieldService = moduleFieldService;
        this.moduleFieldFilterService = moduleFieldFilterService;
        this.moduleFieldAffectService = moduleFieldAffectService;
        this.validator = validator;
    }

    public ModuleDefinition compile(String moduleAlias) {
        PlatformModule module = requireDynamicModule(moduleAlias);
        List<ModuleMetadataRelation> relations = relations(moduleAlias);
        ModuleMetadataRelation mainRelation = requireMainRelation(moduleAlias, relations);
        Map<String, Metadata> metadataById = metadataById(relations);
        List<EntityDefinition> entities = relations.stream()
                .map(relation -> entity(relation, metadataById.get(relation.getMetadataId()), relations))
                .toList();
        List<EntityRelationDefinition> childRelations = relations.stream()
                .filter(relation -> relation.getRelationRole() == RelationRole.CHILD)
                .map(relation -> childRelation(relation, metadataById))
                .toList();
        List<EntityReferenceDefinition> references = references(module.getAlias(), relations, metadataById);
        List<EntityViewDefinition> views = views(relations, metadataById);
        List<EntityActionDefinition> actions = actions(module.getAlias(), mainRelation, relations, metadataById,
                entities);
        List<EntityAssociationViewDefinition> associationViews = associationViews(module.getAlias(), childRelations,
                references);
        String mainEntityAlias = metadataById.get(mainRelation.getMetadataId()).getAlias();
        ModuleDefinition definition = ModuleDefinition.builder(module.getAlias(), module.getTitle())
                .entities(entities)
                .relations(childRelations)
                .references(references)
                .views(views)
                .associationViews(associationViews)
                .actions(actions)
                .mainEntityAlias(mainEntityAlias)
                .build();
        validator.validate(definition);
        if (!mainRelation.getMetadataId().equals(relations.getFirst().getMetadataId())) {
            return orderMainEntityFirst(definition, mainRelation, metadataById);
        }
        return definition;
    }

    private PlatformModule requireDynamicModule(String moduleAlias) {
        PlatformModule module = moduleService.select(moduleAlias);
        if (module == null) {
            throw new PlatformException("Dynamic runtime refresh requires existing module: " + moduleAlias);
        }
        if (module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new PlatformException("Dynamic runtime refresh requires DYNAMIC module: " + moduleAlias);
        }
        return module;
    }

    private List<ModuleMetadataRelation> relations(String moduleAlias) {
        return relationService.list(
                Criteria.of().eq("moduleAlias", moduleAlias),
                ALL,
                Sort.asc(PlatformAbilityFields.SORT_FIELD)
        );
    }

    private ModuleMetadataRelation requireMainRelation(String moduleAlias, List<ModuleMetadataRelation> relations) {
        return relations.stream()
                .filter(relation -> relation.getRelationRole() == RelationRole.MAIN)
                .findFirst()
                .orElseThrow(() -> new PlatformException("Dynamic module requires MAIN metadata relation: " + moduleAlias));
    }

    private Map<String, Metadata> metadataById(List<ModuleMetadataRelation> relations) {
        Map<String, Metadata> values = new LinkedHashMap<>();
        for (ModuleMetadataRelation relation : relations) {
            Metadata metadata = metadataService.select(relation.getMetadataId());
            if (metadata == null) {
                throw new PlatformException("Module relation points to missing metadata: " + relation.getMetadataId());
            }
            values.put(metadata.getId(), metadata);
        }
        return values;
    }

    private EntityDefinition entity(ModuleMetadataRelation relation,
                                    Metadata metadata,
                                    List<ModuleMetadataRelation> relations) {
        List<MetadataField> metadataFields = metadataFields(metadata.getId());
        MetadataCapabilityResolution capabilityResolution = MetadataCapabilityCatalog.resolve(metadata,
                relation.getRelationRole(), metadataFields);
        List<FieldDefinition> fields = MetadataCapabilityCatalog.mergeDeclaredMetadataFields(capabilityResolution,
                fields(metadataFields, relation.getId()));
        if (relation.getRelationRole() == RelationRole.CHILD) {
            ModuleMetadataCapabilityPolicy.validateChildDefinition(metadata, fields);
        }
        EnumSet<EntityCapability> capabilities = capabilities(metadata, capabilityResolution, fields);
        if (relations.stream().anyMatch(child -> metadata.getId().equals(child.getParentMetadataId()))) {
            capabilities.add(EntityCapability.CHILD_RELATION);
        }
        return new EntityDefinition(metadata.getAlias(),
                metadata.getSchemaName(),
                metadata.getTableName(),
                metadata.getTitle(),
                fields,
                capabilities,
                formulaRules(relation),
                List.of(),
                metadata.getSortPartitionFields() == null ? List.of() : List.copyOf(metadata.getSortPartitionFields()));
    }

    private List<EntityFormulaRuleDefinition> formulaRules(ModuleMetadataRelation relation) {
        return formulaRuleService.listByRelationIds(List.of(relation.getId())).stream()
                .map(formulaRuleService::compile)
                .toList();
    }

    private List<FieldDefinition> fields(List<MetadataField> metadataFields, String relationId) {
        List<MetadataField> runtimeFields = metadataFields.stream()
                .filter(field -> !MetadataSystemFieldCatalog.isRuntimeReserved(field))
                .toList();
        if (moduleFieldService != null) {
            List<ModuleMetadataField> moduleFields = moduleFieldService.listByRelationId(relationId);
            if (!moduleFields.isEmpty()) {
                List<FieldDefinition> definitions = new ArrayList<>();
                Set<String> configuredFieldIds = new LinkedHashSet<>();
                for (ModuleMetadataField moduleField : moduleFields) {
                    MetadataField field = requireField(moduleField);
                    if (MetadataSystemFieldCatalog.isRuntimeReserved(field)) continue;
                    configuredFieldIds.add(moduleField.getMetadataFieldId());
                    definitions.add(fieldDefinitionCompiler.compile(field, relationId, moduleField));
                }
                runtimeFields.stream()
                        .filter(field -> !configuredFieldIds.contains(field.getId()))
                        .map(field -> fieldDefinitionCompiler.compile(field, relationId))
                        .forEach(definitions::add);
                return definitions;
            }
        }
        return runtimeFields.stream()
                .map(field -> fieldDefinitionCompiler.compile(field, relationId))
                .toList();
    }

    private MetadataField requireField(ModuleMetadataField moduleField) {
        MetadataField field = fieldService.select(moduleField.getMetadataFieldId());
        if (field == null) {
            throw new PlatformException("Module field points to missing metadata field: "
                    + moduleField.getMetadataFieldId());
        }
        return field;
    }

    private List<MetadataField> metadataFields(String metadataId) {
        return fieldService.list(
                        Criteria.of().eq("metadataId", metadataId),
                        ALL,
                        Sort.asc(PlatformAbilityFields.SORT_FIELD)
                );
    }

    private EnumSet<EntityCapability> capabilities(Metadata metadata, MetadataCapabilityResolution capabilityResolution,
                                                    List<FieldDefinition> fields) {
        EnumSet<EntityCapability> capabilities = EnumSet.noneOf(EntityCapability.class);
        capabilities.add(EntityCapability.CRUD);
        for (FieldDefinition field : fields) {
            if (field.isTitle()) {
                capabilities.add(EntityCapability.REFERENCE);
            }
            if (isApprovalField(field)) {
                capabilities.add(EntityCapability.APPROVAL);
            }
        }
        capabilities.addAll(capabilityResolution.capabilities());
        if (Boolean.TRUE.equals(metadata.getDataScopeEnabled())) capabilities.add(EntityCapability.DATA_SCOPE);
        return capabilities;
    }

    private boolean isApprovalField(FieldDefinition field) {
        return PlatformAbilityFields.APPROVAL_INSTANCE_FIELD.equals(field.fieldName())
                || PlatformAbilityFields.APPROVAL_STATUS_FIELD.equals(field.fieldName())
                || PlatformAbilityFields.APPROVAL_SUBMITTED_BY_FIELD.equals(field.fieldName())
                || PlatformAbilityFields.APPROVAL_SUBMITTED_AT_FIELD.equals(field.fieldName())
                || PlatformAbilityFields.APPROVAL_COMPLETED_AT_FIELD.equals(field.fieldName())
                || PlatformAbilityFields.APPROVAL_INSTANCE_COLUMN.equals(field.columnName())
                || PlatformAbilityFields.APPROVAL_STATUS_COLUMN.equals(field.columnName())
                || PlatformAbilityFields.APPROVAL_SUBMITTED_BY_COLUMN.equals(field.columnName())
                || PlatformAbilityFields.APPROVAL_SUBMITTED_AT_COLUMN.equals(field.columnName())
                || PlatformAbilityFields.APPROVAL_COMPLETED_AT_COLUMN.equals(field.columnName());
    }

    private EntityRelationDefinition childRelation(ModuleMetadataRelation relation, Map<String, Metadata> metadataById) {
        Metadata parent = metadataById.get(relation.getParentMetadataId());
        Metadata child = metadataById.get(relation.getMetadataId());
        if (parent == null || child == null) {
            throw new PlatformException("Child relation metadata is incomplete: " + relation.getRelationAlias());
        }
        EntityRelationDefinition definition = EntityRelationDefinition.child(
                relation.getRelationAlias(),
                parent.getAlias(),
                child.getAlias(),
                relation.getForeignKey()
        );
        if (Boolean.TRUE.equals(relation.getAutoPopulate())) {
            definition = definition.withAutoPopulate();
        }
        return definition;
    }

    private List<EntityReferenceDefinition> references(String moduleAlias,
                                                       List<ModuleMetadataRelation> relations,
                                                       Map<String, Metadata> metadataById) {
        Set<String> childForeignKeys = relations.stream()
                .filter(relation -> relation.getRelationRole() == RelationRole.CHILD)
                .map(relation -> relation.getMetadataId() + ":" + relation.getForeignKey())
                .collect(java.util.stream.Collectors.toSet());
        List<EntityReferenceDefinition> references = new ArrayList<>();
        for (ModuleMetadataRelation relation : relations) {
            references.addAll(references(moduleAlias, relation, metadataById, childForeignKeys));
        }
        for (ModuleMetadataRelation relation : relations) {
            if (relation.getRelationRole() == RelationRole.CHILD) {
                references.add(childForeignKeyReference(moduleAlias, relation, metadataById));
            }
        }
        return List.copyOf(references);
    }

    private List<EntityReferenceDefinition> references(String moduleAlias,
                                                       ModuleMetadataRelation relation,
                                                       Map<String, Metadata> metadataById,
                                                       Set<String> childForeignKeys) {
        Metadata sourceMetadata = metadataById.get(relation.getMetadataId());
        List<EntityReferenceDefinition> references = new java.util.ArrayList<>();
        Set<String> moduleReferenceFieldIds = new LinkedHashSet<>();
        if (moduleFieldService != null) {
            for (ModuleMetadataField moduleField : moduleFieldService.listByRelationId(relation.getId())) {
                if (moduleField.getReferenceModuleAlias() != null && !moduleField.getReferenceModuleAlias().isBlank()) {
                    MetadataField sourceField = requireField(moduleField);
                    if (childForeignKeys.contains(sourceMetadata.getId() + ":" + sourceField.getFieldName())) {
                        throw new PlatformException("Child relation foreign key cannot use module reference configuration: "
                                + sourceField.getFieldName());
                    }
                    moduleReferenceFieldIds.add(moduleField.getMetadataFieldId());
                    references.add(moduleReference(moduleField, sourceMetadata));
                }
            }
        }
        references.addAll(metadataFields(sourceMetadata.getId()).stream()
                .filter(field -> !moduleReferenceFieldIds.contains(field.getId()))
                .filter(field -> !childForeignKeys.contains(sourceMetadata.getId() + ":" + field.getFieldName()))
                .map(field -> reference(moduleAlias, relation, sourceMetadata, field, metadataById))
                .filter(Objects::nonNull)
                .toList());
        return references;
    }

    private EntityReferenceDefinition childForeignKeyReference(String moduleAlias,
                                                                ModuleMetadataRelation relation,
                                                                Map<String, Metadata> metadataById) {
        Metadata parentMetadata = metadataById.get(relation.getParentMetadataId());
        Metadata childMetadata = metadataById.get(relation.getMetadataId());
        if (parentMetadata == null || childMetadata == null) {
            throw new PlatformException("Child relation metadata is incomplete: " + relation.getRelationAlias());
        }
        MetadataField foreignKey = metadataFields(childMetadata.getId()).stream()
                .filter(field -> relation.getForeignKey().equals(field.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new PlatformException("Child relation requires physical foreign key field: "
                        + relation.getForeignKey()));
        ModuleMetadataCapabilityPolicy.validateChildForeignKey(foreignKey);
        MetadataFieldReferenceConfig config = referenceConfigService.findForRelation(foreignKey.getId(), relation.getId());
        if (config == null) {
            return EntityReferenceDefinition.to(childMetadata.getAlias(), foreignKey.getFieldName(),
                    moduleAlias + "." + parentMetadata.getAlias());
        }
        ModuleMetadataCapabilityPolicy.validateChildForeignKeyReference(relation, foreignKey, config);
        return new EntityReferenceDefinition(
                childMetadata.getAlias(),
                foreignKey.getFieldName(),
                moduleAlias + "." + parentMetadata.getAlias(),
                config.getCardinality(),
                config.projections(), config.getTargetKeyField(), config.getTargetLabelField(), null, null, Set.of(), List.of(), List.of(),
                new ReferenceIntegrityPolicy(config.getTargetUnavailablePolicy())
        );
    }

    private EntityReferenceDefinition moduleReference(ModuleMetadataField moduleField,
                                                      Metadata sourceMetadata) {
        MetadataField sourceField = requireField(moduleField);
        ModuleMetadataRelation targetRelation = mainRelation(moduleField.getReferenceModuleAlias());
        Metadata targetMetadata = metadataService.select(targetRelation.getMetadataId());
        if (targetMetadata == null) {
            throw new PlatformException("Reference module points to missing metadata: "
                    + targetRelation.getMetadataId());
        }
        validateReferenceFields(moduleField, targetMetadata);
        return new EntityReferenceDefinition(
                sourceMetadata.getAlias(),
                sourceField.getFieldName(),
                targetRelation.getModuleAlias() + "." + targetMetadata.getAlias()
        ).withRuntimeConfig(
                moduleField.getReferenceModuleKeyField(),
                moduleField.getReferenceModuleLabelField(),
                moduleField.getReferenceGenerateRuleId(),
                moduleField.getReferenceQueryTemplateId(),
                moduleField.getReferenceModulePlusFields()
        ).withIntegrity(new ReferenceIntegrityPolicy(moduleField.getReferenceTargetUnavailablePolicy()))
        .withInteractionRules(
                referenceFilters(moduleField),
                referenceAffects(moduleField)
        );
    }

    private List<EntityReferenceFilterDefinition> referenceFilters(ModuleMetadataField moduleField) {
        if (moduleFieldFilterService == null) {
            return List.of();
        }
        return moduleFieldFilterService.list(Criteria.of().eq("moduleMetadataFieldId", moduleField.getId()),
                        ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .stream()
                .map(filter -> new EntityReferenceFilterDefinition(
                        moduleFieldName(filter.getFormFieldId()),
                        moduleFieldName(filter.getReferenceFieldId()),
                        filter.getOperator()))
                .toList();
    }

    private List<EntityReferenceAffectDefinition> referenceAffects(ModuleMetadataField moduleField) {
        if (moduleFieldAffectService == null) {
            return List.of();
        }
        return moduleFieldAffectService.list(Criteria.of().eq("moduleMetadataFieldId", moduleField.getId()),
                        ALL, Sort.asc(PlatformAbilityFields.SORT_FIELD))
                .stream()
                .map(affect -> new EntityReferenceAffectDefinition(
                        moduleFieldName(affect.getReferenceFieldId()),
                        moduleFieldName(affect.getTargetFieldId())))
                .toList();
    }

    private String moduleFieldName(String moduleMetadataFieldId) {
        if (moduleFieldService == null) {
            throw new PlatformException("Module field interaction rule requires moduleFieldService");
        }
        return moduleFieldService.resolve(moduleMetadataFieldId).fieldName();
    }

    private ModuleMetadataRelation mainRelation(String moduleAlias) {
        return relationService.list(Criteria.of()
                        .eq("moduleAlias", moduleAlias)
                        .eq("relationRole", RelationRole.MAIN),
                ALL).stream().findFirst().orElseThrow(() ->
                new PlatformException("Reference module requires main relation: " + moduleAlias));
    }

    private void validateReferenceFields(ModuleMetadataField moduleField, Metadata targetMetadata) {
        Set<String> fieldNames = metadataFields(targetMetadata.getId()).stream()
                .map(MetadataField::getFieldName)
                .collect(java.util.stream.Collectors.toSet());
        requireReferenceTargetField(fieldNames, moduleField.getReferenceModuleKeyField(), "reference key field");
        requireReferenceTargetField(fieldNames, moduleField.getReferenceModuleLabelField(), "reference label field");
        if (moduleField.getReferenceModulePlusFields() != null) {
            for (String plusField : moduleField.getReferenceModulePlusFields()) {
                // A plus field is a selection-projection path. The first target field is not
                // necessarily terminal; ModuleDefinitionValidator verifies its reference hops
                // against the complete compiled dynamic module.
                new net.ximatai.muyun.spring.ability.reference.ReferenceSelectionProjection(plusField);
            }
        }
    }

    private void requireReferenceTargetField(Set<String> fieldNames, String fieldName, String label) {
        if (fieldName == null || fieldName.isBlank()) {
            return;
        }
        if (!fieldNames.contains(fieldName)) {
            throw new PlatformException(label + " does not exist in reference target: " + fieldName);
        }
    }

    private EntityReferenceDefinition reference(String moduleAlias,
                                                ModuleMetadataRelation relation,
                                                Metadata sourceMetadata,
                                                MetadataField sourceField,
                                                Map<String, Metadata> metadataById) {
        MetadataFieldReferenceConfig config = referenceConfigService.findForRelation(sourceField.getId(), relation.getId());
        if (config == null) {
            return null;
        }
        ReferenceTarget target = referenceConfigService.resolveTarget(config, sourceField, relation);
        EntityReferenceDefinition definition = new EntityReferenceDefinition(
                sourceMetadata.getAlias(),
                sourceField.getFieldName(),
                target.qualifiedName(),
                config.getCardinality(),
                config.projections(), config.getTargetKeyField(), config.getTargetLabelField(), null, null, Set.of(), List.of(), List.of(),
                new ReferenceIntegrityPolicy(config.getTargetUnavailablePolicy())
        );
        return definition;
    }

    private List<EntityViewDefinition> views(List<ModuleMetadataRelation> relations, Map<String, Metadata> metadataById) {
        Map<String, ModuleMetadataRelation> relationById = relations.stream()
                .collect(java.util.stream.Collectors.toMap(ModuleMetadataRelation::getId, relation -> relation));
        return viewService.listByRelationIds(relations.stream().map(ModuleMetadataRelation::getId).toList()).stream()
                .map(view -> view(view, relationById, metadataById))
                .toList();
    }

    private EntityViewDefinition view(MetadataView view,
                                      Map<String, ModuleMetadataRelation> relationById,
                                      Map<String, Metadata> metadataById) {
        ModuleMetadataRelation relation = relationById.get(view.getRelationId());
        if (relation == null) {
            throw new PlatformException("View points to relation outside current module: " + view.getRelationId());
        }
        Metadata metadata = metadataById.get(relation.getMetadataId());
        if (metadata == null) {
            throw new PlatformException("View relation metadata is incomplete: " + view.getRelationId());
        }
        return new EntityViewDefinition(
                metadata.getAlias(),
                view.getViewType(),
                view.getTitle(),
                viewFieldService.listByViewId(view.getId()).stream()
                        .map(viewFieldService::compile)
                        .toList()
        );
    }

    private ModuleDefinition orderMainEntityFirst(ModuleDefinition definition,
                                                 ModuleMetadataRelation mainRelation,
                                                 Map<String, Metadata> metadataById) {
        String mainEntityAlias = metadataById.get(mainRelation.getMetadataId()).getAlias();
        List<EntityDefinition> ordered = definition.entities().stream()
                .sorted((left, right) -> Boolean.compare(!left.alias().equals(mainEntityAlias), !right.alias().equals(mainEntityAlias)))
                .toList();
        return definition.toBuilder().entities(ordered).build();
    }

    private List<EntityAssociationViewDefinition> associationViews(String moduleAlias,
                                                                   List<EntityRelationDefinition> childRelations,
                                                                   List<EntityReferenceDefinition> references) {
        return java.util.stream.Stream.concat(
                childRelations.stream().map(relation -> EntityAssociationViewDefinition.childRelation(
                        relation.code(),
                        relation.parentEntityAlias(),
                        moduleAlias,
                        relation.childEntityAlias(),
                        relation.code()
                )),
                references.stream().filter(reference -> isDynamicReferenceTarget(reference.target())).map(reference -> {
                    ReferenceTarget target = reference.target();
                    return EntityAssociationViewDefinition.reference(
                            reference.sourceField(),
                            reference.sourceEntityAlias(),
                            target.moduleAlias(),
                            target.entityAlias(),
                            reference.sourceField(),
                            reference.cardinality()
                    );
                })
        ).toList();
    }

    /**
     * Association views are backed by DynamicRecordRelationRuntime's target-page operation. A
     * static target already exposes the same picker through ReferenceAbility and must not be
     * represented as a fake dynamic association view.
     */
    private boolean isDynamicReferenceTarget(ReferenceTarget target) {
        PlatformModule targetModule = moduleService.select(target.moduleAlias());
        return targetModule != null && targetModule.getModuleKind() == ModuleKind.DYNAMIC;
    }

    private List<EntityActionDefinition> actions(String moduleAlias,
                                                 ModuleMetadataRelation mainRelation,
                                                 List<ModuleMetadataRelation> relations,
                                                 Map<String, Metadata> metadataById,
                                                 List<EntityDefinition> entities) {
        String mainEntityAlias = metadataById.get(mainRelation.getMetadataId()).getAlias();
        Map<String, Metadata> metadataByAlias = relations.stream()
                .map(relation -> metadataById.get(relation.getMetadataId()))
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(Metadata::getAlias, metadata -> metadata));
        List<EntityActionDefinition> actions = actionService.listByModuleAliases(List.of(moduleAlias)).stream()
                .map(action -> action(action, mainEntityAlias, metadataByAlias))
                .toList();
        return withWorkflowActions(actions, mainEntity(mainEntityAlias, entities));
    }

    private EntityDefinition mainEntity(String mainEntityAlias, List<EntityDefinition> entities) {
        return entities.stream()
                .filter(entity -> entity.alias().equals(mainEntityAlias))
                .findFirst()
                .orElseThrow(() -> new PlatformException("Main entity not found in module definition: " + mainEntityAlias));
    }

    private List<EntityActionDefinition> withWorkflowActions(List<EntityActionDefinition> configured,
                                                             EntityDefinition mainEntity) {
        Map<String, EntityActionDefinition> actions = new LinkedHashMap<>();
        configured.forEach(action -> actions.put(action.actionCode(), action));
        if (mainEntity.supports(EntityCapability.APPROVAL)) {
            actions.putIfAbsent("submitApproval", workflowAction(mainEntity.alias(), "submitApproval", "提交审批"));
        }
        return List.copyOf(actions.values());
    }

    private EntityActionDefinition workflowAction(String entityAlias, String actionCode, String title) {
        return new EntityActionDefinition(
                entityAlias,
                actionCode,
                title,
                true,
                EntityActionLevel.RECORD,
                EntityActionCategory.WORKFLOW,
                EntityActionAccessMode.AUTH_REQUIRED,
                true,
                false,
                null,
                null,
                null,
                EntityActionExecutorType.SERVICE,
                DynamicWorkflowActionExecutor.EXECUTOR_KEY
        );
    }

    private EntityActionDefinition action(PlatformModuleAction action,
                                          String mainEntityAlias,
                                          Map<String, Metadata> metadataByAlias) {
        String entityAlias = action.getEntityAlias() == null || action.getEntityAlias().isBlank()
                ? mainEntityAlias
                : action.getEntityAlias();
        if (!metadataByAlias.containsKey(entityAlias)) {
            throw new PlatformException("Module action points to entity outside current module: "
                    + action.getModuleAlias() + "." + action.getActionCode() + "." + entityAlias);
        }
        return new EntityActionDefinition(
                entityAlias,
                action.getActionCode(),
                action.getTitle(),
                Boolean.TRUE.equals(action.getEnabled()),
                action.getActionLevel(),
                action.getCategory(),
                action.effectiveAccessMode(),
                action.effectiveActionAuth(),
                action.effectiveDataAuth(),
                action.effectiveDefaultGrantPolicy(),
                inheritedActionCode(action),
                action.getAvailableExpression(),
                action.getUnavailableMessage(),
                action.getExecutorType(),
                action.getExecutorKey()
        );
    }

    private String inheritedActionCode(PlatformModuleAction action) {
        String permissionActionCode = action.getPermissionActionCode();
        if (permissionActionCode == null || permissionActionCode.isBlank()
                || permissionActionCode.equals(action.getActionCode())) {
            return null;
        }
        return permissionActionCode;
    }
}
