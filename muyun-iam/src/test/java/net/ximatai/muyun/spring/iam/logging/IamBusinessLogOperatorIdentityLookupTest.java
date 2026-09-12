package net.ximatai.muyun.spring.iam.logging;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityKey;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentDao;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccountDao;
import net.ximatai.muyun.spring.iam.employee.EmployeeDao;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationDao;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.UserAccountDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IamBusinessLogOperatorIdentityLookupTest {
    @Test
    void shouldBatchResolveOperatorIdentityAndKeepEventOrganizationSnapshot() {
        UserAccountDao userDao = mock(UserAccountDao.class);
        EmployeeAccountDao employeeAccountDao = mock(EmployeeAccountDao.class);
        EmployeeDao employeeDao = mock(EmployeeDao.class);
        OrganizationDao organizationDao = mock(OrganizationDao.class);
        DepartmentDao departmentDao = mock(DepartmentDao.class);
        IamBusinessLogOperatorIdentityLookup lookup = new IamBusinessLogOperatorIdentityLookup(userDao,
                employeeAccountDao, employeeDao, organizationDao, departmentDao);

        when(userDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                user("user-1", "tenant-1", "alice", "Alice Account"),
                user("user-2", "tenant-1", "bob", "Bob Account")));
        when(employeeAccountDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                binding("binding-1", "tenant-1", "user-1", "employee-1"),
                binding("binding-2", "tenant-1", "user-2", "employee-2")));
        when(employeeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                employee("employee-1", "tenant-1", "E001", "Alice Employee", "org-current", "department-1"),
                employee("employee-2", "tenant-1", "E002", "Bob Employee", "org-current", "department-2")));
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                organization("organization-history", "tenant-1", "历史机构"),
                organization("organization-2", "tenant-1", "机构二")));
        when(departmentDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                department("department-1", "tenant-1", "当前部门一"),
                department("department-2", "tenant-1", "当前部门二")));

        var identities = lookup.resolve(List.of(
                new BusinessLogOperatorIdentityKey("tenant-1", "user-1", "organization-history"),
                new BusinessLogOperatorIdentityKey("tenant-1", "user-2", "organization-2")));

        assertThat(identities).hasSize(2);
        assertThat(identities.get(new BusinessLogOperatorIdentityKey("tenant-1", "user-1", "organization-history")))
                .satisfies(identity -> {
                    assertThat(identity.employeeName()).isEqualTo("Alice Employee");
                    assertThat(identity.username()).isEqualTo("alice");
                    assertThat(identity.organizationId()).isEqualTo("organization-history");
                    assertThat(identity.organizationName()).isEqualTo("历史机构");
                    assertThat(identity.departmentId()).isEqualTo("department-1");
                    assertThat(identity.departmentName()).isEqualTo("当前部门一");
                });
        verify(userDao, times(1)).query(any(Criteria.class), any(PageRequest.class));
        verify(employeeAccountDao, times(1)).query(any(Criteria.class), any(PageRequest.class));
        verify(employeeDao, times(1)).query(any(Criteria.class), any(PageRequest.class));
        verify(organizationDao, times(1)).query(any(Criteria.class), any(PageRequest.class));
        verify(departmentDao, times(1)).query(any(Criteria.class), any(PageRequest.class));
    }

    @Test
    void shouldProjectEmployeeNameAndUsernameSeparately() {
        UserAccountDao userDao = mock(UserAccountDao.class);
        EmployeeAccountDao employeeAccountDao = mock(EmployeeAccountDao.class);
        EmployeeDao employeeDao = mock(EmployeeDao.class);
        OrganizationDao organizationDao = mock(OrganizationDao.class);
        DepartmentDao departmentDao = mock(DepartmentDao.class);
        IamBusinessLogOperatorIdentityLookup lookup = new IamBusinessLogOperatorIdentityLookup(userDao,
                employeeAccountDao, employeeDao, organizationDao, departmentDao);
        when(userDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                user("user-1", "tenant-1", "alice", "Alice Account"),
                user("user-2", "tenant-1", "bob", null)));
        when(employeeAccountDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                binding("binding-1", "tenant-1", "user-1", "employee-1")));
        when(employeeDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                employee("employee-1", "tenant-1", "E001", null, "org-1", "department-1")));
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(departmentDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());

        var identities = lookup.resolve(List.of(
                new BusinessLogOperatorIdentityKey("tenant-1", "user-1", "org-1"),
                new BusinessLogOperatorIdentityKey("tenant-1", "user-2", "org-1")));

        assertThat(identities.get(new BusinessLogOperatorIdentityKey("tenant-1", "user-1", "org-1"))
                .employeeName()).isNull();
        assertThat(identities.get(new BusinessLogOperatorIdentityKey("tenant-1", "user-1", "org-1"))
                .username()).isEqualTo("alice");
        assertThat(identities.get(new BusinessLogOperatorIdentityKey("tenant-1", "user-2", "org-1"))
                .employeeName()).isNull();
        assertThat(identities.get(new BusinessLogOperatorIdentityKey("tenant-1", "user-2", "org-1"))
                .username()).isEqualTo("bob");
    }

    @Test
    void shouldRetainSameOperatorIdAcrossTenantsWithoutTruncatingTheBatch() {
        UserAccountDao userDao = mock(UserAccountDao.class);
        EmployeeAccountDao employeeAccountDao = mock(EmployeeAccountDao.class);
        EmployeeDao employeeDao = mock(EmployeeDao.class);
        OrganizationDao organizationDao = mock(OrganizationDao.class);
        DepartmentDao departmentDao = mock(DepartmentDao.class);
        IamBusinessLogOperatorIdentityLookup lookup = new IamBusinessLogOperatorIdentityLookup(userDao,
                employeeAccountDao, employeeDao, organizationDao, departmentDao);
        when(userDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                user("shared-user", "tenant-1", "alice", "Alice Tenant One"),
                user("shared-user", "tenant-2", "alice", "Alice Tenant Two")));
        when(employeeAccountDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        when(organizationDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                organization("shared-organization", "tenant-1", "机构一"),
                organization("shared-organization", "tenant-2", "机构二")));

        var identities = lookup.resolve(List.of(
                new BusinessLogOperatorIdentityKey("tenant-1", "shared-user", "shared-organization"),
                new BusinessLogOperatorIdentityKey("tenant-2", "shared-user", "shared-organization")));

        assertThat(identities).hasSize(2);
        assertThat(identities.get(new BusinessLogOperatorIdentityKey("tenant-1", "shared-user", "shared-organization"))
                .organizationName()).isEqualTo("机构一");
        assertThat(identities.get(new BusinessLogOperatorIdentityKey("tenant-2", "shared-user", "shared-organization"))
                .organizationName()).isEqualTo("机构二");
        ArgumentCaptor<PageRequest> pageRequest = ArgumentCaptor.forClass(PageRequest.class);
        verify(userDao).query(any(Criteria.class), pageRequest.capture());
        assertThat(pageRequest.getValue().getLimit()).isEqualTo(2);
    }

    @Test
    void shouldNotReadOtherDirectoriesWhenNoEventOperatorResolvesToAUser() {
        UserAccountDao userDao = mock(UserAccountDao.class);
        EmployeeAccountDao employeeAccountDao = mock(EmployeeAccountDao.class);
        EmployeeDao employeeDao = mock(EmployeeDao.class);
        OrganizationDao organizationDao = mock(OrganizationDao.class);
        DepartmentDao departmentDao = mock(DepartmentDao.class);
        IamBusinessLogOperatorIdentityLookup lookup = new IamBusinessLogOperatorIdentityLookup(userDao,
                employeeAccountDao, employeeDao, organizationDao, departmentDao);
        when(userDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());

        assertThat(lookup.resolve(List.of(new BusinessLogOperatorIdentityKey("tenant-1", "unknown", "org-1"))))
                .isEmpty();

        verify(userDao).query(any(Criteria.class), any(PageRequest.class));
        verifyNoInteractions(employeeAccountDao, employeeDao, organizationDao, departmentDao);
    }

    @Test
    void shouldResolvePlatformUserWithoutTenant() {
        UserAccountDao userDao = mock(UserAccountDao.class);
        EmployeeAccountDao employeeAccountDao = mock(EmployeeAccountDao.class);
        EmployeeDao employeeDao = mock(EmployeeDao.class);
        OrganizationDao organizationDao = mock(OrganizationDao.class);
        DepartmentDao departmentDao = mock(DepartmentDao.class);
        IamBusinessLogOperatorIdentityLookup lookup = new IamBusinessLogOperatorIdentityLookup(userDao,
                employeeAccountDao, employeeDao, organizationDao, departmentDao);
        when(userDao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(
                user("platform.user.super_admin", null, "admin", null)));

        BusinessLogOperatorIdentityKey key = new BusinessLogOperatorIdentityKey(null,
                "platform.user.super_admin", null);

        var identities = lookup.resolve(List.of(key));

        assertThat(identities.get(key).employeeName()).isNull();
        assertThat(identities.get(key).username()).isEqualTo("admin");
        verify(userDao).query(any(Criteria.class), any(PageRequest.class));
        verifyNoInteractions(employeeAccountDao, employeeDao, organizationDao, departmentDao);
    }

    private static UserAccount user(String id, String tenantId, String username, String title) {
        UserAccount user = new UserAccount();
        user.setId(id); user.setTenantId(tenantId); user.setUsername(username); user.setTitle(title);
        return user;
    }

    private static EmployeeAccount binding(String id, String tenantId, String userId, String employeeId) {
        EmployeeAccount binding = new EmployeeAccount();
        binding.setId(id); binding.setTenantId(tenantId); binding.setUserId(userId); binding.setEmployeeId(employeeId);
        return binding;
    }

    private static Employee employee(String id, String tenantId, String employeeNo, String title,
                                     String organizationId, String departmentId) {
        Employee employee = new Employee();
        employee.setId(id); employee.setTenantId(tenantId); employee.setEmployeeNo(employeeNo);
        employee.setTitle(title); employee.setOrganizationId(organizationId); employee.setDepartmentId(departmentId);
        return employee;
    }

    private static Organization organization(String id, String tenantId, String title) {
        Organization organization = new Organization();
        organization.setId(id); organization.setTenantId(tenantId); organization.setTitle(title);
        return organization;
    }

    private static Department department(String id, String tenantId, String title) {
        Department department = new Department();
        department.setId(id); department.setTenantId(tenantId); department.setTitle(title);
        return department;
    }
}
