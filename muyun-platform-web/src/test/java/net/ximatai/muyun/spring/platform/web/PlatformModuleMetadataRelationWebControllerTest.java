package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPublishResult;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilitySnapshotService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldPropertySummaryService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldPropertySummary;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldPropertyKind;
import net.ximatai.muyun.spring.platform.metadata.ReferenceTargetFieldCatalog;
import net.ximatai.muyun.spring.platform.metadata.ReferenceTargetFieldCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformModuleMetadataRelationWebControllerTest {
    @Test
    void shouldRouteChangeSetPreviewWithoutPublishingIt() {
        MetadataRelationChangeSetPreviewService changeSetPreviewService = mock(MetadataRelationChangeSetPreviewService.class);
        MetadataRelationChangeSetPreviewCommand command = new MetadataRelationChangeSetPreviewCommand(7, Map.of(), List.of());
        MetadataRelationChangeSetPreview expected = new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 7,
                Set.of(), List.of(), List.of(), List.of(), List.of(), "fingerprint");
        when(changeSetPreviewService.preview("crm.customer", "main", command)).thenReturn(expected);
        PlatformModuleMetadataRelationWebController controller = new PlatformModuleMetadataRelationWebController(
                mock(ModuleMetadataOrchestrationService.class), mock(ModuleMetadataCapabilitySnapshotService.class),
                changeSetPreviewService,
                mock(MetadataRelationChangeSetApplyService.class));

        assertThat(controller.changeSetPreview(request("crm.customer"), "main", command)).isSameAs(expected);
        verify(changeSetPreviewService).preview("crm.customer", "main", command);
    }

    @Test
    void shouldAcceptReferenceProjectionMappingsAsJsonArrayInChangeSetPreviewHttpContract() throws Exception {
        String json = """
                {"expectedMetadataVersion":7,"capabilitySelections":{},"fieldDrafts":[{
                  "operation":"ADD","field":{"fieldName":"studentId","columnName":"student_id","fieldSpecAlias":"string"},
                  "property":{"kind":"MODULE_REFERENCE","referenceConfig":{
                    "targetModuleAlias":"education.student","targetKeyField":"studentNo","targetLabelField":"name",
                    "projectionMappings":["grade:studentGrade","name:studentDisplayName"]}}
                }]}""";

        MetadataRelationChangeSetPreviewService previews = mock(MetadataRelationChangeSetPreviewService.class);
        MetadataRelationChangeSetPreview expected = new MetadataRelationChangeSetPreview("crm.customer", "main", "metadata-1", 7,
                Set.of(), List.of(), List.of(), List.of(), List.of(), "fingerprint");
        when(previews.preview(eq("crm.customer"), eq("main"), any())).thenReturn(expected);
        PlatformModuleMetadataRelationWebController controller = new PlatformModuleMetadataRelationWebController(
                mock(ModuleMetadataOrchestrationService.class), mock(ModuleMetadataCapabilitySnapshotService.class), previews,
                mock(MetadataRelationChangeSetApplyService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(post("/platform.module/crm.customer/metadata-relations/main/change-set-preview")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<MetadataRelationChangeSetPreviewCommand> captured =
                org.mockito.ArgumentCaptor.forClass(MetadataRelationChangeSetPreviewCommand.class);
        verify(previews).preview(eq("crm.customer"), eq("main"), captured.capture());
        assertThat(captured.getValue().fieldDrafts()).singleElement().satisfies(field -> {
            assertThat(field.property().referenceConfig().projectionMappings())
                    .containsExactly("grade:studentGrade", "name:studentDisplayName");
            assertThat(field.property().referenceConfig().toConfig().getProjectionMappings())
                    .isEqualTo("grade:studentGrade,name:studentDisplayName");
        });
    }

    @Test
    void shouldRouteChangeSetApplyToAtomicService() {
        MetadataRelationChangeSetApplyService applyService = mock(MetadataRelationChangeSetApplyService.class);
        MetadataRelationChangeSetApplyCommand command = new MetadataRelationChangeSetApplyCommand(
                new MetadataRelationChangeSetPreviewCommand(7, Map.of(), List.of()), "fingerprint");
        MetadataRelationChangeSetPublishResult expected = new MetadataRelationChangeSetPublishResult(null, null, List.of("crm.customer"));
        when(applyService.apply("crm.customer", "main", command)).thenReturn(expected);
        PlatformModuleMetadataRelationWebController controller = new PlatformModuleMetadataRelationWebController(
                mock(ModuleMetadataOrchestrationService.class), mock(ModuleMetadataCapabilitySnapshotService.class),
                mock(MetadataRelationChangeSetPreviewService.class), applyService);

        assertThat(controller.applyChangeSet(request("crm.customer"), "main", command)).isSameAs(expected);
        verify(applyService).apply("crm.customer", "main", command);
    }

    @Test
    void shouldExposeRelationScopedFieldPropertySummaries() {
        ModuleMetadataFieldPropertySummaryService summaries = mock(ModuleMetadataFieldPropertySummaryService.class);
        ModuleMetadataFieldPropertySummary expected = new ModuleMetadataFieldPropertySummary("field-1", "studentId", "string",
                MetadataFieldPropertyKind.MODULE_REFERENCE, 2, null, null);
        when(summaries.list("crm.customer", "main")).thenReturn(List.of(expected));
        PlatformModuleMetadataRelationWebController controller = new PlatformModuleMetadataRelationWebController(
                mock(ModuleMetadataOrchestrationService.class), mock(ModuleMetadataCapabilitySnapshotService.class),
                mock(MetadataRelationChangeSetPreviewService.class), mock(MetadataRelationChangeSetApplyService.class), summaries);

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
                mock(MetadataRelationChangeSetPreviewService.class), mock(MetadataRelationChangeSetApplyService.class),
                mock(ModuleMetadataFieldPropertySummaryService.class), catalogService);

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
