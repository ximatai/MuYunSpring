package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowPublishFacade {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final WorkflowDefinitionService definitionService;
    private final WorkflowVersionService versionService;
    private final WorkflowModuleActionContributor actionContributor;
    private final WorkflowNodeDefinitionDao nodeDefinitionDao;
    private final WorkflowDesignService designs;
    private final WorkflowDesignCompiler compiler;

    public WorkflowPublishFacade(WorkflowDefinitionService definitionService,
                                 WorkflowVersionService versionService,
                                 WorkflowModuleActionContributor actionContributor,
                                 WorkflowNodeDefinitionDao nodeDefinitionDao,
                                 WorkflowDesignService designs, WorkflowDesignCompiler compiler) {
        this.definitionService = definitionService;
        this.versionService = versionService;
        this.actionContributor = actionContributor;
        this.nodeDefinitionDao = nodeDefinitionDao;
        this.designs = designs;
        this.compiler = compiler;
    }

    @Transactional
    public WorkflowVersion publish(String definitionId, String versionId, String operatorId) {
        return publish(definitionId, versionId, null, null, operatorId);
    }

    @Transactional
    public WorkflowVersion publish(String definitionId, String versionId, Integer expectedDefinitionVersion,
                                   Integer expectedWorkflowVersion, String operatorId) {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.lockMutationPartition("workflow.definition",
                net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse("system") + ":" + definitionId);
        WorkflowDefinition definition = requireDefinition(definitionId);
        WorkflowVersion version = requireVersion(versionId);
        requireExpectedVersion(definition, expectedDefinitionVersion, "workflow definition");
        requireExpectedVersion(version, expectedWorkflowVersion, "workflow version");
        if (!definition.getId().equals(version.getDefinitionId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.the-version-does-not-belong-to-this-workflow-definition",
                    "workflow version does not belong to definition: {value1}"), Map.of("value1", String.valueOf(versionId)));
        }
        if (version.getPublishStatus() != WorkflowPublishStatus.PUBLISHED) {
        var design = designs.validate(definitionId, versionId);
        validateOvertimeDefinitions(version);
        for (var node : design.nodes()) {
            Integer expectedNodeVersion = node.getVersion();
            net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareUpdate(node, Instant.now());
            if (nodeDefinitionDao.updateByIdAndVersion(node, expectedNodeVersion) == 0)
                throw new OptimisticLockException("workflow node changed while publishing: " + node.getNodeKey());
        }
            WorkflowVersion publishing = copyVersion(version);
            publishing.setSnapshotText(compiler.serialize(design));
            publishing.setSemanticJson(compiler.serialize(design));
            publishing.setLayoutJson(design.layoutJson());
            publishing.setPublishStatus(WorkflowPublishStatus.PUBLISHED);
            publishing.setPublishedBy(operatorId == null || operatorId.isBlank() ? null : operatorId);
            publishing.setPublishedAt(Instant.now());
            versionService.update(publishing);
            version = publishing;
        }
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED);
        definition.setCurrentVersionNo(version.getVersionNo());
        definitionService.update(definition);
        actionContributor.registerPublishedWorkflowAction(definition, version);
        return version;
    }

    public WorkflowVersion publish(String definitionId, String versionId) {
        return publish(definitionId, versionId, null);
    }

    /** Restores the frozen current published version without publishing a working draft. */
    @Transactional
    public WorkflowDefinition enable(String definitionId, Integer expectedVersion) {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.lockMutationPartition("workflow.definition",
                net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse("system") + ":" + definitionId);
        WorkflowDefinition definition = requireDefinition(definitionId);
        requireExpectedVersion(definition, expectedVersion, "workflow definition");
        if (definition.getDefinitionStatus() != WorkflowDefinitionStatus.DISABLED || definition.getCurrentVersionNo() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.enable-published-only",
                    "only a disabled workflow with a published version can be enabled"));
        }
        WorkflowVersion published = versionService.list(Criteria.of()
                .eq("definitionId", definitionId)
                .eq("versionNo", definition.getCurrentVersionNo())
                .eq("publishStatus", WorkflowPublishStatus.PUBLISHED), new PageRequest(0, 1)).stream().findFirst().orElse(null);
        if (published == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.enable-published-only",
                    "only a disabled workflow with a published version can be enabled"));
        }
        definition.setEnabled(true);
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED);
        definitionService.update(definition);
        actionContributor.registerPublishedWorkflowAction(definition, published);
        return definition;
    }

    @Transactional
    public WorkflowDefinition disable(String definitionId) {
        return disable(definitionId, null);
    }

    @Transactional
    public WorkflowDefinition disable(String definitionId, Integer expectedVersion) {
        return changeDefinitionStatus(definitionId, expectedVersion, WorkflowDefinitionStatus.DISABLED);
    }

    @Transactional
    public WorkflowDefinition archive(String definitionId) {
        return archive(definitionId, null);
    }

    @Transactional
    public WorkflowDefinition archive(String definitionId, Integer expectedVersion) {
        return changeDefinitionStatus(definitionId, expectedVersion, WorkflowDefinitionStatus.ARCHIVED);
    }

    private WorkflowDefinition changeDefinitionStatus(String definitionId, Integer expectedVersion,
                                                      WorkflowDefinitionStatus status) {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.lockMutationPartition("workflow.definition",
                net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse("system") + ":" + definitionId);
        WorkflowDefinition definition = requireDefinition(definitionId);
        requireExpectedVersion(definition, expectedVersion, "workflow definition");
        definition.setDefinitionStatus(status);
        definitionService.update(definition);
        actionContributor.disableWorkflowActions(definition);
        return definition;
    }

    private WorkflowDefinition requireDefinition(String definitionId) {
        WorkflowDefinition definition = definitionService.select(definitionId);
        if (definition == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.workflow-definition-not-found",
                    "workflow definition not found: {value1}"), Map.of("value1", String.valueOf(definitionId)));
        }
        return definition;
    }

    private WorkflowVersion requireVersion(String versionId) {
        WorkflowVersion version = versionService.select(versionId);
        if (version == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.workflow-version-not-found",
                    "workflow version not found: {value1}"), Map.of("value1", String.valueOf(versionId)));
        }
        return version;
    }

    private void requireExpectedVersion(net.ximatai.muyun.spring.common.model.contract.EntityContract entity,
                                        Integer expectedVersion, String recordType) {
        if (expectedVersion != null && !expectedVersion.equals(entity.getVersion())) {
            throw new OptimisticLockException(recordType + " version conflict: " + entity.getId());
        }
    }

    private void validateOvertimeDefinitions(WorkflowVersion version) {
        if (nodeDefinitionDao == null) {
            return;
        }
        List<WorkflowNodeDefinition> nodes = nodeDefinitionDao.query(
                Criteria.of().eq("workflowVersionId", version.getId()), ALL);
        for (WorkflowNodeDefinition node : nodes) {
            validateOvertimeDefinition(node);
        }
    }

    private void validateOvertimeDefinition(WorkflowNodeDefinition node) {
        validatePositiveDuration(node.getWarningDurationMinutes(), "warningDurationMinutes", node);
        validatePositiveDuration(node.getOvertimeDurationMinutes(), "overtimeDurationMinutes", node);
        if (node.getWarningDurationMinutes() != null
                && node.getOvertimeDurationMinutes() != null
                && node.getOvertimeDurationMinutes() < node.getWarningDurationMinutes()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.the-overtime-duration-must-not-be-shorter-than-the-warning-duration",
                    "workflow overtimeDurationMinutes must not be less than warningDurationMinutes: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
        }
        if (node.getNodeType() != WorkflowNodeType.APPROVAL
                && (node.getWarningDurationMinutes() != null || node.getOvertimeDurationMinutes() != null)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.overtime-settings-are-only-supported-on-approval-nodes",
                    "workflow overtime durations are only supported on approval nodes: {value1}"), Map.of("value1", String.valueOf(node.getNodeKey())));
        }
    }

    private void validatePositiveDuration(Integer value, String fieldName, WorkflowNodeDefinition node) {
        if (value != null && value <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-publish.the-workflow-setting-must-be-positive",
                    "workflow {value1} must be positive: {value2}"), Map.of("value1", String.valueOf(fieldName), "value2", String.valueOf(node.getNodeKey())));
        }
    }

    private WorkflowVersion copyVersion(WorkflowVersion source) {
        WorkflowVersion copy = new WorkflowVersion();
        copy.setId(source.getId());
        copy.setTenantId(source.getTenantId());
        copy.setVersion(source.getVersion());
        copy.setDeleted(source.getDeleted());
        copy.setDeletedAt(source.getDeletedAt());
        copy.setDeletedBy(source.getDeletedBy());
        copy.setDefinitionId(source.getDefinitionId());
        copy.setVersionNo(source.getVersionNo());
        copy.setPublishStatus(source.getPublishStatus());
        copy.setSnapshotText(source.getSnapshotText());
        copy.setSemanticJson(source.getSemanticJson());
        copy.setLayoutJson(source.getLayoutJson());
        copy.setPublishedBy(source.getPublishedBy());
        copy.setPublishedAt(source.getPublishedAt());
        return copy;
    }
}
