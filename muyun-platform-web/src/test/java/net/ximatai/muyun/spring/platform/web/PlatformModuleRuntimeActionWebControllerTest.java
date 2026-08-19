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
