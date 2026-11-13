package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.EffectivePasswordRule;
import net.ximatai.muyun.spring.iam.user.LoginLockoutPolicy;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicy;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyMode;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyResolver;
import net.ximatai.muyun.spring.iam.user.PasswordSecurityPolicyService;
import net.ximatai.muyun.spring.iam.user.ResolvedPasswordSecurityPolicy;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRule;
import net.ximatai.muyun.spring.iam.user.PasswordPolicyRuleService;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.platform.web.ActionEndpointContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class PasswordSecurityPolicyWebControllerTest {
    private final PasswordSecurityPolicyService policyService = mock(PasswordSecurityPolicyService.class);
    private final PasswordSecurityPolicyResolver resolver = mock(PasswordSecurityPolicyResolver.class);

    private PasswordSecurityPolicyWebController controller() {
        return controller(mock(PasswordPolicyRuleService.class), mock(ActionExecutionPolicyService.class),
                mock(ActionEndpointContextResolver.class));
    }

    private PasswordSecurityPolicyWebController controller(PasswordPolicyRuleService rules,
            ActionExecutionPolicyService permissions, ActionEndpointContextResolver contexts) {
        PasswordSecurityPolicyWebController controller =
                new PasswordSecurityPolicyWebController(resolver, new net.ximatai.muyun.spring.iam.user.PasswordSecurityWorkspaceService(policyService, rules), permissions, contexts);
        ReflectionTestUtils.setField(controller, "service", policyService);
        return controller;
    }

    @Test
    void effectiveViewProjectsTheResolvedSourcesAndStoredModes() {
        PasswordSecurityPolicy stored = new PasswordSecurityPolicy();
        stored.setId("policy-1");
        stored.setTenantId("t1");
        stored.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        stored.setLockoutMode(PasswordSecurityPolicyMode.CUSTOM);
        stored.setMaxFailedAttempts(3);
        stored.setLockDurationSeconds(60);
        when(policyService.findManagedByScope("t1")).thenReturn(Optional.of(stored));
        ResolvedPasswordSecurityPolicy snapshot = new ResolvedPasswordSecurityPolicy(
                "t1",
                ResolvedPasswordSecurityPolicy.ComplexitySource.TENANT,
                List.of(new EffectivePasswordRule("^.*\\d.*$", TextReference.code("k", "密码必须包含数字"))),
                3, Duration.ofSeconds(60), ResolvedPasswordSecurityPolicy.LockoutSource.TENANT);
        when(resolver.resolve(eq("t1"))).thenReturn(snapshot);
        when(resolver.resolve(eq(null))).thenReturn(snapshot);

        PasswordSecurityPolicyWebController.EffectivePolicyView view;
        try (CurrentUserContext.Scope actor = CurrentUserContext.use(CurrentUser.systemUser("admin", "Admin"));
             TenantContext.Scope ignored = TenantContext.system("test effective view")) {
            view = controller().effective("t1");
        }

        assertThat(view.tenantId()).isEqualTo("t1");
        assertThat(view.policyId()).isEqualTo("policy-1");
        assertThat(view.complexityMode()).isEqualTo("custom");
        assertThat(view.complexitySource()).isEqualTo("TENANT");
        assertThat(view.complexityRules()).extracting(PasswordSecurityPolicyWebController.RuleView::message)
                .containsExactly("密码必须包含数字");
        assertThat(view.lockoutSource()).isEqualTo("TENANT");
        assertThat(view.maxFailedAttempts()).isEqualTo(3);
        assertThat(view.lockDurationSeconds()).isEqualTo(60);
    }

    @Test
    void tenantCallerCannotRequestAnotherTenant() {
        PasswordSecurityPolicyWebController controller = controller();
        try (TenantContext.Scope ignored = TenantContext.use("t1")) {
            assertThatThrownBy(() -> controller.effective("t2"))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @Test
    void systemExecutionScopeDoesNotGrantTenantActorCrossTenantAccess() {
        try (CurrentUserContext.Scope actor = CurrentUserContext.use(CurrentUser.tenantUser("admin", "Admin", "t1"));
             TenantContext.Scope ignored = TenantContext.system("infrastructure scope")) {
            assertThatThrownBy(() -> controller().effective("t2"))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @Test
    void tenantCallerWithoutRequestUsesItsOwnTenant() {
        when(policyService.findManagedByScope("t1")).thenReturn(Optional.empty());
        ResolvedPasswordSecurityPolicy snapshot = new ResolvedPasswordSecurityPolicy(
                "t1", ResolvedPasswordSecurityPolicy.ComplexitySource.DEFAULT,
                List.of(new EffectivePasswordRule("^.{6,}$", TextReference.code("k", "密码长度不能少于 6 位"))),
                5, Duration.ofMinutes(15), ResolvedPasswordSecurityPolicy.LockoutSource.PLATFORM_DB);
        when(resolver.resolve(eq("t1"))).thenReturn(snapshot);
        when(resolver.resolve(eq(null))).thenReturn(snapshot);

        PasswordSecurityPolicyWebController.EffectivePolicyView view;
        try (TenantContext.Scope ignored = TenantContext.use("t1")) {
            view = controller().effective(null);
        }
        assertThat(view.tenantId()).isEqualTo("t1");
        assertThat(view.complexityMode()).isEqualTo("inherit");
    }

    @Test
    void rejectsStaleRuleSetBeforeAnyMutation() {
        PasswordPolicyRuleService rules = mock(PasswordPolicyRuleService.class);
        PasswordPolicyRule existing = new PasswordPolicyRule();
        existing.setId("r1"); existing.setTenantId("t1"); existing.setVersion(4);
        when(rules.managedRulesForScope("t1")).thenReturn(List.of(existing));
        PasswordSecurityPolicyWebController controller = controller(rules, mock(ActionExecutionPolicyService.class),
                mock(ActionEndpointContextResolver.class));
        var request = new PasswordSecurityPolicyWebController.WorkspaceSave("t1", null,
                List.of(new PasswordSecurityPolicyWebController.RuleVersion("r1", 3)), List.of(existing));
        try (CurrentUserContext.Scope actor = CurrentUserContext.use(CurrentUser.tenantUser("admin", "Admin", "t1"));
             TenantContext.Scope ignored = TenantContext.use("t1")) {
            assertThatThrownBy(() -> controller.saveWorkspace(request)).isInstanceOf(OptimisticLockException.class);
        }
        verify(rules).lockWorkspace("t1");
        verify(rules, never()).update(any());
        verify(rules, never()).delete(any(), any());
    }

    @Test
    void rejectsNullEnabledBeforeItCanBeNormalizedIntoAnUnauthorizedEnable() {
        PasswordPolicyRuleService rules = mock(PasswordPolicyRuleService.class);
        PasswordPolicyRule existing = new PasswordPolicyRule();
        existing.setId("r1"); existing.setTenantId("t1"); existing.setVersion(1); existing.setEnabled(false);
        PasswordPolicyRule draft = new PasswordPolicyRule();
        draft.setId("r1"); draft.setTenantId("t1"); draft.setVersion(1); draft.setEnabled(null);
        when(rules.managedRulesForScope("t1")).thenReturn(List.of(existing));
        PasswordSecurityPolicyWebController controller = controller(rules, mock(ActionExecutionPolicyService.class),
                mock(ActionEndpointContextResolver.class));
        var request = new PasswordSecurityPolicyWebController.WorkspaceSave("t1", null,
                List.of(new PasswordSecurityPolicyWebController.RuleVersion("r1", 1)), List.of(draft));
        try (CurrentUserContext.Scope actor = CurrentUserContext.use(CurrentUser.tenantUser("admin", "Admin", "t1"));
             TenantContext.Scope ignored = TenantContext.use("t1")) {
            assertThatThrownBy(() -> controller.saveWorkspace(request)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("enabled");
        }
        verify(rules, never()).update(any());
    }

    @Test
    void rejectsAnUnauthorizedRuleUpdateBeforeWritingIt() {
        PasswordPolicyRuleService rules = mock(PasswordPolicyRuleService.class);
        PasswordPolicyRule existing = new PasswordPolicyRule();
        existing.setId("r1"); existing.setTenantId("t1"); existing.setVersion(1);
        existing.setTitle("old"); existing.setEnabled(true);
        PasswordPolicyRule draft = new PasswordPolicyRule();
        draft.setId("r1"); draft.setTenantId("t1"); draft.setVersion(1);
        draft.setTitle("new"); draft.setEnabled(true);
        when(rules.managedRulesForScope("t1")).thenReturn(List.of(existing));
        ActionEndpointContextResolver contexts = mock(ActionEndpointContextResolver.class);
        ActionExecutionPolicyService permissions = mock(ActionExecutionPolicyService.class);
        when(contexts.resolveActionCode(eq(PasswordPolicyRuleService.MODULE_ALIAS), eq("update"), any()))
                .thenReturn(net.ximatai.muyun.spring.common.platform.ActionExecutionContext.ofPlatformAction(
                        PasswordPolicyRuleService.MODULE_ALIAS,
                        net.ximatai.muyun.spring.common.platform.PlatformAction.UPDATE,
                        java.util.Set.of("r1"), Optional.empty()));
        org.mockito.Mockito.doThrow(new PlatformAccessDeniedException("denied"))
                .when(permissions).requireAuthorized(any());
        PasswordSecurityPolicyWebController controller = controller(rules, permissions, contexts);
        var request = new PasswordSecurityPolicyWebController.WorkspaceSave("t1", null,
                List.of(new PasswordSecurityPolicyWebController.RuleVersion("r1", 1)), List.of(draft));
        try (CurrentUserContext.Scope actor = CurrentUserContext.use(CurrentUser.tenantUser("admin", "Admin", "t1"));
             TenantContext.Scope ignored = TenantContext.use("t1")) {
            assertThatThrownBy(() -> controller.saveWorkspace(request)).isInstanceOf(PlatformAccessDeniedException.class);
        }
        verify(rules, never()).update(any());
    }
}
