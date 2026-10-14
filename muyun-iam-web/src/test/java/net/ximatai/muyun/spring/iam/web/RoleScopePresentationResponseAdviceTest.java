package net.ximatai.muyun.spring.iam.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.i18n.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RoleScopePresentationResponseAdviceTest {
    @Test void deliversAllDeclaredScopeLabelsAndPreservesSelectionKeysAndOriginals() {
        var descriptor = new RoleScopeSelectionFacade.RoleScopeSelectionDescriptor(List.of(
                RoleScopeSelectionFacade.DirectSelection.declared("iam.role.scope.platform", "platform", "平台角色"),
                RoleScopeSelectionFacade.DirectSelection.declared("iam.role.scope.current-tenant", "tenant:business", "当前租户")), List.of(
                RoleScopeSelectionFacade.Navigation.declared("iam.role.scope.tenants", "role-scope-tenants", RoleScopeSelectionFacade.ScopeLevel.TENANT, "租户"),
                RoleScopeSelectionFacade.Navigation.declared("iam.role.scope.organizations", "tenant:business", RoleScopeSelectionFacade.ScopeLevel.ORGANIZATION, "机构")));
        var beans = new DefaultListableBeanFactory();
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        beans.registerSingleton("catalog", catalog);
        beans.registerSingleton("resources", TextResources.load(getClass().getClassLoader(), catalog));
        var advice = new RoleScopePresentationResponseAdvice(beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class));
        var projected = (RoleScopeSelectionFacade.RoleScopeSelectionDescriptor) advice.project(descriptor);
        assertThat(projected.directSelections()).extracting(RoleScopeSelectionFacade.DirectSelection::selectionKey).containsExactly("platform", "tenant:business");
        assertThat(projected.directSelections()).extracting(RoleScopeSelectionFacade.DirectSelection::title).containsExactly("平台角色", "当前租户");
        assertThat(projected.directSelections()).extracting(selection -> selection.titleText().languages().get("en").text()).containsExactly("Platform roles", "Current tenant");
        assertThat(projected.navigations()).extracting(navigation -> navigation.titleText().languages().get("en").text()).containsExactly("Tenants", "Organizations");
        var json = new ObjectMapper().valueToTree(projected);
        assertThat(json.at("/directSelections/0/titleSource").isMissingNode()).isTrue();
        assertThat(json.at("/directSelections/0/titleText/source/sourceVersion").asText()).isEqualTo(TextReference.versionOf("平台角色"));
        assertThat(descriptor.directSelections().getFirst().titleText()).isNull();
    }

    @Test void leavesUnknownAndMismatchedLabelsUntranslated() {
        var source = TextReference.code("iam.role.scope.platform", "平台角色");
        var descriptor = new RoleScopeSelectionFacade.RoleScopeSelectionDescriptor(List.of(
                new RoleScopeSelectionFacade.DirectSelection("platform", "客户维护的名称", source, null),
                new RoleScopeSelectionFacade.DirectSelection("custom", "自定义范围")), List.of());
        var beans = new DefaultListableBeanFactory();
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        beans.registerSingleton("catalog", catalog);
        beans.registerSingleton("resources", TextResources.load(getClass().getClassLoader(), catalog));
        var advice = new RoleScopePresentationResponseAdvice(beans.getBeanProvider(TextResolver.class), beans.getBeanProvider(LanguageCatalog.class));
        var projected = (RoleScopeSelectionFacade.RoleScopeSelectionDescriptor) advice.project(descriptor);
        assertThat(projected.directSelections()).extracting(RoleScopeSelectionFacade.DirectSelection::title).containsExactly("客户维护的名称", "自定义范围");
        assertThat(projected.directSelections()).allMatch(selection -> selection.titleText() == null);
        var candidates = List.of(new RoleScopeSelectionFacade.Candidate("id", "租户原名", "alias", "tenant:id", false));
        assertThat(advice.project(candidates)).isSameAs(candidates);
    }
}
