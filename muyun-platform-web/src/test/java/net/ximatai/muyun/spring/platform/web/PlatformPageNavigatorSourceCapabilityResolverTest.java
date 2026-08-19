package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(resolver.capabilities("catalog.category"))
                .containsExactlyInAnyOrder(NavigatorSourceCapability.REFERENCE_QUERY,
                        NavigatorSourceCapability.REFERENCE_TREE);
    }

    @Test
    void shouldOnlyProveManagementForStaticSourcesWithActionsAndEditor() {
        ModuleUiDefinition ui = ModuleUiDefinition.builder("catalog.directory")
                .editors(editors -> editors.defaultEditor(editor -> editor.field("title")))
                .build();
        StaticModuleDefinition source = StaticModuleDefinition.builder("catalog", "catalog.directory", "目录")
                .navigatorSourceCapabilities(Set.of(NavigatorSourceCapability.REFERENCE_QUERY))
                .actions(List.of(StaticModuleActionDefinition.platformAction(PlatformAction.CREATE)))
                .uiDefinition(ui)
                .build();
        PlatformPageNavigatorSourceCapabilityResolver resolver = new PlatformPageNavigatorSourceCapabilityResolver(
                new StaticModuleDefinitionCatalog(List.of(source)));

        assertThat(resolver.supportsManagement("catalog.directory", Set.of("CREATE"), null)).isTrue();
        assertThat(resolver.supportsManagement("catalog.directory", Set.of("UPDATE"), null)).isFalse();
        assertThat(resolver.supportsManagement("catalog.missing", Set.of("CREATE"), null)).isFalse();
    }
}
