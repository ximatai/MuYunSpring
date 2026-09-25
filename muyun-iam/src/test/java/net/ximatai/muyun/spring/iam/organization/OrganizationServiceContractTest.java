package net.ximatai.muyun.spring.iam.organization;

import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeCatalogResolver;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.OrganizationCreationProvisioner;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.ability.TreeAbility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.mockito.Mockito;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import net.ximatai.muyun.spring.iam.role.RoleDataGrantActionDao;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import java.util.concurrent.atomic.AtomicReference;

import java.util.List;
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
        OrganizationService service = new OrganizationService(
                mock(OrganizationDao.class),
                activeTenantVerifier(),
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class));

        assertThat(service.getModuleAlias()).isEqualTo("iam.organization");
    }

    @Test
    void shouldFillOrganizationDefaultsThroughCrudAbility() {
        OrganizationDao dao = mock(OrganizationDao.class);
        when(dao.insert(any())).thenReturn("org-1");
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        OrganizationService service = new OrganizationService(
                dao,
                tenantVerifier,
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class));
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
        OrganizationService service = new OrganizationService(
                mock(OrganizationDao.class),
                activeTenantVerifier(),
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class));

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
        OrganizationService service = new OrganizationService(
                mock(OrganizationDao.class),
                tenantVerifier,
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class));

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
        OrganizationService service = new OrganizationService(
                dao,
                activeTenantVerifier(),
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            Organization branch = organization("BR-001", "Branch");
            service.insert(branch);
            assertThat(branch.getCode()).isEqualTo("BR-001");

            assertThatThrownBy(() -> service.insert(organization(" ", "Blank Code")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("code");
        }
    }

    @Test
    void shouldResolveOrganizationIdsFromSelfToRoot() {
        OrganizationService service = spy(new OrganizationService(
                mock(OrganizationDao.class),
                activeTenantVerifier(),
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class)));
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

    @Test
    void disabledOrganizationCanCreateItsDefaultRole() {
        AtomicReference<Organization> stored = new AtomicReference<>();
        OrganizationDao dao = mock(OrganizationDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenAnswer(call -> stored.get() == null ? List.of() : List.of(stored.get()));
        when(dao.insert(any())).thenAnswer(call -> {
            Organization row = call.getArgument(0);
            stored.set(row);
            return row.getId();
        });
        var beans = new StaticListableBeanFactory();
        var organizations = new OrganizationService(
                dao,
                activeTenantVerifier(),
                beans.getBeanProvider(OrganizationCreationProvisioner.class));
        var roleDao = mock(RoleDao.class);
        var roles = new RoleService(
                roleDao,
                mock(AccountRoleGrantDao.class),
                mock(EmploymentRoleGrantDao.class),
                mock(RoleActionDao.class),
                activeTenantVerifier(),
                RoleActionGrantVerifier.platformActionsOnly(),
                mock(UserAccountService.class),
                mock(EmployeeService.class),
                mock(EmployeePositionService.class),
                mock(EmployeeAccountService.class),
                organizations,
                mock(RoleDataGrantActionDao.class),
                mock(TenantApplicationService.class),
                new StaticListableBeanFactory().getBeanProvider(ReferenceDependencyScopeCatalogResolver.class));
        var templates = mock(BuiltInRolePermissionTemplateService.class);
        beans.addBean("roles", new DefaultOrganizationRoleProvisioner(roles, templates));
        Organization disabled = organization("disabled", "Disabled organization");
        disabled.setEnabled(false);
        try (var tenant = TenantContext.use("tenant_a")) {
            organizations.insert(disabled);
        }
        var captor = org.mockito.ArgumentCaptor.forClass(Role.class);
        verify(roleDao).insert(captor.capture());
        assertThat(captor.getValue().getOwnerScopeId()).isEqualTo(disabled.getId());
        assertThat(disabled.getEnabled()).isFalse();
        verify(templates).applyOrganizationAdminTemplate(captor.getValue().getId());
    }

    @Test
    void replayRequiresValidOrganizationAndActiveTenantEvenWithoutExtensions() {
        OrganizationDao dao = mock(OrganizationDao.class);
        var service = new OrganizationService(
                dao,
                activeTenantVerifier(),
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class));
        assertThatThrownBy(() -> service.provisionOrganization(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.provisionOrganization("missing")).hasMessageContaining("tenant context");
        try (var tenant = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.provisionOrganization("missing")).hasMessageContaining("does not exist");
            Organization foreign = organization("foreign", "Foreign");
            foreign.setTenantId("tenant_b");
            when(dao.query(any(Criteria.class), any(PageRequest.class), any(Sort[].class))).thenReturn(List.of(foreign));
            try (var bypass = TenantContext.bypassTenantFilter("test caller bypass")) {
                assertThatThrownBy(() -> service.provisionOrganization("foreign")).hasMessageContaining("does not exist");
            }
        }
    }

    @Test
    void hierarchyReadsEachStructuralRecordOnceWithoutBusinessReadHooks() {
        var service = spy(new OrganizationService(
                mock(OrganizationDao.class),
                activeTenantVerifier(),
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class)));
        Organization parent = organization("parent", "Parent"); parent.setId("parent");
        Organization child = organization("child", "Child"); child.setId("child"); child.setParentId("parent");
        doReturn(parent).when(service).selectActiveRaw("parent");
        doReturn(child).when(service).selectActiveRaw("child");
        assertThat(service.organizationIdsFromSelfToRoot("child")).containsExactly("child", "parent");
        verify(service).selectActiveRaw("child");
        verify(service).selectActiveRaw("parent");
        verify(service, Mockito.never()).afterSelect(any());
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
