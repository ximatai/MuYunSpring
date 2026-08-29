package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
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

    public MetadataRelationChangeSetPreviewService(PlatformModuleService moduleService,
                                                   ModuleMetadataRelationService relationService,
                                                   MetadataService metadataService,
                                                   MetadataFieldService fieldService) {
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
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
        String syntheticId = "new:" + normalized.getFieldName();
        fields.put(syntheticId, normalized);
        mutations.add(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.ADD, null, null, normalized));
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
        if (!java.util.Objects.equals(draft.expectedFieldVersion(), existing.getVersion())) {
            error(errors, "STALE_FIELD_VERSION", existing.getFieldName(), "字段版本已变化，请重新载入后预检。");
            return;
        }
        MetadataField field = draft.field();
        if (!validateDraftField(field, existing.getFieldName(), errors)) return;
        if (!same(existing.getFieldName(), field.getFieldName()) || !same(existing.getColumnName(), field.getColumnName())
                || !same(existing.getFieldSpecAlias(), field.getFieldSpecAlias())
                || !same(existing.getFieldOwnership(), field.getFieldOwnership())
                || !same(existing.getFieldForm(), field.getFieldForm())
                || !same(existing.getOwnerFieldId(), field.getOwnerFieldId())
                || !same(existing.getFieldRole(), field.getFieldRole())
                || !same(existing.getSystemManaged(), field.getSystemManaged())) {
            error(errors, "NON_ADDITIVE_FIELD_MUTATION", existing.getFieldName(),
                    "首批发布仅允许修改业务展示和约束属性，不能修改字段结构、归属或平台管理属性。");
            return;
        }
        MetadataField normalized = overlayBusinessAttributes(existing, field);
        fields.put(fieldId, normalized);
        mutations.add(new MetadataFieldChangeSetPlan(MetadataFieldChangeSetDraft.Operation.UPDATE, fieldId,
                draft.expectedFieldVersion(), normalized));
        impacts.add(new MetadataChangeSetFieldImpact("UPDATE", field.getFieldName(), field.getColumnName(), false, "更新字段元数据。"));
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

    private boolean validateDraftField(MetadataField field, String subject, List<MetadataChangeSetValidationIssue> errors) {
        if (field == null) {
            error(errors, "INVALID_FIELD_DRAFT", subject, "字段草稿不能为空。");
            return false;
        }
        try {
            PlatformNameRules.requireFieldName(field.getFieldName(), "fieldName");
            PlatformNameRules.requireDatabaseName(field.getColumnName(), "columnName");
            PlatformNameRules.requireIdentifier(field.getFieldSpecAlias(), "fieldSpecAlias");
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
        }
        facts.sort(Comparator.naturalOrder());
        return sha256(String.join("\n", facts));
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
