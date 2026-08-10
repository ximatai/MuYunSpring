package net.ximatai.muyun.spring.web;

import jakarta.servlet.DispatcherType;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CurrentUserWebFilterTest {
    @Test
    void shouldRebindUserAndTenantContextForAsyncDispatch() throws Exception {
        CurrentUser currentUser = CurrentUser.tenantUser("user-1", "alice", "tenant-a", "org-1", false);
        CurrentUserWebFilter filter = new CurrentUserWebFilter(() -> Optional.of(currentUser));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/business/stream");
        request.setDispatcherType(DispatcherType.ASYNC);
        AtomicReference<CurrentUser> boundUser = new AtomicReference<>();
        AtomicReference<String> boundTenantId = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> {
            boundUser.set(CurrentUserContext.currentUser().orElse(null));
            boundTenantId.set(TenantContext.currentTenantId().orElse(null));
        });

        assertThat(boundUser.get()).isEqualTo(currentUser);
        assertThat(boundTenantId.get()).isEqualTo("tenant-a");
        assertThat(CurrentUserContext.currentUser()).isEmpty();
        assertThat(TenantContext.hasContext()).isFalse();
    }

    @Test
    void shouldRejectBusinessRequestsWhenPasswordChangeIsRequired() throws Exception {
        MockMvc mvc = restrictedMvc();

        mvc.perform(get("/business"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(PlatformErrorCodes.PASSWORD_CHANGE_REQUIRED)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("password change required")));
    }

    @Test
    void shouldAllowPasswordChangeRequestWhenPasswordChangeIsRequired() throws Exception {
        MockMvc mvc = restrictedMvc();

        mvc.perform(post("/iam.auth/changeOwnPassword"))
                .andExpect(status().isOk())
                .andExpect(content().string("changed"));
    }

    @Test
    void shouldAllowContextRequestWhenPasswordChangeIsRequired() throws Exception {
        MockMvc mvc = restrictedMvc();

        mvc.perform(get("/iam.auth/context"))
                .andExpect(status().isOk())
                .andExpect(content().string("context"));
    }

    @Test
    void shouldRejectInvalidBearerTokenBeforeBusinessHandler() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(new CurrentUserWebFilter(Optional::empty))
                .build();

        mvc.perform(get("/business").header("Authorization", "Bearer stale-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AUTH_REQUIRED")));
    }

    @Test
    void shouldKeepCorsHeadersWhenRejectingInvalidBearerToken() throws Exception {
        MuYunSpringCorsProperties properties = new MuYunSpringCorsProperties();
        properties.setAllowedOrigins(java.util.List.of("http://127.0.0.1:5173"));
        FilterRegistrationBean<CorsFilter> cors = new MuYunSpringWebConfiguration(properties)
                .corsFilterRegistration();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(cors.getFilter(), new CurrentUserWebFilter(Optional::empty))
                .build();

        mvc.perform(get("/business")
                        .header("Origin", "http://127.0.0.1:5173")
                        .header("Authorization", "Bearer stale-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:5173"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("AUTH_REQUIRED")));
    }

    @Test
    void shouldDenyCrossOriginByDefault() throws Exception {
        MuYunSpringCorsProperties properties = new MuYunSpringCorsProperties();
        FilterRegistrationBean<CorsFilter> cors = new MuYunSpringWebConfiguration(properties)
                .corsFilterRegistration();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(cors.getFilter())
                .build();

        mvc.perform(get("/business").header("Origin", "http://127.0.0.1:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private MockMvc restrictedMvc() {
        return MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "alice", "tenant-a", "org-1", true))))
                .build();
    }

    @RestController
    private static class TestController {
        @GetMapping("/business")
        String business() {
            return "business";
        }

        @PostMapping("/iam.auth/changeOwnPassword")
        String changeOwnPassword() {
            return "changed";
        }

        @GetMapping("/iam.auth/context")
        String context() {
            return "context";
        }
    }
}
