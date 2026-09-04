package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetApplyService;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPreview;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPreviewCommand;
import net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformMetadataModelChangeSetWebControllerTest {
    @Test
    void shouldExposeModuleModelPreviewAtDedicatedPath() throws Exception {
        MetadataModelChangeSetPreviewService previews = mock(MetadataModelChangeSetPreviewService.class);
        MetadataModelChangeSetPreview expected = new MetadataModelChangeSetPreview("education.exam", List.of(),
                List.of(new net.ximatai.muyun.spring.platform.metadata.MetadataChangeSetFieldImpact(
                        "ADD", "title", "title", false, "新增业务字段。")),
                List.of(new net.ximatai.muyun.spring.platform.metadata.MetadataChangeSetSchemaImpact(
                        "ADD_COLUMN", "public", "exam", "title", "新增业务字段。")),
                List.of(new net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetOrderImpact(
                        "REORDER_FIELDS", "main", null, List.of("field-title"), "调整实体字段顺序。")),
                List.of(), List.of(), "fingerprint", null);
        when(previews.preview(eq("education.exam"), any())).thenReturn(expected);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformMetadataModelChangeSetWebController(previews,
                mock(MetadataModelChangeSetApplyService.class))).build();

        mvc.perform(post("/platform.module/education.exam/metadata-model/change-set-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relationDrafts\":[],\"relationOrders\":[],\"fieldOrders\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldImpacts[0].fieldName").value("title"))
                .andExpect(jsonPath("$.schemaImpacts[0].operation").value("ADD_COLUMN"))
                .andExpect(jsonPath("$.orderImpacts[0].operation").value("REORDER_FIELDS"));

        verify(previews).preview(eq("education.exam"), any(MetadataModelChangeSetPreviewCommand.class));
    }
}
