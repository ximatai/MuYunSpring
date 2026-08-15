package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;

import java.util.List;
import java.util.function.Consumer;

public record ModuleUiDefinition(String moduleAlias,
                                 List<UiActionDefinition> actions,
                                 ModulePageDefinition page,
                                 ViewDefinition customPageEditor,
                                 List<PageDetailEditorContribution> editorContributions) {
    public ModuleUiDefinition {
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (customPageEditor != null && customPageEditor.viewKind() != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("custom page editor must be a form view");
        }
        editorContributions = editorContributions == null ? List.of() : List.copyOf(editorContributions);
        if (editorContributions.stream().map(PageDetailEditorContribution::resource).distinct().count()
                != editorContributions.size()) {
            throw new IllegalArgumentException("duplicate editor contribution resource");
        }
    }

    public ModuleUiDefinition(String moduleAlias, ModulePageDefinition page) {
        this(moduleAlias, List.of(), page, null, List.of());
    }

    public static Builder builder(String moduleAlias) {
        return new Builder(moduleAlias);
    }

    public static final class Builder {
        private final String moduleAlias;
        private final List<UiActionDefinition> actions = new java.util.ArrayList<>();
        private ModulePageDefinition page;
        private ViewDefinition customPageEditor;
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

        /** Declares form fields for a bespoke page without claiming a standard page template. */
        public Builder customPageEditor(Consumer<ViewDefinition.Builder> customizer) {
            if (page != null) throw new IllegalStateException("custom page editor cannot be combined with a page template");
            ViewDefinition.Builder builder = ViewDefinition.form("custom_page_editor");
            if (customizer != null) customizer.accept(builder);
            customPageEditor = builder.build();
            return this;
        }

        /** Selects a page-root template. Its supported slots are defined by the template type. */
        public Builder page(ModulePageDefinition definition) {
            if (definition == null) throw new IllegalArgumentException("page definition must not be null");
            if (customPageEditor != null) throw new IllegalStateException("page template cannot be combined with a custom page editor");
            page = definition;
            return this;
        }

        public ModuleUiDefinition build() {
            return new ModuleUiDefinition(moduleAlias, actions, page, customPageEditor, editorContributions);
        }
    }
}
