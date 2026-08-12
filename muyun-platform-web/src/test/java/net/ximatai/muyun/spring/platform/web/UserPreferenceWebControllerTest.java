package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.UserPreference;
import net.ximatai.muyun.spring.platform.ui.UserPreferenceService;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserPreferenceWebControllerTest {
    @Test
    void shouldReadSaveAndDeleteCurrentUserPreference() throws Exception {
        UserPreferenceService service = mock(UserPreferenceService.class);
        MockMvc mvc = mvc(service);
        UserPreference preference = preference();
        when(service.currentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth"))
                .thenReturn(preference);
        when(service.saveCurrentUserPreference(eq(PlatformUiClientType.WEB),
                eq("workbench.menu-display-depth"), eq("{\"depth\":3}"))).thenReturn(preference);

        mvc.perform(post("/platform.user-preference/workbench.menu-display-depth")
                        .contentType("application/json")
                        .content("{\"clientType\":\"WEB\",\"valueJson\":\"{\\\"depth\\\":3}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valueJson").value("{\"depth\":3}"))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.tenantId").doesNotExist());
        mvc.perform(get("/platform.user-preference/workbench.menu-display-depth"))
                .andExpect(status().isOk());
        mvc.perform(delete("/platform.user-preference/workbench.menu-display-depth"))
                .andExpect(status().isNoContent());

        verify(service).deleteCurrentUserPreference(PlatformUiClientType.WEB, "workbench.menu-display-depth");
    }

    @Test
    void shouldReturnNoContentWhenCurrentUserHasNoPreference() throws Exception {
        UserPreferenceService service = mock(UserPreferenceService.class);
        MockMvc mvc = mvc(service);

        mvc.perform(get("/platform.user-preference/workbench.menu-display-depth"))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldRejectJsonNullRequestBodyAsBadRequest() throws Exception {
        UserPreferenceService service = mock(UserPreferenceService.class);

        mvc(service).perform(post("/platform.user-preference/workbench.menu-display-depth")
                        .contentType("application/json")
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(service);
    }

    private MockMvc mvc(UserPreferenceService service) {
        return MockMvcBuilders.standaloneSetup(new UserPreferenceWebController(service))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant-a"))))
                .build();
    }

    private UserPreference preference() {
        UserPreference preference = new UserPreference();
        preference.setId("pref-1");
        preference.setUserId("user-1");
        preference.setClientType("WEB");
        preference.setPreferenceKey("workbench.menu-display-depth");
        preference.setValueJson("{\"depth\":3}");
        return preference;
    }
}
