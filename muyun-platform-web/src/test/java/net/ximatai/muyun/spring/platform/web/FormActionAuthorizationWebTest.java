package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.iam.role.RoleActionExecutionPolicyService;
import net.ximatai.muyun.spring.iam.role.RoleService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class FormActionAuthorizationWebTest {
    @Test
    void shouldAuthorizeStaticAndPathBoundFormActionsBeforeExecutingDraft() throws Exception {
        for (String module : List.of("demo.form", "demo.dynamic")) {
            Fixture fixture = fixture(module);
            fixture.mvc.perform(post("/" + module + "/form-actions/calculate")
                    .contentType("application/json").content("{\"record\":{}}"))
                    .andExpect(status().isForbidden());
            assertThat(fixture.controller.executions).isZero();
            when(fixture.roles.hasActionPermission("user", module, "calculate")).thenReturn(true);
            fixture.mvc.perform(post("/" + module + "/form-actions/calculate")
                    .contentType("application/json").content("{\"record\":{}}"))
                    .andExpect(status().isOk());
            assertThat(fixture.controller.executions).isEqualTo(1);
            assertThat(ActionExecutionContextHolder.current()).isEmpty();
            assertThat(CurrentUserContext.currentUser()).isEmpty();
            assertThat(TenantContext.hasContext()).isFalse();
        }
    }

    @Test
    void shouldRejectDisabledUnknownAndNonFormActionsDespitePermission() throws Exception {
        for (String module : List.of("demo.form", "demo.dynamic")) {
            Fixture fixture = fixture(module);
            when(fixture.roles.hasActionPermission(anyString(), anyString(), anyString())).thenReturn(true);
            fixture.action.setEnabled(false);
            fixture.mvc.perform(post("/" + module + "/form-actions/calculate")
                    .contentType("application/json").content("{\"record\":{}}"))
                    .andExpect(status().isNotFound());
            fixture.action.setEnabled(true);
            fixture.action.setFormSupported(false);
            fixture.mvc.perform(post("/" + module + "/form-actions/calculate")
                    .contentType("application/json").content("{\"record\":{}}"))
                    .andExpect(status().isNotFound());
            fixture.mvc.perform(post("/" + module + "/form-actions/unknown")
                    .contentType("application/json").content("{\"record\":{}}"))
                    .andExpect(status().isNotFound());
            assertThat(fixture.controller.executions).isZero();
            verifyNoInteractions(fixture.roles);
        }
    }

    @Test
    void shouldRejectMissingFormRecordAfterAuthorization() throws Exception {
        Fixture fixture = fixture("demo.form");
        when(fixture.roles.hasActionPermission("user", "demo.form", "calculate")).thenReturn(true);
        for (String body : List.of("{}", "{\"record\":null}", "")) {
            fixture.mvc.perform(post("/demo.form/form-actions/calculate")
                    .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(fixture.controller.executions).isZero();
    }

    @Test
    void shouldCheckPersistedRecordScopeRatherThanTrustDraftOwnership() throws Exception {
        Fixture fixture = fixture("demo.form");
        when(fixture.roles.hasActionPermission("user", "demo.form", "calculate")).thenReturn(true);
        fixture.mvc.perform(post("/demo.form/form-actions/calculate")
                .contentType("application/json").content("{\"record\":{\"id\":\"hidden\",\"tenantId\":\"tenant-a\"}}"))
                .andExpect(status().isForbidden());
        verify(fixture.controller.service).requireRecordScope(
                argThat((ActionExecutionPolicy policy) -> policy.actionCode().equals("calculate") && policy.requiresDataScope()),
                eq(List.of("hidden")));
        verify(fixture.controller.service).count(any(Criteria.class));
        assertThat(fixture.controller.executions).isZero();
        doReturn(1L).when(fixture.controller.service).count(any(Criteria.class));
        fixture.mvc.perform(post("/demo.form/form-actions/calculate")
                .contentType("application/json").content("{\"record\":{\"id\":\"visible\"}}"))
                .andExpect(status().isOk());
        assertThat(fixture.controller.executions).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture(String module) {
        RoleService roles = mock(RoleService.class);
        PlatformModuleActionService actions = mock(PlatformModuleActionService.class);
        PlatformModuleAction action = new PlatformModuleAction();
        action.setModuleAlias(module);
        action.setActionCode("calculate");
        action.setActionLevel(EntityActionLevel.ANY);
        action.setActionAuth(true);
        action.setDataAuth(true);
        action.setFormSupported(true);
        action.setEnabled(true);
        when(actions.findByModuleAliasAndActionCode(module, "calculate")).thenReturn(action);
        DataScopeAbility<Draft> service = mock(DataScopeAbility.class, CALLS_REAL_METHODS);
        doReturn(module).when(service).getModuleAlias();
        doReturn(0L).when(service).count(any(Criteria.class));
        DataScopeCriteriaService scopes = mock(DataScopeCriteriaService.class);
        doReturn(scopes).when(service).getDataScopeCriteriaService();
        when(scopes.resolveReadScope(eq(module), any(ActionExecutionPolicy.class), any(Criteria.class), any(), any()))
                .thenAnswer(call -> DataScopeCriteriaResult.restricted(call.getArgument(2)));
        FormController controller = module.equals("demo.form") ? new StaticFormController(service) : new DynamicFormController(service);
        MockMvc mvc = standaloneSetup(controller)
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> Optional.of(CurrentUser.tenantUser("user", "User", "tenant-a"))))
                .addInterceptors(new ActionEndpointInterceptor(new RoleActionExecutionPolicyService(roles),
                        new ActionEndpointContextResolver(actions))).build();
        return new Fixture(mvc, roles, controller, action);
    }

    public static class Draft extends StandardEntity { }

    private record Fixture(MockMvc mvc, RoleService roles, FormController controller, PlatformModuleAction action) { }

    public abstract static class FormController implements FormActionWeb<DataScopeAbility<Draft>, Draft, String> {
        final DataScopeAbility<Draft> service;
        int executions;
        FormController(DataScopeAbility<Draft> service) { this.service = service; }
        public DataScopeAbility<Draft> service() { return service; }
        public String webScopeName() { return service.getModuleAlias(); }
        public String executeFormAction(String code, FormActionRequest<Draft> request) {
            assertThat(ActionExecutionContextHolder.current()).get().satisfies(context -> {
                assertThat(context.actionCode()).isEqualTo(code);
                assertThat(context.authorizationResult()).isNotNull();
            });
            executions++;
            return "calculated";
        }
    }

    @RestController
    @RequestMapping("/demo.form")
    @PlatformStaticActionScope(module = "demo.form")
    public static class StaticFormController extends FormController {
        StaticFormController(DataScopeAbility<Draft> service) { super(service); }
    }

    @RestController
    @RequestMapping("/{moduleAlias}")
    public static class DynamicFormController extends FormController {
        DynamicFormController(DataScopeAbility<Draft> service) { super(service); }
    }
}
