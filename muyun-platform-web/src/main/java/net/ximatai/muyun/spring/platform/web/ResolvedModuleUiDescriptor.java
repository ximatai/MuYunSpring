package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.ModuleKind;

import java.util.List;

public record ResolvedModuleUiDescriptor(String schemaVersion,
                                         String moduleAlias,
                                         ModuleKind moduleKind,
                                         String title,
                                         List<ResolvedUiActionDescriptor> actions,
                                         String recordLabelField,
                                         List<ResolvedFileReferenceFieldDescriptor> fileReferences,
                                         ResolvedModulePageDescriptor page,
                                         ResolvedViewDescriptor customPageEditor,
                                         List<ResolvedPageDetailEditorContribution> editorContributions) {
    public static final String SCHEMA_VERSION = "module-ui.v4";

    public ResolvedModuleUiDescriptor {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        title = title == null || title.isBlank() ? null : title.trim();
        actions = actions == null ? List.of() : List.copyOf(actions);
        recordLabelField = recordLabelField == null || recordLabelField.isBlank() ? null : recordLabelField.trim();
        fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
        editorContributions = editorContributions == null ? List.of() : List.copyOf(editorContributions);
    }

    public ResolvedModuleUiDescriptor withFileReferences(List<ResolvedFileReferenceFieldDescriptor> values) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions,
                recordLabelField, values, page, customPageEditor, editorContributions);
    }

    public ResolvedModuleUiDescriptor withPage(ResolvedModulePageDescriptor value) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions,
                recordLabelField, fileReferences, value, customPageEditor, editorContributions);
    }

}
