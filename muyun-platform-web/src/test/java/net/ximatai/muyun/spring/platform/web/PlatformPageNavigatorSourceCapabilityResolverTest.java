package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.ui.NavigatorSourceCapability;
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
}
