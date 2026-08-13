package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.spring.platform.application.PlatformStaticApplication;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.platform.module.StaticModuleRegistrationSource;
import net.ximatai.muyun.spring.platform.module.StaticReferenceCompiler;
import net.ximatai.muyun.spring.platform.module.StaticReferenceDefinition;
import net.ximatai.muyun.spring.platform.module.StaticServiceAbilityCompiler;
import net.ximatai.muyun.spring.platform.module.StaticModuleServiceDeclaration;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.spring.web.EnableWeb;
import net.ximatai.muyun.spring.web.ReadOnlyWeb;
import net.ximatai.muyun.spring.web.ReferenceWeb;
import net.ximatai.muyun.spring.web.ScopedWeb;
import net.ximatai.muyun.spring.web.SortWeb;
import net.ximatai.muyun.spring.web.TreeWeb;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjectionContributor;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StaticModuleDefinitionScanner implements StaticModuleRegistrationSource {
    private final ApplicationContext applicationContext;

    public StaticModuleDefinitionScanner(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public List<StaticModuleDefinition> scan() {
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
        addActionScopes(definitions);
        return List.copyOf(definitions.values());
    }

    @Override
    public List<StaticModuleDefinition> definitions() {
        return scan();
    }

    private StaticModuleDefinition definition(Object bean, Class<?> beanClass, PlatformStaticModule module) {
        ApplicationDeclaration application = application(module);
        validateModuleAlias(module, application);
        validateScopeAlias(bean, beanClass, module);
        List<RelationProjectionJoinDefinition> projectionJoins = projectionJoins(bean);
        java.util.Set<EntityCapability> capabilities = capabilities(bean, module);
        return StaticModuleDefinition.builder(application.alias(), module.alias(), module.title())
                .parentModuleAlias(module.parent().isBlank() ? null : module.parent())
                .entry(entryType(module), module.route(), module.externalUrl())
                .capabilities(capabilities)
                .actions(actions(bean, beanClass, capabilities))
                .entities(entities(bean, module, projectionJoins))
                .uiDefinition(uiDefinition(bean, module))
                .references(references(bean))
                .readProjections(readProjections(bean))
                .modelClass(modelClass(bean))
                .projectionJoins(projectionJoins)
                .openApiAvailable(AnnotationUtils.findAnnotation(beanClass, StaticModuleOpenApi.class) != null)
                .build();
    }

    private java.util.Set<EntityCapability> capabilities(Object bean, PlatformStaticModule module) {
        java.util.EnumSet<EntityCapability> capabilities = java.util.EnumSet.noneOf(EntityCapability.class);
        for (EntityCapability declared : module.capabilities()) {
            if (StaticServiceAbilityCompiler.isServiceDeclared(declared)) {
                throw new IllegalStateException("@PlatformStaticModule must not redeclare service ability: "
                        + module.alias() + "." + declared.name());
            }
            capabilities.add(declared);
        }
        capabilities.addAll(StaticServiceAbilityCompiler.compile(service(bean)));
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

    private List<StaticModuleReadProjectionDefinition> readProjections(Object bean) {
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
                        projection.path(),
                        projection.referencePath(),
                        projection.outputField(),
                        projection.projectionType(),
                        projection.filterable(),
                        projection.sortable()
                ))
                .toList();
    }

    private List<RelationProjectionJoinDefinition> projectionJoins(Object bean) {
        @SuppressWarnings("deprecation")
        boolean legacyProjectionJoinContributor = bean instanceof RelationProjectionJoinContributor;
        if (!legacyProjectionJoinContributor) {
            return List.of();
        }
        @SuppressWarnings("deprecation")
        RelationProjectionJoinContributor contributor = (RelationProjectionJoinContributor) bean;
        List<RelationProjectionJoinDefinition> joins = contributor.projectionJoins();
        return joins == null ? List.of() : List.copyOf(joins);
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
        addWorkflowActions(actions, capabilities);
        java.util.Set<PlatformAction> disabledActions = StaticServiceAbilityCompiler.disabledActions(service(bean));
        ReflectionUtils.doWithMethods(beanClass,
                method -> addAnnotatedAction(actions, method, disabledActions));
        return List.copyOf(actions.values());
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
                    .forEach(action -> mergeContributionAction(target.moduleAlias(), beanClass, merged, action));
            List<EntityDefinition> entities = mergeContributionEntities(
                    target.moduleAlias(), beanClass, target.entities(), contributionEntities(bean, contribution));
            ModuleUiDefinition uiDefinition = mergeContributionUiDefinition(
                    target.moduleAlias(),
                    beanClass,
                    target.uiDefinition(),
                    contributionUiDefinition(bean, targetModule)
            );
            definitions.put(targetModule, target.toBuilder()
                    .actions(List.copyOf(merged.values()))
                    .entities(entities)
                    .uiDefinition(uiDefinition)
                    .build());
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
            LinkedHashMap<String, StaticModuleActionDefinition> merged = new LinkedHashMap<>();
            target.actions().forEach(action -> merged.put(action.actionCode(), action));
            LinkedHashMap<String, StaticModuleActionDefinition> scopedActions = new LinkedHashMap<>();
            ReflectionUtils.doWithMethods(beanClass,
                    method -> addAnnotatedAction(scopedActions, method, java.util.Set.of()));
            scopedActions.values().forEach(action -> mergeContributionAction(target.moduleAlias(), beanClass, merged, action));
            definitions.put(target.moduleAlias(), target.toBuilder().actions(List.copyOf(merged.values())).build());
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
        LinkedHashMap<String, ViewDefinition> views = new LinkedHashMap<>();
        if (targetUiDefinition != null) {
            targetUiDefinition.views().forEach(view -> views.put(view.viewCode(), view));
        }
        for (ViewDefinition view : contributionUiDefinition.views()) {
            if (views.containsKey(view.viewCode())) {
                throw new IllegalStateException("@PlatformStaticActionContribution UI view conflicts with target module: "
                        + targetModule + "." + view.viewCode() + " <- " + contributor.getName());
            }
            views.put(view.viewCode(), view);
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
        return new ModuleUiDefinition(targetModule, List.copyOf(views.values()), List.copyOf(actions.values()));
    }

    private void mergeContributionAction(String targetModule,
                                         Class<?> contributor,
                                         LinkedHashMap<String, StaticModuleActionDefinition> actions,
                                         StaticModuleActionDefinition action) {
        if (actions.containsKey(action.actionCode())) {
            throw new IllegalStateException("@PlatformStaticActionContribution action conflicts with target module: "
                    + targetModule + "." + action.actionCode() + " <- " + contributor.getName());
        }
        actions.put(action.actionCode(), action);
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
        } else if (ReadOnlyWeb.class.isAssignableFrom(beanClass)) {
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
        StaticServiceAbilityCompiler.standardActions(service).forEach(action -> addPlatform(actions, action));
        if (service == null) {
            addUnwiredLegacyAbilityActions(actions, beanClass);
        }
        if (ReferenceWeb.class.isAssignableFrom(beanClass)) {
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
        if (TreeWeb.class.isAssignableFrom(beanClass)) {
            addPlatform(actions, PlatformAction.TREE);
            addPlatform(actions, PlatformAction.SORT);
        } else if (SortWeb.class.isAssignableFrom(beanClass)) {
            addPlatform(actions, PlatformAction.SORT);
        }
        if (EnableWeb.class.isAssignableFrom(beanClass)) {
            addPlatform(actions, PlatformAction.ENABLE);
            addPlatform(actions, PlatformAction.DISABLE);
        }
        if (RecycleBinWeb.class.isAssignableFrom(beanClass)) {
            addPlatform(actions, PlatformAction.RECYCLE_BIN_QUERY);
            addPlatform(actions, PlatformAction.RECYCLE_BIN_RESTORE);
        }
        if (RecycleBinPurgeWeb.class.isAssignableFrom(beanClass)) {
            addPlatform(actions, PlatformAction.RECYCLE_BIN_PURGE);
        }
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
        } else if (ReadOnlyWeb.class.isAssignableFrom(beanClass)) {
            addContributionUnlessDisabled(actions, contribution, PlatformAction.VIEW, disabledActions);
            addContributionUnlessDisabled(actions, contribution, PlatformAction.QUERY, disabledActions);
        }
        StaticServiceAbilityCompiler.standardActions(service)
                .forEach(action -> addContributionPlatform(actions, contribution, action));
        if (service == null) {
            if (TreeWeb.class.isAssignableFrom(beanClass)) {
                addContributionPlatform(actions, contribution, PlatformAction.TREE);
                addContributionPlatform(actions, contribution, PlatformAction.SORT);
            } else if (SortWeb.class.isAssignableFrom(beanClass)) {
                addContributionPlatform(actions, contribution, PlatformAction.SORT);
            }
            if (EnableWeb.class.isAssignableFrom(beanClass)) {
                addContributionPlatform(actions, contribution, PlatformAction.ENABLE);
                addContributionPlatform(actions, contribution, PlatformAction.DISABLE);
            }
            if (RecycleBinWeb.class.isAssignableFrom(beanClass)) {
                addContributionPlatform(actions, contribution, PlatformAction.RECYCLE_BIN_QUERY);
                addContributionPlatform(actions, contribution, PlatformAction.RECYCLE_BIN_RESTORE);
            }
            if (RecycleBinPurgeWeb.class.isAssignableFrom(beanClass)) {
                addContributionPlatform(actions, contribution, PlatformAction.RECYCLE_BIN_PURGE);
            }
        }
        if (ReferenceWeb.class.isAssignableFrom(beanClass)) {
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
            actions.put(custom.value(), new StaticModuleActionDefinition(
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
            actions.put(actionCode, new StaticModuleActionDefinition(
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
