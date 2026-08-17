package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validates that static page DSL navigator levels target explicitly exposed reference projections. */
final class StaticPageNavigatorSourceValidator {
    private StaticPageNavigatorSourceValidator() {
    }

    static void validate(List<StaticModuleDefinition> definitions) {
        Map<String, StaticModuleDefinition> modules = definitions.stream()
                .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));
        for (StaticModuleDefinition definition : definitions) {
            PageNavigatorDefinition navigator = navigator(definition.uiDefinition());
            if (navigator == null) continue;
            for (PageNavigatorLevelDefinition level : navigator.levels()) {
                NavigatorSourceCapability required = level.kind() == PageNavigatorKind.TREE
                        ? NavigatorSourceCapability.REFERENCE_TREE
                        : NavigatorSourceCapability.REFERENCE_QUERY;
                StaticModuleDefinition source = modules.get(level.sourceModuleAlias());
                if (source == null || !source.navigatorSourceCapabilities().contains(required)) {
                    throw new IllegalStateException("navigator source capability is unavailable: page="
                            + definition.moduleAlias() + ", level=" + level.key() + ", source="
                            + level.sourceModuleAlias() + ", required=" + required);
                }
            }
        }
    }

    private static PageNavigatorDefinition navigator(ModuleUiDefinition definition) {
        if (definition == null || definition.page() == null) return null;
        return switch (definition.page()) {
            case FlatManagementPageDefinition page -> page.navigator();
            case ListDetailCardPageDefinition page -> page.navigator();
            case TreeManagementPageDefinition page -> page.navigator();
        };
    }
}
