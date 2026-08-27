package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.platform.module.StaticModuleRegistration;
import net.ximatai.muyun.spring.platform.module.StaticReferenceDefinition;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

public final class StaticModuleDefinition implements StaticModuleRegistration {
    private final String applicationAlias;
    private final String moduleAlias;
    private final String title;
    private final String parentModuleAlias;
    private final ModuleEntryType entryType;
    private final String entryRoute;
    private final String entryExternalUrl;
    private final Set<EntityCapability> capabilities;
    private final Set<NavigatorSourceCapability> navigatorSourceCapabilities;
    private final List<StaticModuleActionDefinition> actions;
    private final List<EntityDefinition> entities;
    private final ModuleUiDefinition uiDefinition;
    private final List<PageContextBindingDefinition> pageContextBindings;
    private final List<StaticReferenceDefinition> references;
    private final List<StaticModuleReadProjectionDefinition> readProjections;
    private final Class<?> modelClass;
    private final Map<String, Class<?>> entityModelClasses;
    private final List<RelationProjectionJoinDefinition> projectionJoins;
    private final QueryDescriptor queryDescriptor;
    private final boolean openApiAvailable;
    private final boolean legacyReadProjectionCompatibility;

    private StaticModuleDefinition(String applicationAlias,
                                   String moduleAlias,
                                   String title,
                                   String parentModuleAlias,
                                   ModuleEntryType entryType,
                                   String entryRoute,
                                   String entryExternalUrl,
                                   Set<EntityCapability> capabilities,
                                   Set<NavigatorSourceCapability> navigatorSourceCapabilities,
                                   List<StaticModuleActionDefinition> actions,
                                   List<EntityDefinition> entities,
                                   ModuleUiDefinition uiDefinition,
                                   List<PageContextBindingDefinition> pageContextBindings,
                                   List<StaticReferenceDefinition> references,
                                   List<StaticModuleReadProjectionDefinition> readProjections,
                                   Class<?> modelClass,
                                   Map<String, Class<?>> entityModelClasses,
                                   List<RelationProjectionJoinDefinition> projectionJoins,
                                   QueryDescriptor queryDescriptor,
                                   boolean openApiAvailable,
                                   boolean legacyReadProjectionCompatibility) {
        applicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        moduleAlias = PlatformNameRules.requireModuleAliasInApplication(moduleAlias, applicationAlias);
        title = title == null || title.isBlank() ? moduleAlias : title.trim();
        if (parentModuleAlias != null && parentModuleAlias.isBlank()) {
            parentModuleAlias = null;
        }
        if (parentModuleAlias != null) {
            parentModuleAlias = PlatformNameRules.requireModuleAliasInApplication(parentModuleAlias, applicationAlias);
        }
        if (entryType == null) {
            entryType = ModuleEntryType.MODULE;
        }
        if (entryRoute != null) {
            entryRoute = entryRoute.trim();
        }
        if (entryExternalUrl != null) {
            entryExternalUrl = entryExternalUrl.trim();
        }
        capabilities = normalizeCapabilities(capabilities);
        navigatorSourceCapabilities = navigatorSourceCapabilities == null ? Set.of() : Set.copyOf(navigatorSourceCapabilities);
        actions = actions == null ? List.of() : List.copyOf(actions);
        entities = entities == null ? List.of() : List.copyOf(entities);
        if (uiDefinition != null && !moduleAlias.equals(uiDefinition.moduleAlias())) {
            throw new IllegalArgumentException("static module UI definition alias must match module alias: "
                    + moduleAlias + " != " + uiDefinition.moduleAlias());
        }
        pageContextBindings = pageContextBindings == null ? List.of() : List.copyOf(pageContextBindings);
        references = references == null ? List.of() : List.copyOf(references);
        readProjections = readProjections == null ? List.of() : List.copyOf(readProjections);
        projectionJoins = projectionJoins == null ? List.of() : List.copyOf(projectionJoins);
        validateReferences(moduleAlias, references);
        validateReadProjectionOutputFields(moduleAlias, entities, readProjections);
        this.applicationAlias = applicationAlias;
        this.moduleAlias = moduleAlias;
        this.title = title;
        this.parentModuleAlias = parentModuleAlias;
        this.entryType = entryType;
        this.entryRoute = entryRoute;
        this.entryExternalUrl = entryExternalUrl;
        this.capabilities = capabilities;
        this.navigatorSourceCapabilities = navigatorSourceCapabilities;
        this.actions = actions;
        this.entities = entities;
        this.uiDefinition = uiDefinition;
        this.pageContextBindings = pageContextBindings;
        this.references = references;
        this.readProjections = readProjections;
        this.modelClass = modelClass;
        this.entityModelClasses = entityModelClasses == null ? Map.of() : Map.copyOf(entityModelClasses);
        this.projectionJoins = projectionJoins;
        this.queryDescriptor = queryDescriptor;
        this.openApiAvailable = openApiAvailable;
        this.legacyReadProjectionCompatibility = legacyReadProjectionCompatibility;
    }

