package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.common.exception.AuthenticationFailedException;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.iam.tenant.TenantBranding;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LoginWebControllerTest {
    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test
    void shouldExposeCurrentUserContext() throws Exception {
        LoginWebController controller = new LoginWebController(mock(UserSessionService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "Alice", "tenant-a", "org-1"))))
                .build();

        mvc.perform(get("/iam.auth/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("user-1"))
                .andExpect(jsonPath("$.username").value("Alice"))
                .andExpect(jsonPath("$.tenantId").value("tenant-a"))
                .andExpect(jsonPath("$.organizationId").value("org-1"));
    }

    @Test
    void shouldReturnUnauthorizedWhenCurrentUserContextIsMissing() throws Exception {
        LoginWebController controller = new LoginWebController(mock(UserSessionService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(get("/iam.auth/context"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.AUTH_REQUIRED))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("current user context is not available"));
    }

    @Test
    void shouldExposeOnlyTheCurrentTenantBrandingToWorkbenchStartup() throws Exception {
        TenantService tenantService = mock(TenantService.class);
        when(tenantService.branding("tenant-a"))
                .thenReturn(new TenantBranding("data:image/png;base64,bGlnaHQ=", "data:image/png;base64,ZGFyaw=="));
        LoginWebController controller = new LoginWebController(mock(UserSessionService.class), tenantService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "Alice", "tenant-a", "org-1"))))
                .build();

        mvc.perform(get("/iam.auth/tenant-branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lightLogo").value("data:image/png;base64,bGlnaHQ="))
                .andExpect(jsonPath("$.darkLogo").value("data:image/png;base64,ZGFyaw=="));

        verify(tenantService).branding("tenant-a");
    }

    @Test
    void shouldReturnUnauthorizedWhenLoginCredentialsAreInvalid() throws Exception {
        UserSessionService userSessionService = mock(UserSessionService.class);
        when(userSessionService.login(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class)))
                .thenThrow(new AuthenticationFailedException("invalid username or password"));
        LoginWebController controller = new LoginWebController(userSessionService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(post("/iam.auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"tenant-a","username":"alice","password":"wrong-password"}
                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.LOGIN_BAD_CREDENTIALS))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("invalid username or password"));
    }

    @Test
    void shouldReturnAuthenticationFailedWithoutLeakingInactiveTenantReason() throws Exception {
        UserSessionService userSessionService = mock(UserSessionService.class);
        when(userSessionService.login(anyString(), anyString(), anyString(), nullable(String.class), nullable(String.class)))
                .thenThrow(new AuthenticationFailedException("invalid username or password",
                        new RuntimeException("Tenant is not active: tenant-a")));
        LoginWebController controller = new LoginWebController(userSessionService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(post("/iam.auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"tenant-a","username":"alice","password":"secret1"}
                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.LOGIN_BAD_CREDENTIALS))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("invalid username or password"));
    }

    @Test
    void shouldReturnBadRequestWhenLoginRequestIsMalformed() throws Exception {
        UserSessionService userSessionService = mock(UserSessionService.class);
        LoginWebController controller = new LoginWebController(userSessionService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .build();

        mvc.perform(post("/iam.auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"tenantId":"tenant-a","password":"secret1"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("登录请求缺少用户名"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("username"));
    }

    @Test
    void shouldChangeOwnPasswordForCurrentUser() throws Exception {
        UserSessionService userSessionService = mock(UserSessionService.class);
        LoginWebController controller = new LoginWebController(userSessionService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(
                        CurrentUser.tenantUser("user-1", "Alice", "tenant-a", "org-1"))))
                .build();

        mvc.perform(post("/iam.auth/changeOwnPassword")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"old-secret","newPassword":"new-secret"}
                """))
                .andExpect(status().isOk());

        verify(userSessionService).changeOwnPassword("user-1", "old-secret", "new-secret");
    }
}
