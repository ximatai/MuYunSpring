package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformModuleRuntimeActionWebControllerTest {
    @Test
    void serializesSingleAndTypedBatchReasonProjectionsThroughTheHttpConverter() throws Exception {
        var source = net.ximatai.muyun.spring.common.i18n.TextReference.code("platform.managed-record.edit-unavailable", "平台托管记录不可编辑");
        var availability = new PlatformRecordActionAvailability("record", List.of(
                new PlatformRecordActionAvailability.Action("update", false, source.originalText(), source)));
        var service = mock(PlatformRecordActionAvailabilityService.class);
        when(service.recordActions("platform.module", "record")).thenReturn(availability);
        when(service.recordActions("platform.module", List.of("record"))).thenReturn(List.of(availability));
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var catalog = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        beans.registerSingleton("languages", catalog);
        beans.registerSingleton("texts", net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var advice = new PlatformPresentationResponseAdvice(mapper,
                beans.getBeanProvider(net.ximatai.muyun.spring.common.i18n.TextResolver.class),
                beans.getBeanProvider(net.ximatai.muyun.spring.common.i18n.LanguageCatalog.class));
        var mvc = MockMvcBuilders.standaloneSetup(new PlatformModuleRuntimeActionWebController(service))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), advice).build();
        mvc.perform(get("/platform.module/actions/record")).andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].reason").value(source.originalText()))
                .andExpect(jsonPath("$.actions[0].reasonText.languages.en.text").value("Platform-managed records cannot be edited"));
        mvc.perform(post("/platform.module/actions/availability").contentType("application/json").content("{\"recordIds\":[\"record\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].recordId").value("record"))
                .andExpect(jsonPath("$[0].actions[0].available").value(false))
                .andExpect(jsonPath("$[0].actions[0].reasonText.languages.en.text").value("Platform-managed records cannot be edited"));
    }

    @Test
    void shouldExposeRecordActionAvailabilityByModuleAliasAndRecordId() throws Exception {
        PlatformRecordActionAvailabilityService service = mock(PlatformRecordActionAvailabilityService.class);
        when(service.recordActions("iam.user", "platform.user.super_admin"))
                .thenReturn(new PlatformRecordActionAvailability(
                        "platform.user.super_admin",
                        List.of(new PlatformRecordActionAvailability.Action(
                                "resetPassword",
                                false,
                                "cannot administrate current user's password"
                        ))
                ));
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new PlatformModuleRuntimeActionWebController(service))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(get("/{moduleAlias}/actions/{recordId}", "iam.user", "platform.user.super_admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId").value("platform.user.super_admin"))
                .andExpect(jsonPath("$.actions[0].actionCode").value("resetPassword"))
                .andExpect(jsonPath("$.actions[0].available").value(false))
                .andExpect(jsonPath("$.actions[0].reason").value("cannot administrate current user's password"));

        verify(service).recordActions("iam.user", "platform.user.super_admin");
    }

    @Test
    void shouldExposeBoundedBatchRecordActionAvailability() throws Exception {
        PlatformRecordActionAvailabilityService service = mock(PlatformRecordActionAvailabilityService.class);
        when(service.recordActions("platform.module", List.of("platform.module", "iam.user")))
                .thenReturn(List.of(
                        new PlatformRecordActionAvailability("platform.module",
                                List.of(new PlatformRecordActionAvailability.Action("update", false,
                                        "平台托管记录不可编辑"))),
                        new PlatformRecordActionAvailability("iam.user",
                                List.of(new PlatformRecordActionAvailability.Action("update", true, null)))
                ));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformModuleRuntimeActionWebController(service))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(post("/{moduleAlias}/actions/availability", "platform.module")
                        .contentType("application/json")
                        .content("{\"recordIds\":[\"platform.module\",\"iam.user\"]}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$[0].recordId").value("platform.module"))
                .andExpect(jsonPath("$[0].actions[0].available").value(false))
                .andExpect(jsonPath("$[1].recordId").value("iam.user"));

        verify(service).recordActions("platform.module", List.of("platform.module", "iam.user"));
    }
}
