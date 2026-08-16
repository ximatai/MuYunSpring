package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/** Stable factory for the only supported page-root skeletons. */
public final class PageTemplates {
    private PageTemplates() {
    }

    public static FlatManagementPageDefinition flatManagement(Consumer<FlatManagementPageDefinition.Builder> customizer) {
        return ModulePageDefinition.flatManagement(customizer);
    }

    public static ListDetailCardPageDefinition listDetailCard(Consumer<ListDetailCardPageDefinition.Builder> customizer) {
        return ModulePageDefinition.listDetailCard(customizer);
    }

    public static TreeManagementPageDefinition treeManagement(Consumer<TreeManagementPageDefinition.Builder> customizer) {
        return ModulePageDefinition.treeManagement(customizer);
    }
}
