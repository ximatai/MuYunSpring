package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.ability.action.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordSecurityPolicyResolverTest {
    private final PasswordSecurityPolicyService policyService = mock(PasswordSecurityPolicyService.class);
    private final PasswordPolicyRuleService ruleService = mock(PasswordPolicyRuleService.class);
    private final PasswordSecurityPolicyResolver resolver = new PasswordSecurityPolicyResolver(policyService, ruleService);

    @Test
    void missingDatabaseDefaultFailsClosed() {
        when(policyService.findPlatformPolicy()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.resolve("t1"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not initialized");
    }

    @Test
    void tenantCustomComplexityUsesTheCompleteTenantRuleSet() {
        PasswordSecurityPolicy tenant = tenantPolicy("t1");
        tenant.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        when(policyService.findTenantPolicy("t1")).thenReturn(Optional.of(tenant));
        when(policyService.findPlatformPolicy()).thenReturn(Optional.of(platformDefault()));
        when(ruleService.tenantActiveRules("t1")).thenReturn(List.of(rule("^.*\\d.*$", "密码必须包含数字")));

        ResolvedPasswordSecurityPolicy snapshot = resolver.resolve("t1");

        assertThat(snapshot.complexitySource()).isEqualTo(ResolvedPasswordSecurityPolicy.ComplexitySource.TENANT);
        assertThat(snapshot.complexityRules()).extracting(EffectivePasswordRule::pattern)
                .containsExactly("^.*\\d.*$");
    }

    @Test
    void emptyTenantCustomRuleSetFallsBackToMinimumLengthNotThePlatformRules() {
        PasswordSecurityPolicy tenant = tenantPolicy("t1");
        tenant.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        when(policyService.findTenantPolicy("t1")).thenReturn(Optional.of(tenant));
        when(policyService.findPlatformPolicy()).thenReturn(Optional.of(platformDefault()));
        when(ruleService.tenantActiveRules("t1")).thenReturn(List.of());
        when(ruleService.platformActiveRules()).thenReturn(List.of(rule("^.*[A-Z].*$", "平台规则不应生效")));

        ResolvedPasswordSecurityPolicy snapshot = resolver.resolve("t1");

        assertThat(snapshot.complexitySource()).isEqualTo(ResolvedPasswordSecurityPolicy.ComplexitySource.DEFAULT);
        assertThat(snapshot.complexityRules()).extracting(EffectivePasswordRule::pattern).containsExactly("^.{6,}$");
    }

    @Test
    void inheritingComplexityUsesPlatformRulesWhileCustomLockoutStaysIndependent() {
        PasswordSecurityPolicy tenant = tenantPolicy("t1");
        tenant.setComplexityMode(PasswordSecurityPolicyMode.INHERIT);
        tenant.setLockoutMode(PasswordSecurityPolicyMode.CUSTOM);
        tenant.setMaxFailedAttempts(3);
        tenant.setLockDurationSeconds(60);
        when(policyService.findTenantPolicy("t1")).thenReturn(Optional.of(tenant));
        when(policyService.findPlatformPolicy()).thenReturn(Optional.of(platformDefault()));
        when(ruleService.platformActiveRules()).thenReturn(List.of(rule("^.*[A-Z].*$", "必须包含大写字母")));

        ResolvedPasswordSecurityPolicy snapshot = resolver.resolve("t1");

        assertThat(snapshot.complexitySource()).isEqualTo(ResolvedPasswordSecurityPolicy.ComplexitySource.PLATFORM);
        assertThat(snapshot.complexityRules()).extracting(EffectivePasswordRule::pattern)
                .containsExactly("^.*[A-Z].*$");
        assertThat(snapshot.lockoutSource()).isEqualTo(ResolvedPasswordSecurityPolicy.LockoutSource.TENANT);
        assertThat(snapshot.maxFailedAttempts()).isEqualTo(3);
        assertThat(snapshot.lockDuration()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void tenantInheritsPersistedPlatformLockout() {
        PasswordSecurityPolicy platform = tenantPolicy(null);
        platform.setMaxFailedAttempts(2);
        platform.setLockDurationSeconds(120);
        when(policyService.findTenantPolicy("t1")).thenReturn(Optional.empty());
        when(policyService.findPlatformPolicy()).thenReturn(Optional.of(platform));
        when(ruleService.platformActiveRules()).thenReturn(List.of());

        ResolvedPasswordSecurityPolicy snapshot = resolver.resolve("t1");

        assertThat(snapshot.lockoutSource()).isEqualTo(ResolvedPasswordSecurityPolicy.LockoutSource.PLATFORM_DB);
        assertThat(snapshot.maxFailedAttempts()).isEqualTo(2);
        assertThat(snapshot.lockDuration()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void validateComplexityRejectsAgainstTheTargetTenantsRules() {
        PasswordSecurityPolicy tenant = tenantPolicy("t1");
        tenant.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        when(policyService.findTenantPolicy("t1")).thenReturn(Optional.of(tenant));
        when(policyService.findPlatformPolicy()).thenReturn(Optional.of(platformDefault()));
        when(ruleService.tenantActiveRules("t1")).thenReturn(List.of(rule("^.*\\d.*$", "密码必须包含数字")));

        assertThatThrownBy(() -> resolver.validateComplexity("letters", "t1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码必须包含数字");
    }

    private PasswordSecurityPolicy platformDefault() {
        return new PasswordSecurityPolicyService(mock(PasswordSecurityPolicyDao.class),
                mock(net.ximatai.muyun.spring.iam.tenant.TenantService.class), mock(PasswordPolicyRuleService.class)).initialData().getFirst();
    }

    private PasswordSecurityPolicy tenantPolicy(String tenantId) {
        PasswordSecurityPolicy policy = new PasswordSecurityPolicy();
        policy.setTenantId(tenantId);
        policy.setComplexityMode(PasswordSecurityPolicyMode.INHERIT);
        policy.setLockoutMode(PasswordSecurityPolicyMode.INHERIT);
        return policy;
    }

    private PasswordPolicyRule rule(String pattern, String message) {
        PasswordPolicyRule rule = new PasswordPolicyRule();
        rule.setPattern(pattern);
        rule.setMessage(message);
        rule.setEnabled(Boolean.TRUE);
        return rule;
    }
}
