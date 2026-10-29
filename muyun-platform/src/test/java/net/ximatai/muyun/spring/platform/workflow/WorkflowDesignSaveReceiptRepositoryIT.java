package net.ximatai.muyun.spring.platform.workflow;

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

@SpringBootTest(classes = WorkflowDesignSaveReceiptRepositoryIT.Application.class)
class WorkflowDesignSaveReceiptRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired WorkflowDesignSaveReceiptService service;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table if not exists workflow_receipt_business_test(id varchar(64) primary key)");
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
    private WorkflowVersion record(String id) {
        var record = new WorkflowVersion(); record.setId(id); record.setVersion(4); return record;
    }
    @Test void commitsReceiptAndRejectsChangedPayloadOrUnauthorizedReplay() {
        String key = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        as("user", "tenant", () -> {
            Supplier<WorkflowVersion> write = () -> { calls.incrementAndGet(); return record("saved"); };
            service.execute(key, "definition", "version", "payload", write, () -> record("saved"));
            service.execute(key, "definition", "version", "payload", write, () -> record("saved"));
            assertThat(calls).hasValue(1);
            assertThat(service.lookup(key, "definition", "version").savedVersion()).isEqualTo(4);
            assertThatThrownBy(() -> service.execute(key, "definition", "version", "changed", write, () -> record("saved")))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.execute(key, "definition", "version", "payload", write,
                () -> { throw new SecurityException("permission revoked"); })).isInstanceOf(SecurityException.class);
            return null;
        });
        assertThat(as("other", "tenant", () -> service.lookup(key, "definition", "version"))).extracting(WorkflowDesignSaveReceiptService.Result::committed).isEqualTo(false);
        assertThat(as("user", "other", () -> service.lookup(key, "definition", "version"))).extracting(WorkflowDesignSaveReceiptService.Result::committed).isEqualTo(false);
    }
    @Test void rollsBackBusinessWriteAndReceiptTogether() {
        String key = UUID.randomUUID().toString();
        as("user", "tenant", () -> {
            assertThatThrownBy(() -> service.execute(key, "definition", "version", "payload", () -> {
                jdbc.update("insert into workflow_receipt_business_test values (?)", key);
                throw new IllegalArgumentException("domain rejected");
            }, () -> record("saved"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("select count(*) from workflow_receipt_business_test where id=?", Integer.class, key)).isZero();
            assertThat(service.lookup(key, "definition", "version")).extracting(WorkflowDesignSaveReceiptService.Result::committed).isEqualTo(false);
            return null;
        });
    }
    @Test void outerRollbackRemovesTheReceiptAndBusinessWrite() {
        String key = UUID.randomUUID().toString();
        as("user", "tenant", () -> {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                service.execute(key, "definition", "version", "payload", () -> {
                    jdbc.update("insert into workflow_receipt_business_test values (?)", key);
                    return record(key);
                }, () -> record(key));
                throw new IllegalStateException("later aggregate failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(service.lookup(key, "definition", "version").committed()).isFalse();
            assertThat(jdbc.queryForObject("select count(*) from workflow_receipt_business_test where id=?", Integer.class, key)).isZero();
            return null;
        });
    }
    @Test void serializesConcurrentRetriesAcrossTransactions() throws Exception {
        String key = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> as("user", "tenant", () ->
                service.execute(key, "definition", "version", "payload", () -> {
                    calls.incrementAndGet();
                    jdbc.update("insert into workflow_receipt_business_test values (?)", key);
                    return record(key);
                }, () -> record(key))))).toList();
            for (var task : tasks) assertThat(task.get(10, TimeUnit.SECONDS).getId()).isEqualTo(key);
        }
        assertThat(calls).hasValue(1);
        assertThat(as("user", "tenant", () -> service.lookup(key, "definition", "version"))).extracting(WorkflowDesignSaveReceiptService.Result::committed).isEqualTo(true);
    }
    @Test void explicitAbandonFencesLateRequestsButNeverRevertsCommittedSaves() {
        as("user", "tenant", () -> {
            String key = UUID.randomUUID().toString();
            assertThat(service.abandon(key, "definition", "version").cancelled()).isTrue();
            assertThatThrownBy(() -> service.execute(key, "definition", "version", "payload",
                    () -> { throw new AssertionError("late request must not write"); }, () -> record(key)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(service.lookup(key, "definition", "version").committed()).isFalse();
            String committed = UUID.randomUUID().toString();
            service.execute(committed, "definition", "version", "payload", () -> record(committed), () -> record(committed));
            assertThat(service.abandon(committed, "definition", "version").committed()).isTrue();
            assertThat(service.lookup(committed, "definition", "other-version").committed()).isFalse();
            return null;
        });
    }

    @Test void saveRacingAbandonHasExactlyOneDurableOutcome() throws Exception {
        String key = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var write = pool.submit(() -> {
                start.await();
                return as("user", "tenant", () -> {
                    try {
                        service.execute(key, "definition", "version", "payload", () -> {
                            calls.incrementAndGet();
                            jdbc.update("insert into workflow_receipt_business_test values (?)", key);
                            return record(key);
                        }, () -> record(key));
                        return true;
                    } catch (IllegalArgumentException cancelled) { return false; }
                });
            });
            var cancel = pool.submit(() -> {
                start.await();
                return as("user", "tenant", () -> service.abandon(key, "definition", "version"));
            });
            try {
                start.countDown();
                boolean written = write.get(10, TimeUnit.SECONDS);
                var result = cancel.get(10, TimeUnit.SECONDS);
                assertThat(result.committed()).isEqualTo(written);
                assertThat(result.cancelled()).isEqualTo(!written);
                assertThat(calls).hasValue(written ? 1 : 0);
                assertThat(jdbc.queryForObject("select count(*) from workflow_receipt_business_test where id=?", Integer.class, key))
                        .isEqualTo(written ? 1 : 0);
            } finally { start.countDown(); }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = WorkflowDesignSaveReceiptDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean WorkflowDesignSaveReceiptService receipts(WorkflowDesignSaveReceiptDao dao) { return new WorkflowDesignSaveReceiptService(dao); }
    }
}
