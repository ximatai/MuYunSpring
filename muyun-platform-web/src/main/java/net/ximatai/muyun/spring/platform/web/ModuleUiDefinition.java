package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.ability.child.AggregateChildFormulaDefinition;

import java.util.List;
import java.util.function.Consumer;

public record ModuleUiDefinition(String moduleAlias,
                                 List<UiActionDefinition> actions,
                                 ModulePageDefinition page,
                                 ViewDefinition defaultEditor,
                                 List<EditorSurfaceDefinition> editorSurfaces,
                                 List<PageDetailEditorContribution> editorContributions,
                                 List<PageDetailRelationDefinition> detailRelations) {
    public ModuleUiDefinition(String moduleAlias, List<UiActionDefinition> actions, ModulePageDefinition page,
                              ViewDefinition defaultEditor, List<EditorSurfaceDefinition> editorSurfaces,
                              List<PageDetailEditorContribution> editorContributions) {
        this(moduleAlias, actions, page, defaultEditor, editorSurfaces, editorContributions, List.of());
    }
    public ModuleUiDefinition {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (defaultEditor != null && defaultEditor.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("default editor must be a form view");
        }
        editorSurfaces = editorSurfaces == null ? List.of() : List.copyOf(editorSurfaces);
        if (editorSurfaces.stream().map(EditorSurfaceDefinition::key).distinct().count() != editorSurfaces.size()) {
            throw new IllegalArgumentException("duplicate editor surface key");
        }
        editorContributions = editorContributions == null ? List.of() : List.copyOf(editorContributions);
        if (editorContributions.stream().map(PageDetailEditorContribution::resource).distinct().count()
                != editorContributions.size()) {
            throw new IllegalArgumentException("duplicate editor contribution resource");
        }
        detailRelations = detailRelations == null ? List.of() : List.copyOf(detailRelations);
        if (detailRelations.stream().map(PageDetailRelationDefinition::code).distinct().count()
                != detailRelations.size()) {
            throw new IllegalArgumentException("duplicate detail relation code");
        }
    }

    public ModuleUiDefinition(String moduleAlias, ModulePageDefinition page) {
        this(moduleAlias, List.of(), page, null, List.of(), List.of());
    }

    public static Builder builder(String moduleAlias) {
        return new Builder(moduleAlias);
    }

    public static final class Builder {
        private final String moduleAlias;
        private final List<UiActionDefinition> actions = new java.util.ArrayList<>();
        private ModulePageDefinition page;
        private ViewDefinition defaultEditor;
        private final List<EditorSurfaceDefinition> editorSurfaces = new java.util.ArrayList<>();
        private final List<PageDetailEditorContribution> editorContributions = new java.util.ArrayList<>();
        private final List<PageDetailRelationDefinition> detailRelations = new java.util.ArrayList<>();

        private Builder(String moduleAlias) {
            this.moduleAlias = moduleAlias;
        }

        public Builder typedTextConfirmation(String actionCode, String requiredField) {
            actions.add(UiActionDefinition.typedTextConfirmation(actionCode, requiredField));
            return this;
        }

        /** Contributes a child-resource editor without creating or nesting another page. */
        public Builder editorContribution(String resource, Consumer<ViewDefinition.Builder> customizer) {
            ViewDefinition.Builder builder = ViewDefinition.form(ModuleUiViewCodes.childResourceDefaultForm(resource));
            if (customizer != null) customizer.accept(builder);
            editorContributions.add(new PageDetailEditorContribution(resource, builder.build()));
            return this;
        }

        /** Declares a relation by its persistence semantics instead of selecting a positional overload. */
        public Builder relation(String code, Consumer<RelationSelectionBuilder> customizer) {
            RelationSelectionBuilder builder = new RelationSelectionBuilder(code);
            if (customizer != null) customizer.accept(builder);
            detailRelations.add(builder.build());
            return this;
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code readOnly(...)}. */
        @Deprecated(forRemoval = false)
        public Builder detailRelation(String code, String title, String targetEntityAlias, String parentBinding,
                                      boolean readOnly) {
            if (readOnly) {
                return relation(code, relation -> relation.readOnly(value -> value
                        .title(title).targetEntity(targetEntityAlias).parentBinding(parentBinding)));
            }
            detailRelations.add(new PageDetailRelationDefinition(code, title, targetEntityAlias, parentBinding,
                    false, true));
            return this;
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code managed(...)}. */
        @Deprecated(forRemoval = false)
        public Builder managedDetailRelation(String code, String title, String targetEntityAlias, String parentBinding,
                                             PageDetailRelationMutationDefinition mutations) {
            return managedDetailRelation(code, title, targetEntityAlias, parentBinding, mutations, null,
                    PageDetailRelationPaginationDefinition.DEFAULT, PageDetailRelationEditingDefinition.DEFAULT);
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code managed(...)}. */
        @Deprecated(forRemoval = false)
        public Builder managedDetailRelation(String code, String title, String targetEntityAlias, String parentBinding,
                                             PageDetailRelationMutationDefinition mutations,
                                             PageDetailRelationParentConstraintDefinition parentConstraint) {
            return managedDetailRelation(code, title, targetEntityAlias, parentBinding, mutations, parentConstraint,
                    PageDetailRelationPaginationDefinition.DEFAULT, PageDetailRelationEditingDefinition.DEFAULT);
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code managed(...)}. */
        @Deprecated(forRemoval = false)
        public Builder managedDetailRelation(String code, String title, String targetEntityAlias, String parentBinding,
                                             PageDetailRelationMutationDefinition mutations,
                                             PageDetailRelationPaginationDefinition pagination) {
            return managedDetailRelation(code, title, targetEntityAlias, parentBinding, mutations, null, pagination,
                    PageDetailRelationEditingDefinition.DEFAULT);
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code managed(...)}. */
        @Deprecated(forRemoval = false)
        public Builder managedDetailRelation(String code, String title, String targetEntityAlias, String parentBinding,
                                             PageDetailRelationMutationDefinition mutations,
                                             PageDetailRelationParentConstraintDefinition parentConstraint,
                                             PageDetailRelationPaginationDefinition pagination) {
            return managedDetailRelation(code, title, targetEntityAlias, parentBinding, mutations, parentConstraint,
                    pagination, PageDetailRelationEditingDefinition.DEFAULT);
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code managed(...)}. */
        @Deprecated(forRemoval = false)
        public Builder managedDetailRelation(String code, String title, String targetEntityAlias, String parentBinding,
                                             PageDetailRelationMutationDefinition mutations,
                                             PageDetailRelationParentConstraintDefinition parentConstraint,
                                             PageDetailRelationPaginationDefinition pagination,
                                             PageDetailRelationEditingDefinition editing) {
            detailRelations.add(new PageDetailRelationDefinition(code, title, targetEntityAlias, parentBinding,
                    false, true, mutations, parentConstraint, pagination, editing, true, false,
                    List.of(), UiRule.constant(Boolean.TRUE)));
            return this;
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code managedReadOnly(...)}. */
        @Deprecated(forRemoval = false)
        public Builder managedReadOnlyDetailRelation(String code, String title, String targetEntityAlias,
                                                     String parentBinding,
                                                     PageDetailRelationParentConstraintDefinition parentConstraint) {
            return relation(code, relation -> relation.managedReadOnly(value -> value
                    .title(title).targetEntity(targetEntityAlias).parentBinding(parentBinding)
                    .parentConstraint(parentConstraint)));
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code aggregateChild(...)}. */
        @Deprecated(forRemoval = false)
        public Builder aggregateChildRelation(String code, String title, String targetEntityAlias,
                                              String parentBinding, UiRule<Boolean> visible) {
            return aggregateChildRelation(code, title, targetEntityAlias, parentBinding, visible, false);
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code aggregateChild(...)}. */
        @Deprecated(forRemoval = false)
        public Builder aggregateChildRelation(String code, String title, String targetEntityAlias,
                                              String parentBinding, UiRule<Boolean> visible,
                                              boolean recycleBinEnabled) {
            return aggregateChildRelation(code, title, targetEntityAlias, parentBinding, visible, recycleBinEnabled,
                    List.of());
        }

        /** @deprecated Use {@link #relation(String, Consumer)} with {@code aggregateChild(...)}. */
        @Deprecated(forRemoval = false)
        public Builder aggregateChildRelation(String code, String title, String targetEntityAlias,
                                              String parentBinding, UiRule<Boolean> visible,
                                              boolean recycleBinEnabled,
                                              List<AggregateChildFormulaDefinition> formComputeRules) {
            detailRelations.add(new PageDetailRelationDefinition(code, title, targetEntityAlias, parentBinding,
                    false, false, null, null, PageDetailRelationPaginationDefinition.unpaged(),
                    PageDetailRelationEditingDefinition.aggregateInline(recycleBinEnabled), true, true,
                    formComputeRules, visible));
            return this;
        }

        /** Declares one default editor plus optional named editors owned by this module. */
        public Builder editors(Consumer<EditorSurfacesBuilder> customizer) {
            EditorSurfacesBuilder builder = new EditorSurfacesBuilder();
            if (customizer != null) customizer.accept(builder);
            if (builder.defaultEditor != null) {
                if (defaultEditor != null) throw new IllegalStateException("default editor is already declared");
                defaultEditor = builder.defaultEditor;
            }
            editorSurfaces.addAll(builder.editorSurfaces);
            return this;
        }

        /** Selects a page-root template. Its supported slots are defined by the template type. */
        public Builder page(ModulePageDefinition definition) {
            if (definition == null) throw new IllegalArgumentException("page definition must not be null");
            page = definition;
            return this;
        }

        public ModuleUiDefinition build() {
            return new ModuleUiDefinition(moduleAlias, actions, page, defaultEditor, editorSurfaces,
                    editorContributions, detailRelations);
        }
    }

    public static final class EditorSurfacesBuilder {
        private ViewDefinition defaultEditor;
        private final List<EditorSurfaceDefinition> editorSurfaces = new java.util.ArrayList<>();

        public EditorSurfacesBuilder defaultEditor(Consumer<ViewDefinition.Builder> customizer) {
            if (defaultEditor != null) throw new IllegalStateException("default editor is already declared");
            ViewDefinition.Builder builder = ViewDefinition.form("default_editor");
            if (customizer != null) customizer.accept(builder);
            defaultEditor = builder.build();
            return this;
        }

        public EditorSurfacesBuilder editor(String key, Consumer<ViewDefinition.Builder> customizer) {
            ViewDefinition.Builder builder = ViewDefinition.form("editor_" + key);
            if (customizer != null) customizer.accept(builder);
            editorSurfaces.add(new EditorSurfaceDefinition(key, builder.build()));
            return this;
        }
    }

    /**
     * Chooses exactly one persistence semantic for a detail relation.
     *
     * <p>Each branch exposes only the options that can reach its resolved descriptor. This keeps
     * the declaration honest: unsupported relation facts cannot be accepted and later discarded.</p>
     */
    public static final class RelationSelectionBuilder {
        private final String code;
        private PageDetailRelationDefinition definition;

        private RelationSelectionBuilder(String code) {
            this.code = code;
        }

        /** Declares a passive read-only relation; no relation gateway contract is invented. */
        public RelationSelectionBuilder readOnly(Consumer<ReadOnlyRelationBuilder> customizer) {
            ReadOnlyRelationBuilder builder = new ReadOnlyRelationBuilder(code);
            if (customizer != null) customizer.accept(builder);
            return select(builder.build());
        }

        /** Declares a gateway-backed relation query while deliberately exposing no mutations. */
        public RelationSelectionBuilder managedReadOnly(Consumer<ManagedReadOnlyRelationBuilder> customizer) {
            ManagedReadOnlyRelationBuilder builder = new ManagedReadOnlyRelationBuilder(code);
            if (customizer != null) customizer.accept(builder);
            return select(builder.build());
        }

        /** Declares a gateway-backed relation with an explicit mutation contract. */
        public RelationSelectionBuilder managed(PageDetailRelationMutationDefinition mutations,
                                                Consumer<ManagedRelationBuilder> customizer) {
            ManagedRelationBuilder builder = new ManagedRelationBuilder(code, mutations);
            if (customizer != null) customizer.accept(builder);
            return select(builder.build());
        }

        /** Declares an embedded child collection persisted by the parent aggregate. */
        public RelationSelectionBuilder aggregateChild(Consumer<AggregateChildRelationBuilder> customizer) {
            AggregateChildRelationBuilder builder = new AggregateChildRelationBuilder(code);
            if (customizer != null) customizer.accept(builder);
            return select(builder.build());
        }

        private RelationSelectionBuilder select(PageDetailRelationDefinition value) {
            if (definition != null) throw new IllegalStateException("detail relation save semantics are already declared");
            definition = value;
            return this;
        }

        private PageDetailRelationDefinition build() {
            if (definition == null) {
                throw new IllegalArgumentException("detail relation must declare readOnly, managedReadOnly, managed or aggregateChild");
            }
            return definition;
        }
    }

    public static final class ReadOnlyRelationBuilder extends RelationDetails<ReadOnlyRelationBuilder> {
        private ReadOnlyRelationBuilder(String code) { super(code); }

        private PageDetailRelationDefinition build() {
            return definition(true, false, null, null, PageDetailRelationPaginationDefinition.DEFAULT,
                    PageDetailRelationEditingDefinition.DEFAULT, true, false, List.of());
        }
    }

    public static final class ManagedReadOnlyRelationBuilder extends ManagedRelationDetails<ManagedReadOnlyRelationBuilder> {
        private ManagedReadOnlyRelationBuilder(String code) { super(code); }

        private PageDetailRelationDefinition build() {
            return definition(true, null);
        }
    }

    public static final class ManagedRelationBuilder extends ManagedRelationDetails<ManagedRelationBuilder> {
        private final PageDetailRelationMutationDefinition mutations;

        private ManagedRelationBuilder(String code, PageDetailRelationMutationDefinition mutations) {
            super(code);
            if (mutations == null) throw new IllegalArgumentException("managed detail relation requires mutations");
            this.mutations = mutations;
        }

        private PageDetailRelationDefinition build() {
            return definition(false, mutations);
        }
    }

    public static final class AggregateChildRelationBuilder extends RelationDetails<AggregateChildRelationBuilder> {
        private boolean recycleBinEnabled;
        private final List<AggregateChildFormulaDefinition> formComputeRules = new java.util.ArrayList<>();

        private AggregateChildRelationBuilder(String code) { super(code); }

        public AggregateChildRelationBuilder recycleBin() { recycleBinEnabled = true; return this; }

        public AggregateChildRelationBuilder formCompute(AggregateChildFormulaDefinition value) {
            if (value != null) formComputeRules.add(value);
            return this;
        }

        private PageDetailRelationDefinition build() {
            return definition(false, false, null, null, PageDetailRelationPaginationDefinition.unpaged(),
                    PageDetailRelationEditingDefinition.aggregateInline(recycleBinEnabled), true, true,
                    formComputeRules);
        }
    }

    public abstract static class RelationDetails<T extends RelationDetails<T>> {
        private final String code;
        private String title;
        private String targetEntityAlias;
        private String parentBinding;
        private UiRule<Boolean> visible = UiRule.constant(Boolean.TRUE);

        private RelationDetails(String code) { this.code = code; }

        @SuppressWarnings("unchecked")
        public T title(String value) { title = value; return (T) this; }

        @SuppressWarnings("unchecked")
        public T targetEntity(String value) { targetEntityAlias = value; return (T) this; }

        @SuppressWarnings("unchecked")
        public T parentBinding(String value) { parentBinding = value; return (T) this; }

        @SuppressWarnings("unchecked")
        public T visible(UiRule<Boolean> value) { visible = value; return (T) this; }

        protected final PageDetailRelationDefinition definition(boolean readOnly, boolean managedQuery,
                                                                 PageDetailRelationMutationDefinition mutations,
                                                                 PageDetailRelationParentConstraintDefinition parentConstraint,
                                                                 PageDetailRelationPaginationDefinition pagination,
                                                                 PageDetailRelationEditingDefinition editing,
                                                                 boolean refreshOnDetailReload, boolean aggregateChild,
                                                                 List<AggregateChildFormulaDefinition> formComputeRules) {
            return new PageDetailRelationDefinition(code, title, targetEntityAlias, parentBinding, readOnly, managedQuery,
                    mutations, parentConstraint, pagination, editing, refreshOnDetailReload, aggregateChild,
                    formComputeRules, visible);
        }
    }

    public abstract static class ManagedRelationDetails<T extends ManagedRelationDetails<T>> extends RelationDetails<T> {
        private PageDetailRelationParentConstraintDefinition parentConstraint;
        private PageDetailRelationPaginationDefinition pagination = PageDetailRelationPaginationDefinition.DEFAULT;

        private ManagedRelationDetails(String code) { super(code); }

        @SuppressWarnings("unchecked")
        public T parentConstraint(PageDetailRelationParentConstraintDefinition value) {
            parentConstraint = value;
            return (T) this;
        }

        @SuppressWarnings("unchecked")
        public T pagination(PageDetailRelationPaginationDefinition value) {
            pagination = value;
            return (T) this;
        }

        protected final PageDetailRelationDefinition definition(boolean readOnly,
                                                                 PageDetailRelationMutationDefinition mutations) {
            return definition(readOnly, true, mutations, parentConstraint, pagination,
                    PageDetailRelationEditingDefinition.DEFAULT, true, false, List.of());
        }
    }
}
