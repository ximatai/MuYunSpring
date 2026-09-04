package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a side-effect-free final-model preview for one metadata relation edit session. */
@Service
public class MetadataRelationChangeSetPreviewService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final FieldSpecService fieldSpecService;
    private final MetadataFieldReferenceConfigService referenceConfigService;
    private final MetadataFieldConfigService fieldConfigService;
    private final ModuleMetadataFieldService moduleFieldService;
    private final DynamicRecordService recordService;

    public MetadataRelationChangeSetPreviewService(PlatformModuleService moduleService,
                                                   ModuleMetadataRelationService relationService,
                                                   MetadataService metadataService,
                                                   MetadataFieldService fieldService,
                                                   FieldSpecService fieldSpecService) {
        this(moduleService, relationService, metadataService, fieldService, fieldSpecService, null, null);
    }

    public MetadataRelationChangeSetPreviewService(PlatformModuleService moduleService,
                                                   ModuleMetadataRelationService relationService,
                                                   MetadataService metadataService,
                                                   MetadataFieldService fieldService,
                                                   FieldSpecService fieldSpecService,
                                                   MetadataFieldReferenceConfigService referenceConfigService,
                                                   MetadataFieldConfigService fieldConfigService) {
        this(moduleService, relationService, metadataService, fieldService, fieldSpecService,
                referenceConfigService, fieldConfigService, null, null);
    }

    public MetadataRelationChangeSetPreviewService(PlatformModuleService moduleService,
                                                   ModuleMetadataRelationService relationService,
                                                   MetadataService metadataService,
                                                   MetadataFieldService fieldService,
                                                   FieldSpecService fieldSpecService,
                                                   MetadataFieldReferenceConfigService referenceConfigService,
                                                   MetadataFieldConfigService fieldConfigService,
                                                   ModuleMetadataFieldService moduleFieldService) {
        this(moduleService, relationService, metadataService, fieldService, fieldSpecService,
                referenceConfigService, fieldConfigService, moduleFieldService, null);
    }

    @Autowired
    public MetadataRelationChangeSetPreviewService(PlatformModuleService moduleService,
                                                   ModuleMetadataRelationService relationService,
                                                   MetadataService metadataService,
                                                   MetadataFieldService fieldService,
                                                   FieldSpecService fieldSpecService,
                                                   MetadataFieldReferenceConfigService referenceConfigService,
                                                   MetadataFieldConfigService fieldConfigService,
                                                   ModuleMetadataFieldService moduleFieldService,
                                                   DynamicRecordService recordService) {
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
        this.fieldSpecService = fieldSpecService;
        this.referenceConfigService = referenceConfigService;
        this.fieldConfigService = fieldConfigService;
        this.moduleFieldService = moduleFieldService;
        this.recordService = recordService;
    }

    public MetadataRelationChangeSetPreview preview(String moduleAlias, String relationId,
                                                    MetadataRelationChangeSetPreviewCommand command) {
        if (command == null) throw new IllegalArgumentException("metadata change-set preview command must not be null");
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        Context context = context(validModuleAlias, relationId);
        List<MetadataChangeSetValidationIssue> warnings = new ArrayList<>();
        List<MetadataChangeSetValidationIssue> errors = new ArrayList<>();
        Metadata metadata = context.metadata();
        if (metadata.getCapabilityDeclarations() == null) {
            warnings.add(new MetadataChangeSetValidationIssue(MetadataChangeSetValidationIssue.Severity.WARNING,
                    "LEGACY_CAPABILITY_INFERENCE", "capabilities", "当前能力由既有字段推导；首次发布将转为元数据声明。"));
        }
        if (!java.util.Objects.equals(command.expectedMetadataVersion(), metadata.getVersion())) {
            error(errors, "STALE_METADATA_VERSION", "metadata", "元数据版本已变化，请重新载入后预检。");
        }

        List<MetadataField> savedFields = fieldService.list(Criteria.of().eq("metadataId", metadata.getId()), ALL);
        Set<EntityCapability> effective = proposedCapabilities(context, savedFields, command.capabilitySelections(), errors);
        LinkedHashMap<String, MetadataField> finalFields = fieldsById(savedFields);
        List<MetadataChangeSetFieldImpact> fieldImpacts = new ArrayList<>();
        List<MetadataFieldChangeSetPlan> fieldMutations = new ArrayList<>();
        applyDrafts(context, finalFields, command.fieldDrafts(), fieldImpacts, fieldMutations, errors);
        appendCapabilityFieldPlan(metadata, effective, finalFields, fieldImpacts, errors);
        validateFinalFieldNames(finalFields.values(), errors);
        List<MetadataChangeSetSchemaImpact> schemaImpacts = schemaImpacts(metadata, fieldImpacts);
        MetadataRelationChangeSetPlan plan = new MetadataRelationChangeSetPlan(metadata.getId(), metadata.getVersion(),
                Set.copyOf(effective), command.capabilitySelections() != null && !command.capabilitySelections().isEmpty(),
                List.copyOf(fieldMutations));
        String fingerprint = fingerprint(plan);
        return new MetadataRelationChangeSetPreview(validModuleAlias, context.relation().getId(), metadata.getId(),
                metadata.getVersion(), Set.copyOf(effective), List.copyOf(fieldImpacts), List.copyOf(schemaImpacts),
                List.copyOf(warnings), List.copyOf(errors), fingerprint, plan);
    }

    private Set<EntityCapability> proposedCapabilities(Context context, List<MetadataField> savedFields,
                                                        Map<EntityCapability, Boolean> selections,
                                                        List<MetadataChangeSetValidationIssue> errors) {
        Set<EntityCapability> current = MetadataCapabilityCatalog.resolve(context.metadata(), context.relation().getRelationRole(), savedFields)
                .capabilities();
        EnumSet<EntityCapability> proposed = current.isEmpty() ? EnumSet.noneOf(EntityCapability.class) : EnumSet.copyOf(current);
        if (selections == null || selections.isEmpty()) return proposed;
        if (context.relation().getRelationRole() != RelationRole.MAIN) {
            error(errors, "CHILD_CAPABILITY_UNSUPPORTED", "capabilities", "子元数据不能变更模块能力。");
            return proposed;
        }
        boolean changed = false;
        for (Map.Entry<EntityCapability, Boolean> entry : selections.entrySet()) {
            EntityCapability capability = entry.getKey();
            if (!MetadataCapabilityCatalog.isMutableInFirstRelease(capability)) {
                error(errors, "CAPABILITY_NOT_MUTABLE", String.valueOf(capability), "当前能力不支持动态声明。");
                continue;
            }
            boolean enabled = Boolean.TRUE.equals(entry.getValue());
            if (!enabled && current.contains(capability)) {
                error(errors, "NON_ADDITIVE_CAPABILITY", capability.name(), "首批发布仅支持启用能力，不能禁用或移除字段。");
                continue;
            }
            if (enabled && !current.contains(capability)) {
                proposed.add(capability);
                changed = true;
            }
        }
        if (Boolean.TRUE.equals(selections.get(EntityCapability.TREE))
                && Boolean.FALSE.equals(selections.get(EntityCapability.SORT))) {
            error(errors, "CAPABILITY_DEPENDENCY", "TREE", "树能力依赖排序能力，不能同时关闭排序。");
        }
        if (proposed.contains(EntityCapability.TREE)) proposed.add(EntityCapability.SORT);
        if (changed && metadataHasChildUsage(context.metadata().getId())) {
            error(errors, "CHILD_RELATION_BLOCK", "capabilities", "元数据参与子关系时，不能变更结构能力。");
        }
        return proposed;
    }

    private void applyDrafts(Context context, LinkedHashMap<String, MetadataField> finalFields,
                             List<MetadataFieldChangeSetDraft> drafts,
                             List<MetadataChangeSetFieldImpact> impacts,
                             List<MetadataFieldChangeSetPlan> mutations,
                             List<MetadataChangeSetValidationIssue> errors) {
        if (drafts == null) return;
        Set<String> touched = new HashSet<>();
        for (MetadataFieldChangeSetDraft draft : drafts) {
            if (draft == null || draft.operation() == null) {
                error(errors, "INVALID_FIELD_DRAFT", "fields", "字段草稿缺少操作类型。");
                continue;
            }
            String key = draft.fieldId();
            switch (draft.operation()) {
                case ADD -> addDraft(context, finalFields, draft, impacts, mutations, errors);
                case UPDATE -> updateDraft(context, finalFields, draft, impacts, mutations, errors, touched, key);
                case DELETE -> deleteDraft(context, finalFields, draft, impacts, errors, touched, key);
            }
        }
    }

    private void addDraft(Context context, Map<String, MetadataField> fields, MetadataFieldChangeSetDraft draft,
                          List<MetadataChangeSetFieldImpact> impacts, List<MetadataFieldChangeSetPlan> mutations,
                          List<MetadataChangeSetValidationIssue> errors) {
        MetadataField field = draft.field();
        if (!validateDraftField(field, "new", errors)) return;
        if (Boolean.TRUE.equals(field.getSystemManaged()) || field.getFieldOwnership() == MetadataFieldOwnership.STANDARD) {
            error(errors, "PROTECTED_FIELD", field.getFieldName(), "新增字段不能声明为平台或系统托管字段。");
            return;
        }
        if (fields.values().stream().anyMatch(existing -> same(existing.getFieldName(), field.getFieldName()))) {
            error(errors, "DUPLICATE_FIELD_NAME", field.getFieldName(), "字段名在最终模型中重复。");
            return;
        }
        if (fields.values().stream().anyMatch(existing -> same(existing.getColumnName(), field.getColumnName()))) {
            error(errors, "DUPLICATE_COLUMN_NAME", field.getColumnName(), "物理列名在最终模型中重复。");
            return;
        }
        MetadataField normalized = newBusinessField(field);
        normalized.setMetadataId(context.metadata().getId());
        MetadataFieldPropertyChangeSetPlan property = propertyPlan(context, normalized, draft.property(), null, errors);
        if (draft.property() != null && property == null) return;
        String syntheticId = "new:" + normalized.getFieldName();
        fields.put(syntheticId, normalized);
        mutations.add(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.ADD, null, null, normalized, property));
        impacts.add(new MetadataChangeSetFieldImpact("ADD", field.getFieldName(), field.getColumnName(), false, "新增业务字段。"));
    }

    private void updateDraft(Context context, Map<String, MetadataField> fields, MetadataFieldChangeSetDraft draft,
                             List<MetadataChangeSetFieldImpact> impacts, List<MetadataFieldChangeSetPlan> mutations,
                             List<MetadataChangeSetValidationIssue> errors,
                             Set<String> touched, String fieldId) {
        MetadataField existing = fields.get(fieldId);
        if (fieldId == null || existing == null || !touched.add(fieldId)) {
            error(errors, "INVALID_FIELD_UPDATE", String.valueOf(fieldId), "字段修改必须唯一地指向已存在字段。");
            return;
        }
        if (protectedField(existing, context.relation())) {
            error(errors, "PROTECTED_FIELD", existing.getFieldName(), "受保护字段不能在编辑会话中修改。");
            return;
        }
        if (legacyPropertyLocked(existing, context.relation())) {
            error(errors, "LEGACY_FIELD_PROPERTY_LOCKED", existing.getFieldName(),
                    "字段仍使用旧模块字段引用或字典配置，迁移前不能通过新编排链路修改。");
            return;
        }
        if (!java.util.Objects.equals(draft.expectedFieldVersion(), existing.getVersion())) {
            error(errors, "STALE_FIELD_VERSION", existing.getFieldName(), "字段版本已变化，请重新载入后预检。");
            return;
        }
        MetadataField field = draft.field();
        if (!validateDraftField(field, existing.getFieldName(), errors)) return;
        if (!same(existing.getFieldName(), field.getFieldName()) || !same(existing.getColumnName(), field.getColumnName())
                || !same(existing.getFieldOwnership(), field.getFieldOwnership())
                || !same(existing.getFieldForm(), field.getFieldForm())
                || !same(existing.getOwnerFieldId(), field.getOwnerFieldId())
                || !same(existing.getFieldRole(), field.getFieldRole())
                || !same(existing.getSystemManaged(), field.getSystemManaged())) {
            error(errors, "NON_ADDITIVE_FIELD_MUTATION", existing.getFieldName(),
                    "首批发布仅允许修改业务展示和约束属性，不能修改字段结构、归属或平台管理属性。");
            return;
        }
        if (!same(existing.getFieldSpecAlias(), field.getFieldSpecAlias())
                && !validateFieldSpecChange(context, existing, field, errors)) return;
        MetadataField normalized = overlayBusinessAttributes(existing, field);
        MetadataFieldPropertyChangeSetPlan property = propertyPlan(context, normalized, draft.property(), existing, errors);
        if (draft.property() != null && property == null) return;
        fields.put(fieldId, normalized);
        mutations.add(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.UPDATE, fieldId,
                draft.expectedFieldVersion(), normalized, property));
        impacts.add(new MetadataChangeSetFieldImpact("UPDATE", field.getFieldName(), field.getColumnName(), false, "更新字段元数据。"));
    }

    /**
     * A physical specification change is safe on an empty entity.  Once business data exists we
     * deliberately keep the first-release surface to a widening text conversion only: it neither
     * truncates values nor changes their runtime meaning.  Other conversions need an explicit
     * migration policy rather than an optimistic generic cast.
     */
    private boolean validateFieldSpecChange(Context context, MetadataField existing, MetadataField proposed,
                                            List<MetadataChangeSetValidationIssue> errors) {
        if (recordService == null) {
            error(errors, "FIELD_SPEC_CHANGE_UNAVAILABLE", existing.getFieldName(),
                    "当前环境未配置数据预检，不能修改存储字段规格。");
            return false;
        }
        long records = recordService.schemaGovernanceFacts().countPhysicalRecords(context.relation().getModuleAlias(), context.metadata().getAlias(),
                Criteria.of());
        if (records == 0 || fieldSpecService.allowsDataSafeTarget(
                existing.getFieldSpecAlias(), proposed.getFieldSpecAlias())) return true;
        error(errors, "FIELD_SPEC_CHANGE_WITH_DATA", existing.getFieldName(),
                "字段“" + existing.getTitle() + "”已有 " + records
                        + " 条业务数据；目标字段规格不在当前规格声明的数据安全转换范围内。");
        return false;
    }

    private void deleteDraft(Context context, Map<String, MetadataField> fields, MetadataFieldChangeSetDraft draft,
                             List<MetadataChangeSetFieldImpact> impacts, List<MetadataChangeSetValidationIssue> errors,
                             Set<String> touched, String fieldId) {
        MetadataField existing = fields.get(fieldId);
        if (fieldId == null || existing == null || !touched.add(fieldId)) {
            error(errors, "INVALID_FIELD_DELETE", String.valueOf(fieldId), "字段删除必须唯一地指向已存在字段。");
            return;
        }
        if (protectedField(existing, context.relation())) {
            error(errors, "PROTECTED_FIELD", existing.getFieldName(), "受保护字段不能在编辑会话中删除。");
        }
        error(errors, "NON_ADDITIVE_FIELD_DELETE", existing.getFieldName(), "首批发布不支持删除字段或删除物理列。");
        impacts.add(new MetadataChangeSetFieldImpact("DELETE", existing.getFieldName(), existing.getColumnName(),
                protectedField(existing, context.relation()), "删除草稿不会被发布。"));
    }

    private void appendCapabilityFieldPlan(Metadata metadata, Set<EntityCapability> capabilities,
                                           Map<String, MetadataField> fields,
                                           List<MetadataChangeSetFieldImpact> impacts,
                                           List<MetadataChangeSetValidationIssue> errors) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        for (MetadataField field : fields.values()) {
            names.add(field.getFieldName());
            columns.add(field.getColumnName());
        }
        for (ModuleMetadataCapabilityFieldContribution contribution : MetadataCapabilityCatalog.plan(capabilities).metadataFields()) {
            if (names.contains(contribution.fieldName()) && columns.contains(contribution.columnName())) continue;
            if (names.contains(contribution.fieldName()) || columns.contains(contribution.columnName())) {
                error(errors, "CAPABILITY_FIELD_CONFLICT", contribution.fieldName(), "能力派生字段与业务字段或物理列冲突。");
                continue;
            }
            impacts.add(new MetadataChangeSetFieldImpact("ADD", contribution.fieldName(), contribution.columnName(), true,
                    contribution.defaultDescription()));
            names.add(contribution.fieldName());
            columns.add(contribution.columnName());
        }
    }

    private List<MetadataChangeSetSchemaImpact> schemaImpacts(Metadata metadata, List<MetadataChangeSetFieldImpact> fields) {
        return fields.stream().filter(field -> "ADD".equals(field.operation())).map(field ->
                new MetadataChangeSetSchemaImpact("ADD_COLUMN", metadata.getSchemaName(), metadata.getTableName(),
                        field.columnName(), field.platformManaged() ? "能力派生平台字段。" : "新增业务字段。"))
                .toList();
    }

    private void validateFinalFieldNames(Iterable<MetadataField> fields, List<MetadataChangeSetValidationIssue> errors) {
        Set<String> names = new HashSet<>();
        Set<String> columns = new HashSet<>();
        for (MetadataField field : fields) {
            if (!names.add(field.getFieldName())) error(errors, "DUPLICATE_FIELD_NAME", field.getFieldName(), "字段名在最终模型中重复。");
            if (!columns.add(field.getColumnName())) error(errors, "DUPLICATE_COLUMN_NAME", field.getColumnName(), "物理列名在最终模型中重复。");
        }
    }

    private MetadataFieldPropertyChangeSetPlan propertyPlan(Context context,
                                                            MetadataField proposedField,
                                                            MetadataFieldPropertyDraft draft,
                                                            MetadataField existingField,
                                                            List<MetadataChangeSetValidationIssue> errors) {
        if (draft == null) return null;
        MetadataFieldPropertyKind kind = draft.kind();
        if (kind == null) {
            error(errors, "INVALID_FIELD_PROPERTY", proposedField.getFieldName(), "字段属性草稿缺少类型。");
            return null;
        }
        if (kind == MetadataFieldPropertyKind.LEGACY_LOCKED) {
            error(errors, "LEGACY_FIELD_PROPERTY_LOCKED", proposedField.getFieldName(),
                    "旧模块字段属性仅可读取，不能作为新编排草稿预检或发布。");
            return null;
        }
        MetadataFieldReferenceConfig existingReference = existingField == null || referenceConfigService == null ? null
                : referenceConfigService.findForRelation(existingField.getId(), context.relation().getId());
        MetadataFieldConfig existingDictionary = existingField == null || fieldConfigService == null ? null
                : effectiveFieldConfig(existingField.getId(), context.relation().getId());
        boolean hasReference = existingReference != null;
        boolean hasDictionary = existingDictionary != null && existingDictionary.hasDictionaryBinding();
        if (hasReference && hasDictionary) {
            error(errors, "CONFLICTING_FIELD_PROPERTY", proposedField.getFieldName(), "字段同时存在引用和字典绑定，不能编排。");
            return null;
        }
        if (kind == MetadataFieldPropertyKind.BASIC) {
            if (draft.referenceConfig() != null || draft.dictionaryConfig() != null) {
                error(errors, "INVALID_FIELD_PROPERTY", proposedField.getFieldName(), "普通字段不能携带引用或字典绑定。");
                return null;
            }
            if (hasReference || hasDictionary) {
                error(errors, "NON_ADDITIVE_FIELD_PROPERTY", proposedField.getFieldName(), "当前发布不能移除既有字段属性绑定。");
                return null;
            }
            return new MetadataFieldPropertyChangeSetPlan(kind, draft.expectedBindingVersion(), null, null);
        }
        if (kind == MetadataFieldPropertyKind.MODULE_REFERENCE) {
            if (draft.referenceConfig() == null || draft.dictionaryConfig() != null) {
                error(errors, "INVALID_FIELD_PROPERTY", proposedField.getFieldName(), "模块引用字段必须且只能携带引用绑定。");
                return null;
            }
            if (hasDictionary) {
                error(errors, "CONFLICTING_FIELD_PROPERTY", proposedField.getFieldName(), "字典字段不能同时配置模块引用。");
                return null;
            }
            MetadataFieldReferenceConfig reference = draft.referenceConfig().toConfig();
            if (!validateReferenceBinding(context, proposedField, reference, errors)) return null;
            if (!bindingVersionMatches(draft.expectedBindingVersion(), existingReference)) {
                error(errors, "STALE_FIELD_PROPERTY_VERSION", proposedField.getFieldName(), "引用配置版本已变化，请重新载入后预检。");
                return null;
            }
            return new MetadataFieldPropertyChangeSetPlan(kind, draft.expectedBindingVersion(),
                    copyReference(reference), null);
        }
        if (draft.dictionaryConfig() == null || draft.referenceConfig() != null) {
            error(errors, "INVALID_FIELD_PROPERTY", proposedField.getFieldName(), "数据字典字段必须且只能携带字典绑定。");
            return null;
        }
        if (hasReference) {
            error(errors, "CONFLICTING_FIELD_PROPERTY", proposedField.getFieldName(), "引用字段不能同时配置数据字典。");
            return null;
        }
        if (!validateDictionaryBinding(context, proposedField, draft.dictionaryConfig(), errors)) return null;
        if (!bindingVersionMatches(draft.expectedBindingVersion(), hasDictionary ? existingDictionary : null)) {
            error(errors, "STALE_FIELD_PROPERTY_VERSION", proposedField.getFieldName(), "字典配置版本已变化，请重新载入后预检。");
            return null;
        }
        return new MetadataFieldPropertyChangeSetPlan(kind, draft.expectedBindingVersion(), null,
                copyDictionary(draft.dictionaryConfig()));
    }

    private boolean legacyPropertyLocked(MetadataField field, ModuleMetadataRelation relation) {
        if (moduleFieldService == null || field == null || relation == null) return false;
        ModuleMetadataField legacy = moduleFieldService.findByRelationAndField(relation.getId(), field.getId());
        return legacy != null && ((legacy.getReferenceModuleAlias() != null && !legacy.getReferenceModuleAlias().isBlank())
                || (legacy.getDictionaryCategoryAlias() != null && !legacy.getDictionaryCategoryAlias().isBlank()));
    }

    private MetadataFieldConfig effectiveFieldConfig(String fieldId, String relationId) {
        MetadataFieldConfig override = fieldConfigService.findRelationOverride(fieldId, relationId);
        return override == null ? fieldConfigService.findByMetadataFieldId(fieldId) : override;
    }

    private boolean validateReferenceBinding(Context context, MetadataField field, MetadataFieldReferenceConfig config,
                                             List<MetadataChangeSetValidationIssue> errors) {
        try {
            if (referenceConfigService == null) {
                throw new IllegalStateException("Metadata reference validation is not configured");
            }
            config.setMetadataFieldId(field.getId());
            config.setRelationId(context.relation().getId());
            referenceConfigService.validateDraft(config, field, context.relation());
            return true;
        } catch (RuntimeException exception) {
            error(errors, "INVALID_FIELD_PROPERTY", field.getFieldName(), exception.getMessage());
            return false;
        }
    }

    private boolean validateDictionaryBinding(Context context, MetadataField field, MetadataFieldConfig config,
                                              List<MetadataChangeSetValidationIssue> errors) {
        try {
            if (fieldConfigService == null) {
                throw new IllegalStateException("Metadata dictionary validation is not configured");
            }
            config.setMetadataFieldId(field.getId());
            config.setRelationId(context.relation().getId());
            fieldConfigService.validateDictionaryDraft(config, field);
            return true;
        } catch (RuntimeException exception) {
            error(errors, "INVALID_FIELD_PROPERTY", field.getFieldName(), exception.getMessage());
            return false;
        }
    }

    private boolean bindingVersionMatches(Integer expectedVersion, Object existing) {
        if (existing == null) return expectedVersion == null;
        if (expectedVersion == null) return false;
        Integer actual = existing instanceof MetadataFieldReferenceConfig reference ? reference.getVersion()
                : ((MetadataFieldConfig) existing).getVersion();
        return java.util.Objects.equals(expectedVersion, actual);
    }

    private MetadataFieldReferenceConfig copyReference(MetadataFieldReferenceConfig source) {
        MetadataFieldReferenceConfig result = new MetadataFieldReferenceConfig();
        result.setTargetModuleAlias(source.getTargetModuleAlias());
        result.setTargetMetadataId(source.getTargetMetadataId());
        result.setTargetKeyField(source.getTargetKeyField());
        result.setTargetLabelField(source.getTargetLabelField());
        result.setCardinality(source.getCardinality());
        result.setTargetUnavailablePolicy(source.getTargetUnavailablePolicy());
        result.setProjectionMappings(source.getProjectionMappings());
        return result;
    }

    private MetadataFieldConfig copyDictionary(MetadataFieldConfig source) {
        MetadataFieldConfig result = new MetadataFieldConfig();
        result.setDictionaryApplicationAlias(source.getDictionaryApplicationAlias());
        result.setDictionaryCategoryAlias(source.getDictionaryCategoryAlias());
        result.setSelectionMode(source.getSelectionMode());
        return result;
    }

    private boolean validateDraftField(MetadataField field, String subject, List<MetadataChangeSetValidationIssue> errors) {
        if (field == null) {
            error(errors, "INVALID_FIELD_DRAFT", subject, "字段草稿不能为空。");
            return false;
        }
        try {
            PlatformNameRules.requireFieldName(field.getFieldName(), "fieldName");
            PlatformNameRules.requireDatabaseName(field.getColumnName(), "columnName");
            PlatformNameRules.requireIdentifier(field.getFieldSpecAlias(), "fieldSpecAlias");
            fieldSpecService.requireFieldType(field.getFieldSpecAlias());
            return true;
        } catch (RuntimeException exception) {
            error(errors, "INVALID_FIELD_DRAFT", subject, exception.getMessage());
            return false;
        }
    }

    private boolean protectedField(MetadataField field, ModuleMetadataRelation relation) {
        return Boolean.TRUE.equals(field.getSystemManaged())
                || field.getFieldOwnership() != MetadataFieldOwnership.BUSINESS
                || (relation.getForeignKey() != null && (relation.getForeignKey().equals(field.getFieldName())
                || relation.getForeignKey().equals(field.getColumnName())));
    }

    private Context context(String moduleAlias, String relationId) {
        PlatformModule module = moduleService.select(moduleAlias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new IllegalArgumentException("Metadata change-set preview requires dynamic module: " + moduleAlias);
        }
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null || !moduleAlias.equals(relation.getModuleAlias())) {
            throw new IllegalArgumentException("metadata relation does not belong to module: " + moduleAlias + "." + relationId);
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw new IllegalArgumentException("Module relation points to missing metadata: " + relation.getMetadataId());
        return new Context(relation, metadata);
    }

    private boolean metadataHasChildUsage(String metadataId) {
        return relationService.count(Criteria.of().eq("parentMetadataId", metadataId).eq("relationRole", RelationRole.CHILD)) > 0
                || relationService.count(Criteria.of().eq("metadataId", metadataId).eq("relationRole", RelationRole.CHILD)) > 0;
    }

    private LinkedHashMap<String, MetadataField> fieldsById(List<MetadataField> fields) {
        LinkedHashMap<String, MetadataField> result = new LinkedHashMap<>();
        for (MetadataField field : fields) result.put(field.getId(), field);
        return result;
    }

    private String fingerprint(MetadataRelationChangeSetPlan plan) {
        List<String> facts = new ArrayList<>();
        facts.add(plan.metadataId() + "|" + plan.expectedMetadataVersion() + "|" + plan.replaceCapabilityDeclarations());
        plan.effectiveCapabilities().stream().sorted().forEach(capability -> facts.add("capability:" + capability));
        for (MetadataFieldChangeSetPlan mutation : plan.fieldMutations()) {
            MetadataField field = mutation.field();
            facts.add("field:" + mutation.operation() + "|" + mutation.fieldId() + "|" + mutation.expectedFieldVersion() + "|"
                    + field.getFieldName() + "|" + field.getColumnName() + "|" + field.getFieldSpecAlias() + "|"
                    + field.getTitle() + "|" + field.getRequired() + "|" + field.getUniqueField() + "|"
                    + field.getIndexed() + "|" + field.getSortableField() + "|" + field.getTitleField() + "|"
                    + field.getEnabled() + "|" + field.getSortOrder());
            MetadataFieldPropertyChangeSetPlan property = mutation.property();
            if (property != null) {
                facts.add("property:" + mutation.fieldId() + "|" + field.getFieldName() + "|" + property.kind() + "|"
                        + property.expectedBindingVersion() + "|" + propertyFacts(property));
            }
        }
        facts.sort(Comparator.naturalOrder());
        return sha256(String.join("\n", facts));
    }

    private String propertyFacts(MetadataFieldPropertyChangeSetPlan property) {
        if (property.referenceConfig() != null) {
            MetadataFieldReferenceConfig config = property.referenceConfig();
            return String.join("|", "reference", String.valueOf(config.getTargetModuleAlias()),
                    String.valueOf(config.getTargetMetadataId()), String.valueOf(config.getTargetKeyField()),
                    String.valueOf(config.getTargetLabelField()), String.valueOf(config.getCardinality()),
                    String.valueOf(config.getTargetUnavailablePolicy()), String.valueOf(config.getProjectionMappings()));
        }
        if (property.dictionaryConfig() != null) {
            MetadataFieldConfig config = property.dictionaryConfig();
            return String.join("|", "dictionary", String.valueOf(config.getDictionaryApplicationAlias()),
                    String.valueOf(config.getDictionaryCategoryAlias()), String.valueOf(config.getSelectionMode()));
        }
        return "basic";
    }

    private MetadataField newBusinessField(MetadataField source) {
        MetadataField result = new MetadataField();
        result.setFieldName(source.getFieldName());
        result.setColumnName(source.getColumnName());
        result.setFieldSpecAlias(source.getFieldSpecAlias());
        result.setTitle(source.getTitle());
        result.setRequired(source.getRequired());
        result.setUniqueField(source.getUniqueField());
        result.setIndexed(source.getIndexed());
        result.setSortableField(source.getSortableField());
        result.setTitleField(source.getTitleField());
        result.setEnabled(source.getEnabled());
        result.setSortOrder(source.getSortOrder());
        result.setFieldOwnership(MetadataFieldOwnership.BUSINESS);
        result.setFieldForm(MetadataFieldForm.PHYSICAL);
        result.setSystemManaged(Boolean.FALSE);
        return result;
    }

    private MetadataField overlayBusinessAttributes(MetadataField existing, MetadataField source) {
        MetadataField result = newBusinessField(existing);
        result.setId(existing.getId());
        result.setMetadataId(existing.getMetadataId());
        result.setVersion(existing.getVersion());
        result.setFieldSpecAlias(source.getFieldSpecAlias());
        result.setTitle(source.getTitle());
        result.setRequired(source.getRequired());
        result.setUniqueField(source.getUniqueField());
        result.setIndexed(source.getIndexed());
        result.setSortableField(source.getSortableField());
        result.setTitleField(source.getTitleField());
        result.setEnabled(source.getEnabled());
        result.setSortOrder(source.getSortOrder());
        return result;
    }

    private static void error(List<MetadataChangeSetValidationIssue> errors, String code, String subject, String message) {
        errors.add(new MetadataChangeSetValidationIssue(MetadataChangeSetValidationIssue.Severity.ERROR, code, subject, message));
    }

    private static boolean same(Object first, Object second) {
        return java.util.Objects.equals(first, second);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte valueByte : digest) hex.append(String.format("%02x", valueByte));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record Context(ModuleMetadataRelation relation, Metadata metadata) {
    }
}
