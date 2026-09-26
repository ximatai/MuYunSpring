package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ApplicationConstructionPlanServiceIT.Application.class)
class ApplicationConstructionPlanServiceIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired ApplicationConstructionPlanService service;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
            public void lock(String scope, String key) {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            }
        });
    }
    @AfterEach void cleanup() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }

    private <T> T as(String user, String tenant, Supplier<T> operation) {
        try (var identity = CurrentUserContext.use(CurrentUser.tenantUser(user, user, tenant));
             var scope = TenantContext.use(tenant)) { return operation.get(); }
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private ApplicationConstructionPlanContent content(String title) {
        return new ApplicationConstructionPlanContent(title, "管理订单", java.util.List.of("订单录入"), java.util.List.of("结算"),
            java.util.List.of(new ApplicationConstructionPlanContent.BusinessObject("order", "订单", "记录客户订购")),
            java.util.List.of(), java.util.List.of(), java.util.List.of("是否需要审批"), java.util.List.of(),
            java.util.List.of(new ApplicationConstructionPlanContent.Decision("先管理订单", ApplicationConstructionPlanContent.Source.RECOMMENDATION)),
            java.util.List.of("可以记录一张订单"), java.util.List.of());
    }
    private ApplicationConstructionPlanService.ConfirmCommand command(int revision, String title) {
        return new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), revision, content(title));
    }
    @Test void revisionsAreImmutableAndConfirmationRetriesRemainIdempotent() {
        var planId = id(); var first = command(0, "一期");
        as("owner", "tenant", () -> {
            assertThat(service.confirm(planId, first).revision()).isEqualTo(1);
            assertThat(service.confirm(planId, command(1, "二期")).revision()).isEqualTo(2);
            assertThat(service.read(planId).revision()).isEqualTo(2);
            assertThat(service.confirm(planId, first).revision()).isEqualTo(1);
            assertThat(service.confirmation(planId, first.requestId()).content().title()).isEqualTo("一期");
            assertThat(service.history(planId)).extracting(ApplicationConstructionPlanService.Snapshot::revision).containsExactly(2, 1);
            assertThatThrownBy(() -> service.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(first.requestId(), 0, content("篡改")))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.confirm(planId, command(1, "过期"))).hasMessageContaining("方案已被修改");
            assertThatThrownBy(() -> service.confirm(planId, command(2, "二期"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.read(planId).constructionStatus()).isEqualTo("NOT_STARTED");
            return null;
        });
        as("other", "tenant", () -> {
            assertThat(service.list()).noneMatch(plan -> plan.planId().equals(planId));
            assertThatThrownBy(() -> service.read(planId)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> service.confirm(planId, first)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> service.history(planId)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            return null;
        });
        as("owner", "other", () -> {
            assertThatThrownBy(() -> service.confirmation(planId, first.requestId())).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            return null;
        });
    }
    @Test void concurrentRevisionsHaveExactlyOneWinner() throws Exception {
        String planId = id(); var winners = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> as("owner", "tenant", () -> {
                try { service.confirm(planId, command(0, "候选" + i)); winners.incrementAndGet(); }
                catch (net.ximatai.muyun.spring.ability.action.BusinessException exception) { /* stale version */ }
                return null;
            }))).toList();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(winners).hasValue(1);
        assertThat(as("owner", "tenant", () -> service.history(planId))).hasSize(1);
    }
    @Test void outerRollbackDiscardsHeadAndHistoryAndPageTenantDoesNotOwnPlan() {
        String planId = id(); var command = command(0, "一期");
        as("owner", "tenant", () -> {
            new TransactionTemplate(transactions).execute(status -> {
                service.confirm(planId, command); status.setRollbackOnly(); return null;
            });
            assertThat(service.confirmation(planId, command.requestId())).isNull();
            try (var pageTenant = TenantContext.use("different-page-tenant")) { service.confirm(planId, command); }
            assertThat(service.list()).anyMatch(plan -> plan.planId().equals(planId));
            return null;
        });
        assertThatThrownBy(() -> service.list()).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = ApplicationConstructionPlanDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean ApplicationConstructionPlanService plans(ApplicationConstructionPlanDao dao, ApplicationConstructionPlanRevisionDao revisions, ApplicationConstructionInitializationDao initializations, ApplicationConstructionFieldChangeDao fieldChanges, ApplicationConstructionDeliveryDao deliveries) { return new ApplicationConstructionPlanService(dao, revisions, initializations, fieldChanges, deliveries); }
    }
}
