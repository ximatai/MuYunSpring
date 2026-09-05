package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.ui.PageCapabilityContractValidator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validates static page-owned contracts before the registered endpoint catalog exists. */
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
                validateNavigatorManagement(definition, level, modules.get(level.sourceModuleAlias()));
            }
        }
    }

    /** Cross-module read endpoint facts are validated after endpoint registration by the resolver. */
    private static void validatePageCapabilities(StaticModuleDefinition definition) {
        ModulePageDefinition page = definition.uiDefinition() == null ? null : definition.uiDefinition().page();
        if (page == null) return;
        Set<String> actionCodes = definition.actions().stream()
                .map(action -> action.actionCode())
                .collect(Collectors.toUnmodifiableSet());
        PageCapabilityContractValidator.validate(definition.moduleAlias(), page.template().name(),
                traits(page).stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()),
                definition.capabilities().stream().map(Enum::name).collect(Collectors.toUnmodifiableSet()), actionCodes,
                page instanceof TreeManagementPageDefinition tree && tree.treeResource() != null);
        if (page instanceof TreeManagementPageDefinition tree && tree.treeResource() != null) {
            validateTreeResource(definition, actionCodes, tree.treeResource());
        }
    }

    private static void validateTreeResource(StaticModuleDefinition definition, Set<String> actionCodes,
                                             PageTreeResourceDefinition resource) {
        if (!definition.entities().isEmpty()
                && definition.entities().getFirst().alias().equals(resource.resource())) {
            throw new IllegalStateException("tree resource must be a contributed resource, not the page module tree: module="
                    + definition.moduleAlias() + ", resource=" + resource.resource());
        }
        PageDetailEditorContribution contribution = definition.uiDefinition().editorContributions().stream()
                .filter(candidate -> candidate.resource().equals(resource.resource()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("tree resource requires an editor contribution: module="
                        + definition.moduleAlias() + ", resource=" + resource.resource()));
        boolean hasScopeField = contribution.editor().fields().stream()
                .anyMatch(field -> field.fieldRef().fieldName().equals(resource.scopeField()));
        if (!hasScopeField) {
            throw new IllegalStateException("tree resource editor must declare its scope field: module="
                    + definition.moduleAlias() + ", resource=" + resource.resource() + ", field="
                    + resource.scopeField());
        }
        for (PlatformAction action : List.of(PlatformAction.CREATE, PlatformAction.VIEW, PlatformAction.UPDATE,
                PlatformAction.DELETE, PlatformAction.QUERY, PlatformAction.TREE, PlatformAction.SORT)) {
            String actionCode = resource.resource() + "_" + action.code();
            if (!actionCodes.contains(actionCode)) {
                throw new IllegalStateException("tree resource action is unavailable: module="
                        + definition.moduleAlias() + ", resource=" + resource.resource() + ", required=" + actionCode);
            }
        }
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
        requireActions(source, sourceActionCodes, "navigator management " + pageDefinition.moduleAlias()
                + "." + level.key(), PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE);
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

    static PageNavigatorDefinition navigator(ModuleUiDefinition definition) {
        if (definition == null || definition.page() == null) return null;
        return switch (definition.page()) {
            case FlatManagementPageDefinition page -> page.navigator();
            case ListDetailCardPageDefinition page -> page.navigator();
            case TreeManagementPageDefinition page -> page.navigator();
        };
    }
}
