package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.web.ActionResultResponseAdvice;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import net.ximatai.muyun.spring.platform.web.ActionEndpointInterceptor;
import net.ximatai.muyun.spring.platform.web.MenuEntryRequestContext;
import net.ximatai.muyun.spring.platform.web.MenuEntryRequestInterceptor;
import net.ximatai.muyun.spring.web.BusinessMutationInterceptor;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import net.ximatai.muyun.spring.iam.user.UserSessionService;
import net.ximatai.muyun.spring.iam.user.UserSessionStatusView;
import net.ximatai.muyun.spring.iam.user.UserSessionView;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.platform.menu.Menu;
import net.ximatai.muyun.spring.platform.menu.MenuService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAccountWebControllerTest {
    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldTreatTenantNavigatorAsAnOptionalUserListFilter() {
        var page = (net.ximatai.muyun.spring.platform.web.ListDetailCardPageDefinition) new UserAccountWebController()
                .moduleUiDefinition().page();

        assertThat(page.navigator().contextBindings())
                .filteredOn(binding -> binding.sourceKey().equals("tenant")
                        && binding.targetKey().equals("tenantId")
                        && binding.target() == net.ximatai.muyun.spring.platform.web.PageContextTarget.LIST_QUERY)
                .singleElement()
                .satisfies(binding -> assertThat(binding.navigatorListQueryMode())
                        .isEqualTo(net.ximatai.muyun.spring.platform.web.NavigatorListQueryMode.OPTIONAL_FILTER));
    }

    @Test
    void shouldScopeAnUnselectedSystemUserWorkspaceToSystemAccounts() {
        UserAccountWebController controller = new UserAccountWebController();
        WebQueryRequest request = new WebQueryRequest(null, List.of(), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            assertThat(controller.queryForCurrentWorkspace(request).conditions())
                    .containsExactly(new WebQueryCondition("tenantId", "NULL", List.of()));
        }
    }

    @Test
    void shouldRetainAnExplicitTenantPageContextForSystemUserQueries() {
        UserAccountWebController controller = new UserAccountWebController();
        WebQueryRequest request = new WebQueryRequest(null, List.of(), List.of());
        org.springframework.mock.web.MockHttpServletRequest servletRequest =
                new org.springframework.mock.web.MockHttpServletRequest();
        servletRequest.addHeader("X-MuYun-Page-Context", "{\"tenant\":\"demo\"}");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            assertThat(controller.queryForCurrentWorkspace(request)).isSameAs(request);
        }
    }

    @Test
    void shouldRetainAnExplicitTenantNavigatorValueForSystemUserQueries() {
        UserAccountWebController controller = new UserAccountWebController();
        WebQueryRequest request = new WebQueryRequest(null, List.of(), List.of())
                .withExternalQueryValues(java.util.Map.of("tenantId", "demo"));

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"))) {
            assertThat(controller.queryForCurrentWorkspace(request)).isSameAs(request);
        }
    }

    @Test
    void shouldNotInjectSystemAccountScopeForTenantUsers() {
        UserAccountWebController controller = new UserAccountWebController();
        WebQueryRequest request = new WebQueryRequest(null, List.of(), List.of());

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.tenantUser("tenant-admin", "Tenant Admin", "demo"))) {
            assertThat(controller.queryForCurrentWorkspace(request)).isSameAs(request);
        }
    }

    @Test
    void shouldDelegatePasswordChangeToService() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        UserAccountWebController controller = new UserAccountWebController();
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        when(userAccountService.changePassword("user-1", "secret2")).thenReturn(1);

        try (CurrentUserContext.Scope ignoredUser = tenantUserScope();
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-a")) {
            assertThat(controller.changePassword("user-1",
                    new UserAccountWebController.ChangePasswordRequest("secret2"))).isEqualTo(1);
        }

        verify(userAccountService).changePassword("user-1", "secret2");
    }

    @Test
    void shouldDelegatePasswordResetToService() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        UserAccountWebController controller = new UserAccountWebController();
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        when(userAccountService.resetPassword("user-1")).thenReturn(
                new UserAccountService.PasswordResetResult(1, "temp-secret", Instant.parse("2026-07-08T00:00:00Z")));

        try (CurrentUserContext.Scope ignoredUser = tenantUserScope();
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-a")) {
            UserAccountWebController.ResetPasswordResponse response = controller.resetPassword("user-1");

            assertThat(response.count()).isEqualTo(1);
            assertThat(response.temporaryPassword()).isEqualTo("temp-secret");
        }

        verify(userAccountService).resetPassword("user-1");
    }

    @Test
    void shouldDelegateForceLogoutToService() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        UserAccountWebController controller = new UserAccountWebController();
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        when(userAccountService.forceLogout("user-1")).thenReturn(1);

        try (CurrentUserContext.Scope ignoredUser = tenantUserScope();
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-a")) {
            assertThat(controller.forceLogout("user-1")).isEqualTo(1);
        }

        verify(userAccountService).forceLogout("user-1");
    }

    @Test
    void shouldDelegateUserSessionOperationsToService() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        UserSessionService userSessionService = mock(UserSessionService.class);
        UserAccountWebController controller = new UserAccountWebController(null, null, null,
                provider(userSessionService));
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        UserSessionView session = new UserSessionView("session-1", "user-1", "alice", "tenant-a",
                "org-1", Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-07-15T12:00:00Z"), Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2026-07-15T01:00:00Z"), false, "127.0.0.1", "Chrome",
                "desktopWeb", "Web 桌面端", "macos", "macOS", false,
                false, "offline", "离线", 0, null, null);
        when(userSessionService.activeSessionsOfUser("user-1", "token-1")).thenReturn(List.of(session));
        when(userSessionService.activeSessionStatuses(List.of("user-1")))
                .thenReturn(List.of(new UserSessionStatusView("user-1", true, 1, true, 1, 0)));
        when(userAccountService.listForAction(eq(net.ximatai.muyun.spring.common.platform.PlatformAction.QUERY),
                any(net.ximatai.muyun.database.core.orm.Criteria.class)))
                .thenReturn(List.of(user("user-1", "alice", "tenant-a")));
        when(userSessionService.revokeUserSession("user-1", "session-1", "token-1")).thenReturn(1);
        when(userSessionService.revokeUserSessions("user-1", List.of("session-1"), "token-1")).thenReturn(1);

        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token-1");

        try (CurrentUserContext.Scope ignoredUser = tenantUserScope();
             TenantContext.Scope ignoredTenant = TenantContext.use("tenant-a")) {
            assertThat(controller.activeSessions("user-1", request)).containsExactly(session);
            assertThat(controller.sessionStatuses(new UserAccountWebController.SessionStatusRequest(List.of("user-1"))))
                    .containsExactly(new UserSessionStatusView("user-1", true, 1, true, 1, 0));
            assertThat(controller.revokeSession("user-1", "session-1", request)).isEqualTo(1);
            assertThat(controller.revokeSessions("user-1",
                    new UserAccountWebController.RevokeSessionsRequest(List.of("session-1")), request)).isEqualTo(1);
        }

        verify(userSessionService).activeSessionsOfUser("user-1", "token-1");
        verify(userSessionService).activeSessionStatuses(List.of("user-1"));
        verify(userSessionService).revokeUserSession("user-1", "session-1", "token-1");
        verify(userSessionService).revokeUserSessions("user-1", List.of("session-1"), "token-1");
    }

    @Test
    void shouldLoadSessionStatusesForSystemUserAcrossTenants() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        UserSessionService userSessionService = mock(UserSessionService.class);
        UserAccountWebController controller = new UserAccountWebController(null, null, null,
                provider(userSessionService));
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        List<String> userIds = List.of("system-user", "tenant-user");
        when(userAccountService.listForAction(eq(net.ximatai.muyun.spring.common.platform.PlatformAction.QUERY),
                any(net.ximatai.muyun.database.core.orm.Criteria.class)))
                .thenReturn(List.of(user("system-user", "admin", null), user("tenant-user", "demo-admin", "demo")));
        when(userSessionService.activeSessionStatuses(userIds)).thenReturn(List.of(
                new UserSessionStatusView("system-user", true, 2, true, 1, 0),
                new UserSessionStatusView("tenant-user", true, 1, false, 0, 0)
        ));

        try (TenantContext.Scope ignored = TenantContext.system("test system session status")) {
            assertThat(controller.sessionStatuses(new UserAccountWebController.SessionStatusRequest(userIds)))
                    .containsExactly(
                            new UserSessionStatusView("system-user", true, 2, true, 1, 0),
                            new UserSessionStatusView("tenant-user", true, 1, false, 0, 0)
                    );
        }

        verify(userSessionService).activeSessionStatuses(userIds);
    }

    @Test
    void shouldReportNotFoundWhenTenantUserReadsSystemAccount() throws Exception {
        UserAccountService userAccountService = mock(UserAccountService.class);
        UserAccountWebController controller = new UserAccountWebController();
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() ->
                        java.util.Optional.of(CurrentUser.tenantUser("tenant-admin", "Tenant Admin", "tenant-a"))))
                .build();

        mvc.perform(get("/iam.user/view/platform.user.super_admin"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void shouldWrapPasswordBusinessMutationResults() throws Exception {
        UserAccountService userAccountService = mock(UserAccountService.class);
        UserSessionService userSessionService = mock(UserSessionService.class);
        UserAccountWebController controller = new UserAccountWebController(null, null, null,
                provider(userSessionService));
        ReflectionTestUtils.setField(controller, "service", userAccountService);
        when(userAccountService.changePassword("user-1", "secret2")).thenReturn(1);
        when(userAccountService.resetPassword("user-1")).thenReturn(
                new UserAccountService.PasswordResetResult(1, "temp-secret", null));
        when(userAccountService.forceLogout("user-1")).thenReturn(1);
        when(userSessionService.revokeUserSession("user-1", "session-1", null)).thenReturn(1);
        when(userSessionService.revokeUserSessions("user-1", List.of("session-1"), null)).thenReturn(1);
        when(userSessionService.activeSessionsOfUser("user-1", null)).thenReturn(List.of(
                new UserSessionView("session-1", "user-1", "alice", "tenant-a",
                        "org-1", Instant.parse("2026-07-15T00:00:00Z"),
                        Instant.parse("2026-07-15T12:00:00Z"), Instant.parse("2026-07-22T00:00:00Z"),
                        Instant.parse("2026-07-15T01:00:00Z"), false, "127.0.0.1", "Chrome",
                        "desktopWeb", "Web 桌面端", "macos", "macOS", false,
                        false, "offline", "离线", 0, null, null)
        ));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new ActionEndpointInterceptor(new AllowAllActionExecutionPolicyService(),
                        new ActionEndpointContextResolver()))
                .addInterceptors(new BusinessMutationInterceptor())
                .setControllerAdvice(new ActionResultResponseAdvice(UserAccountWebControllerTest::moduleAlias,
                        new com.fasterxml.jackson.databind.ObjectMapper()))
                .build();
        try (TenantContext.Scope ignored = TenantContext.system("test")) {
            mvc.perform(post("/iam.user/changePassword/user-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"password":"secret2"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(1))
                    .andExpect(jsonPath("$.message.code").value("iam.user.password-changed"))
                    .andExpect(jsonPath("$.message.text").value("密码已修改"))
                    .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == 'iam.user' && @.recordId == 'user-1')]")
                            .exists());

            mvc.perform(post("/iam.user/resetPassword/user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.count").value(1))
                    .andExpect(jsonPath("$.data.temporaryPassword").value("temp-secret"))
                    .andExpect(jsonPath("$.message.code").value("iam.user.password-reset"))
                    .andExpect(jsonPath("$.message.text").value("密码已重置"))
                    .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == 'iam.user' && @.recordId == 'user-1')]")
                            .exists());

            mvc.perform(post("/iam.user/forceLogout/user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(1))
                    .andExpect(jsonPath("$.message.code").value("iam.user.force-logout"))
                    .andExpect(jsonPath("$.message.text").value("用户已下线"))
                    .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == 'iam.user' && @.recordId == 'user-1')]")
                            .exists());

            mvc.perform(get("/iam.user/user-1/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].id").value("session-1"))
                    .andExpect(jsonPath("$[0].loginIp").value("127.0.0.1"));

            mvc.perform(post("/iam.user/user-1/sessions/session-1/revoke"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(1))
                    .andExpect(jsonPath("$.message.code").value("iam.user-session.revoked"))
                    .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == 'iam.user' && @.recordId == 'user-1')]")
                            .exists());

            mvc.perform(post("/iam.user/user-1/sessions/revoke")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionIds":["session-1"]}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(1))
                    .andExpect(jsonPath("$.message.code").value("iam.user-session.revoked-batch"))
                    .andExpect(jsonPath("$.changes[?(@.type == 'record-updated' && @.moduleAlias == 'iam.user' && @.recordId == 'user-1')]")
                            .exists());
        }
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        when(provider.getIfAvailable(any(Supplier.class))).thenAnswer(invocation -> {
            if (bean != null) {
                return bean;
            }
            return invocation.<Supplier<T>>getArgument(0).get();
        });
        return provider;
    }

    private static String moduleAlias(Class<?> moduleType) {
        try {
            Object value = moduleType.getField("MODULE_ALIAS").get(null);
            if (value instanceof String alias) {
                return alias;
            }
        } catch (ReflectiveOperationException ignored) {
            // Fall through to a clear test failure.
        }
        throw new IllegalArgumentException("missing MODULE_ALIAS: " + moduleType.getName());
    }

    private static UserAccount user(String id, String username, String tenantId) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setUsername(username);
        user.setTenantId(tenantId);
        return user;
    }

    private static CurrentUserContext.Scope tenantUserScope() {
        return CurrentUserContext.use(CurrentUser.tenantUser("operator-1", "operator", "tenant-a"));
    }
}
