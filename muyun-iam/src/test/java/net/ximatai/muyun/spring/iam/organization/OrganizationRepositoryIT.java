package net.ximatai.muyun.spring.iam.organization;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.EntityMetaResolver;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.SimpleEntityManager;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.schema.PlatformEntityManagers;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = OrganizationRepositoryIT.TestApplication.class)
class OrganizationRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    private final OrganizationService organizationService;
    private final OrganizationDao organizationDao;
    private final DataSource dataSource;

    @Autowired
    OrganizationRepositoryIT(OrganizationService organizationService,
                             OrganizationDao organizationDao,
                             DataSource dataSource) {
        this.organizationService = organizationService;
        this.organizationDao = organizationDao;
        this.dataSource = dataSource;
    }

    @Test
    void springRepositoryShouldEnsureTableAndRunOrganizationSmokePath() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).contains("PostgreSQL");
            assertThat(organizationColumns(connection))
                    .contains("id", "tenant_id", "parent_id", "code", "title", "sort_order", "enabled", "deleted", "version");
            assertThat(organizationUniqueIndexColumns(connection)).contains(List.of("tenant_id", "code"));
        }
        assertThat(organizationDao.ensureTable()).isFalse();

        Organization root = new Organization();
        root.setCode("HQ");
        root.setTitle("Headquarters");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_org_it")) {
            String rootId = organizationService.insert(root);

            Organization branch = new Organization();
            branch.setCode("BR-001");
            branch.setTitle("Branch 001");
            branch.setParentId(rootId);
            organizationService.insert(branch);

            assertThat(organizationService.select(rootId))
                    .extracting(Organization::getTitle, Organization::getParentId, Organization::getEnabled)
                    .containsExactly("Headquarters", TreeAbility.ROOT_ID, Boolean.TRUE);
            assertThat(organizationService.children(rootId))
                    .extracting(Organization::getCode)
                    .containsExactly("BR-001");
            assertThat(organizationService.pageQuery(Criteria.of().eq("parentId", TreeAbility.ROOT_ID), PageRequest.of(1, 10)).getRecords())
                    .extracting(Organization::getCode)
                    .containsExactly("HQ");

            assertThat(organizationService.delete(rootId)).isEqualTo(1);
            assertThat(organizationService.select(rootId)).isNull();
            assertThat(organizationService.count(Criteria.of())).isEqualTo(1);
        }
    }

    @Test
    void directSoftDeleteServiceMustEnforceRecordScopeAndHonorCrossTenantGrants() {
        Organization original = new Organization();
        original.setCode("CROSS-TENANT");
        original.setTitle("Original");
        try (var ignored = TenantContext.use("tenant-cross-target")) {
            organizationService.insert(original);
        }
        AtomicBoolean granted = new AtomicBoolean();
        var permissions = new AllowAllDataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                                                            Criteria criteria, Optional<CurrentUser> user) {
                return DataScopeCriteriaResult.crossTenantRestricted(criteria.eq("id",
                        granted.get() ? original.getId() : "not-visible"));
            }
        };
        OrganizationService service = new OrganizationService(organizationDao, tenantId -> {}) {
            @Override public DataScopeCriteriaService getDataScopeCriteriaService() { return permissions; }
        };
        try (var ignored = TenantContext.use("tenant-cross-actor")) {
            Organization update = new Organization();
            update.setId(original.getId());
            update.setCode(original.getCode());
            update.setTitle("Updated");
            update.setVersion(original.getVersion());
            assertThatThrownBy(() -> service.update(update)).isInstanceOf(PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> service.delete(original.getId())).isInstanceOf(PlatformAccessDeniedException.class);
            granted.set(true);
            assertThat(service.update(update)).isEqualTo(1);
            assertThat(service.delete(update)).isEqualTo(1);
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
        try (var ignored = TenantContext.use("tenant-cross-target")) {
            Organization retained = service.selectIgnoreSoftDelete(original.getId());
            assertThat(retained.getTenantId()).isEqualTo("tenant-cross-target");
            assertThat(retained.getTitle()).isEqualTo("Updated");
            assertThat(retained.getDeleted()).isTrue();
            assertThat(retained.getVersion()).isEqualTo(original.getVersion() + 2);
        }
    }

    private List<String> organizationColumns(Connection connection) throws Exception {
        try (var columns = connection.getMetaData().getColumns(null, "public", "iam_organization", null)) {
            ArrayList<String> names = new ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString("COLUMN_NAME"));
            }
            return names;
        }
    }

    private List<List<String>> organizationUniqueIndexColumns(Connection connection) throws Exception {
        try (var indexes = connection.getMetaData().getIndexInfo(null, "public", "iam_organization", true, false)) {
            Map<String, List<String>> columnsByIndex = new LinkedHashMap<>();
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME");
                String column = indexes.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    columnsByIndex.computeIfAbsent(name, ignored -> new ArrayList<>()).add(column);
                }
            }
            return new ArrayList<>(columnsByIndex.values());
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableTransactionManagement
    @EnableMuYunRepositories(basePackageClasses = OrganizationDao.class)
    static class TestApplication {

        @Bean
        DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName())
                    .build();
        }

        @Bean
        OrganizationService organizationService(OrganizationDao organizationDao) {
            return new OrganizationService(organizationDao, tenantId -> {
            }) {
                @Override
                public DataScopeCriteriaService getDataScopeCriteriaService() {
                    return new AllowAllDataScopeCriteriaService();
                }
            };
        }

        @Bean
        EntityMetaResolver entityMetaResolver() {
            return PlatformEntityManagers.entityMetaResolver();
        }

        @Bean
        SimpleEntityManager simpleEntityManager(IDatabaseOperations<?> operations, EntityMetaResolver entityMetaResolver) {
            return PlatformEntityManagers.simpleEntityManager(operations, entityMetaResolver);
        }
    }
}
