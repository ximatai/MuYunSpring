package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QueryCompiler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Source-neutral server-side runtime for standard module Web endpoints.
 *
 * <p>Controllers hand this facade only their stable module alias and service. It consumes the
 * catalogued execution plan; it never reaches back into a controller's declaration DSL.</p>
 */
@Component
public class StandardModuleWebRuntime {
    private final ModuleExecutionPlanCatalog executionPlans;
    private final StaticRecordReadProjectionService readProjectionService;

    public StandardModuleWebRuntime(ModuleExecutionPlanCatalog executionPlans,
                                    StaticRecordReadProjectionService readProjectionService) {
        if (executionPlans == null || readProjectionService == null) {
            throw new IllegalArgumentException("standard module web runtime requires execution plan and read projection services");
        }
        this.executionPlans = executionPlans;
        this.readProjectionService = readProjectionService;
    }

    public boolean hasPlan(String moduleAlias) {
        return executionPlans.find(moduleAlias).isPresent();
    }

    /** Returns the compiled plan or fails before a migrated endpoint can use a compatibility path. */
    public ModuleExecutionPlan requirePlan(String moduleAlias) {
        return executionPlans.find(moduleAlias).orElseThrow(() -> new IllegalStateException(
                "no executable module plan is registered for migrated module: " + moduleAlias));
    }

    public Optional<QuerySchema> querySchema(String moduleAlias, CrudAbility<?> service) {
        return plan(moduleAlias).map(ModuleExecutionPlan::querySchema);
    }

    public Optional<Criteria> queryCriteria(String moduleAlias, CrudAbility<?> service, QueryRequest request) {
        return plan(moduleAlias).map(plan -> new QueryCompiler(plan.queryDescriptor()).criteria(request));
    }

    public Optional<Sort[]> querySorts(String moduleAlias, CrudAbility<?> service, QueryRequest request) {
        return plan(moduleAlias).map(plan -> new QueryCompiler(plan.queryDescriptor()).sorts(request));
    }

    public Optional<FormSchema> formSchema(String moduleAlias, Class<?> modelClass, String resource,
                                            String editorSurface) {
        return plan(moduleAlias).map(plan -> ModuleUiFormSchemaAdapter.formSchema(
                plan.uiDescriptor(), modelClass, resource, editorSurface));
    }

    public List<PageContextBindingDefinition> pageContextBindings(String moduleAlias, PageContextTarget target) {
        return plan(moduleAlias).map(ModuleExecutionPlan::pageContextBindings).orElse(List.of()).stream()
                .filter(binding -> binding.target() == target)
                .toList();
    }

    /** Server-authoritative create/update fields from the compiled execution plan. */
    public List<PageContextBindingDefinition> mutationConstraints(String moduleAlias) {
        return plan(moduleAlias).map(ModuleExecutionPlan::mutationConstraints).orElse(List.of());
    }

    private Optional<ModuleExecutionPlan> plan(String moduleAlias) {
        return executionPlans.find(moduleAlias);
    }
}
