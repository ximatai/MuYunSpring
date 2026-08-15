package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticModuleReadProjectionDefinition;

import net.ximatai.muyun.spring.platform.module.StaticReferenceDefinition;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.reference.ReferencePath;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.common.option.CodeTitleEnumOptionSourceProvider;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.iam.employee.Employee;
import net.ximatai.muyun.spring.iam.employee.EmployeeAccount;
import net.ximatai.muyun.spring.iam.user.UserAccount;
import net.ximatai.muyun.spring.iam.user.PasswordStatus;
import net.ximatai.muyun.spring.platform.module.ModuleEntryType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaticRecordReadProjectionServiceTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldApplyDataScopeBeforeExplicitRecordVisibilityInSharedListPipeline() {
        StaticRecordReadProjectionService service = spy(new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of())));
        ScopedRecycleBinAbility recordService = mock(ScopedRecycleBinAbility.class);
        PageRequest pageRequest = PageRequest.of(1, 20);
        ActionExecutionPolicy policy = ActionExecutionPolicy.standard(PlatformAction.RECYCLE_BIN_QUERY);
        Criteria compiled = Criteria.of().eq("requested", true);
        Criteria scoped = Criteria.of().eq("authorized", true);
        Criteria retained = Criteria.of().eq("authorized", true).eq("deleted", true);
        WebPageResponse<Map<String, Object>> expected = WebPageResponse.fromList(List.of(Map.of("id", "record-1")));

        doReturn(true).when(service).supportsDefaultListQuery("iam.sample", recordService);
        doReturn(compiled).when(service).queryCriteria("iam.sample", recordService, QueryRequest.empty());
        doReturn(new Sort[0]).when(service).querySorts("iam.sample", recordService, QueryRequest.empty());
        when(recordService.readScopeByPolicy(policy, compiled))
                .thenReturn(DataScopeCriteriaResult.restricted(scoped));
        when(recordService.withDataScopeTenant(any(DataScopeCriteriaResult.class), any(Supplier.class)))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
        when(recordService.recycleBinReadCriteria(scoped)).thenReturn(retained);
        doReturn(Optional.of(expected)).when(service).queryDefaultList(
                eq("iam.sample"), eq(retained), eq(pageRequest), eq(recordService), any(Sort[].class));

        Optional<WebPageResponse<Map<String, Object>>> result = service.queryDefaultList(
                "iam.sample", QueryRequest.empty(), pageRequest, recordService, policy, RecordReadVisibility.RETAINED);

        assertThat(result).contains(expected);
        verify(recordService).readScopeByPolicy(policy, compiled);
        verify(recordService).recycleBinReadCriteria(scoped);
    }

    private interface ScopedRecycleBinAbility
            extends DataScopeAbility<StandardEntity>, RecycleBinAbility<StandardEntity> {
    }

    @Test
    void shouldKeepResponseWhenStaticDefinitionIsMissing() {
        StaticRecordReadProjectionService service = new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of())
        );
        ProjectionEmployee record = new ProjectionEmployee();
        record.setId("emp-1");
        record.setEmployeeNo("E001");
        WebPageResponse<ProjectionEmployee> response = WebPageResponse.fromList(List.of(record));

        WebPageResponse<?> projected = service.projectDefaultList(
                "iam.employee",
                response,
                null
        );

        assertThat(projected).isSameAs(response);
        assertThat(projected.records()).hasSize(1);
        assertThat(projected.records().get(0)).isSameAs(record);
    }

    @Test
    void shouldProjectResponseByStaticResolvedListView() {
        StaticRecordReadProjectionService service = new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of(staticDefinition()))
        );
        ProjectionEmployee record = new ProjectionEmployee();
        record.setId("emp-1");
        record.setEmployeeNo("E001");
        record.setTitle("Alice");
        record.setMobile("13800000000");
        WebPageResponse<ProjectionEmployee> response = WebPageResponse.fromList(List.of(record));

        WebPageResponse<?> projected = service.projectDefaultList(
                "iam.employee",
                response,
                null
        );

        assertThat(projected).isNotSameAs(response);
        assertThat(projected.records()).hasSize(1);
        Map<?, ?> output = (Map<?, ?>) projected.records().get(0);
        assertThat(output.get("id")).isEqualTo("emp-1");
        assertThat(output.get("employeeNo")).isEqualTo("E001");
        assertThat(output.get("title")).isEqualTo("Alice");
        assertThat(output.containsKey("mobile")).isFalse();
        assertThat(projected.total()).isEqualTo(response.total());
        assertThat(projected.pageNum()).isEqualTo(response.pageNum());
        assertThat(projected.pageSize()).isEqualTo(response.pageSize());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void shouldPopulateOptionTitleForProjectedStaticResponse() {
        StaticRecordReadProjectionService service = new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of(userRelationDefinitionWithPasswordStatusColumn())),
                null,
                new RelationProjectionDatabaseTypeProvider(),
                new OptionSourceRegistry(List.of(new CodeTitleEnumOptionSourceProvider()))
        );
        CrudAbility recordService = mock(CrudAbility.class);
        when(recordService.modelClass()).thenReturn(UserAccount.class);
        UserAccount record = new UserAccount();
        record.setId("user-1");
        record.setUsername("alice");
        record.setPasswordStatus(PasswordStatus.NORMAL);

        WebPageResponse<?> projected = service.projectDefaultList(
                "iam.user",
                WebPageResponse.fromList(List.of(record)),
                recordService
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) projected.records().getFirst();
        assertThat(output)
                .containsEntry("passwordStatus", PasswordStatus.NORMAL)
                .containsEntry("passwordStatusTitle", "正常");
    }

    @Test
    void shouldExecuteRelationProjectionSqlWithNestedCriteriaFieldsAndResponseFieldBoundary() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        StaticRecordReadProjectionService service = new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of(userRelationDefinition())),
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider()
        );
        Criteria criteria = Criteria.of().eq("tenantId", "tenant_a")
                .andGroup(group -> group
                        .eq("passwordStatus", "ACTIVE")
                        .andGroup(nested -> nested.eq("createdAt", java.time.Instant.EPOCH)));
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of(
                        "id", "user-1",
                        "tenantId", "tenant_a",
                        "version", 1,
                        "deletedAt", java.time.Instant.EPOCH,
                        "username", "alice",
                        "employeeNo", "E001",
                        "employeeTitle", "Alice"
                )));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        WebPageResponse<?> response = service.queryDefaultList(
                "iam.user",
                criteria,
                PageRequest.of(1, 20),
                null,
                Sort.desc("lastLoginAt")
        ).orElseThrow();

        assertThat(response.total()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.records().getFirst();
        assertThat(output).containsEntry("employeeNo", "E001");
        assertThat(output).containsEntry("version", 1);
        assertThat(output).containsEntry("deletedAt", java.time.Instant.EPOCH);
        assertThat(output).doesNotContainKeys("passwordStatus", "createdAt", "lastLoginAt", "deleted");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.captor();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(jdbcOperations).queryForList(sqlCaptor.capture(), paramsCaptor.capture());
        String dataSql = sqlCaptor.getValue();
        assertThat(dataSql).startsWith("select ");
        assertThat(dataSql).contains("\"id\"", "\"username\"",
                "\"employeeNo\"", "\"employeeTitle\"", " from (");
        assertThat(dataSql.substring(0, dataSql.indexOf(" from (")))
                .contains("\"version\"", "\"deletedAt\"")
                .doesNotContain("\"tenantId\"", "\"deleted\"");
        assertThat(dataSql).contains("\"main\".\"password_status\" as \"passwordStatus\"");
        assertThat(dataSql).contains("\"main\".\"created_at\" as \"createdAt\"");
        assertThat(dataSql).contains("\"main\".\"last_login_at\" as \"lastLoginAt\"");
        assertThat(dataSql).contains("left join \"public\".\"iam_employee_account\" \"bound_employee_account\"");
        assertThat(dataSql).contains("left join \"public\".\"iam_employee\" \"bound_employee\"");
        assertThat(dataSql).contains("order by \"lastLoginAt\" desc");
        assertThat(paramsCaptor.getValue()).containsKeys("__limit", "__offset",
                "__join_bound_employee_bound_employee_account_0",
                "__join_bound_employee_bound_employee_0");
    }

    @Test
    void shouldExecuteExplicitRelationProjectionSqlWithoutDefaultListFields() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        StaticRecordReadProjectionService service = new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of(
                        userReferenceProjectionDefinition(),
                        employeeAccountReferenceDefinition(),
                        employeeReferenceDefinition()
                )),
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider()
        );
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of(
                        "id", "user-1",
                        "username", "alice",
                        "employeeTitle", "Alice"
                )));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        WebPageResponse<?> response = service.queryExplicitList(
                "iam.user",
                "user_selector",
                List.of("id", "username", "employeeTitle"),
                Criteria.of(),
                PageRequest.of(1, 20),
                null
        ).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.records().getFirst();
        assertThat(output)
                .containsEntry("id", "user-1")
                .containsEntry("username", "alice")
                .containsEntry("employeeTitle", "Alice")
                .doesNotContainKey("employeeNo");
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.captor();
        org.mockito.Mockito.verify(jdbcOperations).queryForList(sqlCaptor.capture(), any(Map.class));
        String dataSql = sqlCaptor.getValue();
        assertThat(dataSql.substring(0, dataSql.indexOf(" from (")))
                .contains("\"id\"", "\"username\"", "\"employeeTitle\"")
                .doesNotContain("\"employeeNo\"");
    }

    @Test
    void shouldMaskRelationProjectionSqlResponseFields() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        StaticRecordReadProjectionService service = new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of(
                        userReferenceProjectionDefinition(),
                        employeeAccountReferenceDefinition(),
                        protectedEmployeeReferenceDefinition()
                )),
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider()
        );
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(Map.of(
                        "id", "user-1",
                        "username", "alice",
                        "employeeTitle", "Sensitive"
                )));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        WebPageResponse<?> response = service.queryExplicitList(
                "iam.user",
                "user_selector",
                List.of("id", "username", "employeeTitle"),
                Criteria.of(),
                PageRequest.of(1, 20),
                null
        ).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.records().getFirst();
        assertThat(output).containsEntry("employeeTitle", "S*******e");
    }

    @Test
    void shouldPopulateOptionTitlesForRelationProjectionSqlResponse() {
        NamedParameterJdbcOperations jdbcOperations = mock(NamedParameterJdbcOperations.class);
        StaticRecordReadProjectionService service = new StaticRecordReadProjectionService(
                new StaticModuleDefinitionCatalog(List.of(userRelationDefinitionWithPasswordStatusColumn())),
                new RelationProjectionQueryExecutor(jdbcOperations),
                new RelationProjectionDatabaseTypeProvider(),
                new OptionSourceRegistry(List.of(new CodeTitleEnumOptionSourceProvider()))
        );
        @SuppressWarnings("rawtypes")
        CrudAbility recordService = mock(CrudAbility.class);
        when(recordService.modelClass()).thenReturn(UserAccount.class);
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", "user-1");
        row.put("username", "alice");
        row.put("passwordStatus", "NORMAL");
        row.put("passwordStatusTitle", null);
        row.put("employeeNo", "E001");
        row.put("employeeTitle", "Alice");
        when(jdbcOperations.queryForList(any(String.class), any(Map.class)))
                .thenReturn(List.of(row));
        when(jdbcOperations.queryForObject(any(String.class), any(Map.class), eq(Long.class)))
                .thenReturn(1L);

        WebPageResponse<?> response = service.queryDefaultList(
                "iam.user",
                Criteria.of(),
                PageRequest.of(1, 20),
                recordService
        ).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) response.records().getFirst();
        assertThat(output)
                .containsEntry("passwordStatus", "NORMAL")
                .containsEntry("passwordStatusTitle", "正常")
                .containsEntry("employeeNo", "E001")
                .containsEntry("employeeTitle", "Alice");
    }

    private static StaticModuleDefinition staticDefinition() {
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
                                FieldDefinition.string("employeeNo", "职员编号"),
                                FieldDefinition.string("title", "职员姓名"),
                                FieldDefinition.string("mobile", "手机号")
                        )
                )))
                       .uiDefinition(TestModulePages.listDetail("iam.employee", list -> list
                                .field("employeeNo")
                                .field("title"))
                        )
                       .build();
    }

    private static StaticModuleDefinition userRelationDefinition() {
        return userRelationDefinition(false);
    }

    private static StaticModuleDefinition userRelationDefinitionWithPasswordStatusColumn() {
        return userRelationDefinition(true);
    }

    private static StaticModuleDefinition userRelationDefinition(boolean includePasswordStatusColumn) {
        ModuleUiDefinition uiDefinition = TestModulePages.listDetail("iam.user", list -> {
                    list.field("username");
                    if (includePasswordStatusColumn) {
                        list.field("passwordStatus");
                    }
                    list.field("bound_employee", "employeeNo", field -> field.label("职员工号"));
                    list.field("bound_employee", "employeeTitle", field -> field.label("职员姓名"));
                });
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
                       .uiDefinition(uiDefinition)
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

    private static StaticModuleDefinition userReferenceProjectionDefinition() {
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
                       .readProjections(List.of(new StaticModuleReadProjectionDefinition(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .select(Employee::getTitle),
                        "employeeTitle"
                )))
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

    private static StaticModuleDefinition protectedEmployeeReferenceDefinition() {
        return employeeReferenceDefinition(masked());
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
                        List.of(FieldDefinition.string("title", "职员姓名").column("title")
                                .protection(protection))
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

    public static final class ProjectionEmployee {
        private String id;
        private String employeeNo;
        private String title;
        private String mobile;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getEmployeeNo() {
            return employeeNo;
        }

        public void setEmployeeNo(String employeeNo) {
            this.employeeNo = employeeNo;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getMobile() {
            return mobile;
        }

        public void setMobile(String mobile) {
            this.mobile = mobile;
        }
    }

}
