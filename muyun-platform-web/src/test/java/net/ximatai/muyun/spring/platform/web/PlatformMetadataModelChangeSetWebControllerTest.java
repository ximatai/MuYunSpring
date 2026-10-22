package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.platform.metadata.FieldSpecService;
import net.ximatai.muyun.spring.platform.metadata.MetadataChangeSetValidationIssue;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

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
                List.of(), List.of(), "fingerprint",
                new net.ximatai.muyun.spring.platform.metadata.MetadataModelChangeSetPlan(List.of(), List.of(), List.of()));
        when(previews.preview(eq("education.exam"), any())).thenReturn(expected);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformMetadataModelChangeSetWebController(previews,
                mock(MetadataModelChangeSetApplyService.class))).build();

        mvc.perform(post("/platform.module/education.exam/metadata-model/change-set-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relationDrafts\":[],\"relationOrders\":[],\"fieldOrders\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fieldImpacts[0].fieldName").value("title"))
                .andExpect(jsonPath("$.schemaImpacts[0].operation").value("ADD_COLUMN"))
                .andExpect(jsonPath("$.orderImpacts[0].operation").value("REORDER_FIELDS"))
                .andExpect(jsonPath("$.plan").doesNotExist());

        verify(previews).preview(eq("education.exam"), any(MetadataModelChangeSetPreviewCommand.class));
    }
    @Test
    void shouldProjectRealDeclaredValidationFailureFromBackendResourcesWithoutChangingDiagnosticFacts() throws Exception {
        var specs = mock(FieldSpecService.class);
        when(specs.requireFieldType("retired_spec")).thenCallRealMethod();
        var failure = org.junit.jupiter.api.Assertions.assertThrows(
                PlatformException.class,
                () -> specs.requireFieldType("retired_spec"));
        var issue = MetadataChangeSetValidationIssue.failure(
                "INVALID_FIELD_DRAFT", "amount", failure);
        var expected = new MetadataModelChangeSetPreview("education.exam", List.of(), List.of(), List.of(issue), "fingerprint", null);
        var previews = mock(MetadataModelChangeSetPreviewService.class);
        when(previews.preview(eq("education.exam"), any())).thenReturn(expected);
        var beans = new DefaultListableBeanFactory();
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        beans.registerSingleton("languages", catalog);
        beans.registerSingleton("texts", TextResources.load(getClass().getClassLoader(), catalog));
        var mapper = new ObjectMapper();
        var projection = new PlatformPresentationResponseAdvice(mapper,
                beans.getBeanProvider(TextResolver.class),
                beans.getBeanProvider(LanguageCatalog.class));
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformMetadataModelChangeSetWebController(previews,
                        mock(MetadataModelChangeSetApplyService.class)))
                .setControllerAdvice(projection)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        for (String tag : List.of("en", "zh-CN")) {
            try (var language = LanguageContext.use(tag)) {
                mvc.perform(post("/platform.module/education.exam/metadata-model/change-set-preview")
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.errors[0].code").value("INVALID_FIELD_DRAFT"))
                        .andExpect(jsonPath("$.errors[0].subject").value("amount"))
                        .andExpect(jsonPath("$.errors[0].severity").value("ERROR"))
                        .andExpect(jsonPath("$.errors[0].message").value("Field spec requires existing type: retired_spec"))
                        .andExpect(jsonPath("$.errors[0].messageArgs.value1").value("retired_spec"))
                        .andExpect(jsonPath("$.errors[0].messageText.source.sourceId").value(issue.textSource().sourceId()))
                        .andExpect(jsonPath("$.errors[0].messageText.languages.en.text").value("Field spec requires existing type: retired_spec"))
                        .andExpect(jsonPath("$.errors[0].messageText.languages.zh-CN.text").value("字段规格不存在：retired_spec"));
            }
        }
    }

}
