package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformPageNavigatorSourceCapabilityResolverTest {
    @Test
    void shouldExposeDynamicReferenceProjectionCapabilitiesFromPublishedDescriptor() {
        DynamicRecordService dynamicRecordService = mock(DynamicRecordService.class);
        when(dynamicRecordService.describe("catalog.category")).thenReturn(new DynamicModuleDescriptor(
                "catalog.category", "分类", "category", List.of(),
                List.of(new DynamicEntityDescriptor("category", "分类", Set.of("REFERENCE", "TREE"),
                        List.of(), List.of(), List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of()));
        PlatformPageNavigatorSourceCapabilityResolver resolver = new PlatformPageNavigatorSourceCapabilityResolver(
                new StaticModuleDefinitionCatalog(List.of()), dynamicRecordService);

        assertThat(resolver.supports("catalog.category", false)).isTrue();
        assertThat(resolver.supports("catalog.category", true)).isTrue();
        when(dynamicRecordService.describe("catalog.category.missing"))
                .thenThrow(new net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException("not published"));
        assertThat(resolver.supports("catalog.category.missing", false)).isFalse();
    }

    @Test
    void shouldOnlyProveManagementForStaticSourcesWithActionsAndEditor() {
        ModuleUiDefinition ui = ModuleUiDefinition.builder("catalog.directory")
                .editors(editors -> editors.defaultEditor(editor -> editor.field("title")))
                .build();
        StaticModuleDefinition source = StaticModuleDefinition.builder("catalog", "catalog.directory", "目录")
                .actions(List.of(
                        StaticModuleActionDefinition.platformAction(PlatformAction.CREATE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.UPDATE),
                        StaticModuleActionDefinition.platformAction(PlatformAction.DELETE)))
                .uiDefinition(ui)
                .build();
        PlatformPageNavigatorSourceCapabilityResolver resolver = new PlatformPageNavigatorSourceCapabilityResolver(
                new StaticModuleDefinitionCatalog(List.of(source)));

        assertThat(resolver.supportsManagement("catalog.directory", null)).isTrue();
        assertThat(resolver.supportsManagement("catalog.missing", null)).isFalse();
    }

    @Test
    void staticSupportRequiresTheExactRegisteredPostReferenceEndpoint() throws Exception {
        StaticModuleDefinition source = StaticModuleDefinition.builder("catalog", "catalog.category", "分类").build();
        RegisteredWebEndpointCatalog endpoints = new RegisteredWebEndpointCatalog();
        endpoints.register(endpoint("catalog.category", "/catalog.category/navigator/reference/query",
                RequestMethod.POST));
        PlatformPageNavigatorSourceCapabilityResolver resolver = new PlatformPageNavigatorSourceCapabilityResolver(
                new StaticModuleDefinitionCatalog(List.of(source)), null, endpoints);

        assertThat(resolver.supports("catalog.category", false)).isTrue();
        assertThat(resolver.supports("catalog.category", true)).isFalse();
    }

    @Test
    void staticSupportRejectsGetAndCustomPrefixMappings() throws Exception {
        StaticModuleDefinition source = StaticModuleDefinition.builder("catalog", "catalog.category", "分类").build();
        RegisteredWebEndpointCatalog endpoints = new RegisteredWebEndpointCatalog();
        endpoints.register(endpoint("catalog.category", "/catalog.category/custom/navigator/reference/query",
                RequestMethod.POST));
        endpoints.register(endpoint("catalog.category", "/catalog.category/navigator/reference/query",
                RequestMethod.GET));
        PlatformPageNavigatorSourceCapabilityResolver resolver = new PlatformPageNavigatorSourceCapabilityResolver(
                new StaticModuleDefinitionCatalog(List.of(source)), null, endpoints);

        assertThat(resolver.supports("catalog.category", false)).isFalse();
    }

    @Test
    void validateStaticSourcesRejectsAnUnregisteredSourceEndpoint() {
        StaticModuleDefinition source = StaticModuleDefinition.builder("catalog", "catalog.category", "分类").build();
        StaticModuleDefinition page = StaticModuleDefinition.builder("catalog", "catalog.page", "页面")
                .uiDefinition(ModuleUiDefinition.builder("catalog.page")
                        .page(PageTemplates.listDetailCard(definition -> definition
                                .navigator(navigator -> navigator.level("category", level -> level
                                        .microList("catalog.category", "分类", "选择分类")))
                                .list(list -> list.fields(fields -> fields.field("title")))
                                .detail(detail -> detail.editor(editor -> editor.field("title")))))
                        .build())
                .build();
        PlatformPageNavigatorSourceCapabilityResolver resolver = new PlatformPageNavigatorSourceCapabilityResolver(
                new StaticModuleDefinitionCatalog(List.of(source, page)), null, new RegisteredWebEndpointCatalog());

        assertThatThrownBy(resolver::validateStaticSources)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source endpoint is unavailable")
                .hasMessageContaining("catalog.category");
    }

    private static RegisteredWebEndpoint endpoint(String alias, String path, RequestMethod method) throws Exception {
        ResolvedWebEndpoint definition = new ResolvedWebEndpoint(alias + ".reference." + method,
                alias, "reference", "reference", PlatformAction.REFERENCE, method, path,
                ResolvedWebEndpoint.Source.STATIC_EXPLICIT);
        Object handler = new Object();
        return new RegisteredWebEndpoint(definition, RequestMappingInfo.paths(path).methods(method).build(),
                handler, Object.class.getMethod("toString"));
    }
}
