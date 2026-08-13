package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeAccountServiceContractTest {
    @Test
    void shouldExposeStableInternalModuleAlias() {
        EmployeeAccountService service = service(mock(EmployeeAccountDao.class));

        assertThat(service.getModuleAlias()).isEqualTo("iam.employee_account");
    }

    @Test
    void shouldFillBindingDefaultsThroughCrudAbility() {
        EmployeeAccountDao dao = mock(EmployeeAccountDao.class);
        when(dao.insert(any())).thenReturn("binding-1");
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        EmployeeAccountService service = service(dao, tenantVerifier);
        EmployeeAccount binding = binding("employee-1", "user-1");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(binding);
        }

        assertThat(binding.getTenantId()).isEqualTo("tenant_a");
        verify(tenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldValidateEmployeeAndUserBeforeSave() {
        EmployeeService employeeService = mock(EmployeeService.class);
        UserAccountService userAccountService = mock(UserAccountService.class);
        EmployeeAccountService service = new EmployeeAccountService(
                mock(EmployeeAccountDao.class), activeTenantVerifier(), employeeService, userAccountService);
        when(employeeService.requireEnabled(eq("employee-1"), any())).thenReturn(employee("employee-1"));
        when(userAccountService.requireEnabled(eq("user-1"), any())).thenReturn(user("user-1"));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.beforeInsert(binding("employee-1", "user-1"));
        }

        verify(employeeService).requireEnabled(eq("employee-1"), any());
        verify(userAccountService).requireEnabled(eq("user-1"), any());
    }

    @Test
    void shouldRejectBindingSameEmployeeToAnotherUser() {
        EmployeeAccount existing = binding("employee-1", "user-1");
        existing.setId("binding-existing");
        EmployeeAccountDao dao = mock(EmployeeAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(existing));
        EmployeeAccountService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(binding("employee-1", "user-2")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("only one user account");
        }
    }

    @Test
    void shouldRejectBindingSameUserToAnotherEmployee() {
        EmployeeAccount existing = binding("employee-2", "user-1");
        existing.setId("binding-existing");
        EmployeeAccountDao dao = mock(EmployeeAccountDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class)))
                .thenReturn(List.of())
                .thenReturn(List.of(existing));
        EmployeeAccountService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(binding("employee-1", "user-1")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("only one employee");
        }
    }

    @Test
    void shouldRejectInactiveSubjectBeforeInsert() {
        EmployeeService employeeService = mock(EmployeeService.class);
        UserAccountService userAccountService = mock(UserAccountService.class);
        when(employeeService.requireEnabled(eq("employee-1"), any())).thenReturn(employee("employee-1"));
        when(userAccountService.requireEnabled(eq("user-1"), any()))
                .thenThrow(new PlatformException("user account is not active: user-1"));
        EmployeeAccountDao dao = mock(EmployeeAccountDao.class);
        EmployeeAccountService service = new EmployeeAccountService(
                dao, activeTenantVerifier(), employeeService, userAccountService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(binding("employee-1", "user-1")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("user account is not active");
        }

        verify(dao, org.mockito.Mockito.never()).insert(any());
    }

    @Test
    void shouldRequireEmployeeAndUserFields() {
        EmployeeAccountService service = service(mock(EmployeeAccountDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(binding(" ", "user-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("employeeId");
            assertThatThrownBy(() -> service.insert(binding("employee-1", " ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("userId");
        }
    }

    @Test
    void shouldResolveEmployeeAccountAndEmployeeIdOfUser() {
        EmployeeAccountService service = spy(service(mock(EmployeeAccountDao.class)));
        EmployeeAccount binding = binding("employee-1", "user-1");
        doReturn(List.of(binding)).when(service).list(any(Criteria.class), any(PageRequest.class));

        assertThat(service.accountOfEmployee("employee-1")).isEqualTo(binding);
        assertThat(service.employeeIdOfUser("user-1")).isEqualTo("employee-1");
    }

    @Test
    void shouldDeleteBindingAndUserWhenRemovingAccount() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        EmployeeAccountService service = spy(new EmployeeAccountService(
                mock(EmployeeAccountDao.class), activeTenantVerifier(), mock(EmployeeService.class),
                userAccountService));
        EmployeeAccount binding = binding("employee-1", "user-1");
        binding.setId("binding-1");
        binding.setVersion(1);
        doReturn(binding).when(service).accountOfEmployee("employee-1");
        doReturn(1).when(service).delete(binding);
        when(userAccountService.select("user-1")).thenReturn(new UserAccount());
        when(userAccountService.delete("user-1")).thenReturn(1);

        assertThat(service.removeAccount("employee-1")).isEqualTo(1);

        verify(service).delete(binding);
        verify(userAccountService).delete("user-1");
    }

    @Test
    void shouldRemoveDanglingBindingWhenRemovingAccountUserIsMissing() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        EmployeeAccountService service = spy(new EmployeeAccountService(
                mock(EmployeeAccountDao.class), activeTenantVerifier(), mock(EmployeeService.class),
                userAccountService));
        EmployeeAccount binding = binding("employee-1", "user-1");
        binding.setId("binding-1");
        binding.setVersion(1);
        doReturn(binding).when(service).accountOfEmployee("employee-1");
        doReturn(1).when(service).delete(binding);
        when(userAccountService.select("user-1")).thenReturn(null);

        assertThat(service.removeAccount("employee-1")).isEqualTo(1);

        verify(service).delete(binding);
        verify(userAccountService, never()).delete(anyString());
        verify(userAccountService).cleanupDeletedUserReferences("user-1");
    }

    @Test
    void shouldIgnoreRemovingAccountWhenEmployeeHasNoAccount() {
        UserAccountService userAccountService = mock(UserAccountService.class);
        EmployeeAccountService service = spy(new EmployeeAccountService(
                mock(EmployeeAccountDao.class), activeTenantVerifier(), mock(EmployeeService.class),
                userAccountService));
        doReturn(null).when(service).accountOfEmployee("employee-1");

        assertThat(service.removeAccount("employee-1")).isZero();

        verify(userAccountService, never()).delete(anyString());
    }

    @Test
    void shouldProvisionUserAccountAndBindEmployeeInOneServiceAction() {
        EmployeeService employeeService = mock(EmployeeService.class);
        UserAccountService userAccountService = mock(UserAccountService.class);
        EmployeeAccountService service = spy(new EmployeeAccountService(
                mock(EmployeeAccountDao.class), activeTenantVerifier(), employeeService, userAccountService));
        Employee employee = employee("employee-1");
        employee.setTitle("Alice");
        employee.setMobile("13800000000");
        employee.setEmail("alice@example.com");
        employee.setOrganizationId("org-1");
        UserAccount account = new UserAccount();
        account.setUsername("alice");
        account.setPassword("secret1");
        UserAccount persisted = user("user-1");
        persisted.setUsername("alice");
        EmployeeAccount binding = binding("employee-1", "user-1");
        binding.setId("binding-1");

        doReturn(null).when(service).accountOfEmployee("employee-1");
        when(employeeService.requireEnabled(eq("employee-1"), any())).thenReturn(employee);
        when(userAccountService.insert(any(UserAccount.class))).thenReturn("user-1");
        when(userAccountService.select("user-1")).thenReturn(persisted);
        doReturn("binding-1").when(service).bindAccount(eq("employee-1"), any(EmployeeAccount.class));
        doReturn(binding).when(service).select("binding-1");

        EmployeeAccountService.AccountProvisionResult result = service.provisionAccount("employee-1", account);

        assertThat(result.user()).isEqualTo(persisted);
        assertThat(result.binding()).isEqualTo(binding);
        assertThat(account.getTitle()).isNull();
        assertThat(account.getEnabled()).isTrue();
        verify(service).bindAccount(eq("employee-1"), any(EmployeeAccount.class));
    }

    private EmployeeAccountService service(EmployeeAccountDao dao) {
        return service(dao, activeTenantVerifier());
    }

    private EmployeeAccountService service(EmployeeAccountDao dao, ActiveTenantVerifier tenantVerifier) {
        EmployeeService employeeService = mock(EmployeeService.class);
        UserAccountService userAccountService = mock(UserAccountService.class);
        when(employeeService.requireEnabled(eq("employee-1"), any())).thenReturn(employee("employee-1"));
        when(userAccountService.requireEnabled(eq("user-1"), any())).thenReturn(user("user-1"));
        when(userAccountService.requireEnabled(eq("user-2"), any())).thenReturn(user("user-2"));
        return new EmployeeAccountService(dao, tenantVerifier, employeeService, userAccountService);
    }

    private EmployeeAccount binding(String employeeId, String userId) {
        EmployeeAccount binding = new EmployeeAccount();
        binding.setEmployeeId(employeeId);
        binding.setUserId(userId);
        return binding;
    }

    private Employee employee(String id) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setEnabled(Boolean.TRUE);
        return employee;
    }

    private UserAccount user(String id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setEnabled(Boolean.TRUE);
        return user;
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }
}
