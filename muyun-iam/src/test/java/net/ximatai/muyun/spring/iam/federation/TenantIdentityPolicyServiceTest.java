package net.ximatai.muyun.spring.iam.federation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.job.JobService;

import org.junit.jupiter.api.Test;

class TenantIdentityPolicyServiceTest {
    private final TenantIdentityPolicyService service =
            new TenantIdentityPolicyService(
                    mock(TenantIdentityPolicyDao.class),
                    tenantId -> {},
                    mock(IdentityProviderService.class),
                    mock(DirectorySyncConfiguration.class),
                    mock(JobService.class));

    @Test
    void rejectsProvisioningModesThatAreNotImplementedYet() {
        for (ProvisioningMode mode : new ProvisioningMode[]{ProvisioningMode.AUTO, ProvisioningMode.REVIEW}) {
            TenantIdentityPolicy policy = new TenantIdentityPolicy();
            policy.setProviderId("corp_idp");
            policy.setProvisioningMode(mode);

            assertThatThrownBy(() -> service.beforeInsert(policy)).isInstanceOf(PlatformException.class);
            assertThatThrownBy(() -> service.beforeUpdate(policy, policy)).isInstanceOf(PlatformException.class);
        }
        TenantIdentityPolicy disabled = new TenantIdentityPolicy();
        disabled.setProvisioningMode(ProvisioningMode.DISABLED);
        service.beforeInsert(disabled);
    }
}
