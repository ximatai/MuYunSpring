package net.ximatai.muyun.spring.dynamic.metadata;

import java.util.List;
import java.util.Objects;

public final class ModuleDefinition {
    private final String moduleAlias;
    private final String name;
    private final List<EntityDefinition> entities;
    private final List<EntityRelationDefinition> relations;
    private final List<EntityReferenceDefinition> references;
    private final List<EntityDiscriminatedValueDefinition> discriminatedValues;
    private final List<EntityReferenceLoadDefinition> referenceLoads;
    private final List<EntityReferencedByDefinition> referencedBys;
    private final List<EntityViewDefinition> views;
    private final List<EntityAssociationViewDefinition> associationViews;
    private final List<EntityActionDefinition> actions;
    private final String mainEntityAlias;

    public ModuleDefinition(String moduleAlias, String name, List<EntityDefinition> entities) {
        this(moduleAlias, name, entities, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);
    }

    private ModuleDefinition(String moduleAlias,
                             String name,
                             List<EntityDefinition> entities,
                             List<EntityRelationDefinition> relations,
                             List<EntityReferenceDefinition> references,
                             List<EntityDiscriminatedValueDefinition> discriminatedValues,
                             List<EntityReferenceLoadDefinition> referenceLoads,
                             List<EntityReferencedByDefinition> referencedBys,
                             List<EntityViewDefinition> views,
                             List<EntityAssociationViewDefinition> associationViews,
                             List<EntityActionDefinition> actions,
                             String mainEntityAlias) {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        references = references == null ? List.of() : List.copyOf(references);
        discriminatedValues = discriminatedValues == null ? List.of() : List.copyOf(discriminatedValues);
        referenceLoads = referenceLoads == null ? List.of() : List.copyOf(referenceLoads);
        referencedBys = referencedBys == null ? List.of() : List.copyOf(referencedBys);
        views = views == null ? List.of() : List.copyOf(views);
        associationViews = associationViews == null ? List.of() : List.copyOf(associationViews);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (mainEntityAlias == null || mainEntityAlias.isBlank()) {
            mainEntityAlias = entities.isEmpty() ? null : entities.getFirst().alias();
        }
        this.moduleAlias = moduleAlias;
        this.name = name;
        this.entities = entities;
        this.relations = relations;
        this.references = references;
        this.discriminatedValues = discriminatedValues;
        this.referenceLoads = referenceLoads;
        this.referencedBys = referencedBys;
        this.views = views;
        this.associationViews = associationViews;
        this.actions = actions;
        this.mainEntityAlias = mainEntityAlias;
    }

    public String moduleAlias() { return moduleAlias; }
    public String name() { return name; }
    public List<EntityDefinition> entities() { return entities; }
    public List<EntityRelationDefinition> relations() { return relations; }
    public List<EntityReferenceDefinition> references() { return references; }
    public List<EntityDiscriminatedValueDefinition> discriminatedValues() { return discriminatedValues; }
    public List<EntityReferenceLoadDefinition> referenceLoads() { return referenceLoads; }
    public List<EntityReferencedByDefinition> referencedBys() { return referencedBys; }
    public List<EntityViewDefinition> views() { return views; }
    public List<EntityAssociationViewDefinition> associationViews() { return associationViews; }
    public List<EntityActionDefinition> actions() { return actions; }
    public String mainEntityAlias() { return mainEntityAlias; }

