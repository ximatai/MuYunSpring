package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.common.identity.CurrentUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmployeeCurrentUserOrganizationResolverTest {
    @Test
    void shouldResolveTenantPrincipalOrganizationFromBoundEmployee() {
        EmployeeAccountService accountService = mock(EmployeeAccountService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        Employee employee = new Employee();
        employee.setOrganizationId("org-1");
        when(accountService.employeeIdOfUser("user-1")).thenReturn("employee-1");
        when(employeeService.select("employee-1")).thenReturn(employee);

        assertThat(new EmployeeCurrentUserOrganizationResolver(accountService, employeeService)
                .resolveOrganizationId(CurrentUser.tenantUser("user-1", "alice", "tenant-1")))
                .contains("org-1");
    }

    @Test
    void shouldNotResolveOrganizationForSystemPrincipal() {
        EmployeeAccountService accountService = mock(EmployeeAccountService.class);
        EmployeeService employeeService = mock(EmployeeService.class);

        assertThat(new EmployeeCurrentUserOrganizationResolver(accountService, employeeService)
                .resolveOrganizationId(CurrentUser.systemUser("user-1", "admin")))
                .isEmpty();
    }
}
