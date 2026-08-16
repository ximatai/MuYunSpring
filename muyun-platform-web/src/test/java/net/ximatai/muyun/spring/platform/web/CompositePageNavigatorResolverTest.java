package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.ModuleKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompositePageNavigatorResolverTest {
    @Test
    void intersectsPoliciesAndRemovesDescendantsWhoseSourceWasRejected() {
        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder("iam.position")
                .page(PageTemplates.listDetailCard(card -> card
                        .navigator(navigator -> navigator
                                .level("tenant", level -> level.microList("iam.tenant", "租户", null))
                                .level("category", level -> level.tree("iam.position_category", "岗位分类", null))
                                .level("position", level -> level.microList("iam.position", "岗位", null))
                                .bindNavigatorToNavigator("tenant", "category", "tenantId")
                                .bindNavigatorToNavigator("category", "position", "categoryId"))
                        .list(list -> list.fields(fields -> fields.field("code")))
                        .detail(detail -> detail.editor(editor -> editor.field("code")))))
                .build()).page();
        PageNavigatorResolutionContext context = new PageNavigatorResolutionContext("iam.position", ModuleKind.STATIC,
                null, page);

        PageNavigatorResolver tenantPolicy = ignored -> Set.of("tenant", "category", "position");
        PageNavigatorResolver dataScopePolicy = ignored -> Set.of("category", "position");

        assertThat(new CompositePageNavigatorResolver(List.of(tenantPolicy, dataScopePolicy))
                .visibleLevelKeys(context)).isEmpty();
    }

    @Test
    void preservesAnAutoHiddenLevelAsAnUpstreamContextProvider() {
        ResolvedModulePageDescriptor page = ModuleUiDescriptorCompiler.compile(ModuleUiDefinition.builder("iam.position")
                .page(PageTemplates.listDetailCard(card -> card
                        .navigator(navigator -> navigator
                                .level("tenant", level -> level.microList("iam.tenant", "租户", null)
                                        .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE))
                                .level("category", level -> level.tree("iam.position_category", "岗位分类", null))
                                .bindNavigatorToNavigator("tenant", "category", "tenantId"))
                        .list(list -> list.fields(fields -> fields.field("code")))
                        .detail(detail -> detail.editor(editor -> editor.field("code")))))
                .build()).page();

        assertThat(new CompositePageNavigatorResolver(List.of()).visibleLevelKeys(
                new PageNavigatorResolutionContext("iam.position", ModuleKind.STATIC, null, page)))
                .containsExactlyInAnyOrder("tenant", "category");
    }
}
