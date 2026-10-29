package net.ximatai.muyun.spring.platform.code;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = CodeRuleSaveRepositoryIT.Application.class)
class CodeRuleSaveRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) { registry.add("muyun.database.repository-schema-mode", () -> "ENSURE"); }
    @Autowired CodeRuleSaveService saves;
    @Autowired CodeRuleService rules;
    @Autowired CodeConfigurationService configuration;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        reset(rules, configuration);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table if not exists code_save_business_test(id varchar(64) primary key)");
        when(configuration.targets("demo.order")).thenReturn(List.of(new CodeConfigurationService.Target("demo.order", "order", "Order", "STATIC", null,
                List.of(new CodeConfigurationService.Field("documentNo", "Number", 64, null, null, null, null, null)), List.of(), "Order", null)));
        when(rules.saveRuleTree(any())).thenAnswer(call -> {
            CodeRule rule = call.getArgument(0); rule.setId(UUID.randomUUID().toString()); rule.setVersion(1);
            jdbc.update("insert into code_save_business_test values (?)", rule.getId()); return rule;
        });
        when(rules.viewRuleTree(anyString())).thenAnswer(call -> { var rule = command(); rule.setId(call.getArgument(0)); rule.setVersion(1); return rule; });
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
            public void lock(String scope, String key) { jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, scope + key); }
        });
    }
    @AfterEach void cleanup() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }
    private CodeRule command() { var rule = new CodeRule(); rule.setModuleAlias("demo.order"); rule.setEntityAlias("order"); rule.setFieldName("documentNo"); rule.setTitle("Number"); return rule; }
    private <T> T as(String user, String tenant, Supplier<T> work) {
        try (var identity = CurrentUserContext.use(CurrentUser.tenantUser(user,user,tenant)); var scope = TenantContext.use(tenant)) { return work.get(); }
    }
    @Test void commitsOncePreservesCommandAndReauthorizesLookups() {
        String request = UUID.randomUUID().toString();
        as("user","tenant", () -> {
            var command = command(); var saved = saves.save(request, command);
            assertThat(command.getId()).isNull();
            assertThat(saves.save(request, command).getId()).isEqualTo(saved.getId());
            verify(rules, times(1)).saveRuleTree(any());
            assertThat(saves.lookup(request,"demo.order","order","documentNo").committed()).isTrue();
            command.setTitle("Changed"); assertThatThrownBy(() -> saves.save(request,command)).isInstanceOf(IllegalArgumentException.class);
            when(rules.viewRuleTree(saved.getId())).thenThrow(new SecurityException("revoked"));
            assertThatThrownBy(() -> saves.lookup(request,"demo.order","order","documentNo")).isInstanceOf(SecurityException.class);
            return null;
        });
        assertThat(as("other","tenant", () -> saves.lookup(request,"demo.order","order","documentNo").committed())).isFalse();
        assertThat(as("user","other", () -> saves.lookup(request,"demo.order","order","documentNo").committed())).isFalse();
    }
    @Test void rollsBackReceiptAndBusinessTogether() {
        String request = UUID.randomUUID().toString();
        as("user","tenant", () -> {
            long before = jdbc.queryForObject("select count(*) from code_save_business_test", Long.class);
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> { saves.save(request,command()); throw new IllegalStateException("later failure"); })).isInstanceOf(IllegalStateException.class);
            assertThat(saves.lookup(request,"demo.order","order","documentNo").committed()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from code_save_business_test",Long.class)).isEqualTo(before);
            return null;
        });
    }
    @Test void fencesLateSaveAndNeverCancelsCommittedResult() {
        as("user","tenant", () -> {
            String request = UUID.randomUUID().toString();
            assertThat(saves.abandon(request,"demo.order","order","documentNo").cancelled()).isTrue();
            assertThatThrownBy(() -> saves.save(request,command())).isInstanceOf(IllegalArgumentException.class);
            verify(rules,never()).saveRuleTree(any());
            String committed = UUID.randomUUID().toString(); saves.save(committed,command());
            assertThat(saves.abandon(committed,"demo.order","order","documentNo").committed()).isTrue(); return null;
        });
    }
    @Test void concurrentRetriesWriteExactlyOnce() throws Exception {
        String request = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(3)) {
            var tasks = java.util.stream.IntStream.range(0,3).mapToObj(i -> pool.submit(() -> as("user","tenant", () -> saves.save(request,command())))).toList();
            var ids = new HashSet<String>(); for (var task : tasks) ids.add(task.get(10,TimeUnit.SECONDS).getId());
            assertThat(ids).hasSize(1); verify(rules,times(1)).saveRuleTree(any());
        }
    }
    @SpringBootConfiguration @EnableAutoConfiguration @EnableMuYunRepositories(basePackageClasses = CodeRuleSaveReceiptDao.class)
    static class Application {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
        @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean CodeRuleService rules() { return mock(CodeRuleService.class); }
        @Bean CodeConfigurationService configuration() { return mock(CodeConfigurationService.class); }
        @Bean CodeRuleSaveService saves(CodeRuleService rules, CodeRuleSaveReceiptDao receipts, CodeConfigurationService configuration, ObjectMapper mapper) { return new CodeRuleSaveService(rules,receipts,configuration,mapper); }
    }
}
