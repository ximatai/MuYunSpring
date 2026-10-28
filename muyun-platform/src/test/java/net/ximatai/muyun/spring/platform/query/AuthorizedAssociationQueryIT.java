package net.ximatai.muyun.spring.platform.query;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.schema.*;
import net.ximatai.muyun.spring.common.security.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import net.ximatai.muyun.spring.iam.role.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.*;

import javax.sql.DataSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Consumer-style static/dynamic services execute real filtered pagination, never an in-memory join. */
@SpringBootTest(classes = AuthorizedAssociationQueryIT.Host.class)
class AuthorizedAssociationQueryIT extends PlatformPostgresIntegrationTest {
    @Autowired IDatabaseOperations<?> db;
    @Autowired Rows rows;
    private DynamicRecordRuntime runtime;
    private DynamicRecordService records;
    private AuthorizedAssociationQuery queries;
    private ActionExecutionPolicyService actions;
    private DataScopeCriteriaService scopes;
    private final Set<String> crossTenant = new HashSet<>();
    private final Set<String> denied = new HashSet<>();
    private final Set<String> emptyActions = new HashSet<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @BeforeEach void prepare() {
        var entity = new EntityDefinition("row", "association_dynamic", "Rows", List.of(
                FieldDefinition.string("title", "Title"), new FieldDefinition("targetId", "target_id", FieldType.STRING, "Target")))
                .withCapabilities(EntityCapability.DATA_SCOPE);
        var module = new ModuleDefinition("sample.dynamic", "Rows", List.of(entity));
        new DynamicSchemaService(db).ensureModule(module);
        db.execute("delete from association_static");
        db.execute("delete from association_dynamic");
        runtime = DynamicRecordRuntime.builder(db).build().register(module);
        actions = mock(ActionExecutionPolicyService.class, CALLS_REAL_METHODS);
        doAnswer(call -> {
            ActionExecutionContext context = call.getArgument(0);
            if (denied.contains(context.moduleAlias())) throw new PlatformAccessDeniedException("denied");
            return null;
        }).when(actions).requireAuthorized(any());
        scopes = mock(DataScopeCriteriaService.class);
        when(scopes.resolveReadScope(anyString(), any(ActionExecutionPolicy.class), any(), any(), any())).thenAnswer(call -> {
            String moduleAlias = call.getArgument(0);
            ActionExecutionPolicy action = call.getArgument(1);
            DataScopeFieldMapping mapping = call.getArgument(4);
            Criteria condition = Criteria.copyOf(call.getArgument(2));
            if (action.actionCode().equals("view")) condition.eq(mapping.ownerUserField(), CurrentUserContext.currentUser().orElseThrow().userId());
            if (action.actionCode().equals("export")) condition.eq("title", "missing");
            if (emptyActions.contains(action.actionCode())) condition.eq("id", "missing");
            return new DataScopeCriteriaResult(condition, true, crossTenant.contains(moduleAlias));
        });
        when(scopes.resolveReadScope(anyString(), any(ActionExecutionPolicy.class), any(), any())).thenAnswer(call ->
                scopes.resolveReadScope(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), DataScopeFieldMapping.STANDARD));
        queries = new AuthorizedAssociationQuery(db, PlatformEntityManagers.entityMetaResolver(),
                new PlatformDatabaseValueConverter(), actions, scopes);
        records = new DynamicRecordService(runtime, actions, scopes);
    }

    @AfterEach void close() { if (runtime != null) runtime.close(); }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void staticAndDynamicScopesRemainIndependentAcrossPagination(boolean dynamicSource, boolean dynamicTarget) {
        CrudAbility<?> source = service(dynamicSource);
        CrudAbility<?> target = service(dynamicTarget);
        seed(dynamicSource, dynamicTarget);
        try (var tenant = TenantContext.use("a"); var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "a"))) {
            var query = queries.from(source, PlatformAction.QUERY).where(Criteria.of().eq("title", "link"))
                    .whereRelated("targetId", queries.from(target, PlatformAction.VIEW));
            assertPage(query, 2);
            try (var bypass = TenantContext.bypassTenantFilter("outer domain operation")) { assertPage(query, 2); }
            // Empty mandatory scope cannot be relaxed by an unrestricted root action.
            assertPage(queries.from(source, PlatformAction.QUERY)
                    .whereRelated("targetId", queries.from(target, PlatformAction.VIEW)
                            .requiring(PlatformAction.EXPORT.executionPolicy())), 0);
            // Include retained source rows only when the domain deliberately requests it.
            assertPage(queries.from(source, PlatformAction.QUERY).where(Criteria.of().eq("title", "link"))
                    .whereRelated("targetId", queries.from(target, PlatformAction.VIEW), "id",
                            AuthorizedAssociationQuery.RelatedRecords.INCLUDING_DELETED), 4);
            // Reverse association is a semijoin: two links still produce one target row.
            assertPage(queries.from(target, PlatformAction.VIEW).whereRelated("id",
                    queries.from(source, PlatformAction.QUERY).where(Criteria.of().eq("title", "link")),
                    "targetId", AuthorizedAssociationQuery.RelatedRecords.ACTIVE), 1);
            denied.add(target.getModuleAlias());
            assertThatThrownBy(() -> query.page(PageRequest.of(1, 2))).isInstanceOf(PlatformAccessDeniedException.class);
            denied.clear();
            try (var otherUser = CurrentUserContext.use(CurrentUser.tenantUser("other", "Other", "a"))) {
                assertPage(query, 2); // Re-resolves ownership for a previously built plan.
                assertThat(query.page(PageRequest.of(1, 10)).getRecords()).allMatch(row -> row.getId().contains("other"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false,2", "true,false,4", "false,true,4", "true,true,8"})
    void crossTenantGrantOnOneSideDoesNotAuthorizeTheOther(boolean sourceCross, boolean targetCross, int expected) {
        seed(false, true);
        if (sourceCross) crossTenant.add("sample.static");
        if (targetCross) crossTenant.add("sample.dynamic");
        try (var tenant = TenantContext.use("a"); var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "a"))) {
            assertPage(queries.from(service(false), PlatformAction.QUERY).where(Criteria.of().eq("title", "link"))
                    .whereRelated("targetId", queries.from(service(true), PlatformAction.VIEW)), expected);
        }
    }

    @Test void rejectsMissingIdentityTenantAndUnknownFields() {
        var query = queries.from(service(false), PlatformAction.QUERY);
        assertThatThrownBy(() -> query.count()).isInstanceOf(PlatformAccessDeniedException.class);
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "a"))) {
            assertThatThrownBy(() -> query.count()).isInstanceOf(PlatformAccessDeniedException.class);
        }
        assertThatThrownBy(() -> query.whereRelated("missing", query)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query.whereRelated("id", query, "not_a_field", AuthorizedAssociationQuery.RelatedRecords.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void nestedConstraintsKeepUserOrConditionsGroupedAndSnapshotTheDeclaration() {
        seed(false, true);
        try (var tenant = TenantContext.use("a"); var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "a"))) {
            Criteria input = Criteria.of().eq("title", "link").orEq("id", "link-b-b-open0");
            var source = queries.from(service(false), PlatformAction.QUERY);
            var related = queries.from(service(true), PlatformAction.VIEW).whereRelated("id",
                    source.where(Criteria.of().eq("id", "link-a-a-open0")), "targetId", AuthorizedAssociationQuery.RelatedRecords.ACTIVE);
            var query = source.where(input).whereRelated("targetId", related);
            input.eq("id", "missing");
            assertPage(query, 2);
            assertThat(query.page(PageRequest.of(1, 20)).getRecords())
                    .extracting(row -> row.getId()).containsExactlyInAnyOrder("link-a-a-open0", "link-a-a-open1");
        }
    }

    @Test void rechecksRelatedProjectionAfterDynamicMetadataRefresh() {
        var query = queries.from(service(false), PlatformAction.QUERY).whereRelated("id",
                queries.from(service(true), PlatformAction.QUERY), "targetId", AuthorizedAssociationQuery.RelatedRecords.ACTIVE);
        var entity = new EntityDefinition("row", "association_dynamic", "Rows", List.of(
                FieldDefinition.string("title", "Title"), FieldDefinition.string("targetId", "Target").column("target_id")
                        .protection(new FieldProtectionDefinition(FieldEncryptionMode.ENCRYPTED,
                                FieldSignatureMode.NONE, FieldMaskingPolicy.NONE))))
                .withCapabilities(EntityCapability.DATA_SCOPE);
        runtime.refresh(new ModuleDefinition("sample.dynamic", "Rows", List.of(entity)));
        try (var tenant = TenantContext.use("a"); var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "a"))) {
            assertThatThrownBy(query::count).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("protected");
        }
    }

    @Test void declaredViewRangeDoesNotInheritTheUnrelatedQueryRange() {
        seed(false, false);
        seed(true, true);
        emptyActions.add("query");
        try (var tenant = TenantContext.use("a"); var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "a"))) {
            for (boolean dynamic : List.of(false, true)) {
                assertPage(queries.from(service(dynamic), PlatformAction.VIEW).where(Criteria.of().eq("title", "target")), 1);
                assertPage(queries.from(service(dynamic), PlatformAction.QUERY), 0);
            }
        }
    }

    @Test void realIamEmptyRangeRemainsAnEmptyPageWithUnrelatedProtectedFields() {
        var entity = new EntityDefinition("row", "association_dynamic", "Rows", List.of(
                FieldDefinition.string("title", "Title"), FieldDefinition.string("targetId", "Target").column("target_id")
                        .protection(new FieldProtectionDefinition(FieldEncryptionMode.ENCRYPTED,
                                FieldSignatureMode.NONE, FieldMaskingPolicy.NONE))))
                .withCapabilities(EntityCapability.DATA_SCOPE);
        runtime.refresh(new ModuleDefinition("sample.dynamic", "Rows", List.of(entity)));
        scopes = new RoleDataScopeCriteriaService(mock(RoleService.class), mock(TenantAdminImplicitGrantPolicy.class),
                Optional.empty(), Optional.empty(), Optional.empty());
        queries = new AuthorizedAssociationQuery(db, PlatformEntityManagers.entityMetaResolver(),
                new PlatformDatabaseValueConverter(), actions, scopes);
        records = new DynamicRecordService(runtime, actions, scopes);
        try (var tenant = TenantContext.use("a"); var user = CurrentUserContext.use(CurrentUser.tenantUser("reader", "Reader", "a"))) {
            assertPage(queries.from(service(true), PlatformAction.VIEW), 0);
            assertPage(queries.from(service(false), PlatformAction.QUERY)
                    .whereRelated("targetId", queries.from(service(true), PlatformAction.VIEW)), 0);
        }
    }

    private void assertPage(AuthorizedAssociationQuery.Query<?> query, int expected) {
        var ids = new ArrayList<String>();
        for (int page = 1; page <= expected + 1; page++) {
            var result = query.page(PageRequest.of(page, 1), Sort.asc("id"));
            assertThat(result.getTotal()).isEqualTo(expected);
            result.getRecords().forEach(record -> ids.add(record.getId()));
        }
        assertThat(ids).hasSize(expected).doesNotHaveDuplicates().isSorted();
        assertThat(query.count()).isEqualTo(expected);
    }

    private CrudAbility<?> service(boolean dynamic) {
        return dynamic ? records.entity("sample.dynamic", "row") : new StaticService(rows, scopes);
    }

    private void seed(boolean dynamicSource, boolean dynamicTarget) {
        String targetTable = dynamicTarget ? "association_dynamic" : "association_static";
        String ownerColumn = dynamicTarget ? "auth_user_id" : "owner_key";
        String sourceTable = dynamicSource ? "association_dynamic" : "association_static";
        for (String targetTenant : List.of("a", "b")) {
            for (String kind : List.of("open", "old", "other")) {
                String targetId = targetTenant + "-" + kind;
                db.execute("insert into " + targetTable + " (id,tenant_id,title,deleted," + ownerColumn + ") values (?,?,?,?,?)",
                        targetId, targetTenant, "target", kind.equals("old"), kind.equals("other") ? "other" : "reader");
                for (String sourceTenant : List.of("a", "b")) for (int n = 0; n < 2; n++) {
                    db.execute("insert into " + sourceTable + " (id,tenant_id,title,deleted,target_id) values (?,?,?,?,?)",
                            "link-" + sourceTenant + "-" + targetId + n, sourceTenant, "link", false, targetId);
                }
            }
        }
    }

    @Getter @Setter @Table(name = "association_static")
    public static class Row extends StandardTitledEntity {
        @Column(name = "owner_key", type = ColumnType.VARCHAR, length = 64) private String owner;
        @Column(name = "target_id", type = ColumnType.VARCHAR, length = 32) private String targetId;
    }
    @MuYunRepository public interface Rows extends BaseDao<Row, String> { }
    static class StaticService extends StandardBusinessService<Row> implements DataScopeAbility<Row>, SoftDeleteAbility<Row> {
        private final DataScopeCriteriaService scopes;
        StaticService(Rows rows, DataScopeCriteriaService scopes) { super("sample.static", Row.class, rows); this.scopes = scopes; }
        @Override public DataScopeCriteriaService getDataScopeCriteriaService() { return scopes; }
        @Override public DataScopeFieldMapping dataScopeFieldMapping() { return DataScopeFieldMapping.of("owner", null, null); }
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = AuthorizedAssociationQueryIT.class)
    static class Host {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl())
                .username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
    }
}
