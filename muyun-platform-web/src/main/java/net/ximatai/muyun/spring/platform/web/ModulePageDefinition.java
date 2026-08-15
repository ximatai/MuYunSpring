package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/**
 * A page-root declaration. A template owns the page structure and exposes only its documented slots.
 */
public sealed interface ModulePageDefinition permits FlatManagementPageDefinition, ListDetailCardPageDefinition {
    ModulePageTemplate template();

    static FlatManagementPageDefinition flatManagement(Consumer<FlatManagementPageDefinition.Builder> customizer) {
        FlatManagementPageDefinition.Builder builder = FlatManagementPageDefinition.builder();
        if (customizer != null) customizer.accept(builder);
        return builder.build();
    }

    static ListDetailCardPageDefinition listDetailCard(Consumer<ListDetailCardPageDefinition.Builder> customizer) {
        ListDetailCardPageDefinition.Builder builder = ListDetailCardPageDefinition.builder();
        if (customizer != null) customizer.accept(builder);
        return builder.build();
    }
}
