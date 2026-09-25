package net.ximatai.muyun.spring.iam.organization;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.IMetaDataLoader;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.TableWrapper;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.database.core.metadata.DBColumn;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.metadata.DBIndex;
import net.ximatai.muyun.database.core.metadata.DBSchema;
import net.ximatai.muyun.database.core.metadata.DBTable;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.EntityMetaResolver;
import net.ximatai.muyun.database.core.orm.MigrationOptions;
import net.ximatai.muyun.database.core.orm.MigrationResult;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.MuYunRepositoryFactory;
import net.ximatai.muyun.database.spring.boot.sql.annotation.MuYunRepository;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.schema.PlatformEntityManagers;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.schema.StaticEntityTableMapper;
import net.ximatai.muyun.spring.common.schema.StaticSchemaService;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.tenant.OrganizationCreationProvisioner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrganizationRepositoryContractTest {
    @org.junit.jupiter.api.BeforeEach
    void installDataScope() {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.configureDataScopeCriteriaService(
                net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService::new);
    }

    @org.junit.jupiter.api.AfterEach
    void resetDataScope() {
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.resetDataScopeCriteriaService();
    }

    private static final String SCHEMA = "public";
    private static final String TABLE = "iam_organization";
    private static final String DEMO_TABLE = "demo_static_repository_entity";

    @Test
    void abilityServiceShouldUseMuYunRepositoryProxyForInsert() {
        IDatabaseOperations<Object> operations = mockedOperations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));

        OrganizationService service = organizationService(operations);
        Organization organization = new Organization();
        organization.setCode("HQ");
        organization.setTitle("Headquarters");

        String id;
        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            id = service.insert(organization);
        }

        assertThat(id).hasSize(32);
        assertThat(organization.getParentId()).isEqualTo(TreeAbility.ROOT_ID);
        assertThat(organization.getEnabled()).isTrue();
        assertThat(organization.getTenantId()).isEqualTo("tenant_a");

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("id", id)
                .containsEntry("code", "HQ")
                .containsEntry("tenant_id", "tenant_a")
                .containsEntry("title", "Headquarters")
                .containsEntry("parent_id", TreeAbility.ROOT_ID)
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("deleted", Boolean.FALSE)
                .containsEntry("version", 0);
    }

    @Test
    void repositoryShouldResolveStaticEntityForEnsureTable() {
        IDatabaseOperations<Object> operations = mockedOperationsWithExistingOrganizationTable();

        assertThat(repository(operations).ensureTable()).isFalse();

        verify(operations).execute(contains("comment on table \"public\".\"iam_organization\""));
        verify(operations).execute(contains("drop index"));
        verify(operations).execute(contains("\"tenant_id\",\"code\""));
        verify(operations, never()).insertItem(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void staticSchemaServiceShouldDryRunOrganizationTableWithoutExecutingDdl() {
        IDatabaseOperations<Object> operations = mockedOperations();

        MigrationResult result = new StaticSchemaService(operations)
                .ensureTable(Organization.class, MigrationOptions.dryRun());

        assertThat(result.isChanged()).isTrue();
        assertThat(result.isDryRun()).isTrue();
        assertThat(result.getStatements()).anySatisfy(sql -> assertThat(sql).contains("iam_organization"));
        verify(operations, never()).execute(anyString());
    }

    @Test
    void repositoryEnsureTableShouldUsePlatformStaticMapperForColumnUnique() {
        IDatabaseOperations<Object> operations = mockedOperationsWithExistingDemoStaticTable();

        repository(operations, DemoStaticRepositoryDao.class).ensureTable();

        verify(operations).execute(contains("drop index"));
        verify(operations).execute(contains("\"tenant_id\",\"code\""));
        verify(operations, never()).execute(contains("unique (\"code\")"));
    }

    @Test
    void staticModelMapperShouldCompileOrganizationAsPlatformTable() {
        TableWrapper table = new StaticEntityTableMapper().toTable(Organization.class);

        assertThat(table.getName()).isEqualTo(TABLE);
        assertThat(table.getPrimaryKey().getName()).isEqualTo("id");
        assertThat(table.getPrimaryKey().getLength()).isEqualTo(32);
        assertThat(columnNames(table)).containsAll(StandardEntitySchema.columnNames());
        assertThat(columnNames(table))
                .contains("parent_id", "code", "title", "sort_order", "enabled");
        assertThat(table.getColumns().stream().filter(column -> "code".equals(column.getName())).findFirst())
                .get()
                .satisfies(column -> {
                    assertThat(column.getLength()).isEqualTo(64);
                    assertThat(column.isNullable()).isFalse();
                });
        assertThat(table.getIndexes())
                .anySatisfy(index -> {
                    assertThat(index.isUnique()).isTrue();
                    assertThat(index.getColumns()).containsExactly("tenant_id", "code");
                });
    }

    @Test
    void abilityQueriesShouldCompileThroughMuYunRepositoryProxy() {
        IDatabaseOperations<Object> operations = mockedOperations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("org-1", "Headquarters", 10)));

        OrganizationService service = organizationService(operations);

        assertThat(service.list(Criteria.of().eq("parentId", TreeAbility.ROOT_ID), PageRequest.of(1, 10)))
                .extracting(Organization::getTitle)
                .containsExactly("Headquarters");
        assertThat(service.pageQuery(Criteria.of().eq("parentId", TreeAbility.ROOT_ID), PageRequest.of(1, 10)).getRecords())
                .extracting(Organization::getTitle)
                .containsExactly("Headquarters");
        assertThat(service.children(TreeAbility.ROOT_ID))
                .extracting(Organization::getId)
                .containsExactly("org-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, times(3)).query(sql.capture(), anyMap());

        assertThat(sql.getAllValues().get(0))
                .contains("\"parent_id\" =")
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL")
                .contains("OR");
        assertThat(sql.getAllValues().get(1))
                .contains("\"parent_id\" =")
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL")
                .contains("OR");
        assertThat(sql.getAllValues().get(2))
                .contains("ORDER BY \"sort_order\" ASC");
    }

    @Test
    void abilityServiceShouldUseVersionConditionForStaticWrites() {
        IDatabaseOperations<Object> operations = mockedOperations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("org-1", "Headquarters", 10, 2)));
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), anyMap(), anyMap(), eq("id"))).thenReturn(1);
        OrganizationService service = organizationService(operations);
        Organization update = new Organization();
        update.setId("org-1");
        update.setCode("HQ");
        update.setTitle("Headquarters Updated");
        update.setVersion(2);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.update(update)).isEqualTo(1);
            assertThat(service.delete("org-1")).isEqualTo(1);
        }

        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations, times(2)).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), anyMap(), where.capture(), eq("id"));
        assertThat(where.getAllValues())
                .allSatisfy(conditions -> assertThat(conditions).containsEntry("version", 2));
    }

    private OrganizationDao repository(IDatabaseOperations<Object> operations) {
        return repository(operations, OrganizationDao.class);
    }

    private OrganizationService organizationService(IDatabaseOperations<Object> operations) {
        return new OrganizationService(
                repository(operations),
                acceptActiveTenant(),
                new StaticListableBeanFactory().getBeanProvider(OrganizationCreationProvisioner.class));
    }

    private ActiveTenantVerifier acceptActiveTenant() {
        return tenantId -> {
        };
    }

    private <T> T repository(IDatabaseOperations<Object> operations, Class<T> daoType) {
        EntityMetaResolver entityMetaResolver = PlatformEntityManagers.entityMetaResolver();
        return new MuYunRepositoryFactory(
                operations,
                new MockEnvironment(),
                null,
                PlatformEntityManagers.simpleEntityManager(operations, entityMetaResolver)
        ).create(daoType);
    }

    private Set<String> columnNames(TableWrapper table) {
        Set<String> names = new LinkedHashSet<>();
        if (table.getPrimaryKey() != null) {
            names.add(table.getPrimaryKey().getName());
        }
        table.getColumns().forEach(column -> names.add(column.getName()));
        return names;
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> mockedOperations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        return operations;
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> mockedOperationsWithExistingOrganizationTable() {
        IMetaDataLoader loader = mock(IMetaDataLoader.class);
        DBInfo dbInfo = new DBInfo("POSTGRESQL").setName("muyun_test");
        DBSchema schema = new DBSchema(SCHEMA);
        schema.addTable(new DBTable(loader).setName(TABLE).setSchema(SCHEMA));
        dbInfo.addSchema(schema);

        when(loader.getDBInfo()).thenReturn(dbInfo);
        when(loader.getColumnMap(SCHEMA, TABLE)).thenReturn(organizationColumns());
        when(loader.getIndexList(SCHEMA, TABLE)).thenReturn(List.of(index("iam_organization_code_uindex", true, "code")));

        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getMetaDataLoader()).thenReturn(loader);
        when(operations.getDBInfo()).thenReturn(dbInfo);
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        return operations;
    }

    private Map<String, Object> row(String id, String title, int sortOrder) {
        return row(id, title, sortOrder, 0);
    }

    private Map<String, Object> row(String id, String title, int sortOrder, int version) {
        return Map.of(
                "id", id,
                "code", id.toUpperCase(),
                "title", title,
                "parent_id", TreeAbility.ROOT_ID,
                "sort_order", sortOrder,
                "enabled", Boolean.TRUE,
                "deleted", Boolean.FALSE,
                "version", version
        );
    }

    private Map<String, DBColumn> organizationColumns() {
        Map<String, DBColumn> columns = new LinkedHashMap<>();
        columns.put("id", column("id", "VARCHAR", 32, false, true, "ID"));
        columns.put("tenant_id", column("tenant_id", "VARCHAR", 64, true, false, "Tenant id"));
        columns.put("version", column("version", "INT", null, true, false, "Optimistic lock version"));
        columns.put("deleted", column("deleted", "BOOLEAN", null, true, false, "Soft delete flag"));
        columns.put("deleted_at", column("deleted_at", "TIMESTAMP", null, true, false, "Deleted at"));
        columns.put("deleted_by", column("deleted_by", "VARCHAR", 64, true, false, "Deleted by"));
        columns.put("created_by", column("created_by", "VARCHAR", 64, true, false, "Created by"));
        columns.put("created_at", column("created_at", "TIMESTAMP", null, true, false, "Created at"));
        columns.put("updated_by", column("updated_by", "VARCHAR", 64, true, false, "Updated by"));
        columns.put("updated_at", column("updated_at", "TIMESTAMP", null, true, false, "Updated at"));
        columns.put("parent_id", column("parent_id", "VARCHAR", 32, true, false, "Parent organization ID"));
        columns.put("code", column("code", "VARCHAR", 64, false, false, "Organization code"));
        columns.put("title", column("title", "VARCHAR", 128, false, false, "Organization title"));
        columns.put("sort_order", column("sort_order", "INT", null, true, false, "Sort order"));
        columns.put("enabled", column("enabled", "BOOLEAN", null, true, false, "Enabled flag"));
        return columns;
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> mockedOperationsWithExistingDemoStaticTable() {
        IMetaDataLoader loader = mock(IMetaDataLoader.class);
        DBInfo dbInfo = new DBInfo("POSTGRESQL").setName("muyun_test");
        DBSchema schema = new DBSchema(SCHEMA);
        schema.addTable(new DBTable(loader).setName(DEMO_TABLE).setSchema(SCHEMA));
        dbInfo.addSchema(schema);

        when(loader.getDBInfo()).thenReturn(dbInfo);
        when(loader.getColumnMap(SCHEMA, DEMO_TABLE)).thenReturn(demoStaticColumns());
        when(loader.getIndexList(SCHEMA, DEMO_TABLE)).thenReturn(List.of(index("demo_static_code_uindex", true, "code")));

        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getMetaDataLoader()).thenReturn(loader);
        when(operations.getDBInfo()).thenReturn(dbInfo);
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        return operations;
    }

    private Map<String, DBColumn> demoStaticColumns() {
        Map<String, DBColumn> columns = new LinkedHashMap<>();
        columns.put("id", column("id", "VARCHAR", 32, false, true, "ID"));
        columns.put("tenant_id", column("tenant_id", "VARCHAR", 64, true, false, "Tenant id"));
        columns.put("version", column("version", "INT", null, true, false, "Optimistic lock version"));
        columns.put("deleted", column("deleted", "BOOLEAN", null, true, false, "Soft delete flag"));
        columns.put("deleted_at", column("deleted_at", "TIMESTAMP", null, true, false, "Deleted at"));
        columns.put("deleted_by", column("deleted_by", "VARCHAR", 64, true, false, "Deleted by"));
        columns.put("created_by", column("created_by", "VARCHAR", 64, true, false, "Created by"));
        columns.put("created_at", column("created_at", "TIMESTAMP", null, true, false, "Created at"));
        columns.put("updated_by", column("updated_by", "VARCHAR", 64, true, false, "Updated by"));
        columns.put("updated_at", column("updated_at", "TIMESTAMP", null, true, false, "Updated at"));
        columns.put("code", column("code", "VARCHAR", 64, false, false, "Code"));
        return columns;
    }

    private DBColumn column(String name, String type, Integer length, boolean nullable, boolean primaryKey, String description) {
        DBColumn column = new DBColumn();
        column.setName(name);
        column.setType(type);
        if (length != null) {
            column.setLength(length);
        }
        column.setNullable(nullable);
        column.setPrimaryKey(primaryKey);
        column.setDescription(description);
        return column;
    }

    private DBIndex index(String name, boolean unique, String... columns) {
        DBIndex index = new DBIndex().setName(name).setUnique(unique);
        for (String column : columns) {
            index.addColumn(column);
        }
        return index;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @Table(name = DEMO_TABLE, comment = "Demo static repository entity")
    private static class DemoStaticRepositoryEntity extends StandardEntity {
        @Column(name = "code", type = ColumnType.VARCHAR, length = 64, nullable = false, unique = true)
        private String code;
    }

    @MuYunRepository
    private interface DemoStaticRepositoryDao extends BaseDao<DemoStaticRepositoryEntity, String> {
    }
}
