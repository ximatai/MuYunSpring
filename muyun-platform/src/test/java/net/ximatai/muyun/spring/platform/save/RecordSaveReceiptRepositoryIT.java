package net.ximatai.muyun.spring.platform.save;

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

@SpringBootTest(classes = RecordSaveReceiptRepositoryIT.Application.class)
class RecordSaveReceiptRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired RecordSaveReceiptService service;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table if not exists save_receipt_business_test(id varchar(64) primary key)");
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
    private RecordSaveReceipt record(String id) {
        var record = new RecordSaveReceipt(); record.setId(id); record.setVersion(4); return record;
    }
    @Test void commitsReceiptAndRejectsChangedPayloadOrUnauthorizedReplay() {
        String key = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        as("user", "tenant", () -> {
            Supplier<RecordSaveReceipt> write = () -> { calls.incrementAndGet(); return record("saved"); };
            service.execute(key, "demo.record", "create", "payload", write, this::record);
            service.execute(key, "demo.record", "create", "payload", write, this::record);
            assertThat(calls).hasValue(1);
            assertThat(service.lookup(key, "demo.record").getRecordVersion()).isEqualTo(4);
            assertThatThrownBy(() -> service.execute(key, "demo.record", "create", "changed", write, this::record))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.execute(key, "demo.record", "create", "payload", write,
                id -> { throw new SecurityException("permission revoked"); })).isInstanceOf(SecurityException.class);
            return null;
        });
        assertThat(as("other", "tenant", () -> service.lookup(key, "demo.record"))).isNull();
        assertThat(as("user", "other", () -> service.lookup(key, "demo.record"))).isNull();
    }
    @Test void rollsBackBusinessWriteAndReceiptTogether() {
        String key = UUID.randomUUID().toString();
        as("user", "tenant", () -> {
            assertThatThrownBy(() -> service.execute(key, "demo.record", "create", "payload", () -> {
                jdbc.update("insert into save_receipt_business_test values (?)", key);
                throw new IllegalArgumentException("domain rejected");
            }, this::record)).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("select count(*) from save_receipt_business_test where id=?", Integer.class, key)).isZero();
            assertThat(service.lookup(key, "demo.record")).isNull();
            return null;
        });
    }
    @Test void serializesConcurrentRetriesAcrossTransactions() throws Exception {
        String key = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> as("user", "tenant", () ->
                service.execute(key, "demo.record", "create", "payload", () -> {
                    calls.incrementAndGet();
                    jdbc.update("insert into save_receipt_business_test values (?)", key);
                    return record(key);
                }, this::record)))).toList();
            for (var task : tasks) assertThat(task.get(10, TimeUnit.SECONDS).getId()).isEqualTo(key);
        }
        assertThat(calls).hasValue(1);
        assertThat(as("user", "tenant", () -> service.lookup(key, "demo.record"))).isNotNull();
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = RecordSaveReceiptDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean RecordSaveReceiptService receipts(RecordSaveReceiptDao dao) { return new RecordSaveReceiptService(dao); }
    }
}
