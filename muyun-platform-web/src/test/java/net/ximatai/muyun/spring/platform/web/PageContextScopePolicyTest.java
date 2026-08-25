package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageContextScopePolicyTest {
    @Test
    void shouldFailClosedWhenRequiredNavigatorListScopeIsMissing() {
        PageContextBindingDefinition binding = PageContextBindingDefinition.navigatorList(
                "scheme", "schemeId", NavigatorListQueryMode.REQUIRED_SCOPE);

        assertThatThrownBy(() -> PageContextScopePolicy.criteria(List.of(binding), Map.of(), false))
                .hasMessage("Page navigator scope is required: scheme");
    }

    @Test
    void shouldFailClosedWhenNavigatorReferenceDependsOnAnUnselectedParent() {
        PageContextBindingDefinition binding = PageContextBindingDefinition.navigatorToNavigator(
                "tenant", "project", "tenantId");

        assertThatThrownBy(() -> PageContextScopePolicy.criteria(List.of(binding), Map.of(), true))
                .hasMessage("Page navigator scope is required: tenant");
    }

    @Test
    void shouldLeaveOptionalNavigatorListFilterAbsent() {
        PageContextBindingDefinition binding = PageContextBindingDefinition.navigatorList(
                "project", "projectId", NavigatorListQueryMode.OPTIONAL_FILTER);

        assertThat(PageContextScopePolicy.criteria(List.of(binding), Map.of(), false).isEmpty()).isTrue();
        assertThat(PageContextScopePolicy.recordScopeBindings(List.of(binding))).isEmpty();
    }
}
