package net.ximatai.muyun.spring.platform.workflow;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.ModuleTitleCatalog;
import net.ximatai.muyun.spring.platform.module.ModuleTitleView;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import org.springframework.stereotype.Service;

@Service
public class DefaultWorkflowRecordSummaryResolver implements WorkflowRecordSummaryResolver {
    private final WorkflowActionPolicyService policy;
    private final ModuleRecordFacts facts;
    private final PlatformModuleService modules;
    private final WorkflowDefinitionService definitions;

    public DefaultWorkflowRecordSummaryResolver(WorkflowActionPolicyService policy, ModuleRecordFacts facts,
            PlatformModuleService modules, WorkflowDefinitionService definitions) {
        this.policy = Objects.requireNonNull(policy);
        this.facts = Objects.requireNonNull(facts);
        this.modules = Objects.requireNonNull(modules);
        this.definitions = Objects.requireNonNull(definitions);
    }

    @Override
    public Map<String, String> modules() { return moduleTitles().originalTitles(); }

    @Override
    public ModuleTitleCatalog moduleTitles() {
        var result = new LinkedHashMap<String, ModuleTitleView>();
        var tenant = TenantContext.currentTenantId().orElse(null);
        var enabledAliases = new HashSet<String>();
        try (var scope = TenantContext.system("workflow published module catalog")) {
            definitions.list(Criteria.of().eq("enabled", true).eq("definitionStatus", WorkflowDefinitionStatus.PUBLISHED),
                    new PageRequest(0, Integer.MAX_VALUE)).stream()
                    .filter(definition -> definition.getTenantId() == null || Objects.equals(tenant, definition.getTenantId()))
                    .map(WorkflowDefinition::getModuleAlias).forEach(enabledAliases::add);
        }
        modules.listVisibleModules().stream().filter(module -> enabledAliases.contains(module.getAlias()))
                .forEach(module -> result.put(module.getAlias(), ModuleTitleView.of(module)));
        return new ModuleTitleCatalog(result);
    }

    @Override
    public WorkflowRecordSummary resolve(WorkflowInstance instance) {
        var module = modules.resolveVisibleModule(instance.getModuleAlias());
        var directory = module == null ? new ModuleTitleView(instance.getModuleAlias(), instance.getModuleAlias(), null)
                : ModuleTitleView.of(module);
        try {
            policy.requireRecordView(instance);
            var title = facts.displayTitleView(instance.getModuleAlias(), instance.getRecordId());
            return new WorkflowRecordSummary(title == null ? null : title.text(), directory.title(), true,
                    directory.titleSource(), title == null ? null : title.contentText());
        } catch (PlatformAccessDeniedException denied) {
            return new WorkflowRecordSummary(null, directory.title(), false, directory.titleSource());
        }
    }
}
