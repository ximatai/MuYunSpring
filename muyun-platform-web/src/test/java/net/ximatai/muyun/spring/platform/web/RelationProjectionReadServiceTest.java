package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.spring.platform.module.StaticReferenceDefinition;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.reference.ModuleReadProjection;
import net.ximatai.muyun.spring.ability.reference.ReferencePath;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationProjectionReadServiceTest {
    @Test
    void shouldQueryRelationProjectionWithCriteriaSortFieldsAndResponseBoundary() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        RelationProjectionReadService service = new RelationProjectionReadService(
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider()
        );
        StaticModuleDefinition definition = userRelationDefinition();
        RecordReadProjection projection = defaultListProjection(definition);
        Criteria criteria = Criteria.of().eq("tenantId", "tenant_a")
                .andGroup(group -> group.eq("passwordStatus", "ACTIVE"));
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of(
                        "id", "user-1",
                        "version", 7,
                        "deletedAt", java.time.Instant.EPOCH,
                        "username", "alice",
                        "employeeNo", "E001",
                        "employeeTitle", "Alice"
                )));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        PageResult<Map<String, Object>> page = service.queryList(
                definition,
                projection,
                criteria,
                PageRequest.of(1, 20),
                Sort.desc("lastLoginAt")
        ).orElseThrow();

        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getRecords()).singleElement()
                .satisfies(record -> assertThat(record)
                        .containsEntry("employeeNo", "E001")
                        .containsEntry("version", 7)
                        .containsEntry("deletedAt", java.time.Instant.EPOCH)
                        .doesNotContainKeys("tenantId", "deleted", "passwordStatus", "lastLoginAt"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.captor();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(jdbcOperations).queryForList(sqlCaptor.capture(), paramsCaptor.capture());
        String dataSql = sqlCaptor.getValue();
        assertThat(dataSql.substring(0, dataSql.indexOf(" from (")))
                .contains("\"id\"", "\"version\"", "\"deletedAt\"", "\"username\"", "\"employeeNo\"", "\"employeeTitle\"")
                .doesNotContain("\"tenantId\"", "\"deleted\"");
        assertThat(dataSql)
                .contains("\"main\".\"password_status\" as \"passwordStatus\"")
                .contains("\"main\".\"last_login_at\" as \"lastLoginAt\"")
                .contains("order by \"lastLoginAt\" desc");
        assertThat(paramsCaptor.getValue()).containsKeys("__limit", "__offset",
                "__join_bound_employee_bound_employee_account_0",
                "__join_bound_employee_bound_employee_0");
    }

    @Test
    void shouldRejectProjectionWithoutRelationFields() {
        RelationProjectionReadService service = new RelationProjectionReadService(
                mock(RelationProjectionQueryExecutor.class),
                new RelationProjectionDatabaseTypeProvider()
        );
        RecordReadProjection projection = new RecordReadProjection(
                "iam.user",
                "defaultList",
                List.of(ViewFieldRef.main("username")),
                List.of("id"),
                List.of()
        );

        assertThat(service.supportsListQuery(userRelationDefinition(), projection)).isFalse();
        assertThat(service.describeListQuery(userRelationDefinition(), projection).fallbackReason())
                .isEqualTo(ProjectionQueryFallbackReason.NO_RELATION_OUTPUT);
    }

    @Test
    void shouldRejectProjectionWithPostReadTransforms() {
        RelationProjectionReadService service = new RelationProjectionReadService(
                mock(RelationProjectionQueryExecutor.class),
                new RelationProjectionDatabaseTypeProvider()
        );
        RecordReadProjection projection = new RecordReadProjection(
                "iam.user",
                "defaultList",
                List.of(ViewFieldRef.main("username"), ViewFieldRef.relation("bound_employee", "employeeNo")),
                List.of("id"),
                List.of("dictionary:title")
        );

        assertThat(service.supportsListQuery(userRelationDefinition(), projection)).isFalse();
        assertThat(service.describeListQuery(userRelationDefinition(), projection).fallbackReason())
                .isEqualTo(ProjectionQueryFallbackReason.POST_READ_TRANSFORM);
    }

    @Test
    void shouldSupportOptionTitlePostReadTransformsOnSqlProjectionOutput() {
        RelationProjectionReadService service = new RelationProjectionReadService(
                mock(RelationProjectionQueryExecutor.class),
                new RelationProjectionDatabaseTypeProvider()
        );
        RecordReadProjection projection = new RecordReadProjection(
                "iam.user",
                "defaultList",
                List.of(ViewFieldRef.main("username"),
                        ViewFieldRef.main("passwordStatus"),
                        ViewFieldRef.relation("bound_employee", "employeeNo")),
                List.of("id"),
                List.of("optionLoad:passwordStatusTitle")
        );

        ProjectionQueryDescriptor descriptor = service.describeListQuery(userRelationDefinition(), projection);

        assertThat(descriptor.supported()).isTrue();
        assertThat(descriptor.fallbackReason()).isEqualTo(ProjectionQueryFallbackReason.NONE);
    }

    @Test
    void shouldApplyFieldProtectionPostReadTransformsOnSqlProjectionOutput() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        RelationProjectionReadService service = new RelationProjectionReadService(
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider()
        );
        StaticModuleDefinition user = userReferenceDefinition();
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeReferenceDefinition(masked());
        RecordReadProjection projection = new RecordReadProjection(
                "iam.user",
                "defaultList",
                List.of(ViewFieldRef.main("username"), ViewFieldRef.main("employeeTitle")),
                List.of("id"),
                List.of("fieldProtection:employeeTitle")
        );
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of(
                        "id", "user-1",
                        "username", "alice",
                        "employeeTitle", "Sensitive"
                )));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        ProjectionQueryDescriptor descriptor = service.describeListQuery(List.of(user, binding, employee),
                user, projection);
        assertThat(descriptor.supported()).isTrue();

        PageResult<Map<String, Object>> page = service.queryList(
                List.of(user, binding, employee),
                user,
                projection,
                Criteria.of(),
                PageRequest.of(1, 20)
        ).orElseThrow();

        assertThat(page.getRecords()).singleElement()
                .satisfies(record -> assertThat(record).containsEntry("employeeTitle", "S*******e"));
    }

    @Test
    void shouldMaskRelationPathProjectionOutputFields() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        RelationProjectionReadService service = new RelationProjectionReadService(
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider()
        );
        StaticModuleDefinition user = userWithEmployeeReferenceDefinition();
        StaticModuleDefinition employee = employeeReferenceDefinition(masked());
        RecordReadProjection projection = new RecordReadProjection(
                "iam.user",
                "defaultList",
                List.of(ViewFieldRef.main("username"), ViewFieldRef.relation("employee", "title")),
                List.of("id"),
                List.of("fieldProtection:title")
        );
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of(
                        "id", "user-1",
                        "username", "alice",
                        "title", "Sensitive"
                )));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        PageResult<Map<String, Object>> page = service.queryList(
                List.of(user, employee),
                user,
                projection,
                Criteria.of(),
                PageRequest.of(1, 20)
        ).orElseThrow();

        assertThat(page.getRecords()).singleElement()
                .satisfies(record -> assertThat(record).containsEntry("title", "S*******e"));
    }

    @Test
    void shouldRejectStorageProtectedSqlProjectionOutputFields() {
        RelationProjectionReadService service = new RelationProjectionReadService(
                mock(RelationProjectionQueryExecutor.class),
                new RelationProjectionDatabaseTypeProvider()
        );
        StaticModuleDefinition user = userReferenceDefinition();
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeReferenceDefinition(storageProtected());
        RecordReadProjection projection = new RecordReadProjection(
                "iam.user",
                "defaultList",
                List.of(ViewFieldRef.main("username"), ViewFieldRef.main("employeeTitle")),
                List.of("id"),
                List.of("fieldProtection:employeeTitle")
        );

        ProjectionQueryDescriptor descriptor = service.describeListQuery(List.of(user, binding, employee),
                user, projection);

        assertThat(descriptor.supported()).isFalse();
        assertThat(descriptor.fallbackReason()).isEqualTo(ProjectionQueryFallbackReason.PROTECTED_FIELD);
    }

    @Test
    void shouldRejectStorageProtectedReferenceJoinFields() {
        RelationProjectionReadService service = new RelationProjectionReadService(
                mock(RelationProjectionQueryExecutor.class),
                new RelationProjectionDatabaseTypeProvider()
        );
        StaticModuleDefinition user = userWithEmployeeReferenceDefinition(storageProtected());
        StaticModuleDefinition employee = employeeReferenceDefinition();
        RecordReadProjection projection = new RecordReadProjection(
                "iam.user",
                "defaultList",
                List.of(ViewFieldRef.main("username"), ViewFieldRef.relation("employee", "title")),
                List.of("id"),
                List.of()
        );

        ProjectionQueryDescriptor descriptor = service.describeListQuery(List.of(user, employee), user, projection);

        assertThat(descriptor.supported()).isFalse();
        assertThat(descriptor.fallbackReason()).isEqualTo(ProjectionQueryFallbackReason.PROTECTED_FIELD);
    }

    @Test
    void shouldSortByServiceReadProjectionOutputWithoutAllowingCriteriaOnIt() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        RelationProjectionReadService service = new RelationProjectionReadService(
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider()
        );
        StaticModuleDefinition user = userReferenceDefinition();
        StaticModuleDefinition binding = employeeAccountReferenceDefinition();
        StaticModuleDefinition employee = employeeReferenceDefinition();
        RecordReadProjection projection = defaultListProjection(user);
        ProjectionQueryDescriptor descriptor = service.describeListQuery(List.of(user, binding, employee),
                user, projection);
        assertThat(descriptor.supported()).isTrue();
        assertThat(descriptor.queryableFields()).contains("employeeNo");
        assertThat(descriptor.sortableFields()).contains("employeeTitle");
        assertThat(descriptor.responseFields()).contains("id", "username", "employeeNo", "employeeTitle");
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of(
                        "id", "user-1",
                        "username", "alice",
                        "employeeNo", "E001",
                        "employeeTitle", "Alice"
                )));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        PageResult<Map<String, Object>> page = service.queryList(
                List.of(user, binding, employee),
                user,
                projection,
                Criteria.of(),
                PageRequest.of(1, 20),
                Sort.asc("employeeTitle")
        ).orElseThrow();

        assertThat(page.getRecords()).singleElement()
                .satisfies(record -> assertThat(record).containsEntry("employeeTitle", "Alice"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(jdbcOperations).queryForList(sqlCaptor.capture(), any(Map.class));
        assertThat(sqlCaptor.getValue()).contains("order by \"employeeTitle\" asc");

        org.mockito.Mockito.clearInvocations(jdbcOperations);
        PageResult<Map<String, Object>> filteredPage = service.queryList(
                List.of(user, binding, employee),
                user,
                projection,
                Criteria.of().eq("employeeNo", "E001"),
                PageRequest.of(1, 20)
        ).orElseThrow();
        assertThat(filteredPage.getTotal()).isEqualTo(1);
        org.mockito.Mockito.verify(jdbcOperations).queryForList(sqlCaptor.capture(), any(Map.class));
        assertThat(sqlCaptor.getValue()).contains("where \"employeeNo\" = :");

        assertThatThrownBy(() -> service.queryList(
                List.of(user, binding, employee),
                user,
                projection,
                Criteria.of().eq("employeeTitle", "Alice"),
                PageRequest.of(1, 20)
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("projection query field is not projected: employeeTitle");
    }

    private static RecordReadProjection defaultListProjection(StaticModuleDefinition definition) {
        ModuleUiCompilationResult compilation = ModuleUiDescriptorCompiler.compileModule(definition);
        return RecordReadProjectionPlanner.defaultList(compilation.uiDescriptor(), compilation.readModel());
    }

    private static StaticModuleDefinition userRelationDefinition() {
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
                                        FieldDefinition.string("passwordStatus", "密码状态").column("password_status"),
                                        FieldDefinition.timestamp("lastLoginAt", "最后登录时间").column("last_login_at")
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
                        RelationProjectionCardinality.ONE_TO_ONE,
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

    private static StaticModuleDefinition userReferenceDefinition() {
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
                                .field("employeeTitle"))
                        )
                       .references(List.of())
                       .readProjections(List.of(
                        new StaticModuleReadProjectionDefinition(
                                null,
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getEmployeeNo),
                                "employeeNo",
                                ModuleReadProjection.ProjectionType.FIELD,
                                true,
                                true
                        ),
                        new StaticModuleReadProjectionDefinition(
                                ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                        .then(EmployeeAccount::getEmployeeId)
                                        .select(Employee::getTitle),
                                "employeeTitle"
                        )
                ))
                       .modelClass(UserAccount.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private static StaticModuleDefinition userWithEmployeeReferenceDefinition() {
        return userWithEmployeeReferenceDefinition(FieldProtectionDefinition.NONE);
    }

    private static StaticModuleDefinition userWithEmployeeReferenceDefinition(
            FieldProtectionDefinition employeeIdProtection) {
        return StaticModuleDefinition.builder("iam", "iam.user", "用户管理")
                       .parentModuleAlias(null)
                       .entry(ModuleEntryType.ROUTE, "/iam/users", null)
                       .capabilities(Set.of(EntityCapability.CRUD))
                       .actions(List.of())
                       .entities(List.of(new EntityDefinition(
                        "user",
                        "iam_user",
                        "User",
                        List.of(
                                FieldDefinition.string("username", "账号").column("username"),
                                FieldDefinition.string("employeeId", "职员").column("employee_id")
                                        .protection(employeeIdProtection)
                        )
                )))
                       .uiDefinition(null)
                       .references(List.of(new StaticReferenceDefinition("employee", "employeeId", "iam.employee")))
                       .readProjections(List.of())
                       .modelClass(UserAccount.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private static StaticModuleDefinition employeeAccountReferenceDefinition() {
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

    private static StaticModuleDefinition employeeReferenceDefinition() {
        return employeeReferenceDefinition(FieldProtectionDefinition.NONE);
    }

    private static StaticModuleDefinition employeeReferenceDefinition(FieldProtectionDefinition protection) {
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
                                        .protection(protection)
                        )
                )))
                       .uiDefinition(null)
                       .references(List.of())
                       .readProjections(List.of())
                       .modelClass(Employee.class)
                       .projectionJoins(List.of())
                       .build();
    }

    private static FieldProtectionDefinition masked() {
        return new FieldProtectionDefinition(
                FieldEncryptionMode.NONE,
                FieldSignatureMode.NONE,
                FieldMaskingPolicy.MIDDLE
        );
    }

    private static FieldProtectionDefinition storageProtected() {
        return new FieldProtectionDefinition(
                FieldEncryptionMode.ENCRYPTED,
                FieldSignatureMode.SIGNED,
                FieldMaskingPolicy.MIDDLE
        );
    }
}
