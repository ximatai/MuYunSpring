package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Publishes one complete metadata tree proposal in a single transaction and one runtime activation. */
@Service
public class MetadataModelChangeSetApplyService {
    private final MetadataModelChangeSetPreviewService previewService;
    private final MetadataRelationChangeSetApplyService relationApplyService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final PlatformMetadataSchemaEnsureService schemaEnsureService;
    private final PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;
    private final ModuleMetadataCapabilitySnapshotService snapshotService;
    private final EmptyMetadataFieldSpecColumnRebuildService emptyFieldSpecColumnRebuildService;

    public MetadataModelChangeSetApplyService(MetadataModelChangeSetPreviewService previewService,
                                              MetadataRelationChangeSetApplyService relationApplyService,
                                              ModuleMetadataRelationService relationService,
                                              MetadataService metadataService,
                                              MetadataFieldService fieldService,
                                              PlatformMetadataSchemaEnsureService schemaEnsureService,
                                              PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator,
                                              ModuleMetadataCapabilitySnapshotService snapshotService,
                                              EmptyMetadataFieldSpecColumnRebuildService emptyFieldSpecColumnRebuildService) {
        this.previewService = Objects.requireNonNull(previewService, "previewService must not be null");
        this.relationApplyService = Objects.requireNonNull(relationApplyService, "relationApplyService must not be null");
        this.relationService = Objects.requireNonNull(relationService, "relationService must not be null");
        this.metadataService = Objects.requireNonNull(metadataService, "metadataService must not be null");
        this.fieldService = Objects.requireNonNull(fieldService, "fieldService must not be null");
        this.schemaEnsureService = Objects.requireNonNull(schemaEnsureService, "schemaEnsureService must not be null");
        this.refreshCoordinator = Objects.requireNonNull(refreshCoordinator, "refreshCoordinator must not be null");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService must not be null");
        this.emptyFieldSpecColumnRebuildService = Objects.requireNonNull(emptyFieldSpecColumnRebuildService,
                "emptyFieldSpecColumnRebuildService must not be null");
    }

    @Transactional
    public MetadataModelChangeSetPublishResult apply(String moduleAlias, MetadataModelChangeSetApplyCommand command) {
        if (command == null || command.proposal() == null) {
            throw new IllegalArgumentException("metadata model change-set apply command must include a proposal");
        }
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        MetadataModelChangeSetPreview preview = previewService.preview(validModuleAlias, command.proposal());
        if (!preview.valid()) {
            throw new PlatformException("Metadata model change-set validation failed: "
                    + preview.errors().stream().map(MetadataChangeSetValidationIssue::code).toList());
        }
        if (!Objects.equals(command.proposalFingerprint(), preview.proposalFingerprint())) {
            throw new PlatformException("Metadata model change-set preview fingerprint is stale; preview again before publish");
        }

        Map<String, ModuleMetadataRelation> relations = relations(preview.plan());
        Map<String, Metadata> metadata = metadata(preview.plan(), relations);
        Map<String, String> previousFieldSpecs = previousFieldSpecs(preview.plan());
        MetadataFieldPropertyMutationContext.run(() -> MetadataCapabilityGovernanceMutationContext.run(() -> {
            applyRelationPlans(preview.plan(), relations, metadata);
            applyRelationOrders(preview.plan().relationOrderPlans(), relations);
            applyFieldOrders(preview.plan().fieldOrderPlans(), relations);
            ensureSchemas(preview.plan(), relations, metadata, previousFieldSpecs);
            return null;
        }));
        TransactionScopeSupport.afterCommitOrNow(() -> refreshCoordinator.activateModulesNow(List.of(validModuleAlias)));
        return new MetadataModelChangeSetPublishResult(preview, snapshots(validModuleAlias, relations), List.of(validModuleAlias));
    }

