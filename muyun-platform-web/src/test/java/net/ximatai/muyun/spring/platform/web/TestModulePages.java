package net.ximatai.muyun.spring.platform.web;

import java.util.function.Consumer;

/** Compact page-root fixtures for read-projection tests. */
final class TestModulePages {
    private TestModulePages() {
    }

    static ModuleUiDefinition listDetail(String moduleAlias, Consumer<ViewDefinition.Builder> list) {
        return ModuleUiDefinition.builder(moduleAlias)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(slot -> slot.fields(list))
                        .detail(detail -> detail.editor(editor -> editor.field("title")))))
                .build();
    }
}