    public String applicationAlias() { return applicationAlias; }
    public String moduleAlias() { return moduleAlias; }
    public String title() { return title; }
    public String parentModuleAlias() { return parentModuleAlias; }
    public ModuleEntryType entryType() { return entryType; }
    public String entryRoute() { return entryRoute; }
    public String entryExternalUrl() { return entryExternalUrl; }
    public Set<EntityCapability> capabilities() { return capabilities; }
    public Set<NavigatorSourceCapability> navigatorSourceCapabilities() { return navigatorSourceCapabilities; }
    public List<StaticModuleActionDefinition> actions() { return actions; }
    public List<EntityDefinition> entities() { return entities; }
    public ModuleUiDefinition uiDefinition() { return uiDefinition; }
    public List<PageContextBindingDefinition> pageContextBindings() { return pageContextBindings; }
    public List<StaticReferenceDefinition> references() { return references; }
    public List<StaticModuleReadProjectionDefinition> readProjections() { return readProjections; }
    public Class<?> modelClass() { return modelClass; }
    public Map<String, Class<?>> entityModelClasses() { return entityModelClasses; }
    public List<RelationProjectionJoinDefinition> projectionJoins() { return projectionJoins; }
    public QueryDescriptor queryDescriptor() { return queryDescriptor; }
    public boolean openApiAvailable() { return openApiAvailable; }
    public boolean legacyReadProjectionCompatibility() { return legacyReadProjectionCompatibility; }