    private Map<String, ModuleMetadataRelation> relations(MetadataModelChangeSetPlan plan) {
        Map<String, ModuleMetadataRelation> result = new LinkedHashMap<>();
        for (MetadataModelRelationPlan relationPlan : plan.relationPlans()) requireRelation(result, relationPlan.relationId());
        for (MetadataModelRelationOrderPlan orderPlan : plan.relationOrderPlans()) {
            for (MetadataModelRelationOrderPlan.Entry entry : orderPlan.entries()) requireRelation(result, entry.relationId());
        }
        for (MetadataModelFieldOrderPlan orderPlan : plan.fieldOrderPlans()) requireRelation(result, orderPlan.relationId());
        return result;
    }

    private void requireRelation(Map<String, ModuleMetadataRelation> relations, String relationId) {
        if (relations.containsKey(relationId)) return;
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null) throw new PlatformException("Metadata model relation is stale: " + relationId);
        relations.put(relationId, relation);
    }

    private Map<String, Metadata> metadata(MetadataModelChangeSetPlan plan,
                                            Map<String, ModuleMetadataRelation> relations) {
        Map<String, Metadata> result = new LinkedHashMap<>();
        for (MetadataModelRelationPlan relationPlan : plan.relationPlans()) {
            ModuleMetadataRelation relation = relations.get(relationPlan.relationId());
            assertRelationScope(relationPlan, relation);
            Metadata value = metadataService.select(relation.getMetadataId());
            if (value == null || !Objects.equals(value.getVersion(), relationPlan.changeSet().expectedMetadataVersion())) {
                throw new PlatformException("Metadata model change-set is stale; reload and preview again");
            }
            result.put(relationPlan.relationId(), value);
        }
        return result;
    }

    private void assertRelationScope(MetadataModelRelationPlan plan, ModuleMetadataRelation relation) {
        if (!Objects.equals(plan.moduleAlias(), relation.getModuleAlias())
                || !Objects.equals(plan.expectedRelationVersion(), relation.getVersion())
                || !Objects.equals(plan.metadataId(), relation.getMetadataId())
                || plan.relationRole() != relation.getRelationRole()
                || !Objects.equals(plan.parentMetadataId(), relation.getParentMetadataId())
                || !Objects.equals(plan.foreignKey(), relation.getForeignKey())) {
            throw new PlatformException("Metadata model relation scope is stale; reload and preview again");
        }
    }

    private void applyRelationPlans(MetadataModelChangeSetPlan plan,
                                    Map<String, ModuleMetadataRelation> relations,
                                    Map<String, Metadata> metadata) {
        for (MetadataModelRelationPlan relationPlan : plan.relationPlans()) {
            relationApplyService.applyValidated(metadata.get(relationPlan.relationId()), relations.get(relationPlan.relationId()),
                    relationPlan.changeSet(), relationPlan.effectiveCapabilities());
        }
    }

    private void applyRelationOrders(List<MetadataModelRelationOrderPlan> plans,
                                     Map<String, ModuleMetadataRelation> relations) {
        for (MetadataModelRelationOrderPlan plan : plans) {
            for (MetadataModelRelationOrderPlan.Entry entry : plan.entries()) {
                ModuleMetadataRelation relation = relations.get(entry.relationId());
                if (!Objects.equals(relation.getVersion(), entry.expectedVersion())
                        || !Objects.equals(relation.getParentMetadataId(), plan.parentMetadataId())) {
                    throw new PlatformException("Metadata model relation order is stale; reload and preview again");
                }
                relation.setSortOrder(entry.sortOrder());
                relationService.update(relation);
            }
        }
    }

    private void applyFieldOrders(List<MetadataModelFieldOrderPlan> plans,
                                  Map<String, ModuleMetadataRelation> relations) {
        for (MetadataModelFieldOrderPlan plan : plans) {
            ModuleMetadataRelation relation = relations.get(plan.relationId());
            assertRelationScope(plan, relation);
            for (MetadataModelFieldOrderPlan.Entry entry : plan.entries()) {
                MetadataField field = fieldService.select(entry.fieldId());
                if (!movable(field, relation) || !Objects.equals(field.getVersion(), entry.expectedVersion())) {
                    throw new PlatformException("Metadata model field order is stale; reload and preview again");
                }
                field.setSortOrder(entry.sortOrder());
                fieldService.update(field);
            }
        }
    }

    private void assertRelationScope(MetadataModelFieldOrderPlan plan, ModuleMetadataRelation relation) {
        if (!Objects.equals(plan.moduleAlias(), relation.getModuleAlias())
                || !Objects.equals(plan.expectedRelationVersion(), relation.getVersion())
                || !Objects.equals(plan.metadataId(), relation.getMetadataId())
                || plan.relationRole() != relation.getRelationRole()
                || !Objects.equals(plan.parentMetadataId(), relation.getParentMetadataId())
                || !Objects.equals(plan.foreignKey(), relation.getForeignKey())) {
            throw new PlatformException("Metadata model field order relation scope is stale; reload and preview again");
        }
    }

    private boolean movable(MetadataField field, ModuleMetadataRelation relation) {
        return field != null && Objects.equals(field.getMetadataId(), relation.getMetadataId())
                && !Boolean.TRUE.equals(field.getSystemManaged())
                && field.getFieldOwnership() == MetadataFieldOwnership.BUSINESS
                && !Objects.equals(relation.getForeignKey(), field.getFieldName())
                && !Objects.equals(relation.getForeignKey(), field.getColumnName());
    }

    private Map<String, String> previousFieldSpecs(MetadataModelChangeSetPlan plan) {
        Map<String, String> result = new LinkedHashMap<>();
        for (MetadataModelRelationPlan relationPlan : plan.relationPlans()) {
            for (MetadataFieldChangeSetPlan mutation : relationPlan.changeSet().fieldMutations()) {
                if (mutation.operation() != MetadataFieldChangeSetDraft.Operation.UPDATE || mutation.fieldId() == null) continue;
                MetadataField existing = fieldService.select(mutation.fieldId());
                if (existing != null) result.put(mutation.fieldId(), existing.getFieldSpecAlias());
            }
        }
        return result;
    }

    private void ensureSchemas(MetadataModelChangeSetPlan plan,
                               Map<String, ModuleMetadataRelation> relations,
                               Map<String, Metadata> metadata,
                               Map<String, String> previousFieldSpecs) {
        for (MetadataModelRelationPlan relationPlan : plan.relationPlans()) {
            rebuildEmptyFieldSpecColumns(relationPlan, relations.get(relationPlan.relationId()),
                    metadata.get(relationPlan.relationId()), previousFieldSpecs);
            schemaEnsureService.ensureNow(metadata.get(relationPlan.relationId()));
        }
    }

    private void rebuildEmptyFieldSpecColumns(MetadataModelRelationPlan relationPlan,
                                              ModuleMetadataRelation relation,
                                              Metadata metadata,
                                              Map<String, String> previousFieldSpecs) {
        for (MetadataFieldChangeSetPlan mutation : relationPlan.changeSet().fieldMutations()) {
            if (mutation.operation() != MetadataFieldChangeSetDraft.Operation.UPDATE || mutation.fieldId() == null) continue;
            emptyFieldSpecColumnRebuildService.rebuildIfEmpty(relation.getModuleAlias(), metadata,
                    previousFieldSpecs.get(mutation.fieldId()), mutation.field());
        }
    }

    private List<ModuleMetadataCapabilitySnapshot> snapshots(String moduleAlias,
                                                              Map<String, ModuleMetadataRelation> relations) {
        List<ModuleMetadataCapabilitySnapshot> result = new ArrayList<>();
        for (String relationId : relations.keySet()) result.add(snapshotService.snapshot(moduleAlias, relationId));
        return List.copyOf(result);
    }
}
