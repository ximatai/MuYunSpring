package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Aggregate authoring boundary; callers never persist executable nodes independently. */
@Service
public class WorkflowDesignService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);
    private final WorkflowDefinitionService definitions;
    private final WorkflowVersionService versions;
    private final WorkflowNodeDefinitionDao nodes;
    private final WorkflowLinkDefinitionDao links;
    private final WorkflowDesignCompiler compiler;
    private final WorkflowBusinessTaskResolver taskSpecifications;

    public WorkflowDesignService(WorkflowDefinitionService definitions, WorkflowVersionService versions,
                                 WorkflowNodeDefinitionDao nodes, WorkflowLinkDefinitionDao links,
                                 WorkflowDesignCompiler compiler, WorkflowBusinessTaskResolver taskSpecifications) {
        this.definitions = definitions;
        this.versions = versions;
        this.nodes = nodes;
        this.links = links;
        this.compiler = compiler;
        this.taskSpecifications = taskSpecifications;
    }

    public WorkflowDesignDocument read(String definitionId, String versionId) {
        requireVersion(definitionId, versionId);
        return document(versionId);
    }

    /** Selection settings govern future submissions; executable published versions remain immutable. */
    @Transactional
    public WorkflowDefinition configureSelection(String definitionId, SelectionSettings settings) {
        lock(definitionId);
        var definition = requireDefinition(definitionId);
        if (definition.getDefinitionStatus() == WorkflowDefinitionStatus.ARCHIVED)
            throw new PlatformException("已归档流程不能调整匹配规则");
        if (settings.version() == null || !settings.version().equals(definition.getVersion()))
            throw new OptimisticLockException("workflow selection settings version conflict");
        if (settings.title() == null || settings.title().isBlank()) throw new PlatformException("流程名称不能为空");
        compiler.validateSelectionExpression(settings.matchExpression());
        definition.setTitle(settings.title().trim());
        definition.setOrganizationId(settings.organizationId() == null || settings.organizationId().isBlank() ? null : settings.organizationId().trim());
        definition.setMatchExpression(settings.matchExpression() == null || settings.matchExpression().isBlank() ? null : settings.matchExpression().trim());
        definition.setMatchPriority(settings.matchPriority() == null ? 0 : settings.matchPriority());
        definition.setDefaultDefinition(Boolean.TRUE.equals(settings.defaultDefinition()));
        definitions.update(definition);
        return definitions.select(definitionId);
    }

    public record SelectionSettings(Integer version, String title, String organizationId, String matchExpression,
                                    Integer matchPriority, Boolean defaultDefinition) {}

    @Transactional
    public WorkflowVersion save(String definitionId, String versionId, Integer expectedVersion,
                                 WorkflowDesignDocument document) {
        lock(definitionId);
        var definition = requireDefinition(definitionId);
        var version = requireVersion(definitionId, versionId);
        if (version.getPublishStatus() != WorkflowPublishStatus.DRAFT)
            throw new PlatformException("已发布版本不能编辑，请创建新版本");
        if (expectedVersion == null || !expectedVersion.equals(version.getVersion()))
            throw new OptimisticLockException("workflow design version conflict: " + versionId);
        compiler.validate(document, false, Boolean.TRUE.equals(definition.getApprovalEnabled()));
        nodes.query(Criteria.of().eq("workflowVersionId", versionId), ALL).forEach(item -> nodes.deleteById(item.getId()));
        links.query(Criteria.of().eq("workflowVersionId", versionId), ALL).forEach(item -> links.deleteById(item.getId()));
        int order = 0;
        for (var node : document.nodes()) {
            node.setId(Ids.newId());
            node.setTenantId(version.getTenantId());
            node.setWorkflowVersionId(versionId);
            node.setSortOrder(order++);
            EntityLifecycle.prepareInsert(node, Instant.now());
            nodes.insert(node);
        }
        order = 0;
        for (var link : document.links()) {
            link.setId(Ids.newId());
            link.setTenantId(version.getTenantId());
            link.setWorkflowVersionId(versionId);
            link.setSortOrder(order++);
            EntityLifecycle.prepareInsert(link, Instant.now());
            links.insert(link);
        }
        version.setSemanticJson(compiler.serialize(document));
        version.setLayoutJson(document.layoutJson());
        version.setSnapshotText(null);
        versions.update(version);
        return version;
    }

    @Transactional
    public WorkflowVersion upgrade(String definitionId) {
        lock(definitionId);
        var definition = requireDefinition(definitionId);
        var existing = versions.list(Criteria.of().eq("definitionId", definitionId), ALL, Sort.desc("versionNo"));
        var draft = existing.stream().filter(item -> item.getPublishStatus() == WorkflowPublishStatus.DRAFT).findFirst();
        if (draft.isPresent()) return draft.get();
        var next = new WorkflowVersion();
        next.setDefinitionId(definitionId);
        next.setVersionNo(existing.isEmpty() ? 1 : existing.getFirst().getVersionNo() + 1);
        next.setPublishStatus(WorkflowPublishStatus.DRAFT);
        next.setTenantId(definition.getTenantId());
        versions.insert(next);
        if (!existing.isEmpty()) return save(definitionId, next.getId(), next.getVersion(), document(existing.getFirst().getId()));
        return next;
    }

    public WorkflowDesignDocument validate(String definitionId, String versionId) {
        var definition = requireDefinition(definitionId);
        requireVersion(definitionId, versionId);
        var document = document(versionId);
        document.nodes().forEach(node -> taskSpecifications.freeze(node, definition.getModuleAlias()));
        compiler.validate(document, true, Boolean.TRUE.equals(definition.getApprovalEnabled()));
        return document;
    }

    private WorkflowDesignDocument document(String versionId) {
        var version = versions.select(versionId);
        return new WorkflowDesignDocument(nodes.query(Criteria.of().eq("workflowVersionId", versionId), ALL, Sort.asc("sortOrder")),
                links.query(Criteria.of().eq("workflowVersionId", versionId), ALL, Sort.asc("sortOrder")), version.getLayoutJson());
    }

    private WorkflowDefinition requireDefinition(String id) {
        var definition = definitions.select(id);
        if (definition == null) throw new PlatformException("workflow definition not found: " + id);
        return definition;
    }

    private WorkflowVersion requireVersion(String definitionId, String versionId) {
        var version = versions.select(versionId);
        if (version == null || !definitionId.equals(version.getDefinitionId()))
            throw new PlatformException("workflow version does not belong to definition: " + versionId);
        return version;
    }

    private void lock(String definitionId) {
        PlatformAbilityRuntime.lockMutationPartition("workflow.definition", TenantContext.currentTenantId().orElse("system") + ":" + definitionId);
    }
}