    public String getApplicationAlias() { return applicationAlias; }
    public String getModuleAlias() { return moduleAlias; }
    public String getTitle() { return title; }
    public String getParentModuleAlias() { return parentModuleAlias; }
    public ModuleEntryType getEntryType() { return entryType; }
    public String getEntryRoute() { return entryRoute; }
    public String getEntryExternalUrl() { return entryExternalUrl; }
    public Set<EntityCapability> getCapabilities() { return capabilities; }
    public Set<NavigatorSourceCapability> getNavigatorSourceCapabilities() { return navigatorSourceCapabilities; }
    public List<StaticModuleActionDefinition> getActions() { return actions; }
    public List<EntityDefinition> getEntities() { return entities; }
    public ModuleUiDefinition getUiDefinition() { return uiDefinition; }
    public List<PageContextBindingDefinition> getPageContextBindings() { return pageContextBindings; }
    public List<StaticReferenceDefinition> getReferences() { return references; }
    public List<StaticModuleReadProjectionDefinition> getReadProjections() { return readProjections; }
    public Class<?> getModelClass() { return modelClass; }
    public Map<String, Class<?>> getEntityModelClasses() { return entityModelClasses; }
    public List<RelationProjectionJoinDefinition> getProjectionJoins() { return projectionJoins; }
    public QueryDescriptor getQueryDescriptor() { return queryDescriptor; }
    public boolean isOpenApiAvailable() { return openApiAvailable; }
    public boolean isLegacyReadProjectionCompatibility() { return legacyReadProjectionCompatibility; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StaticModuleDefinition that)) return false;
        return Objects.equals(applicationAlias, that.applicationAlias)
                && Objects.equals(moduleAlias, that.moduleAlias)
                && Objects.equals(title, that.title)
                && Objects.equals(parentModuleAlias, that.parentModuleAlias)
                && entryType == that.entryType
                && Objects.equals(entryRoute, that.entryRoute)
                && Objects.equals(entryExternalUrl, that.entryExternalUrl)
                && Objects.equals(capabilities, that.capabilities)
                && Objects.equals(navigatorSourceCapabilities, that.navigatorSourceCapabilities)
                && Objects.equals(actions, that.actions)
                && Objects.equals(entities, that.entities)
                && Objects.equals(uiDefinition, that.uiDefinition)
                && Objects.equals(pageContextBindings, that.pageContextBindings)
                && Objects.equals(references, that.references)
                && Objects.equals(readProjections, that.readProjections)
                && Objects.equals(modelClass, that.modelClass)
                && Objects.equals(entityModelClasses, that.entityModelClasses)
                && Objects.equals(projectionJoins, that.projectionJoins)
                && Objects.equals(queryDescriptor, that.queryDescriptor)
                && openApiAvailable == that.openApiAvailable
                && legacyReadProjectionCompatibility == that.legacyReadProjectionCompatibility;
    }

    @Override
    public int hashCode() {
        return Objects.hash(applicationAlias, moduleAlias, title, parentModuleAlias, entryType, entryRoute,
                entryExternalUrl, capabilities, navigatorSourceCapabilities, actions, entities, uiDefinition, pageContextBindings, references, readProjections,
                modelClass, entityModelClasses, projectionJoins, queryDescriptor, openApiAvailable,
                legacyReadProjectionCompatibility);
    }

    @Override
    public String toString() {
        return "StaticModuleDefinition[applicationAlias=" + applicationAlias
                + ", moduleAlias=" + moduleAlias
                + ", title=" + title
                + ", parentModuleAlias=" + parentModuleAlias
                + ", entryType=" + entryType
                + ", entryRoute=" + entryRoute
                + ", entryExternalUrl=" + entryExternalUrl
                + ", capabilities=" + capabilities
                + ", navigatorSourceCapabilities=" + navigatorSourceCapabilities
                + ", actions=" + actions
                + ", entities=" + entities
                + ", uiDefinition=" + uiDefinition
                + ", pageContextBindings=" + pageContextBindings
                + ", references=" + references
                + ", readProjections=" + readProjections
                + ", modelClass=" + modelClass
                + ", entityModelClasses=" + entityModelClasses
                + ", projectionJoins=" + projectionJoins
                + ", queryDescriptor=" + queryDescriptor
                + ", openApiAvailable=" + openApiAvailable + "]";
    }

    public boolean supports(EntityCapability capability) {
        return capabilities.contains(capability);
    }

    public static Builder builder(String applicationAlias, String moduleAlias, String title) {
        return new Builder(applicationAlias, moduleAlias, title);
    }

    public Builder toBuilder() {
        return builder(applicationAlias, moduleAlias, title)
                .parentModuleAlias(parentModuleAlias)
                .entry(entryType, entryRoute, entryExternalUrl)
                .capabilities(capabilities)
                .navigatorSourceCapabilities(navigatorSourceCapabilities)
                .actions(actions)
                .entities(entities)
                .uiDefinition(uiDefinition)
                .pageContextBindings(pageContextBindings)
                .references(references)
                .readProjections(readProjections)
                .modelClass(modelClass)
                .entityModelClasses(entityModelClasses)
                .projectionJoins(projectionJoins)
                .queryDescriptor(queryDescriptor)
                .openApiAvailable(openApiAvailable)
                .legacyReadProjectionCompatibility(legacyReadProjectionCompatibility);
    }

    public static final class Builder {
        private final String applicationAlias;
        private final String moduleAlias;
        private final String title;
        private String parentModuleAlias;
        private ModuleEntryType entryType = ModuleEntryType.MODULE;
        private String entryRoute;
        private String entryExternalUrl;
        private Set<EntityCapability> capabilities = Set.of();
        private Set<NavigatorSourceCapability> navigatorSourceCapabilities = Set.of();
        private List<StaticModuleActionDefinition> actions = List.of();
        private List<EntityDefinition> entities = List.of();
        private ModuleUiDefinition uiDefinition;
        private List<PageContextBindingDefinition> pageContextBindings = List.of();
        private List<StaticReferenceDefinition> references = List.of();
        private List<StaticModuleReadProjectionDefinition> readProjections = List.of();
        private Class<?> modelClass;
        private Map<String, Class<?>> entityModelClasses = Map.of();
        private List<RelationProjectionJoinDefinition> projectionJoins = List.of();
        private QueryDescriptor queryDescriptor;
        private boolean openApiAvailable;
        private boolean legacyReadProjectionCompatibility;

        private Builder(String applicationAlias, String moduleAlias, String title) {
            this.applicationAlias = applicationAlias;
            this.moduleAlias = moduleAlias;
            this.title = title;
        }

        public Builder parentModuleAlias(String parentModuleAlias) {
            this.parentModuleAlias = parentModuleAlias;
            return this;
        }

        public Builder entry(ModuleEntryType entryType, String entryRoute, String entryExternalUrl) {
            this.entryType = entryType;
            this.entryRoute = entryRoute;
            this.entryExternalUrl = entryExternalUrl;
            return this;
        }

        public Builder capabilities(Set<EntityCapability> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public Builder navigatorSourceCapabilities(Set<NavigatorSourceCapability> navigatorSourceCapabilities) {
            this.navigatorSourceCapabilities = navigatorSourceCapabilities;
            return this;
        }

        public Builder actions(List<StaticModuleActionDefinition> actions) {
            this.actions = actions;
            return this;
        }

        public Builder entities(List<EntityDefinition> entities) {
            this.entities = entities;
            return this;
        }

        public Builder uiDefinition(ModuleUiDefinition uiDefinition) {
            this.uiDefinition = uiDefinition;
            return this;
        }

        public Builder pageContextBindings(List<PageContextBindingDefinition> pageContextBindings) {
            this.pageContextBindings = pageContextBindings == null ? List.of() : List.copyOf(pageContextBindings);
            return this;
        }

        public Builder references(List<StaticReferenceDefinition> references) {
            this.references = references;
            return this;
        }

        public Builder readProjections(List<StaticModuleReadProjectionDefinition> readProjections) {
            this.readProjections = readProjections;
            return this;
        }

        public Builder modelClass(Class<?> modelClass) {
            this.modelClass = modelClass;
            return this;
        }

        public Builder entityModelClasses(Map<String, Class<?>> value) {
            this.entityModelClasses = value == null ? Map.of() : Map.copyOf(value);
            return this;
        }

        public Builder projectionJoins(List<RelationProjectionJoinDefinition> projectionJoins) {
            this.projectionJoins = projectionJoins;
            return this;
        }

        public Builder queryDescriptor(QueryDescriptor queryDescriptor) {
            this.queryDescriptor = queryDescriptor;
            return this;
        }

        public Builder openApiAvailable(boolean value) {
            this.openApiAvailable = value;
            return this;
        }

        public Builder legacyReadProjectionCompatibility(boolean value) {
            this.legacyReadProjectionCompatibility = value;
            return this;
        }

        public StaticModuleDefinition build() {
            return new StaticModuleDefinition(applicationAlias, moduleAlias, title, parentModuleAlias, entryType,
                    entryRoute, entryExternalUrl, capabilities, navigatorSourceCapabilities, actions, entities, uiDefinition, pageContextBindings, references,
                    readProjections, modelClass, entityModelClasses, projectionJoins, queryDescriptor, openApiAvailable,
                    legacyReadProjectionCompatibility);
        }
    }

    private static Set<EntityCapability> normalizeCapabilities(Set<EntityCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return Set.of();
        }
        EnumSet<EntityCapability> normalized = EnumSet.copyOf(capabilities);
        if (normalized.contains(EntityCapability.APPROVAL)) {
            normalized.add(EntityCapability.WORKFLOW);
        }
        return Set.copyOf(normalized);
    }

    private static void validateReadProjectionOutputFields(String moduleAlias,
                                                           List<EntityDefinition> entities,
                                                           List<StaticModuleReadProjectionDefinition> projections) {
        if (projections.isEmpty()) {
            return;
        }
        Set<String> outputFields = new java.util.LinkedHashSet<>();
        for (StaticModuleReadProjectionDefinition projection : projections) {
            if (!outputFields.add(projection.outputField())) {
                throw new IllegalArgumentException("duplicate static module read projection output field: "
                        + moduleAlias + "." + projection.outputField());
            }
        }
        if (entities.isEmpty()) {
            return;
        }
        Set<String> reservedFields = new java.util.LinkedHashSet<>();
        reservedFields.add(StandardEntitySchema.ID_FIELD);
        reservedFields.add(StandardEntitySchema.TENANT_ID_FIELD);
        reservedFields.add(StandardEntitySchema.VERSION_FIELD);
        reservedFields.add(StandardEntitySchema.DELETED_FIELD);
        reservedFields.add(StandardEntitySchema.DELETED_AT_FIELD);
        reservedFields.add(StandardEntitySchema.DELETED_BY_FIELD);
        reservedFields.add(StandardEntitySchema.CREATED_BY_FIELD);
        reservedFields.add(StandardEntitySchema.CREATED_AT_FIELD);
        reservedFields.add(StandardEntitySchema.UPDATED_BY_FIELD);
        reservedFields.add(StandardEntitySchema.UPDATED_AT_FIELD);
        reservedFields.add(PlatformAbilityFields.TITLE_FIELD);
        reservedFields.add(PlatformAbilityFields.ENABLED_FIELD);
        reservedFields.add(PlatformAbilityFields.TREE_PARENT_FIELD);
        reservedFields.add(PlatformAbilityFields.SORT_FIELD);
        entities.getFirst().fields().stream()
                .map(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition::fieldName)
                .forEach(reservedFields::add);
        for (StaticModuleReadProjectionDefinition projection : projections) {
            if (reservedFields.contains(projection.outputField())) {
                throw new IllegalArgumentException("static module read projection output field conflicts with main field: "
                        + moduleAlias + "." + projection.outputField());
            }
        }
    }

    private static void validateReferences(String moduleAlias, List<StaticReferenceDefinition> references) {
        if (references.isEmpty()) {
            return;
        }
        Set<String> codes = new java.util.LinkedHashSet<>();
        for (StaticReferenceDefinition reference : references) {
            if (!codes.add(reference.code())) {
                throw new IllegalArgumentException("duplicate static reference code: "
                        + moduleAlias + "." + reference.code());
            }
        }
    }
}
