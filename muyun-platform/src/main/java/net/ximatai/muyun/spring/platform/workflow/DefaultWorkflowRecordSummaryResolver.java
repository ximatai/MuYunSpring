package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.stereotype.Service;

@Service
public class DefaultWorkflowRecordSummaryResolver implements WorkflowRecordSummaryResolver {
    private final WorkflowActionPolicyService policy;
    private final ModuleRecordFacts facts;
    private final PlatformModuleService modules;
    private WorkflowDefinitionService definitions;
    public DefaultWorkflowRecordSummaryResolver(WorkflowActionPolicyService policy, ModuleRecordFacts facts,
                                                PlatformModuleService modules) {
        this.policy = policy; this.facts = facts; this.modules = modules;
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultWorkflowRecordSummaryResolver(WorkflowActionPolicyService policy, ModuleRecordFacts facts,
            PlatformModuleService modules, WorkflowDefinitionService definitions) {
        this(policy, facts, modules);
        this.definitions = definitions;
    }

    @Override
    public java.util.Map<String, String> modules() {
        var result = new java.util.LinkedHashMap<String, String>();
        var tenant = net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(null);
        var enabledAliases = new java.util.HashSet<String>();
        if (definitions != null) {
            try (var scope = net.ximatai.muyun.spring.common.tenant.TenantContext.system("workflow published module catalog")) {
                definitions.list(net.ximatai.muyun.database.core.orm.Criteria.of().eq("enabled", true)
                                .eq("definitionStatus", WorkflowDefinitionStatus.PUBLISHED),
                        new net.ximatai.muyun.database.core.orm.PageRequest(0, Integer.MAX_VALUE)).stream()
                        .filter(definition -> definition.getTenantId() == null || java.util.Objects.equals(tenant, definition.getTenantId()))
                        .map(WorkflowDefinition::getModuleAlias).forEach(enabledAliases::add);
            }
        }
        modules.listVisibleModules().stream().filter(module -> enabledAliases.contains(module.getAlias()))
                .forEach(module -> result.put(module.getAlias(), module.getTitle() == null ? module.getAlias() : module.getTitle()));
        return result;
    }

    @Override
    public WorkflowRecordSummary resolve(WorkflowInstance instance) {
        var module = modules.resolveVisibleModule(instance.getModuleAlias());
        String moduleTitle = module == null ? instance.getModuleAlias() : module.getTitle();
        try {
            policy.requireRecordView(instance);
            return new WorkflowRecordSummary(facts.displayTitle(instance.getModuleAlias(), instance.getRecordId()), moduleTitle, true);
        } catch (PlatformAccessDeniedException denied) {
            return new WorkflowRecordSummary(null, moduleTitle, false);
        }
    }
}
