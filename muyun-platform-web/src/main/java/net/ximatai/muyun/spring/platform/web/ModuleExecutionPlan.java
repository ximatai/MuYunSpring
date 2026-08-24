package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;

import java.util.List;
import java.util.Map;

/**
 * Source-neutral, compiled server-side facts for one executable module revision.
 *
 * <p>The plan deliberately contains no request, service, or SQL state. Those remain runtime
 * concerns; this object is the immutable bridge between a module declaration and that runtime.</p>
 */
public record ModuleExecutionPlan(String moduleAlias,
                                  String versionKey,
                                  ResolvedModuleUiDescriptor uiDescriptor,
                                  ResolvedModuleReadModel readModel,
                                  List<PageContextBindingDefinition> pageContextBindings,
                                  QueryDescriptor queryDescriptor,
                                  QuerySchema querySchema,
                                  List<String> queryTemplateIds,
                                  List<ModuleQueryTemplatePlan> queryTemplates,
                                  String listUiConfigId,
                                  String formUiConfigId,
                                  List<ModuleQueryFormField> queryFormFields,
                                  List<PageContextBindingDefinition> mutationConstraints,
                                  List<ModuleMutationFieldValidation> mutationFieldValidations,
                                  List<StaticModuleActionDefinition> actions,
                                  boolean dataScopeEnabled,
                                  Map<String, FieldValueType> responseWireFieldTypes,
                                  Map<String, Map<String, FieldValueType>> detailRelationWireFieldTypes) {
    /** Source-compatible constructor for callers that do not yet provide response wire facts. */
    public ModuleExecutionPlan(String moduleAlias, String versionKey, ResolvedModuleUiDescriptor uiDescriptor,
                               ResolvedModuleReadModel readModel,
                               List<PageContextBindingDefinition> pageContextBindings,
                               QueryDescriptor queryDescriptor,
                               QuerySchema querySchema,
                               List<String> queryTemplateIds,
                               List<ModuleQueryTemplatePlan> queryTemplates,
                               String listUiConfigId,
                               String formUiConfigId,
                               List<ModuleQueryFormField> queryFormFields,
                               List<PageContextBindingDefinition> mutationConstraints,
                               List<ModuleMutationFieldValidation> mutationFieldValidations,
                               List<StaticModuleActionDefinition> actions,
                               boolean dataScopeEnabled) {
        this(moduleAlias, versionKey, uiDescriptor, readModel, pageContextBindings, queryDescriptor, querySchema,
                queryTemplateIds, queryTemplates, listUiConfigId, formUiConfigId, queryFormFields,
                mutationConstraints, mutationFieldValidations, actions, dataScopeEnabled, Map.of(), Map.of());
    }
    /** Compatibility constructor for plans created before query and mutation facts were explicit. */
    public ModuleExecutionPlan(String moduleAlias, String versionKey, ResolvedModuleUiDescriptor uiDescriptor,
                               ResolvedModuleReadModel readModel,
                               List<PageContextBindingDefinition> pageContextBindings) {
        this(moduleAlias, versionKey, uiDescriptor, readModel, pageContextBindings,
                QueryDescriptor.builder(moduleAlias).build(),
                QuerySchema.from(QueryDescriptor.builder(moduleAlias).build()),
                List.of(),
                List.of(), null, null,
                List.of(),
                pageContextBindings == null ? List.of() : pageContextBindings.stream()
                        .filter(binding -> binding.target() == PageContextTarget.MUTATION_CONSTRAINT).toList(),
                List.of(),
                List.of(), false, Map.of(), Map.of());
    }

    /** Compatibility constructor for callers that already provide compiled query and mutation facts. */
    public ModuleExecutionPlan(String moduleAlias, String versionKey, ResolvedModuleUiDescriptor uiDescriptor,
                               ResolvedModuleReadModel readModel, List<PageContextBindingDefinition> pageContextBindings,
                               QueryDescriptor queryDescriptor, QuerySchema querySchema,
                               List<PageContextBindingDefinition> mutationConstraints,
                               List<StaticModuleActionDefinition> actions, boolean dataScopeEnabled) {
        this(moduleAlias, versionKey, uiDescriptor, readModel, pageContextBindings, queryDescriptor, querySchema,
                List.of(), List.of(), null, null, List.of(), mutationConstraints, List.of(), actions, dataScopeEnabled,
                Map.of(), Map.of());
    }

    /** Compatibility constructor with explicit model-derived response wire facts. */
    public ModuleExecutionPlan(String moduleAlias, String versionKey, ResolvedModuleUiDescriptor uiDescriptor,
                               ResolvedModuleReadModel readModel, List<PageContextBindingDefinition> pageContextBindings,
                               QueryDescriptor queryDescriptor, QuerySchema querySchema,
                               List<PageContextBindingDefinition> mutationConstraints,
                               List<StaticModuleActionDefinition> actions, boolean dataScopeEnabled,
                               Map<String, FieldValueType> responseWireFieldTypes) {
        this(moduleAlias, versionKey, uiDescriptor, readModel, pageContextBindings, queryDescriptor, querySchema,
                List.of(), List.of(), null, null, List.of(), mutationConstraints, List.of(), actions, dataScopeEnabled,
                responseWireFieldTypes, Map.of());
    }

    /** Compatibility constructor with complete main and detail-relation response wire facts. */
    public ModuleExecutionPlan(String moduleAlias, String versionKey, ResolvedModuleUiDescriptor uiDescriptor,
                               ResolvedModuleReadModel readModel, List<PageContextBindingDefinition> pageContextBindings,
                               QueryDescriptor queryDescriptor, QuerySchema querySchema,
                               List<PageContextBindingDefinition> mutationConstraints,
                               List<StaticModuleActionDefinition> actions, boolean dataScopeEnabled,
                               Map<String, FieldValueType> responseWireFieldTypes,
                               Map<String, Map<String, FieldValueType>> detailRelationWireFieldTypes) {
        this(moduleAlias, versionKey, uiDescriptor, readModel, pageContextBindings, queryDescriptor, querySchema,
                List.of(), List.of(), null, null, List.of(), mutationConstraints, List.of(), actions, dataScopeEnabled,
                responseWireFieldTypes, detailRelationWireFieldTypes);
    }

    public ModuleExecutionPlan {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (versionKey == null || versionKey.isBlank()) {
            throw new IllegalArgumentException("module execution plan version key must not be blank");
        }
        if (uiDescriptor == null || readModel == null) {
            throw new IllegalArgumentException("module execution plan requires compiled UI descriptor and read model");
        }
        if (!moduleAlias.equals(uiDescriptor.moduleAlias()) || !moduleAlias.equals(readModel.moduleAlias())) {
            throw new IllegalArgumentException("module execution plan compiled facts must match module alias: " + moduleAlias);
        }
        pageContextBindings = pageContextBindings == null ? List.of() : List.copyOf(pageContextBindings);
        queryDescriptor = queryDescriptor == null ? QueryDescriptor.builder(moduleAlias).build() : queryDescriptor;
        if (!moduleAlias.equals(queryDescriptor.scopeName())) {
            throw new IllegalArgumentException("module execution query scope must match module alias: " + moduleAlias);
        }
        querySchema = querySchema == null ? QuerySchema.from(queryDescriptor) : querySchema;
        validatePersistentQueryControls(moduleAlias, uiDescriptor, querySchema, pageContextBindings);
        queryTemplateIds = queryTemplateIds == null ? List.of() : queryTemplateIds.stream()
                .filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
        queryTemplates = queryTemplates == null ? List.of() : List.copyOf(queryTemplates);
        if (!queryTemplateIds.containsAll(queryTemplates.stream().map(ModuleQueryTemplatePlan::templateId).toList())) {
            throw new IllegalArgumentException("compiled query templates must belong to module plan template ids");
        }
        listUiConfigId = listUiConfigId == null || listUiConfigId.isBlank() ? null : listUiConfigId.trim();
        formUiConfigId = formUiConfigId == null || formUiConfigId.isBlank() ? null : formUiConfigId.trim();
        queryFormFields = queryFormFields == null ? List.of() : List.copyOf(queryFormFields);
        mutationConstraints = mutationConstraints == null ? List.of() : List.copyOf(mutationConstraints);
        if (mutationConstraints.stream().anyMatch(binding -> binding.target() != PageContextTarget.MUTATION_CONSTRAINT)) {
            throw new IllegalArgumentException("module execution mutation facts may contain only mutation constraints");
        }
        mutationFieldValidations = mutationFieldValidations == null ? List.of() : List.copyOf(mutationFieldValidations);
        actions = actions == null ? List.of() : List.copyOf(actions);
        responseWireFieldTypes = responseWireFieldTypes == null ? Map.of() : Map.copyOf(responseWireFieldTypes);
        detailRelationWireFieldTypes = detailRelationWireFieldTypes == null ? Map.of()
                : detailRelationWireFieldTypes.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue() == null ? Map.of() : Map.copyOf(entry.getValue())
                ));
    }

    private static void validatePersistentQueryControls(String moduleAlias,
                                                        ResolvedModuleUiDescriptor uiDescriptor,
                                                        QuerySchema querySchema,
                                                        List<PageContextBindingDefinition> pageContextBindings) {
        if (uiDescriptor.page() == null || uiDescriptor.page().list() == null) {
            return;
        }
        for (ResolvedPageListPersistentQueryControlDescriptor control
                : uiDescriptor.page().list().persistentQueryControls()) {
            QuerySchema.ExternalCriteria criterion = querySchema.externalCriteria().stream()
                    .filter(item -> control.externalCriteriaKey().equals(item.key()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "persistent query control requires declared external criteria: "
                                    + moduleAlias + "." + control.externalCriteriaKey()));
            if (!"BOOLEAN".equals(criterion.valueType()) || !"USER_INPUT".equals(criterion.providedBy())) {
                throw new IllegalArgumentException("persistent query control requires BOOLEAN USER_INPUT criteria: "
                        + moduleAlias + "." + control.externalCriteriaKey());
            }
            boolean conflictsWithPageContext = pageContextBindings.stream()
                    .filter(binding -> binding.target() == PageContextTarget.LIST_QUERY)
                    .anyMatch(binding -> control.externalCriteriaKey().equals(binding.targetKey()));
            if (conflictsWithPageContext) {
                throw new IllegalArgumentException("persistent query control must not override page context criteria: "
                        + moduleAlias + "." + control.externalCriteriaKey());
            }
        }
    }
}
