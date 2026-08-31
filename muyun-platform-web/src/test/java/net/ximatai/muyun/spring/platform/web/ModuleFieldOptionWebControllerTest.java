package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import net.ximatai.muyun.spring.platform.web.ActionEndpointInterceptor;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.RequestTraceWebFilter;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.option.OptionItem;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ModuleFieldOptionWebControllerTest {
    @Test
    void shouldForwardOptionQueryParameters() throws Exception {
        ModuleFieldOptionService service = mock(ModuleFieldOptionService.class);
        when(service.options("iam.dictionary", null, "category", false, "root"))
                .thenReturn(List.of(new OptionItem("gender", "性别", true, 10, "root")));

        mvc(service).perform(get("/platform.module/iam.dictionary/fields/category/options")
                        .param("enabledOnly", "false")
                        .param("parentCode", "root"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("gender"))
                .andExpect(jsonPath("$[0].title").value("性别"))
                .andExpect(jsonPath("$[0].parentCode").value("root"));

        verify(service).options("iam.dictionary", null, "category", false, "root");
    }

    @Test
    void shouldDefaultToEnabledOptionsOnly() throws Exception {
        ModuleFieldOptionService service = mock(ModuleFieldOptionService.class);
        when(service.options("iam.employee", null, "gender", true, null)).thenReturn(List.of());

        mvc(service).perform(get("/platform.module/iam.employee/fields/gender/options"))
                .andExpect(status().isOk());

        verify(service).options("iam.employee", null, "gender", true, null);
    }

    @Test
    void shouldReturnNotFoundForUnknownModuleOrField() throws Exception {
        ModuleFieldOptionService service = mock(ModuleFieldOptionService.class);
        when(service.options(anyString(), org.mockito.ArgumentMatchers.isNull(), eq("unknown"), eq(true), eq(null))).thenThrow(new PlatformException(
                PlatformErrorCodes.RESOURCE_NOT_FOUND,
                404,
                "option field not found"
        ));

        mvc(service).perform(get("/platform.module/iam.ghost/fields/unknown/options"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.RESOURCE_NOT_FOUND))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldDeclareMenuActionForOptionsEndpoint() throws Exception {
        Method method = ModuleFieldOptionWebController.class.getMethod(
                "options", String.class, String.class, String.class, boolean.class, String.class);
        ActionEndpoint endpoint = method.getAnnotation(ActionEndpoint.class);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.value()).isEqualTo(PlatformAction.MENU);
    }

    @Test
    void shouldForwardOptionalDynamicEntityAlias() throws Exception {
        ModuleFieldOptionService service = mock(ModuleFieldOptionService.class);
        when(service.options("education.exam", "exam_participant", "attendanceStatus", false, null))
                .thenReturn(List.of(new OptionItem("ATTENDED", "已参加", true, 10, null)));

        mvc(service).perform(get("/platform.module/education.exam/fields/attendanceStatus/options")
                        .param("entityAlias", "exam_participant")
                        .param("enabledOnly", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("已参加"));

        verify(service).options("education.exam", "exam_participant", "attendanceStatus", false, null);
    }

    @Test
    void shouldRejectOptionsWhenMenuActionIsDeniedByInterceptor() throws Exception {
        ModuleFieldOptionService service = mock(ModuleFieldOptionService.class);
        ActionExecutionPolicyService deniedPolicy = context -> {
            throw new PlatformAccessDeniedException("denied");
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ModuleFieldOptionWebController(service))
                .addInterceptors(new ActionEndpointInterceptor(deniedPolicy, new ActionEndpointContextResolver()))
                .addFilters(new RequestTraceWebFilter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(get("/platform.module/iam.employee/fields/gender/options"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.ACCESS_DENIED))
                .andExpect(jsonPath("$.status").value(403));
    }

    private MockMvc mvc(ModuleFieldOptionService service) {
        return MockMvcBuilders.standaloneSetup(new ModuleFieldOptionWebController(service))
                .addFilters(new RequestTraceWebFilter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();
    }
}
