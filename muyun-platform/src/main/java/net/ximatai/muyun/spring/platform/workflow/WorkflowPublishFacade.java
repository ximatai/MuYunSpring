package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

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
            throw new PlatformException("workflow version does not belong to definition: " + versionId);
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
            throw new PlatformException("workflow definition not found: " + definitionId);
        }
        return definition;
    }

    private WorkflowVersion requireVersion(String versionId) {
        WorkflowVersion version = versionService.select(versionId);
        if (version == null) {
            throw new PlatformException("workflow version not found: " + versionId);
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
            throw new PlatformException("workflow overtimeDurationMinutes must not be less than warningDurationMinutes: "
                    + node.getNodeKey());
        }
        if (node.getNodeType() != WorkflowNodeType.APPROVAL
                && (node.getWarningDurationMinutes() != null || node.getOvertimeDurationMinutes() != null)) {
            throw new PlatformException("workflow overtime durations are only supported on approval nodes: "
                    + node.getNodeKey());
        }
    }

    private void validatePositiveDuration(Integer value, String fieldName, WorkflowNodeDefinition node) {
        if (value != null && value <= 0) {
            throw new PlatformException("workflow " + fieldName + " must be positive: " + node.getNodeKey());
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
