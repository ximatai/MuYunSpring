package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadFacade;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountService;
import net.ximatai.muyun.spring.iam.employee.EmployeePositionService;
import net.ximatai.muyun.spring.iam.employee.EmployeeService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CurrentUserProfileServiceTest {
    @Test
    void shouldPersistOwnContactAndAvatarThroughTheSelfManagedProfileFacade() {
        EmployeeAccountService accountService = mock(EmployeeAccountService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        EmployeePositionService positionService = mock(EmployeePositionService.class);
        Employee employee = employee();
        when(accountService.employeeIdOfUser("user-1")).thenReturn("employee-1");
        when(employeeService.select("employee-1")).thenReturn(employee);
        when(positionService.list(any(Criteria.class), any(PageRequest.class))).thenReturn(java.util.List.of());
        CurrentUserProfileService service = new CurrentUserProfileService(accountService, employeeService, positionService,
                new ReferenceReadFacade(ReferenceLoadResolver.NONE));

        CurrentUserProfile profile = service.updateCurrentProfile(
                CurrentUser.tenantUser("user-1", "demo.user", "tenant-a"),
                new UpdateCurrentUserProfileRequest("13800000001", "demo@example.test", "asset-1"));

        assertThat(profile.employee()).isNotNull();
        verify(employeeService).updateSelfManagedProfile("employee-1", "13800000001", "demo@example.test", "asset-1");
        verify(employeeService, never()).update(any());
    }

    private Employee employee() {
        Employee employee = new Employee();
        employee.setId("employee-1");
        employee.setEnabled(Boolean.TRUE);
        employee.setEmployeeNo("DEMO-001");
        employee.setTitle("演示职员");
        return employee;
    }
}
