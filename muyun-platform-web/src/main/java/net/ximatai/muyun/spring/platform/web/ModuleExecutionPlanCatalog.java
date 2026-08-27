package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventListener;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;

import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.LinkedHashSet;
import java.util.Set;

/** Compiles static declarations once and exposes their immutable execution facts by module alias. */
@Component
public class ModuleExecutionPlanCatalog implements SmartInitializingSingleton, RuntimeEventListener {
    private final StaticModuleDefinitionCatalog staticModuleCatalog;
    private final DynamicModuleExecutionPlanResolver dynamicPlanResolver;
    private final ListQuerySummaryContributorCatalog listQuerySummaryContributors;
    private volatile Map<String, ModuleExecutionPlan> cachedPlans;
    private final Map<String, ModuleExecutionPlan> dynamicPlans = new ConcurrentHashMap<>();

    public ModuleExecutionPlanCatalog(StaticModuleDefinitionCatalog staticModuleCatalog) {
        this(staticModuleCatalog, null, new ListQuerySummaryContributorCatalog(List.of()));
    }

    /** Embedded-runtime entry point with an explicit immutable summary contributor directory. */
    public ModuleExecutionPlanCatalog(StaticModuleDefinitionCatalog staticModuleCatalog,
                                      ListQuerySummaryContributorCatalog listQuerySummaryContributors) {
        this(staticModuleCatalog, null, listQuerySummaryContributors);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ModuleExecutionPlanCatalog(StaticModuleDefinitionCatalog staticModuleCatalog,
                                      ObjectProvider<PlatformModuleRuntimeContextService> runtimeContextService,
                                      ObjectProvider<ListQuerySummaryContributorCatalog> listQuerySummaryContributors) {
        this(staticModuleCatalog, new DynamicModuleExecutionPlanResolver() {
            private PlatformModuleRuntimeContextService service() {
                return runtimeContextService == null ? null : runtimeContextService.getIfAvailable();
            }

            @Override
            public Optional<ModuleExecutionPlan> resolve(String moduleAlias) {
                PlatformModuleRuntimeContextService service = service();
                return service == null ? Optional.empty() : service.dynamicExecutionPlan(moduleAlias);
            }

            @Override
            public List<String> moduleAliases() {
                PlatformModuleRuntimeContextService service = service();
                return service == null ? List.of() : service.dynamicModuleAliases();
            }
        }, summaryContributorCatalog(listQuerySummaryContributors));
    }

    ModuleExecutionPlanCatalog(StaticModuleDefinitionCatalog staticModuleCatalog,
                               DynamicModuleExecutionPlanResolver dynamicPlanResolver) {
        this(staticModuleCatalog, dynamicPlanResolver, new ListQuerySummaryContributorCatalog(List.of()));
    }

    ModuleExecutionPlanCatalog(StaticModuleDefinitionCatalog staticModuleCatalog,
                               DynamicModuleExecutionPlanResolver dynamicPlanResolver,
                               ListQuerySummaryContributorCatalog listQuerySummaryContributors) {
        if (staticModuleCatalog == null) {
            throw new IllegalArgumentException("static module definition catalog must not be null");
        }
        this.staticModuleCatalog = staticModuleCatalog;
        this.dynamicPlanResolver = dynamicPlanResolver;
        this.listQuerySummaryContributors = listQuerySummaryContributors == null
                ? new ListQuerySummaryContributorCatalog(List.of()) : listQuerySummaryContributors;
    }

    private static ListQuerySummaryContributorCatalog summaryContributorCatalog(
            ObjectProvider<ListQuerySummaryContributorCatalog> provider) {
        ListQuerySummaryContributorCatalog catalog = provider == null ? null : provider.getIfAvailable();
        return catalog == null ? new ListQuerySummaryContributorCatalog(List.of()) : catalog;
    }

    public Optional<ModuleExecutionPlan> find(String moduleAlias) {
        if (moduleAlias == null || moduleAlias.isBlank()) {
            return Optional.empty();
        }
        String validAlias = moduleAlias.trim();
        ModuleExecutionPlan staticPlan = plans().get(validAlias);
        if (staticPlan != null) {
            return Optional.of(staticPlan);
        }
        return Optional.ofNullable(dynamicPlans.get(validAlias));
    }

    public Map<String, ModuleExecutionPlan> plans() {
        Map<String, ModuleExecutionPlan> current = cachedPlans;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedPlans == null) {
                cachedPlans = compilePlans(staticModuleCatalog.definitions());
            }
            return cachedPlans;
        }
    }

    /** Compile all static plans during application startup so invalid declarations never become runnable. */
    @Override
    public void afterSingletonsInstantiated() {
        plans();
    }

    /** Dynamic metadata tables are created and populated after singleton wiring. */
    @EventListener(ApplicationReadyEvent.class)
    public void installInitialDynamicPlans() {
        if (dynamicPlanResolver != null) {
            dynamicPlanResolver.moduleAliases().forEach(this::rebuildDynamicPlan);
        }
    }

    private Map<String, ModuleExecutionPlan> compilePlans(List<StaticModuleDefinition> definitions) {
        Map<String, StaticModuleDefinition> definitionsByAlias = new LinkedHashMap<>();
        for (StaticModuleDefinition definition : definitions) {
            definitionsByAlias.put(definition.moduleAlias(), definition);
        }
        Map<String, ModuleExecutionPlan> plans = new LinkedHashMap<>();
        for (StaticModuleDefinition definition : definitions) {
            ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(definition,
                    targetAlias -> referencePickerMode(definitionsByAlias.get(targetAlias)));
            if (compilation == null || compilation.uiDescriptor() == null || compilation.readModel() == null) {
                throw new IllegalStateException("static module execution plan compilation produced incomplete facts: "
                        + definition.moduleAlias());
            }
            ModuleExecutionPlan plan = new ModuleExecutionPlan(
                    definition.moduleAlias(), versionKey(definition), compilation.uiDescriptor(), compilation.readModel(),
                    pageContextBindings(definition), queryDescriptor(definition),
                    QuerySchema.from(queryDescriptor(definition), definition.modelClass()),
                    mutationConstraints(definition), definition.actions(),
                    definition.supports(EntityCapability.DATA_SCOPE), responseWireFieldTypes(definition),
                    detailRelationWireFieldTypes(definition, compilation.uiDescriptor()));
            listQuerySummaryContributors.validate(plan);
            plans.put(definition.moduleAlias(), plan);
        }
        return Map.copyOf(plans);
    }

    /**
     * Output wire semantics are model facts, not a side effect of a particular page layout.
     * CrudWeb can return the complete entity while a module's list/detail omits individual
     * fields, so every declared main-entity field must survive into the execution plan.
     */
    private static Map<String, FieldValueType> responseWireFieldTypes(StaticModuleDefinition definition) {
        LinkedHashMap<String, FieldValueType> types = new LinkedHashMap<>();
        definition.entities().stream().findFirst().map(entity -> entity.fields()).orElse(List.of()).stream()
                .forEach(field -> addResponseWireType(types, field));
        return Map.copyOf(types);
    }

    /** Complete child model wire facts keyed by the compiled relation code. */
    private static Map<String, Map<String, FieldValueType>> detailRelationWireFieldTypes(
            StaticModuleDefinition definition, ResolvedModuleUiDescriptor descriptor) {
        Map<String, EntityDefinition> entities = definition.entities().stream()
                .collect(java.util.stream.Collectors.toMap(
                        EntityDefinition::alias,
                        java.util.function.Function.identity()));
        LinkedHashMap<String, Map<String, FieldValueType>> relationTypes = new LinkedHashMap<>();
        descriptor.detailRelations().stream()
                .filter(relation -> relation.embeddedField() != null
                        || relation.queryContract() != null && relation.queryContract().managedGateway())
                .forEach(relation -> {
                    var entity = entities.get(relation.targetEntityAlias());
                    if (entity == null) {
                        throw new IllegalStateException("detail relation target entity is not declared: "
                                + definition.moduleAlias() + "." + relation.code());
                    }
                    LinkedHashMap<String, FieldValueType> types = new LinkedHashMap<>();
                    entity.fields().forEach(field -> addResponseWireType(types, field));
                    relationTypes.put(relation.code(), Map.copyOf(types));
                });
        return Map.copyOf(relationTypes);
    }

    private static void addResponseWireType(Map<String, FieldValueType> target, FieldDefinition field) {
        FieldValueType type = FieldValueType.from(field.type());
        if (type != null) {
            target.put(field.fieldName(), type);
        }
    }

    /**
     * Rebuilds an installed dynamic plan after its runtime or published configuration changes.
     * Resolution is deliberately performed before mutating the directory: a compilation failure
     * must retain the last known-good plan rather than making the module disappear.
     */
    public void rebuildDynamicPlan(String moduleAlias) {
        if (moduleAlias == null || moduleAlias.isBlank() || dynamicPlanResolver == null) return;
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        Optional<ModuleExecutionPlan> resolved = dynamicPlanResolver.resolve(validAlias);
        replaceDynamicPlan(validAlias, resolved);
    }

    /** Atomically installs a prepared candidate, or marks a successfully published incomplete page non-executable. */
    public void replaceDynamicPlan(String moduleAlias, Optional<ModuleExecutionPlan> candidate) {
        String validAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        if (candidate == null || candidate.isEmpty()) {
            dynamicPlans.remove(validAlias);
            return;
        }
        ModuleExecutionPlan plan = candidate.get();
        if (!validAlias.equals(plan.moduleAlias())) {
            throw new IllegalArgumentException("dynamic execution plan alias does not match replacement target: " + validAlias);
        }
        validateCandidate(plan);
        dynamicPlans.compute(validAlias, (ignored, current) -> current != null && current.versionKey().equals(plan.versionKey())
                ? current : plan);
    }

    /** Validates a dynamic publication candidate while its transaction can still be rolled back. */
    public void validateCandidate(ModuleExecutionPlan candidate) {
        if (candidate == null) throw new IllegalArgumentException("dynamic execution plan candidate must not be null");
        listQuerySummaryContributors.validate(candidate);
    }

    public void invalidateDynamicPlan(String moduleAlias) {
        if (moduleAlias != null && !moduleAlias.isBlank()) {
            dynamicPlans.remove(moduleAlias.trim());
        }
    }

    @Override
    public void onRuntimeEvent(RuntimeEvent event) {
        if (event == null || event.moduleAlias() == null) return;
        if (event.eventType() == RuntimeEventType.MODULE_REFRESHED) {
            rebuildDynamicPlan(event.moduleAlias());
        }
    }

    private static ReferencePickerMode referencePickerMode(StaticModuleDefinition target) {
        if (target == null) {
            return ReferencePickerMode.AUTO;
        }
        return target.supports(EntityCapability.TREE) ? ReferencePickerMode.TREE : ReferencePickerMode.LIST;
    }

    private static List<PageContextBindingDefinition> pageContextBindings(StaticModuleDefinition definition) {
        ModuleUiDefinition uiDefinition = definition.uiDefinition();
        if (uiDefinition == null || uiDefinition.page() == null) {
            return definition.pageContextBindings();
        }
        PageNavigatorDefinition navigator = switch (uiDefinition.page()) {
            case FlatManagementPageDefinition page -> page.navigator();
            case ListDetailCardPageDefinition page -> page.navigator();
            case TreeManagementPageDefinition page -> page.navigator();
        };
        List<PageContextBindingDefinition> bindings = new java.util.ArrayList<>(
                navigator == null ? List.of() : navigator.contextBindings());
        definition.pageContextBindings().forEach(binding -> { if (!bindings.contains(binding)) bindings.add(binding); });
        return List.copyOf(bindings);
    }

    private static List<PageContextBindingDefinition> mutationConstraints(StaticModuleDefinition definition) {
        return pageContextBindings(definition).stream()
                .filter(binding -> binding.target() == PageContextTarget.MUTATION_CONSTRAINT)
                .toList();
    }

    /**
     * Produces the immutable query allow-list used by the runtime.  It preserves the service's
     * declared descriptor while adding declared read-projection fields once during compilation.
     */
    private static QueryDescriptor queryDescriptor(StaticModuleDefinition definition) {
        QueryDescriptor base = definition.queryDescriptor() == null
                ? queryDescriptorFromModelFacts(definition)
                : definition.queryDescriptor();
        QueryDescriptor.Builder builder = QueryDescriptor.builder(definition.moduleAlias());
        base.fields().forEach(builder::field);
        base.externalCriteria().forEach(criteria -> builder.externalCriteria(criteria.key(), criteria.valueType(),
                criteria.valueSource(), base.externalCriteriaResolver(criteria.key())));
        for (var sort : base.defaultSorts()) builder.defaultSort(sort);
        for (StaticModuleReadProjectionDefinition projection : definition.readProjections()) {
            QueryField field = projection.projectionType() == ModuleReadProjection.ProjectionType.EXISTS
                    ? QueryField.of(projection.outputField(), QueryValueType.BOOLEAN, QueryOperator.EQ)
                    : QueryDescriptors.field(definition.modelClass(), projection.outputField());
            if (!projection.filterable()) {
                field = new QueryField(field.fieldName(), field.title(), field.valueType(), Set.of(), null,
                        projection.sortable(), false, field.optionBinding(), field.selectionMode(),
                        field.optionTitleField());
            } else if (projection.sortable()) {
                field = field.withSortable();
            }
            builder.field(field);
        }
        return builder.build();
    }

    /** Supports programmatically supplied static definitions in contract tests and integrations. */
    private static QueryDescriptor queryDescriptorFromModelFacts(StaticModuleDefinition definition) {
        QueryDescriptor.Builder builder = QueryDescriptor.builder(definition.moduleAlias());
        if (definition.modelClass() == null) return builder.build();
        LinkedHashSet<String> fields = new LinkedHashSet<>();
        for (Class<?> type = definition.modelClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) fields.add(field.getName());
        }
        definition.entities().stream().findFirst().ifPresent(entity ->
                entity.fields().forEach(field -> fields.add(field.fieldName())));
        fields.forEach(field -> builder.field(QueryDescriptors.field(definition.modelClass(), field)));
        return builder.build();
    }

    private static String versionKey(StaticModuleDefinition definition) {
        return "static-" + Integer.toUnsignedString(definition.hashCode(), 36);
    }
}
