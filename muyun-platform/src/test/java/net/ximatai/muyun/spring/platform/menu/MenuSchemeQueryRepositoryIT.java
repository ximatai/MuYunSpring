package net.ximatai.muyun.spring.platform.menu;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = MenuSchemeQueryRepositoryIT.TestApplication.class)
class MenuSchemeQueryRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired
    MenuSchemeService service;

    @Test
    void findsVisibleBusinessNamesThroughTheStandardQueryAndRetainsAliasSearch() {
        try (TenantContext.Scope ignored = TenantContext.system("menu scheme query contract")) {
            MenuScheme target = scheme("admin_query_contract", "平台超管");
            String id = service.insert(target);
            service.insert(scheme("other_query_contract", "其他菜单"));

            assertThat(search("平台超管")).extracting(MenuScheme::getId).containsExactly(id);
            assertThat(search("超管")).extracting(MenuScheme::getId).containsExactly(id);
            assertThat(search("admin_query_contract")).extracting(MenuScheme::getId).containsExactly(id);
            assertThat(search("不存在的菜单")).isEmpty();
        }
    }

    private List<MenuScheme> search(String keyword) {
        QueryRequest request = new QueryRequest(List.of(), null, Map.of(), List.of(), null, null,
                Map.of(), keyword, List.of(), false, null);
        return service.list(service.queryCriteria(request), new PageRequest(0, 20), service.querySorts(request));
    }

    private MenuScheme scheme(String alias, String title) {
        MenuScheme scheme = new MenuScheme();
        scheme.setAlias(alias);
        scheme.setTitle(title);
        scheme.setScopeType(MenuScopeType.SYSTEM);
        return scheme;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = MenuSchemeDao.class)
    static class TestApplication {
        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl())
                    .username(postgres.getUsername()).password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName()).build();
        }

        @Bean
        MenuSchemeService menuSchemeService(MenuSchemeDao dao) {
            return new MenuSchemeService(dao);
        }
    }
}
