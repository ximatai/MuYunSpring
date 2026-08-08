package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.module.ModuleKind;

import java.util.List;

public record ResolvedModuleUiDescriptor(String schemaVersion,
                                         String moduleAlias,
                                         ModuleKind moduleKind,
                                         String title,
                                         List<ResolvedViewDescriptor> views,
                                         List<ResolvedUiActionDescriptor> actions,
                                         String recordLabelField,
                                         List<ResolvedFileReferenceFieldDescriptor> fileReferences) {
    public static final String SCHEMA_VERSION = "module-ui.v2";

    public ResolvedModuleUiDescriptor {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion.trim();
        moduleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        title = title == null || title.isBlank() ? null : title.trim();
        views = views == null ? List.of() : List.copyOf(views);
        actions = actions == null ? List.of() : List.copyOf(actions);
        recordLabelField = recordLabelField == null || recordLabelField.isBlank() ? null : recordLabelField.trim();
        fileReferences = fileReferences == null ? List.of() : List.copyOf(fileReferences);
    }

    public ResolvedModuleUiDescriptor(String schemaVersion, String moduleAlias, ModuleKind moduleKind, String title,
                                      List<ResolvedViewDescriptor> views, List<ResolvedUiActionDescriptor> actions,
                                      String recordLabelField) {
        this(schemaVersion, moduleAlias, moduleKind, title, views, actions, recordLabelField, List.of());
    }

    public ResolvedModuleUiDescriptor(String moduleAlias,
                                      List<ResolvedViewDescriptor> views) {
        this(SCHEMA_VERSION, moduleAlias, null, null, views, List.of(), null, List.of());
    }

    public ResolvedModuleUiDescriptor withFileReferences(List<ResolvedFileReferenceFieldDescriptor> values) {
        return new ResolvedModuleUiDescriptor(schemaVersion, moduleAlias, moduleKind, title, views, actions,
                recordLabelField, values);
    }

}
