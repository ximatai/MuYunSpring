package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowDefinitionSelector {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final WorkflowDefinitionService definitionService;
    private final WorkflowVersionService versionService;
    private final WorkflowNodeDefinitionDao nodeDefinitionDao;
    private final WorkflowLinkDefinitionDao linkDefinitionDao;
    private final WorkflowConditionService conditions;
    private final java.util.Optional<net.ximatai.muyun.spring.common.platform.OrganizationHierarchyService> organizations;

    public WorkflowDefinitionSelector(WorkflowDefinitionService definitionService,
                                      WorkflowVersionService versionService,
                                      WorkflowNodeDefinitionDao nodeDefinitionDao,
                                      WorkflowLinkDefinitionDao linkDefinitionDao,
                                      WorkflowConditionService conditions,
                                      java.util.Optional<net.ximatai.muyun.spring.common.platform.OrganizationHierarchyService> organizations) {
        this.definitionService = definitionService;
        this.versionService = versionService;
        this.nodeDefinitionDao = nodeDefinitionDao;
        this.linkDefinitionDao = linkDefinitionDao;
        this.conditions = conditions;
        this.organizations = organizations;
    }

    public WorkflowDefinitionSelection select(WorkflowSubmitRequest request) {
        String moduleAlias = PlatformNameRules.requireModuleAlias(requireText(request.moduleAlias(), "moduleAlias"));
        Criteria criteria = Criteria.of()
                .eq("moduleAlias", moduleAlias)
                .eq("approvalEnabled", request.approvalRequired())
                .eq("definitionStatus", WorkflowDefinitionStatus.PUBLISHED)
                .eq("enabled", Boolean.TRUE);
        boolean hasDefinitionAlias = request.definitionAlias() != null && !request.definitionAlias().isBlank();
        if (hasDefinitionAlias) {
            criteria.eq("alias", PlatformNameRules.requireIdentifier(request.definitionAlias(), "workflowAlias"));
        } else if (!request.approvalRequired()) {
            throw new PlatformException("workflow definition alias is required for non-approval workflow");
        }
        String tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null);
        List<WorkflowDefinition> definitions;
        try (var definitionScope = net.ximatai.muyun.spring.common.tenant.TenantContext.system("workflow published definition selection")) {
            definitions = definitionService.list(criteria, ALL, Sort.asc("sortOrder"), Sort.asc("alias"))
                    .stream().filter(item -> item.getTenantId() == null || java.util.Objects.equals(item.getTenantId(), tenant)).toList();
        }
        WorkflowDefinition definition = match(definitions, request);
        WorkflowVersion version = publishedVersion(definition);
        List<WorkflowNodeDefinition> nodes = nodeDefinitionDao.query(
                Criteria.of().eq("workflowVersionId", version.getId()), ALL, Sort.asc("sortOrder"));
        List<WorkflowLinkDefinition> links = linkDefinitionDao.query(
                Criteria.of().eq("workflowVersionId", version.getId()), ALL, Sort.asc("sortOrder"));
        return new WorkflowDefinitionSelection(definition, version, nodes, links);
    }

    private WorkflowDefinition match(List<WorkflowDefinition> definitions, WorkflowSubmitRequest request) {
        java.util.List<String> scopes = new java.util.ArrayList<>();
        if (request.authOrgId() != null) {
            scopes.addAll(organizations.map(service -> service.organizationIdsFromSelfToRoot(request.authOrgId()))
                    .orElse(java.util.List.of(request.authOrgId())));
        }
        scopes.add(null);
        for (String organizationId : scopes) {
            var candidates = definitions.stream()
                    .filter(item -> java.util.Objects.equals(item.getOrganizationId(), organizationId))
                    .toList();
            var matched = candidates.stream().filter(item -> !Boolean.TRUE.equals(item.getDefaultDefinition()))
                    .filter(item -> conditions.matches(item.getMatchExpression(), request.moduleAlias(), request.recordId()))
                    .toList();
            if (matched.isEmpty()) matched = candidates.stream().filter(item -> Boolean.TRUE.equals(item.getDefaultDefinition())).toList();
            if (matched.isEmpty()) continue;
            int priority = matched.stream().mapToInt(item -> item.getMatchPriority() == null ? 0 : item.getMatchPriority()).max().orElse(0);
            var winners = matched.stream().filter(item -> (item.getMatchPriority() == null ? 0 : item.getMatchPriority()) == priority).toList();
            if (winners.size() != 1) throw new PlatformException("multiple approval workflow definitions matched: " + request.moduleAlias());
            return winners.getFirst();
        }
        throw new PlatformException("published workflow definition not found: " + request.moduleAlias());
    }

    private WorkflowVersion publishedVersion(WorkflowDefinition definition) {
        Criteria criteria = Criteria.of()
                .eq("definitionId", definition.getId())
                .eq("publishStatus", WorkflowPublishStatus.PUBLISHED);
        if (definition.getCurrentVersionNo() != null) {
            criteria.eq("versionNo", definition.getCurrentVersionNo());
        }
        List<WorkflowVersion> versions;
        try (var definitionScope = net.ximatai.muyun.spring.common.tenant.TenantContext.system("workflow published definition selection")) {
            versions = versionService.list(criteria, ALL, Sort.desc("versionNo"));
        }
        if (versions.isEmpty()) {
            throw new PlatformException("published workflow version not found: " + definition.getAlias());
        }
        return versions.getFirst();
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(name + " must not be blank");
        }
        return value;
    }
}
