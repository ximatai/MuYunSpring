package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticPageCapabilityValidatorTest {
    @Test
    void shouldRejectTreeManagementWithoutTreeAbilityAndProjectionAction() {
        StaticModuleDefinition definition = module("catalog.category", treePage(), Set.of(), List.of());

        assertThatThrownBy(() -> StaticPageNavigatorSourceValidator.validate(List.of(definition)))
                .hasMessageContaining("consumer=TREE_MANAGEMENT")
                .hasMessageContaining("required=TREE");
    }

    @Test
    void shouldRejectTraitsWithoutTheirOwnedActions() {
        StaticModuleDefinition definition = module("catalog.category", listPage(), Set.of(EntityCapability.ENABLE),
                actions(PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE));

        assertThatThrownBy(() -> StaticPageNavigatorSourceValidator.validate(List.of(definition)))
                .hasMessageContaining("consumer=ENABLED_STATUS")
                .hasMessageContaining("required=enable");
    }

    @Test
    void shouldRejectManageableNavigatorWhenSourceDoesNotExposeRequestedAction() {
        StaticModuleDefinition source = module("catalog.directory", sourcePage(), Set.of(),
                actions(PlatformAction.CREATE, PlatformAction.QUERY));
        StaticModuleDefinition page = module("catalog.item", navigatorPage(null), Set.of(),
                actions(PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE));

        assertThatThrownBy(() -> StaticPageNavigatorSourceValidator.validate(List.of(source, page)))
                .hasMessageContaining("consumer=navigator management catalog.item.directory")
                .hasMessageContaining("required=");
    }

    @Test
    void shouldRejectManageableNavigatorWhenNamedSourceEditorIsMissing() {
        StaticModuleDefinition source = module("catalog.directory", sourcePage(), Set.of(),
                actions(PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE, PlatformAction.QUERY));
        StaticModuleDefinition page = module("catalog.item", navigatorPage("quick"), Set.of(),
                actions(PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE));

        assertThatThrownBy(() -> StaticPageNavigatorSourceValidator.validate(List.of(source, page)))
                .hasMessageContaining("navigator management editor surface is unavailable")
                .hasMessageContaining("editor=quick");
    }

    @Test
    void shouldAcceptDeclaredTreeTraitsAndManageableSourceContract() {
        StaticModuleDefinition source = module("catalog.directory", sourcePageWithSurface(), Set.of(),
                actions(PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE, PlatformAction.QUERY));
        StaticModuleDefinition tree = module("catalog.category", treePage(), Set.of(EntityCapability.TREE),
                actions(PlatformAction.TREE, PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE));
        StaticModuleDefinition page = module("catalog.item", navigatorPage("quick"), Set.of(),
                actions(PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE));

        assertThatCode(() -> StaticPageNavigatorSourceValidator.validate(List.of(source, tree, page)))
                .doesNotThrowAnyException();
    }

    private static StaticModuleDefinition module(String alias, ModuleUiDefinition ui, Set<EntityCapability> capabilities,
                                                 List<StaticModuleActionDefinition> actions) {
        return StaticModuleDefinition.builder("catalog", alias, alias)
                .capabilities(capabilities)
                .actions(actions)
                .navigatorSourceCapabilities(Set.of(NavigatorSourceCapability.REFERENCE_QUERY))
                .uiDefinition(ui)
                .build();
    }

    private static List<StaticModuleActionDefinition> actions(PlatformAction... actions) {
        return Arrays.stream(actions).map(StaticModuleActionDefinition::platformAction).toList();
    }

    private static ModuleUiDefinition treePage() {
        return ModuleUiDefinition.builder("catalog.category")
                .page(PageTemplates.treeManagement(page -> page
                        .detail(detail -> detail.editor(editor -> editor.field("title")))
                        .traits(traits -> traits.standardCrud())))
                .build();
    }

    private static ModuleUiDefinition listPage() {
        return ModuleUiDefinition.builder("catalog.category")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))
                        .traits(traits -> traits.standardCrud().enabledStatus())))
                .build();
    }

    private static ModuleUiDefinition sourcePage() {
        return ModuleUiDefinition.builder("catalog.directory")
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))
                        .traits(traits -> { })))
                .build();
    }

    private static ModuleUiDefinition sourcePageWithSurface() {
        return ModuleUiDefinition.builder("catalog.directory")
                .editors(editors -> editors.editor("quick", form -> form.field("title")))
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))
                        .traits(traits -> { })))
                .build();
    }

    private static ModuleUiDefinition navigatorPage(String editorSurface) {
        return ModuleUiDefinition.builder("catalog.item")
                .page(PageTemplates.listDetailCard(page -> page
                        .navigator(navigator -> navigator.level("directory", level -> level
                                .microList("catalog.directory", "目录", null)
                                .manageable(editorSurface, PageNavigatorManagementAction.CREATE,
                                        PageNavigatorManagementAction.UPDATE, PageNavigatorManagementAction.DELETE)))
                        .list(list -> list.fields(fields -> fields.field("title")))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))
                        .traits(traits -> traits.standardCrud())))
                .build();
    }
}
