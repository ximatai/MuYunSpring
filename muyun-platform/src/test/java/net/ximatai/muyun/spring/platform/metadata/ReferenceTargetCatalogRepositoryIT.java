package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationDao;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleDao;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = ReferenceTargetCatalogRepositoryIT.TestApplication.class)
class ReferenceTargetCatalogRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired ApplicationService applications;
    @Autowired PlatformModuleService modules;

    @Test void discoveryReadsSavedApplicationNameAndModuleOwnershipWithoutChangingTenantScope() {
        try (var ignored = TenantContext.system("catalog application ownership contract")) {
            Application application = new Application();
            application.setAlias("catalog_shop"); application.setTitle("青禾文具");
            applications.insert(application);
            PlatformModule module = new PlatformModule();
            module.setAlias("catalog_shop.customer"); module.setApplicationAlias(application.getAlias());
            module.setTitle("客户"); module.setModuleKind(ModuleKind.DYNAMIC);
            modules.insert(module);
            var catalog = new ReferenceTargetFieldCatalogService(mock(ModuleMetadataRelationService.class),
                    modules, applications, mock(MetadataFieldService.class), null);
            assertThat(catalog.discoverModules()).filteredOn(candidate -> candidate.alias().equals(module.getAlias()))
                    .singleElement().satisfies(candidate -> {
                        assertThat(candidate.applicationAlias()).isEqualTo("catalog_shop");
                        assertThat(candidate.applicationTitle()).isEqualTo("青禾文具");
                        assertThat(candidate.referenceReady()).isFalse();
                    });
            assertThat(TenantContext.isSystem()).isTrue();
        }
        try (var ignored = TenantContext.use("another_tenant")) {
            var catalog = new ReferenceTargetFieldCatalogService(mock(ModuleMetadataRelationService.class),
                    modules, applications, mock(MetadataFieldService.class), null);
            assertThat(catalog.discoverModules()).noneMatch(candidate -> candidate.alias().equals("catalog_shop.customer"));
            assertThat(TenantContext.currentTenantId()).contains("another_tenant");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = { ApplicationDao.class, PlatformModuleDao.class })
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean ApplicationService applications(ApplicationDao dao) { return new ApplicationService(dao); }
        @Bean PlatformModuleService modules(PlatformModuleDao dao) { return new PlatformModuleService(dao, event -> {}); }
    }
}
