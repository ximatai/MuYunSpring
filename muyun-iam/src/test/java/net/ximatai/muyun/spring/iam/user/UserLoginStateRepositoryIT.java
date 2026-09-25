package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.EntityMetaResolver;
import net.ximatai.muyun.database.core.orm.SimpleEntityManager;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.VersionedRecordMutation;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.schema.PlatformEntityManagers;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = UserLoginStateRepositoryIT.Application.class)
class UserLoginStateRepositoryIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired UserAccountDao dao;
    @Autowired UserAccountService service;

    @Test
    void concurrentFailuresKeepEveryIncrementAndConcurrentCredentialChanges() throws Exception {
        UserAccount user = insertUser("tenant-login-concurrent");
        int workers = 6;
        int writes = 12;
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(workers + 1)) {
            ArrayList<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                futures.add(executor.submit(() -> {
                    await(start);
                    try (var ignored = TenantContext.use(user.getTenantId())) {
                        for (int i = 0; i < writes; i++) service.recordLoginFailure(user, Instant.now());
                    }
                }));
            }
            futures.add(executor.submit(() -> {
                await(start);
                VersionedRecordMutation.update(dao, () -> dao.findById(user.getId()), latest -> {
                    latest.setPasswordHash("changed-password-hash");
                    latest.setPasswordStatus(PasswordStatus.RESET_REQUIRED);
                });
            }));
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
        UserAccount stored = dao.findById(user.getId());
        assertThat(stored.getFailedLoginCount()).isEqualTo(workers * writes);
        assertThat(stored.getVersion()).isEqualTo(workers * writes + 1);
        assertThat(stored.getPasswordHash()).isEqualTo("changed-password-hash");
        assertThat(stored.getPasswordStatus()).isEqualTo(PasswordStatus.RESET_REQUIRED);
    }

    @Test
    void successResetsFailuresAndStateWritesStayWithinTenantWithoutLoginIdentity() {
        UserAccount user = insertUser("tenant-login-owner");
        Instant time = Instant.parse("2026-09-24T12:00:00Z");
        try (var ignored = TenantContext.use("tenant-login-other")) {
            service.recordLoginFailure(user, time);
            service.recordLoginSuccess(user.getId(), time, "other", "other");
        }
        assertThat(dao.findById(user.getId()).getVersion()).isZero();
        try (var ignored = TenantContext.use(user.getTenantId())) {
            service.recordLoginFailure(user, time);
            service.recordLoginFailure(user, time);
            service.recordLoginSuccess(user.getId(), time, "127.0.0.1", "test-browser");
        }
        UserAccount stored = dao.findById(user.getId());
        assertThat(stored.getFailedLoginCount()).isZero();
        assertThat(stored.getVersion()).isEqualTo(3);
        assertThat(stored.getLastLoginAt()).isEqualTo(time);
        assertThat(stored.getLastLoginIp()).isEqualTo("127.0.0.1");
        assertThat(stored.getPasswordHash()).isEqualTo("original-hash");
    }

    private UserAccount insertUser(String tenant) {
        UserAccount user = new UserAccount();
        user.setUsername(UUID.randomUUID().toString());
        user.setTitle(user.getUsername());
        user.setPasswordHash("original-hash");
        user.setFailedLoginCount(0);
        user.setTenantId(tenant);
        EntityLifecycle.prepareInsert(user, Instant.now());
        dao.insert(user);
        return user;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("concurrency barrier timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = UserAccountDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return org.springframework.boot.jdbc.DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean EntityMetaResolver entityMetaResolver() { return PlatformEntityManagers.entityMetaResolver(); }
        @Bean SimpleEntityManager simpleEntityManager(IDatabaseOperations<?> database, EntityMetaResolver resolver) {
            return PlatformEntityManagers.simpleEntityManager(database, resolver);
        }
        @Bean UserAccountService userAccountService(UserAccountDao dao) {
            return UserAccountServiceTestFactory.create(dao, tenant -> {}, new PasswordHashingService());
        }
    }
}
