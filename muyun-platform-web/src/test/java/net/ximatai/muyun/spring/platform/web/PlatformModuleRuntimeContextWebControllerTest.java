package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import net.ximatai.muyun.spring.platform.web.ActionEndpointInterceptor;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.RequestTraceWebFilter;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlatformModuleRuntimeContextWebControllerTest {
    @Test
    void shouldExposeRuntimeContextByDottedModuleAlias() throws Exception {
        PlatformModuleRuntimeContextService service = mock(PlatformModuleRuntimeContextService.class);
        when(service.context("iam.organization")).thenReturn(new PlatformModuleRuntimeContext(
                "iam.organization",
                "组织管理",
                ModuleKind.STATIC,
                ModuleEntryType.ROUTE,
                "/iam/organizations",
                null,
                "organization",
                Set.of(EntityCapability.CRUD, EntityCapability.TREE),
                Set.of("crud", "tree"),
                List.of(),
                ModuleUiDefinition.builder("iam.organization")
                        .page(PageTemplates.listDetailCard(page -> page
                                .list(list -> list.fields(fields -> fields.field("title", field -> field.label("组织名称"))))
                                .detail(detail -> detail.editor(fields -> fields.field("title")))
                                .traits(traits -> traits.standardCrud())))
                        .build()
        ));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/platform.module/iam.organization/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleAlias").value("iam.organization"))
                .andExpect(jsonPath("$.entryRoute").value("/iam/organizations"))
                .andExpect(jsonPath("$.abilities[?(@ == 'tree')]").exists())
                .andExpect(jsonPath("$.uiDefinition").doesNotExist())
                .andExpect(jsonPath("$.uiDescriptor.schemaVersion").value(ResolvedModuleUiDescriptor.SCHEMA_VERSION))
                .andExpect(jsonPath("$.uiDescriptor.moduleAlias").value("iam.organization"))
                .andExpect(jsonPath("$.uiDescriptor.page.template").value("LIST_DETAIL_CARD"))
                .andExpect(jsonPath("$.uiDescriptor.page.list.fields.viewCode").value("default_list"))
                .andExpect(jsonPath("$.uiDescriptor.page.list.fields.fields[0].fieldRef.fieldName").value("title"))
                .andExpect(jsonPath("$.uiDescriptor.page.list.fields.fields[0].columnName").doesNotExist())
                .andExpect(jsonPath("$.uiDescriptor.page.list.fields.fields[0].tableName").doesNotExist())
                .andExpect(jsonPath("$.uiDescriptor.page.list.fields.fields[0].sql").doesNotExist())
                .andExpect(jsonPath("$.sourceKind").doesNotExist());
    }

    @Test
    void shouldReturnUnifiedErrorWhenRuntimeContextNotFound() throws Exception {
        PlatformModuleRuntimeContextService service = mock(PlatformModuleRuntimeContextService.class);
        when(service.context("iam.ghost")).thenThrow(new PlatformException(
                PlatformErrorCodes.RESOURCE_NOT_FOUND,
                404,
                "module runtime context not found: iam.ghost"
        ));
        MockMvc mvc = mvc(service);

        mvc.perform(get("/platform.module/iam.ghost/context"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.RESOURCE_NOT_FOUND))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("module runtime context not found: iam.ghost"));
    }

    @Test
    void shouldRequireMenuActionForRuntimeContextEndpoint() throws Exception {
        Method method = PlatformModuleRuntimeContextWebController.class.getMethod("context", String.class);
        ActionEndpoint endpoint = method.getAnnotation(ActionEndpoint.class);

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.value()).isEqualTo(PlatformAction.MENU);
    }

    @Test
    void shouldRejectRuntimeContextWhenMenuActionIsDeniedByInterceptor() throws Exception {
        PlatformModuleRuntimeContextService service = mock(PlatformModuleRuntimeContextService.class);
        ActionExecutionPolicyService deniedPolicy = context -> {
            throw new PlatformAccessDeniedException("denied");
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformModuleRuntimeContextWebController(service))
                .addInterceptors(new ActionEndpointInterceptor(deniedPolicy, new ActionEndpointContextResolver()))
                .addFilters(new RequestTraceWebFilter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(get("/platform.module/iam.organization/context"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.ACCESS_DENIED))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void shouldAuthorizeRuntimeContextAgainstTheCompleteDottedModuleAlias() throws Exception {
        PlatformModuleRuntimeContextService service = mock(PlatformModuleRuntimeContextService.class);
        AtomicReference<String> authorizedModuleAlias = new AtomicReference<>();
        ActionExecutionPolicyService policy = context -> authorizedModuleAlias.set(context.moduleAlias());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlatformModuleRuntimeContextWebController(service))
                .addInterceptors(new ActionEndpointInterceptor(policy, new ActionEndpointContextResolver()))
                .addFilters(new RequestTraceWebFilter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(get("/platform.module/mr.device/context"))
                .andExpect(status().isOk());

        assertThat(authorizedModuleAlias).hasValue("mr.device");
    }

    private MockMvc mvc(PlatformModuleRuntimeContextService service) {
        return MockMvcBuilders.standaloneSetup(new PlatformModuleRuntimeContextWebController(service))
                .addFilters(new RequestTraceWebFilter())
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();
    }
}
