package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.EntitySaveLifecycleListener;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.ability.form.FormAbility;
import net.ximatai.muyun.spring.ability.option.StaticOptionFieldValueValidator;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeServiceContractTest {
    @AfterEach
    void resetPlatformAbilityRuntime() {
        PlatformAbilityRuntime.resetStaticOptionFieldValueValidator();
        PlatformAbilityRuntime.resetEntitySaveLifecycleListener();
    }

    @Test
    void shouldExposeStableModuleAlias() {
        EmployeeService service = new EmployeeService(mock(EmployeeDao.class), activeTenantVerifier(),
                organizationService(), departmentService());

        assertThat(service.getModuleAlias()).isEqualTo("iam.employee");
    }

    @Test
    void shouldExposeRecoverableEmployeeRecycleBinWithoutIrreversiblePurge() {
        EmployeeService service = new EmployeeService(mock(EmployeeDao.class), activeTenantVerifier(),
                organizationService(), departmentService());

        assertThat(service).isInstanceOf(RecycleBinAbility.class);
        assertThat(service.getDeletionEntityAlias()).isEqualTo("employee");
        assertThat(service.isRecycleBinPurgeEnabled()).isFalse();
    }

    @Test
    void shouldExplainConflictWhenEmployeeNumberIsRetainedBySoftDeletedEmployee() {
        EmployeeDao dao = mock(EmployeeDao.class);
        OrganizationService organizationService = organizationService();
        DepartmentService departmentService = departmentService();
        when(organizationService.requireEnabled(eq("org-1"), any())).thenReturn(organization("org-1"));
        when(departmentService.requireEnabled(eq("dept-1"), any())).thenReturn(department("org-1", "dept-1"));
        Employee retained = employee("org-1", "dept-1", "E001", "Deleted Alice");
        retained.setId("employee-deleted");
        retained.setTenantId("tenant_a");
        retained.setDeleted(Boolean.TRUE);
        retained.setDeletedAt(Instant.parse("2026-07-27T00:00:00Z"));
        when(dao.query(any(), any())).thenReturn(List.of(retained));
        EmployeeService service = new EmployeeService(dao, activeTenantVerifier(), organizationService,
                departmentService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(employee("org-1", "dept-1", "E001", "New Alice")))
                    .isInstanceOf(PlatformException.class)
                    .satisfies(exception -> {
                        PlatformException platformException = (PlatformException) exception;
                        assertThat(platformException.code())
                                .isEqualTo(PlatformErrorCodes.RESOURCE_SOFT_DELETED_CONFLICT);
                        assertThat(platformException.details())
                                .containsEntry("resourceModuleAlias", EmployeeService.MODULE_ALIAS)
                                .containsEntry("resourceRecordId", "employee-deleted")
                                .containsEntry("recoveryAvailable", Boolean.TRUE);
                    });
        }
    }

    @Test
    void shouldFillEmployeeDefaultsThroughCrudAbility() {
        EmployeeDao dao = mock(EmployeeDao.class);
        when(dao.insert(any())).thenReturn("employee-1");
        OrganizationService organizationService = organizationService();
        DepartmentService departmentService = departmentService();
        when(organizationService.requireEnabled(eq("org-1"), any())).thenReturn(organization("org-1"));
        when(departmentService.requireEnabled(eq("dept-1"), any())).thenReturn(department("org-1", "dept-1"));
        EmployeeService service = new EmployeeService(dao, activeTenantVerifier(), organizationService,
                departmentService);
        Employee employee = employee("org-1", "dept-1", "E001", "Alice");
        employee.setGender(" ");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(employee);
        }

        assertThat(employee.getEnabled()).isTrue();
        assertThat(employee.getTenantId()).isEqualTo("tenant_a");
        assertThat(employee.getGender()).isNull();
        assertThat(employee.getMobile()).isNull();
        verify(organizationService).requireEnabled(eq("org-1"), any());
        verify(departmentService).requireEnabled(eq("dept-1"), any());
    }

    @Test
    void shouldRequireTenantContextForEmployeeMutation() {
        EmployeeService service = new EmployeeService(mock(EmployeeDao.class), activeTenantVerifier(),
                organizationService(), departmentService());

        assertThatThrownBy(() -> service.insert(employee("org-1", "dept-1", "E001", "Alice")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("tenant context");
    }

    @Test
    void shouldRequireCoreEmployeeFields() {
        EmployeeService service = new EmployeeService(mock(EmployeeDao.class), activeTenantVerifier(),
                organizationService(), departmentService());

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(employee(" ", "dept-1", "E001", "Alice")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("organizationId");
            assertThatThrownBy(() -> service.insert(employee("org-1", " ", "E001", "Alice")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("departmentId");
            assertThatThrownBy(() -> service.insert(employee("org-1", "dept-1", " ", "Alice")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("employeeNo");
            assertThatThrownBy(() -> service.insert(employee("org-1", "dept-1", "E001", " ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("employeeName");
        }
    }

    @Test
    void shouldRejectDepartmentFromAnotherOrganization() {
        OrganizationService organizationService = organizationService();
        DepartmentService departmentService = departmentService();
        when(organizationService.requireEnabled(eq("org-1"), any())).thenReturn(organization("org-1"));
        when(departmentService.requireEnabled(eq("dept-2"), any())).thenReturn(department("org-2", "dept-2"));
        EmployeeService service = new EmployeeService(mock(EmployeeDao.class), activeTenantVerifier(),
                organizationService, departmentService);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(employee("org-1", "dept-2", "E001", "Alice")))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee.department-organization-mismatch"))
                    .hasMessage("职员所属部门必须隶属于同一机构");
        }
    }

    @Test
    void shouldValidateGenderThroughStaticOptionFieldValidator() {
        EmployeeDao dao = mock(EmployeeDao.class);
        when(dao.insert(any())).thenReturn("employee-1");
        OrganizationService organizationService = organizationService();
        DepartmentService departmentService = departmentService();
        StaticOptionFieldValueValidator validator = mock(StaticOptionFieldValueValidator.class);
        when(organizationService.requireEnabled(eq("org-1"), any())).thenReturn(organization("org-1"));
        when(departmentService.requireEnabled(eq("dept-1"), any())).thenReturn(department("org-1", "dept-1"));
        PlatformAbilityRuntime.configureStaticOptionFieldValueValidator(validator);
        EmployeeService service = new EmployeeService(dao, activeTenantVerifier(), organizationService,
                departmentService);
        Employee employee = employee("org-1", "dept-1", "E001", "Alice");
        employee.setGender("1");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(employee);
        }

        verify(validator).validate(Employee.class, employee);
    }

    @Test
    void shouldExposeGenderOptionBindingInQuerySchema() {
        EmployeeService service = new EmployeeService(mock(EmployeeDao.class), activeTenantVerifier(),
                organizationService(), departmentService());

        assertThat(service.querySchema().fields()).anySatisfy(field -> {
            assertThat(field.name()).isEqualTo("gender");
            assertThat(field.optionBinding()).isEqualTo(OptionBinding.dictionary("iam", "gender"));
            assertThat(field.optionTitleField()).isEqualTo("genderTitle");
        });
    }

    @Test
    void shouldNotExposeFormSchemaFromServiceAbility() {
        assertThat(FormAbility.class.isAssignableFrom(EmployeeService.class)).isFalse();
    }

    @Test
    void shouldPersistSelfManagedProfileThroughTheStandardSaveLifecycle() {
        EmployeeDao dao = mock(EmployeeDao.class);
        Employee employee = employee("org-1", "dept-1", "E001", "Alice");
        employee.setId("employee-1");
        employee.setTenantId("tenant-a");
        employee.setVersion(0);
        employee.setEnabled(Boolean.TRUE);
        when(dao.query(any(), any())).thenReturn(List.of(employee));
        when(dao.updateByIdAndVersion(any(), any())).thenReturn(1);
        OrganizationService organizations = organizationService();
        DepartmentService departments = departmentService();
        when(organizations.requireEnabled(eq("org-1"), any())).thenReturn(organization("org-1"));
        when(departments.requireEnabled(eq("dept-1"), any())).thenReturn(department("org-1", "dept-1"));
        EmployeeService service = new EmployeeService(dao, activeTenantVerifier(), organizations, departments);
        boolean[] lifecycle = {false, false};
        PlatformAbilityRuntime.configureEntitySaveLifecycleListener(new EntitySaveLifecycleListener() {
            @Override
            public <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> void beforeSave(
                    net.ximatai.muyun.spring.ability.CrudAbility<T> ability, T existing, T incoming) {
                lifecycle[0] = true;
            }

            @Override
            public <T extends net.ximatai.muyun.spring.common.model.contract.EntityContract> void persisted(
                    net.ximatai.muyun.spring.ability.CrudAbility<T> ability, T entity) {
                lifecycle[1] = true;
            }
        });

        ActionExecutionPolicy selfProfile = new ActionExecutionPolicy("selfProfile", PlatformActionLevel.RECORD,
                ActionAccessMode.LOGIN_REQUIRED, false, false, ActionDefaultGrantPolicy.NONE, null);
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a");
             ActionExecutionContextHolder.Scope ignoredAction = ActionExecutionContextHolder.use(
                     ActionExecutionContext.ofPolicy(EmployeeService.MODULE_ALIAS, selfProfile,
                             Set.of("employee-1"), Optional.empty()))) {
            assertThat(service.updateSelfManagedProfile("employee-1", " 13800000001 ", "alice@example.test", "asset-1"))
                    .isEqualTo(1);
        }

        verify(dao).updateByIdAndVersion(argThat(updated -> updated == employee
                && "13800000001".equals(updated.getMobile())
                && "alice@example.test".equals(updated.getEmail())
                && "asset-1".equals(updated.getAvatarAssetId())), eq(0));
        assertThat(lifecycle).containsExactly(true, true);
    }

    @Test
    void shouldSortOnlyWithinDepartment() {
        EmployeeService service = new EmployeeService(mock(EmployeeDao.class), activeTenantVerifier(),
                organizationService(), departmentService());

        assertThatThrownBy(() -> service.validateSortScope(
                employee("org-1", "dept-1", "E001", "Alice"),
                employee("org-1", "dept-2", "E002", "Bob")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same department");
    }

    private Employee employee(String organizationId, String departmentId, String employeeNo, String title) {
        Employee employee = new Employee();
        employee.setOrganizationId(organizationId);
        employee.setDepartmentId(departmentId);
        employee.setEmployeeNo(employeeNo);
        employee.setTitle(title);
        employee.setMobile(" ");
        return employee;
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }

    private OrganizationService organizationService() {
        return mock(OrganizationService.class);
    }

    private DepartmentService departmentService() {
        return mock(DepartmentService.class);
    }

    private Organization organization(String id) {
        Organization organization = new Organization();
        organization.setId(id);
        organization.setEnabled(Boolean.TRUE);
        return organization;
    }

    private Department department(String organizationId, String id) {
        Department department = new Department();
        department.setId(id);
        department.setOrganizationId(organizationId);
        department.setEnabled(Boolean.TRUE);
        return department;
    }
}
