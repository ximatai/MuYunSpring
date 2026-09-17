package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.WebReferenceResolveMode;
import net.ximatai.muyun.spring.web.WebReferenceResolveItem;
import net.ximatai.muyun.spring.web.WebReferenceResolveRequest;
import net.ximatai.muyun.spring.web.WebReferenceResolveResponse;
import net.ximatai.muyun.spring.web.WebReferenceResolveStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaticReferenceResolveWebControllerTest {
    @Test
    void shouldRouteStaticReferenceResolutionThroughTheIsolatedPlatformEndpoint() throws Exception {
        StaticReferenceResolveFacade facade = mock(StaticReferenceResolveFacade.class);
        when(facade.resolve(eq("iam.department"), eq("organizationId"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new WebReferenceResolveResponse(WebReferenceResolveStatus.OK,
                        WebReferenceResolveMode.TREE_CHILDREN,
                        List.of(new WebReferenceResolveItem("department-1", "平台研发部", null,
                                null, null, true)), List.of(), 0, 20, 1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new StaticReferenceResolveWebController(facade, mock(ModuleTenantScope.class))).build();

        mvc.perform(post("/platform.module/iam.department/references/organizationId/resolve")
                        .contentType("application/json")
                        .content("{\"mode\":\"TREE_CHILDREN\",\"parentId\":\"organization-1\",\"page\":{\"pageNum\":2,\"pageSize\":10}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("TREE_CHILDREN"))
                .andExpect(jsonPath("$.options[0].hasChildren").value(true));

        var request = forClass(WebReferenceResolveRequest.class);
        verify(facade).resolve(eq("iam.department"), eq("organizationId"), request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().mode()).isEqualTo(WebReferenceResolveMode.TREE_CHILDREN);
        org.assertj.core.api.Assertions.assertThat(request.getValue().parentId()).isEqualTo("organization-1");
        org.assertj.core.api.Assertions.assertThat(request.getValue().page().pageNum()).isEqualTo(2);
    }

    @Test
    void shouldUseTheModuleTenantScopeForReferenceResolution() {
        StaticReferenceResolveFacade facade = mock(StaticReferenceResolveFacade.class);
        ModuleTenantScope tenantScope = mock(ModuleTenantScope.class);
        StaticReferenceResolveWebController controller =
                new StaticReferenceResolveWebController(facade, tenantScope);

        controller.resolve("iam.department", "organizationId", null);

        verify(tenantScope).requireActiveTenantIfRequired("iam.department");
        verify(facade).resolve("iam.department", "organizationId", null);
    }
}
