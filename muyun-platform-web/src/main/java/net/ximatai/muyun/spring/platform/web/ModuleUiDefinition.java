package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;
import java.util.function.Consumer;

public record ModuleUiDefinition(String moduleAlias,
                                 List<UiActionDefinition> actions,
                                 ModulePageDefinition page,
                                 ViewDefinition defaultEditor,
                                 List<EditorSurfaceDefinition> editorSurfaces,
                                 List<PageDetailEditorContribution> editorContributions) {
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
            return new ModuleUiDefinition(moduleAlias, actions, page, defaultEditor, editorSurfaces, editorContributions);
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
}
