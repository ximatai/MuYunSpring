package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.i18n.TextReference;

/** A module API document visible to the current caller. */
public record OpenApiModuleCatalogItem(
        String moduleAlias,
        String title,
        String moduleKind,
        String documentPath,
        TextReference titleSource
) {
    public OpenApiModuleCatalogItem(String moduleAlias, String title, String moduleKind, String documentPath) {
        this(moduleAlias, title, moduleKind, documentPath, null);
    }
}
