package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.spring.platform.application.PlatformStaticApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.StaticModuleRegistrationSource;
import net.ximatai.muyun.spring.platform.module.StaticReferenceCompiler;
import net.ximatai.muyun.spring.platform.module.StaticReferenceDefinition;
import net.ximatai.muyun.spring.platform.module.StaticServiceAbilityCompiler;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityRegistry;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.ability.child.StaticChildResolver;
import net.ximatai.muyun.spring.ability.capability.StaticCapabilityDeclarationCatalog;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;
import net.ximatai.muyun.spring.platform.module.StaticModuleServiceDeclaration;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.spring.web.EnableWeb;
import net.ximatai.muyun.spring.web.QueryViewWeb;
import net.ximatai.muyun.spring.web.ReferenceWeb;
import net.ximatai.muyun.spring.web.NavigatorReferenceWeb;
import net.ximatai.muyun.spring.web.NavigatorReferenceTreeWeb;
import net.ximatai.muyun.spring.web.ScopedWeb;
import net.ximatai.muyun.spring.web.SortWeb;
import net.ximatai.muyun.spring.web.TreeWeb;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjectionContributor;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.ability.reference.StaticReferenceResolver;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.StaticEntityDefinitionCompiler;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.ResolvableType;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StaticModuleDefinitionScanner implements StaticModuleRegistrationSource {
    private final ApplicationContext applicationContext;
    private final StaticCapabilityRegistry staticCapabilityRegistry;

    public StaticModuleDefinitionScanner(ApplicationContext applicationContext) {
        this(applicationContext, CapabilityModuleRegistry.defaultRegistry());
    }

    /** Allows scanner contracts to prove a newly registered static facet needs no scanner branch. */
    public StaticModuleDefinitionScanner(ApplicationContext applicationContext,
                                         StaticCapabilityRegistry staticCapabilityRegistry) {
        this.applicationContext = applicationContext;
        this.staticCapabilityRegistry = staticCapabilityRegistry;
    }

    public List<StaticModuleDefinition> scan() {
        validateActionEndpointOrigins();
        LinkedHashMap<String, StaticModuleDefinition> definitions = new LinkedHashMap<>();
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticModule.class)) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticModule module = AnnotationUtils.findAnnotation(beanClass, PlatformStaticModule.class);
            if (module == null) {
                continue;
            }
            StaticModuleDefinition definition = definition(bean, beanClass, module);
            definitions.put(definition.moduleAlias(), definition);
        }
        addActionContributions(definitions);
        addActionDeclarations(definitions);
        addActionScopes(definitions);
        validateUiCompilation(definitions.values());
        return List.copyOf(definitions.values());
    }

    /**
     * A static module has all model and UI declaration facts at scan time. Compile it here so a
     * mistyped field or invalid binding prevents the module from entering the executable catalog,
     * rather than surfacing only when a page bootstrap is requested.
     */
    private void validateUiCompilation(Iterable<StaticModuleDefinition> definitions) {
        for (StaticModuleDefinition definition : definitions) {
            if (definition.uiDefinition() != null) {
                ModuleUiDescriptorCompiler.compileModule(definition);
            }
        }
    }

    private void validateActionEndpointOrigins() {
        for (String beanName : actionEndpointOriginBeanNames()) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            List<String> origins = new ArrayList<>();
            if (AnnotationUtils.findAnnotation(beanClass, PlatformStaticModule.class) != null) {
                origins.add("@PlatformStaticModule");
            }
            if (AnnotationUtils.findAnnotation(beanClass, PlatformStaticActionContribution.class) != null) {
                origins.add("@PlatformStaticActionContribution");
            }
            if (AnnotationUtils.findAnnotation(beanClass, PlatformStaticActionDeclaration.class) != null) {
                origins.add("@PlatformStaticActionDeclaration");
            }
            if (AnnotationUtils.findAnnotation(beanClass, PlatformStaticActionScope.class) != null) {
                origins.add("@PlatformStaticActionScope");
            }
            if (AnnotationUtils.findAnnotation(beanClass, PlatformStaticWebProjection.class) != null) {
                origins.add("@PlatformStaticWebProjection");
            }
            if (origins.size() > 1) {
                throw new IllegalStateException("static action endpoint origin annotations are mutually exclusive: "
                        + beanClass.getName() + " -> " + origins);
            }
        }
    }

    private Set<String> actionEndpointOriginBeanNames() {
        Set<String> beanNames = new LinkedHashSet<>();
        beanNames.addAll(List.of(applicationContext.getBeanNamesForAnnotation(PlatformStaticModule.class)));
        beanNames.addAll(List.of(applicationContext.getBeanNamesForAnnotation(PlatformStaticActionContribution.class)));
        beanNames.addAll(List.of(applicationContext.getBeanNamesForAnnotation(PlatformStaticActionDeclaration.class)));
        beanNames.addAll(List.of(applicationContext.getBeanNamesForAnnotation(PlatformStaticActionScope.class)));
        beanNames.addAll(List.of(applicationContext.getBeanNamesForAnnotation(PlatformStaticWebProjection.class)));
        return beanNames;
    }

    @Override
    public List<StaticModuleDefinition> definitions() {
        return scan();
    }

    private StaticModuleDefinition definition(Object bean, Class<?> beanClass, PlatformStaticModule module) {
        ApplicationDeclaration application = application(module);
        validateModuleAlias(module, application);
        validateScopeAlias(bean, beanClass, module);
        List<RelationProjectionJoinDefinition> projectionJoins = List.of();
        java.util.Set<EntityCapability> capabilities = capabilities(bean, module);
        Class<?> modelClass = modelClass(bean);
        List<EntityDefinition> entities = entities(bean, module, projectionJoins);
        return StaticModuleDefinition.builder(application.alias(), module.alias(), module.title())
                .parentModuleAlias(module.parent().isBlank() ? null : module.parent())
                .entry(entryType(module), module.route(), module.externalUrl())
                .capabilities(capabilities)
                .actions(actions(bean, beanClass, capabilities))
                .entities(entities)
                .uiDefinition(uiDefinition(bean, module))
                .pageContextBindings(pageContextBindings(bean))
                .references(references(bean))
                .readProjections(readProjections(bean, module.alias()))
                .modelClass(modelClass)
                .sortPartitionFields(sortPartitionFields(bean))
                .entityModelClasses(entityModelClasses(bean, modelClass, entities))
                .projectionJoins(projectionJoins)
                .queryDescriptor(queryDescriptor(bean, module.alias()))
                .openApiAvailable(AnnotationUtils.findAnnotation(beanClass, StaticModuleOpenApi.class) != null)
                .legacyReadProjectionCompatibility(bean instanceof LegacyStaticReadProjectionCompatibility)
                .build();
    }

    private List<String> sortPartitionFields(Object bean) {
        Object service = service(bean);
        if (!(service instanceof SortAbility<?> sortAbility)) {
            return List.of();
        }
        List<String> fields = sortAbility.sortPartitionFields();
        return fields == null ? List.of() : List.copyOf(fields);
    }

    /**
     * Every entity definition exposed to a static UI descriptor must retain its Java model facts.
     * In particular, aggregate child editors need their own reference and option declarations.
     */
    private Map<String, Class<?>> entityModelClasses(Object bean, Class<?> modelClass,
                                                      List<EntityDefinition> entities) {
        if (entities.isEmpty() || modelClass == null) {
            return Map.of();
        }
        LinkedHashMap<String, Class<?>> models = new LinkedHashMap<>();
        models.put(entities.getFirst().alias(), modelClass);
        if (service(bean) instanceof ChildrenAbility<?> childrenAbility
                && !childrenAbility.usesAutomaticChildRelations()) {
            for (StaticChildResolver.ChildRule child : StaticChildResolver.rules(modelClass)) {
                models.put(child.plan().relationCode(), child.childModel());
            }
        }
        return Map.copyOf(models);
    }

    private java.util.Set<EntityCapability> capabilities(Object bean, PlatformStaticModule module) {
        java.util.EnumSet<EntityCapability> capabilities = java.util.EnumSet.noneOf(EntityCapability.class);
        java.util.Set<EntityCapability> serviceCapabilities =
                StaticServiceAbilityCompiler.compile(service(bean), staticCapabilityRegistry);
        for (EntityCapability declared : module.capabilities()) {
            if (StaticCapabilityDeclarationCatalog.isServiceOnly(declared, staticCapabilityRegistry)) {
                throw new IllegalStateException("@PlatformStaticModule must not redeclare service ability: "
                        + module.alias() + "." + declared.name());
            }
            capabilities.add(declared);
        }
        capabilities.addAll(serviceCapabilities);
        return java.util.Set.copyOf(capabilities);
    }

    private Class<?> modelClass(Object bean) {
        Object service = service(bean);
        if (service instanceof CrudAbility<?> ability) {
            Class<?> modelClass = ability.modelClass();
            if (modelClass != null && modelClass != Object.class) {
                return modelClass;
            }
            Class<?> resolvedModelClass = ResolvableType.forClass(AopUtils.getTargetClass(service))
                    .as(CrudAbility.class)
                    .getGeneric(0)
                    .resolve();
            return resolvedModelClass != null && resolvedModelClass.isAnnotationPresent(Table.class)
                    ? resolvedModelClass
                    : null;
        }
        return null;
    }

    private List<StaticReferenceDefinition> references(Object bean) {
        Class<?> modelClass = modelClass(bean);
        if (modelClass == null || modelClass == Object.class) {
            return List.of();
        }
        return StaticReferenceCompiler.compile(modelClass);
    }

    private static List<PageContextBindingDefinition> pageContextBindings(Object bean) {
        return bean instanceof CrudWeb<?, ?> controller ? controller.pageSelectionContextBindings() : List.of();
    }

    private List<StaticModuleReadProjectionDefinition> readProjections(Object bean, String moduleAlias) {
        Object service = service(bean);
        if (!(service instanceof ModuleReadProjectionContributor contributor)) {
            return List.of();
        }
        List<net.ximatai.muyun.spring.ability.reference.ModuleReadProjection> projections =
                contributor.moduleReadProjections();
        if (projections == null || projections.isEmpty()) {
            return List.of();
        }
        return projections.stream()
                .map(projection -> new StaticModuleReadProjectionDefinition(
                        declaredPath(modelClass(bean), moduleAlias, projection),
                        projection.referencePath(),
                        projection.outputField(),
                        projection.projectionType(),
                        projection.filterable(),
                        projection.sortable()
                ))
                .toList();
    }

    private String declaredPath(Class<?> modelClass,
                                String moduleAlias,
                                net.ximatai.muyun.spring.ability.reference.ModuleReadProjection projection) {
        if (projection.path() != null || projection.referencePath() != null) return projection.path();
        if (modelClass == null) {
            throw new IllegalArgumentException("declared read projection requires a static entity model: "
                    + projection.outputField());
        }
        List<ReferencePlan> plans = StaticReferenceResolver.plans(modelClass).stream()
                .filter(plan -> plan.cardinality() == net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE)
                .filter(plan -> plan.projections().stream().anyMatch(item -> item.outputField().equals(projection.outputField())))
                .toList();
        if (plans.size() != 1) {
            throw new IllegalArgumentException("declared read projection requires exactly one direct @ReferenceLoad: "
                    + moduleAlias + "." + projection.outputField());
        }
        ReferencePlan plan = plans.getFirst();
        ReferenceProjection output = plan.projections().stream()
                .filter(item -> item.outputField().equals(projection.outputField())).findFirst().orElseThrow();
        String referenceCode = StaticReferenceCompiler.compile(modelClass).stream()
                .filter(reference -> reference.sourceField().equals(plan.sourceField()))
                .map(StaticReferenceDefinition::code)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "declared read projection source is not a static reference: "
                                + moduleAlias + "." + plan.sourceField()));
        return referenceCode + "." + output.targetField();
    }

    private ModuleUiDefinition uiDefinition(Object bean, PlatformStaticModule module) {
        if (!(bean instanceof StaticModuleUiContributor contributor)) {
            return null;
        }
        ModuleUiDefinition uiDefinition = contributor.moduleUiDefinition();
        if (uiDefinition == null) {
            return null;
        }
        if (!module.alias().equals(uiDefinition.moduleAlias())) {
            throw new IllegalStateException("static module UI definition alias must match module alias: "
                    + module.alias() + " != " + uiDefinition.moduleAlias());
        }
        return uiDefinition;
    }

    private ModuleEntryType entryType(PlatformStaticModule module) {
        boolean hasRoute = !module.route().isBlank();
        boolean hasExternalUrl = !module.externalUrl().isBlank();
        if (hasRoute && hasExternalUrl) {
            throw new IllegalStateException("@PlatformStaticModule cannot declare both route and externalUrl: "
                    + module.alias());
        }
        if (hasRoute) {
            return ModuleEntryType.ROUTE;
        }
        if (hasExternalUrl) {
            return ModuleEntryType.LINK;
        }
        return ModuleEntryType.MODULE;
    }

    private List<EntityDefinition> entities(Object bean,
                                            PlatformStaticModule module,
                                            List<RelationProjectionJoinDefinition> projectionJoins) {
        Class<?> modelClass = modelClass(bean);
        if (modelClass == null || modelClass == Object.class) {
            return List.of();
        }
        LinkedHashMap<String, EntityDefinition> entities = new LinkedHashMap<>();
        EntityDefinition mainEntity = new StaticEntityDefinitionCompiler().compile(
                entityAlias(module),
                module.title(),
                modelClass
        );
        entities.put(mainEntity.alias(), mainEntity);
        for (RelationProjectionJoinDefinition join : projectionJoins) {
            EntityDefinition target = join.targetEntity();
            if (entities.containsKey(target.alias())) {
                throw new IllegalStateException("static projection join entity conflicts with module entity: "
                        + module.alias() + "." + target.alias());
            }
            entities.put(target.alias(), target);
        }
        if (service(bean) instanceof ChildrenAbility<?> childrenAbility
                && !childrenAbility.usesAutomaticChildRelations()) {
            for (StaticChildResolver.ChildRule child : StaticChildResolver.rules(modelClass)) {
                EntityDefinition target = new StaticEntityDefinitionCompiler().compile(
                        child.plan().relationCode(), child.childModel().getSimpleName(), child.childModel());
                if (entities.putIfAbsent(target.alias(), target) != null) {
                    throw new IllegalStateException("explicit aggregate child entity conflicts with module entity: "
                            + module.alias() + "." + target.alias());
                }
            }
        }
        return List.copyOf(entities.values());
    }

    private String entityAlias(PlatformStaticModule module) {
        String moduleName = module.alias().substring(application(module).alias().length() + 1);
        int lastSeparator = moduleName.lastIndexOf('.');
        if (lastSeparator < 0) {
            return moduleName;
        }
        return moduleName.substring(lastSeparator + 1);
    }

    private Object service(Object bean) {
        if (bean instanceof ScopedWeb<?> scopedWeb) {
            return scopedWeb.service();
        }
        if (bean instanceof StaticModuleServiceDeclaration declaration) {
            return declaration.staticModuleService();
        }
        return null;
    }

    /** Captures the service query contract while static modules are compiled, never per request. */
    private QueryDescriptor queryDescriptor(Object bean, String moduleAlias) {
        Object service = service(bean);
        if (service instanceof QueryAbility<?> queryAbility) {
            return queryAbility.queryDescriptor();
        }
        return QueryDescriptor.builder(moduleAlias).build();
    }

    private ApplicationDeclaration application(PlatformStaticModule module) {
        PlatformStaticApplication application = AnnotationUtils.findAnnotation(
                module.application(), PlatformStaticApplication.class);
        if (application == null) {
            throw new IllegalStateException("@PlatformStaticModule application must declare @PlatformStaticApplication: "
                    + module.application().getName());
        }
        return new ApplicationDeclaration(application.alias());
    }

    private void validateModuleAlias(PlatformStaticModule module, ApplicationDeclaration application) {
        if (!module.alias().startsWith(application.alias() + ".")) {
            throw new IllegalStateException("@PlatformStaticModule alias must belong to application: "
                    + module.alias() + " is not under " + application.alias());
        }
    }

    private record ApplicationDeclaration(String alias) {
    }

    private void validateScopeAlias(Object bean, Class<?> beanClass, PlatformStaticModule module) {
        Object service = service(bean);
        if (service instanceof CrudAbility<?> ability
                && ability.getModuleAlias() != null
                && !ability.getModuleAlias().isBlank()
                && !module.alias().equals(ability.getModuleAlias())) {
            throw new IllegalStateException("@PlatformStaticModule alias must match service module alias: "
                    + module.alias() + " != " + ability.getModuleAlias());
        }
        org.springframework.web.bind.annotation.RequestMapping mapping =
                AnnotationUtils.findAnnotation(beanClass, org.springframework.web.bind.annotation.RequestMapping.class);
        List<String> paths = mapping == null ? List.of() : java.util.stream.Stream
                .concat(java.util.Arrays.stream(mapping.value()), java.util.Arrays.stream(mapping.path()))
                .filter(path -> path != null && !path.isBlank())
                .distinct()
                .toList();
        PlatformStaticWebScope webScope = AnnotationUtils.findAnnotation(beanClass, PlatformStaticWebScope.class);
        if (webScope != null && webScope.value() == PlatformStaticWebScope.Scope.CUSTOM) {
            if (paths.isEmpty()) {
                throw new IllegalStateException("@PlatformStaticWebScope(CUSTOM) requires @RequestMapping: "
                        + module.alias());
            }
            return;
        }
        if (paths.isEmpty()) {
            return;
        }
        String expectedPath = "/" + module.alias();
        if (!paths.equals(List.of(expectedPath))) {
            throw new IllegalStateException("@PlatformStaticModule module-alias web scope requires @RequestMapping: "
                    + expectedPath + ", actual " + paths);
        }
    }

    private List<StaticModuleActionDefinition> actions(Object bean,
                                                       Class<?> beanClass,
                                                       java.util.Set<EntityCapability> capabilities) {
        LinkedHashMap<String, StaticModuleActionDefinition> actions = new LinkedHashMap<>();
        addMenuAction(actions, beanClass);
        addStandardActions(actions, bean, beanClass);
        addSourceReferenceAction(actions, bean);
        addWorkflowActions(actions, capabilities);
        java.util.Set<PlatformAction> disabledActions = StaticServiceAbilityCompiler.disabledActions(service(bean));
        ReflectionUtils.doWithMethods(beanClass,
                method -> addAnnotatedAction(actions, method, disabledActions));
        return List.copyOf(actions.values());
    }

    /**
     * Static {@code @ReferenceTo} fields are resolved through the platform-owned source-module
     * endpoint.  Publishing that endpoint is therefore a model fact, not an implementation detail
     * of a particular {@link ReferenceWeb} controller.
     */
    private void addSourceReferenceAction(Map<String, StaticModuleActionDefinition> actions, Object bean) {
        Class<?> modelClass = modelClass(bean);
        if (modelClass == null || StaticReferenceResolver.plans(modelClass).isEmpty()) {
            return;
        }
        java.util.Set<PlatformAction> disabledActions = StaticServiceAbilityCompiler.disabledActions(service(bean));
        addPlatformUnlessDisabled(actions, PlatformAction.REFERENCE, disabledActions);
    }

    private void addActionContributions(LinkedHashMap<String, StaticModuleDefinition> definitions) {
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticActionContribution.class)) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticActionContribution contribution =
                    AnnotationUtils.findAnnotation(beanClass, PlatformStaticActionContribution.class);
            if (contribution == null) {
                continue;
            }
            String targetModule = PlatformStaticActionContributionSupport.targetModule(contribution);
            StaticModuleDefinition target = definitions.get(targetModule);
            if (target == null) {
                throw new IllegalStateException("@PlatformStaticActionContribution target module is not scanned: "
                        + targetModule + " <- " + beanClass.getName());
            }
            LinkedHashMap<String, StaticModuleActionDefinition> merged = new LinkedHashMap<>();
            target.actions().forEach(action -> merged.put(action.actionCode(), action));
            contributionActions(bean, beanClass, contribution)
                    .forEach(action -> mergeDeclaredAction("@PlatformStaticActionContribution", target.moduleAlias(),
                            beanClass, merged, action));
            List<EntityDefinition> entities = mergeContributionEntities(
                    target.moduleAlias(), beanClass, target.entities(), contributionEntities(bean, contribution));
            LinkedHashMap<String, Class<?>> entityModelClasses = new LinkedHashMap<>(target.entityModelClasses());
            Object contributionService = service(bean);
            if (contributionService instanceof CrudAbility<?> ability
                    && ability.modelClass() != null && ability.modelClass() != Object.class) {
                entityModelClasses.put(contribution.resource(), ability.modelClass());
            }
            ModuleUiDefinition uiDefinition = mergeContributionUiDefinition(
                    target.moduleAlias(),
                    beanClass,
                    target.uiDefinition(),
                    contributionUiDefinition(bean, targetModule)
            );
            definitions.put(targetModule, target.toBuilder()
                    .actions(List.copyOf(merged.values()))
                    .entities(entities)
                    .entityModelClasses(entityModelClasses)
                    .uiDefinition(uiDefinition)
                    .build());
        }
    }

    private void addActionDeclarations(LinkedHashMap<String, StaticModuleDefinition> definitions) {
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticActionDeclaration.class)) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticActionDeclaration declaration =
                    AnnotationUtils.findAnnotation(beanClass, PlatformStaticActionDeclaration.class);
            if (declaration == null) {
                continue;
            }
            String targetModule = PlatformNameRules.requireModuleAlias(declaration.module());
            StaticModuleDefinition target = definitions.get(targetModule);
            if (target == null) {
                throw new IllegalStateException("@PlatformStaticActionDeclaration target module is not scanned: "
                        + targetModule + " <- " + beanClass.getName());
            }
            LinkedHashMap<String, StaticModuleActionDefinition> merged = new LinkedHashMap<>();
            target.actions().forEach(action -> merged.put(action.actionCode(), action));
            LinkedHashMap<String, StaticModuleActionDefinition> declaredActions = new LinkedHashMap<>();
            ReflectionUtils.doWithMethods(beanClass,
                    method -> addAnnotatedAction(declaredActions, method, java.util.Set.of()));
            declaredActions.values().forEach(action -> mergeDeclaredAction("@PlatformStaticActionDeclaration",
                    target.moduleAlias(), beanClass, merged, action));
            definitions.put(target.moduleAlias(), target.toBuilder().actions(List.copyOf(merged.values())).build());
        }
    }

    private void addActionScopes(LinkedHashMap<String, StaticModuleDefinition> definitions) {
        for (String beanName : applicationContext.getBeanNamesForAnnotation(PlatformStaticActionScope.class)) {
            Object bean = applicationContext.getBean(beanName);
            Class<?> beanClass = AopUtils.getTargetClass(bean);
            PlatformStaticActionScope scope = AnnotationUtils.findAnnotation(beanClass, PlatformStaticActionScope.class);
            if (scope == null) {
                continue;
            }
            StaticModuleDefinition target = definitions.get(scope.module());
            if (target == null) {
                throw new IllegalStateException("@PlatformStaticActionScope target module is not scanned: "
                        + scope.module() + " <- " + beanClass.getName());
            }
            LinkedHashMap<String, StaticModuleActionDefinition> targetActions = new LinkedHashMap<>();
            target.actions().forEach(action -> targetActions.put(action.actionCode(), action));
            LinkedHashMap<String, StaticModuleActionDefinition> scopedActions = new LinkedHashMap<>();
            ReflectionUtils.doWithMethods(beanClass,
                    method -> addAnnotatedAction(scopedActions, method, java.util.Set.of()));
            scopedActions.values().forEach(action -> validateScopedAction(target.moduleAlias(), beanClass,
                    targetActions, action));
        }
    }

    private List<EntityDefinition> contributionEntities(Object bean,
                                                        PlatformStaticActionContribution contribution) {
        Object service = service(bean);
        if (!(service instanceof CrudAbility<?> ability)) {
            return List.of();
        }
        Class<?> modelClass = ability.modelClass();
        if (modelClass == null || modelClass == Object.class) {
            return List.of();
        }
        return List.of(new StaticEntityDefinitionCompiler().compile(
                contribution.resource(),
                contribution.resourceTitle(),
                modelClass
        ));
    }

    private List<EntityDefinition> mergeContributionEntities(String targetModule,
                                                             Class<?> contributor,
                                                             List<EntityDefinition> targetEntities,
                                                             List<EntityDefinition> contributionEntities) {
        LinkedHashMap<String, EntityDefinition> merged = new LinkedHashMap<>();
        for (EntityDefinition entity : targetEntities) {
            merged.put(entity.alias(), entity);
        }
        for (EntityDefinition entity : contributionEntities) {
            if (merged.containsKey(entity.alias())) {
                throw new IllegalStateException("@PlatformStaticActionContribution entity conflicts with target module: "
                        + targetModule + "." + entity.alias() + " <- " + contributor.getName());
            }
            merged.put(entity.alias(), entity);
        }
        return List.copyOf(merged.values());
    }

    private ModuleUiDefinition contributionUiDefinition(Object bean, String targetModule) {
        if (!(bean instanceof StaticModuleUiContributor contributor)) {
            return null;
        }
        ModuleUiDefinition uiDefinition = contributor.moduleUiDefinition();
        if (uiDefinition == null) {
            return null;
        }
        if (!targetModule.equals(uiDefinition.moduleAlias())) {
            throw new IllegalStateException("@PlatformStaticActionContribution UI definition alias must match target module: "
                    + targetModule + " != " + uiDefinition.moduleAlias());
        }
        return uiDefinition;
    }

    private ModuleUiDefinition mergeContributionUiDefinition(String targetModule,
                                                             Class<?> contributor,
                                                             ModuleUiDefinition targetUiDefinition,
                                                             ModuleUiDefinition contributionUiDefinition) {
        if (contributionUiDefinition == null) {
            return targetUiDefinition;
        }
        if (targetUiDefinition != null
                && targetUiDefinition.page() != null
                && contributionUiDefinition.page() != null) {
            throw new IllegalStateException("@PlatformStaticActionContribution page conflicts with target module: "
                    + targetModule + " <- " + contributor.getName());
        }
        LinkedHashMap<String, UiActionDefinition> actions = new LinkedHashMap<>();
        if (targetUiDefinition != null) {
            targetUiDefinition.actions().forEach(action -> actions.put(action.actionCode(), action));
        }
        for (UiActionDefinition action : contributionUiDefinition.actions()) {
            if (actions.containsKey(action.actionCode())) {
                throw new IllegalStateException("@PlatformStaticActionContribution UI action conflicts with target module: "
                        + targetModule + "." + action.actionCode() + " <- " + contributor.getName());
            }
            actions.put(action.actionCode(), action);
        }
        List<PageDetailEditorContribution> editorContributions = new ArrayList<>();
        if (targetUiDefinition != null) editorContributions.addAll(targetUiDefinition.editorContributions());
        for (PageDetailEditorContribution contribution : contributionUiDefinition.editorContributions()) {
            if (editorContributions.stream().anyMatch(existing -> existing.resource().equals(contribution.resource()))) {
                throw new IllegalStateException("@PlatformStaticActionContribution detail editor conflicts with target module: "
                        + targetModule + "." + contribution.resource() + " <- " + contributor.getName());
            }
            editorContributions.add(contribution);
        }
        List<PageDetailRelationDefinition> detailRelations = new ArrayList<>();
        if (targetUiDefinition != null) detailRelations.addAll(targetUiDefinition.detailRelations());
        for (PageDetailRelationDefinition relation : contributionUiDefinition.detailRelations()) {
            if (detailRelations.stream().anyMatch(existing -> existing.code().equals(relation.code()))) {
                throw new IllegalStateException("@PlatformStaticActionContribution detail relation conflicts with target module: "
                        + targetModule + "." + relation.code() + " <- " + contributor.getName());
            }
            detailRelations.add(relation);
        }
        ViewDefinition defaultEditor = targetUiDefinition == null ? null : targetUiDefinition.defaultEditor();
        if (contributionUiDefinition.defaultEditor() != null) {
            if (defaultEditor != null) {
                throw new IllegalStateException("@PlatformStaticActionContribution default editor conflicts with target module: "
                        + targetModule + " <- " + contributor.getName());
            }
            defaultEditor = contributionUiDefinition.defaultEditor();
        }
        List<EditorSurfaceDefinition> editorSurfaces = new ArrayList<>();
        if (targetUiDefinition != null) editorSurfaces.addAll(targetUiDefinition.editorSurfaces());
        for (EditorSurfaceDefinition surface : contributionUiDefinition.editorSurfaces()) {
            if (editorSurfaces.stream().anyMatch(existing -> existing.key().equals(surface.key()))) {
                throw new IllegalStateException("@PlatformStaticActionContribution editor surface conflicts with target module: "
                        + targetModule + "." + surface.key() + " <- " + contributor.getName());
            }
            editorSurfaces.add(surface);
        }
        return new ModuleUiDefinition(targetModule, List.copyOf(actions.values()),
                targetUiDefinition != null && targetUiDefinition.page() != null
                        ? targetUiDefinition.page() : contributionUiDefinition.page(), defaultEditor,
                editorSurfaces, editorContributions, detailRelations);
    }

    private void mergeDeclaredAction(String sourceAnnotation,
                                     String targetModule,
                                     Class<?> contributor,
                                     LinkedHashMap<String, StaticModuleActionDefinition> actions,
                                     StaticModuleActionDefinition action) {
        if (actions.containsKey(action.actionCode())) {
            throw new IllegalStateException(sourceAnnotation + " action conflicts with target module: "
                    + targetModule + "." + action.actionCode() + " <- " + contributor.getName());
        }
        actions.put(action.actionCode(), action);
    }

    private void validateScopedAction(String targetModule,
                                      Class<?> scope,
                                      Map<String, StaticModuleActionDefinition> targetActions,
                                      StaticModuleActionDefinition action) {
        StaticModuleActionDefinition targetAction = targetActions.get(action.actionCode());
        if (targetAction == null) {
            throw new IllegalStateException("@PlatformStaticActionScope action is not declared by target module: "
                    + targetModule + "." + action.actionCode() + " <- " + scope.getName());
        }
        if (!targetAction.equals(action)) {
            throw new IllegalStateException("@PlatformStaticActionScope action conflicts with target module: "
                    + targetModule + "." + action.actionCode() + " <- " + scope.getName());
        }
    }

    private List<StaticModuleActionDefinition> contributionActions(Object bean,
                                                                   Class<?> beanClass,
                                                                   PlatformStaticActionContribution contribution) {
        LinkedHashMap<String, StaticModuleActionDefinition> actions = new LinkedHashMap<>();
        addContributionStandardActions(actions, bean, beanClass, contribution);
        java.util.Set<PlatformAction> disabledActions = StaticServiceAbilityCompiler.disabledActions(service(bean));
        ReflectionUtils.doWithMethods(beanClass, method -> addContributionAnnotatedAction(actions, method,
                contribution, disabledActions));
        return List.copyOf(actions.values());
    }

    private void addMenuAction(Map<String, StaticModuleActionDefinition> actions, Class<?> beanClass) {
        if (AnnotationUtils.findAnnotation(beanClass, PlatformMenu.class) != null) {
            addPlatform(actions, PlatformAction.MENU);
        }
    }

    private void addStandardActions(Map<String, StaticModuleActionDefinition> actions,
                                    Object bean,
                                    Class<?> beanClass) {
        Object service = service(bean);
        java.util.Set<PlatformAction> disabledActions = StaticServiceAbilityCompiler.disabledActions(service);
        if (CrudWeb.class.isAssignableFrom(beanClass)) {
            addPlatformUnlessDisabled(actions, PlatformAction.MENU, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.CREATE, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.VIEW, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.UPDATE, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.DELETE, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.QUERY, disabledActions);
        } else if (QueryViewWeb.class.isAssignableFrom(beanClass)) {
            addPlatformUnlessDisabled(actions, PlatformAction.MENU, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.VIEW, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.QUERY, disabledActions);
        } else if (bean instanceof StaticModuleServiceDeclaration && service instanceof CrudAbility<?>) {
            addPlatformUnlessDisabled(actions, PlatformAction.CREATE, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.VIEW, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.UPDATE, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.DELETE, disabledActions);
            addPlatformUnlessDisabled(actions, PlatformAction.QUERY, disabledActions);
        }
        StaticServiceAbilityCompiler.standardActions(service, staticCapabilityRegistry)
                .forEach(action -> addPlatform(actions, action));
        if (service == null) {
            addUnwiredLegacyAbilityActions(actions, beanClass);
        }
        if (ReferenceWeb.class.isAssignableFrom(beanClass)
                || NavigatorReferenceWeb.class.isAssignableFrom(beanClass)
                || NavigatorReferenceTreeWeb.class.isAssignableFrom(beanClass)) {
            addPlatformUnlessDisabled(actions, PlatformAction.REFERENCE, disabledActions);
        }
    }

    private void addPlatformUnlessDisabled(Map<String, StaticModuleActionDefinition> actions,
                                           PlatformAction action,
                                           java.util.Set<PlatformAction> disabledActions) {
        if (!disabledActions.contains(action)) {
            addPlatform(actions, action);
        }
    }

    private void addUnwiredLegacyAbilityActions(Map<String, StaticModuleActionDefinition> actions,
                                                Class<?> beanClass) {
        legacyCapabilityActions(beanClass).forEach(action -> addPlatform(actions, action));
    }

    private void addContributionStandardActions(Map<String, StaticModuleActionDefinition> actions,
                                                Object bean,
                                                Class<?> beanClass,
                                                PlatformStaticActionContribution contribution) {
        Object service = service(bean);
        java.util.Set<PlatformAction> disabledActions = StaticServiceAbilityCompiler.disabledActions(service);
        if (CrudWeb.class.isAssignableFrom(beanClass)) {
            addContributionUnlessDisabled(actions, contribution, PlatformAction.CREATE, disabledActions);
            addContributionUnlessDisabled(actions, contribution, PlatformAction.VIEW, disabledActions);
            addContributionUnlessDisabled(actions, contribution, PlatformAction.UPDATE, disabledActions);
            addContributionUnlessDisabled(actions, contribution, PlatformAction.DELETE, disabledActions);
            addContributionUnlessDisabled(actions, contribution, PlatformAction.QUERY, disabledActions);
        } else if (QueryViewWeb.class.isAssignableFrom(beanClass)) {
            addContributionUnlessDisabled(actions, contribution, PlatformAction.VIEW, disabledActions);
            addContributionUnlessDisabled(actions, contribution, PlatformAction.QUERY, disabledActions);
        }
        StaticServiceAbilityCompiler.standardActions(service, staticCapabilityRegistry)
                .forEach(action -> addContributionPlatform(actions, contribution, action));
        if (service == null) {
            legacyCapabilityActions(beanClass)
                    .forEach(action -> addContributionPlatform(actions, contribution, action));
        }
        if (ReferenceWeb.class.isAssignableFrom(beanClass)
                || NavigatorReferenceWeb.class.isAssignableFrom(beanClass)
                || NavigatorReferenceTreeWeb.class.isAssignableFrom(beanClass)) {
            addContributionPlatform(actions, contribution, PlatformAction.REFERENCE);
        }
    }

    private void addContributionUnlessDisabled(Map<String, StaticModuleActionDefinition> actions,
                                               PlatformStaticActionContribution contribution,
                                               PlatformAction action,
                                               java.util.Set<PlatformAction> disabledActions) {
        if (!disabledActions.contains(action)) {
            addContributionPlatform(actions, contribution, action);
        }
    }

    /** Legacy Web markers only select capabilities; action facts stay owned by their registry facets. */
    private java.util.List<PlatformAction> legacyCapabilityActions(Class<?> beanClass) {
        java.util.LinkedHashSet<EntityCapability> capabilities = new java.util.LinkedHashSet<>();
        if (TreeWeb.class.isAssignableFrom(beanClass)) {
            capabilities.add(EntityCapability.TREE);
            capabilities.add(EntityCapability.SORT);
        } else if (SortWeb.class.isAssignableFrom(beanClass)) {
            capabilities.add(EntityCapability.SORT);
        }
        if (EnableWeb.class.isAssignableFrom(beanClass)) capabilities.add(EntityCapability.ENABLE);
        if (RecycleBinWeb.class.isAssignableFrom(beanClass) || RecycleBinPurgeWeb.class.isAssignableFrom(beanClass)) {
            capabilities.add(EntityCapability.RECYCLE_BIN);
        }
        boolean allowPurge = RecycleBinPurgeWeb.class.isAssignableFrom(beanClass);
        boolean queryRecycleBin = RecycleBinWeb.class.isAssignableFrom(beanClass);
        return capabilities.stream()
                .flatMap(capability -> CapabilityModuleRegistry.defaultRegistry().find(capability).stream())
                .flatMap(module -> module.actionContribution().standardActions().stream())
                .filter(action -> action != PlatformAction.RECYCLE_BIN_PURGE || allowPurge)
                .filter(action -> action != PlatformAction.RECYCLE_BIN_QUERY || queryRecycleBin)
                .toList();
    }

    private void addWorkflowActions(Map<String, StaticModuleActionDefinition> actions,
                                    java.util.Set<EntityCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return;
        }
        if (capabilities.contains(EntityCapability.APPROVAL)) {
            actions.putIfAbsent("submitApproval",
                    StaticModuleActionDefinition.workflowAction("submitApproval", "提交审批"));
        }
    }

    private void addAnnotatedAction(Map<String, StaticModuleActionDefinition> actions,
                                    Method method,
                                    java.util.Set<PlatformAction> disabledActions) {
        ActionEndpoint standard = AnnotationUtils.findAnnotation(method, ActionEndpoint.class);
        if (standard != null && (!disabledActions.contains(standard.value())
                || !StandardWebEndpoint.isDefault(method))) {
            addPlatform(actions, standard.value());
        }
        CustomActionEndpoint custom = AnnotationUtils.findAnnotation(method, CustomActionEndpoint.class);
        if (custom != null) {
            addAnnotatedCustomAction(actions, method, custom.value(), new StaticModuleActionDefinition(
                    custom.value(),
                    custom.value(),
                    custom.title().isBlank() ? custom.value() : custom.title(),
                    toEntityLevel(custom.level()),
                    net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode.valueOf(custom.accessMode().name()),
                    custom.actionAuth(),
                    custom.dataAuth(),
                    net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy.NONE
            ));
        }
    }

    private void addContributionAnnotatedAction(Map<String, StaticModuleActionDefinition> actions,
                                                Method method,
                                                PlatformStaticActionContribution contribution,
                                                java.util.Set<PlatformAction> disabledActions) {
        ActionEndpoint standard = AnnotationUtils.findAnnotation(method, ActionEndpoint.class);
        if (standard != null && (!disabledActions.contains(standard.value())
                || !StandardWebEndpoint.isDefault(method))) {
            addContributionPlatform(actions, contribution, standard.value());
        }
        CustomActionEndpoint custom = AnnotationUtils.findAnnotation(method, CustomActionEndpoint.class);
        if (custom != null) {
            String actionCode = PlatformStaticActionContributionSupport.actionCode(contribution, custom.value());
            addAnnotatedCustomAction(actions, method, actionCode, new StaticModuleActionDefinition(
                    actionCode,
                    actionCode,
                    PlatformStaticActionContributionSupport.title(contribution,
                            custom.title().isBlank() ? custom.value() : custom.title()),
                    toEntityLevel(custom.level()),
                    net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode.valueOf(custom.accessMode().name()),
                    custom.actionAuth(),
                    custom.dataAuth(),
                    net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy.NONE
            ));
        }
    }

    private void addAnnotatedCustomAction(Map<String, StaticModuleActionDefinition> actions,
                                          Method method,
                                          String actionCode,
                                          StaticModuleActionDefinition action) {
        StaticModuleActionDefinition existing = actions.putIfAbsent(actionCode, action);
        if (existing != null && !existing.equals(action)) {
            throw new IllegalStateException("@CustomActionEndpoint action conflicts within controller: "
                    + method.getDeclaringClass().getName() + "." + actionCode + " <- " + method.getName());
        }
    }

    private void addPlatform(Map<String, StaticModuleActionDefinition> actions, PlatformAction action) {
        actions.putIfAbsent(action.code(), StaticModuleActionDefinition.platformAction(action));
    }

    private void addContributionPlatform(Map<String, StaticModuleActionDefinition> actions,
                                         PlatformStaticActionContribution contribution,
                                         PlatformAction action) {
        if (action == PlatformAction.MENU) {
            return;
        }
        String actionCode = PlatformStaticActionContributionSupport.actionCode(contribution, action);
        actions.putIfAbsent(actionCode, new StaticModuleActionDefinition(
                actionCode,
                PlatformStaticActionContributionSupport.permissionActionCode(contribution, action),
                PlatformStaticActionContributionSupport.title(contribution, action),
                toEntityLevel(action.level()),
                net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode.valueOf(action.accessMode().name()),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy()
        ));
    }

    private EntityActionLevel toEntityLevel(net.ximatai.muyun.spring.common.platform.PlatformActionLevel level) {
        if (level == null) {
            return EntityActionLevel.ANY;
        }
        return switch (level) {
            case LIST -> EntityActionLevel.LIST;
            case RECORD -> EntityActionLevel.RECORD;
            case BATCH -> EntityActionLevel.BATCH;
            case DEFAULT, ANY -> EntityActionLevel.ANY;
        };
    }

    private String firstText(String[] values) {
        if (values == null || values.length == 0 || values[0].isBlank()) {
            return null;
        }
        return values[0];
    }
}
