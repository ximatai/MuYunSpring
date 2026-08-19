package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.spring.platform.module.StaticReferenceDefinition;

import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.ability.reference.ReferencePath;
import net.ximatai.muyun.spring.ability.reference.ReferenceProjection;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.iam.department.Department;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.organization.Organization;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationProjectionQueryPlannerTest {
    @Test
    void shouldPlanSqlJoinProjectionForStaticRelationFields() {
        StaticModuleDefinition definition = userDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(definition);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                definition,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of("passwordStatus", "createdAt")
        );

        assertThat(plan.hasRelationProjection()).isTrue();
        assertThat(plan.queryableFields()).contains(
                "id", "tenantId", "deleted", "createdAt", "updatedAt",
                "username", "passwordStatus");
        assertThat(plan.queryableFields()).doesNotContain("employeeNo", "employeeTitle");
        assertThat(plan.responseFields()).containsExactlyInAnyOrder(
                "id", "version", "deletedAt", "username", "employeeNo", "employeeTitle");
        assertThat(plan.responseFields()).doesNotContain("tenantId", "deleted");
        assertThat(plan.relationOutputFields()).extracting(ViewFieldRef::fieldName)
                .containsExactly("employeeNo", "employeeTitle");
        assertThat(plan.baseSql())
                .contains("from \"public\".\"iam_user\" \"main\"")
                .contains("\"main\".\"password_status\" as \"passwordStatus\"")
                .contains("left join \"public\".\"iam_employee_account\" \"bound_employee_account\"")
                .contains("\"main\".\"id\" = \"bound_employee_account\".\"user_id\"")
                .contains("left join \"public\".\"iam_employee\" \"bound_employee\"")
                .contains("\"bound_employee_account\".\"employee_id\" = \"bound_employee\".\"id\"")
                .contains("\"bound_employee\".\"title\" as \"employeeTitle\"");
        assertThat(plan.baseSql()).doesNotContain("\"main\".\"enabled\" as \"enabled\"");
        assertThat(plan.baseParams())
                .containsEntry("__join_bound_employee_bound_employee_account_0", Boolean.FALSE)
                .containsEntry("__join_bound_employee_bound_employee_0", Boolean.FALSE);
    }

    @Test
    void shouldPlanRecursiveReferencePathProjectionFromStaticReferences() {
        StaticModuleDefinition user = userReferenceDefinition();
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(user);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                List.of(user, binding, employee),
                user,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        );

        assertThat(plan.hasRelationProjection()).isTrue();
        assertThat(plan.projectionGraph()).isNotNull();
        assertThat(plan.projectionGraph().edges())
                .filteredOn(edge -> edge.edgeKind() == ProjectionGraphEdgeKind.REFERENCE_JOIN)
                .hasSize(2);
        assertThat(plan.projectionGraph().edges())
                .filteredOn(edge -> edge.edgeKind() == ProjectionGraphEdgeKind.REFERENCE_OUTPUT_FIELD)
                .filteredOn(edge -> edge.outputFieldName().equals("employeeNo"))
                .singleElement()
                .satisfies(edge -> {
                    assertThat(edge.sourceNodeId()).isEqualTo("join:user_id_employee_id");
                    assertThat(edge.targetFieldName()).isEqualTo("employeeNo");
                    assertThat(edge.existsProjection()).isFalse();
                });
        assertThat(plan.projectionGraph().nodes())
                .filteredOn(node -> node.nodeId().equals("join:user_id_employee_id"))
                .singleElement()
                .satisfies(node -> {
                    assertThat(node.moduleAlias()).isEqualTo("iam.employee");
                    assertThat(node.entityAlias()).isEqualTo("employee");
                    assertThat(node.tableAlias()).isEqualTo("user_id_employee_id");
                });
        assertThat(plan.queryableFields()).contains("id", "username", "employeeNo");
        assertThat(plan.queryableFields()).doesNotContain("employeeTitle");
        assertThat(plan.sortableFields()).contains("id", "username", "employeeNo", "employeeTitle");
        assertThat(plan.responseFields()).containsExactlyInAnyOrder(
                "id", "version", "deletedAt", "username", "employeeNo", "employeeTitle");
        assertThat(plan.baseSql())
                .contains("left join \"public\".\"iam_employee_account\" \"user_id\"")
                .contains("\"main\".\"id\" = \"user_id\".\"user_id\"")
                .contains("left join \"public\".\"iam_employee\" \"user_id_employee_id\"")
                .contains("\"user_id\".\"employee_id\" = \"user_id_employee_id\".\"id\"")
                .contains("\"user_id_employee_id\".\"employee_no\" as \"employeeNo\"")
                .contains("\"user_id_employee_id\".\"title\" as \"employeeTitle\"");
        assertThat(plan.baseParams())
                .containsEntry("__join_user_id_deleted", Boolean.FALSE)
                .containsEntry("__join_user_id_employee_id_deleted", Boolean.FALSE);
    }

    @Test
    void shouldPlanExplicitUserSelectorProjectionWithOrganizationAndDepartmentFields() {
        StaticModuleDefinition user = userSelectorReferenceDefinition();
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeWithOrganizationAndDepartmentDefinition();
        StaticModuleDefinition organization = organizationReferenceDefinition();
        StaticModuleDefinition department = departmentReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(user);
        RecordReadProjection projection = RecordReadProjectionPlanner.explicit(
                "iam.user",
                compilation.readModel(),
                "user_selector",
                List.of(
                        "id",
                        "username",
                        "employeeId",
                        "employeeNo",
                        "employeeTitle",
                        "employeeOrganizationId",
                        "organizationTitle",
                        "employeeDepartmentId",
                        "departmentTitle"
                ),
                null,
                null
        );

        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                List.of(user, binding, employee, organization, department),
                user,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        );

        assertThat(plan.hasRelationProjection()).isTrue();
        assertThat(plan.responseFields()).containsExactlyInAnyOrder(
                "id",
                "version",
                "deletedAt",
                "username",
                "employeeId",
                "employeeNo",
                "employeeTitle",
                "employeeOrganizationId",
                "organizationTitle",
                "employeeDepartmentId",
                "departmentTitle"
        );
        assertThat(plan.baseSql())
                .contains("left join \"public\".\"iam_employee_account\" \"user_id\"")
                .contains("left join \"public\".\"iam_employee\" \"user_id_employee_id\"")
                .contains("left join \"public\".\"iam_organization\" \"user_id_employee_id_organization_id\"")
                .contains("left join \"public\".\"iam_department\" \"user_id_employee_id_department_id\"")
                .contains("\"user_id\".\"employee_id\" as \"employeeId\"")
                .contains("\"user_id_employee_id\".\"employee_no\" as \"employeeNo\"")
                .contains("\"user_id_employee_id\".\"title\" as \"employeeTitle\"")
                .contains("\"user_id_employee_id\".\"organization_id\" as \"employeeOrganizationId\"")
                .contains("\"user_id_employee_id_organization_id\".\"title\" as \"organizationTitle\"")
                .contains("\"user_id_employee_id\".\"department_id\" as \"employeeDepartmentId\"")
                .contains("\"user_id_employee_id_department_id\".\"title\" as \"departmentTitle\"");
    }

    @Test
    void shouldRejectExplicitProjectionForNonListActionContext() {
        StaticModuleDefinition user = userSelectorReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(user);
        ActionExecutionPolicy policy = new ActionExecutionPolicy(
                "resetPassword",
                PlatformActionLevel.RECORD,
                ActionAccessMode.AUTH_REQUIRED,
                true,
                true,
                ActionDefaultGrantPolicy.NONE,
                null
        );

        assertThatThrownBy(() -> RecordReadProjectionPlanner.explicit(
                "iam.user",
                compilation.readModel(),
                "user_selector",
                List.of("id", "username"),
                null,
                ActionExecutionContext.ofPolicy("iam.user", policy, Set.of("user-1"), java.util.Optional.empty())
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit record read projection requires list action context: "
                        + "iam.user.resetPassword");
    }

    @Test
    void shouldPlanDirectReferenceProjectionFromStaticReferences() {
        StaticModuleDefinition employee = employeeWithOrganizationProjectionDefinition();
        StaticModuleDefinition organization = organizationReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(employee);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                List.of(employee, organization),
                employee,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        );

        assertThat(plan.hasRelationProjection()).isTrue();
        assertThat(plan.queryableFields()).doesNotContain("organizationTitle");
        assertThat(plan.sortableFields()).contains("organizationTitle");
        assertThat(plan.responseFields()).containsExactlyInAnyOrder(
                "id", "version", "deletedAt", "employeeNo", "organizationTitle", "title");
        assertThat(plan.baseSql())
                .contains("left join \"public\".\"iam_organization\" \"organization\"")
                .contains("\"main\".\"organization_id\" = \"organization\".\"id\"")
                .contains("\"organization\".\"title\" as \"organizationTitle\"");
        assertThat(plan.baseParams())
                .containsEntry("__join_organization_deleted", Boolean.FALSE);
    }

    @Test
    void shouldPlanDynamicReferenceProjectionThroughUnifiedRelationDefinitions() {
        ModuleDefinition order = ModuleDefinition.builder("crm.order", "订单")
                .entities(List.of(new EntityDefinition(
                        "order",
                        "crm_order",
                        "Order",
                        List.of(
                                FieldDefinition.string("customerId", "客户").column("customer_id"),
                                FieldDefinition.string("orderNo", "订单号").column("order_no")
                        )
                )))
                .relations(List.of())
                .references(List.of(new EntityReferenceDefinition(
                        "order",
                        "customerId",
                        "crm.customer.customer"
                ).withProjection("title", "customerTitle")))
                .build();
        ModuleDefinition customer = new ModuleDefinition(
                "crm.customer",
                "客户",
                List.of(new EntityDefinition(
                        "customer",
                        "crm_customer",
                        "Customer",
                        List.of(FieldDefinition.string("title", "客户名称").column("title"))
                ))
        );
        List<StaticModuleDefinition> definitions = DynamicRelationProjectionDefinitionAdapter.adapt(
                List.of(order, customer));
        StaticModuleDefinition orderDefinition = definitions.stream()
                .filter(definition -> definition.moduleAlias().equals("crm.order"))
                .findFirst()
                .orElseThrow();
        RecordReadProjection projection = new RecordReadProjection(
                "crm.order",
                "dynamic_list",
                List.of(ViewFieldRef.main("orderNo"), ViewFieldRef.main("customerTitle")),
                List.of("id", "tenantId", "version"),
                List.of()
        );

        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                definitions,
                orderDefinition,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        );

        assertThat(plan.hasRelationProjection()).isTrue();
        assertThat(plan.projectionGraph()).isNotNull();
        assertThat(plan.projectionGraph().edges())
                .filteredOn(edge -> edge.edgeKind() == ProjectionGraphEdgeKind.REFERENCE_JOIN)
                .singleElement()
                .satisfies(edge -> assertThat(edge.tableAlias()).isEqualTo("customer_id"));
        assertThat(plan.responseFields()).containsExactlyInAnyOrder(
                "id", "version", "deletedAt", "orderNo", "customerTitle");
        assertThat(plan.baseSql())
                .contains("from \"public\".\"crm_order\" \"main\"")
                .contains("left join \"public\".\"crm_customer\" \"customer_id\"")
                .contains("\"main\".\"customer_id\" = \"customer_id\".\"id\"")
                .contains("\"customer_id\".\"title\" as \"customerTitle\"");
    }

    @Test
    void shouldRejectReadProjectionOutputConflictWithMainField() {
        assertThatThrownBy(() -> userReferenceDefinitionWithOutput("employee_account.employee.title", "username"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read projection output field conflicts with main field: iam.user.username");
    }

    @Test
    void shouldRejectDuplicateReadProjectionOutputField() {
        assertThatThrownBy(this::duplicateReadProjectionOutputDefinition)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate static module read projection output field: iam.employee.organizationTitle");
    }

    @Test
    void shouldRejectDuplicateReferenceCode() {
        assertThatThrownBy(this::duplicateReferenceCodeDefinition)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate static reference code: iam.employee.organization");
    }

    @Test
    void shouldRejectReadProjectionPathCannotResolve() {
        StaticModuleDefinition user = userReferenceDefinitionWithOutput("missing.employee.title", "employeeTitle");
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(user);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        assertThatThrownBy(() -> RelationProjectionQueryPlanner.plan(
                List.of(user, binding, employee),
                user,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection reference path is not declared: "
                        + "iam.user.employeeTitle.missing.employee");
    }

    @Test
    void shouldRejectMissingReferenceTargetField() {
        StaticModuleDefinition employee = employeeWithReadProjectionDefinition("organization.missingTitle",
                "organizationTitle");
        StaticModuleDefinition organization = organizationReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(employee);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        assertThatThrownBy(() -> RelationProjectionQueryPlanner.plan(
                List.of(employee, organization),
                employee,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection reference field is not declared: organization.missingTitle");
    }

    @Test
    void shouldRejectUnsafeInverseReferencePathProjection() {
        StaticModuleDefinition user = userReferenceDefinitionWithOutput(
                ReferencePath.inverse(EmployeeAccount::getUserId)
                        .then(EmployeeAccount::getEmployeeId)
                        .select(Employee::getTitle),
                "employeeTitle");
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(user);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        assertThatThrownBy(() -> RelationProjectionQueryPlanner.plan(
                List.of(user, binding, employee),
                user,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection reference path cardinality is not safe for page join")
                .hasMessageContaining("ONE_TO_MANY");
    }

    @Test
    void shouldRejectReferenceProjectionPathExceedingDepthLimit() {
        StaticModuleDefinition user = userSelectorReferenceDefinition();
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeWithOrganizationAndDepartmentDefinition();
        StaticModuleDefinition organization = organizationReferenceDefinition();
        StaticModuleDefinition department = departmentReferenceDefinition();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(user);
        RecordReadProjection projection = RecordReadProjectionPlanner.explicit(
                "iam.user",
                compilation.readModel(),
                "user_selector",
                List.of("id", "username", "organizationTitle"),
                null,
                null
        );

        assertThatThrownBy(() -> RelationProjectionQueryPlanner.plan(
                List.of(user, binding, employee, organization, department),
                user,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of(),
                new RelationProjectionPlanningOptions(2, 24)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relation projection reference path depth exceeds limit")
                .hasMessageContaining("3 > 2");
    }

    @Test
    void shouldAllowFiniteReferenceProjectionPathReturningToVisitedModule() {
        StaticModuleDefinition a = cyclicDefinitionA();
        StaticModuleDefinition b = cyclicDefinitionB();
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(a);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        RelationProjectionSqlPlan plan = RelationProjectionQueryPlanner.plan(
                List.of(a, b),
                a,
                projection,
                DBInfo.Type.POSTGRESQL,
                Set.of()
        );

        assertThat(plan.hasRelationProjection()).isTrue();
        assertThat(plan.baseSql())
                .contains("left join \"public\".\"test_b\" \"b\"")
                .contains("left join \"public\".\"test_a\" \"b_a\"")
                .contains("\"b_a\".\"title\" as \"bTitle\"");
    }

    @Test
    void shouldRejectUnsafePageJoinCardinality() {
        StaticModuleDefinition definition = userDefinition(RelationProjectionCardinality.ONE_TO_MANY);
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(definition);
        RecordReadProjection projection = RecordReadProjectionPlanner.defaultList(
                compilation.uiDescriptor(),
                compilation.readModel()
        );

        assertThatThrownBy(() -> RelationProjectionQueryPlanner.plan(definition, projection, DBInfo.Type.POSTGRESQL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cardinality is not safe for page join");
    }

    @Test
    void shouldRequireExplicitJoinCardinality() {
        assertThatThrownBy(() -> userDefinition(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection join cardinality must not be null");
    }

    private StaticModuleDefinition userDefinition() {
        return userDefinition(RelationProjectionCardinality.ONE_TO_ONE);
    }

    private StaticModuleDefinition userReferenceDefinition() {
        return userReferenceDefinitionWithOutput(
                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                        .then(EmployeeAccount::getEmployeeId)
                        .select(Employee::getTitle),
                "employeeTitle");
    }

    private StaticModuleDefinition userSelectorReferenceDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.user", "用户管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/users", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "user",
                        "iam_user",
                        "User",
                        List.of(FieldDefinition.string("username", "账号").column("username"))
                )))
                       .uiDefinition(TestModulePages.listDetail("iam.user", list -> list.field("username")))
                       .references(List.of())
                       .readProjections(List.of(
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .select(EmployeeAccount::getEmployeeId),
                                "employeeId"
                        ),
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getEmployeeNo),
                                "employeeNo"
                        ),
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getTitle),
                                "employeeTitle"
                        ),
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getOrganizationId),
                                "employeeOrganizationId"
                        ),
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .then(Employee::getOrganizationId)
                                        .select(Organization::getTitle),
                                "organizationTitle"
                        ),
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getDepartmentId),
                                "employeeDepartmentId"
                        ),
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .then(Employee::getDepartmentId)
                                        .select(Department::getTitle),
                                "departmentTitle"
                        )
                ))
                       .modelClass(UserAccount.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition userReferenceDefinitionWithOutput(String readProjectionPath, String outputField) {
        return StaticModuleDefinition.builder("iam", "iam.user", "用户管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/users", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "user",
                        "iam_user",
                        "User",
                        List.of(FieldDefinition.string("username", "账号").column("username"))
                )))
                       .uiDefinition(TestModulePages.listDetail("iam.user", list -> list
                                .field("username")
                                .field("employeeNo")
                                .field(outputField))
                        )
                       .references(List.of())
                       .readProjections(List.of(
                        new StaticModuleReadProjectionDefinition(
                                null,
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getEmployeeNo),
                                "employeeNo",
                                net.ximatai.muyun.spring.ability.reference.ModuleReadProjection.ProjectionType.FIELD,
                                true,
                                true
                        ),
                        new StaticModuleReadProjectionDefinition(
                                readProjectionPath,
                                outputField
                        )
                ))
                       .modelClass(UserAccount.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition userReferenceDefinitionWithOutput(ReferencePath readProjectionPath,
                                                                     String outputField) {
        return StaticModuleDefinition.builder("iam", "iam.user", "用户管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/users", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "user",
                        "iam_user",
                        "User",
                        List.of(FieldDefinition.string("username", "账号").column("username"))
                )))
                       .uiDefinition(TestModulePages.listDetail("iam.user", list -> list
                                .field("username")
                                .field("employeeNo")
                                .field(outputField))
                        )
                       .references(List.of())
                       .readProjections(List.of(
                        new StaticModuleReadProjectionDefinition(
                                null,
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getEmployeeNo),
                                "employeeNo",
                                net.ximatai.muyun.spring.ability.reference.ModuleReadProjection.ProjectionType.FIELD,
                                true,
                                true
                        ),
                        new StaticModuleReadProjectionDefinition(
                                readProjectionPath,
                                outputField
                        )
                ))
                       .modelClass(UserAccount.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition employeeAccountReferenceDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.employee_account", "职员账号绑定")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.MODULE, null, null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "employee_account",
                        "iam_employee_account",
                        "Employee Account",
                        List.of(
                                FieldDefinition.string("employeeId", "职员").column("employee_id"),
                                FieldDefinition.string("userId", "用户").column("user_id")
                        )
                )))
                       .uiDefinition(null)
                       .references(List.of(
                        new StaticReferenceDefinition("employee", "employeeId", "iam.employee"),
                        new StaticReferenceDefinition("user", "userId", "iam.user")
                ))
                       .readProjections(List.of())
                       .modelClass(EmployeeAccount.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition employeeReferenceDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "employee",
                        "iam_employee",
                        "Employee",
                        List.of(
                                FieldDefinition.string("employeeNo", "职员编号").column("employee_no"),
                                FieldDefinition.string("title", "职员姓名").column("title")
                        )
                )))
                       .uiDefinition(null)
                       .references(List.of())
                       .readProjections(List.of())
                       .modelClass(Employee.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition employeeWithOrganizationAndDepartmentDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "employee",
                        "iam_employee",
                        "Employee",
                        List.of(
                                FieldDefinition.string("organizationId", "所属机构").column("organization_id"),
                                FieldDefinition.string("departmentId", "所属部门").column("department_id"),
                                FieldDefinition.string("employeeNo", "职员编号").column("employee_no"),
                                FieldDefinition.string("title", "职员姓名").column("title")
                        )
                )))
                       .uiDefinition(null)
                       .references(List.of(
                        new StaticReferenceDefinition("organization", "organizationId", "iam.organization"),
                        new StaticReferenceDefinition("department", "departmentId", "iam.department")
                ))
                       .readProjections(List.of())
                       .modelClass(Employee.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition employeeWithOrganizationProjectionDefinition() {
        return employeeWithReadProjectionDefinition("organization.title", "organizationTitle");
    }

    private StaticModuleDefinition employeeWithReadProjectionDefinition(String readProjectionPath, String outputField) {
        return StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "employee",
                        "iam_employee",
                        "Employee",
                        List.of(
                                FieldDefinition.string("organizationId", "所属机构").column("organization_id"),
                                FieldDefinition.string("employeeNo", "职员编号").column("employee_no"),
                                FieldDefinition.string("title", "职员姓名").column("title")
                        )
                )))
                       .uiDefinition(TestModulePages.listDetail("iam.employee", list -> list
                                .field("employeeNo")
                                .field(outputField)
                                .field("title"))
                        )
                       .references(List.of(new StaticReferenceDefinition("organization", "organizationId", "iam.organization")))
                       .readProjections(List.of(new StaticModuleReadProjectionDefinition(readProjectionPath, outputField)))
                       .modelClass(Employee.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition organizationReferenceDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.organization", "机构管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/organizations", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "organization",
                        "iam_organization",
                        "Organization",
                        List.of(FieldDefinition.string("title", "机构名称").column("title"))
                )))
                       .uiDefinition(null)
                       .references(List.of())
                       .readProjections(List.of())
                       .modelClass(Organization.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition departmentReferenceDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.department", "部门管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/departments", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "department",
                        "iam_department",
                        "Department",
                        List.of(FieldDefinition.string("title", "部门名称").column("title"))
                )))
                       .uiDefinition(null)
                       .references(List.of())
                       .readProjections(List.of())
                       .modelClass(Department.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private StaticModuleDefinition duplicateReadProjectionOutputDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "employee",
                        "iam_employee",
                        "Employee",
                        List.of(FieldDefinition.string("employeeNo", "职员编号").column("employee_no"))
                )))
                       .uiDefinition(null)
                       .references(List.of())
                       .readProjections(List.of(
                        new StaticModuleReadProjectionDefinition("organization.title", "organizationTitle"),
                        new StaticModuleReadProjectionDefinition("department.title", "organizationTitle")
                ))
                       .build();
    }

    private StaticModuleDefinition duplicateReferenceCodeDefinition() {
        return StaticModuleDefinition.builder("iam", "iam.employee", "职员管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/employees", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "employee",
                        "iam_employee",
                        "Employee",
                        List.of(
                                FieldDefinition.string("organizationId", "所属机构").column("organization_id"),
                                FieldDefinition.string("departmentId", "所属部门").column("department_id")
                        )
                )))
                       .uiDefinition(null)
                       .references(List.of(
                        new StaticReferenceDefinition("organization", "organizationId", "iam.organization"),
                        new StaticReferenceDefinition("organization", "departmentId", "iam.department")
                ))
                       .readProjections(List.of())
                       .build();
    }

    private StaticModuleDefinition cyclicDefinitionA() {
        return StaticModuleDefinition.builder("test", "test.a", "Cycle A")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/test/a", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "a",
                        "test_a",
                        "Cycle A",
                        List.of(
                                FieldDefinition.string("bId", "B").column("b_id"),
                                FieldDefinition.string("title", "Title").column("title")
                        )
                )))
                       .uiDefinition(TestModulePages.listDetail("test.a", list -> list.field("bTitle")))
                       .references(List.of(new StaticReferenceDefinition("b", "bId", "test.b")))
                       .readProjections(List.of(new StaticModuleReadProjectionDefinition("b.a.title", "bTitle")))
                       .build();
    }

    private StaticModuleDefinition cyclicDefinitionB() {
        return StaticModuleDefinition.builder("test", "test.b", "Cycle B")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/test/b", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "b",
                        "test_b",
                        "Cycle B",
                        List.of(FieldDefinition.string("aId", "A").column("a_id"))
                )))
                       .uiDefinition(null)
                       .references(List.of(new StaticReferenceDefinition("a", "aId", "test.a")))
                       .readProjections(List.of())
                       .build();
    }

    private StaticModuleDefinition userDefinition(RelationProjectionCardinality cardinality) {
        return StaticModuleDefinition.builder("iam", "iam.user", "用户管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/users", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(
                        new EntityDefinition(
                                "user",
                                "iam_user",
                                "User",
                                List.of(
                                        FieldDefinition.string("username", "账号").column("username"),
                                        FieldDefinition.bool("enabled", "启用").column("enabled"),
                                        FieldDefinition.string("passwordStatus", "密码状态").column("password_status")
                                )
                        ),
                        new EntityDefinition(
                                "bound_employee",
                                "iam_employee",
                                "绑定职员",
                                List.of(
                                        FieldDefinition.string("employeeNo", "职员工号").column("employee_no"),
                                        FieldDefinition.string("employeeTitle", "职员姓名").column("title")
                                )
                        )
                ))
                       .uiDefinition(TestModulePages.listDetail("iam.user", list -> list
                                .field("username")
                                .field("bound_employee", "employeeNo", field -> field.label("职员工号"))
                                .field("bound_employee", "employeeTitle", field -> field.label("职员姓名")))
                        )
                       .projectionJoins(List.of(new RelationProjectionJoinDefinition(
                        "bound_employee",
                        new EntityDefinition(
                                "bound_employee",
                                "iam_employee",
                                "绑定职员",
                                List.of(
                                        FieldDefinition.string("employeeNo", "职员工号").column("employee_no"),
                                        FieldDefinition.string("employeeTitle", "职员姓名").column("title")
                                )
                        ),
                        cardinality,
                        List.of(
                                new RelationProjectionJoinStep(
                                        "public",
                                        "iam_employee_account",
                                        "bound_employee_account",
                                        List.of(
                                                new RelationProjectionJoinCondition("main", "tenant_id",
                                                        "bound_employee_account", "tenant_id"),
                                                new RelationProjectionJoinCondition("main", "id",
                                                        "bound_employee_account", "user_id")
                                        ),
                                        List.of(new RelationProjectionJoinFilter(
                                                "bound_employee_account", "deleted", Boolean.FALSE))
                                ),
                                new RelationProjectionJoinStep(
                                        "public",
                                        "iam_employee",
                                        "bound_employee",
                                        List.of(
                                                new RelationProjectionJoinCondition("bound_employee_account", "tenant_id",
                                                        "bound_employee", "tenant_id"),
                                                new RelationProjectionJoinCondition("bound_employee_account", "employee_id",
                                                        "bound_employee", "id")
                                        ),
                                        List.of(new RelationProjectionJoinFilter(
                                                "bound_employee", "deleted", Boolean.FALSE))
                                )
                        )
                )))
                       .build();
    }
}
