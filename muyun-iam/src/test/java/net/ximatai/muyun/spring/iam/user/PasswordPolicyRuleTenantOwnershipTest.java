package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordPolicyRuleTenantOwnershipTest {
    @org.junit.jupiter.api.BeforeEach
    void installMutationHost() {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.configureMutationTransactionOperator(
                new net.ximatai.muyun.spring.ability.MutationTransactionOperator() {
                    @Override public <T> T execute(java.util.function.Supplier<T> work) { return work.get(); }
                    @Override public void lock(String scope, String key) { }
                });
    }

    @org.junit.jupiter.api.AfterEach
    void resetMutationHost() {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.resetMutationTransactionOperator();
    }
    private final PasswordPolicyRuleDao dao = mock(PasswordPolicyRuleDao.class);
    private final PasswordPolicyRuleService service = new PasswordPolicyRuleService(dao);

    public PasswordPolicyRuleTenantOwnershipTest() {
        when(dao.insert(any())).thenReturn("rule-1");
    }

    @Test
    void tenantContextOwnsRulesCreatedWithoutAnExplicitPayloadTenant() {
        PasswordPolicyRule rule = rule(null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.insert(rule);
        }

        assertThat(rule.getTenantId()).isEqualTo("tenant-a");
        assertThat(rule.getScopeType()).isEqualTo(PasswordPolicyScopeType.TENANT);
        assertThat(rule.getScopeId()).isEqualTo("tenant-a");
        assertThat(rule.getScopeKey()).isEqualTo("tenant:tenant-a");
    }

    @Test
    void tenantPayloadCannotForgeAnotherTenant() {
        PasswordPolicyRule rule = rule("tenant-b");
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> service.insert(rule))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @Test
    void systemScopeMayTargetAnExplicitTenantOrThePlatformDefault() {
        PasswordPolicyRule tenantRule = rule("tenant-x");
        try (TenantContext.Scope ignored = TenantContext.system("test tenant rule")) {
            service.insert(tenantRule);
        }
        assertThat(tenantRule.getScopeType()).isEqualTo(PasswordPolicyScopeType.TENANT);
        assertThat(tenantRule.getScopeId()).isEqualTo("tenant-x");
        assertThat(tenantRule.getScopeKey()).isEqualTo("tenant:tenant-x");

        PasswordPolicyRule platformRule = rule(null);
        try (TenantContext.Scope ignored = TenantContext.system("test platform rule")) {
            service.insert(platformRule);
        }
        assertThat(platformRule.getTenantId()).isNull();
        assertThat(platformRule.getScopeType()).isEqualTo(PasswordPolicyScopeType.GLOBAL);
        assertThat(platformRule.getScopeId()).isNull();
        assertThat(platformRule.getScopeKey()).isEqualTo("global:");
    }

    @Test
    void withoutAExecutionContextAnExplicitTenantCannotBeForged() {
        PasswordPolicyRule rule = rule("tenant-b");
        assertThatThrownBy(() -> service.insert(rule))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    private PasswordPolicyRule rule(String tenantId) {
        PasswordPolicyRule rule = new PasswordPolicyRule();
        rule.setTenantId(tenantId);
        rule.setTitle("规则");
        rule.setPattern("^.{6,}$");
        rule.setMessage("至少 6 位");
        return rule;
    }
}
