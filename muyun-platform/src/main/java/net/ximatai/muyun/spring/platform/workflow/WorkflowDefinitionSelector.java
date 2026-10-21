package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowDefinitionSelector {
    public static final String NO_PUBLISHED_DEFINITION = "platform.workflow.no-published-definition";

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
        boolean hasDefinitionId = request.definitionId() != null && !request.definitionId().isBlank();
        if (hasDefinitionId && hasDefinitionAlias) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-definition-selector.choose-id-or-alias",
                    "specify either workflow definition id or alias, not both"));
        }
        if (hasDefinitionId) {
            criteria.eq("id", request.definitionId());
        } else if (hasDefinitionAlias) {
            criteria.eq("alias", PlatformNameRules.requireIdentifier(request.definitionAlias(), "workflowAlias"));
        } else if (!request.approvalRequired()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-definition-selector.enter-a-definition-alias-for-a-workflow-without-approval",
                    "workflow definition id or alias is required for non-approval workflow"));
        }
        String tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null);
        List<WorkflowDefinition> definitions;
        try (var definitionScope = net.ximatai.muyun.spring.common.tenant.TenantContext.system("workflow published definition selection")) {
            definitions = definitionService.list(criteria, ALL, Sort.asc("sortOrder"), Sort.asc("alias"), Sort.asc("id"))
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
                    .findFirst();
            if (matched.isPresent()) return matched.get();
            var fallback = candidates.stream().filter(item -> Boolean.TRUE.equals(item.getDefaultDefinition())).findFirst();
            if (fallback.isPresent()) return fallback.get();
        }
        throw new PlatformException(NO_PUBLISHED_DEFINITION, 404,
                "published workflow definition not found: " + request.moduleAlias(), null,
                net.ximatai.muyun.spring.common.exception.ErrorScope.empty(), List.of(), Map.of(),
                Map.of("moduleAlias", request.moduleAlias()), TextReference.code(
                        "platform.rejection.workflow.no-published-definition",
                        "published workflow definition not found: {moduleAlias}"));
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow-definition-selector.published-workflow-version-not-found",
                    "published workflow version not found: {value1}"), Map.of("value1", String.valueOf(definition.getAlias())));
        }
        return versions.getFirst();
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.workflow.required-field",
                    "{name} must not be blank"), Map.of("name", name));
        }
        return value;
    }
}
