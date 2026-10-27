package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfiguredTextSeedBootstrapTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lifecycleBaselinesDoNotUseTheTenantOperatorsApplicationAccess(boolean organization) {
        var seeds = mock(ConfiguredTextSeedService.class);
        var bootstrap = new ConfiguredTextSeedBootstrap(seeds);
        var operator = CurrentUser.tenantUser("tenant-admin", "Admin", "tenant-a");
        doAnswer(invocation -> {
            assertThat(CurrentUserContext.isSystem()).isTrue();
            assertThat(CurrentUserContext.currentUser().orElseThrow().userId()).isNotEqualTo(operator.userId());
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            return 0;
        }).when(seeds).seedTenant("tenant-a");

        try (var user = CurrentUserContext.use(operator);
             var tenant = TenantContext.use("tenant-a")) {
            if (organization) bootstrap.afterOrganizationCreated("tenant-a", "org-a");
            else bootstrap.afterTenantCreated("tenant-a");
            assertThat(CurrentUserContext.currentUser()).contains(operator);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
        }
        verify(seeds).seedTenant("tenant-a");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedBaselineMaintenanceRestoresTheCallerAndPropagatesTheFailure(boolean organization) {
        var seeds = mock(ConfiguredTextSeedService.class);
        var bootstrap = new ConfiguredTextSeedBootstrap(seeds);
        var operator = CurrentUser.tenantUser("tenant-admin", "Admin", "tenant-a");
        var failure = new IllegalStateException("baseline publication failed");
        doAnswer(invocation -> {
            assertThat(CurrentUserContext.isSystem()).isTrue();
            throw failure;
        }).when(seeds).seedTenant("tenant-a");

        try (var user = CurrentUserContext.use(operator);
             var tenant = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> {
                if (organization) bootstrap.afterOrganizationCreated("tenant-a", "org-a");
                else bootstrap.afterTenantCreated("tenant-a");
            }).isSameAs(failure);
            assertThat(CurrentUserContext.currentUser()).contains(operator);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
        }
    }
}
