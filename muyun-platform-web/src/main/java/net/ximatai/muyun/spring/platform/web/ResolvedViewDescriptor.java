package net.ximatai.muyun.spring.platform.web;

import java.util.List;

public record ResolvedViewDescriptor(String viewCode,
                                     ModuleViewKind viewKind,
                                     ModuleUiClientType clientType,
                                     String title,
                                     List<ResolvedViewFieldDescriptor> fields,
                                     String sourceUiConfigId,
                                     List<ResolvedFormGroupDescriptor> formGroups) {
    public ResolvedViewDescriptor {
        if (viewCode == null || viewCode.isBlank()) {
            throw new IllegalArgumentException("view code must not be blank");
        }
        viewCode = viewCode.trim();
        if (viewKind == null) {
            throw new IllegalArgumentException("view kind must not be null");
        }
        clientType = clientType == null ? ModuleUiClientType.WEB : clientType;
        title = title == null || title.isBlank() ? null : title.trim();
        fields = fields == null ? List.of() : List.copyOf(fields);
        sourceUiConfigId = sourceUiConfigId == null || sourceUiConfigId.isBlank() ? null : sourceUiConfigId.trim();
        formGroups = formGroups == null ? List.of() : List.copyOf(formGroups);
        if (!formGroups.isEmpty() && viewKind != ModuleViewKind.FORM) {
            throw new IllegalArgumentException("form groups are only supported by form views: " + viewCode);
        }
    }

    public ResolvedViewDescriptor(String viewCode, ModuleViewKind viewKind, ModuleUiClientType clientType, String title,
                                  List<ResolvedViewFieldDescriptor> fields) {
        this(viewCode, viewKind, clientType, title, fields, null, null);
    }

}
