package net.ximatai.muyun.spring.iam.organization;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.OrganizationCreationProvisioner;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.ability.TreeAbility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class OrganizationServiceContractTest {
    @Test
    void shouldExposeStableModuleAlias() {
        OrganizationService service = new OrganizationService(mock(OrganizationDao.class), activeTenantVerifier());

        assertThat(service.getModuleAlias()).isEqualTo("iam.organization");
    }

    @Test
    void shouldFillOrganizationDefaultsThroughCrudAbility() {
        OrganizationDao dao = mock(OrganizationDao.class);
        when(dao.insert(any())).thenReturn("org-1");
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        OrganizationService service = new OrganizationService(dao, tenantVerifier);
        Organization organization = organization("HQ", "Headquarters");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(organization);
        }

        assertThat(organization.getEnabled()).isTrue();
        assertThat(organization.getParentId()).isEqualTo(TreeAbility.ROOT_ID);
        assertThat(organization.getTenantId()).isEqualTo("tenant_a");
        verify(tenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldRequireTenantContextForOrganizationMutation() {
        OrganizationService service = new OrganizationService(mock(OrganizationDao.class), activeTenantVerifier());

        assertThatThrownBy(() -> service.insert(organization("HQ", "Headquarters")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("tenant context");

        try (TenantContext.Scope ignored = TenantContext.system("test system context")) {
            assertThatThrownBy(() -> service.insert(organization("HQ", "Headquarters")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("tenant context");
        }
    }

    @Test
    void shouldRejectInactiveTenantForOrganizationMutation() {
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        doThrow(new PlatformException("Tenant is not active: tenant_a"))
                .when(tenantVerifier).verifyActiveTenant("tenant_a");
        OrganizationService service = new OrganizationService(mock(OrganizationDao.class), tenantVerifier);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(organization("HQ", "Headquarters")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("not active");
        }
    }

    @Test
    void shouldRequireOrganizationCodeButAllowBusinessCodeShape() {
        OrganizationDao dao = mock(OrganizationDao.class);
        when(dao.insert(any())).thenReturn("org-1");
        OrganizationService service = new OrganizationService(dao, activeTenantVerifier());

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            Organization branch = organization("BR-001", "Branch");
            service.insert(branch);
            assertThat(branch.getCode()).isEqualTo("BR-001");

            assertThatThrownBy(() -> service.insert(organization(" ", "Blank Code")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("organizationCode");
        }
    }

    @Test
    void shouldResolveOrganizationIdsFromSelfToRoot() {
        OrganizationService service = spy(new OrganizationService(mock(OrganizationDao.class), activeTenantVerifier()));
        doReturn(List.of("group-1", "dept-1")).when(service).ancestorIdsAndSelf("dept-1");

        assertThat(service.organizationIdsFromSelfToRoot("dept-1"))
                .containsExactly("dept-1", "group-1");
    }

    @Test
    void shouldRunOrganizationCreationProvisionersAndAllowReplay() {
        OrganizationDao dao = mock(OrganizationDao.class);
        when(dao.insert(any())).thenReturn("org-1");
        Organization saved = organization("HQ", "Headquarters");
        saved.setId("org-1");
        saved.setTenantId("tenant_a");
        when(dao.query(any(), any())).thenReturn(List.of(), List.of(saved));
        OrganizationCreationProvisioner provisioner = mock(OrganizationCreationProvisioner.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<OrganizationCreationProvisioner> provisioners = mock(ObjectProvider.class);
        when(provisioners.orderedStream()).thenAnswer(invocation -> Stream.of(provisioner));
        OrganizationService service = new OrganizationService(dao, activeTenantVerifier(), provisioners);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(organization("HQ", "Headquarters"));
            service.provisionOrganization("org-1");
        }

        verify(provisioner, times(2)).afterOrganizationCreated("tenant_a", "org-1");
    }

    private Organization organization(String code, String title) {
        Organization organization = new Organization();
        organization.setCode(code);
        organization.setTitle(title);
        return organization;
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }
}
