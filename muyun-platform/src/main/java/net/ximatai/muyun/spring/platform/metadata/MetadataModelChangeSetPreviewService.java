package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles the one user-visible metadata tree draft into relation plans and constrained order plans.
 * It deliberately accepts edits to existing nodes only: relation topology is governed elsewhere.
 */
@Service
public class MetadataModelChangeSetPreviewService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private static final int SORT_STEP = 100;

    private final PlatformModuleService moduleService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataFieldService fieldService;
    private final MetadataRelationChangeSetPreviewService relationPreviewService;

    public MetadataModelChangeSetPreviewService(PlatformModuleService moduleService,
                                                ModuleMetadataRelationService relationService,
                                                MetadataFieldService fieldService,
                                                MetadataRelationChangeSetPreviewService relationPreviewService) {
        this.moduleService = moduleService;
        this.relationService = relationService;
        this.fieldService = fieldService;
        this.relationPreviewService = relationPreviewService;
    }

    public MetadataModelChangeSetPreview preview(String moduleAlias, MetadataModelChangeSetPreviewCommand command) {
        if (command == null) throw new IllegalArgumentException("metadata model change-set preview command must not be null");
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        requireDynamicModule(validModuleAlias);
        List<ModuleMetadataRelation> relations = relationService.list(Criteria.of().eq("moduleAlias", validModuleAlias), ALL);
        Map<String, ModuleMetadataRelation> relationsById = new LinkedHashMap<>();
        for (ModuleMetadataRelation relation : relations) relationsById.put(relation.getId(), relation);

        List<MetadataChangeSetValidationIssue> warnings = new ArrayList<>();
        List<MetadataChangeSetValidationIssue> errors = new ArrayList<>();
        List<MetadataRelationChangeSetPreview> relationPreviews = previewRelations(validModuleAlias, command.relationDrafts(),
                relationsById, warnings, errors);
        List<MetadataModelRelationOrderPlan> relationOrders = relationOrders(command.relationOrders(), relationsById, errors);
        List<MetadataModelFieldOrderPlan> fieldOrders = fieldOrders(command.fieldOrders(), relationsById, relationPreviews, errors);
        List<MetadataModelRelationPlan> relationPlans = relationPreviews.stream()
                .map(preview -> relationPlan(preview, relationsById.get(preview.relationId())))
                .toList();
        MetadataModelChangeSetPlan plan = new MetadataModelChangeSetPlan(relationPlans, relationOrders, fieldOrders);
        List<MetadataChangeSetFieldImpact> fieldImpacts = relationPreviews.stream()
                .flatMap(preview -> preview.fieldImpacts().stream()).toList();
        List<MetadataChangeSetSchemaImpact> schemaImpacts = relationPreviews.stream()
                .flatMap(preview -> preview.schemaImpacts().stream()).toList();
        return new MetadataModelChangeSetPreview(validModuleAlias, List.copyOf(relationPreviews), fieldImpacts, schemaImpacts,
                orderImpacts(relationOrders, fieldOrders), List.copyOf(warnings), List.copyOf(errors),
                fingerprint(plan, relationPreviews), plan);
    }

    private MetadataModelRelationPlan relationPlan(MetadataRelationChangeSetPreview preview,
                                                    ModuleMetadataRelation relation) {
        return new MetadataModelRelationPlan(preview.relationId(), relation.getModuleAlias(), relation.getVersion(),
                relation.getMetadataId(), relation.getRelationRole(), relation.getParentMetadataId(), relation.getForeignKey(),
                preview.plan(), preview.effectiveCapabilities());
    }

    private List<MetadataModelChangeSetOrderImpact> orderImpacts(List<MetadataModelRelationOrderPlan> relationOrders,
                                                                  List<MetadataModelFieldOrderPlan> fieldOrders) {
        List<MetadataModelChangeSetOrderImpact> impacts = new ArrayList<>();
        for (MetadataModelRelationOrderPlan order : relationOrders) {
            impacts.add(new MetadataModelChangeSetOrderImpact("REORDER_RELATIONS", null, order.parentMetadataId(),
                    order.entries().stream().map(MetadataModelRelationOrderPlan.Entry::relationId).toList(), "调整子元数据顺序。"));
        }
        for (MetadataModelFieldOrderPlan order : fieldOrders) {
            impacts.add(new MetadataModelChangeSetOrderImpact("REORDER_FIELDS", order.relationId(), null,
                    order.entries().stream().map(MetadataModelFieldOrderPlan.Entry::fieldId).toList(), "调整实体字段顺序。"));
        }
        return List.copyOf(impacts);
    }

    private void requireDynamicModule(String moduleAlias) {
        PlatformModule module = moduleService.select(moduleAlias);
        if (module == null || module.getModuleKind() != ModuleKind.DYNAMIC) {
            throw new IllegalArgumentException("Metadata model change-set requires dynamic module: " + moduleAlias);
        }
    }

    private List<MetadataRelationChangeSetPreview> previewRelations(String moduleAlias,
                                                                      List<MetadataModelRelationChangeSetDraft> drafts,
                                                                      Map<String, ModuleMetadataRelation> relations,
                                                                      List<MetadataChangeSetValidationIssue> warnings,
                                                                      List<MetadataChangeSetValidationIssue> errors) {
        if (drafts == null || drafts.isEmpty()) return List.of();
        List<MetadataRelationChangeSetPreview> previews = new ArrayList<>();
        Set<String> seenRelations = new HashSet<>();
        Set<String> seenMetadata = new HashSet<>();
        for (MetadataModelRelationChangeSetDraft draft : drafts) {
            if (draft == null || draft.relationId() == null || draft.relationId().isBlank() || !seenRelations.add(draft.relationId())) {
                error(errors, "INVALID_RELATION_DRAFT", "relationDrafts", "每个既有元数据节点只能出现一次。");
                continue;
            }
            ModuleMetadataRelation relation = relations.get(draft.relationId());
            if (relation == null) {
                error(errors, "INVALID_RELATION_DRAFT", draft.relationId(), "元数据节点不属于当前模块。");
                continue;
            }
            if (!seenMetadata.add(relation.getMetadataId())) {
                error(errors, "DUPLICATE_METADATA_DRAFT", draft.relationId(), "同一元数据不能在一个模型草稿中由多个节点同时修改。");
                continue;
            }
            MetadataRelationChangeSetPreview preview = relationPreviewService.preview(moduleAlias, relation.getId(),
                    draft.asRelationProposal());
            previews.add(preview);
            warnings.addAll(preview.warnings());
            errors.addAll(preview.errors());
        }
        return List.copyOf(previews);
    }

    private List<MetadataModelRelationOrderPlan> relationOrders(List<MetadataModelRelationOrder> orders,
                                                                  Map<String, ModuleMetadataRelation> relations,
                                                                  List<MetadataChangeSetValidationIssue> errors) {
        if (orders == null || orders.isEmpty()) return List.of();
        Map<String, List<ModuleMetadataRelation>> siblings = new HashMap<>();
        for (ModuleMetadataRelation relation : relations.values()) {
            siblings.computeIfAbsent(parentKey(relation.getParentMetadataId()), ignored -> new ArrayList<>()).add(relation);
        }
        Set<String> seenParents = new HashSet<>();
        List<MetadataModelRelationOrderPlan> result = new ArrayList<>();
        for (MetadataModelRelationOrder order : orders) {
            String parent = order == null ? null : order.parentMetadataId();
            String key = parentKey(parent);
            if (order == null || !seenParents.add(key)) {
                error(errors, "INVALID_RELATION_ORDER", "relationOrders", "每组同级元数据只能提交一次排序。");
                continue;
            }
            List<ModuleMetadataRelation> expected = siblings.getOrDefault(key, List.of());
            if (!sameIds(order.relationIds(), expected.stream().map(ModuleMetadataRelation::getId).toList())) {
                error(errors, "INVALID_RELATION_ORDER", String.valueOf(parent), "元数据排序必须覆盖同一父节点下的全部节点，且不能跨父节点移动。");
                continue;
            }
            List<MetadataModelRelationOrderPlan.Entry> entries = new ArrayList<>();
            int sortOrder = SORT_STEP;
            for (String relationId : order.relationIds()) {
                ModuleMetadataRelation relation = relations.get(relationId);
                entries.add(new MetadataModelRelationOrderPlan.Entry(relationId, relation.getVersion(), sortOrder));
                sortOrder += SORT_STEP;
            }
            result.add(new MetadataModelRelationOrderPlan(parent, List.copyOf(entries)));
        }
        return List.copyOf(result);
    }

    private List<MetadataModelFieldOrderPlan> fieldOrders(List<MetadataModelFieldOrder> orders,
                                                           Map<String, ModuleMetadataRelation> relations,
                                                           List<MetadataRelationChangeSetPreview> relationPreviews,
                                                           List<MetadataChangeSetValidationIssue> errors) {
        if (orders == null || orders.isEmpty()) return List.of();
        Set<String> seenRelations = new HashSet<>();
        Set<String> mutationFieldIds = new HashSet<>();
        for (MetadataRelationChangeSetPreview preview : relationPreviews) {
            for (MetadataFieldChangeSetPlan mutation : preview.plan().fieldMutations()) {
                if (mutation.fieldId() != null) mutationFieldIds.add(mutation.fieldId());
            }
        }
        List<MetadataModelFieldOrderPlan> result = new ArrayList<>();
        for (MetadataModelFieldOrder order : orders) {
            if (order == null || order.relationId() == null || order.relationId().isBlank() || !seenRelations.add(order.relationId())) {
                error(errors, "INVALID_FIELD_ORDER", "fieldOrders", "每个元数据节点只能提交一次字段排序。");
                continue;
            }
            ModuleMetadataRelation relation = relations.get(order.relationId());
            if (relation == null) {
                error(errors, "INVALID_FIELD_ORDER", order.relationId(), "字段排序节点不属于当前模块。");
                continue;
            }
            List<MetadataField> fields = fieldService.list(Criteria.of().eq("metadataId", relation.getMetadataId()), ALL);
            List<MetadataField> movable = fields.stream().filter(field -> movable(field, relation)).toList();
            if (!sameIds(order.fieldIds(), movable.stream().map(MetadataField::getId).toList())) {
                error(errors, "INVALID_FIELD_ORDER", order.relationId(), "字段排序必须覆盖当前实体全部可移动业务字段，不能移动平台字段或子表外键。");
                continue;
            }
            if (order.fieldIds().stream().anyMatch(mutationFieldIds::contains)) {
                error(errors, "FIELD_ORDER_CONFLICT", order.relationId(), "同一字段不能同时通过字段草稿和拖拽排序修改。");
                continue;
            }
            List<MetadataModelFieldOrderPlan.Entry> entries = new ArrayList<>();
            Map<String, MetadataField> fieldsById = new LinkedHashMap<>();
            for (MetadataField field : movable) fieldsById.put(field.getId(), field);
            int sortOrder = SORT_STEP;
            for (String fieldId : order.fieldIds()) {
                MetadataField field = fieldsById.get(fieldId);
                entries.add(new MetadataModelFieldOrderPlan.Entry(fieldId, field.getVersion(), sortOrder));
                sortOrder += SORT_STEP;
            }
            result.add(new MetadataModelFieldOrderPlan(relation.getId(), relation.getModuleAlias(), relation.getVersion(),
                    relation.getMetadataId(), relation.getRelationRole(), relation.getParentMetadataId(), relation.getForeignKey(),
                    List.copyOf(entries)));
        }
        return List.copyOf(result);
    }

    private boolean movable(MetadataField field, ModuleMetadataRelation relation) {
        return !Boolean.TRUE.equals(field.getSystemManaged())
                && field.getFieldOwnership() == MetadataFieldOwnership.BUSINESS
                && !Objects.equals(relation.getForeignKey(), field.getFieldName())
                && !Objects.equals(relation.getForeignKey(), field.getColumnName());
    }

    private boolean sameIds(List<String> proposed, List<String> expected) {
        return proposed != null && proposed.size() == expected.size()
                && new LinkedHashSet<>(proposed).size() == proposed.size()
                && new LinkedHashSet<>(proposed).equals(new LinkedHashSet<>(expected));
    }

    private String fingerprint(MetadataModelChangeSetPlan plan, List<MetadataRelationChangeSetPreview> previews) {
        List<String> facts = new ArrayList<>();
        for (MetadataRelationChangeSetPreview preview : previews) facts.add("relation:" + preview.relationId() + "|" + preview.proposalFingerprint());
        for (MetadataModelRelationPlan relationPlan : plan.relationPlans()) {
            facts.add("relation-scope:" + relationPlan.relationId() + "|" + relationPlan.moduleAlias() + "|"
                    + relationPlan.expectedRelationVersion() + "|" + relationPlan.metadataId() + "|"
                    + relationPlan.relationRole() + "|" + relationPlan.parentMetadataId() + "|" + relationPlan.foreignKey());
        }
        for (MetadataModelRelationOrderPlan order : plan.relationOrderPlans()) {
            for (MetadataModelRelationOrderPlan.Entry entry : order.entries()) {
                facts.add("relation-order:" + order.parentMetadataId() + "|" + entry.relationId() + "|" + entry.expectedVersion() + "|" + entry.sortOrder());
            }
        }
        for (MetadataModelFieldOrderPlan order : plan.fieldOrderPlans()) {
            facts.add("field-order-scope:" + order.relationId() + "|" + order.moduleAlias() + "|"
                    + order.expectedRelationVersion() + "|" + order.metadataId() + "|" + order.relationRole() + "|"
                    + order.parentMetadataId() + "|" + order.foreignKey());
            for (MetadataModelFieldOrderPlan.Entry entry : order.entries()) {
                facts.add("field-order:" + order.relationId() + "|" + entry.fieldId() + "|" + entry.expectedVersion() + "|" + entry.sortOrder());
            }
        }
        facts.sort(Comparator.naturalOrder());
        return sha256(String.join("\n", facts));
    }

    private static String parentKey(String parentMetadataId) {
        return parentMetadataId == null ? "<root>" : parentMetadataId;
    }

    private static void error(List<MetadataChangeSetValidationIssue> errors, String code, String subject, String message) {
        errors.add(new MetadataChangeSetValidationIssue(MetadataChangeSetValidationIssue.Severity.ERROR, code, subject, message));
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
}
