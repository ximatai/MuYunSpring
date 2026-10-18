package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.content.BusinessContentGenerationService;
import net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowOpinionContentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkflowOpinionContentWebControllerTest {
    private final WorkflowOpinionContentService service = mock(WorkflowOpinionContentService.class);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new WorkflowOpinionContentWebController(service)).build();

    @Test void currentAndArchivedPathsRetainTheirOwningInstanceAndEventCoordinates() throws Exception {
        mvc.perform(get("/workflow/runtime/instance/instance/events/event/content")).andExpect(status().isOk());
        mvc.perform(get("/workflow/history/instance/events/event/content/reading").param("language", "de"))
                .andExpect(status().isOk());
        verify(service).sources("instance", "event"); verify(service).read("instance", "event", "de");
        verifyNoMoreInteractions(service);
    }
    @Test void statusNeverStartsWorkAndOnlyAnExplicitPostCanRetry() throws Exception {
        when(service.generation("instance", "event", "de", false, false))
                .thenReturn(new BusinessContentGenerationService.Status("UNAVAILABLE", null, "MODEL_UNAVAILABLE", 0, null));
        mvc.perform(get("/workflow/runtime/instance/instance/events/event/content/reason/translations/de/generation"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("UNAVAILABLE"));
        mvc.perform(post("/workflow/history/instance/events/event/content/reason/translations/de/generation").param("retry", "true"))
                .andExpect(status().isOk());
        verify(service).generation("instance", "event", "de", false, false);
        verify(service).generation("instance", "event", "de", true, true);
        verifyNoMoreInteractions(service);
    }
    @Test void translationMaintenanceUsesItsOwnActionAndOriginalRevisionContract() throws Exception {
        var base = "/workflow/history/instance/events/event/content/reason/translations/de";
        mvc.perform(post(base + "/prepare")).andExpect(status().isOk());
        mvc.perform(post(base + "/review")).andExpect(status().isOk());
        mvc.perform(post(base + "/draft").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceRevisionId\":\"original\",\"sourceRevision\":1,\"expectedRevision\":null,\"text\":\"Versand anhalten.\"}"))
                .andExpect(status().isOk());
        verify(service).prepare("instance", "event", "de", PlatformAction.CONTENT_EDIT);
        verify(service).prepare("instance", "event", "de", PlatformAction.CONTENT_CONFIRM);
        verify(service).draft("instance", "event", "de",
                new BusinessContentTranslationService.Change("original", 1, null, "Versand anhalten.", null));
        verifyNoMoreInteractions(service);
    }
}
