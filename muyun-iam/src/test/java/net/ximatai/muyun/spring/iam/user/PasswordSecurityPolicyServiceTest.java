package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PasswordSecurityPolicyServiceTest {
    @Test
    void initializesDatabaseDefaultsBeforeTheAdminWithoutReconcilingOperatorValues() {
        assertThat(service.initialDataOptions().policy())
                .isEqualTo(net.ximatai.muyun.spring.ability.initialdata.InitialDataPolicy.CREATE_IF_MISSING);
        assertThat(service.initialDataOptions().order()).isLessThan(50);
        PasswordSecurityPolicy defaults = service.initialData().getFirst();
        assertThat(defaults.getMaxFailedAttempts()).isEqualTo(5);
        assertThat(defaults.getLockDurationSeconds()).isEqualTo(900);
        defaults.setMaxFailedAttempts(2);
        defaults.setLockDurationSeconds(60);
        when(dao.findById(PasswordSecurityPolicyService.PLATFORM_POLICY_ID)).thenReturn(defaults);
        new net.ximatai.muyun.spring.platform.initialdata.InitialDataExecutor(java.util.List.of(service), java.util.List.of())
                .initializeAll();
        org.mockito.Mockito.verify(dao, org.mockito.Mockito.never()).updateById(org.mockito.ArgumentMatchers.any());
        assertThat(defaults.getMaxFailedAttempts()).isEqualTo(2);
    }

    @Test
    void platformDefaultCannotBeDeletedOrHaveAnUnspecifiedThreshold() {
        assertThat(service.availability(PasswordSecurityPolicyService.MODULE_ALIAS, "delete", PasswordSecurityPolicyService.PLATFORM_POLICY_ID))
                .hasValueSatisfying(decision -> assertThat(decision.available()).isFalse());
        assertThat(service.availability(PasswordSecurityPolicyService.MODULE_ALIAS, "delete", PasswordSecurityPolicyService.tenantPolicyId("tenant-a")))
                .isEmpty();
        assertThatThrownBy(() -> service.beforeDelete(PasswordSecurityPolicyService.PLATFORM_POLICY_ID))
                .isInstanceOf(PlatformException.class).hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> service.normalizeBeforeMutation(new PasswordSecurityPolicy()))
                .isInstanceOf(PlatformException.class).hasMessageContaining("threshold");
        service.beforeDelete(PasswordSecurityPolicyService.tenantPolicyId("tenant-a"));
    }
    private final PasswordSecurityPolicyDao dao = mock(PasswordSecurityPolicyDao.class);
    private final TenantService tenantService = mock(TenantService.class);
    private final PasswordPolicyRuleService rules = mock(PasswordPolicyRuleService.class);
    private final PasswordSecurityPolicyService service = new PasswordSecurityPolicyService(dao, tenantService, rules);

    public PasswordSecurityPolicyServiceTest() {
        when(dao.count(any())).thenReturn(0L);
        when(dao.insert(any())).thenReturn("generated");
    }

    @Test
    void deterministicScopeIdentityFitsStandardIdLength() {
        assertThat(PasswordSecurityPolicyService.PLATFORM_POLICY_ID.length()).isLessThanOrEqualTo(32);
        String tenantId = PasswordSecurityPolicyService.tenantPolicyId("tenant-a");
        assertThat(tenantId.length()).isLessThanOrEqualTo(32);
        assertThat(tenantId).isEqualTo(PasswordSecurityPolicyService.tenantPolicyId("tenant-a"));
        assertThat(tenantId).isNotEqualTo(PasswordSecurityPolicyService.tenantPolicyId("tenant-b"));
    }

    @Test
    void tenantCallersOwnTheirTenantAndCannotForgePayload() {
        PasswordSecurityPolicy policy = new PasswordSecurityPolicy();
        policy.setTenantId(null);
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.insert(policy);
        }
        assertThat(policy.getTenantId()).isEqualTo("tenant-a");
        assertThat(policy.getId()).isEqualTo(PasswordSecurityPolicyService.tenantPolicyId("tenant-a"));
        assertThat(policy.getOwnershipScopeKey()).isEqualTo("T:tenant-a");

        PasswordSecurityPolicy forged = new PasswordSecurityPolicy();
        forged.setTenantId("tenant-b");
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> service.insert(forged))
                    .isInstanceOf(PlatformAccessDeniedException.class);
        }
    }

    @Test
    void missingContextIsNotAuthorization() {
        PasswordSecurityPolicy policy = new PasswordSecurityPolicy();
        assertThatThrownBy(() -> service.insert(policy))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test
    void platformRecordUsesFixedIdentityAndLocalModes() {
        PasswordSecurityPolicy policy = service.initialData().getFirst();
        try (TenantContext.Scope ignored = TenantContext.system("test platform policy")) {
            service.insert(policy);
        }
        assertThat(policy.getId()).isEqualTo(PasswordSecurityPolicyService.PLATFORM_POLICY_ID);
        assertThat(policy.getOwnershipScopeKey()).isEqualTo(PasswordSecurityPolicyService.PLATFORM_SCOPE_KEY);
        assertThat(policy.getComplexityMode()).isEqualTo(PasswordSecurityPolicyMode.CUSTOM);
        assertThat(policy.getLockoutMode()).isEqualTo(PasswordSecurityPolicyMode.CUSTOM);
    }

    @Test
    void ownershipCannotBeMigratedAfterCreation() {
        PasswordSecurityPolicy existing = new PasswordSecurityPolicy();
        existing.setId(PasswordSecurityPolicyService.tenantPolicyId("tenant-a"));
        existing.setTenantId("tenant-a");
        PasswordSecurityPolicy incoming = new PasswordSecurityPolicy();
        incoming.setId(PasswordSecurityPolicyService.tenantPolicyId("tenant-b"));
        incoming.setTenantId("tenant-b");

        assertThatThrownBy(() -> service.allowsTenantOwnershipChange(existing, incoming))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ownership cannot be changed");
    }

    @Test
    void arbitraryIncomingIdIsRejected() {
        PasswordSecurityPolicy policy = new PasswordSecurityPolicy();
        policy.setTenantId("tenant-a");
        policy.setId("attacker-chosen-id");
        assertThatThrownBy(() -> service.normalizeBeforeMutation(policy))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("derived from its ownership scope");
    }

    @Test
    void customLockoutRequiresBoundedValues() {
        assertThatThrownBy(() -> service.normalizeBeforeMutation(customLockout(null, null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("max failed attempts");
        assertThatThrownBy(() -> service.normalizeBeforeMutation(customLockout(3, 0)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> service.normalizeBeforeMutation(customLockout(PasswordSecurityPolicyService.MAX_FAILED_ATTEMPTS_LIMIT + 1, 60)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must not exceed");
        assertThatThrownBy(() -> service.normalizeBeforeMutation(customLockout(3, PasswordSecurityPolicyService.MAX_LOCK_DURATION_SECONDS_LIMIT + 1)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("must not exceed");

        PasswordSecurityPolicy valid = customLockout(3, 60);
        service.normalizeBeforeMutation(valid);
        assertThat(valid.getMaxFailedAttempts()).isEqualTo(3);
        assertThat(valid.getLockDurationSeconds()).isEqualTo(60);
    }

    @Test
    void activatingCustomComplexityRequiresPreparedEnabledTenantRules() {
        PasswordSecurityPolicy incoming = customLockout(3, 60);
        PasswordSecurityPolicy existing = customLockout(3, 60);
        existing.setComplexityMode(PasswordSecurityPolicyMode.INHERIT);
        when(rules.tenantActiveRules("tenant-a")).thenReturn(java.util.List.of());
        assertThatThrownBy(() -> service.beforeUpdate(incoming, existing))
                .isInstanceOf(PlatformException.class).hasMessageContaining("enabled tenant password rule");
        when(rules.tenantActiveRules("tenant-a")).thenReturn(java.util.List.of(new PasswordPolicyRule()));
        service.beforeUpdate(incoming, existing);
        existing.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        when(rules.tenantActiveRules("tenant-a")).thenReturn(java.util.List.of());
        service.beforeUpdate(incoming, existing);
    }

    private PasswordSecurityPolicy customLockout(Integer maxFailedAttempts, Integer lockDurationSeconds) {
        PasswordSecurityPolicy policy = new PasswordSecurityPolicy();
        policy.setTenantId("tenant-a");
        policy.setComplexityMode(PasswordSecurityPolicyMode.CUSTOM);
        policy.setLockoutMode(PasswordSecurityPolicyMode.CUSTOM);
        policy.setMaxFailedAttempts(maxFailedAttempts);
        policy.setLockDurationSeconds(lockDurationSeconds);
        return policy;
    }
}
