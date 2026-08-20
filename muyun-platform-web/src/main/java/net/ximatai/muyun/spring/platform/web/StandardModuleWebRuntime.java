package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.form.FormSchema;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryCompiler;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * Field semantics consumed by the standard-module HTTP adapter.  They are compiled with the
     * execution plan, so request handling never infers wire types from Java reflection.
     */
    public Map<String, FieldValueType> wireFieldTypes(String moduleAlias) {
        ModuleExecutionPlan plan = requirePlan(moduleAlias);
        LinkedHashMap<String, FieldValueType> types = new LinkedHashMap<>(plan.responseWireFieldTypes());
        collectFieldTypes(types, plan.uiDescriptor().defaultEditor());
        if (plan.uiDescriptor().page() != null) {
            ResolvedModulePageDescriptor page = plan.uiDescriptor().page();
            if (page.list() != null) collectFieldTypes(types, page.list().fields());
            if (page.detail() != null) {
                collectFieldTypes(types, page.detail().display());
                collectFieldTypes(types, page.detail().editor());
            }
        }
        plan.uiDescriptor().editorSurfaces().forEach(surface -> collectFieldTypes(types, surface.editor()));
        return Map.copyOf(types);
    }

    /** Field semantics for one compiled child relation, isolated from similarly named parent fields. */
    public Map<String, FieldValueType> relationWireFieldTypes(String moduleAlias, String relationCode) {
        ModuleExecutionPlan plan = requirePlan(moduleAlias);
        var relation = plan.uiDescriptor().detailRelations().stream()
                .filter(candidate -> candidate.code().equals(relationCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown detail relation: " + relationCode));
        LinkedHashMap<String, FieldValueType> types = new LinkedHashMap<>();
        plan.uiDescriptor().editorContributions().stream()
                .filter(contribution -> contribution.resource().equals(relation.targetEntityAlias()))
                .findFirst()
                .ifPresent(contribution -> collectFieldTypes(types, contribution.editor()));
        return Map.copyOf(types);
    }

    /** Marks the current HTTP response for managed serialization-time numeric adaptation. */
    public void markWireResponse(String moduleAlias) {
        StaticModuleWebWireValues.markCurrentResponse(wireFieldTypes(moduleAlias));
    }

    /** Marks a managed relation response without leaking child field types into the parent record contract. */
    public void markRelationWireResponse(String moduleAlias, String relationCode) {
        StaticModuleWebWireValues.markCurrentResponse(relationWireFieldTypes(moduleAlias, relationCode));
    }

    /**
     * Executes the compiled plan's default list projection without consulting a controller
     * declaration.  This is the only read-projection entry point for migrated static modules.
     */
    public Optional<WebPageResponse<Map<String, Object>>> queryProjectedDefaultList(
            String moduleAlias,
            QueryRequest request,
            Criteria additionalCriteria,
            PageRequest pageRequest,
            CrudAbility<?> service,
            ActionExecutionPolicy actionPolicy,
            RecordReadVisibility visibility) {
        requirePlan(moduleAlias);
        return readProjectionService.queryDefaultList(moduleAlias, request, additionalCriteria, pageRequest,
                service, actionPolicy, visibility);
    }

    /** Applies the compiled plan's default response projection for a migrated static module. */
    public <T> WebPageResponse<T> projectDefaultList(String moduleAlias,
                                                      WebPageResponse<T> response,
                                                      CrudAbility<?> service) {
        requirePlan(moduleAlias);
        return readProjectionService.projectDefaultList(moduleAlias, response, service);
    }

    private Optional<ModuleExecutionPlan> plan(String moduleAlias) {
        return executionPlans.find(moduleAlias);
    }

    private static void collectFieldTypes(Map<String, FieldValueType> target, ResolvedViewDescriptor view) {
        if (view == null) return;
        view.fields().forEach(field -> {
            if (field.valueType() != null && field.fieldRef().relationCode() == null) {
                target.putIfAbsent(field.fieldRef().fieldName(), field.valueType());
            }
        });
    }
}
