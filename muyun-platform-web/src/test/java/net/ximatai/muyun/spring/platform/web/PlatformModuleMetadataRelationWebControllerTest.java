package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilitySnapshotService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldPropertySummaryService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldPropertySummary;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldPropertyKind;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelDeletionService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationRecordCountService;
import net.ximatai.muyun.spring.platform.metadata.ReferenceTargetFieldCatalog;
import net.ximatai.muyun.spring.platform.metadata.ReferenceTargetFieldCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformModuleMetadataRelationWebControllerTest {
    @Test
    void shouldExposeRelationScopedFieldPropertySummaries() {
        ModuleMetadataFieldPropertySummaryService summaries = mock(ModuleMetadataFieldPropertySummaryService.class);
        ModuleMetadataFieldPropertySummary expected = new ModuleMetadataFieldPropertySummary("field-1", "studentId", "string",
                MetadataFieldPropertyKind.MODULE_REFERENCE, 2, null, null);
        when(summaries.list("crm.customer", "main")).thenReturn(List.of(expected));
        PlatformModuleMetadataRelationWebController controller = new PlatformModuleMetadataRelationWebController(
                mock(ModuleMetadataOrchestrationService.class), mock(ModuleMetadataCapabilitySnapshotService.class), summaries,
                mock(ReferenceTargetFieldCatalogService.class), mock(MetadataModelDeletionService.class),
                mock(ModuleMetadataRelationRecordCountService.class));

        assertThat(controller.fieldProperties(request("crm.customer"), "main")).containsExactly(expected);
        verify(summaries).list("crm.customer", "main");
    }

    @Test
    void shouldRouteRelationScopedReferenceTargetFieldCatalog() {
        ReferenceTargetFieldCatalogService catalogService = mock(ReferenceTargetFieldCatalogService.class);
        ReferenceTargetFieldCatalog expected = new ReferenceTargetFieldCatalog("education.student", "student-meta",
                List.of(), List.of());
        when(catalogService.list("crm.customer", "main", "education.student", "student-meta")).thenReturn(expected);
        PlatformModuleMetadataRelationWebController controller = new PlatformModuleMetadataRelationWebController(
                mock(ModuleMetadataOrchestrationService.class), mock(ModuleMetadataCapabilitySnapshotService.class),
                mock(ModuleMetadataFieldPropertySummaryService.class), catalogService, mock(MetadataModelDeletionService.class),
                mock(ModuleMetadataRelationRecordCountService.class));

        assertThat(controller.referenceTargetFieldCatalog(request("crm.customer"), "main", "education.student", "student-meta"))
                .isSameAs(expected);
        verify(catalogService).list("crm.customer", "main", "education.student", "student-meta");
    }

    private MockHttpServletRequest request(String moduleAlias) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("moduleAlias", moduleAlias));
        return request;
    }
}
