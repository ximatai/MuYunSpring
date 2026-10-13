package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;

import java.util.List;
import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;

public record ResolvedModuleUiDescriptor(String schemaVersion,
                                         String moduleAlias,
                                         ModuleKind moduleKind,
                                         String title,
                                         List<ResolvedUiActionDescriptor> actions,
                                         String recordLabelField,
                                         List<ResolvedFileReferenceFieldDescriptor> fileReferences,
                                         ResolvedModulePageDescriptor page,
                                         ResolvedViewDescriptor defaultEditor,
                                         List<ResolvedEditorSurfaceDescriptor> editorSurfaces,
                                         List<ResolvedPageDetailEditorContribution> editorContributions,
                                         List<ResolvedDetailRelationDescriptor> detailRelations,
                                         Map<String, TextReference> sourceTexts) {
    public static final String SCHEMA_VERSION = "module-ui.v7";

    public ResolvedModuleUiDescriptor(String schemaVersion, String moduleAlias, ModuleKind moduleKind, String title,
                                     List<ResolvedUiActionDescriptor> actions, String recordLabelField,
                                     List<ResolvedFileReferenceFieldDescriptor> fileReferences,
                                     ResolvedModulePageDescriptor page, ResolvedViewDescriptor defaultEditor,
                                     List<ResolvedEditorSurfaceDescriptor> editorSurfaces,
                                     List<ResolvedPageDetailEditorContribution> editorContributions,
                                     List<ResolvedDetailRelationDescriptor> detailRelations) {
        this(schemaVersion, moduleAlias, moduleKind, title, actions, recordLabelField, fileReferences, page,
                defaultEditor, editorSurfaces, editorContributions, detailRelations, Map.of());
    }

    public ResolvedModuleUiDescriptor {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        title = title == null || title.isBlank() ? null : title.trim();
        actions = actions == null ? List.of() : List.copyOf(actions);
        recordLabelField = recordLabelField == null || recordLabelField.isBlank() ? null : recordLabelField.trim();
        fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
        editorSurfaces = editorSurfaces == null ? List.of() : List.copyOf(editorSurfaces);
        if (editorSurfaces.stream().map(ResolvedEditorSurfaceDescriptor::key).distinct().count()
                != editorSurfaces.size()) {
            throw new IllegalArgumentException("duplicate resolved editor surface key");
        }
        editorContributions = editorContributions == null ? List.of() : List.copyOf(editorContributions);
        detailRelations = detailRelations == null ? List.of() : List.copyOf(detailRelations);
        sourceTexts = sourceTexts == null ? Map.of() : Map.copyOf(sourceTexts);
    }

    public ResolvedModuleUiDescriptor withFileReferences(List<ResolvedFileReferenceFieldDescriptor> values) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions,
                recordLabelField, values, page, defaultEditor, editorSurfaces, editorContributions, detailRelations, sourceTexts);
    }

    public ResolvedModuleUiDescriptor withPage(ResolvedModulePageDescriptor value) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions,
                recordLabelField, fileReferences, value, defaultEditor, editorSurfaces, editorContributions, detailRelations, sourceTexts);
    }

    public ResolvedModuleUiDescriptor withDetailRelations(List<ResolvedDetailRelationDescriptor> values) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions,
                recordLabelField, fileReferences, page, defaultEditor, editorSurfaces, editorContributions, values, sourceTexts);
    }

    public ResolvedModuleUiDescriptor withEditorContributions(
            List<ResolvedPageDetailEditorContribution> values) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions,
                recordLabelField, fileReferences, page, defaultEditor, editorSurfaces, values, detailRelations, sourceTexts);
    }

    public ResolvedModuleUiDescriptor withEditors(ResolvedModulePageDescriptor resolvedPage,
                                                   ResolvedViewDescriptor resolvedDefaultEditor,
                                                   List<ResolvedEditorSurfaceDescriptor> resolvedEditorSurfaces) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions,
                recordLabelField, fileReferences, resolvedPage, resolvedDefaultEditor, resolvedEditorSurfaces,
                editorContributions, detailRelations, sourceTexts);
    }

    public ResolvedModuleUiDescriptor withSourceTexts(Map<String, TextReference> values) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, actions, recordLabelField,
                fileReferences, page, defaultEditor, editorSurfaces, editorContributions, detailRelations, values);
    }

}
