package net.ximatai.muyun.spring.platform.ui;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = UiControlRulesRepositoryIT.TestApplication.class)
class UiControlRulesRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired PlatformUiControlRulesService service;
    @Autowired PlatformTransactionManager transactions;
    @Test void persistsScopeEffectsAndRejectsStaleReplacement() {
        String alias = "test.ui_" + UUID.randomUUID().toString().replace("-", "");
        var empty = service.snapshot(alias);
        var rule = new UiControlRule("ui1", "default", "PRESENT({title})", true,
                List.of(new UiControlRule.Target("title", true, true)));
        service.replace(alias, empty.baselineFingerprint(), List.of(rule));
        var saved = service.snapshot(alias);
        assertThat(saved.rules()).containsExactly(rule);
        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.use("business-tenant")) {
            assertThat(service.snapshot(alias)).isEqualTo(saved);
            assertThat(net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId()).contains("business-tenant");
        }
        assertThatThrownBy(() -> service.replace(alias, empty.baselineFingerprint(), List.of())).hasMessageContaining("已变更");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            service.replace(alias, saved.baselineFingerprint(), List.of());
            throw new IllegalStateException("rollback");
        })).hasMessage("rollback");
        assertThat(service.snapshot(alias)).isEqualTo(saved);
        service.replace(alias, saved.baselineFingerprint(), List.of());
        assertThat(service.snapshot(alias).rules()).isEmpty();
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = PlatformUiControlRulesDao.class)
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean PlatformUiControlRulesService rulesService(PlatformUiControlRulesDao dao) { return new PlatformUiControlRulesService(dao); }
    }
}
