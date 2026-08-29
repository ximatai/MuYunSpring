package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomic publish for a successfully preflighted metadata relation change-set. */
@Service
public class MetadataRelationChangeSetApplyService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final MetadataRelationChangeSetPreviewService previewService;
    private final ModuleMetadataRelationService relationService;
    private final MetadataService metadataService;
    private final MetadataFieldService fieldService;
    private final PlatformMetadataSchemaEnsureService schemaEnsureService;
    private final PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator;
    private final ModuleMetadataCapabilitySnapshotService snapshotService;

    public MetadataRelationChangeSetApplyService(MetadataRelationChangeSetPreviewService previewService,
                                                 ModuleMetadataRelationService relationService,
                                                 MetadataService metadataService,
                                                 MetadataFieldService fieldService,
                                                 PlatformMetadataSchemaEnsureService schemaEnsureService,
                                                 PlatformDynamicRuntimeRefreshCoordinator refreshCoordinator,
                                                 ModuleMetadataCapabilitySnapshotService snapshotService) {
        this.previewService = previewService;
        this.relationService = relationService;
        this.metadataService = metadataService;
        this.fieldService = fieldService;
        this.schemaEnsureService = schemaEnsureService;
        this.refreshCoordinator = refreshCoordinator;
        this.snapshotService = snapshotService;
    }

    @Transactional
    public MetadataRelationChangeSetPublishResult apply(String moduleAlias, String relationId,
                                                        MetadataRelationChangeSetApplyCommand command) {
        if (command == null || command.proposal() == null) {
            throw new IllegalArgumentException("metadata change-set apply command must include a proposal");
        }
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        MetadataRelationChangeSetPreview preview = previewService.preview(validModuleAlias, relationId, command.proposal());
        if (!preview.valid()) {
            throw new PlatformException("Metadata change-set validation failed: "
                    + preview.errors().stream().map(MetadataChangeSetValidationIssue::code).toList());
        }
        if (command.proposalFingerprint() == null || !command.proposalFingerprint().equals(preview.proposalFingerprint())) {
            throw new PlatformException("Metadata change-set preview fingerprint is stale; preview again before publish");
        }
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null || relation.getRelationRole() != RelationRole.MAIN) {
            throw new PlatformException("Metadata change-set apply only supports MAIN metadata relation: " + relationId);
        }
        Metadata metadata = metadataService.select(relation.getMetadataId());
        if (metadata == null) throw new PlatformException("Module relation points to missing metadata: " + relation.getMetadataId());
        MetadataRelationChangeSetPlan plan = preview.plan();
        if (!java.util.Objects.equals(plan.expectedMetadataVersion(), metadata.getVersion())) {
            throw new PlatformException("Metadata change-set is stale; reload and preview again");
        }

        MetadataCapabilityGovernanceMutationContext.run(() -> {
            applyBusinessFieldMutations(metadata, plan.fieldMutations());
            applyDeclarations(metadata, plan.replaceCapabilityDeclarations(), plan.effectiveCapabilities());
            metadataService.update(metadata); // optimistic version is the final transactional CAS boundary
            MetadataCapabilityManagedFieldMaterializer.materialize(fieldService, metadata, preview.effectiveCapabilities());
            schemaEnsureService.ensureNow(metadata);
            return null;
        });
        List<String> affected = affectedModules(metadata.getId());
        TransactionScopeSupport.afterCommitOrNow(() -> refreshCoordinator.activateByMetadataIdNow(metadata.getId()));
        return new MetadataRelationChangeSetPublishResult(preview, snapshotService.snapshot(validModuleAlias, relationId), affected);
    }

    private void applyBusinessFieldMutations(Metadata metadata, List<MetadataFieldChangeSetPlan> mutations) {
        if (mutations == null) return;
        for (MetadataFieldChangeSetPlan mutation : mutations) {
            if (mutation == null) continue;
            switch (mutation.operation()) {
                case ADD -> insertBusinessField(metadata, mutation.field());
                case UPDATE -> updateBusinessField(metadata, mutation);
                case DELETE -> throw new PlatformException("Validated additive change-set cannot delete metadata fields");
            }
        }
    }

    private void insertBusinessField(Metadata metadata, MetadataField field) {
        MetadataField mutation = newBusinessField(requireField(field));
        mutation.setMetadataId(metadata.getId());
        fieldService.insert(mutation);
    }

    private void updateBusinessField(Metadata metadata, MetadataFieldChangeSetPlan plan) {
        MetadataField existing = plan.fieldId() == null ? null : fieldService.select(plan.fieldId());
        if (existing == null || !metadata.getId().equals(existing.getMetadataId())) {
            throw new PlatformException("Metadata change-set field update is stale: " + plan.fieldId());
        }
        if (!java.util.Objects.equals(plan.expectedFieldVersion(), existing.getVersion())) {
            throw new PlatformException("Metadata change-set field version is stale: " + plan.fieldId());
        }
        MetadataField mutation = overlayBusinessAttributes(existing, requireField(plan.field()));
        fieldService.update(mutation);
    }

    private MetadataField requireField(MetadataField field) {
        if (field == null) throw new PlatformException("Validated metadata field draft is missing its field");
        return field;
    }

    private void applyDeclarations(Metadata metadata, boolean replaceDeclarations,
                                   Set<EntityCapability> effectiveCapabilities) {
        if (replaceDeclarations) {
            metadata.setCapabilityDeclarations(MetadataCapabilityCatalog.declarationNames(effectiveCapabilities));
        }
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

    private List<String> affectedModules(String metadataId) {
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        for (ModuleMetadataRelation relation : relationService.list(Criteria.of().eq("metadataId", metadataId), ALL)) {
            aliases.add(relation.getModuleAlias());
        }
        return List.copyOf(aliases);
    }
}
