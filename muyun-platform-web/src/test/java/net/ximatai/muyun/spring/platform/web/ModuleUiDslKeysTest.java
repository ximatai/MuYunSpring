package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleUiDslKeysTest {
    @Test
    void shouldKeepDefaultViewCodesAsNamedDslConstants() {
        assertThat(ModuleUiViewCodes.DEFAULT_LIST).isEqualTo("default_list");
        assertThat(ModuleUiViewCodes.DEFAULT_FORM).isEqualTo("default_form");
        assertThat(ViewDefinition.list().build().viewCode()).isEqualTo(ModuleUiViewCodes.DEFAULT_LIST);
        assertThat(ViewDefinition.form().build().viewCode()).isEqualTo(ModuleUiViewCodes.DEFAULT_FORM);
        assertThat(ViewDefinition.list(ModuleUiViewCode.of("audit_list")).build().viewCode())
                .isEqualTo("audit_list");
    }

    @Test
    void shouldUseTypedDslKeysForFieldsNavigatorAndBindings() {
        ModuleUiField tenantId = ModuleUiField.of("tenantId");
        ModuleUiField categoryId = ModuleUiField.of("categoryId");
        ModuleUiNavigatorKey tenant = ModuleUiNavigatorKey.of("tenant");
        ModuleUiNavigatorKey category = ModuleUiNavigatorKey.of("category");

        PageNavigatorDefinition navigator = new PageNavigatorDefinition.Builder()
                .level(tenant, level -> level.microList("iam.tenant", "租户", "搜索租户"))
                .level(category, level -> level.tree("iam.position_category", "分类", "搜索分类"))
                .filterListBySession(ModuleUiBindingKey.of("tenantId"), tenantId).prefillFormFromSession(ModuleUiBindingKey.of("tenantId"), tenantId).constrainMutationsFromSession(ModuleUiBindingKey.of("tenantId"), tenantId)
                .bindNavigatorToNavigator(tenant, category, tenantId)
                .filterListByNavigator(category, categoryId).prefillFormFromNavigator(category, categoryId)
                .build();

        assertThat(navigator.levels()).extracting(PageNavigatorLevelDefinition::key)
                .containsExactly("tenant", "category");
        assertThat(navigator.contextBindings()).extracting(PageContextBindingDefinition::targetKey)
                .contains("tenantId", "categoryId");
        assertThat(navigator.contextBindings()).filteredOn(binding -> binding.target() == PageContextTarget.LIST_QUERY
                        && binding.source() == PageContextSource.NAVIGATOR)
                .extracting(PageContextBindingDefinition::navigatorListQueryMode)
                .containsExactly(NavigatorListQueryMode.REQUIRED_SCOPE);
    }

    @Test
    void shouldAllowNavigatorListBindingToBeAnOptionalFilter() {
        PageNavigatorDefinition navigator = new PageNavigatorDefinition.Builder()
                .level("project", level -> level.microList("mr.project", "项目", "搜索项目"))
                .filterListByNavigator("project", "projectId", NavigatorListQueryMode.OPTIONAL_FILTER).prefillFormFromNavigator("project", "projectId")
                .build();

        assertThat(navigator.contextBindings()).filteredOn(binding -> binding.target() == PageContextTarget.LIST_QUERY)
                .extracting(PageContextBindingDefinition::navigatorListQueryMode)
                .containsExactly(NavigatorListQueryMode.OPTIONAL_FILTER);
        assertThat(navigator.contextBindings()).filteredOn(binding -> binding.target() == PageContextTarget.FORM_DEFAULT)
                .extracting(PageContextBindingDefinition::navigatorListQueryMode)
                .containsExactly((NavigatorListQueryMode) null);
    }

    @Test
    void shouldKeepNavigatorConsumersExplicitWhilePreservingTheirIndependentTargets() {
        PageNavigatorDefinition navigator = new PageNavigatorDefinition.Builder()
                .level("organization", level -> level.tree("iam.organization", "机构", "搜索机构"))
                .filterListByNavigator("organization", "organizationId", NavigatorListQueryMode.REQUIRED_SCOPE)
                .prefillFormFromNavigator("organization", "organizationId")
                .filterListBySession("tenantId", "tenantId")
                .prefillFormFromSession("tenantId", "tenantId")
                .constrainMutationsFromSession("tenantId", "tenantId")
                .build();

        assertThat(navigator.contextBindings()).extracting(PageContextBindingDefinition::target)
                .containsExactly(PageContextTarget.LIST_QUERY, PageContextTarget.FORM_DEFAULT,
                        PageContextTarget.LIST_QUERY, PageContextTarget.FORM_DEFAULT,
                        PageContextTarget.MUTATION_CONSTRAINT);
    }

    @Test
    void shouldRejectInvalidTypedDslKeysAtDeclarationTime() {
        assertThatThrownBy(() -> ModuleUiField.of("bad-field"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleUiNavigatorKey.of("bad-key"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleUiViewCode.of("BadView"))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
