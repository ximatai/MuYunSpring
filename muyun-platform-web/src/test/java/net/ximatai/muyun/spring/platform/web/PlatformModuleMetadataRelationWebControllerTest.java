package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPublishResult;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataCapabilitySnapshotService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    private MockHttpServletRequest request(String moduleAlias) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("moduleAlias", moduleAlias));
        return request;
    }
}
