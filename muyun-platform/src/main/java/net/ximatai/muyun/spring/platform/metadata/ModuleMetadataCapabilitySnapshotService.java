package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.PlatformDataScopeSchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Predicate;

/**
 * Exposes the existing field-driven capability model to governance UI without introducing a second capability store.
 */
@Service
public class ModuleMetadataCapabilitySnapshotService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;

    public ModuleMetadataCapabilitySnapshotService(ModuleMetadataRelationService relationService,
                                                   MetadataService metadataService,
                                                   MetadataFieldService fieldService) {
        this.relationService = relationService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
    }

    public ModuleMetadataCapabilitySnapshot snapshot(String moduleAlias, String relationId) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null || !validModuleAlias.equals(relation.getModuleAlias())) {
            throw new PlatformException("metadata relation does not belong to module: " + validModuleAlias + "." + relationId);
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw new PlatformException("metadata relation requires existing metadata: " + relation.getMetadataId());
        List<MetadataField> fields = fieldService.list(Criteria.of().eq("metadataId", metadata.getId()), ALL);
        boolean hasChildUsage = hasChildUsage(metadata.getId());
        MetadataCapabilityResolution resolution = MetadataCapabilityCatalog.resolve(metadata, relation.getRelationRole(), fields);
        return new ModuleMetadataCapabilitySnapshot(validModuleAlias, relation.getId(), relation.getRelationRole(),
                List.of(
                catalogCapability(EntityCapability.TREE, relation, resolution, hasChildUsage),
                catalogCapability(EntityCapability.SORT, relation, resolution, hasChildUsage),
                fieldCapability(EntityCapability.REFERENCE, relation, fields, false,
                        field -> Boolean.TRUE.equals(field.getTitleField()),
                        List.of(PlatformAbilityFields.TITLE_FIELD), "NONE", "标题字段用于引用显示；不自动写入初始值。"),
                catalogCapability(EntityCapability.ENABLE, relation, resolution, hasChildUsage),
                dataScopeCapability(relation, metadata),
                fieldCapability(EntityCapability.APPROVAL, relation, fields, false, this::isApprovalField,
                        List.of(PlatformAbilityFields.APPROVAL_INSTANCE_FIELD, PlatformAbilityFields.APPROVAL_STATUS_FIELD),
                        "CONTEXT", "审批字段由审批运行态和流程上下文维护。")
        ));
    }

    private ModuleMetadataCapabilityFact catalogCapability(EntityCapability capability, ModuleMetadataRelation relation,
                                                            MetadataCapabilityResolution resolution,
                                                            boolean blockedByChildUsage) {
        boolean child = relation.getRelationRole() == RelationRole.CHILD;
        MetadataCapabilityPlan plan = MetadataCapabilityCatalog.plan(java.util.Set.of(capability));
        List<String> contributions = plan.metadataFields().stream()
                .map(ModuleMetadataCapabilityFieldContribution::fieldName).toList();
        String defaultKind = capability == EntityCapability.ENABLE ? "STATIC" : "RUNTIME";
        String description = switch (capability) {
            case TREE -> "未填写 parentId 时，运行态写入根节点。";
            case SORT -> "未填写 sortOrder 时，运行态按分区分配下一个排序值。";
            case ENABLE -> "未填写 enabled 时，默认写入 true。";
            default -> "";
        };
        boolean configurable = !child && !blockedByChildUsage;
        String reason = child ? "子实体不能启用该模块保留能力。"
                : blockedByChildUsage ? "该元数据已参与子实体关联；首期不允许变更会影响子实体结构的能力。"
                : resolution.legacyFieldInference() ? "旧元数据暂由字段事实推导；首次治理后将写入能力声明。"
                : "能力由元数据声明驱动。";
        return new ModuleMetadataCapabilityFact(capability, resolution.capabilities().contains(capability), configurable,
                reason, contributions, defaultKind, description);
    }

    private ModuleMetadataCapabilityFact dataScopeCapability(ModuleMetadataRelation relation, Metadata metadata) {
        boolean child = relation.getRelationRole() == RelationRole.CHILD;
        return new ModuleMetadataCapabilityFact(EntityCapability.DATA_SCOPE, Boolean.TRUE.equals(metadata.getDataScopeEnabled()), !child,
                child ? "子实体不能启用模块数据权限范围。" : "数据权限范围由现有元数据配置声明。",
                PlatformDataScopeSchema.fieldNames(), "CONTEXT",
                "权限字段由当前用户与组织上下文填充。 ");
    }

    private ModuleMetadataCapabilityFact fieldCapability(EntityCapability capability, ModuleMetadataRelation relation,
                                                          List<MetadataField> fields,
                                                          boolean blockedByChildUsage,
                                                          Predicate<MetadataField> enabled,
                                                          List<String> contributions, String defaultKind, String defaultDescription) {
        boolean child = relation.getRelationRole() == RelationRole.CHILD;
        boolean allowedForChild = capability == EntityCapability.REFERENCE;
        boolean configurable = (!child || allowedForChild) && !blockedByChildUsage;
        String reason = child && !allowedForChild
                ? "子实体不能启用该模块保留能力。"
                : blockedByChildUsage
                    ? "该元数据已参与子实体关联；首期不允许变更会影响子实体结构的能力。"
                    : "能力由已保存的标准字段事实推导。";
        return new ModuleMetadataCapabilityFact(capability, fields.stream().anyMatch(enabled), configurable, reason,
                contributions, defaultKind, defaultDescription);
    }

    private boolean hasChildUsage(String metadataId) {
        return relationService.count(Criteria.of().eq("parentMetadataId", metadataId)
                .eq("relationRole", RelationRole.CHILD)) > 0
                || relationService.count(Criteria.of().eq("metadataId", metadataId)
                .eq("relationRole", RelationRole.CHILD)) > 0;
    }

    private boolean isApprovalField(MetadataField field) {
        return PlatformAbilityFields.APPROVAL_INSTANCE_FIELD.equals(field.getFieldName())
                || PlatformAbilityFields.APPROVAL_STATUS_FIELD.equals(field.getFieldName())
                || PlatformAbilityFields.APPROVAL_SUBMITTED_BY_FIELD.equals(field.getFieldName())
                || PlatformAbilityFields.APPROVAL_SUBMITTED_AT_FIELD.equals(field.getFieldName())
                || PlatformAbilityFields.APPROVAL_COMPLETED_AT_FIELD.equals(field.getFieldName());
    }
}
