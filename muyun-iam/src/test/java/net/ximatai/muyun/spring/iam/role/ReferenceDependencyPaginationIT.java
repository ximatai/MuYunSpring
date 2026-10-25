package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import java.time.Instant;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Executes the dependency subquery through the public record pagination entry point. */
@Testcontainers
@SpringBootTest(classes = ReferenceDependencyPaginationIT.Host.class)
class ReferenceDependencyPaginationIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired IDatabaseOperations<?> db;
    @Autowired RoleDao roleRows;
    @Autowired RoleActionDao actionRows;
    @Autowired AccountRoleGrantDao accountGrants;
    @Autowired EmploymentRoleGrantDao employmentGrants;
    @Autowired RoleDataGrantActionDao dataGrants;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @ParameterizedTest(name = "source cross tenant={0}, target cross tenant={1}")
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void dependencyUnionKeepsExactRecordsAndTotalsAcrossPages(boolean sourceCrossTenant, boolean targetCrossTenant) {
        var target = new EntityDefinition("student", "scope_student", "Student", List.of(FieldDefinition.string("title", "Title")))
                .withCapabilities(EntityCapability.DATA_SCOPE);
        var source = new EntityDefinition("score", "scope_score", "Score", List.of(
                FieldDefinition.string("title", "Title"), new FieldDefinition("studentId", "student_id", FieldType.STRING, "Student")))
                .withCapabilities(EntityCapability.DATA_SCOPE);
        var targetModule = new ModuleDefinition("school.student", "Students", List.of(target));
        var sourceModule = new ModuleDefinition("sales.score", "Scores", List.of(source));
        var schema = new DynamicSchemaService(db);
        schema.ensureModule(targetModule); schema.ensureModule(sourceModule);
        db.execute("delete from scope_score"); db.execute("delete from scope_student");
        try (var runtime = DynamicRecordRuntime.builder(db).build().register(targetModule).register(sourceModule)) {
            for (String tenant : List.of("tenant-a", "tenant-b")) {
                try (var scope = TenantContext.use(tenant)) {
                    // owner and assignee overlap on 'both': OR grants must never duplicate source rows.
                    for (String kind : List.of("owner", "assignee", "both", "denied", "deleted")) {
                        var student = runtime.newRecord("school.student", "student");
                        student.setId(tenant + "-" + kind); student.setValue("title", kind);
                        student.setAuthUserId(List.of("owner", "both", "deleted").contains(kind) ? "reader" : "other");
                        student.setAuthAssigneeIds(List.of("assignee", "both").contains(kind) ? "reader" : "other");
                        runtime.entityService("school.student", "student").insert(student);
                        if (kind.equals("deleted")) db.execute("update scope_student set deleted=true where id=?", student.getId());
                        // Every source tenant also references targets from the other tenant.
                        for (String sourceTenant : List.of("tenant-a", "tenant-b")) {
                            try (var sourceScope = TenantContext.use(sourceTenant)) {
                                var score = runtime.newRecord("sales.score", "score");
                                score.setId(sourceTenant + "-" + student.getId());
                                score.setValue("title", "OPEN"); score.setValue("studentId", student.getId());
                                runtime.entityService("sales.score", "score").insert(score);
                            }
                        }
                    }
                }
            }
            db.execute("delete from iam_employment_role_grant"); db.execute("delete from iam_account_role_grant"); db.execute("delete from iam_role_action"); db.execute("delete from iam_role");
            var employees = mock(EmployeeService.class);
            var positions = mock(EmployeePositionService.class);
            var employeeAccounts = mock(EmployeeAccountService.class);
            var employee = new Employee(); employee.setId("employee"); employee.setEnabled(true);
            var position = new EmployeePosition(); position.setId("position"); position.setEmployeeId("employee"); position.setEnabled(true);
            when(employeeAccounts.employeeIdOfUser("reader")).thenReturn("employee");
            when(employees.selectActiveRaw("employee")).thenReturn(employee);
            when(positions.activePositionsForRoleResolution("employee")).thenReturn(List.of(position));
            var roles = new RoleService(roleRows, accountGrants, employmentGrants, actionRows, tenant -> { },
                    RoleActionGrantVerifier.platformActionsOnly(), mock(UserAccountService.class), employees,
                    positions, employeeAccounts, mock(OrganizationService.class),
                    dataGrants, mock(TenantApplicationService.class),
                    new StaticListableBeanFactory().getBeanProvider(ReferenceDependencyScopeCatalogResolver.class));
            var dependency = grant("source-platform", "sales.score", DataScopePolicy.REFERENCE_DEPENDENCY, sourceCrossTenant);
            dependency.setReferenceFieldId("studentId"); dependency.setReferenceActionCode("view");
            actionRows.updateById(dependency);
            grant("owner-platform", "school.student", DataScopePolicy.OWNER, targetCrossTenant);
            grant("assignee-platform", "school.student", DataScopePolicy.ASSIGNEE, targetCrossTenant);
            ReferenceDependencyScopeResolver resolver = request -> Optional.of(new ReferenceDependencyScopePlan(
                    "studentId", "school.student", "student", "public", "scope_student",
                    Map.of("id", "id", "tenantId", "tenant_id", "deleted", "deleted", "authUserId", "auth_user_id",
                            "authAssigneeIds", "auth_assignee_ids"), DBInfo.Type.POSTGRESQL));
            var scopes = new RoleDataScopeCriteriaService(roles, mock(TenantAdminImplicitGrantPolicy.class),
                    Optional.empty(), Optional.empty(), Optional.of(resolver));
            var records = new DynamicRecordService(runtime, new AllowAllActionExecutionPolicyService(), scopes);
            var expected = new TreeSet<String>();
            for (String sourceTenant : sourceCrossTenant && targetCrossTenant ? List.of("tenant-a", "tenant-b") : List.of("tenant-a"))
                for (String targetTenant : targetCrossTenant ? List.of("tenant-a", "tenant-b") : List.of("tenant-a"))
                    for (String kind : List.of("owner", "assignee", "both")) expected.add(sourceTenant + "-" + targetTenant + "-" + kind);
            try (var tenant = TenantContext.use("tenant-a");
                 var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "tenant-a"))) {
                var actual = new ArrayList<String>();
                for (int page = 1; page <= (expected.size() + 1) / 2 + 1; page++) {
                    var result = records.page("sales.score", "score", Criteria.of().eq("title", "OPEN"), PageRequest.of(page, 2), Sort.asc("id"));
                    assertThat(result.getTotal()).isEqualTo(expected.size());
                    actual.addAll(result.getRecords().stream().map(DynamicRecord::getId).toList());
                }
                assertThat(actual).containsExactlyElementsOf(expected);
                var all = records.page("sales.score", "score", Criteria.of().eq("title", "OPEN"), PageRequest.of(1, 100), Sort.asc("id"));
                assertThat(all.getRecords().stream().map(DynamicRecord::getId)).containsExactlyElementsOf(actual);
                assertThat(records.page("sales.score", "score", Criteria.of().eq("title", "CLOSED"), PageRequest.of(1, 2)).getTotal()).isZero();
            }
        }
    }

    private RoleAction grant(String roleId, String module, DataScopePolicy policy, boolean crossTenant) {
        var role = new Role(); role.setId(roleId); role.setTitle(roleId); role.setAssignmentType(RoleAssignmentType.EMPLOYMENT);
        role.setOwnerScopeType(RoleOwnerScopeType.PLATFORM); role.setOwnerScopeKey("platform:"); role.setSharePolicy(RoleSharePolicy.PLATFORM);
        EntityLifecycle.prepareInsert(role, Instant.now()); roleRows.insert(role);
        var binding = new EmploymentRoleGrant(); binding.setRoleId(roleId); binding.setEmployeePositionId("position"); binding.setTenantId("tenant-a");
        EntityLifecycle.prepareInsert(binding, Instant.now()); employmentGrants.insert(binding);
        var grant = new RoleAction(); grant.setRoleId(roleId); grant.setDataScopePolicy(policy);
        grant.setModuleAlias(module); grant.setActionCode("view");
        // Platform-owned roles are the only fixtures allowed to grant ALL_TENANTS.
        grant.setTenantId(null); grant.setTenantScopePolicy(crossTenant ? TenantScopePolicy.ALL_TENANTS : TenantScopePolicy.CURRENT_TENANT);
        EntityLifecycle.prepareInsert(grant, Instant.now()); actionRows.insert(grant);
        return grant;
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = RoleDao.class)
    static class Host {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
    }
}
