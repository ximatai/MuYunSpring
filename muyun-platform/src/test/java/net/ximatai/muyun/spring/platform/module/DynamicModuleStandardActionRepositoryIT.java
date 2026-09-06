package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = DynamicModuleStandardActionRepositoryIT.TestApplication.class)
class DynamicModuleStandardActionRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired PlatformModuleService modules;
    @Autowired PlatformModuleActionService actions;
    @Autowired DynamicModuleStandardActionRegistrar registrar;

    @Test
    void shouldRollBackEarlierStandardActionsWhenLaterContributionConflicts() {
        PlatformModule module = new PlatformModule();
        module.setAlias("education.atomic_actions");
        module.setApplicationAlias("education");
        module.setTitle("原子动作登记");
        module.setModuleKind(ModuleKind.DYNAMIC);
        modules.insert(module);
        PlatformModuleAction existing = new PlatformModuleAction();
        existing.setModuleAlias(module.getAlias());
        existing.setActionCode("update");
        existing.setTitle("已有业务动作");
        actions.insert(existing);

        assertThatThrownBy(() -> registrar.register(module))
                .hasMessageContaining("contribution conflicts");

        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "menu")).isNull();
        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "create")).isNull();
        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "view")).isNull();
        assertThat(actions.findByModuleAliasAndActionCode(module.getAlias(), "update").getTitle())
                .isEqualTo("已有业务动作");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = PlatformModuleDao.class)
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean PlatformModuleService modules(PlatformModuleDao dao) { return new PlatformModuleService(dao); }
        @Bean PlatformModuleActionService actions(PlatformModuleActionDao dao, PlatformModuleService modules) {
            return new PlatformModuleActionService(dao, modules);
        }
        @Bean ModuleActionContributionRegistrar contributions(PlatformModuleActionService actions) {
            return new ModuleActionContributionRegistrar(actions);
        }
        @Bean DynamicModuleStandardActionRegistrar registrar(PlatformModuleService modules,
                                                            ModuleActionContributionRegistrar contributions) {
            return new DynamicModuleStandardActionRegistrar(modules, contributions);
        }
    }
}
