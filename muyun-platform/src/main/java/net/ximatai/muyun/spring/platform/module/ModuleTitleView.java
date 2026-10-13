package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.common.i18n.TextReference;

/** Directory display fact shared by pages and workflow; never a business-record title. */
public record ModuleTitleView(String moduleAlias, String title, TextReference titleSource) {
    public static ModuleTitleView of(PlatformModule module) {
        return new ModuleTitleView(module.getAlias(), module.getTitle() == null || module.getTitle().isBlank()
                ? module.getAlias() : module.getTitle(), module.titleSource());
    }
    public static TextReference declaredSource(String moduleAlias, String title) {
        return title == null || title.isBlank() ? null : TextReference.code("module/" + segment(moduleAlias) + "/title", title);
    }
    public static TextReference configuredSource(String moduleAlias, String title) {
        return title == null || title.isBlank() ? null : TextReference.configured("config/module/" + segment(moduleAlias) + "/title", title);
    }
    private static String segment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Module identity is required");
        return value.replace("~", "~0").replace("/", "~1");
    }
}
