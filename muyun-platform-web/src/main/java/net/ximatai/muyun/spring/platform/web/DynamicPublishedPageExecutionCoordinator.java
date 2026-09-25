package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.runtime.PlatformModuleDefinitionCompiler;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;

import net.ximatai.muyun.spring.platform.runtime.DynamicRuntimeActivationService;
import net.ximatai.muyun.spring.platform.ui.PublishedPageExecutionCoordinator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/** Bridges platform publication transactions to the dynamic page execution-plan directory. */
@Component
public class DynamicPublishedPageExecutionCoordinator implements PublishedPageExecutionCoordinator {
    private final Supplier<PlatformModuleRuntimeContextService> runtimeContextService;
    private final ModuleExecutionPlanCatalog executionPlanCatalog;
    private final Supplier<DynamicRuntimeActivationService> activation;
    private final Supplier<PlatformModuleDefinitionCompiler> compiler;

    @Autowired
    public DynamicPublishedPageExecutionCoordinator(ObjectProvider<PlatformModuleRuntimeContextService> runtimeContextService,
                                                    ModuleExecutionPlanCatalog executionPlanCatalog,
                                                    ObjectProvider<DynamicRuntimeActivationService> activation,
                                                    ObjectProvider<PlatformModuleDefinitionCompiler> compiler) {
        this(runtimeContextService::getObject, executionPlanCatalog, activation::getObject, compiler::getObject);
    }

    DynamicPublishedPageExecutionCoordinator(Supplier<PlatformModuleRuntimeContextService> runtimeContextService,
                                             ModuleExecutionPlanCatalog executionPlanCatalog,
                                             Supplier<DynamicRuntimeActivationService> activation,
                                             Supplier<PlatformModuleDefinitionCompiler> compiler) {
        this.compiler = compiler;
        this.activation = activation;
        this.runtimeContextService = runtimeContextService;
        this.executionPlanCatalog = executionPlanCatalog;
    }

    @Override
    public void prepareAfterPublishedConfigurationChange(String moduleAlias) {
        // Resolution happens while the candidate publication is still visible in its transaction.
        // It either yields a fully compiled plan or the explicit non-executable state for an
        // incomplete page. Any compilation failure intentionally escapes and rolls back publish.
        Optional<ModuleExecutionPlan> candidate = runtimeContextService.get().pendingDynamicExecutionPlan(
                DynamicModuleDescriptor.from(compiler.get().compile(moduleAlias)));
        candidate.ifPresent(executionPlanCatalog::validateCandidate);
        activation.get().schedule(moduleAlias);
    }

    @Override
    public void installCurrentPublishedConfiguration(String moduleAlias) {
        Optional<ModuleExecutionPlan> candidate = runtimeContextService.get().dynamicExecutionPlan(moduleAlias);
        candidate.ifPresent(executionPlanCatalog::validateCandidate);
        executionPlanCatalog.replaceDynamicPlan(moduleAlias, candidate);
    }

    @Override
    public void removeInstalledConfiguration(String moduleAlias) {
        executionPlanCatalog.replaceDynamicPlan(moduleAlias, Optional.empty());
    }
}
