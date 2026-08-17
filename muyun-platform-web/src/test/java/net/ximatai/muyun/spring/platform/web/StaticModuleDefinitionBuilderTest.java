package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StaticModuleDefinitionBuilderTest {
    @Test
    void builderAppliesStableDefaultsAndCapabilityImplications() {
        StaticModuleDefinition definition = StaticModuleDefinition
                .builder("sales", "sales.contract", " Contract ")
                .capabilities(Set.of(EntityCapability.APPROVAL))
                .build();

        assertThat(definition.title()).isEqualTo("Contract");
        assertThat(definition.entryType()).isEqualTo(ModuleEntryType.MODULE);
        assertThat(definition.capabilities())
                .containsExactlyInAnyOrder(EntityCapability.APPROVAL, EntityCapability.WORKFLOW);
        assertThat(definition.actions()).isEmpty();
    }

    @Test
    void toBuilderPreservesDefinitionAndSupportsNamedChanges() {
        StaticModuleDefinition original = StaticModuleDefinition
                .builder("sales", "sales.contract", "Contract")
                .build();

        StaticModuleDefinition changed = original.toBuilder()
                .parentModuleAlias("sales.root")
                .build();

        assertThat(changed.applicationAlias()).isEqualTo(original.applicationAlias());
        assertThat(changed.moduleAlias()).isEqualTo(original.moduleAlias());
        assertThat(changed.parentModuleAlias()).isEqualTo("sales.root");
    }

    @Test
    void navigatorPagesRequireTheSourceModuleToExposeTheMatchingReferenceProjection() {
        StaticModuleDefinition source = StaticModuleDefinition.builder("sales", "sales.customer", "客户").build();
        StaticModuleDefinition page = StaticModuleDefinition.builder("sales", "sales.contract", "合同")
                .uiDefinition(ModuleUiDefinition.builder("sales.contract")
                        .page(PageTemplates.listDetailCard(definition -> definition
                                .navigator(navigator -> navigator.level("customer", level -> level
                                        .microList("sales.customer", "客户", "搜索客户")))
                                .list(list -> list.fields(fields -> fields.field("title")))
                                .detail(detail -> detail.editor(form -> form.field("title")))))
                        .build())
                .build();

        assertThatThrownBy(() -> StaticPageNavigatorSourceValidator.validate(java.util.List.of(source, page)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("page=sales.contract")
                .hasMessageContaining("level=customer")
                .hasMessageContaining("source=sales.customer")
                .hasMessageContaining("required=REFERENCE_QUERY");

        assertThatCode(() -> StaticPageNavigatorSourceValidator.validate(java.util.List.of(
                source.toBuilder().navigatorSourceCapabilities(Set.of(NavigatorSourceCapability.REFERENCE_QUERY)).build(),
                page))).doesNotThrowAnyException();
    }
}
