package net.ximatai.muyun.spring.demo.school.test;

import net.ximatai.muyun.spring.boot.MuYunSpringApplication;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.tenant.*;
import net.ximatai.muyun.spring.iam.user.*;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.organization.*;
import net.ximatai.muyun.spring.iam.department.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@Testcontainers
@SpringBootTest(classes = MuYunSpringApplication.class, properties = "muyun.runtime.mode=development")
class IdentityDeliveryIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired WebApplicationContext context;
    @Autowired TenantService tenants;
    @Autowired UserAccountService users;
    @Autowired TenantApplicationService applications;
    @Autowired net.ximatai.muyun.spring.platform.application.ApplicationService applicationDefinitions;
    @Autowired RoleService roles;
    @Autowired OrganizationService organizations;
    @Autowired DepartmentService departments;
    @Autowired EmployeeService employees;
    @Autowired EmployeeAccountService accounts;

    @Test void applicationReopeningRestoresRetainedIdentityAndProvisioningPreservesOtherEntitlements() {
        String tenant = "entitle_" + UUID.randomUUID().toString().substring(0, 8);
        String alias = "sales_" + UUID.randomUUID().toString().substring(0, 8);
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("admin", "管理员"));
             var system = TenantContext.system("tenant entitlement lifecycle")) {
            tenant(tenant);
            var application = new net.ximatai.muyun.spring.platform.application.Application();
            application.setAlias(alias); application.setTitle("业务应用"); application.setEnabled(true);
            applicationDefinitions.insert(application);
            applications.configureApplications(tenant, java.util.List.of("iam", alias));
            var original = applications.list(net.ximatai.muyun.database.core.orm.Criteria.of()
                    .eq("tenantId", tenant).eq("applicationAlias", alias),
                    net.ximatai.muyun.database.core.orm.PageRequest.of(1, 1)).getFirst();
            applications.configureApplications(tenant, java.util.List.of("iam"));
            org.assertj.core.api.Assertions.assertThat(applications.isApplicationOpened(tenant, alias)).isFalse();
            applications.configureApplications(tenant, java.util.List.of("iam", alias));
            org.assertj.core.api.Assertions.assertThat(applications.select(original.getId())).isNotNull();
            applications.ensureApplicationsOpened(tenant, java.util.List.of("iam"));
            org.assertj.core.api.Assertions.assertThat(applications.openedApplicationAliases(tenant))
                    .containsExactlyInAnyOrder("iam", alias);
        }
    }

    @Test void recordOwnedCandidatesNeedOnlySourcePermissionAndKeepTenantAndSummaryFacts() throws Exception {
        String tenant = "candidate_" + UUID.randomUUID().toString().substring(0, 8);
        UserAccount operator;
        UserAccount outsider;
        Role target;
        Employee employee;
        try (var identity = CurrentUserContext.use(CurrentUser.systemUser("admin", "管理员"));
             var system = TenantContext.system("candidate fixture")) {
            tenant(tenant);
            tenant(tenant + "_other");
            try (var scope = TenantContext.use(tenant)) {
                operator = user("operator", true);
                outsider = user("no_grants", true);
                UserAccount chosen = user("pick_50%_exact", true);
                user("pick_50AAexact", true);
                user("pick_disabled", false);
                UserAccount deleted = user("pick_deleted", true);
                users.delete(deleted.getId());
                Organization organization = new Organization();
                organization.setCode("sales"); organization.setTitle("销售机构"); organization.setEnabled(true);
                organizations.insert(organization);
                Department department = new Department();
                department.setOrganizationId(organization.getId()); department.setCode("sales");
                department.setTitle("销售部门"); department.setEnabled(true); departments.insert(department);
                employee = new Employee(); employee.setTitle("候选职员"); employee.setEmployeeNo("E001");
                employee.setOrganizationId(organization.getId()); employee.setDepartmentId(department.getId());
                employee.setEnabled(true); employees.insert(employee);
                EmployeeAccount binding = new EmployeeAccount(); binding.setUserId(chosen.getId());
                accounts.bindAccount(employee.getId(), binding);
                target = role(tenant, "绑定目标");
                Role grant = role(tenant, "绑定经办员");
                roles.grantAction(grant.getId(), "iam.role", "accountRoleGrants");
                roles.grantAction(grant.getId(), "iam.employee", "employeeAccounts");
                roles.grantAccountRoleResult(grant.getId(), operator.getId(), tenant);
            }
            try (var scope = TenantContext.use(tenant + "_other")) { user("pick_50%_exact", true); }
        }
        var mvc = webAppContextSetup(context).build();
        String endpoint = "/iam.role/" + target.getId() + "/account-role-candidates/query";
        try (var identity = CurrentUserContext.use(CurrentUser.tenantUser(operator.getId(), "operator", tenant));
             var scope = TenantContext.use(tenant)) {
            mvc.perform(post(endpoint).contentType("application/json").content("{\"keyword\":\"50%_\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].username").value("pick_50%_exact"))
                    .andExpect(jsonPath("$.records[0].employeeTitle").value("候选职员"))
                    .andExpect(jsonPath("$.records[0].organizationTitle").value("销售机构"))
                    .andExpect(jsonPath("$.records[0].departmentTitle").value("销售部门"))
                    .andExpect(jsonPath("$.records[0].passwordHash").doesNotExist());
            mvc.perform(post(endpoint).contentType("application/json").content("{\"keyword\":\"pick_\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
            // A target tenant supplied by the caller must not replace this tenant-owned role's scope.
            mvc.perform(post(endpoint).contentType("application/json")
                            .content("{\"targetTenantId\":\"" + tenant + "_other\",\"keyword\":\"50%_\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].employeeTitle").value("候选职员"));
            mvc.perform(post("/iam.employee/" + employee.getId() + "/account-candidates/query")
                            .contentType("application/json").content("{\"keyword\":\"pick_\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                    .andExpect(jsonPath("$.records[0].username").value("pick_50AAexact"));
            mvc.perform(post("/iam.user/query").contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
        try (var identity = CurrentUserContext.use(CurrentUser.tenantUser(outsider.getId(), "no_grants", tenant));
             var scope = TenantContext.use(tenant)) {
            mvc.perform(post(endpoint).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    private void tenant(String alias) {
        Tenant tenant = new Tenant(); tenant.setAlias(alias); tenant.setTitle(alias); tenant.setEnabled(true);
        tenants.insert(tenant);
    }
    private UserAccount user(String name, boolean enabled) {
        UserAccount user = new UserAccount(); user.setUsername(name); user.setPassword("CandidateTest123!");
        user.setEnabled(enabled); users.insert(user); return user;
    }
    private Role role(String tenant, String title) {
        Role role = new Role(); role.setTitle(title); role.setAssignmentType(RoleAssignmentType.ACCOUNT);
        role.setOwnerScopeType(RoleOwnerScopeType.TENANT); role.setOwnerScopeId(tenant); role.setEnabled(true);
        roles.insert(role); return role;
    }
}