    public String getModuleAlias() { return moduleAlias; }
    public String getName() { return name; }
    public List<EntityDefinition> getEntities() { return entities; }
    public List<EntityRelationDefinition> getRelations() { return relations; }
    public List<EntityReferenceDefinition> getReferences() { return references; }
    public List<EntityDiscriminatedValueDefinition> getDiscriminatedValues() { return discriminatedValues; }
    public List<EntityReferenceLoadDefinition> getReferenceLoads() { return referenceLoads; }
    public List<EntityReferencedByDefinition> getReferencedBys() { return referencedBys; }
    public List<EntityViewDefinition> getViews() { return views; }
    public List<EntityAssociationViewDefinition> getAssociationViews() { return associationViews; }
    public List<EntityActionDefinition> getActions() { return actions; }
    public String getMainEntityAlias() { return mainEntityAlias; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ModuleDefinition that)) return false;
        return Objects.equals(moduleAlias, that.moduleAlias)
                && Objects.equals(name, that.name)
                && Objects.equals(entities, that.entities)
                && Objects.equals(relations, that.relations)
                && Objects.equals(references, that.references)
                && Objects.equals(discriminatedValues, that.discriminatedValues)
                && Objects.equals(referenceLoads, that.referenceLoads)
                && Objects.equals(referencedBys, that.referencedBys)
                && Objects.equals(views, that.views)
                && Objects.equals(associationViews, that.associationViews)
                && Objects.equals(actions, that.actions)
                && Objects.equals(mainEntityAlias, that.mainEntityAlias);
    }

    @Override
    public int hashCode() {
        return Objects.hash(moduleAlias, name, entities, relations, references, discriminatedValues, referenceLoads, referencedBys, views, associationViews, actions,
                mainEntityAlias);
    }

    @Override
    public String toString() {
        return "ModuleDefinition[moduleAlias=" + moduleAlias
                + ", name=" + name
                + ", entities=" + entities
                + ", relations=" + relations
                + ", references=" + references
                + ", discriminatedValues=" + discriminatedValues
                + ", referenceLoads=" + referenceLoads
                + ", referencedBys=" + referencedBys
                + ", views=" + views
                + ", associationViews=" + associationViews
                + ", actions=" + actions
                + ", mainEntityAlias=" + mainEntityAlias + "]";
    }

    public String code() {
        return moduleAlias;
    }

    public static Builder builder(String moduleAlias, String name) {
        return new Builder(moduleAlias, name);
    }

    public Builder toBuilder() {
        return builder(moduleAlias, name)
                .entities(entities)
                .relations(relations)
                .references(references)
                .discriminatedValues(discriminatedValues)
                .referenceLoads(referenceLoads)
                .referencedBys(referencedBys)
                .views(views)
                .associationViews(associationViews)
                .actions(actions)
                .mainEntityAlias(mainEntityAlias);
    }

    public static final class Builder {
        private final String moduleAlias;
        private final String name;
        private List<EntityDefinition> entities = List.of();
        private List<EntityRelationDefinition> relations = List.of();
        private List<EntityReferenceDefinition> references = List.of();
        private List<EntityDiscriminatedValueDefinition> discriminatedValues = List.of();
        private List<EntityReferenceLoadDefinition> referenceLoads = List.of();
        private List<EntityReferencedByDefinition> referencedBys = List.of();
        private List<EntityViewDefinition> views = List.of();
        private List<EntityAssociationViewDefinition> associationViews = List.of();
        private List<EntityActionDefinition> actions = List.of();
        private String mainEntityAlias;

        private Builder(String moduleAlias, String name) {
            this.moduleAlias = moduleAlias;
            this.name = name;
        }

        public Builder entities(List<EntityDefinition> entities) {
            this.entities = entities;
            return this;
        }

        public Builder relations(List<EntityRelationDefinition> relations) {
            this.relations = relations;
            return this;
        }

        public Builder references(List<EntityReferenceDefinition> references) {
            this.references = references;
            return this;
        }

        public Builder discriminatedValues(List<EntityDiscriminatedValueDefinition> discriminatedValues) {
            this.discriminatedValues = discriminatedValues;
            return this;
        }

        public Builder referenceLoads(List<EntityReferenceLoadDefinition> referenceLoads) {
            this.referenceLoads = referenceLoads;
            return this;
        }

        public Builder referencedBys(List<EntityReferencedByDefinition> referencedBys) {
            this.referencedBys = referencedBys;
            return this;
        }

        public Builder views(List<EntityViewDefinition> views) {
            this.views = views;
            return this;
        }

        public Builder associationViews(List<EntityAssociationViewDefinition> associationViews) {
            this.associationViews = associationViews;
            return this;
        }

        public Builder actions(List<EntityActionDefinition> actions) {
            this.actions = actions;
            return this;
        }

        public Builder mainEntityAlias(String mainEntityAlias) {
            this.mainEntityAlias = mainEntityAlias;
            return this;
        }

        public ModuleDefinition build() {
            return new ModuleDefinition(moduleAlias, name, entities, relations, references, discriminatedValues, referenceLoads, referencedBys, views, associationViews,
                    actions, mainEntityAlias);
        }
    }
}
