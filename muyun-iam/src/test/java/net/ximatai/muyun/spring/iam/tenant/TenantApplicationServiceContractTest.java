package net.ximatai.muyun.spring.iam.tenant;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.ApplicationNotOpenedException;
import net.ximatai.muyun.spring.common.platform.TenantApplicationCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantApplicationServiceContractTest {
    @Test
    void shouldKeepIamApplicationWhenReconcilingTenantApplications() {
        TenantApplicationService service = new TenantApplicationService(mock(TenantApplicationDao.class), mock(TenantApplicationCatalog.class));

        assertThatThrownBy(() -> service.configureApplications("tenant_a", List.of("sales")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iam application must remain opened");
    }

    @Test
    void shouldTreatGloballyDisabledApplicationAsNotOpenedForExecution() {
        TenantApplicationDao dao = mock(TenantApplicationDao.class);
        TenantApplication existing = new TenantApplication();
        existing.setTenantId("tenant_a");
        existing.setApplicationAlias("sales");
        when(dao.query(org.mockito.ArgumentMatchers.any(Criteria.class),
                org.mockito.ArgumentMatchers.any(PageRequest.class),
                org.mockito.ArgumentMatchers.<Sort[]>any())).thenReturn(List.of(existing));
        TenantApplicationCatalog applicationCatalog = mock(TenantApplicationCatalog.class);
        when(applicationCatalog.isEnabledForTenant("sales")).thenReturn(false);
        TenantApplicationService service = new TenantApplicationService(dao, applicationCatalog);

        assertThat(service.isApplicationAvailable("tenant_a", "sales")).isFalse();
        assertThatThrownBy(() -> service.requireApplicationOpened("tenant_a", "sales"))
                .isInstanceOf(ApplicationNotOpenedException.class);
    }

    @Test
    void shouldRejectGloballyDisabledApplicationBeforeReconcilingExistingEntitlements() {
        TenantApplicationCatalog applicationCatalog = mock(TenantApplicationCatalog.class);
        doThrow(new IllegalArgumentException("application is not active: sales"))
                .when(applicationCatalog).requireEnabledForTenant("sales");
        TenantApplicationService service = new TenantApplicationService(mock(TenantApplicationDao.class), applicationCatalog);

        assertThatThrownBy(() -> service.configureApplications("tenant_a", List.of("iam", "sales")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("application is not active: sales");

        verify(applicationCatalog).requireEnabledForTenant("iam");
        verify(applicationCatalog).requireEnabledForTenant("sales");
    }
}
