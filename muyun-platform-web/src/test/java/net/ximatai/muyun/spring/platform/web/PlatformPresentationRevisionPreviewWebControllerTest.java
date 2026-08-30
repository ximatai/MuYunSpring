package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationVariantService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformPresentationRevisionPreviewWebControllerTest {
    @Test
    void shouldExposeRevisionScopedPreviewWithoutPersistingTheSubmittedTree() throws Exception {
        PresentationRevisionPreviewService previewService = mock(PresentationRevisionPreviewService.class);
        PresentationRevisionPreview result = new PresentationRevisionPreview("page-1", "variant-1", "revision-2",
                new ResolvedModuleUiDescriptor(null, "education.exam", ModuleKind.DYNAMIC, "考试", null, null,
                        null, null, null, null, null, null));
        when(previewService.preview("variant-1", "revision-2", new PresentationRevisionPreviewRequest("{}")))
                .thenReturn(result);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformPresentationRevisionWebController(
                mock(PlatformPresentationVariantService.class), previewService)).build();

        mvc.perform(post("/platform.presentation-variant/variant-1/revisions/revision-2/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"uiTreeJson\":\"{}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pageId").value("page-1"))
                .andExpect(jsonPath("$.uiDescriptor.moduleAlias").value("education.exam"));

        verify(previewService).preview("variant-1", "revision-2", new PresentationRevisionPreviewRequest("{}"));
    }

    @Test
    void shouldAuthorizePreviewAsRevisionScopedCustomAction() throws Exception {
        Method method = PlatformPresentationRevisionWebController.class.getMethod("preview", String.class,
                String.class, PresentationRevisionPreviewRequest.class);
        CustomActionEndpoint endpoint = method.getAnnotation(CustomActionEndpoint.class);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.value()).isEqualTo("previewUiComposition");
        assertThat(endpoint.level()).isEqualTo(PlatformActionLevel.RECORD);
        assertThat(endpoint.dataAuth()).isFalse();
    }
}
