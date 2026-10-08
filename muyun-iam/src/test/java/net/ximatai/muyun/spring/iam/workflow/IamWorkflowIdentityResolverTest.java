package net.ximatai.muyun.spring.iam.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.department.*;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.organization.*;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.iam.user.*;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IamWorkflowIdentityResolverTest {
    private final UserAccountService users = mock(UserAccountService.class);
    private final EmployeeService employees = mock(EmployeeService.class);
    private final EmployeeAccountService accounts = mock(EmployeeAccountService.class);
    private final EmployeePositionService positions = mock(EmployeePositionService.class);
    private final DepartmentService departments = mock(DepartmentService.class);
    private final OrganizationService organizations = mock(OrganizationService.class);
    private final RoleService roles = mock(RoleService.class);
    private final AccountRoleGrantDao grants = mock(AccountRoleGrantDao.class);
    private final EmploymentRoleGrantDao employmentGrants = mock(EmploymentRoleGrantDao.class);
    private final IamWorkflowIdentityResolver resolver = new IamWorkflowIdentityResolver(users, employees, accounts,
            positions, departments, organizations, roles, grants, employmentGrants);
    private final Map<String, UserAccount> userRows = new LinkedHashMap<>();
    private final Map<String, Employee> employeeRows = new LinkedHashMap<>();
    private final List<EmployeeAccount> bindings = new ArrayList<>();
    private final Map<String, Department> departmentRows = new LinkedHashMap<>();
    private final Map<String, Organization> organizationRows = new LinkedHashMap<>();
    private final Map<String, Role> roleRows = new LinkedHashMap<>();
    private final List<AccountRoleGrant> grantRows = new ArrayList<>();
    private final Map<String, EmployeePosition> positionRows = new LinkedHashMap<>();
    private final List<EmploymentRoleGrant> employmentGrantRows = new ArrayList<>();

    @BeforeEach void configureDirectory() {
        TenantContext.setTenantId("tenant-a");
        when(users.select(any())).thenAnswer(call -> {
            assertThat(TenantContext.tenantFilterBypassed()).isTrue(); return userRows.get(call.getArgument(0));
        });
        when(employees.select(any())).thenAnswer(call -> employeeRows.get(call.getArgument(0)));
        when(departments.select(any())).thenAnswer(call -> departmentRows.get(call.getArgument(0)));
        when(organizations.select(any())).thenAnswer(call -> organizationRows.get(call.getArgument(0)));
        when(roles.select(any())).thenAnswer(call -> roleRows.get(call.getArgument(0)));
        when(positions.select(any())).thenAnswer(call -> positionRows.get(call.getArgument(0)));
        when(accounts.list(any(Criteria.class), any(PageRequest.class))).thenAnswer(call -> {
            Criteria criteria = call.getArgument(0); PageRequest page = call.getArgument(1);
            return bindings.stream().filter(row -> tenantMatches(criteria, row))
                    .filter(row -> matches(criteria, "userId", row.getUserId()) && matches(criteria, "employeeId", row.getEmployeeId()))
                    .skip(page.getOffset()).limit(page.getLimit()).toList();
        });
        when(employees.list(any(Criteria.class), any(PageRequest.class))).thenAnswer(call -> employeeRows.values().stream()
                .filter(row -> tenantMatches(call.getArgument(0), row)).filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                .filter(row -> matches(call.getArgument(0), "departmentId", row.getDepartmentId())
                        && matches(call.getArgument(0), "organizationId", row.getOrganizationId())).toList());
        when(grants.query(any(Criteria.class), any(PageRequest.class))).thenAnswer(call -> grantRows.stream()
                .filter(row -> tenantMatches(call.getArgument(0), row) && matches(call.getArgument(0), "roleId", row.getRoleId()))
                .filter(row -> Boolean.TRUE.equals(row.getEnabled())).toList());
        when(employmentGrants.query(any(Criteria.class), any(PageRequest.class))).thenAnswer(call -> employmentGrantRows.stream()
                .filter(row -> tenantMatches(call.getArgument(0), row) && matches(call.getArgument(0), "roleId", row.getRoleId()))
                .filter(row -> Boolean.TRUE.equals(row.getEnabled())).toList());
        when(organizations.selfAndDescendantIds(any(Criteria.class), any(String.class))).thenAnswer(call -> {
            Criteria criteria = call.getArgument(0); String scopeId = call.getArgument(1);
            var scope = organizationRows.get(scopeId);
            if (scope == null || !tenantMatches(criteria, scope) || !Boolean.TRUE.equals(scope.getEnabled())) return List.of();
            return organizationRows.values().stream().filter(row -> tenantMatches(criteria, row))
                    .filter(row -> Boolean.TRUE.equals(row.getEnabled()))
                    .filter(row -> scopeId.equals(row.getId()) || scopeId.equals(row.getParentId()))
                    .map(Organization::getId).toList();
        });
        var origin = employee("origin-employee", "tenant-a", "origin"); origin.setSupervisorEmployeeId("first-employee");
        var first = employee("first-employee", "tenant-a", "first"); first.setSupervisorEmployeeId("second-employee");
        employee("second-employee", "tenant-a", "second");
        var department = new Department(); department.setId("department"); department.setTenantId("tenant-a");
        department.setEnabled(true); department.setManagerEmployeeId("first-employee"); departmentRows.put(department.getId(), department);
        var organization = new Organization(); organization.setId("org"); organization.setTenantId("tenant-a");
        organization.setEnabled(true); organization.setManagerEmployeeId("second-employee"); organizationRows.put(organization.getId(), organization);
    }
    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void enabledCurrentTenantAndGlobalAccountsAreEligibleButOtherTenantAndDisabledAccountsAreNot() {
        user("global", null, true); user("foreign", "tenant-b", true); user("disabled", "tenant-a", false);
        assertThat(resolver.isEnabledUser("origin")).isTrue(); assertThat(resolver.isEnabledUser("global")).isTrue();
        assertThat(resolver.isEnabledUser("foreign")).isFalse(); assertThat(resolver.isEnabledUser("disabled")).isFalse();
        assertThat(resolver.isEnabledUser("missing")).isFalse(); assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        assertThat(TenantContext.currentTenantId()).contains("tenant-a");
        TenantContext.clear(); assertThat(resolver.isEnabledUser("origin")).isFalse(); assertThat(resolver.isEnabledUser("global")).isTrue();
    }

    @ParameterizedTest @CsvSource({"0,first", "1,first", "2,second"})
    void supervisorDepthOneIsImmediateAndZeroRemainsCompatible(int depth, String expected) {
        assertThat(resolver.resolve(relative("SUPERVISOR", depth), "origin", null)).containsExactly(expected);
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    void supervisorCycleIsRejectedEvenWhenItClosesOnTheLastRequestedHop(int depth) {
        if (depth == 1) employeeRows.get("origin-employee").setSupervisorEmployeeId("origin-employee");
        else employeeRows.get("first-employee").setSupervisorEmployeeId("origin-employee");
        assertThatThrownBy(() -> resolver.resolve(relative("SUPERVISOR", depth), "origin", null)).hasMessageContaining("循环");
    }
    @ParameterizedTest @ValueSource(ints = {-1, 33})
    void invalidSupervisorDepthIsRejected(int depth) {
        assertThatThrownBy(() -> resolver.resolve(relative("SUPERVISOR", depth), "origin", null)).hasMessageContaining("层级");
    }
    @Test void missingDisabledOrForeignSupervisorDoesNotProduceAUser() {
        employeeRows.get("first-employee").setEnabled(false);
        assertThat(resolver.resolve(relative("SUPERVISOR", 1), "origin", null)).isEmpty();
        employeeRows.get("first-employee").setEnabled(true); employeeRows.get("first-employee").setTenantId("tenant-b");
        assertThat(resolver.resolve(relative("SUPERVISOR", 1), "origin", null)).isEmpty();
        assertThat(resolver.resolve(relative("SUPERVISOR", 1), "missing", null)).isEmpty();
        assertThat(resolver.resolve(relative("SUPERVISOR", 1), null, null)).isEmpty();
    }

    @ParameterizedTest @CsvSource({"DEPT,department,first", "ORG,org,second"})
    void headOnlySelectsConfiguredManagerInsteadOfAllScopeMembers(String type, String id, String expected) {
        employee("ordinary", "tenant-a", "ordinary-user");
        var rule = new WorkflowParticipantRule(type, List.of(id), null, null, 0, true);
        assertThat(participants(rule)).containsExactly(expected);
        var all = new WorkflowParticipantRule(type, List.of(id), null, null, 0, false);
        assertThat(participants(all)).contains("ordinary-user", expected);
    }
    @ParameterizedTest @ValueSource(strings = {"DEPT", "ORG"})
    void disabledScopeOrForeignManagerCannotProduceParticipants(String type) {
        String id = type.equals("DEPT") ? "department" : "org";
        var rule = new WorkflowParticipantRule(type, List.of(id), null, null, 0, true);
        if (type.equals("DEPT")) departmentRows.get(id).setEnabled(false); else organizationRows.get(id).setEnabled(false);
        assertThat(resolver.resolve(rule, "origin", null)).isEmpty();
        if (type.equals("DEPT")) { departmentRows.get(id).setEnabled(true); employeeRows.get("first-employee").setTenantId("tenant-b"); }
        else { organizationRows.get(id).setEnabled(true); employeeRows.get("second-employee").setTenantId("tenant-b"); }
        assertThat(resolver.resolve(rule, "origin", null)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"DEPT", "ORG"})
    void managersRequireAnEnabledEmployeeAndAnEnabledCurrentOrGlobalBoundAccount(String type) {
        String id = type.equals("DEPT") ? "department" : "org";
        String employeeId = type.equals("DEPT") ? "first-employee" : "second-employee";
        String userId = type.equals("DEPT") ? "first" : "second";
        var rule = new WorkflowParticipantRule(type, List.of(id), null, null, 0, true);
        userRows.get(userId).setTenantId(null);
        assertThat(participants(rule)).containsExactly(userId);
        employeeRows.get(employeeId).setEnabled(false);
        assertThat(resolver.resolve(rule, "origin", null)).isEmpty();
        employeeRows.get(employeeId).setEnabled(true); userRows.get(userId).setTenantId("tenant-b");
        assertThatThrownBy(() -> participants(rule)).hasMessageContaining("有效参与人");
    }
    @ParameterizedTest @ValueSource(strings = {"DEPARTMENT_MANAGER", "ORGANIZATION_MANAGER"})
    void managerRelationsDetectScopeCycleAtTheLastHop(String relation) {
        if (relation.startsWith("DEPARTMENT")) departmentRows.get("department").setParentId("department");
        else organizationRows.get("org").setParentId("org");
        assertThatThrownBy(() -> resolver.resolve(relative(relation, 1), "origin", null)).hasMessageContaining("循环");
    }
    @Test void managerRelationsSupportAncestorSelectionAndRejectDisabledManagerAccount() {
        var parent = new Department(); parent.setId("parent"); parent.setTenantId("tenant-a"); parent.setEnabled(true);
        parent.setManagerEmployeeId("second-employee"); departmentRows.put("parent", parent); departmentRows.get("department").setParentId("parent");
        assertThat(participants(relative("DEPARTMENT_MANAGER", 1))).containsExactly("second");
        assertThat(participants(relative("ORGANIZATION_MANAGER", 0))).containsExactly("second");
        userRows.get("second").setEnabled(false);
        assertThatThrownBy(() -> participants(relative("ORGANIZATION_MANAGER", 0))).hasMessageContaining("有效参与人");
    }
    @Test void systemScopeAndExplicitBypassDoNotExpandTenantDirectoryMembership() {
        var rule = new WorkflowParticipantRule("DEPT", List.of("department"), null, null, 0, true);
        try (var system = TenantContext.system("global directory contract")) { assertThat(resolver.resolve(rule, "origin", null)).isEmpty(); }
        departmentRows.get("department").setTenantId("tenant-b");
        try (var bypass = TenantContext.bypassTenantFilter("administrative caller still binds workflow tenant")) {
            assertThat(resolver.resolve(rule, "origin", null)).isEmpty();
        }
    }
    @Test void roleGrantsUseExactTenantPartitionAndFilterDisabledOrForeignAccountsAtParticipantBoundary() {
        var role = new Role(); role.setId("role"); role.setTenantId("tenant-a"); role.setEnabled(true); roleRows.put("role", role);
        user("global", null, true); user("foreign", "tenant-b", true); user("disabled", "tenant-a", false);
        grant("tenant-a", "origin"); grant("tenant-a", "global"); grant("tenant-a", "foreign"); grant("tenant-a", "disabled"); grant("tenant-b", "second");
        var rule = new WorkflowParticipantRule("ROLE", List.of("role"), null, null, 0, false);
        assertThat(participants(rule)).containsExactly("origin", "global");
        role.setTenantId(null); grant(null, "global");
        try (var system = TenantContext.system("global role grant contract")) { assertThat(participants(rule)).containsExactly("global"); }
        var criteria = org.mockito.ArgumentCaptor.forClass(Criteria.class); verify(grants, times(2)).query(criteria.capture(), any(PageRequest.class));
        assertThat(criteria.getAllValues().getLast().getClauses()).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo("tenantId"); assertThat(clause.getOperator().name()).isEqualTo("IS_NULL");
        });
    }
    @Test void accountRoleManagementScopeUsesItsTypeAndRequiresAnExplicitMatchingOrganization() {
        var role = new Role(); role.setId("role"); role.setTenantId("tenant-a"); role.setEnabled(true); roleRows.put("role", role);
        var parent = new Organization(); parent.setId("parent-org"); parent.setTenantId("tenant-a"); parent.setEnabled(true);
        organizationRows.put(parent.getId(), parent); organizationRows.get("org").setParentId(parent.getId());
        grant("tenant-a", "origin");
        var managerGrant = grant("tenant-a", "first"); managerGrant.setManagementScopeType(ManagementScopeType.ORGANIZATION);
        managerGrant.setManagementScopeId("parent-org");
        var platformGrant = grant("tenant-a", "second"); platformGrant.setManagementScopeType(ManagementScopeType.PLATFORM);
        platformGrant.setManagementScopeId(null);
        var rule = new WorkflowParticipantRule("ROLE", List.of("role"), null, null, 0, false);
        assertThat(resolver.resolve(rule, "origin", "org")).containsExactly("origin", "first", "second");
        assertThat(resolver.resolve(rule, "origin", null)).containsExactly("origin", "second");
        organizationRows.get("org").setTenantId("tenant-b");
        assertThat(resolver.resolve(rule, "origin", "org")).containsExactly("origin", "second");
        grantRows.getFirst().setManagementScopeId("tenant-b");
        assertThat(resolver.resolve(rule, "origin", null)).containsExactly("second");
    }
    @Test void employmentRoleGrantsRejectDisabledOrForeignPositionsAndEmployees() {
        var role = new Role(); role.setId("role"); role.setTenantId("tenant-a"); role.setEnabled(true); roleRows.put("role", role);
        var position = new EmployeePosition(); position.setId("position"); position.setEmployeeId("first-employee");
        position.setTenantId("tenant-a"); position.setOrganizationId("org"); position.setEnabled(true); positionRows.put(position.getId(), position);
        var grant = new EmploymentRoleGrant(); grant.setTenantId("tenant-a"); grant.setRoleId("role");
        grant.setEmployeePositionId("position"); grant.setEnabled(true); employmentGrantRows.add(grant);
        var rule = new WorkflowParticipantRule("ROLE", List.of("role"), null, null, 0, false);
        assertThat(resolver.resolve(rule, "origin", "org")).containsExactly("first");
        assertThat(resolver.resolve(rule, "origin", "other-org")).isEmpty();
        position.setEnabled(false); assertThat(resolver.resolve(rule, "origin", "org")).isEmpty();
        position.setEnabled(true); position.setTenantId("tenant-b"); assertThat(resolver.resolve(rule, "origin", "org")).isEmpty();
        position.setTenantId("tenant-a"); employeeRows.get("first-employee").setTenantId("tenant-b");
        assertThat(resolver.resolve(rule, "origin", "org")).isEmpty();
    }

    private List<String> participants(WorkflowParticipantRule rule) {
        var instance = new WorkflowInstance(); instance.setStartedBy("origin"); instance.setModuleAlias("sales.contract"); instance.setRecordId("record");
        var node = new WorkflowNodeInstance(); node.setNodeKey("approve"); node.setNodeTitle("Approval");
        try { node.setParticipantPolicyText(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("rules", List.of(rule)))); }
        catch (Exception failure) { throw new AssertionError(failure); }
        return new WorkflowParticipantService(List.of(resolver), (module, id) -> Map.of()).resolve(instance, node);
    }
    private WorkflowParticipantRule relative(String relation, int depth) { return new WorkflowParticipantRule("RELATIVE", List.of(), null, relation, depth, false); }
    private Employee employee(String id, String tenant, String userId) {
        var employee = new Employee(); employee.setId(id); employee.setTenantId(tenant); employee.setEnabled(true);
        employee.setDepartmentId("department"); employee.setOrganizationId("org"); employeeRows.put(id, employee);
        var binding = new EmployeeAccount(); binding.setEmployeeId(id); binding.setUserId(userId); binding.setTenantId(tenant); bindings.add(binding);
        user(userId, tenant, true); return employee;
    }
    private void user(String id, String tenant, boolean enabled) { var user = new UserAccount(); user.setId(id); user.setTenantId(tenant); user.setEnabled(enabled); userRows.put(id, user); }
    private AccountRoleGrant grant(String tenant, String userId) {
        var grant = new AccountRoleGrant(); grant.setRoleId("role"); grant.setUserId(userId); grant.setTenantId(tenant);
        grant.setManagementScopeType(tenant == null ? ManagementScopeType.PLATFORM : ManagementScopeType.TENANT);
        grant.setManagementScopeId(tenant); grant.setEnabled(true); grantRows.add(grant); return grant;
    }
    private boolean tenantMatches(Criteria criteria, EntityContract row) {
        assertThat(criteria.getClauses()).anyMatch(clause -> "tenantId".equals(clause.getField()));
        return matches(criteria, "tenantId", row.getTenantId());
    }
    private boolean matches(Criteria criteria, String field, Object value) {
        var clause = criteria.getClauses().stream().filter(item -> field.equals(item.getField())).findFirst();
        return clause.isEmpty() || Objects.equals(clause.get().getValues().isEmpty() ? null : clause.get().getValues().getFirst(), value);
    }
}
