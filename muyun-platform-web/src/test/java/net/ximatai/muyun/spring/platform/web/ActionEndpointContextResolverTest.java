package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionEndpointContextResolverTest {

    @Test void numberingEntryGovernanceUsesManagementAuthorizationWithoutTreatingEntryIdsAsRuleIds() {
        var controller = new net.ximatai.muyun.spring.platform.web.code.CodeRuleWebController(
                mock(net.ximatai.muyun.spring.platform.code.CodePreviewService.class));
        int checked = 0;
        for (var method : controller.getClass().getMethods()) {
            if (!java.util.Set.of("inspectLedgerEntry", "releaseLedgerEntry", "adjustRecycleEntry").contains(method.getName())) continue;
            checked++;
            var request = new MockHttpServletRequest();
            request.setAttribute(org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                    java.util.Map.of("entryId", "ledger-or-pool-entry"));
            var handler = new HandlerMethod(controller, method);
            var context = new ActionEndpointContextResolver().resolve(request, handler,
                    handler.getMethodAnnotation(CustomActionEndpoint.class)).orElseThrow();
            assertThat(context.moduleAlias()).isEqualTo("platform.code_rule");
            assertThat(context.actionCode()).isEqualTo(method.getName().equals("inspectLedgerEntry") ? "opsQuery" : "opsManage");
            assertThat(context.actionPolicy().requiresDataScope()).isFalse();
            assertThat(context.actionPolicy().actionAuth()).isTrue();
            assertThat(context.recordIds()).isEmpty();
        }
        assertThat(checked).isEqualTo(3);
    }

    @Test
    void fieldReadsResolveTheProducerViewPolicyWithoutAnAuthorizationResult() {
        var actions = mock(PlatformModuleActionService.class);
        var view = new PlatformModuleAction();
        view.setModuleAlias("test.child"); view.setActionCode("view"); view.setPermissionActionCode("query");
        view.setActionLevel(EntityActionLevel.RECORD); view.setActionAuth(true); view.setDataAuth(true); view.setEnabled(true);
        when(actions.findByModuleAliasAndActionCode("test.child","view")).thenReturn(view);
        var context = new ActionEndpointContextResolver(actions).resolveFieldReadContext("test.child",java.util.Set.of("child-id"));
        assertThat(context.moduleAlias()).isEqualTo("test.child");
        assertThat(context.actionCode()).isEqualTo("view");
        assertThat(context.permissionCode()).isEqualTo("test.child:query");
        assertThat(context.recordIds()).containsExactly("child-id");
        assertThat(context.authorizationResult()).isNull();
    }

    @Test
    void shouldApplyRegisteredModuleActionPolicyToIndependentStaticActionScope() throws Exception {
        ProjectionController controller = new ProjectionController();
        HandlerMethod handler = new HandlerMethod(controller,
                ProjectionController.class.getMethod("issueTransferTicket"));
        ActionEndpoint endpoint = handler.getMethodAnnotation(ActionEndpoint.class);
        PlatformModuleActionService moduleActionService = mock(PlatformModuleActionService.class);
        PlatformModuleAction registeredAction = new PlatformModuleAction();
        registeredAction.setModuleAlias("mr.knowledge_file");
        registeredAction.setActionCode(PlatformAction.CREATE.code());
        registeredAction.setPermissionActionCode(PlatformAction.UPDATE.code());
        registeredAction.setActionLevel(EntityActionLevel.RECORD);
        registeredAction.setActionAuth(Boolean.TRUE);
        registeredAction.setDataAuth(Boolean.TRUE);
        registeredAction.setEnabled(Boolean.TRUE);
        when(moduleActionService.findByModuleAliasAndActionCode("mr.knowledge_file", PlatformAction.CREATE.code()))
                .thenReturn(registeredAction);

        var context = new ActionEndpointContextResolver(moduleActionService)
                .resolve(new MockHttpServletRequest(), handler, endpoint)
                .orElseThrow();

        assertThat(context.moduleAlias()).isEqualTo("mr.knowledge_file");
        assertThat(context.actionCode()).isEqualTo(PlatformAction.CREATE.code());
        assertThat(context.permissionCode()).isEqualTo("mr.knowledge_file:update");
        assertThat(context.actionPolicy().requiresDataScope()).isTrue();
    }

    @Test
    void shouldResolveDeclaredLoginRequiredCustomActionWithoutRoleGrant() throws Exception {
        ProfileController controller = new ProfileController();
        HandlerMethod handler = new HandlerMethod(controller,
                ProfileController.class.getMethod("updateProfile"));
        CustomActionEndpoint endpoint = handler.getMethodAnnotation(CustomActionEndpoint.class);

        var context = new ActionEndpointContextResolver()
                .resolve(new MockHttpServletRequest(), handler, endpoint)
                .orElseThrow();

        assertThat(context.moduleAlias()).isEqualTo("iam.user");
        assertThat(context.actionCode()).isEqualTo("selfProfile");
        assertThat(context.actionPolicy().accessMode()).isEqualTo(ActionAccessMode.LOGIN_REQUIRED);
        assertThat(context.actionPolicy().actionAuth()).isFalse();
    }

    @PlatformStaticActionScope(module = "mr.knowledge_file")
    static class ProjectionController {
        @ActionEndpoint(PlatformAction.CREATE)
        public void issueTransferTicket() {
        }
    }

    @PlatformStaticActionDeclaration(module = "iam.user")
    static class ProfileController {
        @CustomActionEndpoint(value = "selfProfile", accessMode = ActionAccessMode.LOGIN_REQUIRED,
                actionAuth = false)
        public void updateProfile() {
        }
    }
}
