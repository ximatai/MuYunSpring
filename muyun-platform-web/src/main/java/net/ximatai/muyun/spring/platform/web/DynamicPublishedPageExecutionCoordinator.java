package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.platform.ui.PublishedPageExecutionCoordinator;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Bridges platform publication transactions to the dynamic page execution-plan directory. */
@Component
public class DynamicPublishedPageExecutionCoordinator implements PublishedPageExecutionCoordinator {
    private final PlatformModuleRuntimeContextService runtimeContextService;
    private final ModuleExecutionPlanCatalog executionPlanCatalog;

    public DynamicPublishedPageExecutionCoordinator(PlatformModuleRuntimeContextService runtimeContextService,
                                                    ModuleExecutionPlanCatalog executionPlanCatalog) {
        this.runtimeContextService = runtimeContextService;
        this.executionPlanCatalog = executionPlanCatalog;
    }

    @Override
    public void prepareAfterPublishedConfigurationChange(String moduleAlias) {
        // Resolution happens while the candidate publication is still visible in its transaction.
        // It either yields a fully compiled plan or the explicit non-executable state for an
        // incomplete page. Any compilation failure intentionally escapes and rolls back publish.
        Optional<ModuleExecutionPlan> candidate = runtimeContextService.dynamicExecutionPlan(moduleAlias);
        TransactionScopeSupport.afterCommitOrNow(
                () -> executionPlanCatalog.replaceDynamicPlan(moduleAlias, candidate));
    }
}
