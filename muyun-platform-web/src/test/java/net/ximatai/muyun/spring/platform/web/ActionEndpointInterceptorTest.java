package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.web.*;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.exception.ApplicationNotOpenedException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.iam.web.RoleWebController;
import net.ximatai.muyun.spring.iam.web.UserAccountWebController;
import net.ximatai.muyun.spring.iam.web.IamActingRequestResolver;
import net.ximatai.muyun.spring.platform.module.PlatformStaticModule;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService;
import net.ximatai.muyun.spring.iam.role.RoleActionExecutionPolicyService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActionEndpointInterceptorTest {
    private final RecordingPolicyService policyService = new RecordingPolicyService();
    private final ActionEndpointInterceptor interceptor = new ActionEndpointInterceptor(
            policyService,
            new ActionEndpointContextResolver()
    );

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
        ActingContextHolder.clear();
        ActionExecutionContextHolder.clear();
        MDC.clear();
    }

    @Test
    void shouldResolveStaticScopedWebActionContext() throws Exception {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.tenantUser("u1", "User", "t1"))) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/query");

            interceptor.preHandle(request, new MockHttpServletResponse(),
                    handler(new StaticScopedWeb(), CrudWeb.class.getMethod("query", WebQueryRequest.class)));

            assertThat(policyService.context).satisfies(context -> {
                assertThat(context.moduleAlias()).isEqualTo("iam.organization");
                assertThat(context.platformAction()).isEqualTo(PlatformAction.QUERY);
                assertThat(context.actionCode()).isEqualTo("query");
                assertThat(context.permissionCode()).isEqualTo("iam.organization:view");
                assertThat(context.recordIds()).isEmpty();
                assertThat(context.currentUser()).get().extracting(CurrentUser::userId).isEqualTo("u1");
                assertThat(context.authorizationResult()).isNotNull();
                assertThat(context.authorizationResult().operatorId()).isEqualTo("u1");
            });
            assertThat(ActionExecutionContextHolder.current()).contains(policyService.context);
            interceptor.afterCompletion(request, new MockHttpServletResponse(),
                    handler(new StaticScopedWeb(), CrudWeb.class.getMethod("query", WebQueryRequest.class)), null);
            assertThat(ActionExecutionContextHolder.current()).isEmpty();
        }
    }

    @Test
    void shouldAuthorizeCompiledEndpointFromRegisteredCatalogWithoutControllerDeclaration() throws Exception {
        StaticScopedWeb endpointHandler = new StaticScopedWeb();
        Method method = CrudWeb.class.getMethod("query", WebQueryRequest.class);
        RegisteredWebEndpointCatalog catalog = new RegisteredWebEndpointCatalog();
        ResolvedWebEndpoint definition = new ResolvedWebEndpoint(
                "platform.application.enable.enable",
                "platform.application",
                "enable",
                "enable",
                PlatformAction.ENABLE,
                RequestMethod.POST,
                "/platform.application/enable/{id}",
                ResolvedWebEndpoint.Source.STATIC_ABILITY
        );
        RequestMappingInfo mapping = RequestMappingInfo.paths(definition.path())
                .methods(definition.method())
                .build();
        catalog.register(new RegisteredWebEndpoint(definition, mapping, endpointHandler, method));
        ActionEndpointInterceptor compiledInterceptor = new ActionEndpointInterceptor(
                policyService,
                new ActionEndpointContextResolver(),
                null,
                catalog
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/platform.application/enable/app-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "app-1"));

        compiledInterceptor.preHandle(request, new MockHttpServletResponse(), handler(endpointHandler, method));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("platform.application");
            assertThat(context.platformAction()).isEqualTo(PlatformAction.ENABLE);
            assertThat(context.recordIds()).containsExactly("app-1");
        });
    }

    @Test
    void shouldExposeAndClearCompiledEndpointIdInLogContext() throws Exception {
        StaticScopedWeb endpointHandler = new StaticScopedWeb();
        Method method = CrudWeb.class.getMethod("query", WebQueryRequest.class);
        RegisteredWebEndpointCatalog catalog = new RegisteredWebEndpointCatalog();
        ResolvedWebEndpoint definition = new ResolvedWebEndpoint(
                "platform.application.enable.enable", "platform.application", "enable", "enable",
                PlatformAction.ENABLE, RequestMethod.POST, "/platform.application/enable/{id}",
                ResolvedWebEndpoint.Source.STATIC_ABILITY);
        RequestMappingInfo mapping = RequestMappingInfo.paths(definition.path()).methods(definition.method()).build();
        catalog.register(new RegisteredWebEndpoint(definition, mapping, endpointHandler, method));
        ActionEndpointInterceptor compiledInterceptor = new ActionEndpointInterceptor(
                policyService, new ActionEndpointContextResolver(), null, catalog);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/platform.application/enable/app-1");

        compiledInterceptor.preHandle(request, new MockHttpServletResponse(), handler(endpointHandler, method));
        assertThat(MDC.get("endpointId")).isEqualTo(definition.endpointId());

        compiledInterceptor.afterCompletion(request, new MockHttpServletResponse(), handler(endpointHandler, method), null);
        assertThat(MDC.get("endpointId")).isNull();
    }

    @Test
    void shouldRejectStaticEndpointBeforeRolePermissionWhenApplicationIsNotOpened() throws Exception {
        RoleService roleService = mock(RoleService.class);
        TenantApplicationService tenantApplicationService = mock(TenantApplicationService.class);
        org.mockito.Mockito.doThrow(new ApplicationNotOpenedException("tenant_a", "iam"))
                .when(tenantApplicationService).requireApplicationOpened("tenant_a", "iam");
        ActionEndpointInterceptor interceptor = new ActionEndpointInterceptor(
                new RoleActionExecutionPolicyService(roleService, tenantApplicationService),
                new ActionEndpointContextResolver());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/query");

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("user-1", "User", "tenant_a"))) {
            assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(),
                    handler(new StaticScopedWeb(), CrudWeb.class.getMethod("query", WebQueryRequest.class))))
                    .isInstanceOf(ApplicationNotOpenedException.class);
        }

        verify(roleService, never()).hasActionPermission("user-1", "iam.organization", "view");
    }

    @Test
    void shouldPreferDynamicModuleAliasFromPathAndCollectRecordIds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sales.contract/update/contract-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(
                "moduleAlias", "sales.contract",
                "recordId", "contract-1"
        ));
        request.addParameter("ids", "contract-2, contract-3");

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(new StaticScopedWeb(), CrudWeb.class.getMethod("update", String.class, EntityContract.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("sales.contract");
            assertThat(context.platformAction()).isEqualTo(PlatformAction.UPDATE);
            assertThat(context.actionCode()).isEqualTo("update");
            assertThat(context.recordIds()).containsExactlyInAnyOrder("contract-1", "contract-2", "contract-3");
        });
    }

    @Test
    void shouldClearActionContextWhenAsyncHandlingStarts() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/update/org-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "org-1"));
        HandlerMethod handler = handler(new StaticScopedWeb(), CrudWeb.class.getMethod("update", String.class, EntityContract.class));

        interceptor.preHandle(request, new MockHttpServletResponse(), handler);
        assertThat(ActionExecutionContextHolder.current()).contains(policyService.context);

        interceptor.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), handler);

        assertThat(ActionExecutionContextHolder.current()).isEmpty();
    }

    @Test
    void shouldResolveActingContextFromRequestHeadersBeforeAuthorization() throws Exception {
        EmployeeDelegationService delegationService = mock(EmployeeDelegationService.class);
        ActingContext actingContext = new ActingContext(
                "delegation-1",
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                BusinessPrincipal.employee("principal-employee", "org-1", "dept-1"),
                "iam.organization",
                "query");
        when(delegationService.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "principal-employee",
                "principal-position",
                "iam.organization",
                "query")).thenReturn(actingContext);
        RecordingPolicyService policyService = new RecordingPolicyService();
        ActionEndpointInterceptor interceptor = new ActionEndpointInterceptor(
                policyService,
                new ActionEndpointContextResolver(),
                new IamActingRequestResolver(delegationService)
        );

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"))) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/query");
            request.addHeader(IamActingRequestResolver.PRINCIPAL_EMPLOYEE_ID_HEADER, "principal-employee");
            request.addHeader(IamActingRequestResolver.PRINCIPAL_POSITION_ID_HEADER, "principal-position");
            HandlerMethod handler = handler(new StaticScopedWeb(),
                    CrudWeb.class.getMethod("query", WebQueryRequest.class));

            interceptor.preHandle(request, new MockHttpServletResponse(), handler);

            assertThat(policyService.actingContext).isSameAs(actingContext);
            assertThat(ActingContextHolder.current()).contains(actingContext);
            interceptor.afterCompletion(request, new MockHttpServletResponse(), handler, null);
            assertThat(ActingContextHolder.current()).isEmpty();
        }
    }

    @Test
    void shouldIgnoreIamActingHeadersWhenResolverIsNotConfigured() throws Exception {
        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"))) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/query");
            request.addHeader(IamActingRequestResolver.PRINCIPAL_EMPLOYEE_ID_HEADER, "principal-employee");

            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(),
                    handler(new StaticScopedWeb(), CrudWeb.class.getMethod("query", WebQueryRequest.class))))
                    .isTrue();
            assertThat(ActingContextHolder.current()).isEmpty();
        }
    }

    @Test
    void shouldClearActingContextWhenAuthorizationFails() throws Exception {
        EmployeeDelegationService delegationService = mock(EmployeeDelegationService.class);
        ActingContext actingContext = actingContext();
        when(delegationService.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "principal-employee",
                null,
                "iam.organization",
                "query")).thenReturn(actingContext);
        ActionEndpointInterceptor interceptor = new ActionEndpointInterceptor(
                new ThrowingPolicyService(),
                new ActionEndpointContextResolver(),
                new IamActingRequestResolver(delegationService)
        );

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"))) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/query");
            request.addHeader(IamActingRequestResolver.PRINCIPAL_EMPLOYEE_ID_HEADER, "principal-employee");

            assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(),
                    handler(new StaticScopedWeb(), CrudWeb.class.getMethod("query", WebQueryRequest.class))))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("denied");
            assertThat(ActingContextHolder.current()).isEmpty();
        }
    }

    @Test
    void shouldKeepActingContextEmptyWhenActingResolutionFails() throws Exception {
        EmployeeDelegationService delegationService = mock(EmployeeDelegationService.class);
        when(delegationService.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "principal-employee",
                null,
                "iam.organization",
                "query")).thenThrow(new PlatformException("delegation denied"));
        ActionEndpointInterceptor interceptor = new ActionEndpointInterceptor(
                policyService,
                new ActionEndpointContextResolver(),
                new IamActingRequestResolver(delegationService)
        );

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"))) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/query");
            request.addHeader(IamActingRequestResolver.PRINCIPAL_EMPLOYEE_ID_HEADER, "principal-employee");

            assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(),
                    handler(new StaticScopedWeb(), CrudWeb.class.getMethod("query", WebQueryRequest.class))))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("delegation denied");
            assertThat(ActingContextHolder.current()).isEmpty();
        }
    }

    @Test
    void shouldResolveRoleCustomEndpointActionContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.role/grant/role-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("roleId", "role-1"));
        RoleWebController controller = new RoleWebController(null);

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, RoleWebController.class.getMethod(
                        "grantAction", String.class, RoleWebController.GrantActionRequest.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("iam.role");
            assertThat(context.platformAction()).isNull();
            assertThat(context.actionCode()).isEqualTo("rolePermissions");
            assertThat(context.permissionCode()).isEqualTo("iam.role:rolePermissions");
            assertThat(context.actionPolicy().requiresDataScope()).isTrue();
            assertThat(context.recordIds()).containsExactly("role-1");
        });
    }

    @Test
    void shouldResolveAccountRoleGrantsCustomEndpointActionContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.role/role-1/account-grants");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("roleId", "role-1"));
        RoleWebController controller = new RoleWebController(null);

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, RoleWebController.class.getMethod(
                        "grantAccountRole", String.class, RoleWebController.AccountRoleGrantRequest.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("iam.role");
            assertThat(context.platformAction()).isNull();
            assertThat(context.actionCode()).isEqualTo("accountRoleGrants");
            assertThat(context.permissionCode()).isEqualTo("iam.role:accountRoleGrants");
            assertThat(context.actionPolicy().requiresDataScope()).isTrue();
            assertThat(context.recordIds()).containsExactly("role-1");
        });
    }

    @Test
    void shouldResolveUserManagementEndpointActionContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.user/changePassword/user-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "user-1"));
        UserAccountWebController controller = new UserAccountWebController();

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, UserAccountWebController.class.getMethod(
                        "changePassword", String.class, UserAccountWebController.ChangePasswordRequest.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("iam.user");
            assertThat(context.platformAction()).isNull();
            assertThat(context.actionCode()).isEqualTo("changePassword");
            assertThat(context.permissionCode()).isEqualTo("iam.user:changePassword");
            assertThat(context.actionPolicy().requiresDataScope()).isTrue();
            assertThat(context.recordIds()).containsExactly("user-1");
        });
    }

    @Test
    void shouldResolveUserForceLogoutEndpointActionContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.user/forceLogout/user-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "user-1"));
        UserAccountWebController controller = new UserAccountWebController();

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, UserAccountWebController.class.getMethod("forceLogout", String.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("iam.user");
            assertThat(context.platformAction()).isNull();
            assertThat(context.actionCode()).isEqualTo("forceLogout");
            assertThat(context.permissionCode()).isEqualTo("iam.user:forceLogout");
            assertThat(context.actionPolicy().requiresDataScope()).isTrue();
            assertThat(context.recordIds()).containsExactly("user-1");
        });
    }

    @Test
    void shouldResolveUserSessionEndpointActionContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/iam.user/user-1/sessions/session-1/revoke");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(
                "id", "user-1",
                "sessionId", "session-1"
        ));
        UserAccountWebController controller = new UserAccountWebController();

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, UserAccountWebController.class.getMethod(
                        "revokeSession", String.class, String.class, HttpServletRequest.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("iam.user");
            assertThat(context.platformAction()).isNull();
            assertThat(context.actionCode()).isEqualTo("revokeSession");
            assertThat(context.permissionCode()).isEqualTo("iam.user:revokeSession");
            assertThat(context.actionPolicy().requiresDataScope()).isTrue();
            assertThat(context.recordIds()).containsExactly("user-1");
        });
    }

    @Test
    void shouldResolveCustomEndpointModuleAliasFromStaticModuleAnnotation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/workflow/admin/history/query");
        StaticModuleActionController controller = new StaticModuleActionController();

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, StaticModuleActionController.class.getMethod("query")));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("platform.workflow_admin");
            assertThat(context.actionCode()).isEqualTo("workflowAdminQuery");
            assertThat(context.permissionCode()).isEqualTo("platform.workflow_admin:workflowAdminQuery");
        });
    }

    @Test
    void shouldPreferStaticModuleAnnotationOverPathModuleAlias() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/platform.module/sales.contract/actions/query");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of(
                "moduleAlias", "sales.contract"
        ));
        StaticModuleActionController controller = new StaticModuleActionController();

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, StaticModuleActionController.class.getMethod("standardQuery", WebQueryRequest.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("platform.workflow_admin");
            assertThat(context.actionCode()).isEqualTo("query");
            assertThat(context.permissionCode()).isEqualTo("platform.workflow_admin:view");
        });
    }

    @Test
    void shouldResolveContributedActionContextOnTargetModule() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/platform.dictionary_category/categories/category-1/items/query");
        ContributedItemActionController controller = new ContributedItemActionController();

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, ContributedItemActionController.class.getMethod("query", WebQueryRequest.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("platform.dictionary_category");
            assertThat(context.actionCode()).isEqualTo("item_query");
            assertThat(context.permissionCode()).isEqualTo("platform.dictionary_category:item_view");
            assertThat(context.platformAction()).isNull();
            assertThat(context.actionPolicy().requiresDataScope()).isTrue();
        });
    }

    @Test
    void shouldUseRegisteredCustomActionPolicyWhenResolvingWebEndpoint() throws Exception {
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias("iam.user");
        action.setActionCode("changePassword");
        action.setPermissionActionCode("update");
        action.setActionLevel(EntityActionLevel.RECORD);
        action.setActionAuth(Boolean.TRUE);
        action.setDataAuth(Boolean.TRUE);
        action.setEnabled(Boolean.TRUE);
        when(moduleActionService.findByModuleAliasAndActionCode("iam.user", "changePassword"))
                .thenReturn(action);
        ActionEndpointInterceptor interceptor = new ActionEndpointInterceptor(
                policyService,
                new ActionEndpointContextResolver(moduleActionService)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.user/changePassword/user-1");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "user-1"));
        UserAccountWebController controller = new UserAccountWebController();

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(controller, UserAccountWebController.class.getMethod(
                        "changePassword", String.class, UserAccountWebController.ChangePasswordRequest.class)));

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo("iam.user");
            assertThat(context.actionCode()).isEqualTo("changePassword");
            assertThat(context.permissionCode()).isEqualTo("iam.user:update");
            assertThat(context.actionPolicy().requiresDataScope()).isTrue();
            assertThat(context.recordIds()).containsExactly("user-1");
        });
    }

    @Test
    void shouldRejectEndpointMethodWithBothStandardAndCustomActionAnnotations() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/iam.organization/invalid");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(new InvalidDualActionWeb(), InvalidDualActionWeb.class.getMethod("invalid"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both standard and custom action endpoint");
    }


    @Test
    void shouldIgnoreEndpointWithoutActionAnnotation() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/iam.organization/actions");

        interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(new StaticScopedWeb(), ActionWeb.class.getMethod("actions")));

        assertThat(policyService.context).isNull();
    }

    @Test
    void shouldFailFastWhenActionEndpointCannotResolveModuleAlias() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/query");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(),
                handler(new Object(), CrudWeb.class.getMethod("query", WebQueryRequest.class))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("action endpoint requires module alias");
    }

    private HandlerMethod handler(Object bean, Method method) {
        return new HandlerMethod(bean, method);
    }

    private ActingContext actingContext() {
        return new ActingContext(
                "delegation-1",
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                BusinessPrincipal.employee("principal-employee", "org-1", "dept-1"),
                "iam.organization",
                "query");
    }

    private static final class RecordingPolicyService implements ActionExecutionPolicyService {
        private ActionExecutionContext context;
        private ActingContext actingContext;

        @Override
        public void requireAuthorized(ActionExecutionContext context) {
            this.context = context;
        }

        @Override
        public ActionAuthorizationResult authorize(ActionExecutionContext context) {
            ActionAuthorizationResult result = ActionAuthorizationResult.allowed(context, "TEST_ALLOWED");
            this.context = context.withAuthorizationResult(result);
            this.actingContext = ActingContextHolder.current().orElse(null);
            return result;
        }
    }

    private static final class ThrowingPolicyService implements ActionExecutionPolicyService {
        @Override
        public void requireAuthorized(ActionExecutionContext context) {
            throw new PlatformException("denied");
        }

        @Override
        public ActionAuthorizationResult authorize(ActionExecutionContext context) {
            throw new PlatformException("denied");
        }
    }

    private static final class StaticScopedWeb implements ScopedWeb<Object> {
        @Override
        public String webScopeName() {
            return "iam.organization";
        }

        @Override
        public Object service() {
            return new Object();
        }
    }

    private static final class InvalidDualActionWeb implements ScopedWeb<Object> {
        @ActionEndpoint(PlatformAction.UPDATE)
        @net.ximatai.muyun.spring.common.platform.CustomActionEndpoint("custom")
        public void invalid() {
        }

        @Override
        public String webScopeName() {
            return "iam.organization";
        }

        @Override
        public Object service() {
            return new Object();
        }
    }

    @PlatformStaticModule(application = net.ximatai.muyun.spring.platform.application.PlatformApplication.class, alias = "platform.workflow_admin", title = "Workflow Admin")
    private static final class StaticModuleActionController {
        @net.ximatai.muyun.spring.common.platform.CustomActionEndpoint("workflowAdminQuery")
        public void query() {
        }

        @ActionEndpoint(PlatformAction.QUERY)
        public void standardQuery(WebQueryRequest request) {
        }
    }

    @PlatformStaticActionContribution(
            targetModule = "platform.dictionary_category",
            resource = "item",
            resourceTitle = "字典项"
    )
    private static final class ContributedItemActionController {
        @ActionEndpoint(PlatformAction.QUERY)
        public void query(WebQueryRequest request) {
        }
    }
}
