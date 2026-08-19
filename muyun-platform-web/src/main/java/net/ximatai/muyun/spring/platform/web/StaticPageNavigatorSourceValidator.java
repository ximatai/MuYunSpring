package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import net.ximatai.muyun.spring.platform.ui.PageCapabilityContractValidator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validates static navigator sources eagerly. Sources not declared by the static catalog may be
 * published dynamic modules, so their capability is validated by the unified runtime resolver.
 */
final class StaticPageNavigatorSourceValidator {
    private StaticPageNavigatorSourceValidator() {
    }

    static void validate(List<StaticModuleDefinition> definitions) {
        Map<String, StaticModuleDefinition> modules = definitions.stream()
                .collect(Collectors.toMap(StaticModuleDefinition::moduleAlias, Function.identity()));
        for (StaticModuleDefinition definition : definitions) {
            validatePageCapabilities(definition);
            PageNavigatorDefinition navigator = navigator(definition.uiDefinition());
            if (navigator == null) continue;
            for (PageNavigatorLevelDefinition level : navigator.levels()) {
                NavigatorSourceCapability required = level.kind() == PageNavigatorKind.TREE
                        ? NavigatorSourceCapability.REFERENCE_TREE
                        : NavigatorSourceCapability.REFERENCE_QUERY;
                StaticModuleDefinition source = modules.get(level.sourceModuleAlias());
                if (source != null && !source.navigatorSourceCapabilities().contains(required)) {
                    throw new IllegalStateException("navigator source capability is unavailable: page="
                            + definition.moduleAlias() + ", level=" + level.key() + ", source="
                            + level.sourceModuleAlias() + ", required=" + required);
                }
                validateNavigatorManagement(definition, level, source);
            }
        }
    }

    /**
     * Validates only facts which are owned by the page's own module. Cross-module sources are
     * deliberately handled below, where the static catalog can prove their projection, actions
     * and editor contracts without relying on a request-time failure.
     */
    private static void validatePageCapabilities(StaticModuleDefinition definition) {
        ModulePageDefinition page = definition.uiDefinition() == null ? null : definition.uiDefinition().page();
        if (page == null) return;
        Set<String> actionCodes = definition.actions().stream()
                .map(action -> action.actionCode())
                .collect(Collectors.toUnmodifiableSet());
        PageCapabilityContractValidator.validate(definition.moduleAlias(), page.template().name(),
                traits(page).stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                definition.capabilities().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()), actionCodes);
    }

    private static void validateNavigatorManagement(StaticModuleDefinition pageDefinition,
                                                    PageNavigatorLevelDefinition level,
                                                    StaticModuleDefinition source) {
        PageNavigatorManagementDefinition management = level.management();
        if (management == null) return;
        if (source == null) {
            throw new IllegalStateException("navigator management source contract cannot be proved from the static catalog: page="
                    + pageDefinition.moduleAlias() + ", level=" + level.key() + ", source="
                    + level.sourceModuleAlias());
        }
        Set<String> sourceActionCodes = source.actions().stream()
                .map(action -> action.actionCode())
                .collect(Collectors.toUnmodifiableSet());
        for (PageNavigatorManagementAction action : management.actions()) {
            PlatformAction required = switch (action) {
                case CREATE -> PlatformAction.CREATE;
                case UPDATE -> PlatformAction.UPDATE;
                case DELETE -> PlatformAction.DELETE;
            };
            requireActions(source, sourceActionCodes, "navigator management " + pageDefinition.moduleAlias()
                    + "." + level.key(), required);
        }
        ModuleUiDefinition sourceUi = source.uiDefinition();
        if (sourceUi == null) {
            throw new IllegalStateException("navigator management source has no UI editor: page="
                    + pageDefinition.moduleAlias() + ", level=" + level.key() + ", source=" + source.moduleAlias());
        }
        String editorSurface = management.editorSurface();
        if (editorSurface != null) {
            boolean exists = sourceUi.editorSurfaces().stream()
                    .anyMatch(surface -> editorSurface.equals(surface.key()));
            if (!exists) {
                throw new IllegalStateException("navigator management editor surface is unavailable: page="
                        + pageDefinition.moduleAlias() + ", level=" + level.key() + ", source="
                        + source.moduleAlias() + ", editor=" + editorSurface);
            }
            return;
        }
        boolean defaultEditor = sourceUi.defaultEditor() != null
                || (sourceUi.page() != null && detail(sourceUi.page()).editor() != null);
        if (!defaultEditor) {
            throw new IllegalStateException("navigator management source has no default editor: page="
                    + pageDefinition.moduleAlias() + ", level=" + level.key() + ", source=" + source.moduleAlias());
        }
    }

    private static void requireActions(StaticModuleDefinition definition, Set<String> available,
                                       String consumer, PlatformAction... required) {
        for (PlatformAction action : required) {
            if (!available.contains(action.code())) {
                throw new IllegalStateException("page action is unavailable: module=" + definition.moduleAlias()
                        + ", consumer=" + consumer + ", required=" + action.code());
            }
        }
    }

    private static Set<PageTrait> traits(ModulePageDefinition page) {
        return switch (page) {
            case FlatManagementPageDefinition value -> value.traits().values();
            case ListDetailCardPageDefinition value -> value.traits().values();
            case TreeManagementPageDefinition value -> value.traits().values();
        };
    }

    private static PageDetailDefinition detail(ModulePageDefinition page) {
        return switch (page) {
            case FlatManagementPageDefinition value -> value.detail();
            case ListDetailCardPageDefinition value -> value.detail();
            case TreeManagementPageDefinition value -> value.detail();
        };
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
