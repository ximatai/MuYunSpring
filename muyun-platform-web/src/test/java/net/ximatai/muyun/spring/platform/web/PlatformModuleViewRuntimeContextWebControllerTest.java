package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.RequestTraceWebFilter;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformModuleViewRuntimeContextWebControllerTest {
    @Test
    void shouldExposeTargetRuntimeContextForViewWithoutMenuContext() throws Exception {
        PlatformModuleRuntimeContextService service = mock(PlatformModuleRuntimeContextService.class);
        when(service.context("crm.customer")).thenReturn(mock(PlatformModuleRuntimeContext.class));
        AtomicReference<ActionExecutionContext> authorized = new AtomicReference<>();
        ActionExecutionPolicyService viewOnlyPolicy = context -> {
            if (context.platformAction() != PlatformAction.VIEW) {
                throw new PlatformAccessDeniedException("only view is granted");
            }
            authorized.set(context);
        };
        MockMvc mvc = mvc(service, viewOnlyPolicy);

        mvc.perform(get("/platform.module/crm.customer/view-context"))
                .andExpect(status().isOk());

        assertThat(authorized.get()).isNotNull();
        assertThat(authorized.get().moduleAlias()).isEqualTo("crm.customer");
        assertThat(authorized.get().platformAction()).isEqualTo(PlatformAction.VIEW);
        verify(service).context("crm.customer");
    }

    @Test
    void shouldRequireViewActionForReferenceDetailContext() throws Exception {
        Method method = PlatformModuleViewRuntimeContextWebController.class.getMethod("context", String.class);
        ActionEndpoint endpoint = method.getAnnotation(ActionEndpoint.class);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.value()).isEqualTo(PlatformAction.VIEW);
    }

    @Test
    void shouldRejectCallerWithoutViewPermission() throws Exception {
        PlatformModuleRuntimeContextService service = mock(PlatformModuleRuntimeContextService.class);
        ActionExecutionPolicyService deniedPolicy = context -> {
            throw new PlatformAccessDeniedException("view permission denied");
        };
        MockMvc mvc = mvc(service, deniedPolicy);

        mvc.perform(get("/platform.module/crm.customer/view-context"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.ACCESS_DENIED));
    }

    private MockMvc mvc(PlatformModuleRuntimeContextService service,
                         ActionExecutionPolicyService policy) {
        return MockMvcBuilders.standaloneSetup(new PlatformModuleViewRuntimeContextWebController(service))
                .addInterceptors(new ActionEndpointInterceptor(policy, new ActionEndpointContextResolver()))
                .addFilters(new RequestTraceWebFilter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();
    }
}
